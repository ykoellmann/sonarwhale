package com.sonarwhale.toolwindow

object ContentTypeUtils {
    /** Returns (IntelliJ Language ID, file extension) for a given Content-Type header value. */
    fun langAndExt(contentType: String): Pair<String, String> {
        val ct = contentType.lowercase()
        return when {
            "json"       in ct                             -> "JSON"       to "json"
            "html"       in ct                             -> "HTML"       to "html"
            "xml"        in ct                             -> "XML"        to "xml"
            "javascript" in ct || "ecmascript" in ct       -> "JavaScript" to "js"
            "css"        in ct                             -> "CSS"        to "css"
            "yaml"       in ct                             -> "YAML"       to "yaml"
            else                                           -> ""           to "txt"
        }
    }
}
