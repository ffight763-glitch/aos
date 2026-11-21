# AOS: keep our own code as it is (screens are built in code and some classes are reached by name through the manifest);
# R8 still optimises and shrinks the libraries.
-keep class org.anthezis.launcher.** { *; }
-dontwarn org.xmlpull.**
-dontwarn javax.annotation.**
