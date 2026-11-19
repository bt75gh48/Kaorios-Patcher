package com.kaorios.patcher

import android.app.Application
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.editor.SmaliTextMate

class KaoriosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        LogSink.init(this)
        LogSink.i("App", "KaoriosPatcher starting…")
        try {
            SmaliTextMate.init(this)
        } catch (t: Throwable) {
            LogSink.e("App", "Failed to init SmaliTextMate", t)
        }
    }

    companion object {
        lateinit var instance: KaoriosApp
            private set
    }
}
