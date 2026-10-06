# Add project specific ProGuard rules here.
-keep class org.nanohttpd.** { *; }
-keep class com.google.zxing.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
