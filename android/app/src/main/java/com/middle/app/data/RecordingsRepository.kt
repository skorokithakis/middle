package com.middle.app.data

import android.content.Context
import com.middle.app.audio.AudioEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

class RecordingsRepository(context: Context) {

    private val recordingsDirectory = File(context.filesDir, "recordings").also { it.mkdirs() }

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings

    val directory: File get() = recordingsDirectory

    init {
        refresh()
    }

    fun refresh() {
        val files = recordingsDirectory.listFiles { file -> file.extension == "m4a" }
            ?: emptyArray()
        _recordings.value = files
            .mapNotNull { Recording.fromFile(it) }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Decode IMA ADPCM data, encode to M4A, and save to disk.
     */
    suspend fun saveEncodedRecording(imaData: ByteArray, filename: String): File {
        val file = File(recordingsDirectory, filename)
        withContext(Dispatchers.IO) {
            AudioEncoder.encodeFromIma(imaData, file)
        }
        refresh()
        return file
    }

    suspend fun deleteRecording(recording: Recording) {
        withContext(Dispatchers.IO) {
            recording.audioFile.delete()
            val transcriptFile = File(
                recording.audioFile.parent,
                recording.audioFile.nameWithoutExtension + ".txt",
            )
            if (transcriptFile.exists()) {
                transcriptFile.delete()
            }
        }
        refresh()
    }

    suspend fun saveTranscript(text: String, audioFile: File): File {
        val transcriptFile = File(audioFile.parent, audioFile.nameWithoutExtension + ".txt")
        withContext(Dispatchers.IO) {
            transcriptFile.writeText(text)
        }
        refresh()
        return transcriptFile
    }
}
