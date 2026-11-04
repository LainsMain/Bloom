# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class be.lain.bloom.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class be.lain.bloom.**$$serializer { *; }
