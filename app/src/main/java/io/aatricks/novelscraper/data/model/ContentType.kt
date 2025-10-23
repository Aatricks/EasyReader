package io.aatricks.novelscraper.data.model

/**
 * Sealed class representing different types of content that can be scraped and displayed.
 * Using sealed class instead of String enum provides type safety and exhaustive when expressions.
 */
sealed class ContentType {
    /**
     * Web-based content from online sources
     */
    data object Web : ContentType()
    
    /**
     * PDF document content
     */
    data object PDF : ContentType()
    
    /**
     * HTML file content
     */
    data object HTML : ContentType()
    
    /**
     * Convert ContentType to string representation for storage
     */
    fun toTypeString(): String = when (this) {
        is Web -> "web"
        is PDF -> "pdf"
        is HTML -> "html"
    }
    
    companion object {
        /**
         * Create ContentType from string representation
         * @throws IllegalArgumentException if type string is invalid
         */
        fun fromString(type: String): ContentType = when (type.lowercase()) {
            "web" -> Web
            "pdf" -> PDF
            "html" -> HTML
            else -> throw IllegalArgumentException("Unknown content type: $type")
        }
    }
}
