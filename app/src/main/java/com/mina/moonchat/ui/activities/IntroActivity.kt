package com.mina.moonchat.ui.activities

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.mina.moonchat.R

class IntroActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_MoonChat)
        setContentView(R.layout.activity_intro)
    }
}