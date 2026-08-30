package dev.rusty.app

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Android side of the downloadable Piper voices: where they live on disk
 * (`filesDir/tts/voices/<id>/`), and the blocking download / verify / extract seams
 * [VoiceDownloadManager] runs. All decisions (phase machine, catalog, storage math) live in the
 * pure layer; this class only touches network, disk and digest.
 */
class PiperVoiceStore(private val context: Context) {

    private val voicesDir: File get() = File(context.filesDir, "tts/voices")

    fun voiceDir(id: String): File = File(voicesDir, id)

    /** A voice counts as installed once its directory was atomically published by [extract]. */
    fun isInstalled(id: String): Boolean = voiceDir(id).isDirectory

    fun installedIds(): Set<String> =
        voicesDir.listFiles()
            ?.filter { it.isDirectory && !it.name.endsWith(TMP_SUFFIX) }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

    /** Free bytes where the voices live, for the pre-download [PiperCatalog.requiredBytes] gate. */
    fun freeBytes(): Long = context.filesDir.usableSpace

    fun delete(id: String): Boolean = voiceDir(id).deleteRecursively()

    /**
     * Blocking download to a temp file next to the final directory (same filesystem, so the
     * eventual rename is atomic). GitHub release URLs redirect to object storage;
     * HttpURLConnection follows same-protocol redirects on its own.
     */
    fun download(voice: PiperVoice, onProgress: (Int?) -> Unit): File {
        voicesDir.mkdirs()
        val archive = File(voicesDir, "${voice.id}$ARCHIVE_SUFFIX")
        archive.delete()
        val conn = URL(voice.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            if (conn.responseCode !in 200..299) {
                throw IOException("download failed (HTTP ${conn.responseCode})")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: voice.sizeBytes
            conn.inputStream.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else null
                        if (pct != lastPct) {
                            lastPct = pct ?: -1
                            onProgress(pct)
                        }
                    }
                }
            }
            return archive
        } catch (t: Throwable) {
            archive.delete()
            throw t
        } finally {
            conn.disconnect()
        }
    }

    /** Streaming sha256 against the catalog's pin. */
    fun verify(voice: PiperVoice, archive: File): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(voice.sha256, ignoreCase = true)
    }

    /**
     * Unpacks the (already hash-verified) tar.bz2 into `<id>.tmp/` and atomically renames to
     * `<id>/` — [isInstalled] can never observe a half-written voice. The bundles nest
     * everything under one `vits-piper-<id>/` root, which is stripped so the runtime finds
     * `<id>.onnx` and `espeak-ng-data/` at the directory top. Entry paths are validated even
     * though the content is pinned by hash — defense in depth costs three lines.
     */
    fun extract(voice: PiperVoice, archive: File) {
        val tmp = File(voicesDir, "${voice.id}$TMP_SUFFIX")
        tmp.deleteRecursively()
        val target = voiceDir(voice.id)
        try {
            TarArchiveInputStream(
                BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())),
            ).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    // Strip the single "vits-piper-<id>" root segment.
                    val relative = entry.name.substringAfter('/', missingDelimiterValue = "")
                    if (relative.isEmpty()) continue
                    val out = File(tmp, relative)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                        throw IOException("archive entry escapes the voice directory: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
            target.deleteRecursively()
            if (!tmp.renameTo(target)) {
                throw IOException("could not publish the extracted voice")
            }
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        } finally {
            archive.delete()
        }
    }

    /** Installed catalog voices as picker rows, shared by the control runtime and the on-device
     *  settings picker so both list a downloaded voice identically. */
    fun installedRows(catalog: List<PiperVoice>): List<VoiceInfo> {
        val installed = installedIds()
        return catalog.filter { it.id in installed }.map { v ->
            VoiceInfo(
                id = v.selector, label = v.label, language = v.language,
                quality = v.quality, installed = true, requiresNetwork = false,
            )
        }
    }

    companion object {
        /** The parsed catalog, cached process-wide: the asset is immutable for the life of the
         *  process, and callers hit this from the settings panel's UI thread too — one read. */
        @Volatile private var cachedCatalog: List<PiperVoice>? = null

        /** Reads the curated catalog shipped in assets; empty on any failure (a build mistake —
         *  the file is checked in and covered by tests). */
        fun loadCatalog(context: Context): List<PiperVoice> =
            cachedCatalog ?: (
                runCatching {
                    context.assets.open("piper-voices.json").bufferedReader().use { it.readText() }
                }.getOrNull()?.let { PiperCatalog.parse(it) } ?: emptyList()
                ).also { cachedCatalog = it }

        private const val TMP_SUFFIX = ".tmp"
        private const val ARCHIVE_SUFFIX = ".tar.bz2.part"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
