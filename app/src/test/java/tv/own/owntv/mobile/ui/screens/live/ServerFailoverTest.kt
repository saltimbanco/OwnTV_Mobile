package tv.own.owntv.mobile.ui.screens.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Pins the name-matching bar for cross-server failover: a provider's shortened or decorated
 * variant of a name is the same channel; a name with different words is not.
 */
class ServerFailoverTest {

    private fun matches(target: String, candidate: String) =
        matchScore(target, candidate) >= FAILOVER_MIN_SCORE

    @Test
    fun `same channel with different spacing and case is an exact match`() {
        assertEquals(1.0, matchScore("ert1", "ERT 1"), 0.0)
        assertEquals(1.0, matchScore("Nova Sports-Prime", "novasportsprime"), 0.0)
    }

    @Test
    fun `shortened or plural variants are the same channel`() {
        assertTrue(matches("nova sports prime hd", "Nova Sport Prime"))
        assertTrue(matches("nova sports prime", "Nova Sports"))
        assertTrue(matches("nova sport", "Nova Sports"))
    }

    @Test
    fun `quality and region decorations do not split a name`() {
        assertTrue(matches("ert1", "ERT1 HD"))
        assertTrue(matches("nova sports", "NOVA SPORTS GR"))
        assertTrue(matches("nova sports", "Nova Sports 1080p"))
        assertTrue(matches("nova sports prime hd", "Nova Sports Prime 4K"))
    }

    @Test
    fun `a word the target never had is a different channel`() {
        assertEquals(0.0, matchScore("ert 1", "ERT Sports 1"), 0.0)
        assertEquals(0.0, matchScore("ert1", "ERT Sports 1"), 0.0)
        assertEquals(0.0, matchScore("nova sports prime hd", "Nova Sports News"), 0.0)
        assertEquals(0.0, matchScore("nova sports 1", "Nova Sports 2"), 0.0)
    }

    @Test
    fun `a shared fragment inside a longer word is not a name match`() {
        assertEquals(0.0, matchScore("sports 1", "ASports 1"), 0.0)
        assertEquals(0.0, matchScore("nova sports prime hd", "asports"), 0.0)
    }

    @Test
    fun `a candidate covering under half the name is too loose`() {
        assertFalse(matches("nova sports prime", "Nova"))
        assertFalse(matches("ERT", "ERT1"))
    }

    @Test
    fun `bestChannelMatch picks the closest name at or above the threshold`() {
        val candidates = listOf(
            channel("Nova Sports News"),
            channel("Nova Sport Prime"),
            channel("Nova Sports 2"),
        )
        assertEquals("Nova Sport Prime", bestChannelMatch("Nova Sports Prime HD", candidates)?.name)
        assertEquals(null, bestChannelMatch("Totally Unrelated", candidates))
    }

    private fun channel(name: String) =
        ChannelEntity(sourceId = 0, name = name, streamUrl = "http://example.invalid/${name.replace(' ', '-')}")
}
