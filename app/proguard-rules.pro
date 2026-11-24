# NewPipe Extractor uses reflection (Rhino JavaScript engine, time-ago patterns,
# protobuf-lite). Keep it whole rather than risk broken YouTube extraction.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Optional classes referenced by the libraries but not present on Android.
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
-dontwarn org.slf4j.**
-dontwarn edu.umd.cs.findbugs.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn okhttp3.**
-dontwarn okio.**
-ignorewarnings

# youtubedl-android (yt-dlp): Kotlin + Jackson reflection over its mapper classes.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * { @com.fasterxml.jackson.annotation.* *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn kotlin.**
