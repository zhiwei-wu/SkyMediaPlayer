plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Versions are supplied explicitly for release builds; debug keeps its old defaults.
val apkVersionCode = providers.gradleProperty("skyVersionCode")
val apkVersionName = providers.gradleProperty("skyVersionName")
val releaseVersionCode = apkVersionCode.orNull?.toIntOrNull()

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
        versionCode = releaseVersionCode ?: 1
        versionName = apkVersionName.orElse("1.0").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        // 自动化测试编译选项：通过 BuildConfig.AUTO_TEST_ENABLED 控制
        buildConfigField("boolean", "AUTO_TEST_ENABLED", skyAutoTestEnabled)
    }

    buildTypes {
        debug {
            // Keep diagnostic builds beside the user installation.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

// Compress native libraries only in release APKs. Android extracts them at install time.
// This trades installation space for a smaller direct-download APK.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

val validateReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Validate explicit APK versions and current-source release settings."
    doLast {
        require(releaseVersionCode != null && releaseVersionCode in 2..2100000000) {
            "Release requires -PskyVersionCode=<integer greater than all published APKs (at least 2)>"
        }
        require(apkVersionName.orNull?.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+([.-][A-Za-z0-9.-]+)?")) == true) {
            "Release requires -PskyVersionName=<version, e.g. 1.6.1-preparation>"
        }
        require(android.buildTypes.getByName("release").signingConfig == null) {
            "Preparation must remain unsigned; signing is a separate maintainer step."
        }
        require(skyDependencyMode == "project") {
            "Release APK must use current source: -PskyDependencyMode=project"
        }
        require(skyAutoTestEnabled == "false") {
            "Release APK must disable automatic test startup: -PskyAutoTestEnabled=false"
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseConfiguration)
}

// Deliberately unsigned: signing happens on the maintainer's machine with the existing key.
// No signing credentials are loaded by this build.
val prepareReleaseApk by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Build and stage an unsigned APK, R8 mapping and APK metadata for local signing."
    dependsOn("assembleRelease")
    into(layout.buildDirectory.dir("release-apk"))
    from(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")) {
        rename { "SkyMediaPlayer-${apkVersionName.get()}-arm64-v8a-unsigned.apk" }
    }
    from(layout.buildDirectory.file("outputs/apk/release/output-metadata.json"))
    from(layout.buildDirectory.file("outputs/mapping/release/mapping.txt"))
    doFirst {
        check(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile.isFile) {
            "Unsigned release APK missing; do not distribute a debug or stale APK."
        }
    }
}
