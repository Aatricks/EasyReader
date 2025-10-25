package io.aatricks.novelscraper.data.model

/**
 * Enum representing content types. Enums serialize cleanly with Gson for persistence.
 */
enum class ContentType(val typeName: String) {
    WEB("web"),
    PDF("pdf"),
    HTML("html"),
    EPUB("epub");

    override fun toString(): String = typeName

    companion object {
        fun fromString(value: String?): ContentType = when (value?.lowercase()) {
            "web" -> WEB
            "pdf" -> PDF
            "html" -> HTML
            "epub" -> EPUB
            else -> WEB
        }
    }
}
