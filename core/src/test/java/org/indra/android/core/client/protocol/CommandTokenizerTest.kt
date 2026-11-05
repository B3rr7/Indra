package org.indra.android.core.client.protocol

import org.indra.android.core.system.ShellText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the Kotlin port of `cSploitClient/command.c: parse_cmd`. */
class CommandTokenizerTest {

    @Test
    fun `splits on unquoted whitespace`() {
        assertEquals(listOf("nmap", "-sS", "10.0.0.1"), CommandTokenizer.tokenize("nmap -sS 10.0.0.1"))
        assertEquals(listOf("a", "b"), CommandTokenizer.tokenize("  a \t b  "))
    }

    @Test
    fun `single quotes group but end the argument at the closing quote`() {
        assertEquals(listOf("sh", "-c", "echo hi"), CommandTokenizer.tokenize("sh -c 'echo hi'"))
        // Not a POSIX shell: `'a b'c` is two arguments, not one.
        assertEquals(listOf("a b", "c"), CommandTokenizer.tokenize("'a b'c"))
    }

    @Test
    fun `double quotes group`() {
        assertEquals(listOf("sh", "-c", "echo a && echo b"), CommandTokenizer.tokenize("""sh -c "echo a && echo b""""))
        // A quote is a delimiter only at the argument boundary: the copy loop strips backslash
        // escapes, never quote characters, so a mid-token quote is copied verbatim.
        assertEquals(listOf("a\"b c", "d"), CommandTokenizer.tokenize("""a"b c"d"""))
    }

    @Test
    fun `backslash escapes only space tab quote and backslash`() {
        assertEquals(listOf("a b"), CommandTokenizer.tokenize("""a\ b"""))
        assertEquals(listOf("""a'b"""), CommandTokenizer.tokenize("""a\'b"""))
        // An unrecognised escape keeps the backslash.
        assertEquals(listOf("""a\nb"""), CommandTokenizer.tokenize("""a\nb"""))
    }

    @Test
    fun `a leading escaped character is dropped`() {
        assertEquals(listOf("a"), CommandTokenizer.tokenize("""\ a"""))
    }

    @Test
    fun `empty quoted arguments are dropped`() {
        assertEquals(emptyList<String>(), CommandTokenizer.tokenize("''"))
        assertEquals(listOf("x"), CommandTokenizer.tokenize("x ''"))
    }

    @Test
    fun `shell metacharacters pass through untouched`() {
        assertEquals(listOf("sh", "-c", "id; echo $?"), CommandTokenizer.tokenize("sh -c 'id; echo $?'"))
        assertEquals(listOf("echo", "x", ">", "/tmp/f"), CommandTokenizer.tokenize("echo x > /tmp/f"))
    }

    @Test
    fun `round-trips what ShellText encodeArg produces`() {
        val scripts =
            listOf(
                "./tools/tcpdump -i 'nosuchif0' -A",
                """sh -c "echo one && echo two"""",
                "./tools/nmap/nmap -sS -p 1-1000 10.0.0.1",
                "./tools/hydra/hydra -V -l root -P /data/wordlist 10.0.0.1 ssh",
            )
        scripts.forEach { script ->
            assertEquals("script=$script", listOf(script), CommandTokenizer.tokenize(ShellText.encodeArg(script)))
        }
    }
}
