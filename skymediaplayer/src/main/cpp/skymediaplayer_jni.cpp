#include <jni.h>
#include <string>
#include <pthread.h>
#include <android/log.h>

#include "player/skymediaplayer.h"
#include "include/skymediaplayer_interface.h"
#include "logger.h"

extern "C" {
#include "libavutil/log.h"
}

#define JX_MEDIA_PLAYER_CLAZZ "imt/zw/skymediaplayer/player/SkyMediaPlayer"
#define JX_NATIVE_PLAYER_PTR "_nativeMediaPlayer"

static JavaVM* g_jvm = nullptr;



// 线程本地存储key，用于缓存JNIEnv
static pthread_key_t g_env_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;

// 线程退出时的清理函数
static void thread_exit_handler(void* env) {
    if (env && g_jvm) {
        // 线程退出时自动detach
        g_jvm->DetachCurrentThread();
    }
}

// 初始化线程本地存储key
static void create_env_key() {
    pthread_key_create(&g_env_key, thread_exit_handler);
}

// 获取当前线程的JNIEnv，如果需要会自动attach
JNIEnv* getJNIEnv() {
    if (!g_jvm) {
        return nullptr;
    }

    // 确保key已创建
    pthread_once(&g_key_once, create_env_key);

    // 先尝试从线程本地存储获取
    auto* env = static_cast<JNIEnv*>(pthread_getspecific(g_env_key));
    if (env) {
        return env;
    }

    // 尝试从JVM获取当前线程的env
    jint result = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    if (result == JNI_EDETACHED) {
        // 当前线程未附加到JVM，需要附加
        result = g_jvm->AttachCurrentThread(&env, nullptr);
        if (result != JNI_OK) {
            ALOG_E(TAG, "getJNIEnv: Failed to attach thread to JVM");
            return nullptr;
        }
        // 将env存储到线程本地存储中，线程退出时会自动detach
        pthread_setspecific(g_env_key, env);
    } else if (result != JNI_OK) {
        ALOG_E(TAG, "getJNIEnv: Failed to get JNIEnv");
        return nullptr;
    }

    return env;
}

SkyPlayer* asSkyPlayer(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    if (nullptr != clazz) {
        jfieldID ptrField = env->GetFieldID(clazz, JX_NATIVE_PLAYER_PTR, "J");
        if (nullptr != ptrField) {
            return reinterpret_cast<SkyPlayer *>(env->GetLongField(thiz, ptrField));
        }
    }
    ALOG_E(TAG, "%s return nullptr", __func__ );
    return nullptr;
}

// C++层调用Java层postEventFromNative的辅助方法
bool postEventToJava(SkyPlayer* player, int what, int arg1, int arg2, jobject obj) {
    if (nullptr == player || nullptr == g_jvm) {
        ALOG_E(TAG, "postEventToJava: player or g_jvm is null");
        return false;
    }

    // 检查方法管理器是否已初始化
    SkyMediaPlayerMethod& methodManager = player->getMethodManager();
    if (!methodManager.isInitialized()) {
        ALOG_E(TAG, "postEventToJava: method manager not initialized");
        return false;
    }

    // 获取当前线程的JNIEnv（会自动处理attach）
    JNIEnv* env = getJNIEnv();
    if (!env) {
        ALOG_E(TAG, "postEventToJava: Failed to get JNIEnv");
        return false;
    }

    bool success = false;
    do {
        // 获取保存的Java弱全局引用
        jweak weakJavaPlayer = static_cast<jweak>(player->getWeakJavaPlayerPtr());
        if (nullptr == weakJavaPlayer) {
            ALOG_E(TAG, "postEventToJava: weakJavaPlayer is null");
            break;
        }

        // 检查弱引用是否仍然有效
        jobject strongRef = env->NewLocalRef(weakJavaPlayer);
        if (nullptr == strongRef) {
            ALOG_E(TAG, "postEventToJava: weak reference has been garbage collected");
            break;
        }

        // 使用缓存的方法ID调用Java层的postEventFromNative方法
        env->CallStaticVoidMethod(methodManager.getJavaClass(), methodManager.getPostEventFromNative(),
            strongRef, static_cast<jint>(what), static_cast<jint>(arg1), static_cast<jint>(arg2), obj);

        // 检查是否有异常
        if (env->ExceptionCheck()) {
            ALOG_E(TAG, "postEventToJava: Exception occurred when calling postEventFromNative");
            env->ExceptionDescribe();
            env->ExceptionClear();
        } else {
            success = true;
        }

        // 释放局部引用
        env->DeleteLocalRef(strongRef);
    } while (false);
    // 注意：不再需要手动detach，线程退出时会自动处理

    return success;
}

void sky_mediaPlayer_native_setup(JNIEnv *env, jobject thiz) {
    FUNC_TRACE()
    auto* player = createSkyPlayer();
    if (nullptr == player) {
        ALOG_E(TAG, "%s failed to create SkyPlayer", __func__);
        return;
    }

    // 直接对SkyMediaPlayer实例创建弱全局引用，而不是对WeakReference对象
    jweak weakGlobalRef = env->NewWeakGlobalRef(thiz);
    if (nullptr == weakGlobalRef) {
        ALOG_E(TAG, "%s failed to create weak global reference", __func__);
        delete player;
        return;
    }
    setSkyPlayerWeakJavaPlayer(player, weakGlobalRef);

    // 初始化方法管理器
    SkyMediaPlayerMethod& methodManager = player->getMethodManager();
    if (!methodManager.initialize(env, JX_MEDIA_PLAYER_CLAZZ)) {
        ALOG_E(TAG, "%s failed to initialize method manager", __func__);
        env->DeleteWeakGlobalRef(weakGlobalRef);
        delete player;
        return;
    }

    jclass clazz = env->GetObjectClass(thiz);
    if (nullptr == clazz) {
        ALOG_E(TAG, "%s get SkyMediaPlayer fail!", __func__);
        methodManager.cleanup(env);
        env->DeleteWeakGlobalRef(weakGlobalRef);
        delete player;
        return;
    }

    jfieldID ptrField = env->GetFieldID(clazz, JX_NATIVE_PLAYER_PTR, "J");
    if (nullptr == ptrField) {
        ALOG_E(TAG, "%s find _nativePtr fail!", __func__);
        methodManager.cleanup(env);
        env->DeleteWeakGlobalRef(weakGlobalRef);
        delete player;
        return;
    }
    env->SetLongField(thiz, ptrField, reinterpret_cast<jlong>(player));

    ALOG_I(TAG, "%s setup completed with method manager initialized", __func__);
}

// 添加释放SkyPlayer资源的JNI方法
void sky_mediaPlayer_release(JNIEnv *env, jobject thiz) {
    FUNC_TRACE()

    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        return;
    }

    // 清理方法管理器
    player->getMethodManager().cleanup(env);

    // 释放弱全局引用
    jweak weakRef = static_cast<jweak>(player->getWeakJavaPlayerPtr());
    if (weakRef) {
        env->DeleteWeakGlobalRef(weakRef);
        player->setWeakJavaPlayerPtr(nullptr);
    }

    // 清除native指针
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz) {
        jfieldID ptrField = env->GetFieldID(clazz, JX_NATIVE_PLAYER_PTR, "J");
        if (ptrField) {
            env->SetLongField(thiz, ptrField, static_cast<jlong>(0));
        }
    }

    delete player;
}

void sky_mediaPlayer_setDataSource(JNIEnv *env, jobject thiz, jstring path) {
    FUNC_TRACE()

    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        return;
    }

    const char* nativeString = env->GetStringUTFChars(path, nullptr);
    if (nullptr == nativeString) {
        ALOG_E(TAG, "nativeString == nullptr");
        return;
    }

    // 使用新的setter方法
    player->setDataSource(nativeString);
    env->ReleaseStringUTFChars(path, nativeString);

    ALOG_I(TAG, "path=%s", player->getDataSource());
}

void sky_mediaPlayer_prepare(JNIEnv *env, jobject thiz) {

}

void sky_mediaPlayer_prepareAsync(JNIEnv *env, jobject thiz) {
    FUNC_TRACE()

    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        return;
    }

    player->prepareAsync();
}

void sky_mediaPlayer_setVideoSurface(JNIEnv *env, jobject thiz, jobject jsurface) {
    FUNC_TRACE()

    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        return;
    }

    std::lock_guard<std::mutex> lock(player->mtx);

    EGLNativeWindowType window = ANativeWindow_fromSurface(env, jsurface);
    if (nullptr != window) {
        player->getSkyVideoOutHandler().setWindow(window);
    }
}

void sky_mediaPlayer_start(JNIEnv *env, jobject thiz) {
    auto* player = asSkyPlayer(env, thiz);
    if (player) {
        player->start();
    }
}

void sky_mediaPlayer_pause(JNIEnv *env, jobject thiz) {
    auto* player = asSkyPlayer(env, thiz);
    if (player) {
        player->pause();
    }
}

void sky_mediaPlayer_seekTo(JNIEnv *env, jobject thiz, jlong pos) {
    auto* player = asSkyPlayer(env, thiz);
    if (player) {
        player->seekTo(pos);
    }
}

jlong sky_mediaPlayer_getCurrentPosition(JNIEnv *env, jobject thiz) {
    auto* player = asSkyPlayer(env, thiz);
    if (player) {
        return player->getCurrentPosition();
    }
    return 0;
}

jlong sky_mediaPlayer_getDuration(JNIEnv *env, jobject thiz) {
    auto* player = asSkyPlayer(env, thiz);
    if (player) {
        return player->getDuration();
    }
    return 0;
}

jboolean sky_mediaPlayer_isPlaying(JNIEnv *env, jobject thiz) {
    auto* player = asSkyPlayer(env, thiz);
    if (player) {
        return player->isPlaying();
    }
    return JNI_FALSE;
}

jint sky_mediaPlayer_setAudioFilter(JNIEnv *env, jobject thiz, jstring filter) {
    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        ALOG_E(TAG, "setAudioFilter: player is null");
        return -1;
    }

    const char* nativeFilter = nullptr;
    if (filter != nullptr) {
        nativeFilter = env->GetStringUTFChars(filter, nullptr);
    }

    int result = player->setAudioFilter(nativeFilter);

    if (nativeFilter != nullptr) {
        env->ReleaseStringUTFChars(filter, nativeFilter);
    }

    return result;
}

jint sky_mediaPlayer_setLut(JNIEnv *env, jobject thiz, jbyteArray rgba, jfloat intensity) {
    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        ALOG_E(TAG, "setLut: player is null");
        return -1;
    }

    if (rgba == nullptr) {
        return player->setLut(nullptr, 0, intensity);
    }

    jsize len = env->GetArrayLength(rgba);
    jbyte* buf = env->GetByteArrayElements(rgba, nullptr);
    if (buf == nullptr) {
        ALOG_E(TAG, "setLut: GetByteArrayElements failed");
        return -1;
    }

    int result = player->setLut(reinterpret_cast<const uint8_t*>(buf),
                                static_cast<int>(len), intensity);

    env->ReleaseByteArrayElements(rgba, buf, JNI_ABORT);
    return result;
}

jint sky_mediaPlayer_setEnhance(JNIEnv *env, jobject thiz, jfloat sharpness, jfloat deband) {
    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        ALOG_E(TAG, "setEnhance: player is null");
        return -1;
    }

    return player->setEnhance(sharpness, deband);
}

void sky_mediaPlayer_setRendererBackend(JNIEnv *env, jobject thiz, jint backend) {
    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        ALOG_E(TAG, "setRendererBackend: player is null");
        return;
    }

    player->setRendererBackend(static_cast<RendererBackend>(backend));
}

void sky_mediaPlayer_setDecoderMode(JNIEnv *env, jobject thiz, jint mode) {
    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        ALOG_E(TAG, "setDecoderMode: player is null");
        return;
    }

    player->setDecoderMode(static_cast<DecoderMode>(mode));
}

jint sky_mediaPlayer_getActiveDecoderMode(JNIEnv *env, jobject thiz) {
    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        ALOG_E(TAG, "getActiveDecoderMode: player is null");
        return SKY_DECODER_MODE_SOFTWARE;
    }

    return static_cast<jint>(player->getSkyDecoderHandler().getActiveDecoderMode());
}

jboolean sky_mediaPlayer_setWhisperPrebufferMode(JNIEnv *env, jobject thiz, jboolean enabled) {
    auto* player = asSkyPlayer(env, thiz);
    if (nullptr == player) {
        ALOG_E(TAG, "setWhisperPrebufferMode: player is null");
        return JNI_FALSE;
    }

    bool result = sky_set_whisper_prebuffer_mode(player, enabled == JNI_TRUE);
    return result ? JNI_TRUE : JNI_FALSE;
}

static JNINativeMethod methods[] = {
        {"_native_setup", "()V", (void *) (sky_mediaPlayer_native_setup)},
        {"_setDataSource", "(Ljava/lang/String;)V", (void *) sky_mediaPlayer_setDataSource},
        {"_prepare", "()V", (void *) sky_mediaPlayer_prepare},
        {"_prepareAsync", "()V", (void *) sky_mediaPlayer_prepareAsync},
        {"_setVideoSurface", "(Landroid/view/Surface;)V", (void *) sky_mediaPlayer_setVideoSurface},
        {"_start", "()V", (void *) sky_mediaPlayer_start},
        {"_pause", "()V", (void *) sky_mediaPlayer_pause},
        {"_seekTo", "(J)V", (void *) sky_mediaPlayer_seekTo},
        {"_getCurrentPosition", "()J", (void *) sky_mediaPlayer_getCurrentPosition},
        {"_getDuration", "()J", (void *) sky_mediaPlayer_getDuration},
        {"_isPlaying", "()Z", (void *) sky_mediaPlayer_isPlaying},
        {"_release", "()V", (void *) sky_mediaPlayer_release},
        {"_setAudioFilter", "(Ljava/lang/String;)I", (void *) sky_mediaPlayer_setAudioFilter},
        {"_setLut", "([BF)I", (void *) sky_mediaPlayer_setLut},
        {"_setEnhance", "(FF)I", (void *) sky_mediaPlayer_setEnhance},
        {"_setWhisperPrebufferMode", "(Z)Z", (void *) sky_mediaPlayer_setWhisperPrebufferMode},
        {"_setRendererBackend", "(I)V", (void *) sky_mediaPlayer_setRendererBackend},
        {"_setDecoderMode", "(I)V", (void *) sky_mediaPlayer_setDecoderMode},
        {"_getActiveDecoderMode", "()I", (void *) sky_mediaPlayer_getActiveDecoderMode}
};

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    // 保存JavaVM指针供后续使用
    g_jvm = vm;

    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOG_E(TAG, "jni version error");
        return JNI_ERR;
    }
    jclass clazz = env->FindClass(JX_MEDIA_PLAYER_CLAZZ);
    if (nullptr == clazz) {
        ALOG_E(TAG, "%s can't find %s", __func__ , JX_MEDIA_PLAYER_CLAZZ);
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        ALOG_E(TAG, "%s register methods fail!!!", __func__);
        return JNI_ERR;
    }

    ALOG_I(TAG, "%s ok ^_^", __func__);
    return JNI_VERSION_1_6;
}