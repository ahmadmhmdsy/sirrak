package app.sirrak.downloads

import app.sirrak.core.models.QueueStore
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [QueueStore] backed by one JSON file in app-private storage.
 *
 * The queue's only recovery from a broken snapshot is "start empty", so a write cut short by process
 * death must not leave half-written JSON behind: [write] always writes a sibling temporary file and
 * renames it over the real one. A rename within one filesystem is the single atomic operation both
 * Android/Linux and Windows expose, so a crash leaves either the previous list or the new one.
 *
 * It is plain `java.io`, with the file passed in rather than an Android `Context`, so the persistence
 * can be tested on a JVM with a temp directory. Callers place it with
 * `QueueFileStore(File(context.filesDir, "download_queue.json"))`.
 *
 * Honest limit: the rename is atomic but is not an fsync; a sudden power loss can lose the newest
 * snapshot (the queue then starts one progress tick older), never a torn one.
 */
class QueueFileStore(private val file: File) : QueueStore {

    /** Null before the first write, so "first launch" stays distinguishable from "empty queue". */
    override fun read(): String? = if (file.exists()) file.readText() else null

    /**
     * Replaces the snapshot atomically. I/O failures are thrown and never swallowed: a list that
     * silently fails to save would claim a durability it does not have, which is the exact lie this
     * rework removes from the screen.
     */
    override fun write(text: String) {
        val directory = file.absoluteFile.parentFile
        if (directory != null && !directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IOException("cannot create download queue directory: $directory")
        }
        val temporary = File(directory, file.name + TEMPORARY_SUFFIX)
        try {
            temporary.writeText(text)
            moveIntoPlace(temporary, file)
        } finally {
            // A successful rename consumed the temp file; only a failed move leaves one behind.
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            // Some filesystems (and some Windows volume setups) cannot replace atomically. A plain
            // replace is still better than a truncated file, and the next write repairs the snapshot.
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}
