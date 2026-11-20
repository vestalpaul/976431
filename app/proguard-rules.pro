# Project-specific ProGuard / R8 rules for Schwab Quant.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }

# App BuildConfig version fields
-keepclassmembers class com.schwabquant.app.BuildConfig {
    public static final <fields>;
}

# Kotlinx Serialization 1.8.x (@Serializable, $$serializer, Companion, custom KSerializer)
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.Serializable <methods>;
}
-keep,includedescriptorclasses class com.schwabquant.app.data.remote.dto.** { *; }
-keep,includedescriptorclasses class com.schwabquant.app.data.remote.streamer.** { *; }
-keep,includedescriptorclasses class com.schwabquant.app.data.repository.ShadowOrderLegPayload** { *; }
-keepclassmembers class * {
    *** serializer(...);
}
-keepclassmembers class *$$serializer {
    *;
}
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepnames class kotlinx.serialization.KSerializer

# Retrofit 2 & OkHttp3 API interfaces
-keep,allowobfuscation,allowshrinking interface com.schwabquant.app.data.remote.api.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.StringConcatFactory

# Google Tink (tink-android)
-keep class com.google.crypto.tink.aead.** { *; }
-keep class com.google.crypto.tink.integration.android.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn javax.crypto.**

# Room Entities, Database & Generated Implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Database class * { *; }

# Hilt + WorkManager (@HiltWorker)
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(...);
}
-keepclassmembers class * {
    @dagger.assisted.AssistedInject <init>(...);
}

# AndroidX BiometricPrompt callbacks
-keep class androidx.biometric.** { *; }
-keepclassmembers class * extends androidx.biometric.BiometricPrompt$AuthenticationCallback {
    public *;
}

# Offline TradingView Lightweight Charts WebView Bridge & Payloads
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.schwabquant.app.presentation.market.chart.SchwabQuantChartBridge { *; }
-keep,includedescriptorclasses class com.schwabquant.app.presentation.market.chart.** { *; }
