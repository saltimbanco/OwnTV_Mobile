package tv.own.owntv.mobile.playback

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.RecordingDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.core.recording.RecordingRules
import tv.own.owntv.core.recording.RecordingSchedule
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.storage.MediaFolders
import tv.own.owntv.core.storage.MediaRoot
import tv.own.owntv.core.storage.MediaTarget
import tv.own.owntv.core.timeshift.TimeshiftSession
import java.io.InputStream

/**
 * Records the channel on screen **out of the copy the picture already comes from**.
 *
 * The saved copy (`TimeshiftManager`) downloads a live channel once and serves every reader from
 * local disk: the picture on screen is one reader of it. Recording is another reader of the same
 * buffer, so bytes are taped as they are written — the connection the buffer already holds stays the
 * only one there is. That is the whole point of this class, and the reason it is not core's
 * `RecordingManager`: that one fetches the channel itself, which spends one of the playlist's
 * connections, and on a one-connection account it cannot record while you watch at all.
 *
 * It is a *live* recorder in the two ways that matter. It ends when the user presses stop — a channel
 * has no end of body — and what it captured stays playable however it ends, because a `.ts` is
 * playable up to the byte it reached, and the reader hands its MP4 initialisation segment over first
 * for a fragmented-MP4 channel.
 *
 * The row it writes is an ordinary `RecordingEntity` under `status = RECORDING`, exactly as a
 * scheduled recording's is: the status pill, the channel menu's "Stop recording", Downloads → Live TV
 * and the player's own REC indicator all read the table already, so nothing else has to learn that
 * this recording exists. A stop from anywhere but here (Downloads, the channel list) closes the row in
 * the table, which this watches for and stops taping — the file must never grow after the row says it
 * is finished.
 *
 * Every public call is safe from the main thread; the copying and the row updates happen on [scope].
 */
class BufferRecorder(
    private val context: Context,
    private val settings: SettingsRepository,
    private val recordingDao: RecordingDao,
) {
    /** The recording taping right now: its row, its channel, and when it started on the wall clock. */
    class Running(val id: Long, val channelId: Long, val startedAtMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _running = MutableStateFlow<Running?>(null)

    /** Non-null while a recording is taping — the player's REC button and badge read this. */
    val running: StateFlow<Running?> = _running.asStateFlow()

    private var copy: Job? = null
    private var ticker: Job? = null
    private var watcher: Job? = null
    private var input: InputStream? = null
    private var target: MediaTarget? = null
    private var row: RecordingEntity? = null
    private var closing = false

    /**
     * Tape [session] — the channel's saved copy, whose bytes are the picture — from its live edge.
     *
     * False when a recording is already taping, or when there is nowhere to write. Nothing here asks
     * the provider for anything: the buffer has the stream already.
     */
    suspend fun start(
        session: TimeshiftSession,
        channel: ChannelEntity,
        profileId: Long,
        programme: XtEpgEntry?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (_running.value != null) return@withContext false
        val startMs = System.currentTimeMillis()
        // The programme's own end, exactly as the recorder's row carries it; a channel the guide
        // knows nothing about runs for the recorder's no-guide runtime.
        val stopMs = programme?.stopMs?.takeIf { it > startMs }
            ?: (startMs + RecordingSchedule.NO_GUIDE_RUNTIME_MINUTES * 60_000L)
        val root = MediaRoot.of(context, settings.downloadRoot.first()).also { it.ensureFolders() }
        val title = programme?.title ?: channel.name
        val file = root.child(MediaFolders.TV, RecordingRules.fileName(channel.name, title, startMs))
            ?: return@withContext false
        if (!file.ensureWritable()) return@withContext false
        val newRow = RecordingEntity(
            profileId = profileId,
            sourceId = channel.sourceId,
            channelId = channel.id,
            channelName = channel.name,
            channelIconUrl = channel.logoUrl,
            epgChannelId = channel.epgChannelId,
            streamUrl = channel.streamUrl,
            httpHeaders = channel.httpHeaders,
            title = title,
            description = programme?.description,
            programmeStartMs = programme?.startMs ?: startMs,
            programmeStopMs = programme?.stopMs ?: stopMs,
            startMs = startMs,
            stopMs = stopMs,
            status = RecordingStatus.RECORDING,
            filePath = file.stored,
            startedAt = startMs,
        )
        val id = recordingDao.upsert(newRow)
        val saved = newRow.copy(id = if (id > 0) id else newRow.id)
        row = saved
        target = file
        closing = false
        _running.value = Running(saved.id, channel.id, startMs)

        copy = scope.launch {
            // The live edge, resolved now: the buffer is a live stream, and a reader that blocks at
            // its edge is exactly the tap this needs. No HTTP request is made for it.
            val stream = runCatching { session.open(null) }.getOrNull()
            if (stream == null) {
                finish()
                return@launch
            }
            input = stream
            try {
                file.openOutput(append = false).use { out ->
                    val buffer = ByteArray(COPY_CHUNK_BYTES)
                    // A closed buffer ends the reader, which is how a channel that went away ends the
                    // recording: whatever was captured stays.
                    while (isActive && !session.isClosed) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            } catch (_: Exception) {
                // The buffer closed under the read, or the volume filled. The bytes already written
                // are the recording, and the row below says how it ended.
            } finally {
                runCatching { stream.close() }
            }
            finish()
        }
        // The row's byte count follows the file every few seconds, so the pill and the list show a
        // growing recording rather than 0 B.
        ticker = scope.launch {
            while (isActive) {
                delay(PROGRESS_MS)
                val current = row ?: break
                if (_running.value == null) break
                recordingDao.updateProgress(
                    id = current.id,
                    status = RecordingStatus.RECORDING,
                    failure = RecordingFailure.NONE,
                    bytes = target?.length() ?: 0L,
                    filePath = current.filePath,
                    startedAt = current.startedAt,
                    endedAt = null,
                    timestamp = System.currentTimeMillis(),
                )
            }
        }
        watcher = scope.launch {
            recordingDao.observeRunning().collect { rows ->
                val current = row ?: return@collect
                if (_running.value?.id == current.id && rows.none { it.id == current.id }) {
                    // Stopped from Downloads or the channel list: the row is already closed out
                    // there, so only the taping has to stop here.
                    finish(updateRow = false)
                }
            }
        }
        true
    }

    /** Stop taping and close the row out, keeping every byte captured. */
    fun stop() {
        if (_running.value == null) return
        scope.launch { finish() }
    }

    /**
     * Stop taping. [updateRow] closes the row out here — false when something else already did, which
     * is what a stop from Downloads looks like: the row is COMPLETED and its file must stop growing.
     */
    private suspend fun finish(updateRow: Boolean = true) {
        if (closing) return
        closing = true
        // Not this coroutine: a recording that ended by itself runs this from the copy job, and
        // cancelling it would throw at the first suspend below, leaving the row saying RECORDING.
        val self = currentCoroutineContext()[Job]
        if (copy !== self) copy?.cancel()
        copy = null
        runCatching { input?.close() }
        input = null
        ticker?.cancel()
        ticker = null
        try {
            val current = row
            val file = target
            if (current != null && updateRow) {
                withContext(NonCancellable) {
                    val bytes = file?.length() ?: 0L
                    if (file?.exists() != true) {
                        // Nothing reached the disk at all: a row naming a file that is not there is
                        // worse than no row, and the user gained nothing.
                        recordingDao.delete(current)
                    } else {
                        val (status, failure) = RecordingRules.outcomeOf(bytes, RecordingFailure.NONE)
                        recordingDao.updateProgress(
                            id = current.id,
                            status = status,
                            failure = failure,
                            bytes = bytes,
                            filePath = current.filePath,
                            startedAt = current.startedAt,
                            endedAt = System.currentTimeMillis(),
                            timestamp = System.currentTimeMillis(),
                        )
                    }
                }
            }
            row = null
            target = null
            _running.value = null
        } finally {
            watcher?.cancel()
            watcher = null
        }
    }

    private companion object {
        const val COPY_CHUNK_BYTES = 64 * 1024
        /** How often the row's byte count follows the file. */
        const val PROGRESS_MS = 4_000L
    }
}
