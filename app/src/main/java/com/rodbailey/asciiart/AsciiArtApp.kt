package com.rodbailey.asciiart

import android.app.Application
import com.rodbailey.asciiart.di.asciiPreviewPresentationModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AsciiArtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AsciiArtApp)
            modules(
                asciiPreviewPresentationModule
            )
        }
    }
}

