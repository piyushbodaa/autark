package com.piyushbodaa.autark.models

import android.app.DownloadManager
import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Gets the model onto the phone with the system DownloadManager: it survives the app being
 * closed, resumes after network drops and shows its own progress notification. The model is
 * ready the moment the download is.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope, val model: CatalogModel) {

    sealed interface Status {
        data object Absent : Status
        data class Downloading(val downloaded: Long, val total: Long, val waiting: Boolean) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private val dir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }
    val file = File(dir, model.fileName)

    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private var poller: Job? = null

    private val _status = MutableStateFlow<Status>(Status.Absent)
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        removeOtherModels()
        when {
            downloadId() >= 0 -> {
                _status.value = Status.Downloading(0, model.sizeBytes, waiting = false)
                startPolling()
            }
            isDownloaded() -> _status.value = Status.Ready
        }
    }

    fun download() {
        file.delete()  // DownloadManager would otherwise save to "name-1.gguf"
        val request = DownloadManager.Request(model.url.toUri())
            .setTitle("Autark")
            .setDescription("Downloading Autark's model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "models", model.fileName)
            .setAllowedOverRoaming(false)
        val id = downloads.enqueue(request)
        prefs.edit {
            putLong(DOWNLOAD_KEY + model.id, id)
            remove(DONE_KEY + model.fileName)
        }
        _status.value = Status.Downloading(0, model.sizeBytes, waiting = false)
        startPolling()
    }

    fun cancel() {
        val id = downloadId()
        if (id >= 0) downloads.remove(id)  // also deletes the partial file
        prefs.edit { remove(DOWNLOAD_KEY + model.id) }
        file.delete()
        _status.value = Status.Absent
    }

    /** Earlier versions let people pick from several models; free up whatever they left behind. */
    private fun removeOtherModels() {
        val stale = prefs.all.keys.filter { it.startsWith(DOWNLOAD_KEY) && it != DOWNLOAD_KEY + model.id }
        prefs.edit {
            for (key in stale) {
                val id = prefs.getLong(key, -1)
                if (id >= 0) downloads.remove(id)
                remove(key)
            }
        }
        dir.listFiles().orEmpty().filter { it.name != model.fileName }.forEach { it.delete() }
    }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch(Dispatchers.IO) {
            while (downloadId() >= 0) {
                poll()
                delay(500)
            }
        }
    }

    private fun poll() {
        val id = downloadId()
        downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {  // cancelled from the notification
                prefs.edit { remove(DOWNLOAD_KEY + model.id) }
                _status.value = Status.Absent
                return
            }
            val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                .takeIf { it > 0 } ?: model.sizeBytes
            when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // Anything else (say, a Wi-Fi sign-in page served in its place) is the wrong size.
                    val whole = file.length() == model.sizeBytes
                    prefs.edit {
                        remove(DOWNLOAD_KEY + model.id)
                        if (whole) putBoolean(DONE_KEY + model.fileName, true)
                    }
                    if (whole) {
                        _status.value = Status.Ready
                    } else {
                        file.delete()
                        _status.value = Status.Failed("The download was incomplete. Please try again.")
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    downloads.remove(id)
                    prefs.edit { remove(DOWNLOAD_KEY + model.id) }
                    _status.value = Status.Failed(failureMessage(reason))
                }
                DownloadManager.STATUS_PAUSED -> _status.value = Status.Downloading(done, total, waiting = true)
                else -> _status.value = Status.Downloading(done, total, waiting = false)
            }
        }
    }

    // DownloadManager sets aside the whole file as soon as it starts, so the size alone says nothing.
    private fun isDownloaded() =
        prefs.getBoolean(DONE_KEY + model.fileName, false) && file.length() == model.sizeBytes

    private fun downloadId() = prefs.getLong(DOWNLOAD_KEY + model.id, -1)

    private fun failureMessage(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space on the phone."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME ->
            "The connection dropped. Please try again."
        else -> "The download failed. Please try again."
    }

    companion object {
        private const val DOWNLOAD_KEY = "download:"

        /** Set when a download finishes. Earlier versions set it once the file's SHA-256 matched. */
        private const val DONE_KEY = "verified:"
    }
}
