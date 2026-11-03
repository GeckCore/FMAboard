package org.futo.inputmethod.latin.fmaboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.util.Locale

/**
 * "Descargar enlace": paste/receive a link, download the video or only its audio, then send
 * it to the chat you came from, share it anywhere or save it to the gallery.
 */
class LinkDownloadActivity : ComponentActivity() {
    private val targetPackage by lazy { intent.getStringExtra(LinkDownload.EXTRA_TARGET_PACKAGE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent.getStringExtra(LinkDownload.EXTRA_URL) ?: ""
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen(initialUrl)
                }
            }
        }
    }

    private fun targetLabel(): String? = targetPackage?.let { pkg ->
        if (pkg == packageName) return null
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { null }
    }

    private fun send(done: LinkDownload.State.Done, toTarget: Boolean) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fmaboard.files", done.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(done.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            if (toTarget && targetPackage != null) {
                startActivity(send.setPackage(targetPackage))
            } else {
                startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Esa app no acepta este archivo", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun Screen(initialUrl: String) {
        var url by remember { mutableStateOf(initialUrl) }
        val state by LinkDownload.state.collectAsState()

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Descargar enlace", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, contentDescription = "Cerrar") }
            }

            when (val s = state) {
                is LinkDownload.State.Working -> {
                    Text(s.message)
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text("Puedes cerrar esta pantalla: sigue descargando. Vuelve a abrir \"Descargar enlace\" para verlo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    OutlinedButton(onClick = { LinkDownload.cancel() }) { Text("Cancelar") }
                }

                is LinkDownload.State.Done -> {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${if (s.audio) "Audio" else "Vídeo"} · ${String.format(Locale.US, "%.1f", s.file.length() / 1048576f)} MB",
                        color = MaterialTheme.colorScheme.outline)
                    val label = targetLabel()
                    if (label != null) {
                        Button(onClick = { send(s, toTarget = true) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Enviar por $label")
                        }
                    }
                    OutlinedButton(onClick = { send(s, toTarget = false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Compartir…")
                    }
                    OutlinedButton(onClick = {
                        val ok = LinkDownload.saveToGallery(this@LinkDownloadActivity, s)
                        Toast.makeText(this@LinkDownloadActivity,
                            if (ok) "Guardado en ${if (s.audio) "Música" else "Películas"}/FMAboard" else "No se pudo guardar",
                            Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Guardar en la galería")
                    }
                    TextButton(onClick = {
                        url = ""
                        LinkDownload.reset()
                    }) { Text("Descargar otro enlace") }
                }

                else -> {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Enlace (TikTok, YouTube, Instagram, X…)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (url.isBlank()) {
                        Text("Copia el enlace en la app y vuelve a pulsar \"Descargar enlace\" en el teclado, o pégalo aquí.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    val link = LinkDownload.findUrl(url)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { LinkDownload.start(this@LinkDownloadActivity, link!!, audio = false) },
                            enabled = link != null, modifier = Modifier.weight(1f)) { Text("Vídeo") }
                        OutlinedButton(onClick = { LinkDownload.start(this@LinkDownloadActivity, link!!, audio = true) },
                            enabled = link != null, modifier = Modifier.weight(1f)) { Text("Solo audio") }
                    }
                    if (s is LinkDownload.State.Failed) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = {
                            LinkDownload.start(this@LinkDownloadActivity, link ?: return@TextButton, audio = false, forceUpdate = true)
                        }, enabled = link != null) { Text("Actualizar el descargador y reintentar") }
                    }
                }
            }
        }
    }
}
