import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 版本默认值唯一来源：仓库根 VERSION（如 1.3.0 → versionName=1.3.0、versionCode=10300）。
// CI 发布流程仍可用 -PversionName/-PversionCode 注入（tag 优先于 VERSION）。
val repoVersion: String? = rootProject.file("../VERSION")
    .takeIf { it.isFile }
    ?.readText()
    ?.trim()
    ?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
val repoVersionParts: List<Int>? = repoVersion?.split('.')?.map { it.toInt() }
val defaultVersionName: String = repoVersion ?: "1.0.0"
val defaultVersionCode: Int = repoVersionParts?.let { it[0] * 10_000 + it[1] * 100 + it[2] } ?: 1

android {
    namespace = "com.wpiaopiao.saolei"
    compileSdk = 34

    // 版本号由 CI 发布流程按 tag 注入（android-v1.2.1 → -PversionName=1.2.1 -PversionCode=10201），
    // 本地构建回落到仓库根 VERSION；About 页显示运行时 versionName，不再硬编码。
    val injectedVersionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
    val injectedVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()

    defaultConfig {
        applicationId = "com.wpiaopiao.saolei"
        minSdk = 26
        targetSdk = 34
        versionCode = injectedVersionCode ?: defaultVersionCode
        versionName = injectedVersionName ?: defaultVersionName
    }

    // 正式版签名：优先读本机 keystore/keystore.properties（不入库），
    // 否则读 CI 环境变量（ANDROID_KEYSTORE_BASE64 等 secrets）。
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore/keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { load(propsFile.inputStream()) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else {
                val base64 = System.getenv("ANDROID_KEYSTORE_BASE64")
                if (!base64.isNullOrEmpty()) {
                    val ks = rootProject.file(
                        System.getenv("ANDROID_KEYSTORE_PATH") ?: "keystore/release.keystore"
                    )
                    if (!ks.exists()) {
                        ks.parentFile?.mkdirs()
                        ks.writeBytes(Base64.getDecoder().decode(base64))
                    }
                    storeFile = ks
                    storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
                    keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
                    keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
                }
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 压缩/混淆：纯 Kotlin + 无反射，默认规则即可；
            // 如后续引入反射框架，在 proguard-rules.pro 补 keep 规则
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    // app 层纯逻辑（GameText/GameClock/OverlayHitRegistry 等）单元测试
    testImplementation(libs.junit)
}
