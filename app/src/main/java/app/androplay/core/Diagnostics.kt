package app.androplay.core

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Keep a small, private history using only event names and explicitly selected fields. */
class Diagnostics(context: Context, scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "diagnostics.txt"))
    private val initial = runCatching { file.readFully().decodeToString().lineSequence().filter { it.isNotBlank() }.takeLastEntries() }
        .getOrDefault(emptyList())
    private val events = MutableStateFlow(initial)
    val history = events.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            history.collect { lines ->
                runCatching {
                    val out = file.startWrite()
                    try {
                        out.write(lines.joinToString("\n").encodeToByteArray())
                        file.finishWrite(out)
                    } catch (e: Exception) {
                        file.failWrite(out)
                        throw e
                    }
                }
            }
        }
    }

    @Synchronized
    fun record(source: String, event: String) {
        val previous = events.value
        val message = "$source · $event"
        val now = System.currentTimeMillis()
        val last = previous.lastOrNull()
        if (last?.substringAfter('\t') == message &&
            now - (last.substringBefore('\t').toLongOrNull() ?: 0) < 10_000
        ) return
        events.value = previous.takeLast(LIMIT - 1) + "$now\t$message"
    }

    private fun Sequence<String>.takeLastEntries() = toList().takeLast(LIMIT)

    private companion object {
        const val LIMIT = 250
    }
}
