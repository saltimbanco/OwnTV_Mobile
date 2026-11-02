package tv.own.owntv.mobile.playback

import android.content.Context
import android.net.Uri
import tv.own.owntv.core.storage.MediaRoot
import tv.own.owntv.core.storage.MediaTarget
import tv.own.owntv.core.storage.StorageAccess

/**
 * Where this phone writes the recordings it makes while watching.
 *
 * Its own folder, beside the downloads one: films and recordings are different piles, and the card
 * a user keeps a season of films on is not necessarily where an hour of last night's news belongs.
 *
 * **Blank means "the downloads folder"**, so a user who never opens the setting has exactly the one
 * media folder the app had before, and downloads and recordings still share `TV/`, `Movies/` and
 * `Series/` inside it.
 *
 * App-local [android.content.SharedPreferences] on purpose: the shared settings store lives in the
 * core artifact, which this fork does not change (the same reason `FailoverPrefs` does), so this
 * stays additive here.
 */
object RecordingFolder {

    private const val PREFS = "owntv_recording_folder"
    private const val KEY_ROOT = "recording_root"

    /** The stored folder as a `String` — a path, a `content://` tree, or blank for the downloads root. */
    fun get(context: Context): String = prefs(context).getString(KEY_ROOT, "").orEmpty()

    fun set(context: Context, stored: String) {
        prefs(context).edit().putString(KEY_ROOT, stored).apply()
    }

    /**
     * The root the recorder should write into: the chosen folder, or the downloads folder when none
     * has been chosen. A chosen folder that has gone away falls back the same way the downloads root
     * does — see [MediaRoot.of] — rather than failing recording after recording.
     */
    fun root(context: Context, downloadRoot: String): MediaRoot =
        MediaRoot.of(context, get(context).ifBlank { downloadRoot })

    /**
     * Remember a folder picked in the system picker.
     *
     * The grant has to be made persistable first: a tree URI is granted to the activity that asked
     * and lasts only as long as the process, while a recording is written later — sometimes days
     * later, by a service with no activity behind it. If the system refuses, the folder is not
     * adopted at all rather than adopted and silently unwritable tomorrow.
     */
    fun adoptTree(context: Context, tree: Uri): Boolean {
        if (!StorageAccess.persistAccess(context, tree)) return false
        release(context, get(context))
        set(context, tree.toString())
        return true
    }

    /** Hand back the grant on a folder that is no longer the recordings folder. */
    fun release(context: Context, stored: String?) {
        if (stored.isNullOrBlank() || !MediaTarget.isDocument(stored)) return
        StorageAccess.releaseTree(context, stored)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
