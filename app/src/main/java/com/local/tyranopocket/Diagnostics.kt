package com.local.tyranopocket

import com.local.tyranopocket.core.SafeFiles
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Diagnostics(private val directory: File) : AutoCloseable {
    private val lines = ArrayDeque<String>()
    private val executor = Executors.newSingleThreadExecutor()
    private val scheduled = AtomicBoolean(false)
    @Synchronized fun record(event: String) {
        if (executor.isShutdown) return
        // Caller supplies event codes and game-relative paths only. Never log JS values,
        // document-provider URIs, external URLs, save values or Android filesystem paths.
        lines.addLast("${System.currentTimeMillis()} ${event.take(500).replace('\n', ' ')}")
        while (lines.size > 500) lines.removeFirst()
        if (scheduled.compareAndSet(false, true) && !executor.isShutdown) executor.execute {
            val snapshot = synchronized(this) { lines.joinToString("\n") }
            runCatching { SafeFiles.write(File(directory, "compat/runtime.log"), snapshot) }
            scheduled.set(false)
        }
    }
    @Synchronized override fun close() {
        if (executor.isShutdown) return
        val snapshot = lines.joinToString("\n")
        executor.execute { runCatching { SafeFiles.write(File(directory, "compat/runtime.log"), snapshot) } }
        executor.shutdown()
    }
}
