# Shizuku: newProcess is called reflectively
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
# kotlinx.serialization model classes (projects, settings, backups)
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class com.aerogrid.app.**$$serializer { *; }
-keepclassmembers class com.aerogrid.app.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep class com.aerogrid.app.model.** { *; }
-keep class com.aerogrid.app.data.AppSettings { *; }
# JTS uses reflection-free code but keep geometry classes used via factories
-dontwarn org.locationtech.jts.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
