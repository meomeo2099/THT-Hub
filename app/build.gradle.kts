plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.tht.hub"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.tht.hub"
        minSdk = 28          // Android 9 – đầu xe Coolray 2025 (SX11-LA)
        targetSdk = 28
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // Ký bằng khóa platform của firmware (chứng thư c8a2e9bc…). Đường dẫn và mật khẩu lấy từ biến môi trường,
    // không ghi vào repo. Thiếu thì dùng khóa debug (khi đó quyền hệ thống sẽ không được cấp).
    val signFile = System.getenv("THT_SIGN_STORE_FILE")?.let { file(it) }
    signingConfigs {
        create("tht") {
            if (signFile != null && signFile.exists()) {
                storeFile = signFile
                storePassword = System.getenv("THT_SIGN_STORE_PASSWORD")
                keyAlias = System.getenv("THT_SIGN_KEY_ALIAS")
                keyPassword = System.getenv("THT_SIGN_KEY_PASSWORD") ?: System.getenv("THT_SIGN_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signFile != null && signFile.exists())
                signingConfigs.getByName("tht") else signingConfigs.getByName("debug")
        }
        debug {
            if (signFile != null && signFile.exists()) signingConfig = signingConfigs.getByName("tht")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    // Driver USB-serial cho bộ thu GPS: CDC-ACM (u-blox), Prolific PL2303, SiLabs CP210x, FTDI, CH340.
    // Bản 3.9.0 (MIT): bản mới hơn đòi compileSdk 35.
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
    testImplementation("junit:junit:4.13.2")
}

android.applicationVariants.all {
    val variant = this
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "THT-Hub-${variant.versionName}-${variant.buildType.name}.apk"
    }
}
