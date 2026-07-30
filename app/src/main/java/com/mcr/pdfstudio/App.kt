package com.mcr.pdfstudio

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // PdfBox-Android loads its font metrics and CMap resources through this
        // loader; without it any font work throws at runtime.
        PDFBoxResourceLoader.init(applicationContext)
    }
}
