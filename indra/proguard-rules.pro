# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep JNI classes
-keep class org.indra.android.core.client.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# The client library resolves these by exact name at runtime
# (indra/jni/cSploitClient/cache.c), so R8 must not rename, shrink or strip them:
#  - ChildProcessManager.onEvent(id, event) is the single daemon callback entry point
#  - org.indra.android.events.* are instantiated by native code one class at a time
# Without these keeps the release build silently loses every child event (no output,
# no ChildEnd) and each tool runs to its timeout.
-keep class org.indra.android.events.** { *; }
-keep class org.indra.android.core.child.ChildProcessManager { *; }

# Keep data models
-keep class org.indra.android.data.model.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
