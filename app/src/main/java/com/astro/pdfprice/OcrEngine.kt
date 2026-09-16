package com.astro.pdfprice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OcrEngine(private val context: Context) {
    private val baseDir = File(context.filesDir, "tessdata")
    private val trained = mutableSetOf<String>()

    fun extractPage(pdf: ParcelFileDescriptor, pageIndex: Int, language: String = "fas+ara+eng"): String {
        ensureModels(language)
        val renderer = PdfRenderer(pdf)
        val page = renderer.openPage(pageIndex)
        val scale = 2f
        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close(); renderer.close()
        val api = TessBaseAPI()
        val ok = api.init(context.filesDir.absolutePath, language)
        if (!ok) { api.end(); return "" }
        api.setImage(bmp)
        val text = api.getUTF8Text() ?: ""
        api.end(); bmp.recycle()
        return text
    }

    private fun ensureModels(language: String) {
        baseDir.mkdirs()
        val langs = language.split('+')
        for (lang in langs) {
            if (trained.contains(lang)) continue
            val f = File(baseDir, "$lang.traineddata")
            if (!f.exists()) download(lang, f)
            trained += lang
        }
    }

    private fun download(lang: String, dest: File) {
        val url = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$lang.traineddata")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.inputStream.use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
        c.disconnect()
    }
}
