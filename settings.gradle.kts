pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // legacy Xposed API 官方坐标 de.robv.android.xposed:api:82，经官方 gh-pages Maven 布局获取
        // （JCenter/Bintray 已下线，JitPack 不再托管 com.github.rovo89:XposedBridge）
        maven { url = uri("https://raw.githubusercontent.com/rovo89/XposedBridge/gh-pages") }
    }
}

rootProject.name = "TipIsMine"
include(":xposed")
