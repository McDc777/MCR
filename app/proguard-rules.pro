# PdfBox-Android relies on reflection for font/filter registries and ships
# resources that R8 must not strip.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.**

# PdfBox references java.awt/javax.imageio pieces that do not exist on Android.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.**
-dontwarn org.apache.commons.logging.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep Kotlin metadata used by Compose tooling.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
