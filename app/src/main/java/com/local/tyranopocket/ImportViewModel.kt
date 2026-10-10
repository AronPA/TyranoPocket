package com.local.tyranopocket

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.tyranopocket.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

data class LibraryState(val games: List<Game> = emptyList(), val importing: Boolean = false,
    val phase: String = "", val progress: Float? = null, val error: String? = null)

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    val library = GameLibrary(app)
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var ready = false

    init { viewModelScope.launch(Dispatchers.IO) {
        runCatching { GameImporter(app.filesDir).clearInterruptedImports() }
        mutable.value = mutable.value.copy(games = library.games())
        ready = true
    } }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun report(message: String) { mutable.value = mutable.value.copy(error = message) }
    fun cancel() { cancelled.set(true) }
    fun refresh() { viewModelScope.launch(Dispatchers.IO) { mutable.value = mutable.value.copy(games = library.games()) } }

    fun import(uri: Uri, folder: Boolean) {
        if (!ready || mutable.value.importing) return
        cancelled.set(false)
        mutable.value = mutable.value.copy(importing = true, phase = "게임 확인 중", progress = null, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]!!
            var lastUpdate = 0L
            var lastPhase = ""
            val monitor = SafeFiles.Monitor { phase, done, total ->
                if (cancelled.get() || !job.isActive) throw InterruptedIOException("CANCELLED")
                val now = System.nanoTime()
                if (now - lastUpdate > 100_000_000 || done == total || phase != lastPhase) {
                    lastUpdate = now
                    lastPhase = phase
                    mutable.value = mutable.value.copy(phase = phase, progress = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null)
                }
            }
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val importer = GameImporter(context.filesDir)
                if (folder) {
                    val tree = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("GAME_NOT_FOUND")
                    importer.importSource({ target, progress -> copyTree(tree, target, progress) }, tree.name ?: "내 게임", monitor, mutableListOf())
                } else {
                    val name = DocumentFile.fromSingleUri(context, uri)?.name?.removeSuffix(".zip") ?: "내 게임"
                    context.contentResolver.openInputStream(uri)?.use { importer.importZip(it, name, monitor) }
                        ?: throw IOException("SOURCE_UNREADABLE")
                }
                mutable.value = LibraryState(games = library.games())
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(importing = false, error = if (cancelled.get() || e is InterruptedIOException) null else friendlyError(e))
            }
        }
    }

    private fun copyTree(tree: DocumentFile, target: File, monitor: SafeFiles.Monitor) {
        data class Item(val file: DocumentFile, val path: String)
        val items = mutableListOf<Item>()
        val seen = hashSetOf<String>()
        var bytes = 0L
        var visited = 0
        fun visit(directory: DocumentFile, prefix: String, depth: Int) {
            if (depth > 64) throw IOException("PATH_DEPTH_LIMIT")
            if (!directory.canRead()) throw IOException("SOURCE_UNREADABLE")
            for (child in directory.listFiles()) {
                monitor.update("파일 확인 중", visited++.toLong(), -1)
                if (visited > SafeFiles.MAX_FILES) throw IOException("FILE_COUNT_LIMIT")
                val component = child.name ?: throw IOException("UNSAFE_PATH")
                if ('/' in component || '\\' in component) throw IOException("UNSAFE_PATH")
                val path = SafeFiles.relative(prefix + component)
                if (!seen.add(path)) throw IOException("DUPLICATE_PATH")
                if (child.isDirectory) visit(child, "$path/", depth + 1)
                else if (child.isFile) {
                    val size = child.length()
                    if (size < 0 || size > SafeFiles.MAX_BYTES - bytes) throw IOException("SIZE_LIMIT")
                    bytes += size; items += Item(child, path)
                } else throw IOException("SOURCE_UNREADABLE")
            }
        }
        visit(tree, "", 0)
        SafeFiles.space(target, bytes)
        var copied = 0L
        for (item in items) {
            context.contentResolver.openInputStream(item.file.uri)?.use { input ->
                // Some SAF providers report 0 for an unknown length; enforce actual total too.
                copied += SafeFiles.copy(input, SafeFiles.child(target, item.path), if (item.file.length() > 0) item.file.length() else -1,
                    { p, d, _ -> if (copied + d > SafeFiles.MAX_BYTES) throw IOException("SIZE_LIMIT") else monitor.update(p, copied + d, bytes) }, "게임 복사 중")
            } ?: throw IOException("SOURCE_UNREADABLE")
        }
    }
    override fun onCleared() { cancelled.set(true); super.onCleared() }
}

fun friendlyError(error: Throwable): String = when {
    error.message?.contains("SPACE") == true -> "저장 공간이 부족합니다. 휴대폰의 여유 공간을 확보한 뒤 다시 시도해 주세요."
    error.message == "GAME_NOT_FOUND" -> "이 폴더에서는 실행 가능한 게임을 찾지 못했습니다. 게임의 최상위 폴더 또는 ZIP을 선택해 주세요."
    error.message == "MULTIPLE_GAMES" -> "여러 게임이 함께 들어 있습니다. 추가하려는 게임 하나의 폴더 또는 ZIP을 선택해 주세요."
    error.message?.startsWith("HTML_") == true -> "게임 파일은 발견했지만 현재 버전에서 실행 구조를 확인하지 못했습니다."
    error.message?.contains("UNSUPPORTED") == true -> "이 게임은 현재 TyranoPocket에서 지원하지 않는 기능을 사용합니다."
    error.message?.contains("LIMIT") == true -> "게임 파일의 크기나 개수가 현재 버전에서 처리할 수 있는 범위를 넘었습니다."
    error.message?.contains("PATH") == true -> "안전하게 읽을 수 없는 파일 경로가 있어 가져오기를 중단했습니다."
    error is SecurityException -> "선택한 파일을 읽을 수 없습니다. 게임 파일 또는 폴더를 다시 선택해 주세요."
    else -> "게임 파일을 읽지 못했습니다. 파일이 손상되지 않았는지 확인하고 다시 선택해 주세요."
}
