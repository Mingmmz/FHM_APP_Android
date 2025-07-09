package com.example.dopfone

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class UserInfoActivity : AppCompatActivity() {
    private lateinit var etId: EditText
    private lateinit var etGT: EditText
    private lateinit var etG:  EditText
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_info)

        etId = findViewById<EditText>(R.id.etPatientId)
        etGT = findViewById<EditText>(R.id.etGroundTruth)
        etG  = findViewById<EditText>(R.id.etGestation)
        val btn  = findViewById<Button>(R.id.btnNext)

        btn.setOnClickListener {
            val id = etId.text.toString()
            val gt = etGT.text.toString()
            val gp = etG.text.toString()
            if (id.isBlank() || gt.isBlank() || gp.isBlank()) return@setOnClickListener

            // launch the recording screen
            Intent(this, RecordActivity::class.java).also {
                it.putExtra("ID", id)
                it.putExtra("GT", gt)
                it.putExtra("GP", gp)
                startActivity(it)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        // clear all three fields whenever we return here
        etId.text.clear()
        etGT.text.clear()
        etG.text.clear()
    }
}
