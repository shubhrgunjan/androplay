package app.androplay.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androplay.AndroPlayApp
import java.text.DateFormat
import java.util.Date

class LogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AndroPlayApp
        setContent {
            val history by app.diagnostics.history.collectAsState()
            AndroPlayTheme(dark = true) {
                Surface {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                        TextButton(onClick = ::finish) { Text("Back") }
                        Text("Activity logs", style = MaterialTheme.typography.headlineMedium)
                        if (history.isEmpty()) Text("No events recorded yet.")
                        SelectionContainer {
                            LazyColumn(
                                modifier = Modifier.padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(history.asReversed()) { row ->
                                    val timestamp = row.substringBefore('\t').toLongOrNull()
                                    val time = timestamp?.let { DateFormat.getDateTimeInstance().format(Date(it)) }.orEmpty()
                                    Text("$time · ${row.substringAfter('\t')}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
