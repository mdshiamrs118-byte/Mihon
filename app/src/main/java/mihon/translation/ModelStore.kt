package mihon.translation

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ImportResult(
    val imported: List<String>,
    /** File names that did not match any expected file. */
    val skipped: List<String>,
    /** Original (Hugging Face) names of files that are still missing. */
    val missing: List<String>,
)

/** Downloads, imports and stores the files of the offline ONNX engines. */
class ModelStore(context: Context) {
    private val root = File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, "models")

    fun dir(info: EngineInfo): File = File(root, info.id)

    fun isDownloaded(info: EngineInfo): Boolean =
        info.files.isNotEmpty() && info.files.all {
            val f = File(dir(info), it.relPath)
            f.exists() && f.length() > 0
        }

    fun delete(info: EngineInfo) {
        dir(info).deleteRecursively()
    }

    /** Resumable download of every file of [info]. [onProgress] gets a short human-readable status. */
    suspend fun download(info: EngineInfo, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val d = dir(info)
        d.mkdirs()
        for ((index, rf) in info.files.withIndex()) {
            val target = File(d, rf.relPath)
            if (target.exists() && target.length() > 0) continue
            val part = File(d, rf.relPath + ".part")
            var existing = if (part.exists()) part.length() else 0L
            val label = "file ${index + 1}/${info.files.size}"

            val conn = (URL(rf.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mihon (Android)")
                setRequestProperty("Accept-Encoding", "identity")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = conn.responseCode
                if (code == 416 && existing > 0) {
                    // The .part file already holds the whole file.
                    if (!part.renameTo(target)) throw IOException("Could not finish ${rf.relPath}")
                    continue
                }
                if (code != 200 && code != 206) {
                    throw IOException("HTTP $code from ${URL(rf.url).host} for ${rf.originalName}")
                }
                val type = conn.contentType ?: ""
                if (type.startsWith("text/html")) {
                    throw IOException("Got a web page instead of ${rf.originalName}")
                }

                val append = code == 206
                if (!append) existing = 0L
                val len = conn.contentLengthLong
                val total = if (len > 0) len + existing else -1L

                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = existing
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            ensureActive()
                            out.write(buf, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 400) {
                                lastReport = now
                                onProgress("Downloading $label: ${mb(done)}" + if (total > 0) " / ${mb(total)}" else "")
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("Could not finish ${rf.relPath}")
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Copies files the user saved elsewhere (e.g. downloaded with a browser) into the engine's folder.
     * Files are recognised by name: the original Hugging Face name or the name used inside the app,
     * ignoring browser suffixes such as "(1)".
     */
    suspend fun importFiles(info: EngineInfo, resolver: ContentResolver, uris: List<Uri>): ImportResult =
        withContext(Dispatchers.IO) {
            val d = dir(info)
            d.mkdirs()
            val imported = ArrayList<String>()
            val skipped = ArrayList<String>()
            for (uri in uris) {
                val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "unknown"
                val rf = Catalog.matchFile(info, name)
                if (rf == null) {
                    skipped.add(name)
                    continue
                }
                val part = File(d, rf.relPath + ".part")
                val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $name")
                input.use { src -> FileOutputStream(part).use { out -> src.copyTo(out) } }
                val target = File(d, rf.relPath)
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Could not store ${rf.relPath}")
                imported.add(rf.originalName)
            }
            val missing = info.files
                .filter { val f = File(d, it.relPath); !(f.exists() && f.length() > 0) }
                .map { it.originalName }
            ImportResult(imported, skipped, missing)
        }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)
}
