package com.example.dopfone

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.opencsv.CSVWriter
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class RecordActivity : AppCompatActivity() {

    companion object {
        private const val REQ_AUDIO      = 123
        private const val SAMPLE_RATE    = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
        private const val TOTAL_MS       = 60_000L
    }

    private lateinit var timerView: TextView
    private lateinit var btnRecord: Button
    private lateinit var timer: CountDownTimer

    private var isRecording   = false
    private var dialogShown   = false
    private var tempPcm       = ByteArray(0)
    private var recorder: AudioRecord? = null
    private var player: MediaPlayer?    = null

    private lateinit var patientId:   String
    private lateinit var groundTruth: String
    private lateinit var gestation:   String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        // 1) Request AUDIO permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO
            )
        }

        // 2) Read extras
        patientId   = intent.getStringExtra("ID")!!
        groundTruth = intent.getStringExtra("GT")!!
        gestation   = intent.getStringExtra("GP")!!
        findViewById<TextView>(R.id.tvInfo).text =
            "PatientID: $patientId   GT: $groundTruth   GW: $gestation"

        // 3) Hook up views
        timerView = findViewById(R.id.tvTimer)
        btnRecord = findViewById(R.id.btnRecord)

        // 4) Initialize countdown
        initTimer()

        btnRecord.setOnClickListener {
            // reset state
            dialogShown = false
            if (::timer.isInitialized) timer.cancel()
            initTimer()

            startPlayAndRecord()
            timer.start()
            btnRecord.isEnabled = false
        }
    }

    /** Sets up a fresh 60s timer */
    private fun initTimer() {
        timerView.text = "Remaining: 01:00"
        timer = object : CountDownTimer(TOTAL_MS, 1_000) {
            override fun onTick(remMs: Long) {
                val sec = (remMs / 1000).toInt()
                timerView.text = "Remaining: %02d:%02d".format(sec/60, sec%60)
            }
            override fun onFinish() {
                if (!dialogShown) {
                    dialogShown = true
                    stopPlayAndRecord()
                    showSaveDialog()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPlayAndRecord() {
        // 1) Start AudioRecord
        try {
            recorder?.release()
            val bufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
            ).apply {
                if (state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord init failed")
                }
            }

            isRecording = true
            recorder!!.startRecording()

            // Record on background thread
            Thread {
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(bufSize)
                // Loop until main thread flips isRecording = false
                while (isRecording) {
                    val read = recorder!!.read(buffer, 0, buffer.size)
                    if (read > 0) baos.write(buffer, 0, read)
                }
                // **No recorder.stop() here!** we handle stop() in stopPlayAndRecord()
                tempPcm = baos.toByteArray()
                baos.close()
            }.start()

        } catch (e: Exception) {
            Toast.makeText(this,
                "Recorder error: ${e.message}", Toast.LENGTH_LONG
            ).show()
            btnRecord.isEnabled = true
            return
        }

        // 2) Play 18kHz tone
        try {
            player?.release()
            player = MediaPlayer.create(this, R.raw.tone18khz).apply {
                setOnCompletionListener {
                    if (isRecording && !dialogShown) {
                        dialogShown = true
                        stopPlayAndRecord()
                        timer.cancel()
                        showSaveDialog()
                    }
                }
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this,
                "Playback error: ${e.message}", Toast.LENGTH_LONG
            ).show()
            stopPlayAndRecord()
        }
    }

    /** Single place to stop & release both recorder & player */
    private fun stopPlayAndRecord() {
        isRecording = false

        player?.apply {
            try { stop() } catch (_:Throwable) {}
            release()
        }
        recorder?.apply {
            try { stop() } catch (_:Throwable) {}
            release()
        }
    }

    private fun showSaveDialog() {
        runOnUiThread {
            val notesInput = EditText(this).apply {
                hint = "Comments"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            AlertDialog.Builder(this)
                .setTitle("Recording Ended")
                .setView(notesInput)
                .setCancelable(false)
                .setPositiveButton("Save") { d,_->
                    saveRecording(notesInput.text.toString(), false)
                    d.dismiss()
                    finish()
                }
                .setNegativeButton("Discard") { d,_->
                    d.dismiss()
                    // allow retry on same page
                    initTimer()
                    btnRecord.isEnabled = true
                }
                .show()
        }
    }

    private fun saveRecording(notes: String, incomplete: Boolean) {
        // build filename
        val ts     = SimpleDateFormat("MMddyyyy_HHmmss", Locale.US).format(Date())
        val suffix = if (incomplete) "_INCOMPLETE" else ""
        val fn     = "PatientID_${patientId}_Timestamp_${ts}${suffix}_18KHz.wav"

        // write WAV
        val dir     = getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!
        val outFile = File(dir, fn)
        FileOutputStream(outFile).use { fos ->
            fos.write(createWavHeader(
                tempPcm.size.toLong(), SAMPLE_RATE, 1, 16
            ))
            fos.write(tempPcm)
        }
        Toast.makeText(this,
            "Saved: ${outFile.absolutePath}", Toast.LENGTH_LONG
        ).show()

        // append CSV (with header on first write)
        val csv = File(getExternalFilesDir(null), "records.csv")
        csv.parentFile?.mkdirs()
        val needHeader = !csv.exists() || csv.length() == 0L
        CSVWriter(FileWriter(csv, true)).use { w ->
            if (needHeader) {
                w.writeNext(arrayOf(
                    "Filename","PatientID","GroundTruth","GestationWeek","Notes"
                ))
            }
            w.writeNext(arrayOf(fn, patientId, groundTruth, gestation, notes))
        }
    }

    private fun createWavHeader(
        pcmSize: Long, sampleRate: Int, channels: Int, bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val riffSize = 36 + pcmSize
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(riffSize.toInt())
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(channels.toShort())
            .putInt(sampleRate)
            .putInt(byteRate)
            .putShort((channels * bitsPerSample / 8).toShort())
            .putShort(bitsPerSample.toShort())
            .put("data".toByteArray())
            .putInt(pcmSize.toInt())
            .array()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this,
                "Audio permission is required", Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
}
