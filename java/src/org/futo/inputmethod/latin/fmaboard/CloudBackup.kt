package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.SettingsExporter
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import org.futo.inputmethod.latin.uix.settings.Ios
import org.futo.inputmethod.latin.uix.settings.IosFootnote
import org.futo.inputmethod.latin.uix.settings.IosGroup
import org.futo.inputmethod.latin.uix.settings.IosIconTile
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup of the whole keyboard to a file the person picks once with the Android file picker:
 * a file in Google Drive, OneDrive, Dropbox or the phone. From then on it is overwritten on
 * its own every day or week. No accounts or keys in the app: the cloud app does the upload.
 *
 * The file is the keyboard's usual settings backup (settings, personal dictionary, learned
 * words, clipboard, themes) plus FMAboard's own data. AI keys only go in if the person sets a
 * password, and then encrypted with it.
 */
object CloudBackup {
    private const val TAG = "FMAboardBackup"
    private const val JOB_ID = 0x464D4142 // "FMAB"

    private const val PREF_URI = "fmaboard_backup_uri"
    private const val PREF_DAYS = "fmaboard_backup_days"
    private const val PREF_LAST = "fmaboard_backup_last"
    private const val PREF_LAST_SIZE = "fmaboard_backup_last_size"
    private const val PREF_ERROR = "fmaboard_backup_error"
    private const val PREF_PASSWORD = "fmaboard_backup_password"
    private const val PREF_PENDING_KEYS = "fmaboard_backup_pending_keys"

    /** Kept on this phone only: never written to a backup, never replaced by a restore. */
    private val LOCAL_ONLY = setOf(
        AiRewrite.PREF_API_KEY, AiRewrite.PREF_GEMINI_KEY,
        PREF_URI, PREF_DAYS, PREF_LAST, PREF_LAST_SIZE, PREF_ERROR, PREF_PASSWORD, PREF_PENDING_KEYS
    )

    fun isLocalOnly(key: String) = key in LOCAL_ONLY

    private fun prefs(context: Context) = PreferenceUtils.getDefaultSharedPreferences(context)

    // --- What FMAboard adds to the backup file --------------------------------------------------

    private const val EXTRA = "fmaboard/"
    private const val EXTRA_FILES = "fmaboard/files/"
    private const val EXTRA_LOG = "fmaboard/log/"
    private const val EXTRA_KEYS = "fmaboard/claves.enc"

    fun isExtra(name: String) = name.startsWith(EXTRA)

    /** Shortcuts, personal autocorrect, typing log and (if there is a password) the AI keys. */
    fun writeExtras(context: Context, zip: ZipOutputStream) {
        context.filesDir.listFiles()?.filter { it.isFile && it.name.startsWith("fmaboard_") && it.name.endsWith(".json") }
            ?.forEach { f ->
                zip.putNextEntry(ZipEntry(EXTRA_FILES + f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

        TypingLogStore.flushBlocking()
        val logDir = TypingLogStore.logDir(context)
        logDir.walk().filter { it.isFile }.forEach { f ->
            zip.putNextEntry(ZipEntry(EXTRA_LOG + f.toRelativeString(logDir).replace('\\', '/')))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }

        val password = prefs(context).getString(PREF_PASSWORD, null)
        if (!password.isNullOrEmpty()) {
            val keys = JSONObject()
                .put("gemini", AiRewrite.geminiKey(context))
                .put("openrouter", AiRewrite.apiKey(context))
            if (keys.optString("gemini").isNotEmpty() || keys.optString("openrouter").isNotEmpty()) {
                zip.putNextEntry(ZipEntry(EXTRA_KEYS))
                zip.write(encrypt(keys.toString().toByteArray(), password))
                zip.closeEntry()
            }
        }
    }

    fun readExtra(context: Context, name: String, input: InputStream) {
        when {
            name.startsWith(EXTRA_FILES) -> {
                val fileName = name.removePrefix(EXTRA_FILES)
                if (fileName.contains('/') || !fileName.startsWith("fmaboard_") || !fileName.endsWith(".json")) return
                File(context.filesDir, fileName).outputStream().use { input.copyTo(it) }
            }
            name.startsWith(EXTRA_LOG) -> {
                val logDir = TypingLogStore.logDir(context)
                val target = File(logDir, name.removePrefix(EXTRA_LOG))
                // Stay inside the log folder, and keep what this phone has already written
                if (!target.canonicalPath.startsWith(logDir.canonicalPath + File.separator) || target.exists()) return
                target.parentFile?.mkdirs()
                target.outputStream().use { input.copyTo(it) }
            }
            name == EXTRA_KEYS -> {
                // Needs the password: asked for in the backup screen
                prefs(context).edit { putString(PREF_PENDING_KEYS, Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)) }
            }
        }
    }

    fun afterRestore(context: Context) {
        TextShortcuts.reload(context)
        PersonalAutocorrect.reload(context)
        Log.i(TAG, "restored, keys waiting for password: ${hasPendingKeys(context)}")
    }

    // --- AI keys, encrypted with the person's password -------------------------------------------

    private const val ITERATIONS = 120_000

    private fun secretKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /** salt (16) + iv (12) + AES-GCM ciphertext. */
    private fun encrypt(data: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(password, salt), GCMParameterSpec(128, iv))
        return salt + iv + cipher.doFinal(data)
    }

    private fun decrypt(blob: ByteArray, password: String): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(password, blob.copyOfRange(0, 16)),
            GCMParameterSpec(128, blob.copyOfRange(16, 28)))
        cipher.doFinal(blob.copyOfRange(28, blob.size))
    } catch (e: Exception) { null }

    fun hasPendingKeys(context: Context) = prefs(context).contains(PREF_PENDING_KEYS)

    /** True if the password opened the keys from a restored backup. */
    fun unlockPendingKeys(context: Context, password: String): Boolean {
        val blob = prefs(context).getString(PREF_PENDING_KEYS, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val json = decrypt(blob, password)?.let { JSONObject(String(it)) } ?: return false
        prefs(context).edit {
            json.optString("gemini").takeIf { it.isNotEmpty() }?.let { putString(AiRewrite.PREF_GEMINI_KEY, it) }
            json.optString("openrouter").takeIf { it.isNotEmpty() }?.let { putString(AiRewrite.PREF_API_KEY, it) }
            putString(PREF_PASSWORD, password)
            remove(PREF_PENDING_KEYS)
        }
        return true
    }

    fun forgetPendingKeys(context: Context) = prefs(context).edit { remove(PREF_PENDING_KEYS) }

    fun hasPassword(context: Context) = !prefs(context).getString(PREF_PASSWORD, null).isNullOrEmpty()

    fun setPassword(context: Context, password: String?) = prefs(context).edit {
        if (password.isNullOrEmpty()) remove(PREF_PASSWORD) else putString(PREF_PASSWORD, password)
    }

    // --- Where and how often --------------------------------------------------------------------

    fun target(context: Context): Uri? = prefs(context).getString(PREF_URI, null)?.let(Uri::parse)

    fun setTarget(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        target(context)?.takeIf { it != uri }?.let {
            try { context.contentResolver.releasePersistableUriPermission(it, flags) } catch (_: Exception) {}
        }
        context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs(context).edit { putString(PREF_URI, uri.toString()); remove(PREF_ERROR) }
        if (days(context) == 0) setDays(context, 1) else schedule(context)
    }

    fun forget(context: Context) {
        target(context)?.let {
            try {
                context.contentResolver.releasePersistableUriPermission(it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
        }
        prefs(context).edit { remove(PREF_URI); remove(PREF_LAST); remove(PREF_LAST_SIZE); remove(PREF_ERROR) }
        schedule(context)
    }

    /** Name of the chosen file, as the cloud app shows it. */
    fun targetName(context: Context): String? {
        val uri = target(context) ?: return null
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null } ?: uri.lastPathSegment
    }

    /** 0 = off, 1 = every day, 7 = every week. */
    fun days(context: Context) = prefs(context).getInt(PREF_DAYS, 0)

    fun setDays(context: Context, days: Int) {
        prefs(context).edit { putInt(PREF_DAYS, days) }
        schedule(context)
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val days = days(context)
        if (days <= 0 || target(context) == null) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, CloudBackupJob::class.java))
            .setPeriodic(days * DateUtils.DAY_IN_MILLIS)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true) // survives restarting the phone
            .build()
        val result = scheduler.schedule(job)
        Log.i(TAG, "scheduled every $days day(s): ${result == JobScheduler.RESULT_SUCCESS}")
    }

    class Status(val last: Long, val size: Long, val error: String?)

    fun status(context: Context) = prefs(context).let {
        Status(it.getLong(PREF_LAST, 0), it.getLong(PREF_LAST_SIZE, 0), it.getString(PREF_ERROR, null))
    }

    /** Writes the backup to the chosen file. Returns an error to show, or null if it went well. */
    suspend fun runNow(context: Context): String? = withContext(Dispatchers.IO) {
        val uri = target(context) ?: return@withContext "Primero elige dónde guardarla"
        if (!context.isDirectBootUnlocked) return@withContext "El móvil está bloqueado"
        val temp = File(context.cacheDir, "fmaboard-backup.tmp")
        val error = try {
            // Made aside first, so a failure never leaves a broken file in the cloud
            temp.outputStream().use { SettingsExporter.exportSettings(context, it, false) }
            val out = try { context.contentResolver.openOutputStream(uri, "wt") }
                      catch (e: Exception) { context.contentResolver.openOutputStream(uri, "w") }
            if (out == null) "No se puede escribir en el archivo elegido" else {
                out.use { o -> temp.inputStream().use { it.copyTo(o) } }
                prefs(context).edit {
                    putLong(PREF_LAST, System.currentTimeMillis())
                    putLong(PREF_LAST_SIZE, temp.length())
                    remove(PREF_ERROR)
                }
                Log.i(TAG, "backup written, ${temp.length()} bytes")
                null
            }
        } catch (e: SecurityException) {
            "Ya no hay permiso para ese archivo: vuelve a elegirlo"
        } catch (e: java.io.FileNotFoundException) {
            "El archivo ya no existe: vuelve a elegirlo"
        } catch (e: Exception) {
            Log.w(TAG, "backup failed: ${e.javaClass.simpleName}")
            "No se pudo guardar (${e.javaClass.simpleName})"
        } finally {
            temp.delete()
        }
        if (error != null) prefs(context).edit { putString(PREF_ERROR, error) }
        error
    }
}

class CloudBackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val error = CloudBackup.runNow(applicationContext)
            jobFinished(params, error != null)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

// --- Settings screen ------------------------------------------------------------------------

@Composable
private fun PasswordDialog(title: String, text: String, confirm: String, check: (String) -> String?, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                OutlinedTextField(
                    value = value, onValueChange = { value = it; error = null },
                    label = { Text("Contraseña") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null, modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { error = check(value) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun describe(context: Context, s: CloudBackup.Status): String = when {
    s.error != null -> s.error
    s.last == 0L -> "Todavía no se ha hecho ninguna copia"
    System.currentTimeMillis() - s.last < DateUtils.MINUTE_IN_MILLIS -> "Última copia: ahora mismo · " + Formatter.formatShortFileSize(context, s.size)
    else -> "Última copia: " + DateUtils.getRelativeTimeSpanString(s.last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
            " · " + Formatter.formatShortFileSize(context, s.size)
}

@Composable
private fun BackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var askUnlock by remember { mutableStateOf(false) }

    val name = remember(refresh) { CloudBackup.targetName(context) }
    val days = remember(refresh) { CloudBackup.days(context) }
    val status = remember(refresh) { CloudBackup.status(context) }
    val hasPassword = remember(refresh) { CloudBackup.hasPassword(context) }
    val pendingKeys = remember(refresh) { CloudBackup.hasPendingKeys(context) }

    fun backupNow() {
        running = true
        scope.launch { CloudBackup.runNow(context); running = false; refresh++ }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            try { CloudBackup.setTarget(context, uri); backupNow() } catch (e: Exception) {
                Log.w("FMAboardBackup", "could not keep access: ${e.javaClass.simpleName}")
            }
            refresh++
        }
    }

    if (pendingKeys) {
        IosGroup {
            SettingItem(
                title = "Recuperar tus claves de IA",
                subtitle = "La copia restaurada trae tus claves cifradas. Escribe la contraseña que pusiste.",
                icon = { IosIconTile(painterResource(R.drawable.fmaboard_sparkle), Ios.Purple) },
                onClick = { askUnlock = true }
            ) {}
        }
    }

    IosGroup {
        SettingItem(
            title = if (name == null) "Elegir dónde guardarla" else name,
            subtitle = if (name == null) "Elige Drive, OneDrive o una carpeta del móvil" else "Toca para cambiar de archivo",
            icon = { IosIconTile(painterResource(R.drawable.fmaboard_cloud), Ios.Blue) },
            onClick = { picker.launch("FMAboard-copia.backup") }
        ) {}
        if (name != null) {
            SettingItem(
                title = "Hacer copia ahora",
                subtitle = describe(context, status),
                icon = { IosIconTile(painterResource(R.drawable.fmaboard_refresh), Ios.Green) },
                onClick = { if (!running) backupNow() }
            ) {
                if (running) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }

    if (name != null) {
        IosGroup {
            listOf(1 to "Cada día", 7 to "Cada semana", 0 to "Solo cuando la haga yo").forEach { (d, label) ->
                SettingItem(title = label, compact = true, onClick = { CloudBackup.setDays(context, d); refresh++ }) {
                    RadioButton(selected = days == d, onClick = null)
                }
            }
        }
        IosFootnote("Se hace sola en segundo plano cuando la batería no está baja. Si eliges un archivo de Drive u OneDrive, su app lo sube a tu nube.")
    }

    IosGroup {
        SettingToggleRaw(
            title = "Incluir mis claves de IA",
            subtitle = if (hasPassword) "Van cifradas con tu contraseña" else "Necesitas poner una contraseña",
            enabled = hasPassword,
            setValue = { on -> if (on) askPassword = true else { CloudBackup.setPassword(context, null); refresh++ } }
        )
    }
    IosFootnote("Sin contraseña, las claves de Gemini y OpenRouter se quedan solo en este móvil. Con ella, van en la copia cifradas, y en el móvil nuevo te la pedirá para recuperarlas. No la olvides: no se puede recuperar.")

    IosGroup {
        SettingItem(
            title = "Restaurar una copia",
            subtitle = "En un móvil nuevo: elige el archivo de tu nube",
            icon = { IosIconTile(painterResource(R.drawable.fmaboard_download), Ios.Orange) },
            onClick = { SettingsExporter.triggerImportSettings(context) }
        ) {}
        if (name != null) {
            SettingItem(title = "Dejar de hacer copias", compact = true,
                onClick = { CloudBackup.forget(context); refresh++ }) {}
        }
    }
    IosFootnote("La copia guarda los ajustes, el diccionario personal, las palabras aprendidas, la autocorrección personal, los atajos, el portapapeles, los temas y el registro de escritura. Los modelos grandes (voz y predicción) no van: se descargan solos de nuevo.")

    if (askPassword) {
        PasswordDialog(
            title = "Contraseña de la copia",
            text = "Con ella se cifran tus claves de IA dentro de la copia. Te la pedirá al restaurar en otro móvil.",
            confirm = "Guardar",
            check = { p ->
                if (p.length < 6) "Al menos 6 caracteres" else {
                    CloudBackup.setPassword(context, p); askPassword = false; refresh++; null
                }
            },
            onDismiss = { askPassword = false }
        )
    }
    if (askUnlock) {
        PasswordDialog(
            title = "Recuperar claves de IA",
            text = "Escribe la contraseña que pusiste en el otro móvil.",
            confirm = "Recuperar",
            check = { p ->
                if (CloudBackup.unlockPendingKeys(context, p)) { askUnlock = false; refresh++; null }
                else "Contraseña incorrecta"
            },
            onDismiss = { askUnlock = false }
        )
    }
}

val CloudBackupMenu = UserSettingsMenu(
    title = R.string.fmaboard_backup_title,
    navPath = "fmaboardBackup", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            Tip("Guarda todo el teclado en un archivo de tu Google Drive, OneDrive o del móvil, y se actualiza solo. En un móvil nuevo instalas FMAboard, tocas «Restaurar una copia», eliges el archivo y queda como estaba.")
        },
        userSettingDecorationOnly { BackupScreen() },
    )
)
