package com.local.tyranopocket

import android.content.Context
import com.local.tyranopocket.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class Game(val id: String, val title: String, val entry: String, val shim: Boolean,
    val supported: Boolean, val orientation: String, val directory: File) {
    val host get() = "game-$id.invalid"
    val origin get() = "https://$host"
    val runtime get() = File(directory, "runtime")
}

class GameLibrary(private val context: Context) {
    fun games(): List<Game> = File(context.filesDir, "games").listFiles().orEmpty().mapNotNull { directory ->
        runCatching {
            require(directory.name.matches(Regex("[0-9a-f-]{36}")))
            val m = Json.`object`(Json.parse(SafeFiles.text(File(directory, "metadata/game.json"), 1024 * 1024)))
            require(m["id"] == directory.name)
            Game(directory.name, m["title"] as String, SafeFiles.relative(m["entry"] as String),
                m["shim"] == true, m["supported"] == true, m["orientation"] as? String ?: "auto", directory)
        }.getOrNull()
    }.sortedBy { it.title }

    fun orientation(game: Game, value: String) {
        require(value in listOf("auto", "landscape", "portrait"))
        val target = File(game.directory, "metadata/game.json")
        val m = Json.`object`(Json.parse(SafeFiles.text(target, 1024 * 1024)))
        m["orientation"] = value
        val temporary = File(target.parentFile, "game.next.json")
        SafeFiles.write(temporary, Json.encode(m))
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun recheck(game: Game): String {
        val found = GameDetector().detect(game.runtime, game.title)
        val vfs = VirtualFileSystem.scan(game.runtime)
        val report = found.json().apply { put("caseCollisions", vfs.collisions()) }
        SafeFiles.write(File(game.directory, "compat/recheck.json"), Json.encode(report))
        // A recheck never rewrites original game scripts or clears saves.
        return "파일 재검사 결과\n" + Json.encode(report)
    }

    fun diagnostics(game: Game): String = buildString {
        appendLine("TyranoPocket ${BuildConfig.VERSION_NAME}")
        for (p in listOf("compat/report.json", "compat/recheck.json", "compat/runtime.log")) {
            val f = File(game.directory, p)
            if (f.isFile) { appendLine(p); appendLine(SafeFiles.text(f, 2 * 1024 * 1024)) }
        }
    }
    fun removeFiles(game: Game) = SafeFiles.delete(game.directory)
}
