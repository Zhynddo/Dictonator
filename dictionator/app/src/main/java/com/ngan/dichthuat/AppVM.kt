package com.ngan.dichthuat

import android.app.Application
import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Item(val src: String, val dst: String, val input: String, val output: String)
data class Msg(val side: Int, val text: String, val out: String = "", val to: String = "", val pending: Boolean = true)

class AppVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
    val engine = Engine()
    private val dict = Dictionary(app)

    // first-run setup
    var setupDone by mutableStateOf(prefs.getBoolean("setupDone", false))
        private set

    fun startSetup(restart: Boolean = false) = SetupWorker.start(ctx, restart)

    fun finishSetup() {
        setupDone = true
        prefs.edit().putBoolean("setupDone", true).apply()
        refreshModels()
        viewModelScope.launch { dictCount = withContext(Dispatchers.IO) { dict.count() } }
    }

    // navigation
    var tab by mutableStateOf(0)
    var showModels by mutableStateOf(false)

    // translate tab
    var src by mutableStateOf(prefs.getString("src", AUTO) ?: AUTO)
        private set
    var dst by mutableStateOf(prefs.getString("dst", "en") ?: "en")
        private set
    var input by mutableStateOf("")
    var output by mutableStateOf("")
    var detected by mutableStateOf<String?>(null)
    var current by mutableStateOf<Item?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var missing by mutableStateOf<List<String>>(emptyList())
    private var job: Job? = null

    // language packs
    var downloaded by mutableStateOf(setOf("en"))
    val downloading = mutableStateMapOf<String, Boolean>()

    // saved
    val recents = mutableStateListOf<Item>().apply { addAll(loadItems("recents")) }
    val favorites = mutableStateListOf<Item>().apply { addAll(loadItems("favorites")) }

    // conversation
    var convA by mutableStateOf(prefs.getString("convA", "vi") ?: "vi")
        private set
    var convB by mutableStateOf(prefs.getString("convB", "en") ?: "en")
        private set
    val convo = mutableStateListOf<Msg>()

    // dictionary
    var dictQuery by mutableStateOf("")
    var dictEntries by mutableStateOf<List<Entry>>(emptyList())
    var dictSuggest by mutableStateOf<List<String>>(emptyList())
    var dictCount by mutableStateOf(0L)
    var importing by mutableStateOf<Int?>(null)
    var quick by mutableStateOf<String?>(null)
    private var dictJob: Job? = null

    // text to speech
    private var ttsReady = false
    private val tts = TextToSpeech(app) { status -> ttsReady = status == TextToSpeech.SUCCESS }

    init {
        refreshModels()
        viewModelScope.launch { dictCount = withContext(Dispatchers.IO) { dict.count() } }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // ---------- translate ----------
    fun changeSrc(code: String) {
        src = code; prefs.edit().putString("src", code).apply()
    }

    fun changeDst(code: String) {
        dst = code; prefs.edit().putString("dst", code).apply()
        if (input.isNotBlank()) translate()
    }

    fun swap() {
        val from = if (src == AUTO) detected ?: run { toast("Pick the input language first"); return } else src
        val to = dst
        src = to; dst = if (from == to) (if (to == "en") "vi" else "en") else from
        prefs.edit().putString("src", src).putString("dst", dst).apply()
        if (output.isNotBlank()) input = output
        output = ""; detected = null; current = null
        if (input.isNotBlank()) translate()
    }

    fun clear() {
        input = ""; output = ""; error = null; missing = emptyList(); detected = null; current = null
    }

    fun translate() {
        job?.cancel()
        job = viewModelScope.launch { runTranslate() }
    }

    private suspend fun runTranslate() {
        val text = input.trim()
        if (text.isEmpty()) { output = ""; return }
        busy = true; error = null; missing = emptyList()
        try {
            refreshModelsNow()
            val from = if (src == AUTO) {
                engine.detect(text) ?: run {
                    error = "Couldn't tell which language this is. Pick it at the top left."
                    return
                }
            } else src
            detected = if (src == AUTO) from else null
            val need = listOf(from, dst).distinct().filter { it !in downloaded }
            if (need.isNotEmpty()) {
                missing = need
                error = "To translate offline, download ${need.joinToString { Langs.name(it) }} once (needs internet)."
                return
            }
            val out = engine.translate(text, from, dst)
            output = out
            val item = Item(from, dst, text, out)
            current = item
            addRecent(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Translation failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            busy = false
        }
    }

    fun downloadMissing() {
        val need = missing
        viewModelScope.launch {
            for (code in need) downloadNow(code)
            refreshModelsNow()
            if (need.all { it in downloaded }) translate()
        }
    }

    fun translateImage(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            busy = true; error = null; output = ""
            val text = try {
                engine.readText(ctx, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't read the photo: ${e.message}"; busy = false; return@launch
            }
            busy = false
            if (text.isBlank()) { error = "No text found in the photo."; return@launch }
            input = text
            runTranslate()
        }
    }

    // ---------- saved ----------
    private fun addRecent(item: Item) {
        recents.removeAll { it.input == item.input && it.dst == item.dst }
        recents.add(0, item)
        while (recents.size > 50) recents.removeAt(recents.lastIndex)
        saveItems("recents", recents)
    }

    fun isFavorite(item: Item?) = item != null && favorites.any { it.input == item.input && it.dst == item.dst }

    fun toggleFavorite(item: Item?) {
        item ?: return
        if (isFavorite(item)) {
            favorites.removeAll { it.input == item.input && it.dst == item.dst }
            toast("Removed from Saved")
        } else {
            favorites.add(0, item); toast("Saved")
        }
        saveItems("favorites", favorites)
    }

    fun clearRecents() { recents.clear(); saveItems("recents", recents) }

    fun open(item: Item) {
        src = item.src; dst = item.dst
        prefs.edit().putString("src", src).putString("dst", dst).apply()
        input = item.input; output = item.output; current = item
        error = null; missing = emptyList(); detected = null
        tab = 0
    }

    private fun loadItems(key: String): List<Item> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.getString("s"), o.getString("d"), o.getString("i"), o.getString("o"))
        }
    } catch (e: Exception) { emptyList() }

    private fun saveItems(key: String, list: List<Item>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("s", it.src).put("d", it.dst).put("i", it.input).put("o", it.output)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    // ---------- language packs ----------
    fun refreshModels() { viewModelScope.launch { refreshModelsNow() } }

    private suspend fun refreshModelsNow() {
        try { downloaded = engine.downloadedLanguages() } catch (_: Exception) {}
    }

    fun download(code: String) { viewModelScope.launch { downloadNow(code); refreshModelsNow() } }

    private suspend fun downloadNow(code: String) {
        if (downloading[code] == true) return
        downloading[code] = true
        try {
            engine.download(code)
            toast("${Langs.name(code)} is ready offline")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Download failed — check your internet connection")
        } finally {
            downloading.remove(code)
        }
    }

    fun deleteModel(code: String) {
        viewModelScope.launch {
            try { engine.delete(code) } catch (_: Exception) {}
            refreshModelsNow()
        }
    }

    // ---------- conversation ----------
    fun changeConv(a: String? = null, b: String? = null) {
        a?.let { convA = it }; b?.let { convB = it }
        prefs.edit().putString("convA", convA).putString("convB", convB).apply()
    }

    fun sendConvo(side: Int, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val from = if (side == 0) convA else convB
        val to = if (side == 0) convB else convA
        convo.add(Msg(side, t, to = to))
        val index = convo.lastIndex
        viewModelScope.launch {
            refreshModelsNow()
            val need = listOf(from, to).filter { it !in downloaded }
            val out = if (need.isNotEmpty()) {
                "Download ${need.joinToString { Langs.name(it) }} first (⬇ at the top)."
            } else try {
                engine.translate(t, from, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't translate."
            }
            if (index < convo.size) convo[index] = convo[index].copy(out = out, pending = false)
        }
    }

    // ---------- dictionary ----------
    fun lookup(q: String) {
        dictQuery = q
        dictJob?.cancel()
        dictJob = viewModelScope.launch {
            delay(200)
            val word = q.trim()
            if (word.isEmpty()) {
                dictEntries = emptyList(); dictSuggest = emptyList(); quick = null; return@launch
            }
            val (entries, sugg) = withContext(Dispatchers.IO) { dict.lookup(word) to dict.suggest(word) }
            dictEntries = entries
            dictSuggest = sugg
            quick = quickTranslate(word)
        }
    }

    private suspend fun quickTranslate(word: String): String? {
        val from = if (Engine.VIET.containsMatchIn(word)) "vi" else "en"
        val to = if (from == "vi") "en" else "vi"
        if (from !in downloaded || to !in downloaded) return null
        return try { engine.translate(word, from, to) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    fun importDictionary(uri: Uri) {
        viewModelScope.launch {
            importing = 0
            try {
                val n = withContext(Dispatchers.IO) {
                    val stream = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open file")
                    stream.use { s -> dict.import(s) { p -> importing = p } }
                }
                dictCount = withContext(Dispatchers.IO) { dict.count() }
                toast(if (n > 0) "Imported $n words" else "No words found — unsupported file format")
                if (dictQuery.isNotBlank()) lookup(dictQuery)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Import failed: ${e.message}")
            } finally {
                importing = null
            }
        }
    }

    // ---------- speech ----------
    fun speak(text: String, code: String) {
        if (text.isBlank()) return
        if (!ttsReady) { toast("Text-to-speech isn't ready yet"); return }
        val r = tts.setLanguage(Locale.forLanguageTag(Langs.speech(code)))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("No ${Langs.name(code)} voice. Add it in Settings → Text-to-speech.")
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say")
    }

    override fun onCleared() {
        tts.shutdown()
        engine.close()
        dict.close()
    }
}
