package com.nikhil.texturedvideoplayer

import android.content.Context
import android.content.DialogInterface
import android.database.ContentObserver
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

//This should be at top level of this file to make the below scenario work
//Play Video -> Open FullScreen -> Pause Video -> Minimise app -> Reopen from Recent Apps
//ER: Video should be in paused state
//AR: The video controls stay in paused state but the video keeps playing.
private var pausedByUser = false

//This should be at top level of this file to make the below scenario work
//Play Video -> Let Video End -> Open another instance of this custom view -> Let its video end too.
//ER: mUpdateHandler keeps executing the updateSeekBarProgress() even though mUpdateHandler.removeCallbacksAndMessages(null) is called
//in videoPlayCompleted()
private var pauseHandler = false
private var isMuted = false

class CustomTextureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    //UI
    private val mTextureView: TextureView
    private val mLoControls: ConstraintLayout
    private val mIvAudioOn: ImageView
    private val mIvAudioOff: ImageView
    private val mIvPlay: ImageView
    private val mIvPause: ImageView
    private val mIvForward: ImageView
    private val mIvClose: ImageView
    private val mIvRewind: ImageView
    private val mIvFullScreen: ImageView
    private val mIvHalfScreen: ImageView
    private val mIvMaskControls: View
    private val mTvDurationDone: TextView
    private val mTvDurationLeft: TextView
    private var mSeekBar: SeekBar

    // lateinits
    private lateinit var mMediaPlayer: MediaPlayer
    private lateinit var mHostContext: Any
    private lateinit var mUri: Uri
    private lateinit var mOnVideoCloseListener: OnVideoCloseListener
    private lateinit var mSurface: Surface
    private lateinit var mDialogFragment: DialogFragment

    // inits
    private val mUpdateHandler = Handler(Looper.myLooper()!!)
    private val mAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mIsSeeking = false
    private var mVideoPrepared = false
    private var initialResume = true
    private var mCurrentVolume = 0
    private var mMaxVolume = 0

    init {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        inflater.inflate(R.layout.custom_texture_view, this, true)
        mTextureView = findViewById(R.id.textureView)
        mLoControls = findViewById(R.id.loControls)
        mIvAudioOn = findViewById(R.id.ivAudioOn)
        mIvAudioOff = findViewById(R.id.ivAudioOff)
        mIvPlay = findViewById(R.id.ivPlay)
        mIvPause = findViewById(R.id.ivPause)
        mIvForward = findViewById(R.id.ivForward)
        mIvClose = findViewById(R.id.ivClose)
        mIvRewind = findViewById(R.id.ivRewind)
        mIvFullScreen = findViewById(R.id.ivFullScreen)
        mIvHalfScreen = findViewById(R.id.ivHalfScreen)
        mIvMaskControls = findViewById(R.id.ivMaskControls)
        mTvDurationDone = findViewById(R.id.tvDurationDone)
        mTvDurationLeft = findViewById(R.id.tvDurationLeft)
        mSeekBar = findViewById(R.id.seekBar)
        mIvAudioOn.setOnClickListener { audioAlter(true) }
        mIvAudioOff.setOnClickListener { audioAlter(false) }
        mIvPlay.setOnClickListener { playVideo() }
        mIvPause.setOnClickListener {
            pausedByUser = true
            pauseVideo()
        }
        mIvForward.setOnClickListener { forwardVideo() }
        mIvRewind.setOnClickListener { rewindVideo() }
        mIvFullScreen.setOnClickListener {
            mDialogFragment =
                VideoDialogFragment(this, mMediaPlayer, object : OnVideoCloseListener {
                    override fun onVideoClosed(muted: Boolean) {
                        mMediaPlayer.setSurface(mSurface)
                        audioAlter(muted)
                        if (mMediaPlayer.isPlaying) playVideo() else pauseVideo()
                    }
                })
            val fragmentManager = when (mHostContext) {
                is AppCompatActivity -> (mHostContext as AppCompatActivity).supportFragmentManager
                is Fragment -> (mHostContext as Fragment).childFragmentManager
                else -> throw IllegalArgumentException("hostContext must be AppCompatActivity or Fragment")
            }
            mDialogFragment.show(fragmentManager, "video_dialog")
        }
        mIvHalfScreen.setOnClickListener { closeFragment() }
        mIvClose.setOnClickListener { closeFragment() }
        setVolumeChangeListener()
    }

    private fun audioAlter(mute: Boolean) {
        if (mute) {
            setVolume(0)
            mIvAudioOn.visibility = GONE
            mIvAudioOff.visibility = VISIBLE
            isMuted = true
        } else {
            mIvAudioOff.visibility = GONE
            mIvAudioOn.visibility = VISIBLE
            isMuted = false
            getAudioResource()
        }
    }

    private fun closeFragment() {
        mOnVideoCloseListener.onVideoClosed(isMuted)
    }

    private fun updateSeekBarProgress() {
        if (!mIsSeeking) {
            var progress = mMediaPlayer.currentPosition
            mSeekBar.progress = progress
            progress /= 1000
            mTvDurationDone.text = secondsToTime(progress)
            Log.d("sdfjhsdgfjd", "${mTvDurationDone.text} two")
            mTvDurationLeft.text = "- ${secondsToTime(mSeekBar.max / 1000 - progress)}"
        }
        if (!pauseHandler)
            mUpdateHandler.postDelayed(::updateSeekBarProgress, 100)
    }

    private fun Lifecycle.setLifeCycleObserver() {
        this.addObserver(LifecycleEventObserver { source, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    // Handle ON_CREATE event
                    Log.d("Lifecycle Tag", "Activity/Fragment ON_CREATE")
                }

                Lifecycle.Event.ON_START -> {
                    // Handle ON_START event
                    Log.d("Lifecycle Tag", "Activity/Fragment ON_START")
                }

                Lifecycle.Event.ON_RESUME -> {
                    // Handle ON_RESUME event
                    Log.d("Lifecycle Tag", "Activity/Fragment ON_RESUME")
                    if (initialResume) initialResume = false
                    else if (!pausedByUser) playVideo()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    // Handle ON_PAUSE event
                    Log.d("Lifecycle Tag", "Activity/Fragment ON_PAUSE")
                    pauseVideo()
                }

                Lifecycle.Event.ON_STOP -> {
                    // Handle ON_STOP event
                    Log.d("Lifecycle Tag", "Activity/Fragment ON_STOP")
                }

                Lifecycle.Event.ON_DESTROY -> {
                    // Handle ON_DESTROY event
                    Log.d("Lifecycle Tag", "Activity/Fragment ON_DESTROY")
                }

                else -> {
                    Log.d("Lifecycle Tag", "Activity/Fragment OTHER")
                }
            }
        })
    }

    fun setVideoUri(
        hostContext: Any,
        videoSource: Any?,
        onVideoPrepareListener: OnVideoPrepareListener? = null,
        onVideoCloseListener: OnVideoCloseListener? = null,
        onVideoCompleteListener: OnVideoCompleteListener? = null,
        mediaPlayer: MediaPlayer? = null,
        isFullscreen: Boolean = false
    ) {
        mHostContext = hostContext
        if (mediaPlayer == null) {
            when (mHostContext) {
                is AppCompatActivity -> (mHostContext as AppCompatActivity)
                is Fragment -> (mHostContext as Fragment)
                else -> throw IllegalArgumentException("hostContext must be AppCompatActivity or Fragment")
            }.lifecycle.setLifeCycleObserver()
            mUri = if (videoSource is String) Uri.parse(videoSource)
            else if (videoSource is Uri) videoSource
            else throw Exception("Video Source should either be a URI or URL")
            mMediaPlayer = MediaPlayer()
            mMediaPlayer.setDataSource(context, mUri)
            mMediaPlayer.prepareAsync()
            mMediaPlayer.setOnPreparedListener {
                afterPrepared(
                    onVideoPrepareListener,
                    isFullscreen
                )
            }
            mMediaPlayer.setOnErrorListener { mp, what, extra ->
                mVideoPrepared = false
                disableControls(isFullscreen)
                false
            }

        } else {
            mMediaPlayer = mediaPlayer
            if (onVideoCloseListener != null) mOnVideoCloseListener = onVideoCloseListener
            audioAlter(isMuted)
            afterPrepared(onVideoPrepareListener, isFullscreen)
        }
        mMediaPlayer.setOnCompletionListener { mediaPlayer2 ->
            videoPlayCompleted()
            onVideoCompleteListener?.onVideoComplete()
        }
        mMediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
            setAspectRatio(
                mTextureView,
                width,
                height
            )
        }
        mSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar2: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mMediaPlayer.seekTo(progress)
                    mTvDurationDone.text = secondsToTime(progress / 1000)
                    mTvDurationLeft.text =
                        "- ${secondsToTime(mSeekBar.max / 1000 - progress / 1000)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                mIsSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mIsSeeking = false
            }
        })
        mTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture, width: Int, height: Int
            ) {
                // Set up MediaPlayer when the TextureView's SurfaceTexture is ready
                mSurface = Surface(surface)
                mMediaPlayer.setSurface(mSurface)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    fun videoPlayCompleted() {
        mUpdateHandler.removeCallbacksAndMessages(null)
        pauseHandler = true
        mSeekBar.progress = 0
        videoNotPlaying()
        mTvDurationDone.text = "0:00"
        mTvDurationLeft.text = "- ${secondsToTime(mSeekBar.max / 1000)}"
    }

    private fun afterPrepared(
        onVideoPrepareListener: OnVideoPrepareListener?,
        isFullscreen: Boolean
    ) {
        mVideoPrepared = true
        onVideoPrepareListener?.onVideoPrepared()
        if (isFullscreen) {
            mIvRewind.visibility = VISIBLE
            mIvForward.visibility = VISIBLE
            mIvClose.visibility = VISIBLE
            mIvFullScreen.visibility = INVISIBLE
            mIvHalfScreen.visibility = VISIBLE
            if (mMediaPlayer.isPlaying) playVideo() else pauseVideo()
        }
        enableControls(isFullscreen)
        mSeekBar.max = mMediaPlayer.duration
        updateSeekBarProgress()
    }

    private fun setAspectRatio(
        textureView: TextureView,
        nW: Int = textureView.width,
        nH: Int = textureView.height
    ) {
        val layoutParams = textureView.layoutParams as ConstraintLayout.LayoutParams
        val videoAspectRatio = nW.toFloat() / nH
        val screenAspectRatio = this.width.toFloat() / this.height
        if (videoAspectRatio > screenAspectRatio) {
            // Video is wider, adjust height to maintain aspect ratio
            val newHeight = (this.width / videoAspectRatio).toInt()
            layoutParams.width = this.width
            layoutParams.height = newHeight
        } else {
            // Video is taller, adjust width to maintain aspect ratio
            val newWidth = (this.height * videoAspectRatio).toInt()
            layoutParams.width = newWidth
            layoutParams.height = this.height
        }
        textureView.layoutParams = layoutParams
        textureView.requestLayout()
    }

    private fun videoPlaying() {
        mIvPlay.visibility = INVISIBLE
        mIvPause.visibility = VISIBLE
    }

    private fun videoNotPlaying() {
        mIvPause.visibility = INVISIBLE
        mIvPlay.visibility = VISIBLE
    }

    //Video Controls

    fun playVideo() {
        if (mVideoPrepared) {
            mMediaPlayer.start()
            pausedByUser = false
            pauseHandler = false
            mUpdateHandler.removeCallbacksAndMessages(null)
            updateSeekBarProgress()
            videoPlaying()
        } else Toast.makeText(context, "Video not ready yet!", Toast.LENGTH_SHORT).show()
    }

    fun pauseVideo() {
        if (mVideoPrepared) {
            mMediaPlayer.pause()
            videoNotPlaying()
        }
    }

    private fun forwardVideo() {
        mUpdateHandler.removeCallbacksAndMessages(null)
        updateSeekBarProgress()
        mMediaPlayer.seekTo(addCurrentTime(5))
    }

    private fun rewindVideo() {
        mUpdateHandler.removeCallbacksAndMessages(null)
        updateSeekBarProgress()
        mMediaPlayer.seekTo(addCurrentTime(-5))
    }

    private fun disableControls(isFullscreen: Boolean) {
        //Below commented functionality is to handle the visibility of video controls as per business requirements.
        //if(isFullscreen)
        mIvMaskControls.visibility = VISIBLE
        //else mLoControls.visibility = INVISIBLE
    }

    private fun enableControls(isFullscreen: Boolean) {
        //Below commented functionality is to handle the visibility of video controls as per business requirements.
        //if(isFullscreen)
        mIvMaskControls.visibility = INVISIBLE
        //else mLoControls.visibility = VISIBLE
    }

    private fun setVolumeChangeListener() {
        mMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumeObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                getAudioResource()
            }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, volumeObserver
        )
        getAudioResource()
    }

    private fun setVolume(volume: Int = mCurrentVolume) {
        Log.d("dsfdsfsefes", "$volume")
        val newVolume = volume / mMaxVolume.toFloat()
        if (mVideoPrepared)
            mMediaPlayer.setVolume(newVolume, newVolume)
//        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
    }

    private fun getAudioResource() {
        mCurrentVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (!isMuted) {
            //Segregating the volume level into 4 slabs
            val slabCount = 3
            val slabFactor = (mMaxVolume / slabCount).toFloat()
            mIvAudioOn.setImageResource(
                when (mCurrentVolume.toFloat()) {
                    in 0f..slabFactor -> R.drawable.ic_sound_0
                    in slabFactor + 1..slabFactor * 2 -> R.drawable.ic_sound_1
                    in (slabFactor * 2) + 1..(slabFactor * 3) - 1 -> R.drawable.ic_sound_2
                    else -> R.drawable.ic_sound_3
                }
            )
            setVolume()
        }
    }

    //Utils
    private fun secondsToTime(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes < 10) {
            String.format("%d:%02d", minutes, remainingSeconds)
        } else {
            String.format("%02d:%02d", minutes, remainingSeconds)
        }
    }

    private fun addCurrentTime(sec: Int): Int {
        return mMediaPlayer.currentPosition + (sec * 1000)
    }

    //Interfaces
    interface OnVideoPrepareListener {
        fun onVideoPrepared()
    }

    interface OnVideoCloseListener {
        fun onVideoClosed(muted: Boolean)
    }

    interface OnVideoCompleteListener {
        fun onVideoComplete()
    }

    //DialogFragment for Full Screen View
    class VideoDialogFragment(
        private var customTextureView: CustomTextureView, private var mediaPlayer: MediaPlayer,
        private var onVideoCloseListener: OnVideoCloseListener
    ) : DialogFragment() {

        private lateinit var window: Window

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setStyle(STYLE_NO_FRAME, R.style.AppTheme_VideoDialog)
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val rootView = inflater.inflate(R.layout.fragment_full_screen_dialog, container, false)
            val vid = rootView.findViewById<CustomTextureView>(R.id.customVideoView)
            vid.setVideoUri(this, null, null, object : OnVideoCloseListener {
                override fun onVideoClosed(muted: Boolean) {
                    onVideoCloseListener.onVideoClosed(muted)
                    //Intentional Delay to let the previous screen's surface get updated
                    // by the time this DialogFragment gets dismissed
                    Handler(Looper.myLooper()!!).postDelayed({ dismiss() }, 75)
                }
            }, object : OnVideoCompleteListener {
                override fun onVideoComplete() {
                    //Manually resetting the video controls of the under lying view
                    // as it isn't getting reset automatically
                    customTextureView.videoPlayCompleted()
                }
            }, mediaPlayer, true)
            window = dialog?.window!!
            return rootView
        }

        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            onVideoCloseListener.onVideoClosed(isMuted)
        }
    }
}