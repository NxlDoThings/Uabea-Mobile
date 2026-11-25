package com.example.uabeamobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

data class TextureItem(
    val pathId: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val format: String,
    val streamPath: String,
    val streamMissing: Boolean,
)

data class UiState(
    val busyMessage: String? = null,
    val fileName: String? = null,
    val textures: List<TextureItem> = emptyList(),
    val query: String = "",
    val selected: TextureItem? = null,
    val preview: Bitmap? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val modified: Boolean = false, // at least one texture was replaced
    val unsaved: Boolean = false,  // replaced textures not yet written out with "Save Modified File"
    val keepSize: Boolean = true,
    val envWarning: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val confirmDiscard: Boolean = false,
)

// ---------------------------------------------------------------------------
// ViewModel: owns the working copy and talks to Python
// ---------------------------------------------------------------------------

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var ui by mutableStateOf(UiState())
        private set

    private val pyMutex = Mutex()
    private val workDir = File(app.cacheDir, "work")
    private var originalFile: File? = null
    private var currentFile: File? = null
    private var originalName: String? = null
    private var generation = 0

    init {
        launchBusy("Starting Python…") { checkEnvironment() }
    }

    // ----- simple UI actions -------------------------------------------------

    fun setQuery(value: String) {
        ui = ui.copy(query = value)
    }

    fun setKeepSize(value: Boolean) {
        ui = ui.copy(keepSize = value)
    }

    fun dismissNotice() {
        ui = ui.copy(notice = null)
    }

    fun dismissError() {
        ui = ui.copy(error = null)
    }

    fun dismissEnvWarning() {
        ui = ui.copy(envWarning = null)
    }

    fun askDiscard() {
        ui = ui.copy(confirmDiscard = true)
    }

    fun dismissDiscard() {
        ui = ui.copy(confirmDiscard = false)
    }

    fun closePreview() {
        ui = ui.copy(selected = null, preview = null, previewError = null, previewLoading = false, notice = null)
    }

    fun suggestedSaveName(): String {
        val name = originalName ?: "modified.assets"
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + "_modified" + name.substring(dot) else name + "_modified"
    }

    fun suggestedPngName(): String {
        val base = ui.selected?.name?.takeIf { it.isNotBlank() } ?: "texture"
        return safeName(base) + ".png"
    }

    // ----- loading -----------------------------------------------------------

    /** Copies the picked .assets/bundle file into the app cache and lists its textures. */
    fun loadFile(uri: Uri) {
        launchBusy("Copying file…") {
            resetSession()
            python { it.callAttr("release_memory") }
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            val file = withContext(Dispatchers.IO) {
                workDir.deleteRecursively()
                workDir.mkdirs()
                File(workDir, safeName(name)).also { copyUriToFile(uri, it) }
            }
            originalFile = file
            currentFile = file
            originalName = name
            generation = 0

            ui = ui.copy(busyMessage = "Reading textures… (large files can take a while)")
            val json = python { it.callAttr("list_textures", file.absolutePath).toString() }
            val textures = parseTextures(json)
            ui = ui.copy(
                fileName = name,
                textures = textures,
                query = "",
                modified = false,
                unsaved = false,
                notice = if (textures.isEmpty()) "No Texture2D objects found in this file." else null,
            )
        }
    }

    /** Copies a .resS (or any sibling resource file) next to the working copy. */
    fun attachResource(uri: Uri) {
        val current = currentFile ?: return
        launchBusy("Copying attached file…") {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            withContext(Dispatchers.IO) { copyUriToFile(uri, File(workDir, safeName(name))) }
            ui = ui.copy(busyMessage = "Re-reading textures…")
            val json = python { it.callAttr("list_textures", current.absolutePath).toString() }
            ui = ui.copy(textures = parseTextures(json), notice = "Attached $name")
        }
    }

    // ----- preview / export / replace / save ---------------------------------

    fun selectTexture(item: TextureItem) {
        ui = ui.copy(selected = item, preview = null, previewError = null, previewLoading = true, notice = null)
        viewModelScope.launch { renderPreview(item) }
    }

    fun exportPng(uri: Uri) {
        val item = ui.selected ?: return
        val file = currentFile ?: return
        launchBusy("Exporting PNG…") {
            val png = File(workDir, "export_${item.pathId}.png")
            python { it.callAttr("export_texture", file.absolutePath, item.pathId, png.absolutePath) }
            withContext(Dispatchers.IO) {
                copyFileToUri(png, uri)
                png.delete()
            }
            ui = ui.copy(notice = "PNG exported.")
        }
    }

    fun replaceSelected(uri: Uri) {
        val item = ui.selected ?: return
        val input = currentFile ?: return
        val keepSize = ui.keepSize
        launchBusy("Replacing texture… (encoding can take a while)") {
            val png = File(workDir, "replacement.png")
            withContext(Dispatchers.IO) { copyUriToFile(uri, png) }

            generation += 1
            val out = File(workDir, editedName(generation))
            val result = try {
                JSONObject(
                    python {
                        it.callAttr(
                            "replace_texture",
                            input.absolutePath,
                            item.pathId,
                            png.absolutePath,
                            out.absolutePath,
                            keepSize,
                        ).toString()
                    },
                )
            } catch (e: Throwable) {
                out.delete()
                throw e
            }

            currentFile = out
            if (input != originalFile) input.delete() // drop the previous edit generation
            png.delete()

            val updated = item.copy(
                width = result.optInt("width", item.width),
                height = result.optInt("height", item.height),
                format = result.optString("m_TextureFormat", item.format),
                streamPath = "",
                streamMissing = false,
            )
            val note = result.optString("note", "")
            ui = ui.copy(
                textures = ui.textures.map { if (it.pathId == item.pathId) updated else it },
                selected = updated,
                preview = null,
                previewError = null,
                previewLoading = true,
                modified = true,
                unsaved = true,
                notice = if (note.isNotEmpty()) note else "Texture replaced. Use \"Save Modified File\" to write the result.",
            )
            renderPreview(updated)
        }
    }

    fun saveModified(uri: Uri) {
        val file = currentFile ?: return
        launchBusy("Writing file…") {
            withContext(Dispatchers.IO) { copyFileToUri(file, uri) }
            ui = ui.copy(unsaved = false, notice = "Saved. Copy it over the original to use it in the game.")
        }
    }

    // ----- internals ---------------------------------------------------------

    private suspend fun renderPreview(item: TextureItem) {
        val file = currentFile
        if (file == null) {
            return
        }
        val png = File(workDir, "preview_${item.pathId}.png")
        try {
            python { it.callAttr("export_texture", file.absolutePath, item.pathId, png.absolutePath) }
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    decodePreview(png)
                } finally {
                    png.delete()
                }
            }
            if (ui.selected?.pathId == item.pathId) {
                ui = ui.copy(
                    preview = bitmap,
                    previewLoading = false,
                    previewError = if (bitmap == null) "The exported image could not be displayed." else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (ui.selected?.pathId == item.pathId) {
                ui = ui.copy(preview = null, previewLoading = false, previewError = e.friendly())
            }
        }
    }

    private suspend fun checkEnvironment() {
        val json = JSONObject(python { it.callAttr("environment_info").toString() })
        val error = if (json.isNull("error")) null else json.getString("error")
        val missing = json.optJSONObject("native_missing")
        val names = missing?.keys()?.asSequence()?.toList().orEmpty()
        val warning = when {
            error != null -> "UnityPy is not working: $error"
            names.isNotEmpty() ->
                "Native decoders not installed: ${names.joinToString()}. Textures in formats that need them " +
                    "(ETC, ASTC, ...) can't be previewed, and replacements for those are stored as " +
                    "uncompressed RGBA32. See app/wheels/README.txt."
            else -> null
        }
        ui = ui.copy(envWarning = warning)
    }

    private fun resetSession() {
        originalFile = null
        currentFile = null
        originalName = null
        generation = 0
        ui = ui.copy(
            fileName = null,
            textures = emptyList(),
            query = "",
            selected = null,
            preview = null,
            previewLoading = false,
            previewError = null,
            modified = false,
            unsaved = false,
        )
    }

    /** Runs [block] with the spinner overlay; every Python call inside goes through [python] (Dispatchers.IO). */
    private fun launchBusy(message: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            ui = ui.copy(busyMessage = message, notice = null)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ui = ui.copy(error = e.friendly())
            } finally {
                ui = ui.copy(busyMessage = null)
            }
        }
    }

    private suspend fun <T> python(block: (PyObject) -> T): T = withContext(Dispatchers.IO) {
        pyMutex.withLock {
            block(Python.getInstance().getModule("asset_handler"))
        }
    }

    private fun parseTextures(json: String): List<TextureItem> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            TextureItem(
                pathId = o.getLong("path_id"),
                name = o.optString("name", ""),
                width = o.optInt("width", 0),
                height = o.optInt("height", 0),
                format = o.optString("m_TextureFormat", "?"),
                streamPath = o.optString("stream_path", ""),
                streamMissing = o.optBoolean("stream_missing", false),
            )
        }
    }

    private fun editedName(gen: Int): String {
        val name = originalFile?.name ?: "edited.assets"
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + "__e" + gen + name.substring(dot) else name + "__e" + gen
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    cursor.getString(index)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file.assets"
    }

    private fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun copyUriToFile(uri: Uri, dest: File) {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file.")
        input.use { source ->
            dest.outputStream().use { target -> source.copyTo(target, 256 * 1024) }
        }
    }

    private fun copyFileToUri(src: File, uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write to the chosen location.")
        output.use { target ->
            src.inputStream().use { source -> source.copyTo(target, 256 * 1024) }
        }
    }

    private fun decodePreview(file: File, maxSide: Int = 1600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun Throwable.friendly(): String {
        val text = (message ?: toString()).trim()
        return if (text.length > 2000) text.take(2000) + "…" else text
    }
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val ui = vm.ui
    val snackbar = remember { SnackbarHostState() }
    val busy = ui.busyMessage != null

    val openAssets = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadFile(uri)
    }
    val openResource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachResource(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.replaceSelected(uri)
    }
    val createPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.exportPng(uri)
    }
    val createAs
