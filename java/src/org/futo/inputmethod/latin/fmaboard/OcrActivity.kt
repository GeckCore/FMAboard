package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Text taken from an image, waiting to be typed into the field the keyboard was in when
 * "Texto de imagen" was opened. The keyboard types it the next time that app's field opens.
 */
object OcrCapture {
    const val EXTRA_IMAGE_PATH = "fmaboard_image_path"
    const val EXTRA_TARGET_PACKAGE = "fmaboard_target_package"
    private const val PENDING_MS = 5 * 60 * 1000L
    private const val MAX_SIDE = 2400
    private const val CROP_TARGET_WIDTH = 1600f
    private const val AI_MAX_SIDE = 1600f

    private class Pending(val text: String, val pkg: String?, val time: Long)

    @Volatile private var pending: Pending? = null

    fun launch(context: Context, targetPackage: String?, imagePath: String? = null) {
        context.startActivity(Intent(context, OcrActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_IMAGE_PATH, imagePath))
    }

    fun setPending(text: String, targetPackage: String?) {
        pending = Pending(text, targetPackage, System.currentTimeMillis())
    }

    /** The text to type into a field of [packageName], if there is one waiting. */
    @JvmStatic
    fun consumePending(packageName: String?): String? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - p.time > PENDING_MS) {
            pending = null
            return null
        }
        if (p.pkg != null && p.pkg != packageName) return null
        pending = null
        return p.text
    }

    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) { null } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) }, true)
    }

    private fun region(bitmap: Bitmap, crop: Rect?): Rect? = crop?.let {
        Rect(it).takeIf { r -> r.intersect(0, 0, bitmap.width, bitmap.height) && r.width() > 4 && r.height() > 4 }
    }

    private fun cut(bitmap: Bitmap, region: Rect?, scale: Float): Bitmap {
        val part = if (region == null) bitmap
            else Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
        if (scale == 1f) return part
        return Bitmap.createScaledBitmap(part, (part.width * scale).toInt().coerceAtLeast(1),
            (part.height * scale).toInt().coerceAtLeast(1), true)
    }

    /**
     * On-device recognition: the image never leaves the phone. With [crop], only that part is
     * read, enlarged so small letters come out better. Boxes are always in [bitmap] pixels.
     */
    suspend fun recognize(bitmap: Bitmap, crop: Rect? = null): List<OcrLine> {
        val region = region(bitmap, crop)
        val scale = if (region == null) 1f else (CROP_TARGET_WIDTH / region.width()).coerceIn(1f, 3f)
        val input = withContext(Dispatchers.Default) { cut(bitmap, region, scale) }
        val dx = region?.left ?: 0
        val dy = region?.top ?: 0
        fun toImage(r: Rect) = Rect((r.left / scale).toInt() + dx, (r.top / scale).toInt() + dy,
            (r.right / scale).toInt() + dx, (r.bottom / scale).toInt() + dy)

        return suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(input, 0))
                .addOnSuccessListener { text ->
                    val lines = ArrayList<OcrLine>()
                    text.textBlocks.forEachIndexed { blockIndex, block ->
                        for (l in block.lines) {
                            val box = l.boundingBox ?: continue
                            val words = l.elements.mapNotNull { e -> e.boundingBox?.let { OcrWord(e.text, toImage(it)) } }
                            if (words.isNotEmpty()) lines.add(OcrLine(words, toImage(box), blockIndex))
                        }
                    }
                    recognizer.close()
                    cont.resume(lines)
                }
                .addOnFailureListener {
                    recognizer.close()
                    cont.resumeWithException(it)
                }
        }
    }

    /** The image (or the cropped part) as a JPEG small enough to send to the AI. */
    fun jpegForAi(bitmap: Bitmap, crop: Rect?): ByteArray {
        val region = region(bitmap, crop)
        val w = region?.width() ?: bitmap.width
        val h = region?.height() ?: bitmap.height
        val scale = (AI_MAX_SIDE / maxOf(w, h)).coerceAtMost(2f)
        val out = ByteArrayOutputStream()
        cut(bitmap, region, scale).compress(Bitmap.CompressFormat.JPEG, 88, out)
        return out.toByteArray()
    }
}

/**
 * "Texto de imagen": pick a photo or take one; the text comes out ready to edit, with the
 * usual long-press selection. Drag over the image to read only a part of it. Code keeps its
 * indentation, and "Mejorar con IA" (optional, free models) fixes hard photos.
 * "Copiar" keeps adding pieces to the keyboard's clipboard; "Escribir" closes this screen and
 * types it into the chat you came from.
 */
class OcrActivity : ComponentActivity() {
    private val targetPackage by lazy { intent.getStringExtra(OcrCapture.EXTRA_TARGET_PACKAGE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialPath = intent.getStringExtra(OcrCapture.EXTRA_IMAGE_PATH)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
                    OcrScreen(initialPath?.let { Uri.fromFile(File(it)) })
                }
            }
        }
    }

    private fun close(text: String?) {
        if (text != null) OcrCapture.setPending(text, targetPackage)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    @Composable
    private fun OcrScreen(initialUri: Uri?) {
        var imageUri by remember { mutableStateOf(initialUri) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var crop by remember { mutableStateOf<Rect?>(null) }
        var lines by remember { mutableStateOf<List<OcrLine>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var codeChoice by remember { mutableStateOf<Boolean?>(null) }
        var text by remember { mutableStateOf(TextFieldValue("")) }
        var aiBusy by remember { mutableStateOf(false) }
        var aiMessage by remember { mutableStateOf<String?>(null) }
        val basket = remember { mutableStateListOf<String>() }
        val scope = rememberCoroutineScope()
        val hasKey = remember { AiRewrite.hasApiKey(this) }

        val photoFile = remember { File(cacheDir, "ocr/photo.jpg").also { it.parentFile?.mkdirs() } }
        val photoUri = remember {
            FileProvider.getUriForFile(this, "$packageName.fmaboard.files", photoFile)
        }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) imageUri = uri
        }
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            // A new Uri object so the same file is loaded again after a second photo
            if (ok) imageUri = Uri.fromFile(photoFile).buildUpon().appendQueryParameter("t", "${System.currentTimeMillis()}").build()
        }

        LaunchedEffect(imageUri) {
            val uri = imageUri ?: return@LaunchedEffect
            bitmap = null
            crop = null
            lines = null
            error = null
            codeChoice = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    OcrCapture.loadBitmap(this@OcrActivity, uri.buildUpon().clearQuery().build())
                }
                if (loaded == null) error = "No se pudo abrir la imagen" else bitmap = loaded
            } catch (e: Exception) {
                error = "No se pudo abrir la imagen: ${e.message}"
            }
        }

        // Read again whenever the crop changes
        LaunchedEffect(bitmap, crop) {
            val bmp = bitmap ?: return@LaunchedEffect
            lines = null
            aiMessage = null
            try {
                lines = OcrCapture.recognize(bmp, crop)
            } catch (e: Exception) {
                error = "No se pudo leer el texto: ${e.message}"
            }
        }

        val found = lines
        val isCode = found != null && (codeChoice ?: OcrText.looksLikeCode(found))
        LaunchedEffect(found, isCode) {
            text = TextFieldValue(if (found == null) "" else OcrText.format(found, isCode))
        }

        BackHandler(enabled = imageUri != null && initialUri == null) {
            imageUri = null
            bitmap = null
        }

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { close(null) }) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                }
                Text("Texto de imagen", color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                if (imageUri != null) {
                    OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.padding(end = 8.dp)) { Text("Otra") }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bmp = bitmap
                when {
                    imageUri == null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text("Elige una imagen: el texto sale listo para editar. Arrastra sobre la imagen para leer solo una parte.",
                            color = Color.White, textAlign = TextAlign.Center)
                        Button(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.fillMaxWidth()) { Text("Elegir de la galería") }
                        OutlinedButton(onClick = { takePhoto.launch(photoUri) },
                            modifier = Modifier.fillMaxWidth()) { Text("Hacer una foto") }
                    }
                    error != null -> Text(error!!, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp))
                    bmp == null -> CircularProgressIndicator()
                    else -> ImageWithCrop(bmp, found ?: emptyList(), crop) { crop = it }
                }
            }

            if (bitmap != null && error == null) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = isCode, onClick = { codeChoice = !isCode },
                            enabled = found != null, label = { Text("Código") })
                        AssistChip(
                            onClick = {
                                val bmp = bitmap ?: return@AssistChip
                                val draft = text.text
                                val region = crop
                                val code = isCode
                                aiBusy = true
                                aiMessage = null
                                scope.launch {
                                    try {
                                        val answer = withContext(Dispatchers.IO) {
                                            AiRewrite.completeWithImage(this@OcrActivity, OcrText.AI_PROMPT,
                                                OcrText.aiRequest(draft, code), OcrCapture.jpegForAi(bmp, region))
                                        }
                                        text = TextFieldValue(OcrText.cleanAiAnswer(answer))
                                        aiMessage = "Mejorado con IA. Revísalo antes de enviarlo."
                                    } catch (e: Exception) {
                                        aiMessage = e.message ?: "No se pudo mejorar"
                                    } finally {
                                        aiBusy = false
                                    }
                                }
                            },
                            enabled = found != null && !aiBusy && hasKey,
                            label = { Text(if (aiBusy) "Mejorando…" else "✨ Mejorar con IA") },
                            leadingIcon = if (aiBusy) {
                                { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp) }
                            } else null
                        )
                    }
                    Text(
                        aiMessage ?: if (!hasKey) "Para usar la IA, pon la clave de OpenRouter en Ajustes → FMAboard."
                            else "La IA envía la imagen${if (crop != null) " recortada" else ""} a un modelo gratis de OpenRouter, solo si la pulsas.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )

                    if (found == null) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { Text("No se encontró texto. Prueba a recortar o con la IA.") },
                            textStyle = if (isCode) TextStyle(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = !isCode),
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }

                    val selection = text.selection
                    val chosen = if (selection.collapsed) text.text else text.text.substring(selection.min, selection.max)
                    Text(
                        when {
                            basket.isNotEmpty() -> "Copiado: ${basket.size} ${if (basket.size == 1) "trozo" else "trozos"} (también en el portapapeles del teclado)"
                            selection.collapsed -> "Mantén pulsado para elegir una parte; si no eliges nada, se usa todo."
                            else -> "Se usará lo seleccionado"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                basket.add(chosen)
                                ClipboardNotes.copyToClipboard(this@OcrActivity, chosen)
                                text = text.copy(selection = TextRange(selection.max))
                                Toast.makeText(this@OcrActivity, "Copiado", Toast.LENGTH_SHORT).show()
                            },
                            enabled = chosen.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Copiar") }
                        Button(
                            onClick = {
                                val pieces = if (basket.isEmpty() || !selection.collapsed) basket + chosen else basket.toList()
                                close(pieces.joinToString(if (pieces.any { '\n' in it }) "\n" else " "))
                            },
                            enabled = chosen.isNotBlank() || basket.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Escribir") }
                    }
                }
            }
        }
    }

    /** The image with the text found on it; drag to read only a rectangle, tap to read it all again. */
    @Composable
    private fun ImageWithCrop(bitmap: Bitmap, lines: List<OcrLine>, crop: Rect?, onCrop: (Rect?) -> Unit) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val highlight = MaterialTheme.colorScheme.primary
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()
            val scale = minOf(viewW / bitmap.width, viewH / bitmap.height)
            val offX = (viewW - bitmap.width * scale) / 2
            val offY = (viewH - bitmap.height * scale) / 2

            fun toView(r: Rect) = Pair(Offset(offX + r.left * scale, offY + r.top * scale),
                Size(r.width() * scale, r.height() * scale))

            fun toImage(a: Offset, b: Offset) = Rect(
                ((minOf(a.x, b.x) - offX) / scale).toInt(), ((minOf(a.y, b.y) - offY) / scale).toInt(),
                ((maxOf(a.x, b.x) - offX) / scale).toInt(), ((maxOf(a.y, b.y) - offY) / scale).toInt())

            // Gestures keep running across recompositions, so they read the latest values
            val currentOnCrop by rememberUpdatedState(onCrop)
            val currentToImage by rememberUpdatedState(::toImage)
            val currentScale by rememberUpdatedState(scale)
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragEnd by remember { mutableStateOf<Offset?>(null) }

            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { currentOnCrop(null) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p -> dragStart = p; dragEnd = p },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragEnd = {
                            val a = dragStart
                            val b = dragEnd
                            dragStart = null
                            dragEnd = null
                            if (a != null && b != null) {
                                val r = currentToImage(a, b)
                                if (r.width() * currentScale > 40 && r.height() * currentScale > 20) currentOnCrop(r)
                            }
                        },
                        onDragCancel = { dragStart = null; dragEnd = null }
                    )
                }
            ) {
                val a = dragStart
                val b = dragEnd
                val area: Pair<Offset, Size>? = when {
                    a != null && b != null -> Pair(Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                        Size(abs(a.x - b.x), abs(a.y - b.y)))
                    crop != null -> toView(crop)
                    else -> null
                }
                if (area == null) {
                    // Faint marks over the text that was found
                    lines.forEach { l ->
                        val (o, s) = toView(l.box)
                        drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(o.x - 3, o.y - 3),
                            Size(s.width + 6, s.height + 6), CornerRadius(6f))
                    }
                } else {
                    val (o, s) = area
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, Offset(0f, 0f), Size(size.width, o.y))
                    drawRect(dim, Offset(0f, o.y + s.height), Size(size.width, size.height - o.y - s.height))
                    drawRect(dim, Offset(0f, o.y), Size(o.x, s.height))
                    drawRect(dim, Offset(o.x + s.width, o.y), Size(size.width - o.x - s.width, s.height))
                    drawRoundRect(highlight, o, s, CornerRadius(8f), style = Stroke(3f))
                }
            }
            Text(
                if (crop == null) "Arrastra para leer solo una parte" else "Toca la imagen para leerla entera",
                color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
