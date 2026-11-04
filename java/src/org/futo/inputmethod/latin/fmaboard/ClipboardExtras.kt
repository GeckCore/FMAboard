package org.futo.inputmethod.latin.fmaboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardSaveScreenshots
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.theme.Typography
import java.io.File
import java.util.Locale

/** Links and phone numbers found in a clip: open them, chat on WhatsApp, call, drop tracking. */
object ClipboardLinks {
    private val URL = Regex("(?i)\\b(?:https?://|www\\.|wa\\.me/)[^\\s<>\"]+")
    private val PHONE = Regex("(?<![\\w+])\\+?\\d[\\d \\-().]{6,18}\\d(?![\\w])")
    private val TRACKING = Regex("(?i)^(utm_[a-z_]+|fbclid|gclid|dclid|msclkid|igsh|igshid|si|mc_cid|mc_eid|ref_src|ref_url|_ga|yclid|twclid|ttclid|s_cid|mkt_tok|spm|share_id|feature)$")

    /** The first link, with https:// put in front when it was written without it. */
    fun findUrl(text: String): String? {
        val raw = URL.find(text)?.value?.trimEnd('.', ',', ')', '!', '?', '\'', ';', ':') ?: return null
        return if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
    }

    /** A phone number (8 to 15 digits), if the clip holds one and it isn't part of a link. */
    fun findPhone(text: String): String? {
        val withoutLinks = URL.replace(text, " ")
        return PHONE.findAll(withoutLinks).map { it.value.trim() }
            .firstOrNull { p -> p.count { it.isDigit() } in 8..15 && !looksLikeDate(p) }
    }

    private fun looksLikeDate(p: String) = Regex("^\\d{1,4}[-.]\\d{1,2}[-.]\\d{1,4}$").matches(p)

    /** wa.me link for [phone]; a number without its country code gets the phone's own. */
    fun whatsappUrl(context: Context, phone: String): String {
        val trimmed = phone.trim()
        val digits = trimmed.filter { it.isDigit() }
        val international = when {
            trimmed.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            else -> {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val iso = (tm?.networkCountryIso?.takeIf { it.isNotBlank() }
                    ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
                    ?: Locale.getDefault().country).uppercase(Locale.ROOT)
                PhoneNumberUtils.formatNumberToE164(digits, iso)?.removePrefix("+") ?: digits
            }
        }
        return "https://wa.me/$international"
    }

    /** [url] without the tracking bits (utm_…, fbclid, si…), or null if it has none. */
    fun cleanUrl(url: String): String? {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        val names = try { uri.queryParameterNames } catch (_: Exception) { return null }
        if (names.none { TRACKING.matches(it) }) return null
        val builder = uri.buildUpon().clearQuery()
        // Keep the rest in its order; the raw query keeps any encoding as it was
        uri.encodedQuery?.split('&')?.forEach { pair ->
            val name = Uri.decode(pair.substringBefore('='))
            if (!TRACKING.matches(name)) builder.appendQueryParameter(name, Uri.decode(pair.substringAfter('=', "")))
        }
        return builder.build().toString().removeSuffix("?")
    }

    /** Opens [uri] in whatever app handles it (browser, WhatsApp, phone…). */
    fun open(context: Context, uri: String, action: String = Intent.ACTION_VIEW) {
        try {
            context.startActivity(Intent(action, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "No hay ninguna app para abrir esto", Toast.LENGTH_SHORT).show()
        }
    }
}

/** An image clip (a screenshot, a copied picture): paste it, take its text or make a sticker. */
@Composable
fun ClipboardImagePanel(file: File, onPaste: () -> Unit, onText: () -> Unit, onSticker: () -> Unit) {
    val bitmap = remember(file) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 900) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        } catch (_: Exception) { null }
    }
    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
            } else {
                Text("No se puede mostrar la imagen", style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onText, modifier = Modifier.weight(1f)) { Text("Sacar texto") }
            OutlinedButton(onClick = onSticker, modifier = Modifier.weight(1f)) { Text("Sticker") }
            Button(onClick = onPaste, modifier = Modifier.weight(1f)) { Text("Pegar") }
        }
    }
}

/** Offer, once, to put new screenshots in the clipboard like Gboard does. */
@Composable
fun ScreenshotOffer(onEnable: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(4.dp)
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Text("¿Quieres que tus capturas de pantalla aparezcan aquí, listas para pegar?",
                style = Typography.SmallMl)
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Ahora no") }
                TextButton(onClick = onEnable) { Text("Activar") }
            }
        }
    }
}

object ScreenshotClips {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Turns screenshots on; the keyboard can't ask for a permission itself, so a screen does. */
    fun enable(context: Context) {
        if (hasPermission(context)) {
            runBlocking { context.setSetting(ClipboardSaveScreenshots, true) }
            Toast.makeText(context, "Listo: tus próximas capturas aparecerán en el portapapeles", Toast.LENGTH_SHORT).show()
        } else {
            context.startActivity(Intent(context, MediaPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Asks for permission to see photos, then turns on screenshots in the clipboard. */
class MediaPermissionActivity : ComponentActivity() {
    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runBlocking { setSetting(ClipboardSaveScreenshots, true) }
            Toast.makeText(this, "Listo: tus próximas capturas aparecerán en el portapapeles", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Sin permiso para ver las fotos no se pueden añadir las capturas", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) request.launch(ScreenshotClips.permission)
    }
}
