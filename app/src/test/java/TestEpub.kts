import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipInputStream

val epubPath = "/home/aatricks/Documents/Novel-Scrape/ATE1 - Kuji Furumiya.epub"
val epubFile = File(epubPath)

if (!epubFile.exists()) {
    println("EPUB file not found: $epubPath")
    return@main
}

println("Testing EPUB: ${epubFile.name}")
println("=" * 80)

// Extract all files from EPUB
val zipEntries = mutableMapOf<String, ByteArray>()
ZipInputStream(epubFile.inputStream()).use { zipStream ->
    var entry = zipStream.nextEntry
    while (entry != null) {
        if (!entry.isDirectory) {
            zipEntries[entry.name] = zipStream.readBytes()
            println("Found file: ${entry.name}")
        }
        zipStream.closeEntry()
        entry = zipStream.nextEntry
    }
}

println("\n" + "=" * 80)
println("Total files: ${zipEntries.size}")

// Parse container.xml
val containerXml = zipEntries["META-INF/container.xml"]
if (containerXml == null) {
    println("ERROR: container.xml not found!")
    return@main
}

val containerDoc = Jsoup.parse(String(containerXml), "", Parser.xmlParser())
val opfPath = containerDoc.select("rootfile").attr("full-path")
println("\nOPF file path: $opfPath")

// Parse OPF
val opfContent = zipEntries[opfPath]
if (opfContent == null) {
    println("ERROR: OPF file not found at: $opfPath")
    return@main
}

val opfDoc = Jsoup.parse(String(opfContent), "", Parser.xmlParser())

// Extract metadata
println("\n" + "=" * 80)
println("METADATA:")
val metadata = opfDoc.select("metadata").first()
println("Title: ${metadata?.select("dc|title, title")?.first()?.text()}")
println("Author: ${metadata?.select("dc|creator, creator")?.first()?.text()}")

// Extract manifest
println("\n" + "=" * 80)
println("MANIFEST:")
val opfBasePath = opfPath.substringBeforeLast("/", "")
val manifest = mutableMapOf<String, String>()
opfDoc.select("manifest item").forEach { item ->
    val id = item.attr("id")
    val href = item.attr("href")
    val mediaType = item.attr("media-type")
    if (id.isNotBlank() && href.isNotBlank()) {
        val fullHref = if (opfBasePath.isNotBlank()) "$opfBasePath/$href" else href
        manifest[id] = fullHref
        if (mediaType.contains("html") || mediaType.contains("xml")) {
            println("  $id -> $fullHref")
        }
    }
}

// Extract spine
println("\n" + "=" * 80)
println("SPINE (Reading Order):")
val spine = mutableListOf<String>()
opfDoc.select("spine itemref").forEach { itemref ->
    val idref = itemref.attr("idref")
    manifest[idref]?.let { href ->
        spine.add(href)
        println("  ${spine.size}. $href")
    }
}

// Test loading first chapter
println("\n" + "=" * 80)
println("TESTING FIRST CHAPTER CONTENT:")
if (spine.isNotEmpty()) {
    val firstChapter = spine[0]
    println("Loading: $firstChapter")
    val chapterContent = zipEntries[firstChapter]
    if (chapterContent != null) {
        val doc = Jsoup.parse(String(chapterContent))
        println("\nTitle: ${doc.title()}")
        
        var textCount = 0
        var imageCount = 0
        
        doc.select("body").first()?.let { body ->
            println("\nContent Elements:")
            body.select("p, div").forEach { element ->
                val text = element.text().trim()
                if (text.isNotBlank() && text.length > 10) {
                    textCount++
                    if (textCount <= 5) {
                        println("  [TEXT ${textCount}] ${text.take(80)}...")
                    }
                }
            }
            
            body.select("img").forEach { img ->
                imageCount++
                println("  [IMAGE ${imageCount}] src=${img.attr("src")}")
            }
        }
        
        println("\nTotal text paragraphs: $textCount")
        println("Total images: $imageCount")
    } else {
        println("ERROR: Chapter file not found!")
    }
}

operator fun String.times(n: Int) = this.repeat(n)
