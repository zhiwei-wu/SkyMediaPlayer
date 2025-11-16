# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("skymediaplayer")

message("CMAKE_SOURCE_DIR 路径：${CMAKE_SOURCE_DIR}")

# 设置预编译库目录
set(PREBUILT_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# 添加 SDL3 动态库
include_directories(${CMAKE_SOURCE_DIR}/sdl/include)
add_library(SDL3 SHARED IMPORTED)
set_target_properties(SDL3 PROPERTIES
        IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libSDL3.so
        IMPORTED_NO_SONAME ON
)

# 添加 FFmpeg 动态库
add_library(skyffmpeg SHARED IMPORTED)
set_target_properties(skyffmpeg PROPERTIES
        IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libskyffmpeg.so
        IMPORTED_NO_SONAME ON
)

include_directories(${CMAKE_SOURCE_DIR}/ffmpeg/include)
include_directories(${CMAKE_SOURCE_DIR}/include)
include_directories(${CMAKE_SOURCE_DIR}/ffplay)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        ffplay/sky_ffplay.c
        ffplay/ffplay.c
        ffplay/cmdutils.c
        ffplay/opt_common.c
        player/skymediaplayer.cpp
        player/skyrenderer.cpp
        player/sky_egl2_renderer_yuv420p.cpp
        player/sky_egl2_renderer_nv12.cpp
        player/sky_egl2_renderer_nv21.cpp
        player/sky_egl2_renderer_rgba.cpp
        player/sky_egl2_renderer_yuv422p.cpp
        player/skyaudio.cpp
        player/sky_msg_queue.cpp
        skymediaplayer_jni.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        PRIVATE
        SDL3
        skyffmpeg
        android
        log
        OpenSLES
        EGL
        GLESv2)