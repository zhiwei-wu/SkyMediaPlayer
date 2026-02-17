# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name.
project("skymediaplayer")

message("CMAKE_SOURCE_DIR 路径：${CMAKE_SOURCE_DIR}")

# 根据 Gradle 传入的 SKY_BUILD_TYPE 选择预编译库目录
# release 使用 jniLibs-release，debug 使用 jniLibs-debug
if(SKY_BUILD_TYPE STREQUAL "debug")
    set(PREBUILT_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs-debug/${ANDROID_ABI})
    message("使用 debug 预编译库: ${PREBUILT_LIBS_DIR}")
else()
    set(PREBUILT_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs-release/${ANDROID_ABI})
    message("使用 release 预编译库: ${PREBUILT_LIBS_DIR}")
endif()

# ===== 预编译动态库 =====

# SDL3
include_directories(${CMAKE_SOURCE_DIR}/sdl/include)
add_library(SDL3 SHARED IMPORTED)
set_target_properties(SDL3 PROPERTIES
        IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libSDL3.so
        IMPORTED_NO_SONAME ON
)

# FFmpeg（子库静态合并为单一 so）
add_library(skyffmpeg SHARED IMPORTED)
set_target_properties(skyffmpeg PROPERTIES
        IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libskyffmpeg.so
        IMPORTED_NO_SONAME ON
)

# OpenSSL（独立动态库）
add_library(skyssl SHARED IMPORTED)
set_target_properties(skyssl PROPERTIES
        IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libskyssl.so
        IMPORTED_NO_SONAME ON
)

# whisper.cpp 已静态链接到 libskyffmpeg.so，不再需要独立动态库

# ===== 头文件目录 =====
include_directories(${CMAKE_SOURCE_DIR}/ffmpeg/include)
include_directories(${CMAKE_SOURCE_DIR}/include)
include_directories(${CMAKE_SOURCE_DIR}/ffplay)
include_directories(${CMAKE_SOURCE_DIR}/player)
include_directories(${CMAKE_SOURCE_DIR}/player/renderer)
include_directories(${CMAKE_SOURCE_DIR}/player/decoder)

# ===== 主库源文件 =====
add_library(${CMAKE_PROJECT_NAME} SHARED
        ffplay/ffplay.c
        ffplay/cmdutils.c
        ffplay/opt_common.c
        player/skymediaplayer.cpp
        player/skyaudio.cpp
        player/sky_msg_queue.cpp
        player/renderer/skyrenderer.cpp
        player/renderer/sky_egl2_renderer_yuv420p.cpp
        player/renderer/sky_egl2_renderer_nv12.cpp
        player/renderer/sky_egl2_renderer_nv21.cpp
        player/renderer/sky_egl2_renderer_rgba.cpp
        player/renderer/sky_egl2_renderer_yuv422p.cpp
        player/renderer/sky_vk_renderer.cpp
        player/decoder/sky_hw_decoder.cpp
        player/decoder/sky_mediacodec_decoder.cpp
        skymediaplayer_jni.cpp)

# ===== 链接库 =====
target_link_libraries(${CMAKE_PROJECT_NAME}
        PRIVATE
        SDL3
        skyffmpeg
        skyssl
        android
        log
        OpenSLES
        EGL
        GLESv2
        vulkan
        mediandk)