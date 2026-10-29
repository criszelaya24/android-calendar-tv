package palacios.top.calendartv.reminders

/** Google Calendar descriptions are small HTML documents; banners and lists want plain text. */
object DescriptionText {

    private val lineBreaks = Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>")
    private val listItem = Regex("(?i)<li[^>]*>")
    private val tags = Regex("<[^>]*>")
    private val spaces = Regex("[ \\t\\u00A0]+")

    fun plain(raw: String, maxChars: Int = 160): String {
        if (raw.isBlank()) return ""
        var text = raw.replace(lineBreaks, "\n").replace(listItem, "• ").replace(tags, "")
        text = text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&") // last, so "&amp;lt;" stays "&lt;"

        val lines = mutableListOf<String>()
        for (line in text.lines().map { it.replace(spaces, " ").trim() }) {
            if (line.isEmpty() && (lines.isEmpty() || lines.last().isEmpty())) continue
            lines += line
        }
        val result = lines.joinToString("\n").trim()
        return if (result.length > maxChars) result.take(maxChars).trimEnd() + "…" else result
    }
}
