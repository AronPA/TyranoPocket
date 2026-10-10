package com.local.tyranopocket

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import com.local.tyranopocket.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var activeRuntime: WebGameRuntime? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Pocket() }
            }
        }
    }
    override fun onPause() { activeRuntime?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); activeRuntime?.resume() }

    @Composable private fun Pocket(vm: ImportViewModel = viewModel()) {
        val state by vm.state.collectAsState()
        val scope = rememberCoroutineScope()
        var add by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf<Game?>(null) }
        var delete by remember { mutableStateOf<Game?>(null) }
        var detail by remember { mutableStateOf<String?>(null) }
        var pendingExport by remember { mutableStateOf("") }
        var playing by remember { mutableStateOf<Pair<Game, VirtualFileSystem>?>(null) }
        var loading by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }
        val zip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it, false) } }
        val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.import(it, true) } }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) scope.launch(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray(Charsets.UTF_8)) } ?: error("EXPORT_FAILED") }
                    .onFailure { vm.report("상세 정보를 저장하지 못했습니다. 다시 시도해 주세요.") }
            }
        }
        fun launch(game: Game) {
            if (loading) return
            if (!game.supported) { vm.report("이 게임은 현재 TyranoPocket에서 지원하지 않는 기능을 사용합니다. 게임 정보에서 상세 내용을 확인할 수 있습니다."); return }
            loading = true
            scope.launch {
                try {
                    val vfs = withContext(Dispatchers.IO) {
                        val json = Json.parse(SafeFiles.text(File(game.directory, "metadata/paths.json"), 32 * 1024 * 1024)) as List<*>
                        VirtualFileSystem(game.runtime, json.map { it as String })
                    }
                    playing = game to vfs
                } catch (e: Exception) { vm.report(friendlyError(e)) }
                finally { loading = false }
            }
        }
        val current = playing
        if (current != null) {
            PlaySurface(current.first, current.second, vm, onExit = { playing = null })
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("TyranoPocket", style = MaterialTheme.typography.headlineLarge)
                if (state.games.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("아직 등록된 게임이 없습니다.", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { add = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !state.importing) { Text("게임 추가하기") }
                    }
                } else {
                    Text("내 게임", style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(state.games, key = { it.id }) { game ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(game.title, style = MaterialTheme.typography.titleLarge)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(onClick = { launch(game) }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = !loading && !deleting) { Text("플레이") }
                                        OutlinedButton(onClick = { menu = game }, Modifier.heightIn(min = 52.dp), enabled = !deleting) { Text("메뉴") }
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick = { add = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !state.importing && !deleting) { Text("+ 게임 추가") }
                }
                if (loading || deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("게임을 어떻게 가져올까요?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { add = false; zip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("ZIP 파일 선택") }
                OutlinedButton(onClick = { add = false; tree.launch(null) }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("게임 폴더 선택") }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { add = false }) { Text("취소") } })

        if (state.importing) AlertDialog(onDismissRequest = {}, title = { Text("게임 가져오는 중") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.phase)
                val progress = state.progress
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()); Text("${(progress * 100).toInt()}%") }
                Text("잠시만 기다려 주세요.")
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = vm::cancel) { Text("가져오기 취소") } })

        menu?.let { game ->
            AlertDialog(onDismissRequest = { menu = null }, title = { Text(game.title) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { menu = null; launch(game) }, Modifier.fillMaxWidth()) { Text("플레이") }
                    for (label in listOf("게임 정보", "호환성 검사")) TextButton(onClick = {
                        menu = null; scope.launch { detail = withContext(Dispatchers.IO) { runCatching { vm.library.diagnostics(game) }.getOrDefault("상세 정보를 읽지 못했습니다.") } }
                    }, Modifier.fillMaxWidth()) { Text(label) }
                    TextButton(onClick = { menu = null; scope.launch { detail = withContext(Dispatchers.IO) { runCatching { vm.library.recheck(game) }.getOrElse(::friendlyError) } } }, Modifier.fillMaxWidth()) { Text("게임 파일 다시 검사") }
                    Text("화면 방향", style = MaterialTheme.typography.titleSmall)
                    listOf("auto" to "자동", "landscape" to "가로 고정", "portrait" to "세로 고정").forEach { (value, label) ->
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { runCatching { vm.library.orientation(game, value) }.onFailure { vm.report(friendlyError(it)) }; vm.refresh() }; menu = null }, Modifier.fillMaxWidth()) { Text(if (game.orientation == value) "✓ $label" else label) }
                    }
                    TextButton(onClick = { menu = null; delete = game }, Modifier.fillMaxWidth()) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                }
            }, confirmButton = { TextButton(onClick = { menu = null }) { Text("닫기") } })
        }
        delete?.let { game ->
            AlertDialog(onDismissRequest = { delete = null }, title = { Text("이 게임을 삭제할까요?") },
                text = { Text("게임 파일과 이 앱에 저장된 데이터가 삭제됩니다.") },
                dismissButton = { TextButton(onClick = { delete = null }) { Text("취소") } },
                confirmButton = { TextButton(onClick = {
                    delete = null
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                        vm.report("이 기기의 웹 구성요소에서는 게임별 저장 데이터 삭제를 지원하지 않습니다. Android System WebView 업데이트 후 다시 시도해 주세요.")
                    } else {
                        deleting = true
                        runCatching {
                            // Each game owns a distinct registrable .invalid domain, not a shared parent site.
                            WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), game.origin) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { vm.library.removeFiles(game) }.onFailure { vm.report("게임 파일을 삭제하지 못했습니다. 다시 시도해 주세요.") } }
                                    vm.refresh(); deleting = false
                                }
                            }
                        }.onFailure { deleting = false; vm.report("게임 데이터를 삭제하지 못했습니다. 다시 시도해 주세요.") }
                    }
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) } })
        }
        detail?.let { text ->
            AlertDialog(onDismissRequest = { detail = null }, title = { Text("상세 정보") },
                text = { Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                confirmButton = { TextButton(onClick = { detail = null }) { Text("닫기") } },
                dismissButton = { TextButton(onClick = { pendingExport = text; export.launch("TyranoPocket-diagnostics.txt") }) { Text("파일로 저장") } })
        }
        state.error?.let { error -> AlertDialog(onDismissRequest = vm::clearError, title = { Text("확인이 필요합니다") }, text = { Text(error) }, confirmButton = { TextButton(onClick = vm::clearError) { Text("확인") } }) }
    }

    @Composable private fun PlaySurface(game: Game, vfs: VirtualFileSystem, vm: ImportViewModel, onExit: () -> Unit) {
        var menu by remember { mutableStateOf(false) }
        val runtime = remember(game.id) { WebGameRuntime(this, game, vfs) { error -> runOnUiThread { vm.report(error) } } }
        DisposableEffect(runtime) {
            activeRuntime = runtime
            requestedOrientation = when (game.orientation) { "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            runtime.resume()
            onDispose {
                runtime.close(); activeRuntime = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        AndroidView(factory = { runtime.container }, modifier = Modifier.fillMaxSize().safeDrawingPadding())
        BackHandler { runtime.hideVideo(); runtime.pause(); menu = true }
        if (menu) AlertDialog(onDismissRequest = { menu = false; runtime.resume() }, title = { Text("게임 메뉴") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { menu = false; runtime.resume() }, Modifier.fillMaxWidth()) { Text("게임으로 돌아가기") }
                OutlinedButton(onClick = { menu = false; runtime.reload(); runtime.resume() }, Modifier.fillMaxWidth()) { Text("처음부터 다시 불러오기") }
                OutlinedButton(onClick = { menu = false; onExit() }, Modifier.fillMaxWidth()) { Text("메인 화면으로") }
            }
        }, confirmButton = {})
    }
}
