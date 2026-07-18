# Reflection metadata used by serialization and PDF/network libraries. The libraries' own
# consumer rules retain the concrete classes that require it.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

# iText references optional algorithms that are not part of this app's PDF feature set.
-dontwarn com.itextpdf.**

# iText rejects its own internal events when their runtime package names are obfuscated.
-keepnames class com.itextpdf.** extends com.itextpdf.commons.actions.AbstractITextEvent

# llmedge publishes consumer rules for its JNI entry points. Keep optional integrations absent
# from the AI flavor quiet without preventing R8 from shrinking unused Java APIs.
-dontwarn io.aatricks.llmedge.**

# Suppress warnings for optional dependencies referenced by llmedge / Ktor
-dontwarn com.google.android.gms.tasks.Task
-dontwarn com.google.gson.Gson
-dontwarn com.google.gson.reflect.TypeToken
-dontwarn com.ml.shubham0204.sentence_embeddings.SentenceEmbedding
-dontwarn com.tom_roush.pdfbox.android.PDFBoxResourceLoader
-dontwarn com.tom_roush.pdfbox.pdmodel.PDDocument
-dontwarn com.tom_roush.pdfbox.text.PDFTextStripper
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn kotlinx.coroutines.tasks.TasksKt
-dontwarn org.slf4j.impl.StaticLoggerBinder
