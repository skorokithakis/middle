package com.middle.app.data

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Recording(
    val audioFile: File,
    val transcriptFile: File?,
    val timestamp: LocalDateTime,
    val durationSeconds: Float,
) {
    val hasTranscript: Boolean get() = transcriptFile?.exists() == true

    val transcriptText: String? get() = transcriptFile?.takeIf { it.exists() }?.readText()

    companion object {
        private val FILENAME_PATTERN = Regex("""recording_(\d{8}_\d{6})_\d+\.m4a""")
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        fun fromFile(audioFile: File): Recording? {
            val match = FILENAME_PATTERN.matchEntire(audioFile.name) ?: return null
            val timestamp = LocalDateTime.parse(match.groupValues[1], TIMESTAMP_FORMAT)
            val transcriptFile = File(audioFile.parent, audioFile.nameWithoutExtension + ".txt")

            // Rough estimate: AAC at 64kbps = ~8KB/s.
            val durationSeconds = audioFile.length().toFloat() / 8000f

            return Recording(
                audioFile = audioFile,
                transcriptFile = transcriptFile.takeIf { it.exists() },
                timestamp = timestamp,
                durationSeconds = durationSeconds,
            )
        }
    }
}
