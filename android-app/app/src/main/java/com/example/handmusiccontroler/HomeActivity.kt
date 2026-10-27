package com.example.handmusiccontroler

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val btnPiano = findViewById<Button>(R.id.btnPiano)
        val btnFlute = findViewById<Button>(R.id.btnFlute)
        val btnGuitar = findViewById<Button>(R.id.btnGuitar)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)
        val btnContainer = findViewById<LinearLayout>(R.id.btnContainer)
        val tvFooter = findViewById<TextView>(R.id.tvFooter)

        tvTitle.alpha = 0f
        tvTitle.translationY = -50f
        tvTitle.animate().alpha(1f).translationY(0f).setDuration(800).start()

        tvSubtitle.alpha = 0f
        tvSubtitle.translationY = -50f
        tvSubtitle.animate().alpha(1f).translationY(0f).setDuration(800).setStartDelay(200).start()

        btnContainer.alpha = 0f
        btnContainer.translationY = 100f
        btnContainer.animate().alpha(1f).translationY(0f).setDuration(800).setStartDelay(400).start()

        tvFooter.alpha = 0f
        tvFooter.animate().alpha(1f).setDuration(1000).setStartDelay(800).start()

        fun animateAndStart(button: Button, instrumentCode: String) {
            button.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(100)
                .withEndAction {
                    button.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .withEndAction {
                            val intent = Intent(this@HomeActivity, MainActivity::class.java)
                            intent.putExtra("INSTRUMENT", instrumentCode)
                            startActivity(intent)
                        }
                        .start()
                }
                .start()
        }

        btnPiano.setOnClickListener { animateAndStart(btnPiano, "P") }
        btnFlute.setOnClickListener { animateAndStart(btnFlute, "D") }
        btnGuitar.setOnClickListener { animateAndStart(btnGuitar, "G") }
    }
}