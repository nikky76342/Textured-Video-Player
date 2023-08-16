package com.nikhil.texturedvideoplayer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MyTestActivity : AppCompatActivity() {

    private val video_url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4"
private lateinit var customTextureView: CustomTextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mytest)
        customTextureView = findViewById<CustomTextureView>(R.id.ctvnik)
        customTextureView.setVideoUri(this, video_url)
    }
}