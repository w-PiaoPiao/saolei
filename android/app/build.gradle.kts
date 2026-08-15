import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wpiaopiao.saolei"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wpiaopiao.saolei"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
            isMinifyEnabled = false
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
}
