# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xiaoyuan.renovation.**$$serializer { *; }
-keepclassmembers class com.xiaoyuan.renovation.** {
    *** Companion;
}
-keepclasseswithmembers class com.xiaoyuan.renovation.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.xiaoyuan.renovation.data.model.** { *; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
