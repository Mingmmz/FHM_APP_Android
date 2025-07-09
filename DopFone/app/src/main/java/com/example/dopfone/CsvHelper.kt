package com.example.dopfone

import android.content.Context
import com.opencsv.CSVWriter
import java.io.File

object CsvHelper {
    private fun csvFile(ctx: Context): File {
        return File(ctx.getExternalFilesDir(null), "records.csv").apply {
            parentFile?.mkdirs()
        }
    }

    /**
     * Append one row of data.  On first ever write, emit a header line:
     * [Filename, PatientID, GroundTruth, Gestation, Notes]
     */
    fun appendRowWithNotes(ctx: Context, row: List<String>) {
        val file = csvFile(ctx)
        val writeHeader = !file.exists()
        CSVWriter(file.writer()).use { writer ->
            if (writeHeader) {
                writer.writeNext(arrayOf(
                    "Filename", "PatientID", "GroundTruth", "Gestation", "Notes"
                ))
            }
            writer.writeNext(row.toTypedArray())
        }
    }
}
