package com.allsocial.sealclone

import android.app.Application
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SealApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@SealApp)
                FFmpeg.getInstance().init(this@SealApp)
                Aria2c.getInstance().init(this@SealApp)
                Log.d("SealApp", "YoutubeDL, FFmpeg, and Aria2c initialized successfully")
            } catch (e: Exception) {
                Log.e("SealApp", "Failed to initialize YoutubeDL engine", e)
            }
        }
    }
}
