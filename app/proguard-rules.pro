-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.akeyboard.live.**$$serializer { *; }
-keepclassmembers class com.akeyboard.live.** {
    *** Companion;
}
-keepclasseswithmembers class com.akeyboard.live.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.akeyboard.data.**$$serializer { *; }
-keepclassmembers class com.akeyboard.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.akeyboard.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
