package com.astro.pdfprice

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

private data class PdfItem(val uri: Uri, val name: String)
private data class PriceResult(
    val file: String,
    val uri: String,
    val page: Int,
    val code: String,
    val name: String,
    val description: String,
    val price: String,
    val score: Int,
    val context: String
)

class MainActivity : AppCompatActivity() {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var search: EditText
    private lateinit var results: RecyclerView
    private lateinit var resultAdapter: ResultAdapter
    private lateinit var filesLabel: TextView
    private lateinit var status: TextView
    private var pdfs = mutableListOf<PdfItem>()
    private var index = mutableListOf<PriceResult>()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        executor.execute {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                val name = displayName(uri) ?: "PDF ${System.currentTimeMillis()}"
                if (pdfs.none { it.uri.toString() == uri.toString() }) pdfs += PdfItem(uri, name)
            }
            saveFiles()
            rebuildIndex()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        loadFiles()
        buildUi()
        if (pdfs.isNotEmpty()) rebuildIndex()
    }

    private fun buildUi() {
        val bg = Color.rgb(15, 18, 24)
        val card = Color.rgb(29, 34, 43)
        val accent = Color.rgb(255, 160, 45)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(16), dp(16), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val title = TextView(this).apply {
            text = "جستجوی هوشمند قیمت ابزار"
            textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.RIGHT
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val sub = TextView(this).apply {
            text = "جستجوی هوشمند با تطبیق نام، شرح، کد، مدل و مشخصات"
            textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.RIGHT
            setPadding(0, dp(5), 0, dp(12))
        }
        root.addView(sub)
        search = EditText(this).apply {
            hint = "مثلاً: دریل 13  •  GWS  •  کد کالا"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 16f; setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0); setBackgroundColor(card); layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(56)))
        search.setOnEditorActionListener { _, _, _ -> doSearch(); true }

        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)); layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val add = makeButton("＋ افزودن PDF") { picker.launch(arrayOf("application/pdf")) }
        val manage = makeButton("مدیریت فایل‌ها") { showFileManager() }
        buttons.addView(manage, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        buttons.addView(add, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        root.addView(buttons)

        filesLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.RIGHT }
        root.addView(filesLabel, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply { textSize = 12f; setTextColor(accent); gravity = Gravity.RIGHT; setPadding(0, dp(6), 0, dp(8)) }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        results = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); setBackgroundColor(bg) }
        resultAdapter = ResultAdapter(); results.adapter = resultAdapter
        root.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateFilesLabel()
    }

    private fun makeButton(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue; textSize = 14f; isAllCaps = false; maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END; minHeight = 0; minimumHeight = 0
        setPadding(dp(6), 0, dp(6), 0); setOnClickListener { action() }
    }

    private fun doSearch() {
        val q = normalize(search.text.toString())
        if (q.isBlank()) { resultAdapter.submit(index.sortedByDescending { it.score }.take(100)); return }
        val ranked = index.map { it to SearchEngine.score(q, it) }
            .filter { it.second >= 18 }.sortedByDescending { it.second }.take(100)
            .map { (r, s) -> r.copy(score = s) }
        resultAdapter.submit(ranked); status.text = "${ranked.size} نتیجه پیدا شد"
    }

    private fun rebuildIndex() {
        main.post { status.text = "در حال استخراج متن و ساخت فهرست جستجو…" }
        executor.execute {
            val all = mutableListOf<PriceResult>()
            pdfs.toList().forEachIndexed { fileNo, item ->
                try {
                    val temp = copyToCache(item.uri, "pdf_${System.nanoTime()}.pdf")
                    PDDocument.load(temp).use { doc ->
                        val stripper = PDFTextStripper()
                        for (p in 1..doc.numberOfPages) {
                            stripper.startPage = p; stripper.endPage = p
                            var text = stripper.getText(doc).trim()
                            if (text.length < 30) text += "\n" + runOcr(temp, p - 1)
                            all += Parser.toResults(item.name, item.uri.toString(), p, text)
                            val done = p
                            main.post { status.text = "پردازش ${fileNo + 1}/${pdfs.size} — صفحه $done/${doc.numberOfPages}" }
                        }
                    }
                    temp.delete()
                } catch (e: Exception) {
                    main.post { status.text = "خطا در ${item.name}: ${e.message ?: "PDF نامعتبر"}" }
                }
            }
            index = all
            main.post {
                updateFilesLabel(); status.text = "آماده — ${index.size} بخش کالا از ${pdfs.size} فایل"; doSearch()
            }
        }
    }

    private fun runOcr(file: File, page: Int): String = try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        OcrEngine(this).extractPage(pfd, page).also { pfd.close() }
    } catch (_: Exception) { "" }

    private fun copyToCache(uri: Uri, name: String): File {
        val out = File(cacheDir, name)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "عدم دسترسی به PDF" }
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    private fun loadFiles() {
        pdfs.clear()
        val arr = JSONArray(getPreferences(Context.MODE_PRIVATE).getString("pdfs", "[]"))
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); pdfs += PdfItem(Uri.parse(o.getString("uri")), o.getString("name")) }
    }
    private fun saveFiles() {
        val arr = JSONArray(); pdfs.forEach { arr.put(JSONObject().apply { put("uri", it.uri.toString()); put("name", it.name) }) }
        getPreferences(Context.MODE_PRIVATE).edit().putString("pdfs", arr.toString()).apply()
    }
    private fun updateFilesLabel() { if (::filesLabel.isInitialized) filesLabel.text = "فایل‌های فعال: ${pdfs.size}   •   برای مشاهده جداگانه، «مدیریت فایل‌ها» را بزنید" }

    private fun showFileManager() {
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(list)
        if (pdfs.isEmpty()) {
            list.addView(TextView(this).apply { text = "هنوز فایل PDF اضافه نشده است."; textSize = 15f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(dp(10), dp(20), dp(10), dp(20)) })
        } else {
            pdfs.toList().forEach { item ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(10)); setBackgroundColor(Color.rgb(29,34,43)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
                row.addView(TextView(this).apply { text = item.name; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.RIGHT })
                val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                val open = Button(this).apply { text = "مشاهده PDF"; isAllCaps = false; setOnClickListener { startActivity(Intent(this@MainActivity, PdfViewerActivity::class.java).apply { putExtra("uri", item.uri.toString()); putExtra("page", 0); putExtra("title", item.name) }) } }
                val delete = Button(this).apply { text = "حذف"; isAllCaps = false; setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("حذف فایل").setMessage("${item.name} از فهرست برنامه حذف شود؟")
                        .setNegativeButton("لغو", null).setPositiveButton("حذف") { _, _ -> pdfs.removeAll { it.uri == item.uri }; saveFiles(); rebuildIndex(); showFileManager() }.show()
                } }
                actions.addView(open, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
                actions.addView(delete, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
                row.addView(actions)
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
            }
        }
        AlertDialog.Builder(this).setTitle("فایل‌های PDF من").setView(scroll)
            .setNeutralButton("افزودن PDF") { _, _ -> picker.launch(arrayOf("application/pdf")) }
            .setNegativeButton("بستن", null).show()
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultVH>() {
        private var data = listOf<PriceResult>()
        fun submit(v: List<PriceResult>) { data = v; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultVH {
            val box = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(Color.rgb(29,34,43)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
            return ResultVH(box)
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: ResultVH, position: Int) = holder.bind(data[position])
    }

    private inner class ResultVH(v: View) : RecyclerView.ViewHolder(v) {
        private val box = v as LinearLayout
        fun bind(r: PriceResult) {
            box.removeAllViews()
            val title = TextView(this@MainActivity).apply { text = "${r.name.ifBlank { "کالای پیدا شده" }}${if (r.code.isNotBlank()) "  |  ${r.code}" else ""}"; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.RIGHT; setPadding(0,0,0,dp(6)) }
            box.addView(title)
            val image = ImageView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(230)); scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.rgb(20,23,30)); tag = "${r.uri}#${r.page}" }
            box.addView(image)
            image.setOnClickListener { openViewer(r) }
            val openText = TextView(this@MainActivity).apply { text = "مشاهده صفحه اصلی PDF  •  صفحه ${r.page}"; textSize = 13f; setTextColor(Color.rgb(190,170,230)); gravity = Gravity.RIGHT; setPadding(0, dp(6), 0, dp(4)); setOnClickListener { openViewer(r) } }
            box.addView(openText)
            if (r.price.isNotBlank()) add("قیمت: ${r.price}", 16f, Color.rgb(255,190,70), true)
            if (r.description.isNotBlank()) add(r.description.take(260), 13f, Color.LTGRAY, false)
            add("${r.file}  •  صفحه ${r.page}", 12f, Color.GRAY, false)
            val key = image.tag
            executor.execute {
                val bmp = PdfPageRenderer.render(this@MainActivity, Uri.parse(r.uri), r.page - 1, dp(900))
                main.post { if (image.tag == key) image.setImageBitmap(bmp) }
            }
        }
        private fun openViewer(r: PriceResult) { startActivity(Intent(this@MainActivity, PdfViewerActivity::class.java).apply { putExtra("uri", r.uri); putExtra("page", r.page - 1); putExtra("title", r.file) }) }
        private fun add(t: String, size: Float, color: Int, bold: Boolean) { box.addView(TextView(this@MainActivity).apply { text=t; textSize=size; setTextColor(color); typeface=if(bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; gravity=Gravity.RIGHT; setPadding(0,dp(3),0,dp(3)) }) }
    }
}

object PdfPageRenderer {
    fun render(context: Context, uri: Uri, pageIndex: Int, maxWidth: Int): Bitmap? {
        return try {
            val file = ensureCached(context, uri)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            try {
                if (pageIndex !in 0 until renderer.pageCount) return null
                val page = renderer.openPage(pageIndex)
                try {
                    val width = maxWidth.coerceAtLeast(400)
                    val height = (width.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } finally { page.close() }
            } finally { renderer.close(); pfd.close() }
        } catch (_: Exception) { null }
    }

    fun ensureCached(context: Context, uri: Uri): File {
        val key = sha256(uri.toString()).take(24)
        val file = File(context.cacheDir, "source_$key.pdf")
        if (!file.exists() || file.length() == 0L) {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "عدم دسترسی به PDF" }
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

private object Parser {
    private val priceRe = Regex("(?<!\\d)([۰-۹٠-٩\\d]{1,3}(?:[,.،٬ ]?[۰-۹٠-٩\\d]{3})+(?:[,.،]\\d{1,2})?|[۰-۹٠-٩\\d]{4,})(?:\\s*(?:تومان|ریال|ريال|تومن))?", RegexOption.IGNORE_CASE)
    private val codeRe = Regex("\\b[A-Za-z]{0,6}[-_/]?[A-Za-z0-9۰-۹٠-٩]{2,}[A-Za-z0-9۰-۹٠-٩_-]*\\b")
    fun toResults(file:String, uri:String, page:Int, raw:String): List<PriceResult> {
        val text = normalize(raw).replace("\u0000", " ")
        if (text.isBlank()) return emptyList()
        val lines = text.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length >= 3 }
        val results = mutableListOf<PriceResult>()
        for (line in lines.take(250)) {
            val prices = priceRe.findAll(line).map { it.value.trim() }.distinct().toList()
            val codes = codeRe.findAll(line).map { it.value }.distinct().filter { it.any(Char::isDigit) }.toList()
            val description = line.take(600)
            val name = line.replace(priceRe, " ").replace(Regex("\\s+"), " ").trim().take(220)
            if (prices.isNotEmpty() || codes.isNotEmpty() || name.length >= 8) results += PriceResult(file, uri, page, codes.firstOrNull() ?: "", name, description, prices.firstOrNull() ?: "", 1, description)
        }
        if (results.isEmpty()) results += PriceResult(file, uri, page, "", "", text.take(300), "", 1, text.take(700))
        return results
    }
}

private object SearchEngine {
    fun score(q:String, r:PriceResult): Int {
        val fields = listOf(r.code, r.name, r.description, r.context)
        var score = 0
        if (normalize(r.code) == q) score += 130
        if (normalize(r.name).contains(q)) score += 105
        if (normalize(r.description).contains(q)) score += 75
        for (token in q.split(" ").filter { it.length > 1 }) {
            val best = fields.maxOfOrNull { field ->
                val nt = normalize(field)
                if (nt.contains(token)) 32.0 else (field.split(" ").maxOfOrNull { word -> Similarity.ratio(token, normalize(word)) * 24.0 } ?: 0.0)
            } ?: 0.0
            score += best.toInt()
        }
        if (q.any(Char::isDigit) && (r.code.contains(q) || r.context.contains(q))) score += 45
        return score
    }
}

private object Similarity {
    fun ratio(a:String,b:String):Double { if(a.isEmpty()||b.isEmpty()) return 0.0; if(a==b) return 1.0; val d=lev(a,b); return 1.0-d.toDouble()/max(a.length,b.length) }
    private fun lev(a:String,b:String):Int { val prev=IntArray(b.length+1){it}; val cur=IntArray(b.length+1); for(i in a.indices){ cur[0]=i+1; for(j in b.indices) cur[j+1]=minOf(cur[j]+1,prev[j+1]+1,prev[j]+if(a[i]==b[j])0 else 1); for(j in prev.indices) prev[j]=cur[j] }; return prev[b.length] }
}

private fun normalize(s:String):String = s.replace('ي','ی').replace('ى','ی').replace('ك','ک').replace('ۀ','ه').replace('ة','ه').replace(Regex("[۰-۹]")) { (it.value[0].code-0x6f0).toString() }.lowercase(Locale.ROOT)
