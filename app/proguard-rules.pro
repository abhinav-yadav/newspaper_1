# kotlinx.serialization (extraction rules)
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.epaper.core.**$$serializer { *; }
-keepclassmembers class com.epaper.core.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.jspecify.annotations.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
