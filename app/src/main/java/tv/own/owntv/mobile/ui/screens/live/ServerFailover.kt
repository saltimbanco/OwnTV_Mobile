package tv.own.owntv.mobile.ui.screens.live

import android.content.Context
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Cross-server failover for interrupted live streams.
 *
 * The engine ladder inside core (ExoPlayer ⇄ mpv, reconnects, watchdogs) already retries the
 * *same* stream; this is the layer above it. A hop is triggered either once the same channel has
 * errored more times than [FailoverPrefs.getRetries] (default [FailoverPrefs.DEFAULT_RETRIES],
 * configurable in Video player settings), or once a channel that never started playing at all has
 * sat on screen for five seconds with neither a stream nor an error. Either way the watcher in
 * [LiveTuner] walks the *other* configured live sources in their own order — Xtream, Stalker or M3U
 * alike — looking for a fuzzy name match, and tunes the first one that carries the channel,
 * announcing every hop with a toast. Hopping continues across failures until every live source has
 * been tried for the current channel, then it stops and leaves the error on screen.
 *
 * App-local [android.content.SharedPreferences] backs the two settings on purpose: the shared
 * settings store lives in the external core artifact, which this fork does not change, so
 * failover tuning stays additive here.
 */
object FailoverPrefs {
    const val DEFAULT_RETRIES = 3
    const val MAX_RETRIES = 10
    val RETRY_CHOICES: IntRange = 0..MAX_RETRIES

    private const val PREFS = "owntv_server_failover"
    private const val KEY_RETRIES = "stream_retries"
    private const val KEY_ENABLED = "hop_enabled"

    fun getRetries(context: Context): Int =
        prefs(context).getInt(KEY_RETRIES, DEFAULT_RETRIES).coerceIn(RETRY_CHOICES)

    fun setRetries(context: Context, retries: Int) {
        prefs(context).edit().putInt(KEY_RETRIES, retries.coerceIn(RETRY_CHOICES)).apply()
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Minimum [matchScore] for a cross-server candidate to be considered the same channel. */
const val FAILOVER_MIN_SCORE = 0.45

/** Compact identity key: lowercased, letters/digits only (`"NOVA Sports-Prime"` → `"novasportsprime"`). */
fun normalizeChannelKey(name: String): String =
    name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

/** Significant search tokens of a channel name (`"Nova Sports Prime HD"` → `{nova, sports, prime, hd}`). */
fun channelTokens(name: String): Set<String> =
    name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

/** Words that mark picture quality or region, not which channel it is. */
private val NAME_DECORATIONS =
    setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h264", "h265",
        "gr", "uk", "us", "de", "fr", "it", "es", "pt", "nl", "tr")

private val RESOLUTION_TOKEN = Regex("\\d{3,4}[pi]")

/** [channelTokens] with the quality/region decorations removed. */
private fun significantTokens(name: String): Set<String> =
    channelTokens(name).filterNot { it in NAME_DECORATIONS || RESOLUTION_TOKEN.matches(it) }.toSet()

/** The same word: equal, or a plural/abbreviation pair — `sport` ≈ `sports`, `spor` ≈ `sport`. */
private fun tokensMatch(a: String, b: String): Boolean {
    if (a == b) return true
    val shorter = if (a.length < b.length) a else b
    val longer = if (a.length < b.length) b else a
    return shorter.length >= 4 && longer.length - shorter.length <= 3 && longer.startsWith(shorter)
}

/**
 * How likely [candidateName] is the same channel as [targetName]. The asymmetry that matters:
 * a provider may shorten or decorate a name (`"nova sport prime"`, `"ERT 1 HD"` for `"ERT1"`)
 * but may not invent words the target never had (`"ERT 1"` is not `"ERT Sports 1"`,
 * `"ASports 1"` is not `"Sports 1"` — a shared fragment is not a shared name). So the candidate
 * is scored on how much of the target it covers, and anything it adds disqualifies it.
 * Quality/region decorations (`" HD"`, `"GR"`, `" 1080p"`) are stripped from both sides first,
 * and plurals/abbreviations count as the same word.
 */
fun matchScore(targetName: String, candidateName: String): Double {
    val target = normalizeChannelKey(targetName)
    val candidate = normalizeChannelKey(candidateName)
    if (target.isEmpty() || candidate.isEmpty()) return 0.0
    if (target == candidate) return 1.0
    val targetTokens = significantTokens(targetName)
    val candidateTokens = significantTokens(candidateName)
    // Same name once the decorations (`" HD"`, `"GR"`, `" 1080p"`) are also removed:
    // `"ERT1"` ≡ `"ERT 1 HD"`, `"Nova Sports Prime HD"` ≡ `"Nova Sports Prime"`.
    val targetBare = targetTokens.joinToString("")
    if (targetBare.isNotEmpty() && targetBare == candidateTokens.joinToString("")) return 0.9
    if (targetTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
    val extra = candidateTokens.filter { cTok -> targetTokens.none { tokensMatch(it, cTok) } }
    if (extra.isNotEmpty()) return 0.0
    val covered = targetTokens.count { tTok -> candidateTokens.any { tokensMatch(tTok, it) } }
    return if (covered == 0) 0.0 else covered.toDouble() / targetTokens.size * 0.9
}

/** Highest-scoring candidate at or above [FAILOVER_MIN_SCORE], or null when nothing is close. */
fun bestChannelMatch(targetName: String, candidates: List<ChannelEntity>): ChannelEntity? =
    candidates
        .map { it to matchScore(targetName, it.name) }
        .filter { it.second >= FAILOVER_MIN_SCORE }
        .maxByOrNull { it.second }
        ?.first
