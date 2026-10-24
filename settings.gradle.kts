// 门禁在仓库根目录直接跑 `gradle :app:compileDebugKotlin`（单机版 Kotlin 编译），
// 而 Gradle 构建本体在 mobile/ 子目录 —— 根目录没有 settings 文件时，Gradle 连
// 任务都解析不了，直接报 "does not contain a Gradle build"。这里把 :app 重定向
// 到 mobile/app：任务路径与在 mobile/ 里构建完全一致，mobile 的脚本一行不用抄。
pluginManagement {
    repositories {
        // 与 mobile/settings.gradle.kts 同一套源：KSP 这类只在 Gradle Plugin Portal
        // 上架的插件，镜像一旦返回错误内容，Gradle 当场判"解析不到"、不会再往后翻
        // 官方源（CI 上踩过这个坑）。
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
    // :app 的构建脚本用 alias(libs...) 引插件与依赖，catalog 默认只在构建根的
    // gradle/ 下找 —— 这里的构建根是仓库根本尊，得显式指回 mobile 的那份
    versionCatalogs {
        create("libs") {
            from(files("mobile/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "renovation-procurement"

include(":app")
project(":app").projectDir = file("mobile/app")
