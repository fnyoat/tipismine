import java.util.Properties

// 提交数派生构建号：每次 new commit → versionCode +1，且保证「提交数即构建号」。
// 构建时按 HEAD 统计提交数；取不到 git 环境时回退 1，不影响本地/CI 无 .git 场景。
fun gitCommitCount(): Int {
    return try {
        val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
}

plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.fnyoat.tipismine"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.fnyoat.tipismine"
        minSdk = 23
        targetSdk = 34
        versionCode = gitCommitCount()
        versionName = "1.0.0"
    }

    // 两个风味各自产出独立 APK，共用大部分源码：
    //  - legacy：老 Xposed / 老版 LSPosed / Android 7 兼容（XSharedPreferences + chmod）。
    //  - modern：新版 LSPosed（官方 libxposed XRemotePreferences + 服务绑定判定激活）。
    flavorDimensions += "variant"
    productFlavors {
        create("legacy") {
            dimension = "variant"
            versionNameSuffix = "-legacy"
        }
        create("modern") {
            dimension = "variant"
            versionNameSuffix = "-modern"
            // libxposed service 库要求 minSdk ≥ 26；legacy 保持 23 兼容老机。
            minSdk = 26
        }
    }

    signingConfigs {
        // CI 会生成 xposed/signing.properties（不进仓库）；本地未配置则用 debug 签名。
        val sp = rootProject.file("xposed/signing.properties")
        if (sp.exists()) {
            val props = Properties().apply {
                sp.inputStream().use { load(it) }
            }
            create("ci") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val sc = signingConfigs.findByName("ci")
            // 无签名 secrets 时退回 debug 签名，保证产出的 APK 始终可直接安装。
            signingConfig = sc ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// HTTPS 自签名证书：构建时由 keytool 生成到 assets，供 https 协议做 TLS。
// 每个构建产物带各自的证书（不签发给特定域名/主机），仓库不存放私钥。
val generateHttpsCert = tasks.register<Exec>("generateHttpsCert") {
    // 资产已存在（如本地已生成过）则跳过，不重复执行 keytool。
    onlyIf { !file("src/main/assets/tipismine_https.p12").exists() }
    doFirst {
        // CI 全新检出时 src/main/assets 目录不存在，先建好，keytool 才能落盘。
        file("src/main/assets").mkdirs()
    }
    commandLine("keytool", "-genkeypair",
        "-alias", "tipismine",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "3650",
        "-storetype", "PKCS12",
        "-keystore", file("src/main/assets/tipismine_https.p12").absolutePath,
        "-storepass", "tipismine",
        "-keypass", "tipismine",
        "-dname", "CN=tipismine.local, O=TipIsMine")
}
tasks.named("preBuild") {
    dependsOn(generateHttpsCert)
}

dependencies {
    // 共享：快速 JVM 单元测试
    testImplementation("junit:junit:4.13.2")

    // legacy 风味：老 Xposed API，仅编译期，不打进 APK（框架运行时提供）。
    // 官方坐标 de.robv.android.xposed:api:82（JCenter/Bintray 已下线，经官方 gh-pages Maven 布局获取）
    add("legacyCompileOnly", "de.robv.android.xposed:api:82")

    // modern 风味：官方 libxposed 模块 API（编译期）；服务需打进 APK（UI 进程要运行其类）。
    add("modernCompileOnly", "io.github.libxposed:api:102.0.0")
    add("modernImplementation", "io.github.libxposed:service:102.0.0")
}