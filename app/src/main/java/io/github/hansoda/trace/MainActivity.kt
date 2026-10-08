package io.github.hansoda.trace

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Xml
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
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
import io.github.hansoda.trace.settings.AppLanguage
import io.github.hansoda.trace.ui.ExportState
import io.github.hansoda.trace.ui.MapPreview
import io.github.hansoda.trace.ui.RouteEditor
import io.github.hansoda.trace.ui.ScreenActions
import io.github.hansoda.trace.ui.ScreenState
import io.github.hansoda.trace.ui.TraceScreen
import io.github.hansoda.trace.ui.TraceTheme
import org.xmlpull.v1.XmlPullParser

class MainActivity : ComponentActivity() {
    private val viewModel: TraceViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receive(intent)
        setContent { TraceTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.importInbox()
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
        val activity by viewModel.activity.collectAsState()
        val removedPoints by viewModel.removedPoints.collectAsState()
        val photos by viewModel.photosOnDays.collectAsState()
        val mediaAdding by viewModel.mediaAdding.collectAsState()
        val finding by viewModel.finding.collectAsState()
        val trimming by viewModel.trimming.collectAsState()

        val openFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.import(uris)
        }
        val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED)) { uris ->
            viewModel.addMedia(uris)
        }
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasGallery()) viewModel.findMedia() else viewModel.galleryDenied()
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
                setDays = viewModel::setDays,
                openTimelineExport = { openTimelineExport() },
                restorePoints = viewModel::restorePoints,
                exportVideo = { withStorage(image = false) },
                saveImage = { withStorage(image = true) },
                cancelExport = viewModel::cancelExport,
                dismissExport = viewModel::dismissExport,
                shareExport = { share() },
                openExport = { open() },
                dismissImportError = viewModel::dismissImportError,
                testKey = viewModel::testKey,
                openKeyPage = { browse("https://carto.com/basemaps/apikey/") },
                openPrivacyPolicy = { browse(PRIVACY_POLICY) },
                clearCache = viewModel::clearCache,
                removeTimeline = viewModel::removeTimeline,
                setLanguage = { language ->
                    if (language != AppLanguage.current(this)) {
                        AppLanguage.choose(this, language)
                        viewModel.languageChanged()
                        recreate()
                    }
                },
                addMedia = {
                    viewModel.dismissMediaNote()
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                removeMedia = viewModel::removeMedia,
                dismissMediaNote = viewModel::dismissMediaNote,
                findMedia = {
                    viewModel.dismissMediaNote()
                    // With only some photos shared, asking again lets more be picked.
                    if (hasWholeGallery()) viewModel.findMedia() else gallery.launch(GALLERY)
                },
                addFound = viewModel::addFound,
                dismissFinding = viewModel::dismissFinding,
                foundThumbnail = viewModel::foundThumbnail,
                trimClip = viewModel::trimClip,
                dismissTrimError = viewModel::dismissTrimError,
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
            activity = activity,
            removedPoints = removedPoints,
            language = AppLanguage.current(this),
            photos = photos,
            mediaAdding = mediaAdding,
            finding = finding,
            trimming = trimming,
        )

        val view = LocalView.current
        val exporting = export is ExportState.Running
        DisposableEffect(exporting) {
            view.keepScreenOn = exporting
            onDispose { view.keepScreenOn = false }
        }

        TraceScreen(
            state,
            actions,
            preview = { modifier, seconds -> plan?.let { MapPreview(it, look, overlay, viewModel.tiles, viewModel.photos, seconds, modifier) } },
            routeEditor = { onClose ->
                val data by viewModel.range.collectAsState()
                val suspects by viewModel.suspects.collectAsState()
                val canUndo by viewModel.canUndo.collectAsState()
                RouteEditor(
                    data, suspects, look, viewModel.tiles, removedPoints, canUndo,
                    onRemove = viewModel::removePoints,
                    onUndo = viewModel::undoRemoval,
                    onRestoreAll = viewModel::restorePoints,
                    onClose = onClose,
                )
            },
        )
    }

    /**
     * Opens Location › Timeline in the phone's settings, where Export Timeline data is. Android
     * has no public link to that page: Google Play services adds it to the Location page, so
     * it's found the way the Settings app finds it. Failing that, Location settings, one tap
     * away from it, with a reminder of where to go.
     */
    private fun openTimelineExport() {
        timelinePage()?.let { if (tryToStart(it)) return }
        for (intent in listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), Intent(Settings.ACTION_SETTINGS))) {
            if (tryToStart(intent)) {
                Toast.makeText(this, R.string.export_where, Toast.LENGTH_LONG).show()
                return
            }
        }
    }

    private fun tryToStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // Not open to other apps on this phone.
        false
    }

    /** The Timeline page that Google Play services adds to Location settings, if it can be found. */
    private fun timelinePage(): Intent? = runCatching {
        val injectors = packageManager.queryIntentServices(Intent(SETTING_INJECTOR).setPackage(GOOGLE_PLAY_SERVICES), PackageManager.GET_META_DATA)
        injectors.firstNotNullOfOrNull { injector ->
            val service = injector.serviceInfo
            runCatching { injectedSetting(service) }.getOrNull()?.takeIf { (title, activity) ->
                TIMELINE_TITLES.any { title.equals(it, ignoreCase = true) } || activity.contains("timeline", ignoreCase = true)
            }?.let { (_, activity) -> Intent().setClassName(service.packageName, activity) }
        }
    }.getOrNull()

    /** The title and page of a setting added to Location settings, as its service describes them. */
    @SuppressLint("ResourceType") // Indices into INJECTED_ATTRIBUTES, not a styleable.
    private fun injectedSetting(service: ServiceInfo): Pair<String, String>? {
        val resources = packageManager.getResourcesForApplication(service.applicationInfo)
        service.loadXmlMetaData(packageManager, SETTING_INJECTOR)?.use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "injected-location-setting") continue
                val values = resources.obtainAttributes(Xml.asAttributeSet(parser), INJECTED_ATTRIBUTES)
                try {
                    val title = values.getString(0) ?: return null
                    val activity = values.getString(1) ?: return null
                    return title to activity
                } finally {
                    values.recycle()
                }
            }
        }
        return null
    }

    /** Android 8 and 9 mount shared storage only for apps that may both read and write it. */
    private fun hasStorage() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || STORAGE.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Any view of the gallery, if only of the photos picked to share. */
    private fun hasGallery() = GALLERY.any(::granted)

    private fun hasWholeGallery() = granted(GALLERY.first())

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

        /** What it takes to look through the gallery; the first is the whole of it. */
        val GALLERY = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        const val GOOGLE_PLAY_SERVICES = "com.google.android.gms"
        const val SETTING_INJECTOR = "android.location.SettingInjectorService"

        /** In the order obtainAttributes needs: ascending. */
        val INJECTED_ATTRIBUTES = intArrayOf(android.R.attr.title, android.R.attr.settingsActivity)

        /** What the page is called in English and Russian. */
        val TIMELINE_TITLES = listOf("Timeline", "Хронология")
        const val MAX_PICKED = 50
        const val PRIVACY_POLICY = "https://github.com/Han-Soda/google-timeline-visualizer/blob/main/trace/PRIVACY.md"
    }
}
