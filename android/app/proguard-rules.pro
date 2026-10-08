# JNA reaches its own classes and the generated bindings by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.dby.core.** { *; }
-dontwarn java.awt.**
