package tv.own.owntv.mobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerListImportTest {

    private fun parse(text: String) = parseServerList(
        text,
        defaultServerName = { "Server $it" },
        defaultPortalName = { "Portal $it" },
    )

    /**
     * A file is not all-or-nothing: the readable servers import and the unreadable lines are
     * reported. This is the 20-line file that quietly produced 14 playlists — the six bad lines
     * used to be dropped without a word because the result sheet only knew about import failures.
     */
    @Test
    fun `a mixed file imports its readable lines and reports the rest`() {
        val valid = (1..14).map { "xtream;Server $it;http://server$it.test:8080;user;pass" }
        val invalid = (1..6).map { "this is not a server $it" }
        val result = parse((valid + invalid).joinToString("\n"))

        assertEquals(14, result.entries.size)
        assertEquals(listOf(15, 16, 17, 18, 19, 20), result.skipped.map { it.first })
        assertEquals("this is not a server 1", result.skipped.first().second)
    }

    @Test
    fun `blank lines and comments are not reported as skipped`() {
        val result = parse(
            """
            # my servers

            xtream;One;http://one.test:8080;user;pass
            """.trimIndent(),
        )

        assertEquals(listOf("One"), result.entries.map { it.name })
        assertEquals(emptyList<Pair<Int, String>>(), result.skipped)
    }

    @Test
    fun `an Xtream playlist URL becomes a server with its credentials`() {
        val result = parse("http://panel.test:8080/get.php?username=user&password=pass&type=m3u_plus")

        assertEquals(1, result.entries.size)
        val entry = result.entries.single() as ServerListEntry.Xtream
        assertEquals("http://panel.test:8080", entry.server)
        assertEquals("user", entry.username)
        assertEquals("pass", entry.password)
        assertEquals("Server 1", entry.name)
    }
}
