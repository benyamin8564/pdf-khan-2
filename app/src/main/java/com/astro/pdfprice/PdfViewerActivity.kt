package com.astro.pdfprice

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class PdfViewerActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var image: ZoomImageView
    private lateinit var pageLabel: TextView
    private lateinit var prev: Button
    private lateinit var next: Button
    private var uriString = ""
    private var title = "PDF"
    private var page = 0
    private var pageCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uriString = intent.getStringExtra("uri") ?: ""
        title = intent.getStringExtra("title") ?: "PDF"
        page = intent.getIntExtra("page", 0).coerceAtLeast(0)
        buildUi()
        loadPage()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(15,18,24))
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(12,8,12,8); layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        val close = Button(this).apply { text = "بستن"; isAllCaps = false; setOnClickListener { finish() } }
        val name = TextView(this).apply { text = title; textSize = 15f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.RIGHT; setPadding(10,0,10,0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        header.addView(close, LinearLayout.LayoutParams(0,48.dp,1f))
        header.addView(name, LinearLayout.LayoutParams(0,48.dp,3f))
        root.addView(header)

        image = ZoomImageView(this)
        root.addView(image, LinearLayout.LayoutParams(-1,0,1f))

        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(8,8,8,8); layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        next = Button(this).apply { text = "صفحه بعد ←"; isAllCaps = false; setOnClickListener { if (page < pageCount - 1) { page++; loadPage() } } }
        pageLabel = TextView(this).apply { text = ""; textSize = 14f; setTextColor(android.graphics.Color.LTGRAY); gravity = Gravity.CENTER }
        prev = Button(this).apply { text = "→ صفحه قبل"; isAllCaps = false; setOnClickListener { if (page > 0) { page--; loadPage() } } }
        nav.addView(next, LinearLayout.LayoutParams(0,48.dp,1f))
        nav.addView(pageLabel, LinearLayout.LayoutParams(0,48.dp,1f))
        nav.addView(prev, LinearLayout.LayoutParams(0,48.dp,1f))
        root.addView(nav)
        setContentView(root)
    }

    private fun loadPage() {
        pageLabel.text = "در حال بارگذاری…"
        prev.isEnabled = false; next.isEnabled = false
        executor.execute {
            var bmp: Bitmap? = null
            try {
                val file = PdfPageRenderer.ensureCached(this, android.net.Uri.parse(uriString))
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                pageCount = renderer.pageCount
                page = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                if (pageCount > 0) {
                    val p = renderer.openPage(page)
                    try {
                        val width = (resources.displayMetrics.widthPixels * 1.5f).roundToInt().coerceAtLeast(900)
                        val height = (width.toFloat() * p.height / p.width).roundToInt()
                        bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bmp?.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally { p.close() }
                }
                renderer.close(); pfd.close()
            } catch (_: Exception) { }
            runOnUiThread {
                image.resetZoom()
                image.setImageBitmap(bmp)
                pageLabel.text = if (pageCount > 0) "صفحه ${page + 1} از $pageCount" else "PDF قابل نمایش نیست"
                prev.isEnabled = page > 0
                next.isEnabled = page < pageCount - 1
            }
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()
}

private class ZoomImageView(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    private val matrixState = Matrix()
    private var scale = 1f
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            scale = (scale * d.scaleFactor).coerceIn(1f, 5f)
            matrixState.setScale(scale, scale, d.focusX, d.focusY)
            imageMatrix = matrixState
            return true
        }
    })
    init { scaleType = ScaleType.FIT_CENTER; setBackgroundColor(android.graphics.Color.WHITE) }
    override fun onTouchEvent(event: MotionEvent): Boolean { detector.onTouchEvent(event); return true }
    fun resetZoom() { scale = 1f; matrixState.reset(); scaleType = ScaleType.FIT_CENTER }
}
