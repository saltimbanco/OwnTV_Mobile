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
        val valid = (1..14).map { "xtream;Server $it;http://server$it.test:8080;user$it;pass$it" }
        val invalid = (1..6).map { "this is not a server $it" }
        val result = parse((valid + invalid).joinToString("\n"))

        assertEquals(14, result.entries.size)
        assertEquals(listOf(15, 16, 17, 18, 19, 20), result.skipped.map { it.line })
        assertEquals("this is not a server 1", result.skipped.first().text)
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
        assertEquals(emptyList<SkippedLine>(), result.skipped)
    }

    /**
     * Providers publish one account under several hostnames — a domain and its raw IP, mirrors,
     * a "top" list and a "full working" list carrying the same login. The credentials are the
     * server's identity: the same login imports once and every later line is a reported duplicate.
     */
    @Test
    fun `the same xtream login under different hosts imports once`() {
        val result = parse(
            """
            xtream;Full;http://full.example.com:8080;user1;pass1
            http://31.43.191.125:8080/get.php?username=user1&password=pass1&type=m3u_plus
            xtream;Mirror;https://mirror.example.net;user1;pass1
            xtream;Other;http://other.test:8080;user2;pass1
            """.trimIndent(),
        )

        assertEquals(listOf("Full", "Other"), result.entries.map { it.name })
        assertEquals(listOf(2, 3), result.skipped.map { it.line })
        assertEquals(listOf(1, 1), result.skipped.map { it.duplicateOf })
    }

    @Test
    fun `a stalker mac is the same portal whatever it is spelled with`() {
        val result = parse(
            """
            stalker;Portal;http://portal.example.com/c/;00:1A:79:AA:BB:CC
            stalker;Mirror;http://mirror.example.com/c/;00:1a:79:aa:bb:cc
            stalker;Other;http://other.example.com/c/;00:1A:79:AA:BB:DD
            """.trimIndent(),
        )

        assertEquals(listOf("Portal", "Other"), result.entries.map { it.name })
        val duplicate = result.skipped.single()
        assertEquals(2, duplicate.line)
        assertEquals(1, duplicate.duplicateOf)
    }

    @Test
    fun `the same host with different logins is two servers`() {
        val result = parse(
            """
            xtream;One;http://host.test:8080;user1;pass1
            xtream;Two;http://host.test:8080;user2;pass2
            """.trimIndent(),
        )

        assertEquals(listOf("One", "Two"), result.entries.map { it.name })
        assertEquals(emptyList<SkippedLine>(), result.skipped)
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
