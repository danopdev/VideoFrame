package com.dan.videoframe

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.dan.videoframe.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    companion object {
        val PERMISSIONS = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        const val REQUEST_PERMISSIONS = 1
        const val INTENT_OPEN_VIDEO = 2
    }

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var videoUri: Uri? = null
    private var videoName  = ""
    private var videoDuration = 0
    private var videoPosition = 0
    private var saveFrameMenuItem: MenuItem? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaMetadataRetriever = MediaMetadataRetriever()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(grantResults)
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun exitApp() {
        setResult(0)
        finish()
    }

    private fun fatalError(msg: String) {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(msg)
                .setIcon(android.R.drawable.stat_notify_error)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> exitApp() }
                .show()
    }

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }

    private fun handleRequestPermissions(grantResults: IntArray) {
        var allowedAll = grantResults.size >= PERMISSIONS.size

        if (grantResults.size >= PERMISSIONS.size) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allowedAll = false
                    break
                }
            }
        }

        if (allowedAll) onPermissionsAllowed()
        else fatalError("You must allow permissions !")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun onPermissionsAllowed() {
        BusyDialog.create(this)

        binding.seekBarPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, p2: Boolean) {
                setVideoPosition(progress)
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
            }

        })

        binding.buttonSubMax.setOnClickListener { shiftVideoPosition(-2000) }
        binding.buttonSubMed.setOnClickListener { shiftVideoPosition(-500) }
        binding.buttonSubMin.setOnClickListener { shiftVideoPosition(-33) }
        binding.buttonAddMin.setOnClickListener { shiftVideoPosition(33) }
        binding.buttonAddMed.setOnClickListener { shiftVideoPosition(500) }
        binding.buttonAddMax.setOnClickListener { shiftVideoPosition(2000) }

        binding.videoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        binding.videoView.setOnPreparedListener { newMediaPlayer ->
            newMediaPlayer.setVolume(0.0f, 0.0f)
            videoDuration = newMediaPlayer.duration
            saveFrameMenuItem?.isEnabled = true
            mediaPlayer = newMediaPlayer
            updateAll()
            Log.i("[Video]", "duration: $videoDuration")
        }

        setContentView(binding.root)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.app_menu, menu)
        saveFrameMenuItem = menu?.findItem(R.id.menuSaveFrame)
        saveFrameMenuItem?.isEnabled = null != videoUri && videoDuration > 0
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.menuOpenVideo -> handleOpenVideo()
            R.id.menuSaveFrame -> handleSaveFrame()
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (resultCode == RESULT_OK) {
            if (requestCode == INTENT_OPEN_VIDEO) {
                intent?.data?.let { uri -> openVideo(uri) }
                return
            }
        }

        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, intent)
    }

    private fun saveFrame() {
        var success = false
        val fileName = "frame_${System.currentTimeMillis()}.png"

        try {
            mediaMetadataRetriever.setDataSource(applicationContext, videoUri)
            mediaMetadataRetriever.getFrameAtTime(videoPosition * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)?.let{ frameBitmap ->
                @Suppress("DEPRECATION")
                val picturesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val fileFullPath = "$picturesDirectory/$fileName"

                val outputStream = File(fileFullPath).outputStream()
                frameBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.close()

                val values = ContentValues()
                @Suppress("DEPRECATION")
                values.put(MediaStore.Images.Media.DATA, fileFullPath)
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                val newUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                success = newUri != null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        runOnUiThread {
            BusyDialog.dismiss()
            Toast.makeText(applicationContext, if (success) "Saved: $fileName" else "Failed !", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSaveFrame() {
        BusyDialog.show(supportFragmentManager)
        GlobalScope.launch(Dispatchers.IO) { saveFrame() }
    }

    private fun handleOpenVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            .putExtra(Intent.EXTRA_TITLE, "Select video")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("video/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, INTENT_OPEN_VIDEO)
    }

    private fun openVideo(videoUri: Uri) {
        mediaPlayer = null
        this.videoUri = videoUri
        binding.videoView.setVideoURI(videoUri)
        videoDuration = 0
        videoName = DocumentFile.fromSingleUri(applicationContext, videoUri)?.name ?: ""
        updateAll()
    }

    private fun updateAll() {
        val enabled = videoDuration > 0

        binding.seekBarPosition.isEnabled = enabled
        binding.seekBarPosition.progress = 0
        binding.buttonAddMax.isEnabled = enabled
        binding.buttonAddMed.isEnabled = enabled
        binding.buttonAddMin.isEnabled = enabled
        binding.buttonSubMin.isEnabled = enabled
        binding.buttonSubMed.isEnabled = enabled
        binding.buttonSubMax.isEnabled = enabled
        saveFrameMenuItem?.isEnabled = enabled
        binding.txtVideoName.text = if (enabled) videoName else ""

        if (enabled) {
            binding.seekBarPosition.max = videoDuration
            setVideoPosition(0, true)
        }
    }

    private fun shiftVideoPosition(delta: Int) {
        setVideoPosition(videoPosition + delta)
    }

    private fun setVideoPosition(newVideoPosition: Int, force: Boolean = false) {
        if (!force && newVideoPosition == this.videoPosition) return
        if (newVideoPosition < 0 || newVideoPosition >= videoDuration || videoDuration <= 0) return

        this.videoPosition = newVideoPosition
        binding.seekBarPosition.progress = newVideoPosition
        mediaPlayer?.seekTo(videoPosition.toLong(), MediaPlayer.SEEK_CLOSEST)
    }
}