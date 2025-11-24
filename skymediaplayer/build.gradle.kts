import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "imt.zw.jxmediaplayer"
    compileSdk = 35

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
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
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// 屏蔽 testClasses 任务，避免构建错误
tasks.register("testClasses") {
    description = "Dummy testClasses task for Android Library compatibility"
    group = "verification"
    doLast {
        println("testClasses task is not applicable for Android Library projects")
    }
}

// 如果存在 testClasses 任务的引用，将其重定向到 Android 的测试任务
tasks.whenTaskAdded {
    if (name == "testClasses") {
        dependsOn("testDebugUnitTest")
    }
}

// Maven 发布配置（用于 JitPack）
afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.github.zhiwei-wu"
                artifactId = "skymediaplayer"
                version = "1.0.0"

                pom {
                    name.set("SkyMediaPlayer")
                    description.set("A high-performance Android media player based on FFmpeg")
                    url.set("https://github.com/zhiwei-wu/SkyPlayer")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("zhiwei-wu")
                            name.set("Wu Zhiwei")
                            email.set("zhiwei.wu@foxmail.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/zhiwei-wu/SkyPlayer.git")
                        developerConnection.set("scm:git:ssh://github.com/zhiwei-wu/SkyPlayer.git")
                        url.set("https://github.com/zhiwei-wu/SkyPlayer")
                    }
                }
            }
        }
    }
}