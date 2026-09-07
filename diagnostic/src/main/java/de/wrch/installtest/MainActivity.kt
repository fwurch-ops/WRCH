package de.wrch.installtest

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = "WRCH Installtest erfolgreich"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        setContentView(text)
    }
}
