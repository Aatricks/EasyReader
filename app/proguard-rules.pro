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

# Suppress warnings for optional dependencies referenced by llmedge / Ktor
-dontwarn com.google.android.gms.tasks.Task
-dontwarn com.google.gson.Gson
-dontwarn com.google.gson.reflect.TypeToken
-dontwarn com.ml.shubham0204.sentence_embeddings.SentenceEmbedding
-dontwarn com.tom_roush.pdfbox.android.PDFBoxResourceLoader
-dontwarn com.tom_roush.pdfbox.pdmodel.PDDocument
-dontwarn com.tom_roush.pdfbox.text.PDFTextStripper
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn kotlinx.coroutines.tasks.TasksKt
-dontwarn org.slf4j.impl.StaticLoggerBinder
