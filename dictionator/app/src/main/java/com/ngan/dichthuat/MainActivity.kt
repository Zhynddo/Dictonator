@file:OptIn(ExperimentalMaterial3Api::class)

package com.ngan.dichthuat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AppTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val text = i?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: i?.getCharSequenceExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            vm.input = text.toString()
            vm.showModels = false
            vm.tab = 0
            vm.translate()
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------- helpers ----------
fun speechIntent(code: String?): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    if (code != null && code != AUTO) {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Langs.speech(code))
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak ${Langs.name(code)}")
    }
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
}

fun tryLaunch(ctx: Context, what: String, block: () -> Unit) {
    try { block() } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No $what app found on this phone", Toast.LENGTH_LONG).show()
    }
}

fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("translation", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

fun newPhotoUri(ctx: Context): Uri {
    val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
}

@Composable
fun SpeechLauncher(onText: (String) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) onText(t)
    }

@Composable
fun LangPicker(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, allowAuto: Boolean = false, downloaded: Set<String> = emptySet()) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(Langs.name(value), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowAuto) DropdownMenuItem(text = { Text("Detect language") }, onClick = { onChange(AUTO); open = false })
            Langs.all.forEach { l ->
                DropdownMenuItem(
                    text = { Text(l.name) },
                    trailingIcon = { if (l.code in downloaded) Icon(Icons.Outlined.DownloadDone, "Offline", Modifier.size(18.dp)) },
                    onClick = { onChange(l.code); open = false }
                )
            }
        }
    }
}

// ---------- app shell ----------
@Composable
fun App(vm: AppVM) {
    if (!vm.setupDone) {
        SetupScreen(vm)
    } else if (vm.showModels) {
        BackHandler { vm.showModels = false }
        ModelsScreen(vm)
    } else {
        val titles = listOf("Translate", "Conversation", "Dictionary", "Saved")
        val icons = listOf(Icons.Outlined.Translate, Icons.Outlined.Forum, Icons.AutoMirrored.Outlined.MenuBook, Icons.Outlined.StarOutline)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(titles[vm.tab], fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { vm.refreshModels(); vm.showModels = true }) {
                            Icon(Icons.Outlined.DownloadForOffline, "Offline languages")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    titles.forEachIndexed { i, t ->
                        NavigationBarItem(selected = vm.tab == i, onClick = { vm.tab = i }, icon = { Icon(icons[i], null) }, label = { Text(t) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (vm.tab) {
                    0 -> TranslateScreen(vm)
                    1 -> ConvoScreen(vm)
                    2 -> DictScreen(vm)
                    else -> SavedScreen(vm)
                }
            }
        }
    }
}

// ---------- translate ----------
@Composable
fun TranslateScreen(vm: AppVM) {
    val ctx = LocalContext.current
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = photoUri
        if (ok && u != null) vm.translateImage(Uri.parse(u))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.translateImage(uri)
    }
    val speech = SpeechLauncher { vm.input = it; vm.translate() }
    val big = MaterialTheme.typography.headlineSmall

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangPicker(vm.src, vm::changeSrc, Modifier.weight(1f), allowAuto = true, downloaded = vm.downloaded)
            IconButton(onClick = vm::swap) { Icon(Icons.Outlined.SwapHoriz, "Swap languages") }
            LangPicker(vm.dst, vm::changeDst, Modifier.weight(1f), downloaded = vm.downloaded)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
                Text(
                    if (vm.src == AUTO && vm.detected != null) "${Langs.name(vm.detected!!)} (detected)" else Langs.name(vm.src),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
                )
                BasicTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    textStyle = big.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(top = 6.dp, end = 8.dp),
                    decorationBox = { inner ->
                        Box {
                            if (vm.input.isEmpty()) Text("Enter text", style = big, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { tryLaunch(ctx, "voice input") { speech.launch(speechIntent(vm.src)) } }) {
                        Icon(Icons.Outlined.Mic, "Speak")
                    }
                    IconButton(onClick = {
                        val u = newPhotoUri(ctx); photoUri = u.toString()
                        tryLaunch(ctx, "camera") { camera.launch(u) }
                    }) { Icon(Icons.Outlined.PhotoCamera, "Translate a photo") }
                    IconButton(onClick = { tryLaunch(ctx, "gallery") { gallery.launch("image/*") } }) {
                        Icon(Icons.Outlined.Image, "Pick a photo")
                    }
                    IconButton(onClick = { vm.speak(vm.input, vm.detected ?: vm.src) }, enabled = vm.input.isNotBlank() && (vm.src != AUTO || vm.detected != null)) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Listen")
                    }
                    Spacer(Modifier.weight(1f))
                    if (vm.input.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Outlined.Close, "Clear") }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
                Text(Langs.name(vm.dst), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(6.dp))
                when {
                    vm.busy -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 0.dp))
                    vm.error != null -> {
                        Text(vm.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
                        if (vm.missing.isNotEmpty()) {
                            val dl = vm.missing.any { vm.downloading[it] == true }
                            Button(onClick = vm::downloadMissing, enabled = !dl, modifier = Modifier.padding(top = 8.dp)) {
                                Text(if (dl) "Downloading…" else "Download now")
                            }
                        }
                    }
                    vm.output.isNotEmpty() -> SelectionContainer {
                        Text(vm.output, style = big, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(end = 8.dp))
                    }
                    else -> Text("Translation", style = big, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.45f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val has = vm.output.isNotEmpty() && !vm.busy && vm.error == null
                    IconButton(onClick = { vm.toggleFavorite(vm.current) }, enabled = has && vm.current != null) {
                        Icon(if (vm.isFavorite(vm.current)) Icons.Filled.Star else Icons.Outlined.StarOutline, "Save")
                    }
                    IconButton(onClick = { copyText(ctx, vm.output) }, enabled = has) { Icon(Icons.Outlined.ContentCopy, "Copy") }
                    IconButton(onClick = { vm.speak(vm.output, vm.dst) }, enabled = has) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Listen") }
                }
            }
        }

        Button(onClick = vm::translate, enabled = !vm.busy && vm.input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Translate", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------- conversation ----------
@Composable
fun ConvoScreen(vm: AppVM) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var micSide by rememberSaveable { mutableIntStateOf(0) }
    val speech = SpeechLauncher { vm.sendConvo(micSide, it) }
    val list = rememberLazyListState()
    LaunchedEffect(vm.convo.size) { if (vm.convo.isNotEmpty()) list.animateScrollToItem(vm.convo.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            LangPicker(vm.convA, { vm.changeConv(a = it) }, Modifier.weight(1f), downloaded = vm.downloaded)
            Icon(Icons.Outlined.SwapHoriz, null, Modifier.padding(horizontal = 8.dp))
            LangPicker(vm.convB, { vm.changeConv(b = it) }, Modifier.weight(1f), downloaded = vm.downloaded)
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.convo.isEmpty()) item {
                Text(
                    "Tap a microphone and speak — each person in their own language. The other person sees (and can hear) the translation.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)
                )
            }
            itemsIndexed(vm.convo) { _, m ->
                val mine = m.side == 1
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp).clickable(enabled = !m.pending) { vm.speak(m.out, m.to) }
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            if (m.pending) Text("…", style = MaterialTheme.typography.titleLarge)
                            else Text(m.out, style = MaterialTheme.typography.bodyLarge)
                            Text(m.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                                color = (if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.75f))
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Type a message") },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), maxLines = 3)
            IconButton(onClick = { vm.sendConvo(0, text); text = "" }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send as ${Langs.name(vm.convA)}", tint = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = { vm.sendConvo(1, text); text = "" }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send as ${Langs.name(vm.convB)}", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { micSide = 0; tryLaunch(ctx, "voice input") { speech.launch(speechIntent(vm.convA)) } }, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(6.dp)); Text(Langs.name(vm.convA), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = { micSide = 1; tryLaunch(ctx, "voice input") { speech.launch(speechIntent(vm.convB)) } }, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(6.dp)); Text(Langs.name(vm.convB), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------- dictionary ----------
@Composable
fun DictScreen(vm: AppVM) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDictionary(uri)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = vm.dictQuery, onValueChange = vm::lookup, singleLine = true,
            placeholder = { Text("English or Vietnamese word") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (vm.dictQuery.isNotEmpty()) IconButton(onClick = { vm.lookup("") }) { Icon(Icons.Outlined.Close, "Clear") } },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        vm.importing?.let { n ->
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Importing… $n words", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        }
        val sugg = vm.dictSuggest.filter { s -> vm.dictEntries.none { it.word.equals(s, ignoreCase = true) } }
        if (sugg.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sugg) { s -> AssistChip(onClick = { vm.lookup(s) }, label = { Text(s) }) }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (vm.dictCount == 0L && vm.importing == null) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("No dictionary loaded yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Import a free English–Vietnamese dictionary file (.zip, .dict.dz or .txt) once — it's saved on the phone and works offline. " +
                                "Until then you'll still see a quick offline translation of each word.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Button(onClick = { importer.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Import dictionary file")
                        }
                    }
                }
            }
            items(vm.dictEntries) { e -> EntryCard(e, vm) }
            vm.quick?.let { q ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Quick translation", style = MaterialTheme.typography.labelLarge)
                            Text(q, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            if (vm.dictQuery.isNotBlank() && vm.dictEntries.isEmpty() && vm.dictCount > 0 && vm.importing == null) item {
                Text("“${vm.dictQuery.trim()}” isn't in the dictionary.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.dictCount > 0) item {
                TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = vm.importing == null) {
                    Text("Replace dictionary (${vm.dictCount} words)")
                }
            }
        }
    }
}

@Composable
fun EntryCard(e: Entry, vm: AppVM) {
    val lang = if (Engine.VIET.containsMatchIn(e.word)) "vi" else "en"
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.speak(e.word, lang) }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Listen") }
                }
                e.body.lines().forEach { raw ->
                    val t = raw.trim()
                    when {
                        t.isEmpty() -> {}
                        t.startsWith("/") -> Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        t.startsWith("*") -> Text(t.removePrefix("*").trim(), fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                        t.startsWith("-") -> Text("• " + t.removePrefix("-").trim(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                        t.startsWith("=") -> {
                            val p = t.removePrefix("=").split("+", limit = 2)
                            Text(p[0].trim(), fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
                            if (p.size > 1) Text(p[1].trim(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp))
                        }
                        t.startsWith("!") -> Text(t.removePrefix("!").trim(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        else -> Text(t, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

// ---------- saved ----------
@Composable
fun SavedScreen(vm: AppVM) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Saved", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        if (vm.favorites.isEmpty()) item {
            Text("Tap ☆ on a translation to keep it here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(vm.favorites.toList()) { ItemRow(it, vm, starred = true) }
        if (vm.recents.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearRecents) { Text("Clear") }
                }
            }
            items(vm.recents.toList()) { ItemRow(it, vm, starred = vm.isFavorite(it)) }
        }
    }
}

@Composable
fun ItemRow(item: Item, vm: AppVM, starred: Boolean) {
    Card(Modifier.fillMaxWidth().clickable { vm.open(item) }) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.input, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(item.output, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Text("${Langs.name(item.src)} → ${Langs.name(item.dst)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.toggleFavorite(item) }) {
                Icon(if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline, "Save")
            }
        }
    }
}

// ---------- one-tap setup ----------
@Composable
fun SetupScreen(vm: AppVM) {
    val ctx = LocalContext.current
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(SetupWorker.NAME) }
        .collectAsState(initial = emptyList())
    val info = infos.firstOrNull()
    val state = info?.state
    LaunchedEffect(state) { if (state == WorkInfo.State.SUCCEEDED) vm.finishSetup() }

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startSetup() }
    val start = {
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startSetup()
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.Translate, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Dịch Thuật", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            val running = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
            when {
                running -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                    val step = info?.progress?.getString(SetupWorker.STEP)
                    Text(
                        when {
                            step != null -> step
                            state == WorkInfo.State.ENQUEUED -> "Đang chờ kết nối mạng…\nWaiting for internet"
                            else -> "Đang chuẩn bị…\nGetting ready"
                        },
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Bạn có thể đóng ứng dụng — sẽ có thông báo khi xong.\nYou can close the app; you'll get a notification when it's done.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED -> {
                    Text(
                        "Chưa cài xong. Hãy bật Wi-Fi rồi thử lại.\nSetup didn't finish. Turn on Wi-Fi and try again.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { vm.startSetup(restart = true) }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Text("Thử lại · Try again", style = MaterialTheme.typography.titleMedium)
                    }
                }
                else -> {
                    Text(
                        "Nhấn một lần để tải mọi thứ cần thiết. Sau đó ứng dụng dùng được khi không có mạng.\n\n" +
                            "Tap once to download everything. After that the app works without internet.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(onClick = start, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        Text("Bắt đầu · Start", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Cần Wi-Fi, khoảng 40 MB · Needs Wi-Fi, about 40 MB",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------- offline languages ----------
@Composable
fun ModelsScreen(vm: AppVM) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Offline languages") },
            navigationIcon = { IconButton(onClick = { vm.showModels = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Text(
                    "Download each language once while you have internet (about 30 MB each, Wi-Fi recommended). " +
                        "After that, translation works with no connection. Start with Tiếng Việt.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            items(Langs.all) { l ->
                val isDown = l.code in vm.downloaded
                val busy = vm.downloading[l.code] == true
                ListItem(
                    headlineContent = { Text(l.name) },
                    supportingContent = {
                        Text(when {
                            l.code == "en" -> "Built in"
                            busy -> "Downloading…"
                            isDown -> "Available offline"
                            else -> "Not downloaded"
                        })
                    },
                    trailingContent = {
                        when {
                            l.code == "en" -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            isDown -> IconButton(onClick = { vm.deleteModel(l.code) }) { Icon(Icons.Outlined.Delete, "Remove") }
                            else -> IconButton(onClick = { vm.download(l.code) }) { Icon(Icons.Outlined.Download, "Download") }
                        }
                    }
                )
            }
        }
    }
}
