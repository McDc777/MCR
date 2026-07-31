package com.mcr.pdfstudio

import android.app.Application
import com.mcr.pdfstudio.fonts.BundledFonts
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // PdfBox-Android loads its font metrics and CMap resources through this
        // loader; without it any font work throws at runtime.
        PDFBoxResourceLoader.init(applicationContext)

        // Unpacking the bundled fonts touches disk, so it happens off the main
        // thread. Until it finishes the app simply uses the device's fonts.
        Thread({ BundledFonts.install(applicationContext) }, "font-install").start()
    }
}
