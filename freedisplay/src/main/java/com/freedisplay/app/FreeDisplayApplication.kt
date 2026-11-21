package com.freedisplay.app

import android.app.Application
import com.n1tools.display.DisplayCore

class FreeDisplayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DisplayCore.init(this)
    }
}
