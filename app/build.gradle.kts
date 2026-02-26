plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 读取依赖配置
val skyDependencyMode: String by project
val skyAarBuildType: String by project
val skyAarVersion: String by project
val skyAutoTestEnabled: String by project

android {
    namespace = "imt.skymediaplayer.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "imt.skymediaplayer.demo"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        // 自动化测试编译选项：通过 BuildConfig.AUTO_TEST_ENABLED 控制
        buildConfigField("boolean", "AUTO_TEST_ENABLED", skyAutoTestEnabled)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        prefab = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.games.activity)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // SkyMediaPlayer 依赖：通过 gradle.properties 中的 SKY_DEPENDENCY_MODE 切换
    // - "project": 项目源码依赖，用于开发调试
    // - "aar":     JitPack AAR 依赖，用于集成使用
    if (skyDependencyMode == "project") {
        implementation(project(":skymediaplayer"))
    } else {
        // 通过 SKY_AAR_BUILD_TYPE 切换 release / debug 版本
        val artifactId = if (skyAarBuildType == "debug") "skymediaplayer-debug" else "skymediaplayer"
        implementation("com.github.zhiwei-wu.SkyMediaPlayer:$artifactId:$skyAarVersion")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}