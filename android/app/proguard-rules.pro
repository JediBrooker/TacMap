# Keep Kotlinx Serialization metadata.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Optional JPEG2000 decoder referenced by pdfbox-android's JPX filter.
-dontwarn com.gemalto.jp2.JP2Decoder

# play:review-ktx references a gms annotation that only exists in play-services-basement,
# which we dont pull in. Its a compile time nullness marker, nothing to keep at runtime.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
