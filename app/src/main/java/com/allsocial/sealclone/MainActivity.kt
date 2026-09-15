package com.allsocial.sealclone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ================= DATA MODELS =================
data class SearchItem(
    val id: String,
    val title: String,
    val uploader: String,
    val duration: String,
    val thumbnail: String,
    val url: String
)

data class ActiveDownloadTask(
    val id: String,
    val title: String,
    val progress: Float,
    val speed: String = ""
)

data class DownloadedRecord(
    val title: String,
    val filePath: String,
    val thumbnail: String,
    val quality: String,
    val ext: String,
    val fileSize: String
)

enum class AppTab(val label: String, val activeIcon: ImageVector, val inactiveIcon: ImageVector) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    TASKS("Tasks", Icons.Filled.Downloading, Icons.Outlined.Downloading),
    LIBRARY("Library", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary)
}

// ================= VIEWMODEL =================
class SealViewModel : ViewModel() {
    private val _activeTasks = MutableStateFlow<Map<String, ActiveDownloadTask>>(emptyMap())
    val activeTasks = _activeTasks.asStateFlow()

    private val _downloadedHistory = MutableStateFlow<List<DownloadedRecord>>(emptyList())
    val downloadedHistory = _downloadedHistory.asStateFlow()

    fun updateProgress(id: String, title: String, progress: Float, speed: String) {
        val current = _activeTasks.value.toMutableMap()
        if (progress >= 100f) {
            current.remove(id)
        } else {
            current[id] = ActiveDownloadTask(id, title, progress, speed)
        }
        _activeTasks.value = current
    }

    fun addCompletedRecord(record: DownloadedRecord) {
        _downloadedHistory.value = listOf(record) + _downloadedHistory.value
    }

    fun removeRecord(record: DownloadedRecord) {
        val file = File(record.filePath)
        if (file.exists()) file.delete()
        _downloadedHistory.value = _downloadedHistory.value.filter { it != record }
    }
}

// ================= DOWNLOAD ENGINE =================
object DownloaderBridge {
    private var isInitDone = false

    fun ensureInit(context: Context) {
        if (!isInitDone) {
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                com.yausername.ffmpeg.FFmpeg.getInstance().init(context.applicationContext)
                com.yausername.aria2c.Aria2c.getInstance().init(context.applicationContext)
                isInitDone = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getDownloadDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun fixUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.split("?si=")[0]
        }
        val idMatch = Regex("([a-zA-Z0-9_-]{11})").find(trimmed)
        return if (idMatch != null) "https://www.youtube.com/watch?v=${idMatch.value}" else "ytsearch1:$trimmed"
    }

    suspend fun searchTop10(context: Context, query: String): List<SearchItem> = withContext(Dispatchers.IO) {
        ensureInit(context)
        val validUrl = if (query.trim().startsWith("http://") || query.trim().startsWith("https://")) {
            fixUrl(query)
        } else {
            "ytsearch10:${query.trim()}"
        }

        val request = YoutubeDLRequest(validUrl).apply {
            addOption("--dump-single-json")
            addOption("--flat-playlist")
            addOption("--no-warnings")
            addOption("--no-check-certificate")
        }

        try {
            val response = YoutubeDL.getInstance().execute(request)
            val out = response.out ?: ""
            val list = mutableListOf<SearchItem>()
            if (out.isNotBlank()) {
                val json = org.json.JSONObject(out)
                val entries = json.optJSONArray("entries")
                if (entries != null && entries.length() > 0) {
                    val count = minOf(10, entries.length())
                    for (i in 0 until count) {
                        val entry = entries.getJSONObject(i)
                        val vId = entry.optString("id", "")
                        val secs = entry.optInt("duration", 0)
                        val durationFormatted = if (secs > 0) {
                            val m = secs / 60
                            val s = secs % 60
                            "%02d:%02d".format(m, s)
                        } else {
                            "00:00"
                        }
                        val title = entry.optString("title", "Unknown Title")
                        val uploader = if (entry.has("uploader")) entry.optString("uploader") else entry.optString("channel", "Artist")
                        val thumbnail = if (entry.has("thumbnail")) {
                            entry.optString("thumbnail")
                        } else if (vId.isNotEmpty()) {
                            "https://i.ytimg.com/vi/$vId/hqdefault.jpg"
                        } else {
                            ""
                        }
                        val itemUrl = if (vId.isNotEmpty()) "https://www.youtube.com/watch?v=$vId" else entry.optString("url", "")
                        list.add(
                            SearchItem(
                                id = vId,
                                title = title,
                                uploader = uploader,
                                duration = durationFormatted,
                                thumbnail = thumbnail,
                                url = itemUrl
                            )
                        )
                    }
                } else {
                    // Single item result
                    val vId = json.optString("id", "")
                    val secs = json.optInt("duration", 0)
                    val durationFormatted = if (secs > 0) {
                        val m = secs / 60
                        val s = secs % 60
                        "%02d:%02d".format(m, s)
                    } else {
                        "00:00"
                    }
                    val title = json.optString("title", "Unknown Title")
                    val uploader = if (json.has("uploader")) json.optString("uploader") else json.optString("channel", "Artist")
                    val thumbnail = if (json.has("thumbnail")) {
                        json.optString("thumbnail")
                    } else if (vId.isNotEmpty()) {
                        "https://i.ytimg.com/vi/$vId/hqdefault.jpg"
                    } else {
                        ""
                    }
                    val itemUrl = if (vId.isNotEmpty()) {
                        "https://www.youtube.com/watch?v=$vId"
                    } else {
                        json.optString("webpage_url", json.optString("url", validUrl))
                    }
                    list.add(
                        SearchItem(
                            id = vId,
                            title = title,
                            uploader = uploader,
                            duration = durationFormatted,
                            thumbnail = thumbnail,
                            url = itemUrl
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun executeDownload(
        context: Context,
        targetUrl: String,
        formatSpec: String,
        isAudioOnly: Boolean,
        audioBitrate: String? = null,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        ensureInit(context)
        val validUrl = fixUrl(targetUrl)
        val downloadDir = getDownloadDir(context)
        val existingFiles = downloadDir.listFiles()?.toSet() ?: emptySet()
        val outPattern = "${downloadDir.absolutePath}/%(title).100B.%(ext)s"

        val request = YoutubeDLRequest(validUrl).apply {
            addOption("--no-warnings")
            addOption("--no-check-certificate")
            addOption("--prefer-free-formats")
            addOption("--extractor-args", "youtube:player_client=android,web")
            if (isAudioOnly) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                if (!audioBitrate.isNullOrBlank()) {
                    addOption("--audio-quality", audioBitrate)
                }
                addOption("-f", "bestaudio/ba/b")
            } else {
                addOption("-f", "$formatSpec+bestaudio/bestvideo+bestaudio/best[ext=mp4]/best")
                addOption("--merge-output-format", "mp4")
            }
            addOption("-o", outPattern)
            addOption("--no-mtime")
        }

        YoutubeDL.getInstance().execute(request) { p, _, line ->
            onProgress(p, line ?: "")
        }

        val allFiles = downloadDir.listFiles()?.toSet() ?: emptySet()
        val newlyCreated = allFiles - existingFiles
        val targetFile = newlyCreated.maxByOrNull { it.lastModified() }
            ?: downloadDir.listFiles()?.maxByOrNull { it.lastModified() }
            ?: File(downloadDir, "media.mp4")

        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(if (isAudioOnly) "audio/mpeg" else "video/mp4"),
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        targetFile
    }
}

// ================= ACTIVITY =================
class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureIntentUrl(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    background = Color(0xFF0A0E17),
                    surface = Color(0xFF141A26),
                    surfaceVariant = Color(0xFF1F2637)
                )
            ) {
                MainAppScaffold(
                    initialSharedUrl = sharedUrl,
                    onUrlConsumed = { sharedUrl = "" }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureIntentUrl(intent)
    }

    private fun captureIntentUrl(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val incoming = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val found = Regex("""(https?://[^\s]+)""").find(incoming)?.value
            if (!found.isNullOrEmpty()) {
                sharedUrl = found.trim()
            }
        }
    }
}

// ================= MAIN SCAFFOLD & TABS =================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    initialSharedUrl: String,
    onUrlConsumed: () -> Unit,
    vm: SealViewModel = viewModel()
) {
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    val activeTasks by vm.activeTasks.collectAsState()
    val downloadedHistory by vm.downloadedHistory.collectAsState()
    val context = LocalContext.current

    // Request Storage, Media, and Notification Permissions
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                context,
                "Storage permissions recommended for saving downloaded media",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permissionsLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                AppTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    val badgeNum = if (tab == AppTab.TASKS) activeTasks.size else 0

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        icon = {
                            BadgedBox(badge = {
                                if (badgeNum > 0) Badge { Text(badgeNum.toString()) }
                            }) {
                                Icon(if (isSelected) tab.activeIcon else tab.inactiveIcon, contentDescription = tab.label)
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            indicatorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                AppTab.HOME -> HomeSearchScreen(
                    incomingUrl = initialSharedUrl,
                    onUrlHandled = onUrlConsumed,
                    onStartDownload = { url, format, isAudio, qualityLabel, title, thumb ->
                        // Check WRITE_EXTERNAL_STORAGE on Android <= 9 (API 28)
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            val writePerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                            if (writePerm != PackageManager.PERMISSION_GRANTED) {
                                permissionsLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                            }
                        }

                        val taskId = System.currentTimeMillis().toString()
                        vm.viewModelScope.launch {
                            try {
                                val savedFile = DownloaderBridge.executeDownload(
                                    context = context,
                                    targetUrl = url,
                                    formatSpec = format,
                                    isAudioOnly = isAudio,
                                    audioBitrate = if (isAudio) qualityLabel else null
                                ) { p, spd ->
                                    vm.updateProgress(taskId, title, p, spd)
                                }
                                vm.updateProgress(taskId, title, 100f, "")
                                val mb = "${(savedFile.length() / (1024 * 1024))} MB"
                                vm.addCompletedRecord(
                                    DownloadedRecord(
                                        title = title,
                                        filePath = savedFile.absolutePath,
                                        thumbnail = thumb,
                                        quality = if (isAudio) "$qualityLabel MP3" else qualityLabel,
                                        ext = if (isAudio) "mp3" else "mp4",
                                        fileSize = mb
                                    )
                                )
                                Toast.makeText(context, "Saved: $title", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                vm.updateProgress(taskId, title, 100f, "")
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                AppTab.TASKS -> TasksListScreen(tasks = activeTasks.values.toList())
                AppTab.LIBRARY -> LibraryHistoryScreen(
                    records = downloadedHistory,
                    onDelete = { vm.removeRecord(it) },
                    onOpenFile = { record ->
                        val f = File(record.filePath)
                        if (f.exists()) {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, if (record.ext == "mp3") "audio/*" else "video/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Play with"))
                        } else {
                            Toast.makeText(context, "File does not exist on disk", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

// ================= 1. HOME & 10-SEARCH SCREEN =================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSearchScreen(
    incomingUrl: String,
    onUrlHandled: () -> Unit,
    onStartDownload: (url: String, format: String, isAudio: Boolean, qualityLabel: String, title: String, thumb: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var searchInput by remember { mutableStateOf("") }
    var searchList by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var isBusy by remember { mutableStateOf(false) }

    var selectedItem by remember { mutableStateOf<SearchItem?>(null) }
    var playingUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl.isNotBlank()) {
            searchInput = incomingUrl
            onUrlHandled()
            isBusy = true
            scope.launch {
                searchList = DownloaderBridge.searchTop10(context, incomingUrl)
                isBusy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // App Bar Title
        Text(
            text = "Seal Downloader",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Search Box
        OutlinedTextField(
            value = searchInput,
            onValueChange = { searchInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search song, artist, or paste URL...", color = Color.Gray) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            trailingIcon = {
                Row {
                    IconButton(onClick = {
                        clipboard.getText()?.let {
                            val raw = it.text.toString()
                            val parsed = Regex("""(https?://[^\s]+)""").find(raw)?.value ?: raw
                            searchInput = parsed
                        }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste", tint = Color.Gray)
                    }
                    IconButton(onClick = {
                        if (searchInput.isNotBlank()) {
                            isBusy = true
                            scope.launch {
                                searchList = DownloaderBridge.searchTop10(context, searchInput)
                                isBusy = false
                                if (searchList.isEmpty()) {
                                    Toast.makeText(context, "No results found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // In-App Player Card
        AnimatedVisibility(visible = playingUrl != null) {
            playingUrl?.let { playStream ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        StreamPlayer(url = playStream)
                        IconButton(
                            onClick = { playingUrl = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }
            }
        }

        if (isBusy) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // 10-Result List
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(searchList) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { selectedItem = item }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(110.dp, 68.dp).clip(RoundedCornerShape(10.dp))) {
                        AsyncImage(
                            model = item.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            text = item.duration,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(0.75f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.uploader,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    // Material 3 Bottom Sheet for Stream Preview & Multi-Format Downloading
    selectedItem?.let { item ->
        MediaDownloadBottomSheet(
            item = item,
            onDismissRequest = { selectedItem = null },
            onPlayStream = { url ->
                playingUrl = url
            },
            onDownloadAudio = { url, bitrate, title, thumb ->
                onStartDownload(url, "ba/b", true, bitrate, title, thumb)
            },
            onDownloadVideo = { url, resolution, formatSelector, title, thumb ->
                onStartDownload(url, formatSelector, false, resolution, title, thumb)
            }
        )
    }
}

// ================= 2. ACTIVE TASKS SCREEN =================
@Composable
fun TasksListScreen(tasks: List<ActiveDownloadTask>) {
    if (tasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No downloads running", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(tasks, key = { it.id }) { task ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(task.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${task.progress.toInt()}%", color = Color.LightGray, fontSize = 12.sp)
                        Text(task.speed, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ================= 3. LIBRARY & HISTORY SCREEN =================
@Composable
fun LibraryHistoryScreen(
    records: List<DownloadedRecord>,
    onDelete: (DownloadedRecord) -> Unit,
    onOpenFile: (DownloadedRecord) -> Unit
) {
    if (records.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No downloads in library", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(records) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onOpenFile(item) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.size(65.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${item.quality} • ${item.ext.uppercase()} • ${item.fileSize}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(0.8f))
                    }
                }
            }
        }
    }
}

// ================= PLAYER COMPOSABLE =================
@Composable
fun StreamPlayer(url: String) {
    val context = LocalContext.current
    var hasError by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    hasError = true
                }
            })
            try {
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                playWhenReady = true
            } catch (e: Exception) {
                hasError = true
            }
        }
    }
    DisposableEffect(url) {
        onDispose { player.release() }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Unable to stream video preview directly",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            "Open In External Player / Browser",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
