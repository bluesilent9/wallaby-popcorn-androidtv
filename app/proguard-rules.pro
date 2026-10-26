-dontwarn org.commonmark.ext.gfm.strikethrough.Strikethrough

# JSch resolves cryptographic algorithm implementations by class name.
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }

# JSch includes optional desktop integrations that are not packaged on Android.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
