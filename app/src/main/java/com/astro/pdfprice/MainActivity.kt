package com.astro.pdfprice

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

private data class PdfItem(val uri: Uri, val name: String)
private data class PriceResult(
    val file: String,
    val page: Int,
    val code: String,
    val name: String,
    val description: String,
    val price: String,
    val score: Int,
    val context: String
)

class MainActivity : AppCompatActivity() {
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
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
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
            setPadding(20, 18, 20, 12)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            text = "جستجوی هوشمند قیمت ابزار"
            textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.RIGHT
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val sub = TextView(this).apply {
            text = "جستجوی هوشمند با تطبیق نام، شرح، کد، مدل و مشخصات"
            textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.RIGHT
            setPadding(0, 5, 0, 12)
        }
        root.addView(sub)

        search = EditText(this).apply {
            hint = "مثلاً: دریل 13  •  GWS  •  کد کالا"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 16f
            setSingleLine(true); setPadding(18, 0, 18, 0)
            setBackgroundColor(card); layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(search, LinearLayout.LayoutParams(-1, 58))
        search.setOnEditorActionListener { _, _, _ -> doSearch(); true }

        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
        val add = Button(this).apply { text = "＋ افزودن PDF"; setOnClickListener { picker.launch(arrayOf("application/pdf")) } }
        val manage = Button(this).apply { text = "مدیریت فایل‌ها"; setOnClickListener { showFileManager() } }
        buttons.addView(add, LinearLayout.LayoutParams(0, 52, 1f))
        buttons.addView(manage, LinearLayout.LayoutParams(0, 52, 1f).apply { marginStart = 8 })
        root.addView(buttons)

        filesLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.RIGHT }
        root.addView(filesLabel, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply { textSize = 12f; setTextColor(accent); gravity = Gravity.RIGHT; setPadding(0, 6, 0, 8) }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        results = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); setBackgroundColor(bg) }
        resultAdapter = ResultAdapter()
        results.adapter = resultAdapter
        root.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateFilesLabel()
    }

    private fun doSearch() {
        val q = normalize(search.text.toString())
        if (q.isBlank()) { resultAdapter.submit(index.sortedByDescending { it.score }.take(100)); return }
        val ranked = index.map { it to SearchEngine.score(q, it) }
            .filter { it.second >= 18 }
            .sortedByDescending { it.second }
            .take(100)
            .map { (r, s) -> r.copy(score = s) }
        resultAdapter.submit(ranked)
        status.text = "${ranked.size} نتیجه پیدا شد"
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
                            if (text.length < 30) {
                                text += "\n" + runOcr(temp, p - 1)
                            }
                            all += Parser.toResults(item.name, p, text)
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
                updateFilesLabel()
                status.text = "آماده — ${index.size} بخش کالا از ${pdfs.size} فایل"
                doSearch()
            }
        }
    }

    private fun runOcr(file: File, page: Int): String = try {
        val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
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
        contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun loadFiles() {
        pdfs.clear()
        val arr = JSONArray(getPreferences(Context.MODE_PRIVATE).getString("pdfs", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); pdfs += PdfItem(Uri.parse(o.getString("uri")), o.getString("name"))
        }
    }
    private fun saveFiles() {
        val arr = JSONArray(); pdfs.forEach { arr.put(JSONObject().apply { put("uri", it.uri.toString()); put("name", it.name) }) }
        getPreferences(Context.MODE_PRIVATE).edit().putString("pdfs", arr.toString()).apply()
    }
    private fun updateFilesLabel() { if (::filesLabel.isInitialized) filesLabel.text = "فایل‌های فعال: ${pdfs.size}   •   برای حذف یا افزودن «مدیریت فایل‌ها» را بزنید" }

    private fun showFileManager() {
        val names = pdfs.map { "✓  ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("مدیریت فایل‌های PDF")
            .setMultiChoiceItems(names, null) { _, _, _ -> }
            .setPositiveButton("حذف انتخاب‌شده") { dialog, _ ->
                val lv = (dialog as AlertDialog).listView
                val remove = (0 until lv.count).filter { lv.isItemChecked(it) }.map { pdfs[it] }.toSet()
                pdfs.removeAll(remove); saveFiles(); rebuildIndex()
            }
            .setNeutralButton("افزودن PDF") { _, _ -> picker.launch(arrayOf("application/pdf")) }
            .setNegativeButton("بستن", null).show()
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultVH>() {
        private var data = listOf<PriceResult>()
        fun submit(v: List<PriceResult>) { data = v; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultVH = ResultVH(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 16, 18, 16); setBackgroundColor(Color.rgb(29,34,43)); layoutDirection = View.LAYOUT_DIRECTION_RTL
        })
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: ResultVH, position: Int) = holder.bind(data[position])
    }
    private class ResultVH(v: View) : RecyclerView.ViewHolder(v) {
        private val box = v as LinearLayout
        fun bind(r: PriceResult) {
            box.removeAllViews()
            add("${r.name.ifBlank { "کالای پیدا شده" }}${if (r.code.isNotBlank()) "  |  ${r.code}" else ""}", 17f, Color.WHITE, true)
            if (r.price.isNotBlank()) add("قیمت: ${r.price}", 16f, Color.rgb(255,190,70), true)
            if (r.description.isNotBlank()) add(r.description.take(260), 13f, Color.LTGRAY, false)
            add("${r.file}  •  صفحه ${r.page}", 12f, Color.GRAY, false)
        }
        private fun add(t:String, size:Float, color:Int, bold:Boolean) { box.addView(TextView(box.context).apply { text=t; textSize=size; setTextColor(color); typeface=if(bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; gravity=Gravity.RIGHT; setPadding(0,3,0,3) }) }
    }
}

private object Parser {
    private val priceRe = Regex("(?<!\\d)([۰-۹٠-٩\\d]{1,3}(?:[,.،٬ ]?[۰-۹٠-٩\\d]{3})+(?:[,.،]\\d{1,2})?|[۰-۹٠-٩\\d]{4,})(?:\\s*(?:تومان|ریال|ريال|تومن))?", RegexOption.IGNORE_CASE)
    private val codeRe = Regex("\\b[A-Za-z]{0,6}[-_/]?[A-Za-z0-9۰-۹٠-٩]{2,}[A-Za-z0-9۰-۹٠-٩_-]*\\b")

    fun toResults(file:String, page:Int, raw:String): List<PriceResult> {
        val text = normalize(raw).replace("\u0000", " ")
        if (text.isBlank()) return emptyList()
        val lines = text.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length >= 3 }
        val results = mutableListOf<PriceResult>()
        for (line in lines.take(250)) {
            val prices = priceRe.findAll(line).map { it.value.trim() }.distinct().toList()
            val codes = codeRe.findAll(line).map { it.value }.distinct().filter { it.any(Char::isDigit) }.toList()
            val description = line.take(600)
            val name = line.replace(priceRe, " ").replace(Regex("\\s+"), " ").trim().take(220)
            if (prices.isNotEmpty() || codes.isNotEmpty() || name.length >= 8) {
                results += PriceResult(
                    file = file,
                    page = page,
                    code = codes.firstOrNull() ?: "",
                    name = name,
                    description = description,
                    price = prices.firstOrNull() ?: "",
                    score = 1,
                    context = description
                )
            }
        }
        if (results.isEmpty()) {
            results += PriceResult(file, page, "", "", text.take(300), "", 1, text.take(700))
        }
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
        val qt = q.split(" ").filter { it.length > 1 }
        for (token in qt) {
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
    private fun lev(a:String,b:String):Int { val dp=IntArray(b.length+1){it}; for(i in 1..a.length){ var prev=dp[0]; dp[0]=i; for(j in 1..b.length){ val cur=dp[j]; dp[j]=minOf(dp[j]+1,dp[j-1]+1,prev+if(a[i-1]==b[j-1])0 else 1); prev=cur } }; return dp[b.length] }
}

private fun normalize(s:String):String = s.lowercase(Locale.ROOT)
    .replace('ي','ی').replace('ى','ی').replace('ك','ک').replace('ة','ه')
    .replace(Regex("[َُِّْـ]"), "")
    .map { when(it){ '۰'->'0';'۱'->'1';'۲'->'2';'۳'->'3';'۴'->'4';'۵'->'5';'۶'->'6';'۷'->'7';'۸'->'8';'۹'->'9';'٠'->'0';'١'->'1';'٢'->'2';'٣'->'3';'٤'->'4';'٥'->'5';'٦'->'6';'٧'->'7';'٨'->'8';'٩'->'9'; else->it } }.joinToString("")
    .replace(Regex("[\\p{Punct}،؛:()\\[\\]{}]+"), " ")
    .replace(Regex("\\s+"), " ").trim()
