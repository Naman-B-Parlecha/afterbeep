# --- Afterbeep release shrinking rules ---

# The engine is started by name from a shell: app_process ... dev.afterbeep.engine.Engine
# and uses reflection on itself / framework hidden APIs. Keep it whole and unrenamed.
-keep class dev.afterbeep.engine.Engine { *; }
-keep class dev.afterbeep.engine.Engine$* { *; }

# Components referenced from the manifest are kept automatically; keep receivers' extras handling intact.
-keep class dev.afterbeep.engine.** { *; }

# libadb-android (+ its spake2 pairing crypto, JNI) — reflection & native code
-keep class io.github.muntashirakon.** { *; }
-dontwarn io.github.muntashirakon.**

# Crypto providers used for adb TLS pairing / cert generation
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**

# Common optional deps referenced by the above
-dontwarn javax.naming.**
-dontwarn sun.security.**
-dontwarn org.openjsse.**
