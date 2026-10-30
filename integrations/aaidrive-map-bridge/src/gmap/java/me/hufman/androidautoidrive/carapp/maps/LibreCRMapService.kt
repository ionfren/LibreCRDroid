package me.hufman.androidautoidrive.carapp.maps

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.graphics.PixelFormat
import android.os.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.MarkerOptions
import androidx.lifecycle.Observer
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.cds.CDSDataProvider
import me.hufman.androidautoidrive.maps.CdsLocationProvider
import me.hufman.androidautoidrive.maps.GMapsPlaceSearch
import me.hufman.androidautoidrive.maps.MapResult
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Explicit, UID-checked map-only bridge. No glucose or API credentials cross this boundary. */
class LibreCRMapService : Service() {
    companion object {
        const val CLIENT_PACKAGE = "re.abbot.librecr.app"
        const val VERSION = 1
        const val REQUEST_FRAME = 1
        const val FRAME = 2
        const val SEARCH = 3
        const val RESULTS = 4
        const val NAVIGATE = 5
        const val NAVIGATION = 6
        const val STOP_NAVIGATION = 7
        const val RECALCULATE = 8
        const val CONTROLS_VERSION = 1
        const val READY = 1
        const val LOADING = 2
        const val DISCONNECTED = 3
        const val DISABLED = 4
        const val ERROR = 5
        const val MAX_FRAME_BYTES = 512 * 1024
        private const val IDLE_TIMEOUT_MS = 5000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var searchJob: Job? = null
    private var navigationJob: Job? = null
    private var placeSearch: GMapsPlaceSearch? = null
    private var suggestions = emptyList<MapResult>()
    private var destinationName: String? = null
    private var namedDestination: LatLng? = null
    private val captureThread = HandlerThread("LibreCR-MapCapture")
    private lateinit var captureHandler: Handler
    private lateinit var messenger: Messenger
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projection: GMapsProjection? = null
    private var location: CdsLocationProvider? = null
    private var cdsData: CDSDataProvider? = null
    private var settings: MutableAppSettingsReceiver? = null
    private var size: Pair<Int, Int>? = null
    @Volatile private var generation = 0
    private var lastRequestMs = 0L
    @Volatile private var lastCaptureMs = 0L
    private var jpeg: ByteArray? = null
    private var sequence = 0L
    private var mapLoaded = false
    private var retryAt = 0L
    private val routeObserver = Observer<LibreCRMapRoute.Snapshot> { drawRoute(it) }
    private val idle = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastRequestMs >= IDLE_TIMEOUT_MS) releaseMap()
            else main.postDelayed(this, IDLE_TIMEOUT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
        messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (isTrustedUid(message.sendingUid)) {
                if (message.what == REQUEST_FRAME) handleRequest(message)
                else handleCommand(message)
            }
            true
        })
    }

    internal fun isTrustedUid(uid: Int): Boolean =
        packageManager.getPackagesForUid(uid)?.contains(CLIENT_PACKAGE) == true

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handleRequest(message: Message) {
        val reply = message.replyTo ?: return
        val request = message.data
        val requestId = request.getLong("requestId", -1L)
        if (request.getInt("version") != VERSION) { respond(reply, ERROR, requestId); return }
        if (!CarInformation.isConnected) { releaseMap(); respond(reply, DISCONNECTED, requestId); return }
        if (!AppSettingsViewer()[AppSettings.KEYS.ENABLED_MAPS].toBoolean()) {
            releaseMap(); respond(reply, DISABLED, requestId); return
        }
        val width = request.getInt("width")
        val height = request.getInt("height")
        if (width !in 160..960 || height !in 160..540) { respond(reply, ERROR, requestId); return }
        if (SystemClock.elapsedRealtime() < retryAt) { respond(reply, ERROR, requestId); return }
        lastRequestMs = SystemClock.elapsedRealtime()
        try {
            if (size != (width to height)) createMap(width, height)
            main.removeCallbacks(idle)
            main.postDelayed(idle, IDLE_TIMEOUT_MS)
            respond(reply, if (mapLoaded && jpeg != null) READY else LOADING, requestId)
        } catch (_: Exception) {
            releaseMap()
            retryAt = SystemClock.elapsedRealtime() + 5000L
            respond(reply, ERROR, requestId)
        }
    }

    private fun respond(reply: Messenger, status: Int, requestId: Long) {
        val response = Message.obtain(null, FRAME).apply {
            data = Bundle().apply {
                putInt("version", VERSION)
                putInt("controlsVersion", CONTROLS_VERSION)
                putLong("requestId", requestId)
                putInt("status", status)
                putLong("sequence", sequence)
                putBoolean("widescreen", AppSettingsViewer()[AppSettings.KEYS.MAP_WIDESCREEN].toBoolean())
                putNavigation(this)
                if (status == READY) putByteArray("jpeg", jpeg)
            }
        }
        try { reply.send(response) } catch (_: RemoteException) { releaseMap() }
    }

    private fun putNavigation(data: Bundle) {
        val route = LibreCRMapRoute.current.value
        data.putString("navigationStatus", route?.status ?: "IDLE")
        data.putLong("distanceMeters", route?.distanceMeters ?: 0L)
        data.putLong("durationSeconds", route?.durationSeconds ?: 0L)
        data.putString("destinationName", destinationName?.takeIf { route?.destination == namedDestination })
    }

    private fun commandReply(reply: Messenger, type: Int, requestId: Long, status: String,
                             results: List<MapResult> = emptyList()) {
        try {
            reply.send(Message.obtain(null, type).apply {
                data = Bundle().apply {
                    putInt("version", VERSION)
                    putLong("requestId", requestId)
                    putString("commandStatus", status)
                    putNavigation(this)
                    if (type == RESULTS) putParcelableArrayList("results", ArrayList(results.map { result ->
                        Bundle().apply {
                            putString("id", result.id)
                            putString("name", result.name.take(200))
                            putString("address", result.address?.take(300))
                        }
                    }))
                }
            })
        } catch (_: RemoteException) { /* The client may leave the search screen. */ }
    }

    private fun handleCommand(message: Message) {
        if (message.what !in listOf(SEARCH, NAVIGATE, STOP_NAVIGATION, RECALCULATE)) return
        val reply = message.replyTo ?: return
        val id = message.data.getLong("requestId", -1L)
        val type = if (message.what == SEARCH) RESULTS else NAVIGATION
        if (message.data.getInt("version") != VERSION || !CarInformation.isConnected ||
            !AppSettingsViewer()[AppSettings.KEYS.ENABLED_MAPS].toBoolean() || location == null) {
            commandReply(reply, type, id, "UNAVAILABLE")
            return
        }
        val interaction = MapInteractionControllerIntent(this)
        when (message.what) {
            SEARCH -> {
                searchJob?.cancel()
                navigationJob?.cancel()
                suggestions = emptyList()
                val query = message.data.getString("query")?.trim().orEmpty()
                if (query.length < 2) { commandReply(reply, type, id, "OK"); return }
                if (query.length > 200) { commandReply(reply, type, id, "ERROR"); return }
                if (location?.currentLocation == null) { commandReply(reply, type, id, "NO_LOCATION"); return }
                searchJob = commands.launch {
                    try {
                        val search = placeSearch ?: GMapsPlaceSearch.getInstance(this@LibreCRMapService, location!!, reportErrors = true)
                            .also { placeSearch = it }
                        val results = withTimeout(10_000L) { search.searchLocationsAsync(query).await() }.take(5)
                        ensureActive()
                        suggestions = results
                        commandReply(reply, type, id, "OK", results)
                    } catch (error: CancellationException) {
                        if (error is TimeoutCancellationException) commandReply(reply, type, id, "ERROR") else throw error
                    } catch (_: Exception) { commandReply(reply, type, id, "ERROR") }
                }
            }
            NAVIGATE -> {
                navigationJob?.cancel()
                val selected = suggestions.firstOrNull { it.id == message.data.getString("placeId") }
                if (selected == null) { commandReply(reply, type, id, "ERROR"); return }
                navigationJob = commands.launch {
                    try {
                        val result = withTimeout(10_000L) {
                            if (selected.location != null) selected else placeSearch?.resultInformationAsync(selected.id)?.await()
                        }
                        ensureActive()
                        val destination = result?.location
                        if (destination == null) { commandReply(reply, type, id, "ERROR"); return@launch }
                        destinationName = result.name
                        namedDestination = LatLng(destination.latitude, destination.longitude)
                        interaction.navigateTo(destination)
                        commandReply(reply, type, id, "OK")
                    } catch (error: CancellationException) {
                        if (error is TimeoutCancellationException) commandReply(reply, type, id, "ERROR") else throw error
                    } catch (_: Exception) { commandReply(reply, type, id, "ERROR") }
                }
            }
            STOP_NAVIGATION -> {
                navigationJob?.cancel()
                interaction.stopNavigation()
                commandReply(reply, type, id, "OK")
            }
            RECALCULATE -> {
                navigationJob?.cancel()
                interaction.recalcNavigation()
                commandReply(reply, type, id, "OK")
            }
        }
    }

    private fun createMap(width: Int, height: Int) {
        releaseMap()
        val token = generation
        size = width to height
        mapLoaded = false
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source -> capture(source, token) }, captureHandler)
        display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).createVirtualDisplay(
            "LibreCR-GoogleMaps", width, height, 250, imageReader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        val data = CDSDataProvider().also { it.setConnection(CarInformation.cdsData.asConnection(it)) }
        cdsData = data
        val location = CdsLocationProvider(data, CarCapabilitiesSummarized(CarInformation()).isId4)
        this.location = location
        val source = GMapsLocationSource()
        val settings = MutableAppSettingsReceiver(applicationContext, main)
        this.settings = settings
        // The addon already reserves the BMW sidebar and requests the map card's exact size.
        val projection = GMapsProjection(applicationContext, display!!.display, settings, source, insetForSidebar = false)
        this.projection = projection
        location.callback = { current ->
            main.post {
                if (generation == token) {
                    source.onLocationUpdate(current)
                    projection.map?.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                        .target(LatLng(current.latitude, current.longitude)).zoom(15f)
                        .bearing(if (current.hasBearing()) current.bearing else 0f).tilt(35f).build()))
                }
            }
        }
        projection.mapListener = Runnable {
            projection.map?.setOnMapLoadedCallback { if (generation == token) mapLoaded = true }
            location.start()
            drawRoute(LibreCRMapRoute.current.value)
        }
        LibreCRMapRoute.current.observeForever(routeObserver)
        projection.show()
    }

    private fun drawRoute(route: LibreCRMapRoute.Snapshot?) {
        val map = projection?.map ?: return
        map.clear()
        route?.destination?.let { map.addMarker(MarkerOptions().position(it)) }
        if (!route?.points.isNullOrEmpty()) {
            map.addPolyline(PolylineOptions().color(getColor(R.color.mapRouteLine)).addAll(route!!.points))
        }
    }

    private fun capture(source: ImageReader, token: Int) {
        val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (token != generation || now - lastCaptureMs < 900L) return
            lastCaptureMs = now
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            try {
                padded.copyPixelsFromBuffer(plane.buffer)
                val frame = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(frame).drawBitmap(padded, Rect(0, 0, image.width, image.height), Rect(0, 0, frame.width, frame.height), null)
                    val bytes = ByteArrayOutputStream().use {
                        if (!frame.compress(Bitmap.CompressFormat.JPEG, 72, it)) return
                        it.toByteArray()
                    }
                    if (bytes.size <= MAX_FRAME_BYTES) main.post {
                        if (generation == token && jpeg?.contentEquals(bytes) != true) {
                            jpeg = bytes
                            sequence++
                        }
                    }
                } finally { frame.recycle() }
            } finally { padded.recycle() }
        } catch (_: RuntimeException) {
            main.post { if (generation == token) { jpeg = null } }
        } finally { runCatching { image.close() } }
    }

    private fun releaseMap() {
        generation++
        searchJob?.cancel()
        navigationJob?.cancel()
        suggestions = emptyList()
        placeSearch = null
        main.removeCallbacks(idle)
        LibreCRMapRoute.current.removeObserver(routeObserver)
        location?.callback = null
        location?.stop()
        location = null
        projection?.mapListener = null
        runCatching { projection?.dismiss() }
        projection = null
        settings?.callback = null
        settings = null
        cdsData?.setConnection(null)
        cdsData = null
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.let { old -> captureHandler.post { runCatching { old.close() } } }
        reader = null
        size = null
        jpeg = null
        mapLoaded = false
        lastCaptureMs = 0L
    }

    override fun onUnbind(intent: Intent?): Boolean { releaseMap(); return false }
    override fun onDestroy() {
        releaseMap()
        commands.cancel()
        captureThread.quitSafely()
        super.onDestroy()
    }
}
