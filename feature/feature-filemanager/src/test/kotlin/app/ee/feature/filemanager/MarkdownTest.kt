package app.ee.feature.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun headingTextAndSpan() {
        val out = renderMarkdown("# Title")
        assertEquals("Title", out.text)
        assertTrue("heading should carry a span", out.rangeContaining(0) != null)
    }

    @Test
    fun boldStripsMarkers() {
        val out = renderMarkdown("a **b** c")
        assertEquals("a b c", out.text)
    }

    @Test
    fun linkAnnotationCarriesUrl() {
        val out = renderMarkdown("see [site](https://x.y) now")
        val urls = out.getStringAnnotations("URL", 0, out.length)
        assertEquals(1, urls.size)
        assertEquals("https://x.y", urls[0].item)
    }

    @Test
    fun codeFenceVerbatim() {
        val out = renderMarkdown("```\nval x = 1\n```")
        assertEquals("```\nval x = 1\n```", out.text)
    }

    @Test
    fun bulletPrefix() {
        val out = renderMarkdown("- item")
        assertEquals("•  item", out.text)
    }

    @Test
    fun plainTextUnchanged() {
        val out = renderMarkdown("hello world")
        assertEquals("hello world", out.text)
    }
}
