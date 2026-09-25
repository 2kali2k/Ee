package app.ee.feature.filemanager

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Minimal Markdown → [AnnotatedString] renderer (M5 — P1-11 preview).
 *
 * Supported: ATX headings (#..######), unordered lists (-, *, +),
 * blockquotes (>), fenced code blocks, inline **bold**, *italic*,
 * `code`, [links](url). Unsupported syntax is shown verbatim — a
 * preview, not a full CommonMark engine. Pure Compose-text, JVM-testable.
 */
fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    var inCode = false
    lines.forEachIndexed { index, rawLine ->
        if (index > 0) append("\n")

        val fence = rawLine.trimStart().startsWith("```")
        if (fence) {
            inCode = !inCode
            if (inCode) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.spCompat(),
                        color = Color(0xFF2E2E38),
                    ),
                ) { append(rawLine) }
            } else {
                withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.spCompat()),
                ) { append(rawLine) }
            }
            return@forEachIndexed
        }

        when {
            inCode -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.spCompat()),
            ) { append(rawLine) }

            rawLine.startsWith("###### ") ->
                heading(rawLine.removePrefix("###### "), 4f)
            rawLine.startsWith("##### ") ->
                heading(rawLine.removePrefix("##### "), 5f)
            rawLine.startsWith("#### ") ->
                heading(rawLine.removePrefix("#### "), 6f)
            rawLine.startsWith("### ") ->
                heading(rawLine.removePrefix("### "), 7f)
            rawLine.startsWith("## ") ->
                heading(rawLine.removePrefix("## "), 8f)
            rawLine.startsWith("# ") ->
                heading(rawLine.removePrefix("# "), 9f)

            rawLine.startsWith("> ") -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append("❝ ")
                    appendInline(rawLine.removePrefix("> "))
                }
            }

            rawLine.startsWith("- ") || rawLine.startsWith("* ") || rawLine.startsWith("+ ") -> {
                append("•  ")
                appendInline(rawLine.substring(2))
            }

            rawLine.isBlank() -> Unit // newline already appended

            else -> appendInline(rawLine)
        }
    }
}

private fun AnnotatedString.Builder.heading(content: String, scale: Float) {
    withStyle(
        SpanStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 14.spCompat() * scale / 7f,
        ),
    ) {
        appendInline(content)
    }
}

private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
private val ITALIC = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")
private val CODE = Regex("`([^`\\n]+)`")
private val LINK = Regex("\\[(.+?)\\]\\(([^)\\s]+)\\)")

/** Inline spans: bold / italic / code / links (URL spans for [links]). */
private fun AnnotatedString.Builder.appendInline(line: String) {
    // resolve non-overlapping segments from left to right
    data class Seg(val start: Int, val end: Int, val style: SpanStyle, val url: String? = null)

    val segs = mutableListOf<Seg>()
    BOLD.findAll(line).forEach { m ->
        segs += Seg(m.range.first, m.range.last + 1, SpanStyle(fontWeight = FontWeight.Bold))
    }
    CODE.findAll(line).forEach { m ->
        segs += Seg(
            m.range.first,
            m.range.last + 1,
            SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.spCompat()),
        )
    }
    LINK.findAll(line).forEach { m ->
        segs += Seg(
            m.range.first,
            m.range.last + 1,
            SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF2962FF)),
            url = m.groupValues[2],
        )
    }
    ITALIC.findAll(line).forEach { m ->
        val overlaps = segs.any { it.start < m.range.last + 1 && m.range.first < it.end }
        if (!overlaps) {
            segs += Seg(m.range.first, m.range.last + 1, SpanStyle(fontStyle = FontStyle.Italic))
        }
    }
    segs.sortBy { it.start }

    var cursor = 0
    for (seg in segs) {
        if (seg.start < cursor) continue
        if (seg.start > cursor) append(line, cursor, seg.start)
        withStyle(seg.style) { append(line, seg.start, seg.end) }
        seg.url?.let { url -> pushStringAnnotation("URL", url, seg.start, seg.end) }
        cursor = seg.end
    }
    if (cursor < line.length) append(line, cursor, line.length)
}

private fun Float.spCompat(): androidx.compose.ui.unit.TextUnit =
    androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
