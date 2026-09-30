# Native code uses FindClass, RegisterNatives, GetFieldID and GetStaticMethodID.
# Keep this JNI boundary intact when an APK consumer enables R8.
-keep class imt.zw.skymediaplayer.player.SkyMediaPlayer {
    native <methods>;
    long _nativeMediaPlayer;
    static void postEventFromNative(imt.zw.skymediaplayer.player.SkyMediaPlayer, int, int, int, java.lang.Object);
}
