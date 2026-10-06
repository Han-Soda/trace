package io.github.hansoda.trace

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.hansoda.trace.ui.ExportState
import io.github.hansoda.trace.ui.MapPreview
import io.github.hansoda.trace.ui.ScreenActions
import io.github.hansoda.trace.ui.ScreenState
import io.github.hansoda.trace.ui.TraceScreen
import io.github.hansoda.trace.ui.TraceTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TraceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receive(intent)
        setContent { TraceTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** Opens a Timeline file shared to Trace or opened with it. */
    private fun receive(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) viewModel.import(listOf(uri))
    }

    @Composable
    private fun App() {
        val settings by viewModel.settings.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val info by viewModel.info.collectAsState()
        val import by viewModel.import.collectAsState()
        val export by viewModel.export.collectAsState()
        val summary by viewModel.summary.collectAsState()
        val plan by viewModel.preview.collectAsState()
        val look by viewModel.look.collectAsState()
        val overlay by viewModel.overlay.collectAsState()
        val keyTest by viewModel.keyTest.collectAsState()
        val cacheSize by viewModel.cacheSize.collectAsState()

        val openFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.import(uris)
        }
        val pendingImage = remember { booleanArrayOf(false) }
        val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasStorage()) if (pendingImage[0]) viewModel.saveImage() else viewModel.exportVideo()
        }
        fun withStorage(image: Boolean) {
            if (!hasStorage()) {
                pendingImage[0] = image
                storage.launch(STORAGE)
            } else if (image) {
                viewModel.saveImage()
            } else {
                viewModel.exportVideo()
            }
        }

        val actions = remember {
            ScreenActions(
                openFiles = { openFiles.launch(arrayOf("*/*")) },
                update = viewModel::update,
                preset = viewModel::preset,
                shiftRange = viewModel::shiftRange,
                setRange = viewModel::setRange,
                exportVideo = { withStorage(image = false) },
                saveImage = { withStorage(image = true) },
                cancelExport = viewModel::cancelExport,
                dismissExport = viewModel::dismissExport,
                shareExport = { share() },
                openExport = { open() },
                dismissImportError = viewModel::dismissImportError,
                testKey = viewModel::testKey,
                openKeyPage = { browse("https://carto.com/basemaps/apikey/") },
                clearCache = viewModel::clearCache,
                removeTimeline = viewModel::removeTimeline,
            )
        }

        val state = ScreenState(
            loading = loading,
            hasTimeline = info != null,
            import = import,
            settings = settings,
            range = summary,
            previewReady = plan != null,
            export = export,
            timelineName = info?.name,
            timelinePoints = info?.points ?: 0,
            cacheSize = cacheSize,
            keyTest = keyTest,
            version = BuildConfig.VERSION_NAME,
        )

        val view = LocalView.current
        val exporting = export is ExportState.Running
        DisposableEffect(exporting) {
            view.keepScreenOn = exporting
            onDispose { view.keepScreenOn = false }
        }

        TraceScreen(state, actions) { modifier, seconds ->
            plan?.let { MapPreview(it, look, overlay, viewModel.tiles, seconds, modifier) }
        }
    }

    /** Android 8 and 9 mount shared storage only for apps that may both read and write it. */
    private fun hasStorage() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || STORAGE.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun share() {
        val uri = viewModel.exportedUri() ?: return
        val image = (viewModel.export.value as? ExportState.Done)?.image == true
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (image) "image/png" else "video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(Intent.createChooser(send, null))
    }

    private fun open() {
        val uri = viewModel.exportedUri() ?: return
        val image = (viewModel.export.value as? ExportState.Done)?.image == true
        launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (image) "image/png" else "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun browse(url: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing installed can handle it; nothing else to do.
        }
    }

    private companion object {
        val STORAGE = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
