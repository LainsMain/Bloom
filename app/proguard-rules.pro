# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class be.lain.bloom.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class be.lain.bloom.**$$serializer { *; }

# ML Kit (barcode scanning) discovers its parts by reflection: MlKitInitProvider creates every
# ComponentRegistrar through its no-argument constructor, which R8 full mode otherwise strips.
# Without these rules ML Kit never initialises in release builds and every scan fails.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-dontwarn com.google.mlkit.**

# Filament (3-D exercise figure): the native renderer calls back into these classes over JNI.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**
