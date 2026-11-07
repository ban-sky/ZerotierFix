# ZerotierFix R8/ProGuard rules.
#
# NOTE: release builds currently keep minifyEnabled false in app/build.gradle
# because the JNI bridge and greenDAO code generation have not been validated
# on a device with R8 enabled. These rules are the minimum set required before
# turning minification on; verify a release build on a device afterwards.

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The JNI layer resolves these classes, constructors and callbacks by hard-coded
# name/signature (externals/core/java/jni/ZT_jnicache.cpp uses FindClass and
# GetMethodID with literal strings), so nothing may be renamed.
-keep class com.zerotier.sdk.** { *; }
-keepclassmembers class * implements com.zerotier.sdk.** { *; }

# greenDAO generated entities/DAOs and their reflective table metadata.
-keep class net.kaaass.zerotierfix.model.** { *; }
-keepclassmembers class * extends org.greenrobot.greendao.AbstractDao {
    public <init>(...);
}

# EventBus 3 subscriber methods and thread modes are looked up reflectively.
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
