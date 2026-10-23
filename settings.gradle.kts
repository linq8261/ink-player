// GitHub Actions 上国内镜像不可靠：阿里云 google 镜像实测返回 502，
// 而 Gradle 遇到仓库 5xx 会直接放弃该模块的解析（不会继续试后面的仓库），
// 所以 CI 里只挂官方源；本机（国内）仍走镜像加速。
// 注意：pluginManagement / dependencyResolutionManagement 是独立编译的脚本块，
// 外面定义的 val 在这里不可见（Unresolved reference），条件只能内联。
pluginManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
            // KSP 的插件标记（com.google.devtools.ksp.gradle.plugin）只发布在 Maven Central，
            // Google Maven 上是 404，所以必须留官方 Central 兜底
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            google()
            mavenCentral()
        } else {
            google()
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
            mavenCentral()
        }
    }
}

rootProject.name = "ink-player"
include(":app")
