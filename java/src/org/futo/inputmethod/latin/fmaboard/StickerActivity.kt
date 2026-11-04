package org.futo.inputmethod.latin.fmaboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Everything chosen in the editor that changes how each picture is turned into the sticker. */
private data class StickerLook(
    val cut: StickerCut = StickerCut.None,
    val removeBg: Boolean = false,
    val shape: StickerMaker.Shape = StickerMaker.Shape.FULL,
    val outline: Boolean = false,
    val caption: String = "",
)

/**
 * "Crear sticker": a photo, video or GIF (picked here, from "Lo último de WhatsApp", or shared
 * from a chat) becomes a WhatsApp sticker, animated if it moves. Cut it to a rectangle or by
 * hand, take the background out (on the phone), add a white edge and a line of text; for
 * videos choose the bit. "Enviar" goes back to the chat and sends it.
 *
 * Shared through "Sticker al momento" it skips the editor and makes it as it is.
 */
class StickerActivity : ComponentActivity() {
    private var targetPackage: String? = null
    private var sharedUri: Uri? = null
    private var quick = false
    private var openSource: StickerSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(StickerSend.EXTRA_TARGET_PACKAGE)
        // From the clipboard: a file of our own, passed by path
        intent.getStringExtra(StickerSend.EXTRA_SOURCE_PATH)?.let { sharedUri = Uri.fromFile(File(it)) }
        if (intent.action == Intent.ACTION_SEND) {
            sharedUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            // Shared from a chat app: the sticker goes back there
            targetPackage = referrer?.takeIf { it.scheme == "android-app" }?.host?.takeIf { it != packageName }
            quick = intent.component?.className?.endsWith(".QuickSticker") == true
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) { StickerScreen() }
            }
        }
    }

    override fun onDestroy() {
        openSource?.release()
        super.onDestroy()
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun sendOrShare(file: File) {
        val target = targetPackage
        if (target != null) {
            StickerSend.setPending(file, target)
            if (intent.action == Intent.ACTION_SEND) {
                Toast.makeText(this, "Sticker listo: abre el chat y toca donde se escribe", Toast.LENGTH_LONG).show()
            }
            finishQuietly()
        } else {
            val share = Intent(Intent.ACTION_SEND).setType("image/webp")
                .putExtra(Intent.EXTRA_STREAM, StickerSend.uriFor(this, file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "Enviar sticker"))
        }
    }

    /** One picture through the cut and the background removal. */
    private fun process(raw: Bitmap, look: StickerLook, still: Boolean): Bitmap {
        var b = StickerEdit.applyCut(raw, look.cut)
        if (look.removeBg) {
            b = StickerEdit.removeBackground(b, if (still) 1024 else 512)
            // A photo is tightened round the subject; a video keeps its frame so it doesn't jump
            if (still) b = StickerEdit.trim(b)
        }
        return b
    }

    private fun hasMediaPermission(): Boolean {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun StickerScreen() {
        val scope = rememberCoroutineScope()
        var uri by remember { mutableStateOf<Uri?>(null) }
        var source by remember { mutableStateOf<StickerSource?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var look by remember { mutableStateOf(StickerLook()) }
        var rangeStart by remember { mutableFloatStateOf(0f) }
        var rangeEnd by remember { mutableFloatStateOf(0f) }
        var rawFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
        var processed by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
        var previewFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
        var previewFps by remember { mutableStateOf(6) }
        var shown by remember { mutableStateOf<Bitmap?>(null) }
        var working by remember { mutableStateOf<String?>(null) } // what the preview is busy with
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        var cutting by remember { mutableStateOf(false) }
        var modelReady by remember { mutableStateOf<Boolean?>(null) }
        var recents by remember { mutableStateOf<List<StickerEdit.Recent>?>(null) }
        var hasShared by remember { mutableStateOf(sharedUri != null) }

        val photoFile = remember { File(cacheDir, "stickers/photo.jpg").also { it.parentFile?.mkdirs() } }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
            if (picked != null) uri = picked
        }
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) uri = Uri.fromFile(photoFile).buildUpon().appendQueryParameter("t", "${System.currentTimeMillis()}").build()
        }
        fun pickMedia() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        fun loadRecents() {
            scope.launch { recents = withContext(Dispatchers.IO) { StickerEdit.recentWhatsApp(this@StickerActivity) } }
        }
        val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadRecents() }
        fun showRecents() {
            if (hasMediaPermission()) loadRecents()
            else askMedia.launch(when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            })
        }
        LaunchedEffect(Unit) { if (sharedUri == null && hasMediaPermission()) loadRecents() }

        LaunchedEffect(Unit) {
            val shared = sharedUri ?: return@LaunchedEffect
            try {
                uri = withContext(Dispatchers.IO) { StickerSource.copyToCache(this@StickerActivity, shared) }
                    .buildUpon().appendQueryParameter("t", "${System.currentTimeMillis()}").build()
            } catch (e: Exception) {
                error = "No se pudo abrir lo que has compartido"
            }
        }

        LaunchedEffect(uri) {
            val u = uri ?: return@LaunchedEffect
            source?.release()
            source = null
            rawFrames = emptyList()
            processed = emptyList()
            previewFrames = emptyList()
            look = look.copy(cut = StickerCut.None, removeBg = false)
            cutting = false
            error = null
            try {
                val s = withContext(Dispatchers.IO) {
                    StickerSource.open(this@StickerActivity, u.buildUpon().clearQuery().build())
                }
                rangeStart = 0f
                rangeEnd = min(s.durationMs, 5_000L) / 1000f
                source = s
                openSource = s
            } catch (e: Exception) {
                error = e.message ?: "No se pudo abrir"
            }
        }

        // Small frames for the preview: taken again only when the source or the bit changes
        LaunchedEffect(source, rangeStart, rangeEnd) {
            val s = source ?: return@LaunchedEffect
            if (!s.isAnimated) {
                rawFrames = listOfNotNull(withContext(Dispatchers.Default) { s.frameAt(0, 512)?.let { StickerEdit.fitForScreen(it, 900) } })
                return@LaunchedEffect
            }
            delay(300)
            val startMs = (rangeStart * 1000).roundToLong()
            val lengthMs = max(200L, ((rangeEnd - rangeStart) * 1000).roundToLong())
            val fps = min(8, StickerMaker.fpsFor(lengthMs))
            val count = max(2, (lengthMs * fps / 1000).toInt())
            previewFps = fps
            val frames = ArrayList<Bitmap>()
            withContext(Dispatchers.IO) {
                val ctx = coroutineContext
                s.frames(startMs, count, fps, 256) { i, f ->
                    frames.add(f)
                    if (i == 0 || i % 6 == 5) rawFrames = frames.toList()
                    ctx.isActive
                }
            }
            rawFrames = frames
        }

        // Cut and background, frame by frame (the slow part when the background goes)
        LaunchedEffect(rawFrames, look.cut, look.removeBg) {
            val raws = rawFrames
            if (raws.isEmpty()) { processed = emptyList(); return@LaunchedEffect }
            if (look.cut != StickerCut.None || look.removeBg) delay(250) // while the cut is being dragged
            val still = source?.isAnimated != true
            if (look.removeBg && modelReady != true) {
                working = "Preparando el quitafondos (solo la primera vez)…"
                val ok = withContext(Dispatchers.IO) { StickerEdit.ensureModel(this@StickerActivity) }
                modelReady = ok
                if (!ok) {
                    working = null
                    look = look.copy(removeBg = false)
                    Toast.makeText(this@StickerActivity, "No se pudo preparar el quitafondos. Comprueba la conexión y prueba otra vez.", Toast.LENGTH_LONG).show()
                    return@LaunchedEffect
                }
            }
            if (look.removeBg) working = "Quitando el fondo…"
            val l = look
            try {
                val out = ArrayList<Bitmap>()
                withContext(Dispatchers.Default) {
                    raws.forEachIndexed { i, r ->
                        ensureActive()
                        out.add(process(r, l, still))
                        if (l.removeBg && i % 4 == 3) processed = out.toList()
                    }
                }
                processed = if (l.removeBg && !still) withContext(Dispatchers.Default) { StickerEdit.trimAll(out) } else out
            } catch (e: Exception) {
                Log.w("FMAboardSticker", "Preview failed", e)
                if (l.removeBg) look = look.copy(removeBg = false)
            } finally {
                working = null
            }
        }

        LaunchedEffect(processed, look.shape, look.caption, look.outline) {
            val frames = processed
            val l = look
            previewFrames = withContext(Dispatchers.Default) {
                frames.map { StickerMaker.toCanvas(StickerMaker.draw(it, l.shape, l.caption, 256, outline = l.outline), 256) }
            }
        }

        LaunchedEffect(previewFrames, previewFps) {
            val frames = previewFrames
            if (frames.isEmpty()) { shown = null; return@LaunchedEffect }
            var i = 0
            while (true) {
                shown = frames[i % frames.size]
                if (frames.size == 1) break
                delay(1000L / previewFps)
                i++
            }
        }

        fun create() {
            val s = source ?: return
            val l = look
            busy = true
            progress = 0f
            scope.launch {
                try {
                    val file = withContext(Dispatchers.Default) {
                        if (l.removeBg && !StickerEdit.ensureModel(this@StickerActivity)) throw IllegalStateException("model")
                        val webp = if (!s.isAnimated) {
                            val src = s.frameAt(0, 1024) ?: throw IllegalStateException()
                            val p = process(src, l, true)
                            StickerMaker.encodeStatic(StickerMaker.toCanvas(StickerMaker.draw(p, l.shape, l.caption, outline = l.outline)),
                                preferLossless = l.removeBg || l.cut is StickerCut.Lasso)
                        } else {
                            val startMs = (rangeStart * 1000).roundToLong()
                            val lengthMs = max(200L, ((rangeEnd - rangeStart) * 1000).roundToLong())
                            val fps = StickerMaker.fpsFor(lengthMs)
                            val count = max(2, (lengthMs * fps / 1000).toInt())
                            val pictures = ArrayList<Bitmap>(count)
                            val t0 = System.currentTimeMillis()
                            val ctx = coroutineContext
                            s.frames(startMs, count, fps, StickerMaker.SIZE) { i, raw ->
                                pictures.add(process(raw, l, false))
                                progress = 0.7f * (i + 1) / count
                                ctx.isActive
                            }
                            ensureActive()
                            val trimmed = if (l.removeBg) StickerEdit.trimAll(pictures) else pictures
                            val frames = trimmed.map { StickerMaker.draw(it, l.shape, l.caption, outline = l.outline) }
                            if (frames.isEmpty()) throw IllegalStateException()
                            Log.i("FMAboardSticker", "Took ${frames.size} frames in ${System.currentTimeMillis() - t0} ms")
                            StickerMaker.encodeAnimated(frames, 1000 / fps) { progress = 0.7f + 0.3f * it }
                        }
                        StickerSend.save(this@StickerActivity, webp)
                    }
                    Log.i("FMAboardSticker", "Sticker ready: ${file.length()} bytes")
                    sendOrShare(file)
                } catch (e: Exception) {
                    Log.w("FMAboardSticker", "Could not make the sticker", e)
                    error = if (e.message == "model") "No se pudo preparar el quitafondos" else "No se pudo crear el sticker"
                } finally {
                    busy = false
                }
            }
        }

        // "Sticker al momento": as it is, straight away
        LaunchedEffect(source) {
            if (quick && source != null && !busy) create()
        }

        BackHandler(enabled = cutting) { cutting = false }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (cutting) cutting = false else finishQuietly() }) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                }
                Text(if (cutting) "Recortar" else "Crear sticker", color = Color.White,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (uri != null && !cutting && !quick) {
                    OutlinedButton(onClick = {
                        uri = null; source = null; hasShared = false
                        if (recents == null && hasMediaPermission()) loadRecents()
                    }, enabled = !busy, modifier = Modifier.padding(end = 8.dp)) { Text("Otra") }
                }
            }

            if (quick) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (error != null) Text(error!!, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                    else {
                        CircularProgressIndicator()
                        Text("Creando el sticker…", color = Color.White, modifier = Modifier.padding(16.dp))
                    }
                }
                return@Column
            }

            if (uri == null && !hasShared) {
                StartScreen(
                    recents = recents,
                    onPick = { pickMedia() },
                    onPhoto = { takePhoto.launch(StickerSend.uriFor(this@StickerActivity, photoFile)) },
                    onShowRecents = { showRecents() },
                    onRecent = { uri = it },
                    modifier = Modifier.weight(1f)
                )
                return@Column
            }

            val base = rawFrames.firstOrNull()
            if (cutting && base != null) {
                CutEditor(base, look.cut, Modifier.weight(1f)) { look = look.copy(cut = it) }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { look = look.copy(cut = StickerCut.None) }, modifier = Modifier.weight(1f)) { Text("Sin recorte") }
                    Button(onClick = { cutting = false }, modifier = Modifier.weight(1f)) { Text("Listo") }
                }
                return@Column
            }

            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0B141A)), contentAlignment = Alignment.Center) {
                val img = shown
                when {
                    error != null -> Text(error!!, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                    img == null -> CircularProgressIndicator()
                    else -> Image(img.asImageBitmap(), contentDescription = "Vista previa del sticker", modifier = Modifier.size(240.dp))
                }
                working?.let {
                    Row(Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(8.dp, 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            val s = source
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = look.cut != StickerCut.None, onClick = { cutting = true },
                            enabled = !busy && base != null, label = { Text("✂ Recortar") })
                        FilterChip(selected = look.removeBg, onClick = { look = look.copy(removeBg = !look.removeBg) },
                            enabled = !busy && base != null && working == null, label = { Text("Quitar fondo") })
                        FilterChip(selected = look.outline, onClick = { look = look.copy(outline = !look.outline) },
                            enabled = !busy, label = { Text("Borde blanco") })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Forma:", style = MaterialTheme.typography.bodyMedium)
                        StickerMaker.Shape.entries.forEach {
                            FilterChip(selected = look.shape == it, onClick = { look = look.copy(shape = it) }, enabled = !busy, label = { Text(it.label) })
                        }
                    }
                    OutlinedTextField(
                        value = look.caption,
                        onValueChange = { look = look.copy(caption = it.take(60)) },
                        placeholder = { Text("Texto encima (opcional)") },
                        enabled = !busy,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (s != null && s.isAnimated) {
                        val total = s.durationMs / 1000f
                        Text("Trozo: %.1f s – %.1f s (máx. %d s)".format(rangeStart, rangeEnd, StickerMaker.MAX_ANIMATED_MS / 1000),
                            style = MaterialTheme.typography.bodySmall)
                        RangeSlider(
                            value = rangeStart..rangeEnd,
                            onValueChange = { r ->
                                val maxLen = StickerMaker.MAX_ANIMATED_MS / 1000f
                                var a = r.start
                                var b = r.endInclusive
                                if (b - a > maxLen) {
                                    // Keep the handle being dragged where it is
                                    if (a != rangeStart) b = a + maxLen else a = b - maxLen
                                }
                                if (b - a < 0.5f) b = min(total, a + 0.5f)
                                rangeStart = a.coerceIn(0f, total)
                                rangeEnd = b.coerceIn(0f, total)
                            },
                            valueRange = 0f..total,
                            enabled = !busy
                        )
                    }
                    Text(
                        (if (s?.isAnimated == true) "Animado" else "Estático") +
                            (if (look.removeBg && s?.isAnimated == true) " · sin fondo tarda un poco más" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                    if (busy) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("Creando el sticker…", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { create() },
                        enabled = s != null && !busy && error == null && working == null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (targetPackage != null) "Enviar sticker" else "Compartir sticker") }
                }
            }
        }
    }

    @Composable
    private fun StartScreen(
        recents: List<StickerEdit.Recent>?,
        onPick: () -> Unit,
        onPhoto: () -> Unit,
        onShowRecents: () -> Unit,
        onRecent: (Uri) -> Unit,
        modifier: Modifier,
    ) {
        Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (recents == null) {
                Spacer(Modifier.weight(1f))
                Text("Elige una foto, un vídeo o un GIF y lo convierto en sticker de WhatsApp. Si se mueve, sale animado.",
                    color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Button(onClick = onShowRecents, modifier = Modifier.fillMaxWidth()) { Text("Lo último de WhatsApp") }
            } else {
                Text("Lo último de WhatsApp", color = Color.White, style = MaterialTheme.typography.titleSmall)
                if (recents.isEmpty()) {
                    Text("No encuentro fotos ni vídeos recibidos. Si solo diste acceso a algunas, elige desde la galería.",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(recents) { r ->
                            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)
                                .clickable { onRecent(r.uri) }) {
                                r.thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                if (r.isVideo) Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart)
                                    .padding(4.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(4.dp, 0.dp))
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Elegir de la galería") }
            OutlinedButton(onClick = onPhoto, modifier = Modifier.fillMaxWidth()) { Text("Hacer una foto") }
            Text("Truco: en WhatsApp, abre una foto, vídeo o GIF → Compartir → \"Sticker al momento\" lo hace tal cual, o \"Crear sticker\" para editarlo.",
                color = Color.Gray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (recents == null) Spacer(Modifier.weight(1f))
        }
    }

    /**
     * The picture with the cut on top. "Rectángulo": drag the corners or move the box. "A mano":
     * draw round what you want to keep.
     */
    @Composable
    private fun CutEditor(bitmap: Bitmap, cut: StickerCut, modifier: Modifier, onCut: (StickerCut) -> Unit) {
        var lassoMode by remember { mutableStateOf(cut is StickerCut.Lasso) }
        LaunchedEffect(Unit) { if (cut == StickerCut.None) onCut(StickerCut.Box(RectF(0.1f, 0.1f, 0.9f, 0.9f))) }
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val accent = MaterialTheme.colorScheme.primary
        Column(modifier) {
            Row(Modifier.padding(12.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !lassoMode, onClick = {
                    lassoMode = false
                    if (cut !is StickerCut.Box) onCut(StickerCut.Box(RectF(0.1f, 0.1f, 0.9f, 0.9f)))
                }, label = { Text("Rectángulo") })
                FilterChip(selected = lassoMode, onClick = { lassoMode = true }, label = { Text("A mano") })
            }
            Text(if (lassoMode) "Dibuja con el dedo alrededor de lo que quieres quedarte" else "Arrastra las esquinas o mueve el recuadro",
                color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp, 4.dp))

            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(12.dp)) {
                val viewW = constraints.maxWidth.toFloat()
                val viewH = constraints.maxHeight.toFloat()
                val scale = min(viewW / bitmap.width, viewH / bitmap.height)
                val imgW = bitmap.width * scale
                val imgH = bitmap.height * scale
                val offX = (viewW - imgW) / 2
                val offY = (viewH - imgH) / 2
                fun toNorm(p: Offset) = PointF(((p.x - offX) / imgW).coerceIn(0f, 1f), ((p.y - offY) / imgH).coerceIn(0f, 1f))
                fun toView(x: Float, y: Float) = Offset(offX + x * imgW, offY + y * imgH)

                val currentCut by rememberUpdatedState(cut)
                val currentOnCut by rememberUpdatedState(onCut)
                val drawing = remember { mutableStateListOf<PointF>() }
                val defaultBox = RectF(0.1f, 0.1f, 0.9f, 0.9f)

                Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize().pointerInput(lassoMode, imgW, imgH) {
                    if (lassoMode) {
                        detectDragGestures(
                            onDragStart = { drawing.clear(); drawing.add(toNorm(it)) },
                            onDrag = { change, _ -> drawing.add(toNorm(change.position)) },
                            onDragEnd = {
                                if (drawing.size > 8) currentOnCut(StickerCut.Lasso(drawing.toList()))
                                drawing.clear()
                            },
                            onDragCancel = { drawing.clear() }
                        )
                    } else {
                        var handle = -1 // 0..3 corners, 4 move
                        detectDragGestures(
                            onDragStart = { p ->
                                val r = (currentCut as? StickerCut.Box)?.rect ?: defaultBox
                                val corners = listOf(toView(r.left, r.top), toView(r.right, r.top), toView(r.left, r.bottom), toView(r.right, r.bottom))
                                val near = corners.indexOfFirst { hypot(it.x - p.x, it.y - p.y) < 80f }
                                handle = if (near >= 0) near else 4
                            },
                            onDrag = { change, drag ->
                                val r = RectF((currentCut as? StickerCut.Box)?.rect ?: defaultBox)
                                val dx = drag.x / imgW
                                val dy = drag.y / imgH
                                val minSize = 0.08f
                                when (handle) {
                                    0 -> { r.left = (r.left + dx).coerceIn(0f, r.right - minSize); r.top = (r.top + dy).coerceIn(0f, r.bottom - minSize) }
                                    1 -> { r.right = (r.right + dx).coerceIn(r.left + minSize, 1f); r.top = (r.top + dy).coerceIn(0f, r.bottom - minSize) }
                                    2 -> { r.left = (r.left + dx).coerceIn(0f, r.right - minSize); r.bottom = (r.bottom + dy).coerceIn(r.top + minSize, 1f) }
                                    3 -> { r.right = (r.right + dx).coerceIn(r.left + minSize, 1f); r.bottom = (r.bottom + dy).coerceIn(r.top + minSize, 1f) }
                                    else -> r.offset(dx.coerceIn(-r.left, 1f - r.right), dy.coerceIn(-r.top, 1f - r.bottom))
                                }
                                currentOnCut(StickerCut.Box(r))
                                change.consume()
                            }
                        )
                    }
                }) {
                    val dim = Color.Black.copy(alpha = 0.55f)
                    when {
                        drawing.isNotEmpty() -> {
                            val path = Path()
                            drawing.forEachIndexed { i, p -> val v = toView(p.x, p.y); if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y) }
                            drawPath(path, accent, style = Stroke(6f))
                        }
                        cut is StickerCut.Lasso -> {
                            val path = Path()
                            cut.points.forEachIndexed { i, p -> val v = toView(p.x, p.y); if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y) }
                            path.close()
                            drawPath(path, accent.copy(alpha = 0.18f))
                            drawPath(path, accent, style = Stroke(6f))
                        }
                        cut is StickerCut.Box -> {
                            val a = toView(cut.rect.left, cut.rect.top)
                            val b = toView(cut.rect.right, cut.rect.bottom)
                            drawRect(dim, Offset(offX, offY), Size(imgW, a.y - offY))
                            drawRect(dim, Offset(offX, b.y), Size(imgW, offY + imgH - b.y))
                            drawRect(dim, Offset(offX, a.y), Size(a.x - offX, b.y - a.y))
                            drawRect(dim, Offset(b.x, a.y), Size(offX + imgW - b.x, b.y - a.y))
                            drawRect(accent, a, Size(b.x - a.x, b.y - a.y), style = Stroke(4f))
                            listOf(a, Offset(b.x, a.y), Offset(a.x, b.y), b).forEach { drawCircle(Color.White, 14f, it) }
                        }
                    }
                }
            }
        }
    }
}
