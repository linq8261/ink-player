# M3U8 转 MP4：现状与问题

> 状态：功能可用，能正常转出 MP4；**性能优化需求已搁置**，本文记录当前实现方式与已知问题。

## 现状

实现位置：`app/src/main/java/com/ink/player/feature/wukong/`

| 文件 | 职责 |
|------|------|
| `M3u8Converter.kt` | 转码主流程（解封装 → 解码 → 缩放 → 重编码 → 合成） |
| `YuvScaler.kt` | YUV 提取、双线性缩放、打包为编码器输入格式 |

**当前方案：全量重编码，不是 remux。**

每个 TS 分片独立解封装 + 独立解码（规避分片间规格切换），视频统一缩放到 720p/24fps 重编码，音频重编码为 AAC，最后用 `MediaMuxer` 合成 MP4。段内保留原始 PTS，段间按"内容时长 + 一帧"累加偏移，重建连续时间轴。

选择重编码而非 remux 的原因：源站 HLS 可能由多个不同规格（如 720p/25fps + 1080p/30fps）的分片拼接而成，纯 remux 无法处理规格切换。

---

## 一、性能问题（最主要）

### 1. 纯 Kotlin 的 YUV 处理链路是最大瓶颈

`YuvScaler` 承担了 `extractToI420` → `scale` → `packTo` 三步，占整体耗时约 **50~70%**。

- **每帧约 5MB 临时内存分配**（按 1080p 源 → 720p 输出计算）：

  | 步骤 | 分配量 |
  |------|--------|
  | `extractToI420`（Y + U + V） | ≈ 3.11 MB |
  | `scale`（目标 Y + U + V） | ≈ 1.38 MB |
  | `packTo`（NV12 交错临时数组） | ≈ 0.46 MB |
  | **合计** | **≈ 4.95 MB / 帧** |

  24fps 下约 **120MB/秒** 的堆分配，GC 频繁触发。

- **双线性缩放是逐像素纯 CPU 计算**：目标 Y+U+V 共约 138 万像素/帧，每像素 4 次读 + 约 8 次算术，单帧约 1800 万次操作。没有 SIMD、没有 NEON、没有 RenderScript。
- **单帧 CPU 处理粗估 35~90ms**，已超过 24fps 的实时预算（41.7ms/帧）。

### 2. 管线完全串行

`staged` 只暂存 1 帧，导致 extractor → decoder → scale → encoder 四步严格串行、互不重叠。总耗时是各环节**相加**而非取 max。

### 3. 每分片重建 decoder

每个 TS 分片都新建 + 释放视频和音频两个 `MediaCodec`（create/configure/start/stop/release）。分片数越多，这部分固定开销累计越大。

### 4. 码率偏高

`VIDEO_BITRATE = 8_000_000`。720p@24fps 通常 2~4Mbps 已足够，8Mbps 放大了编码器熵编码压力和写盘量。

### 5. 粗估结论

1 小时视频（源 1080p/30fps）转换约需 **1~1.5 小时**。

---

## 二、正确性与兼容性问题

### 1. muxer 延迟初始化是 workaround

部分设备的编码器**必须收到真实输入后**才会输出 `INFO_OUTPUT_FORMAT_CHANGED`，原先"start 后死等 format"的写法在这类设备上会卡死（且死等循环不响应取消，导致取消按钮无反应）。

现用 `MuxerState` + `PendingSample` 暂存 + `ensureStarted()` 兜底绕开，能跑通，但逻辑偏复杂，属于兼容性 workaround 而非根本解决。

### 2. 音频参数只探测第一片，不一致直接失败

`probeFirstAudio` 只解析第一片的采样率/声道，后续分片若不同，`processAudioSegment` 直接抛异常 **"音频格式不一致，暂不支持重采样"**，整个转换失败。未实现重采样。

### 3. 音视频时间戳各自独立累加，可能累积漂移

- 视频：`offsets.video +=（段内容时长）+ 一帧`
- 音频：`offsets.audio +=（段内容时长）+ 1024 sample 帧长`

两条轨独立累加，长视频上可能逐渐产生音画不同步。

### 4. 帧率归一化是简单采样

当前逻辑是 `pts >= nextOutPts` 时才取一帧。源高于 24fps 时正常丢帧；源低于 24fps 时 PTS 网格会超前，未做特殊处理。

### 5. 轨道存在性只探测第一片

`hasVideo` / `hasAudio` 来自第一片探测，若后续分片轨道构成不同，muxer 启动判定可能出错，靠 `ensureStarted()` 兜底。

---

## 三、功能限制

| 限制 | 说明 |
|------|------|
| 输出规格硬编码 | 固定 720p / 24fps / 8Mbps，1080p 源会被降级，用户无法选择 |
| 音频阶段进度不推进 | `processAudioSegment` 不回调 `onSegmentProgress`，进度条在音频处理期间停滞 |
| 分片整体载入内存 | `readSegment` 整片读入 `ByteArray`，加密时 `decryptSegment` 再复制一份明文，内存占用随分片大小线性增长 |

---

## 四、已知的优化方向（未实施）

按性价比排序，供后续参考：

| 方案 | 思路 | 预期收益 | 复杂度 |
|------|------|---------|--------|
| **A. 智能路径** | 先探测所有分片规格，全部一致就走纯 remux（只修 PTS 偏移），不一致才重编码 | 10~50 倍（多数源适用） | 中 |
| **B. YuvScaler 内存池化** | 复用每帧 5MB 临时数组、缓存缩放映射表、码率降至 3~4Mbps | 30~60% | 低 |
| **C. Surface + GL 中转** | 解码输出到 SurfaceTexture，GL 渲染到 encoder input Surface，彻底删掉 `YuvScaler` | 3~10 倍 | 高 |
| **D. 跨分片复用 decoder** | 段间用 `flush()` 而非重建 codec | 分片多时明显 | 中 |
| **E. NDK/NEON 重写 YUV 链路** | C++ + NEON 实现 extract/scale/pack，保留内存复用 | 5~10 倍（CPU 部分） | 高 |

> 注：时间轴重置（分片 PTS 各自从 0 开始）其实用现有的 `offsets` 加偏移即可解决，**不是必须重编码的理由**；真正必须重编码的只有"分片间规格不一致"这一种情况。这也是方案 A 收益最大的原因。
