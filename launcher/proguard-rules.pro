# CarLink: behold egen kode (liten) og biblioteker som bruker refleksjon.
-keep class no.carlink.** { *; }
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
