package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirBruteTest {
    @Test
    fun `a bare host gets a scheme and a trailing slash`() {
        assertEquals("http://192.168.1.1/", DirBrute.normalizeUrl("192.168.1.1"))
        assertEquals("http://192.168.1.1/", DirBrute.normalizeUrl("  192.168.1.1  "))
        assertEquals("https://example.test/app/", DirBrute.normalizeUrl("https://example.test/app"))
    }

    @Test
    fun `an already normalised url is left alone`() {
        assertEquals("https://example.test/app/", DirBrute.normalizeUrl("https://example.test/app/"))
        assertEquals("http://example.test/", DirBrute.normalizeUrl("http://example.test/"))
    }

    @Test
    fun `a query string is dropped rather than glued to every path`() {
        // Paths are appended to the base, so "http://h/app?x=1" would become
        // "http://h/app?x=1/admin" — a request the user never asked for.
        assertEquals("http://example.test/app/", DirBrute.normalizeUrl("http://example.test/app?x=1"))
    }

    @Test
    fun `input that cannot be a base url is rejected`() {
        assertNull(DirBrute.normalizeUrl(""))
        assertNull(DirBrute.normalizeUrl("   "))
        assertNull(DirBrute.normalizeUrl("ftp://example.test"))
        assertNull(DirBrute.normalizeUrl("http://"))
        assertNull(DirBrute.normalizeUrl("://nope"))
    }

    @Test
    fun `word lists drop blanks and comments and de-duplicate on the leading slash`() {
        val raw = listOf("admin", "", "  ", "# a comment", "/admin", "login/", "admin")
        assertEquals(listOf("admin", "login/"), DirBrute.words(raw))
    }

    @Test
    fun `extensions are normalised and sanitised`() {
        assertEquals(listOf("php", "html", "txt"), DirBrute.extensions("php, .HTML ,txt"))
        assertEquals(listOf("js"), DirBrute.extensions("  ..JS  "))
        assertEquals(emptyList<String>(), DirBrute.extensions(" , ,, "))
        // A path separator inside an "extension" would silently probe a different URL.
        assertEquals(listOf("php"), DirBrute.extensions("ph/p"))
    }

    @Test
    fun `every word is probed with and without each extension, capped`() {
        val paths = DirBrute.paths(listOf("admin", "login"), listOf("php"))
        assertEquals(listOf("admin", "admin.php", "login", "login.php"), paths)
        assertEquals(listOf("admin", "admin.php"), DirBrute.paths(listOf("admin", "login"), listOf("php"), limit = 2))
    }

    @Test
    fun `the url keeps exactly one separator`() {
        assertEquals("http://h/", DirBrute.url("http://h/", "/"))
        assertEquals("http://h/admin", DirBrute.url("http://h/", "admin"))
        assertEquals("http://h/admin", DirBrute.url("http://h/", "/admin"))
    }

    @Test
    fun `only the reported statuses are findings`() {
        listOf(200, 204, 301, 302, 307, 308, 401, 403).forEach { code ->
            assertTrue("$code should be reported", DirBrute.label(code) != null)
        }
        listOf(400, 404, 405, 500, 503).forEach { code ->
            assertNull("$code should not be reported", DirBrute.label(code))
        }
    }

    @Test
    fun `a line carries the path and the status`() {
        val line = DirBrute.line(DirBrute.Hit(200, "admin.php"))
        assertTrue(line.startsWith("admin.php"))
        assertTrue(line.contains("(Status: 200)"))
    }

    @Test
    fun `the closing summary counts findings honestly`() {
        assertEquals("0 paths found", DirBrute.summary(0))
        assertEquals("1 path found", DirBrute.summary(1))
        assertEquals("7 paths found", DirBrute.summary(7))
    }
}
