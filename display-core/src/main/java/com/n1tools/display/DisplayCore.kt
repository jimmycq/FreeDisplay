package com.n1tools.display

import android.content.Context

object DisplayCore {
    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun getAppContext(): Context {
        return appContext ?: throw IllegalStateException("DisplayCore must be initialized with DisplayCore.init(context)")
    }
}
