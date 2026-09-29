package com.example.myapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var count = 0

        val label = TextView(this)
        label.text = "Taps: 0"
        label.textSize = 30f
        label.gravity = Gravity.CENTER

        val button = Button(this)
        button.text = "Tap me"
        button.setOnClickListener {
            count++
            label.text = "Taps: $count"
        }

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER
        layout.addView(label)
        layout.addView(button)

        setContentView(layout)
    }
}
