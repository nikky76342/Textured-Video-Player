package com.nikhil.texturedvideoplayer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MyTestActivity : AppCompatActivity() {

    private val video_url =
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4"
    private lateinit var customTextureView: CustomTextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mytest)
        customTextureView = findViewById(R.id.customTextureView)
        customTextureView.setVideoUri(
            this,
            video_url,
            object : CustomTextureView.OnVideoPrepareListener {
                override fun onVideoPrepared() {
                    customTextureView.playVideo()
                }
            }, null, object : CustomTextureView.OnVideoCompleteListener {
                override fun onVideoComplete() {
                    Toast.makeText(this@MyTestActivity, "Video Ended!", Toast.LENGTH_SHORT)
                        .show()
                }
            })
    }
}