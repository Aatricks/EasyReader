# Ktor rules
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }

# Kotlinx Serialization rules
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

# Hilt/Dagger rules
-keep class dagger.hilt.** { *; }
-keep interface dagger.hilt.** { *; }

# Room rules
-keep class androidx.room.** { *; }
-keep interface androidx.room.** { *; }

# OkHttp rules
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# llmedge rules
-keep class io.aatricks.llmedge.** { *; }
-keep interface io.aatricks.llmedge.** { *; }
