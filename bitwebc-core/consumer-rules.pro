# Bitwebc discovers bridge methods by annotation and exposes their original names to JavaScript.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
