package com.zephiiel.zeplauncher

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONException
import java.text.DateFormat
import java.util.Date
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    companion object {
        private const val TAG = "ZepLauncher"
        private const val REQUEST_CALENDAR_PERMISSION = 1001
        private const val REQUEST_LOCATION_PERMISSION = 1002
        private const val REQUEST_HOME_WALLPAPER = 1003
        private const val NOTIFICATION_SLIDE_INTERVAL_MS = 5_000L
        private const val DEFAULT_DRAWER_ROWS = 4
        private const val DEFAULT_DRAWER_COLUMNS = 6
        private const val MIN_DRAWER_ROWS = 2
        private const val MAX_DRAWER_ROWS = 5
        private const val MIN_DRAWER_COLUMNS = 3
        private const val MAX_DRAWER_COLUMNS = 8
        const val ACTION_WIDGET_DATA_CHANGED =
            "com.zephiiel.zeplauncher.ACTION_WIDGET_DATA_CHANGED"
    }

    private enum class Screen {
        HOME,
        APP_DRAWER,
        SETTINGS
    }

    private var currentScreen = Screen.HOME
    private var appShortcuts: LinearLayout? = null
    private var notificationIconView: ImageView? = null
    private var notificationTitleView: TextView? = null
    private var notificationBodyView: TextView? = null
    private var notificationCard: View? = null
    private var notificationCountView: TextView? = null
    private var notificationCloseButton: ImageView? = null
    private var notifications: List<NotificationItem> = emptyList()
    private var notificationIndex = 0
    private var calendarDayView: TextView? = null
    private var calendarDateView: TextView? = null
    private var calendarTitleView: TextView? = null
    private var calendarSubtitleView: TextView? = null
    private var mediaArtworkView: ImageView? = null
    private var mediaPlayPauseButton: ImageView? = null
    private var mediaPreviousButton: ImageView? = null
    private var mediaNextButton: ImageView? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var speedometerView: SpeedometerView? = null
    private var locationManager: LocationManager? = null
    private var locationUpdatesStarted = false
    private var activityResumed = false
    private var wallpaperImageView: ImageView? = null
    private var homeWallpaperBitmap: Bitmap? = null
    private var loadedWallpaperUri: String? = null
    private var wallpaperRequestId = 0
    private var widgetReceiverRegistered = false
    private var mediaCallbackRegistered = false
    private var calendarRequestId = 0
    private var calendarQueryFuture: Future<*>? = null
    private val calendarExecutor = Executors.newSingleThreadExecutor()
    private val wallpaperExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationSlideRunnable = object : Runnable {
        override fun run() {
            if (!activityResumed || currentScreen != Screen.HOME || notifications.size < 2) {
                return
            }
            showNextNotification()
        }
    }
    private val widgetDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshNotificationWidget()
            refreshMediaSession()
        }
    }
    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshMediaWidget()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshMediaWidget()
        }

        override fun onSessionDestroyed() {
            setActiveMediaController(null)
            refreshMediaSession()
        }
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) {
                speedometerView?.setReading(location.speed * 3.6f, "GPS SPEED")
            } else {
                speedometerView?.setReading(null, "WAITING FOR GPS")
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER && activityResumed) {
                startLocationUpdates()
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                speedometerView?.setReading(null, "GPS UNAVAILABLE")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(16, 20, 29)
        window.navigationBarColor = Color.rgb(16, 20, 29)
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        refreshRecentApps()
        registerWidgetDataReceiver()
        registerActiveSessionsListener()
        refreshHomeWidgets()
    }

    override fun onPause() {
        activityResumed = false
        mainHandler.removeCallbacks(notificationSlideRunnable)
        stopLocationUpdates()
        unregisterWidgetDataReceiver()
        unregisterActiveSessionsListener()
        super.onPause()
    }

    override fun onDestroy() {
        calendarRequestId += 1
        wallpaperRequestId += 1
        calendarExecutor.shutdownNow()
        wallpaperExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CALENDAR_PERMISSION -> refreshCalendarWidget()
            REQUEST_LOCATION_PERMISSION -> {
                if (hasFineLocationPermission()) {
                    startLocationUpdates()
                } else {
                    speedometerView?.setReading(null, "LOCATION PERMISSION NEEDED")
                }
            }
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_HOME_WALLPAPER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val readPermission = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, readPermission)
        } catch (error: SecurityException) {
            Log.w(TAG, "Wallpaper permission could not be persisted.", error)
            Toast.makeText(this, "Wallpaper access may not persist after restart.", Toast.LENGTH_LONG)
                .show()
        }
        getSharedPreferences("launcher", MODE_PRIVATE).edit()
            .putString("home_wallpaper_uri", uri.toString())
            .apply()
        loadedWallpaperUri = null
        homeWallpaperBitmap = null
        wallpaperRequestId += 1
        showScreen(Screen.HOME)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (currentScreen == Screen.HOME) {
            super.onBackPressed()
        } else {
            showScreen(Screen.HOME, fadeContent = currentScreen == Screen.APP_DRAWER)
        }
    }

    private fun renderScreen(fadeContent: Boolean = false) {
        val configuredWallpaper = getSharedPreferences("launcher", MODE_PRIVATE)
            .getString("home_wallpaper_uri", null)
            ?.takeIf { it.isNotBlank() }
        val savedWallpaper = if (currentScreen == Screen.HOME) configuredWallpaper else null
        if (configuredWallpaper != loadedWallpaperUri) {
            loadedWallpaperUri = configuredWallpaper
            homeWallpaperBitmap = null
            wallpaperRequestId += 1
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(color("#253746"), color("#1B2432"), color("#121720"))
            )
        }
        val root = FrameLayout(this)
        wallpaperImageView = null
        if (savedWallpaper != null) {
            shell.background = ColorDrawable(Color.TRANSPARENT)
            wallpaperImageView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(homeWallpaperBitmap)
                contentDescription = "Home wallpaper"
            }
            root.addView(wallpaperImageView, FrameLayout.LayoutParams(-1, -1))
            root.addView(
                View(this).apply {
                    setBackgroundColor(Color.argb(64, 10, 14, 21))
                    isClickable = false
                },
                FrameLayout.LayoutParams(-1, -1)
            )
        }
        shell.addView(
            createSideNavigation(),
            LinearLayout.LayoutParams(dp(80), -1).apply { marginEnd = dp(10) }
        )
        val page = when (currentScreen) {
            Screen.HOME -> createHomeContent()
            Screen.APP_DRAWER -> createAppDrawerContent()
            Screen.SETTINGS -> createSettingsContent()
        }
        shell.addView(page, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (savedWallpaper != null && homeWallpaperBitmap == null) {
            loadHomeWallpaper(Uri.parse(savedWallpaper), wallpaperRequestId)
        }
        if (fadeContent) {
            page.alpha = 0f
            page.animate().alpha(1f).setDuration(300).start()
        }
        refreshRecentApps()
        refreshHomeWidgets()
        applyImmersiveMode()
    }

    private fun loadHomeWallpaper(uri: Uri, requestId: Int) {
        wallpaperExecutor.execute {
            val bitmap = try {
                decodeHomeWallpaper(uri)
            } catch (error: IOException) {
                Log.e(TAG, "Could not read the selected wallpaper.", error)
                null
            } catch (error: SecurityException) {
                Log.e(TAG, "Wallpaper access is no longer available.", error)
                null
            }
            mainHandler.post {
                if (requestId != wallpaperRequestId || isFinishing) return@post
                if (bitmap == null) {
                    Toast.makeText(this, "Could not load the selected wallpaper.", Toast.LENGTH_LONG)
                        .show()
                    return@post
                }
                homeWallpaperBitmap = bitmap
                if (currentScreen == Screen.HOME) {
                    wallpaperImageView?.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decodeHomeWallpaper(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val targetHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetWidth * 2 ||
            bounds.outHeight / sampleSize > targetHeight * 2
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun showScreen(screen: Screen, fadeContent: Boolean = false) {
        currentScreen = screen
        renderScreen(fadeContent)
    }

    private fun applyImmersiveMode(targetWindow: Window = window) {
        targetWindow.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun createHomeContent(): View {
        val home = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        speedometerView = SpeedometerView(this).apply {
            setOnClickListener { requestSpeedometerLocation() }
        }
        home.addView(speedometerView, LinearLayout.LayoutParams(0, -1, 2.1f))

        val widgets = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        home.addView(widgets, LinearLayout.LayoutParams(0, -1, 1f))
        widgets.addView(
            createNotificationWidget(),
            LinearLayout.LayoutParams(-1, 0, 0.75f).apply { bottomMargin = dp(6) }
        )
        widgets.addView(
            createCalendarWidget(),
            LinearLayout.LayoutParams(-1, 0, 0.75f).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
        )
        widgets.addView(
            createMediaWidget(),
            LinearLayout.LayoutParams(-1, 0, 1.2f).apply { topMargin = dp(6) }
        )
        return home
    }

    private fun createNotificationWidget(): View {
        val card = FrameLayout(this).apply {
            background = widgetBackground()
            contentDescription = "Notification"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(42), dp(10))
        }
        val iconContainer = FrameLayout(this).apply {
            background = rounded("#3A4658", 15)
        }
        notificationIconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            setColorFilter(color("#F3F6FB"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "Notification app"
        }
        iconContainer.addView(
            notificationIconView,
            FrameLayout.LayoutParams(-1, -1)
        )
        row.addView(iconContainer, LinearLayout.LayoutParams(dp(50), dp(50)))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(label("NOTIFICATION", 9f, "#B8C6D2", true))
        notificationCountView = label("", 8f, "#AEBAC5", false).apply {
            gravity = Gravity.END
        }
        heading.addView(
            notificationCountView,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        details.addView(heading)
        notificationTitleView = label("No notifications", 15f, "#F3F6FB", true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(
            notificationTitleView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        notificationBodyView = label(
            "New alerts will appear here",
            10f,
            "#AEBAC5",
            false
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(
            notificationBodyView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) }
        )
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, FrameLayout.LayoutParams(-1, -1))

        notificationCloseButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(color("#CBD4DE"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Dismiss notification"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { dismissCurrentNotification() }
        }
        card.addView(
            notificationCloseButton,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6)
                marginEnd = dp(6)
            }
        )
        notificationCard = card
        card.setOnClickListener {
            if (!hasNotificationAccess()) {
                openNotificationAccessSettings()
            } else {
                openCurrentNotification()
            }
        }
        return card
    }

    private fun createCalendarWidget(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = widgetBackground()
            contentDescription = "Upcoming calendar event"
        }
        val dateTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded("#3A4658", 14)
        }
        calendarDayView = label(
            android.text.format.DateFormat.format("EEE", Date()).toString(),
            8f,
            "#F2B36D",
            false
        ).apply { gravity = Gravity.CENTER }
        dateTile.addView(calendarDayView)
        calendarDateView = label(
            android.text.format.DateFormat.format("d", Date()).toString(),
            20f,
            "#F3F6FB",
            true
        ).apply { gravity = Gravity.CENTER }
        dateTile.addView(calendarDateView)
        card.addView(dateTile, LinearLayout.LayoutParams(dp(54), dp(54)))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        details.addView(label("CALENDAR", 9f, "#B8C6D2", true))
        calendarTitleView = label("Loading calendar", 16f, "#F3F6FB", true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(
            calendarTitleView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }
        )
        calendarSubtitleView = label("", 10f, "#AEBAC5", false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(
            calendarSubtitleView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        card.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_CALENDAR),
                    REQUEST_CALENDAR_PERMISSION
                )
            } else {
                openCalendarApp()
            }
        }
        return card
    }

    private fun createMediaWidget(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = widgetBackground()
            contentDescription = "Media controls"
        }
        mediaArtworkView = ImageView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(color("#377C87"), color("#374A72"), color("#8D615A"))
            ).apply { cornerRadius = dp(16).toFloat() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Album art"
            clipToOutline = true
        }
        val artworkFrame = FrameLayout(this)
        artworkFrame.addView(
            mediaArtworkView,
            FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER)
        )
        card.addView(artworkFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        card.setOnClickListener {
            if (!hasNotificationAccess()) openNotificationAccessSettings()
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        mediaPreviousButton = mediaControlIcon(
            android.R.drawable.ic_media_previous,
            "Previous track"
        ) {
            sendMediaAction(MediaAction.PREVIOUS)
        }
        controls.addView(
            mediaPreviousButton,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(24) }
        )
        mediaPlayPauseButton = mediaControlIcon(
            android.R.drawable.ic_media_play,
            "Play"
        ) {
            sendMediaAction(MediaAction.PLAY_PAUSE)
        }
        controls.addView(
            mediaPlayPauseButton,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(24) }
        )
        mediaNextButton = mediaControlIcon(
            android.R.drawable.ic_media_next,
            "Next track"
        ) {
            sendMediaAction(MediaAction.NEXT)
        }
        controls.addView(
            mediaNextButton,
            LinearLayout.LayoutParams(dp(46), dp(46))
        )
        card.addView(controls, LinearLayout.LayoutParams(-1, dp(48)))
        return card
    }

    private fun mediaControlIcon(
        drawable: Int,
        description: String,
        onClick: () -> Unit
    ) =
        ImageView(this).apply {
            setImageResource(drawable)
            setColorFilter(color("#F3F6FB"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun widgetBackground() =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(217, 56, 67, 84),
                Color.argb(217, 35, 44, 60),
                Color.argb(217, 49, 59, 76)
            )
        ).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.argb(50, 235, 244, 255))
        }

    private fun requestSpeedometerLocation() {
        if (hasFineLocationPermission()) {
            startLocationUpdates()
            return
        }
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQUEST_LOCATION_PERMISSION
        )
    }

    private fun hasFineLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        val view = speedometerView ?: return
        if (!activityResumed || currentScreen != Screen.HOME || locationUpdatesStarted) return
        if (!hasFineLocationPermission()) {
            view.setReading(null, "TAP TO ENABLE GPS")
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            Log.w(TAG, "Location service is unavailable.")
            view.setReading(null, "GPS UNAVAILABLE")
            return
        }
        locationManager = manager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            view.setReading(null, "TURN ON GPS")
            return
        }
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                locationListener
            )
            locationUpdatesStarted = true
            val lastLocation = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastLocation != null && lastLocation.hasSpeed()) {
                view.setReading(lastLocation.speed * 3.6f, "GPS SPEED")
            } else {
                view.setReading(null, "WAITING FOR GPS")
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Location permission was revoked.", error)
            view.setReading(null, "LOCATION PERMISSION NEEDED")
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "GPS provider is unavailable.", error)
            view.setReading(null, "GPS UNAVAILABLE")
        }
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesStarted) return
        locationManager?.removeUpdates(locationListener)
        locationUpdatesStarted = false
    }

    private fun refreshHomeWidgets() {
        if (currentScreen != Screen.HOME) {
            stopLocationUpdates()
            return
        }
        refreshNotificationWidget()
        refreshCalendarWidget()
        refreshMediaSession()
        if (hasFineLocationPermission()) {
            speedometerView?.setReading(null, "WAITING FOR GPS")
            startLocationUpdates()
        } else {
            speedometerView?.setReading(null, "TAP TO ENABLE GPS")
        }
    }

    private fun refreshNotificationWidget() {
        if (notificationTitleView == null || notificationBodyView == null ||
            notificationIconView == null || notificationCard == null
        ) {
            return
        }
        notificationCard?.animate()?.cancel()
        notificationCard?.alpha = 1f
        if (!hasNotificationAccess()) {
            notifications = emptyList()
            notificationIndex = 0
            showCurrentNotification()
            scheduleNotificationSlideshow()
            return
        }

        val preferences = getSharedPreferences(
            RecentNotificationListenerService.PREFERENCES_NAME,
            MODE_PRIVATE
        )
        val savedItems = preferences.getString(
            RecentNotificationListenerService.KEY_NOTIFICATIONS,
            "[]"
        ).orEmpty()
        val parsedItems = try {
            val array = JSONArray(savedItems)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val key = item.optString("key")
                if (key.isBlank()) return@mapNotNull null
                NotificationItem(
                    key = key,
                    packageName = item.optString("package"),
                    appName = item.optString("app"),
                    title = item.optString("title"),
                    body = item.optString("body")
                )
            }
        } catch (error: JSONException) {
            Log.e(TAG, "Saved notification data is invalid.", error)
            emptyList()
        }
        val previousKey = notifications.getOrNull(notificationIndex)?.key
        notifications = parsedItems
        notificationIndex = parsedItems.indexOfFirst { it.key == previousKey }
            .takeIf { it >= 0 } ?: 0
        showCurrentNotification()
        scheduleNotificationSlideshow()
    }

    private fun showCurrentNotification() {
        val titleView = notificationTitleView ?: return
        val bodyView = notificationBodyView ?: return
        val iconView = notificationIconView ?: return
        val card = notificationCard ?: return
        val item = notifications.getOrNull(notificationIndex)
        if (item == null) {
            notificationCountView?.text = ""
            notificationCloseButton?.visibility = View.GONE
            if (hasNotificationAccess()) {
                titleView.text = "No notifications"
                bodyView.text = "New alerts will appear here"
                card.contentDescription = "No notifications"
            } else {
                titleView.text = "Access needed"
                bodyView.text = "Tap to allow notifications"
                card.contentDescription = "Enable notification access"
            }
            setDefaultNotificationIcon(iconView)
            return
        }

        notificationCountView?.text = if (notifications.size > 1) {
            "${notificationIndex + 1} / ${notifications.size}"
        } else {
            ""
        }
        notificationCloseButton?.visibility = View.VISIBLE
        titleView.text = item.title.ifBlank { item.appName.ifBlank { "Notification" } }
        bodyView.text = when {
            item.body.isNotBlank() && item.appName.isNotBlank() ->
                "${item.appName} · ${item.body}"
            item.body.isNotBlank() -> item.body
            else -> item.appName
        }
        card.contentDescription = listOf(item.appName, item.title, item.body)
            .filter { it.isNotBlank() }
            .joinToString(". ") + ". Tap to open notification."
        if (item.packageName.isNotBlank()) {
            try {
                iconView.setImageDrawable(packageManager.getApplicationIcon(item.packageName))
                iconView.clearColorFilter()
                iconView.setPadding(dp(6), dp(6), dp(6), dp(6))
                iconView.contentDescription = item.appName
            } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
                Log.w(TAG, "Notification source app is no longer installed.", error)
                setDefaultNotificationIcon(iconView)
            }
        } else {
            setDefaultNotificationIcon(iconView)
        }
    }

    private fun scheduleNotificationSlideshow() {
        mainHandler.removeCallbacks(notificationSlideRunnable)
        if (activityResumed && currentScreen == Screen.HOME && notifications.size > 1) {
            mainHandler.postDelayed(notificationSlideRunnable, NOTIFICATION_SLIDE_INTERVAL_MS)
        }
    }

    private fun showNextNotification() {
        val card = notificationCard ?: return
        if (notifications.size < 2) return
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                if (!activityResumed || currentScreen != Screen.HOME || notifications.size < 2) {
                    card.alpha = 1f
                    return@withEndAction
                }
                notificationIndex = (notificationIndex + 1) % notifications.size
                showCurrentNotification()
                card.animate().alpha(1f).setDuration(220).start()
                mainHandler.postDelayed(notificationSlideRunnable, NOTIFICATION_SLIDE_INTERVAL_MS)
            }
            .start()
    }

    private fun setDefaultNotificationIcon(iconView: ImageView) {
        iconView.setImageResource(android.R.drawable.ic_dialog_info)
        iconView.setColorFilter(color("#F3F6FB"))
        iconView.setPadding(dp(14), dp(14), dp(14), dp(14))
        iconView.contentDescription = "Notification"
    }

    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val componentName = ComponentName(this, RecentNotificationListenerService::class.java)
        return enabledListeners.split(':').any {
            ComponentName.unflattenFromString(it) == componentName
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "Notification access settings are unavailable.", error)
            Toast.makeText(this, "Open Android Settings to enable notification access.", Toast.LENGTH_LONG)
                .show()
        }
    }

    private data class NotificationItem(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val body: String
    )

    private fun openCurrentNotification() {
        val item = notifications.getOrNull(notificationIndex) ?: return
        sendBroadcast(
            Intent(RecentNotificationListenerService.ACTION_OPEN_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(RecentNotificationListenerService.EXTRA_NOTIFICATION_KEY, item.key)
        )
    }

    private fun dismissCurrentNotification() {
        val item = notifications.getOrNull(notificationIndex) ?: return
        sendBroadcast(
            Intent(RecentNotificationListenerService.ACTION_DISMISS_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(RecentNotificationListenerService.EXTRA_NOTIFICATION_KEY, item.key)
        )
    }

    private fun registerWidgetDataReceiver() {
        if (widgetReceiverRegistered) return
        val filter = IntentFilter(ACTION_WIDGET_DATA_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(widgetDataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(widgetDataReceiver, filter)
        }
        widgetReceiverRegistered = true
    }

    private fun unregisterWidgetDataReceiver() {
        if (!widgetReceiverRegistered) return
        unregisterReceiver(widgetDataReceiver)
        widgetReceiverRegistered = false
    }

    private data class CalendarEvent(
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String?
    )

    private fun refreshCalendarWidget() {
        val titleView = calendarTitleView ?: return
        val subtitleView = calendarSubtitleView ?: return
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            titleView.text = "Allow calendar"
            subtitleView.text = "Tap to allow upcoming events"
            calendarDayView?.text =
                android.text.format.DateFormat.format("EEE", Date()).toString()
            calendarDateView?.text =
                android.text.format.DateFormat.format("d", Date()).toString()
            return
        }

        titleView.text = "Checking calendar"
        subtitleView.text = ""
        calendarQueryFuture?.cancel(true)
        val requestId = ++calendarRequestId
        calendarQueryFuture = calendarExecutor.submit {
            val result = try {
                CalendarQueryResult.Event(queryNextCalendarEvent())
            } catch (error: SecurityException) {
                Log.w(TAG, "Calendar access was revoked while reading events.", error)
                CalendarQueryResult.AccessDenied
            }
            mainHandler.post {
                if (requestId != calendarRequestId || isFinishing ||
                    currentScreen != Screen.HOME
                ) {
                    return@post
                }
                when (result) {
                    is CalendarQueryResult.Event -> showCalendarEvent(result.event)
                    CalendarQueryResult.AccessDenied -> {
                        titleView.text = "Allow calendar"
                        subtitleView.text = "Tap to allow upcoming events"
                    }
                }
            }
        }
    }

    private sealed class CalendarQueryResult {
        data class Event(val event: CalendarEvent?) : CalendarQueryResult()
        object AccessDenied : CalendarQueryResult()
    }

    private fun queryNextCalendarEvent(): CalendarEvent? {
        val now = System.currentTimeMillis()
        val endWindow = now + 30L * 24L * 60L * 60L * 1000L
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, now)
        ContentUris.appendId(uriBuilder, endWindow)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION
        )
        return contentResolver.query(
            uriBuilder.build(),
            projection,
            "${CalendarContract.Instances.END} >= ?",
            arrayOf(now.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val locationIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                CalendarEvent(
                    title = cursor.getString(titleIndex).orEmpty(),
                    begin = cursor.getLong(beginIndex),
                    end = cursor.getLong(endIndex),
                    allDay = cursor.getInt(allDayIndex) != 0,
                    location = cursor.getString(locationIndex)
                )
            }
        }
    }

    private fun showCalendarEvent(event: CalendarEvent?) {
        val titleView = calendarTitleView ?: return
        val subtitleView = calendarSubtitleView ?: return
        if (event == null) {
            titleView.text = "No upcoming events"
            subtitleView.text = "Nothing scheduled in the next 30 days"
            calendarDayView?.text =
                android.text.format.DateFormat.format("EEE", Date()).toString()
            calendarDateView?.text =
                android.text.format.DateFormat.format("d", Date()).toString()
            return
        }
        titleView.text = event.title.ifBlank { "Untitled event" }
        val eventDate = Date(event.begin)
        calendarDayView?.text =
            android.text.format.DateFormat.format("EEE", eventDate).toString()
        calendarDateView?.text =
            android.text.format.DateFormat.format("d", eventDate).toString()
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(eventDate)
        val time = if (event.allDay) {
            "All day"
        } else {
            "${DateFormat.getTimeInstance(DateFormat.SHORT).format(eventDate)} – " +
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.end))
        }
        subtitleView.text = listOfNotNull(
            "$date · $time",
            event.location?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }

    private fun openCalendarApp() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setData(CalendarContract.CONTENT_URI))
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "No calendar app can display the calendar.", error)
            Toast.makeText(this, "No calendar app is available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerActiveSessionsListener() {
        if (!hasNotificationAccess() || activeSessionsListener != null) return
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (manager == null) {
            Log.w(TAG, "Media session service is unavailable.")
            return
        }
        mediaSessionManager = manager
        val listener = object : MediaSessionManager.OnActiveSessionsChangedListener {
            override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
                setActiveMediaController(selectMediaController(controllers.orEmpty()))
            }
        }
        try {
            manager.addOnActiveSessionsChangedListener(
                listener,
                ComponentName(this, RecentNotificationListenerService::class.java),
                mainHandler
            )
            activeSessionsListener = listener
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification access is required to observe media sessions.", error)
        }
    }

    private fun unregisterActiveSessionsListener() {
        activeSessionsListener?.let {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(it)
            activeSessionsListener = null
        }
        if (mediaCallbackRegistered) {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            mediaCallbackRegistered = false
        }
    }

    private fun refreshMediaSession() {
        if (!hasNotificationAccess()) {
            setActiveMediaController(null)
            refreshMediaWidget()
            return
        }
        val manager = mediaSessionManager
            ?: (getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
                ?.also { mediaSessionManager = it }
        if (manager == null) {
            refreshMediaWidget()
            return
        }
        try {
            setActiveMediaController(
                selectMediaController(
                    manager.getActiveSessions(
                        ComponentName(this, RecentNotificationListenerService::class.java)
                    )
                )
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not read active media sessions.", error)
            setActiveMediaController(null)
        }
    }

    private fun selectMediaController(
        controllers: List<MediaController>
    ): MediaController? {
        return controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            val state = it.playbackState?.state
            state == PlaybackState.STATE_PAUSED ||
                state == PlaybackState.STATE_BUFFERING
        } ?: controllers.firstOrNull()
    }

    private fun setActiveMediaController(controller: MediaController?) {
        if (activeMediaController === controller) {
            if (controller != null && widgetReceiverRegistered && !mediaCallbackRegistered) {
                controller.registerCallback(mediaControllerCallback, mainHandler)
                mediaCallbackRegistered = true
            }
            refreshMediaWidget()
            return
        }
        if (mediaCallbackRegistered) {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            mediaCallbackRegistered = false
        }
        activeMediaController = controller
        if (controller != null && widgetReceiverRegistered) {
            controller.registerCallback(mediaControllerCallback, mainHandler)
            mediaCallbackRegistered = true
        }
        refreshMediaWidget()
    }

    private fun refreshMediaWidget() {
        val controller = activeMediaController
        if (controller == null) {
            mediaArtworkView?.apply {
                setImageBitmap(null)
                contentDescription = "No album art"
            }
            setMediaButtonEnabled(mediaPreviousButton, false)
            setMediaButtonEnabled(mediaNextButton, false)
            mediaPlayPauseButton?.apply {
                setImageResource(android.R.drawable.ic_media_play)
                contentDescription = "Play"
                isEnabled = false
                alpha = 0.45f
            }
            return
        }

        val metadata = controller.metadata
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        mediaArtworkView?.apply {
            setImageBitmap(artwork)
            contentDescription = if (artwork == null) "Media album art unavailable" else "Album art"
        }

        val state = controller.playbackState
        val actions = state?.actions ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val previousSupported = state == null ||
            actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS.toLong() != 0L
        val nextSupported = state == null ||
            actions and PlaybackState.ACTION_SKIP_TO_NEXT.toLong() != 0L
        val playPauseSupported = state == null ||
            actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE).toLong() != 0L
        setMediaButtonEnabled(mediaPreviousButton, previousSupported)
        setMediaButtonEnabled(mediaNextButton, nextSupported)
        mediaPlayPauseButton?.apply {
            setImageResource(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            contentDescription = if (playing) "Pause" else "Play"
            isEnabled = playPauseSupported
            alpha = if (playPauseSupported) 1f else 0.45f
        }
    }

    private fun setMediaButtonEnabled(button: ImageView?, enabled: Boolean) {
        button?.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
        }
    }

    private enum class MediaAction {
        PREVIOUS,
        PLAY_PAUSE,
        NEXT
    }

    private fun sendMediaAction(action: MediaAction) {
        if (!hasNotificationAccess()) {
            openNotificationAccessSettings()
            return
        }
        val controller = activeMediaController
        if (controller == null) {
            Toast.makeText(this, "No active media session.", Toast.LENGTH_SHORT).show()
            refreshMediaSession()
            return
        }
        val state = controller.playbackState
        val actions = state?.actions ?: 0L
        try {
            when (action) {
                MediaAction.PREVIOUS -> {
                    if (state != null &&
                        actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS.toLong() == 0L
                    ) {
                        Toast.makeText(this, "Previous track is not supported.", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        controller.transportControls.skipToPrevious()
                    }
                }
                MediaAction.PLAY_PAUSE -> {
                    if (state != null &&
                        actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                            .toLong() == 0L
                    ) {
                        Toast.makeText(this, "Play/pause is not supported.", Toast.LENGTH_SHORT)
                            .show()
                    } else if (state?.state == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                }
                MediaAction.NEXT -> {
                    if (state != null &&
                        actions and PlaybackState.ACTION_SKIP_TO_NEXT.toLong() == 0L
                    ) {
                        Toast.makeText(this, "Next track is not supported.", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        controller.transportControls.skipToNext()
                    }
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Media action was denied by the active session.", error)
            Toast.makeText(this, "The media app denied this control.", Toast.LENGTH_SHORT).show()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "The active media session is no longer available.", error)
            refreshMediaSession()
        }
    }

    private fun createAppDrawerContent(): View {
        val apps = listOf(createLauncherSettingsApp()) + loadLaunchableApps()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }

        val rows = getDrawerRows()
        val columns = getDrawerColumns()
        val grid = GridView(this).apply {
            numColumns = columns
            horizontalSpacing = dp(6)
            verticalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.CENTER
            isVerticalScrollBarEnabled = false
            selector = ColorDrawable(Color.TRANSPARENT)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                launchApp(apps[position])
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, itemView, position, _ ->
                val app = apps[position]
                if (!app.isLauncherSettings) {
                    showFavoriteMenu(app, (itemView as FrameLayout).getChildAt(0))
                }
                true
            }
        }
        page.addView(grid, LinearLayout.LayoutParams(-1, -1))
        grid.post {
            val rowHeight = ((grid.height - dp(6) * (rows - 1)) / rows)
                .coerceAtLeast(dp(36))
            grid.adapter = AppIconAdapter(apps, rowHeight)
        }
        return page
    }

    private fun createLauncherSettingsApp() = LaunchableApp(
        packageName = packageName,
        activityName = "",
        label = "Launcher settings",
        icon = resources.getDrawable(R.drawable.ic_launcher_settings, theme),
        isLauncherSettings = true
    )

    private fun createSettingsContent(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        page.addView(label("LAUNCHER SETTINGS", 20f, "#F3F6FB", true))
        val updater = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded("#D91C2531", 16)
        }
        updater.addView(label("App Version: v0.1 (Up-to-date)", 14f, "#F3F6FB", true))
        updater.addView(
            label("Vibe Coded by Gabriel Royo Jr.", 10f, "#AEBAC5", false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        updater.addView(
            settingsActionButton("Check for Updates") {
                Toast.makeText(
                    this,
                    "Update checking is not configured yet.",
                    Toast.LENGTH_LONG
                ).show()
            },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) }
        )
        page.addView(
            updater,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        )
        page.addView(
            settingsActionButton("Change Wallpaper") { openWallpaperPicker() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(18) }
        )
        page.addView(
            label("APP DRAWER GRID", 10f, "#AEBAC5", true).apply {
                letterSpacing = 0.1f
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(16)
            }
        )
        page.addView(
            createGridSetting(
                "Rows per screen",
                "Set how many icon rows fit before scrolling.",
                "drawer_rows",
                DEFAULT_DRAWER_ROWS,
                MIN_DRAWER_ROWS,
                MAX_DRAWER_ROWS
            ),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        )
        page.addView(
            createGridSetting(
                "Columns per row",
                "Set how many app icons appear across.",
                "drawer_columns",
                DEFAULT_DRAWER_COLUMNS,
                MIN_DRAWER_COLUMNS,
                MAX_DRAWER_COLUMNS
            )
        )
        page.addView(
            label("Changes are saved automatically and applied to the app drawer.", 11f, "#9EABB7", false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        )
        return page
    }

    private fun settingsActionButton(text: String, onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(color("#F3F6FB"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded("#344252", 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun openWallpaperPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQUEST_HOME_WALLPAPER)
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "No file picker is available for wallpaper selection.", error)
            Toast.makeText(this, "No image picker is available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createGridSetting(
        title: String,
        description: String,
        key: String,
        defaultValue: Int,
        minimum: Int,
        maximum: Int
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = rounded("#E51C2531", 18)
        }
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textBlock.addView(label(title, 14f, "#F3F6FB", true))
        textBlock.addView(
            label(description, 10f, "#AEBAC5", false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        row.addView(textBlock, LinearLayout.LayoutParams(0, -2, 1f))

        val value = getSharedPreferences("launcher", MODE_PRIVATE).getInt(key, defaultValue)
        row.addView(
            stepperButton("-") {
                saveGridSetting(key, (value - 1).coerceAtLeast(minimum))
            }
        )
        row.addView(
            label(value.toString(), 16f, "#F3F6FB", true).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(42), dp(40))
        )
        row.addView(
            stepperButton("+") {
                saveGridSetting(key, (value + 1).coerceAtMost(maximum))
            }
        )
        return row
    }

    private fun stepperButton(text: String, onClick: () -> Unit) =
        label(text, 20f, "#F3F6FB", true).apply {
            gravity = Gravity.CENTER
            background = rounded("#344252", 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }

    private fun saveGridSetting(key: String, value: Int) {
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putInt(key, value).apply()
        showScreen(Screen.SETTINGS)
    }

    private fun createSideNavigation(): View {
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.argb(217, 54, 65, 81),
                    Color.argb(217, 28, 35, 49),
                    Color.argb(217, 48, 59, 76)
                )
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.argb(48, 235, 244, 255))
            }
            elevation = dp(4).toFloat()
        }
        val clock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        clock.addView(
            TextClock(this).apply {
                format12Hour = "h:mm"
                format24Hour = "HH:mm"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(color("#F3F6FB"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
                maxLines = 1
            }
        )
        navigation.addView(clock, LinearLayout.LayoutParams(-1, -2))

        appShortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val shortcutsScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(appShortcuts, FrameLayout.LayoutParams(-1, -2))
        }
        navigation.addView(
            shortcutsScroll,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        )
        navigation.addView(
            AppGridButton(currentScreen == Screen.APP_DRAWER).apply {
                setOnClickListener { openAppDrawer() }
            },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(6) }
        )
        return navigation
    }

    private fun refreshRecentApps() {
        val container = appShortcuts ?: return
        container.removeAllViews()
        val appsByPackage = loadLaunchableApps().associateBy { it.packageName }
        val favorites = getFavoritePackages().mapNotNull { appsByPackage[it] }
        favorites.forEachIndexed { index, app ->
            container.addView(
                createSideAppShortcut(app, true),
                LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                    if (index > 0) topMargin = dp(8)
                }
            )
        }

        val preferences = getSharedPreferences("launcher", MODE_PRIVATE)
        val recentPackage = preferences.getString("recent_app", null)
            ?.takeIf { it.isNotBlank() && appsByPackage.containsKey(it) }
            ?: preferences.getString("recent_apps", "").orEmpty()
                .split('|')
                .firstOrNull { appsByPackage.containsKey(it) }
        val recentApp = recentPackage?.let { appsByPackage[it] }
        if (recentApp != null) {
            if (favorites.isNotEmpty()) {
                container.addView(
                    View(this).apply { setBackgroundColor(Color.argb(90, 235, 244, 255)) },
                    LinearLayout.LayoutParams(dp(20), dp(1)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = dp(8)
                        bottomMargin = dp(8)
                    }
                )
            }
            container.addView(
                createSideAppShortcut(recentApp, false),
                LinearLayout.LayoutParams(dp(52), dp(52))
            )
        }
    }

    private fun createSideAppShortcut(app: LaunchableApp, favorite: Boolean): View =
        ImageView(this).apply {
            setImageDrawable(app.icon)
            contentDescription = app.label
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded("#303A45", 18)
            isClickable = true
            isFocusable = true
            setOnClickListener { launchApp(app) }
            if (favorite) {
                setOnLongClickListener {
                    showFavoriteMenu(app, this)
                    true
                }
            }
        }

    private fun showFavoriteMenu(app: LaunchableApp, anchor: View) {
        val isFavorite = getFavoritePackages().contains(app.packageName)
        val popup = PopupWindow(this)
        val option = label(
            if (isFavorite) "Remove Favorite" else "Favorite App",
            14f,
            "#F3F6FB",
            true
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            setOnClickListener {
                val favorites = getFavoritePackages().toMutableList()
                if (isFavorite) {
                    favorites.remove(app.packageName)
                } else if (!favorites.contains(app.packageName)) {
                    favorites.add(app.packageName)
                }
                getSharedPreferences("launcher", MODE_PRIVATE).edit()
                    .putString("favorite_apps", favorites.joinToString("|"))
                    .apply()
                popup.dismiss()
                refreshRecentApps()
            }
        }
        val background = GradientDrawable().apply {
            setColor(Color.argb(217, 28, 36, 51))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.argb(55, 235, 244, 255))
        }
        popup.contentView = option
        popup.width = dp(176)
        popup.height = dp(52)
        popup.isFocusable = false
        popup.isOutsideTouchable = true
        option.systemUiVisibility = window.decorView.systemUiVisibility
        popup.setBackgroundDrawable(background)
        popup.elevation = dp(8).toFloat()
        popup.showAsDropDown(anchor, 0, -dp(4), Gravity.START)
    }

    private fun getFavoritePackages(): List<String> =
        getSharedPreferences("launcher", MODE_PRIVATE)
            .getString("favorite_apps", "").orEmpty()
            .split('|')
            .filter { it.isNotBlank() }
            .distinct()

    private fun openAppDrawer() {
        if (currentScreen == Screen.APP_DRAWER) {
            showScreen(Screen.HOME, fadeContent = true)
        } else {
            showScreen(Screen.APP_DRAWER, fadeContent = true)
        }
    }

    private fun loadLaunchableApps(): List<LaunchableApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val results: List<ResolveInfo> = packageManager.queryIntentActivities(query, 0)
        return results
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { result ->
                LaunchableApp(
                    packageName = result.activityInfo.packageName,
                    activityName = result.activityInfo.name,
                    label = result.loadLabel(packageManager).toString(),
                    icon = result.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun launchApp(app: LaunchableApp) {
        if (app.isLauncherSettings) {
            showScreen(Screen.SETTINGS)
            return
        }
        rememberRecentApp(app.packageName)
        if (currentScreen != Screen.HOME) showScreen(Screen.HOME)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityName)
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "That app is no longer available.", Toast.LENGTH_SHORT).show()
            refreshRecentApps()
        }
    }

    private fun rememberRecentApp(packageName: String) {
        getSharedPreferences("launcher", MODE_PRIVATE).edit()
            .putString("recent_app", packageName)
            .remove("recent_apps")
            .apply()
        refreshRecentApps()
    }

    private fun getDrawerRows() =
        getSharedPreferences("launcher", MODE_PRIVATE).getInt("drawer_rows", DEFAULT_DRAWER_ROWS)
            .coerceIn(MIN_DRAWER_ROWS, MAX_DRAWER_ROWS)

    private fun getDrawerColumns() =
        getSharedPreferences("launcher", MODE_PRIVATE).getInt("drawer_columns", DEFAULT_DRAWER_COLUMNS)
            .coerceIn(MIN_DRAWER_COLUMNS, MAX_DRAWER_COLUMNS)

    private fun label(text: String, size: Float, textColor: String, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color(textColor))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun rounded(fill: String, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(fill))
            cornerRadius = dp(radius).toFloat()
        }

    private fun color(value: String) = Color.parseColor(value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class LaunchableApp(
        val packageName: String,
        val activityName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable,
        val isLauncherSettings: Boolean = false
    )

    private inner class SpeedometerView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color("#344052")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color("#28C6D8")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color("#596778")
            strokeWidth = dp(1).toFloat()
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color("#AEBAC5")
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color("#F3F6FB")
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }
        private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color("#F2B36D")
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }
        private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color("#8FA0B1")
            textAlign = Paint.Align.CENTER
        }
        private val arcBounds = RectF()
        private var speedKmh: Float? = null
        private var statusText = "TAP TO ENABLE GPS"

        fun setReading(speed: Float?, status: String) {
            speedKmh = speed
            statusText = status
            contentDescription = if (speed == null) {
                "Ground speed. $status"
            } else {
                "Ground speed ${speed.toInt()} kilometers per hour"
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = minOf(width, height).toFloat()
            if (side <= 0f) return
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = side * 0.39f
            val strokeWidth = (side * 0.035f).coerceAtLeast(dp(8).toFloat())
            arcBounds.set(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )
            trackPaint.strokeWidth = strokeWidth
            progressPaint.strokeWidth = strokeWidth
            canvas.drawArc(arcBounds, 135f, 270f, false, trackPaint)
            speedKmh?.let {
                val progress = (it / 180f).coerceIn(0f, 1f)
                if (progress > 0f) {
                    canvas.drawArc(arcBounds, 135f, 270f * progress, false, progressPaint)
                }
            }

            for (index in 0..18) {
                val angle = Math.toRadians(135.0 + index * 15.0)
                val innerRadius = radius + strokeWidth * 0.75f
                val outerRadius = innerRadius + dp(5)
                canvas.drawLine(
                    centerX + kotlin.math.cos(angle).toFloat() * innerRadius,
                    centerY + kotlin.math.sin(angle).toFloat() * innerRadius,
                    centerX + kotlin.math.cos(angle).toFloat() * outerRadius,
                    centerY + kotlin.math.sin(angle).toFloat() * outerRadius,
                    tickPaint
                )
            }

            titlePaint.textSize = (side * 0.025f).coerceAtLeast(dp(9).toFloat())
            valuePaint.textSize = (side * 0.22f).coerceAtLeast(dp(48).toFloat())
            unitPaint.textSize = (side * 0.038f).coerceAtLeast(dp(13).toFloat())
            statusPaint.textSize = (side * 0.024f).coerceAtLeast(dp(9).toFloat())
            canvas.drawText("GROUND SPEED", centerX, centerY - radius * 0.34f, titlePaint)
            val speedText = speedKmh?.toInt()?.toString() ?: "--"
            canvas.drawText(speedText, centerX, centerY + valuePaint.textSize * 0.32f, valuePaint)
            canvas.drawText("KM/H", centerX, centerY + radius * 0.38f, unitPaint)
            canvas.drawText(statusText, centerX, centerY + radius * 0.61f, statusPaint)
        }
    }

    private inner class AppIconAdapter(
        private val apps: List<LaunchableApp>,
        private val rowHeight: Int
    ) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity)
            val icon = if (cell.childCount == 0) {
                ImageView(this@MainActivity).also { cell.addView(it) }
            } else {
                cell.getChildAt(0) as ImageView
            }
            val app = apps[position]
            val columnWidth = if (parent.width > 0) parent.width / getDrawerColumns() else dp(72)
            val iconSize = minOf(dp(58), rowHeight - dp(8), columnWidth - dp(12)).coerceAtLeast(dp(28))
            icon.setImageDrawable(app.icon)
            icon.contentDescription = app.label
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            cell.layoutParams = AbsListView.LayoutParams(-1, rowHeight)
            cell.contentDescription = app.label
            cell.background = ColorDrawable(Color.TRANSPARENT)
            cell.isClickable = false
            cell.isFocusable = false
            return cell
        }
    }

    private inner class AppGridButton(selected: Boolean) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color("#F3F6FB")
            style = Paint.Style.FILL
        }

        init {
            contentDescription = "Toggle app drawer"
            isClickable = true
            isFocusable = true
            background = rounded(if (selected) "#52647A" else "#303A45", 15)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawDotGrid(canvas, paint, width, height)
        }
    }

    private fun drawDotGrid(canvas: Canvas, paint: Paint, width: Int, height: Int) {
        val spacing = dp(10).toFloat()
        val radius = dp(2).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        for (row in -1..1) {
            for (column in -1..1) {
                canvas.drawCircle(
                    centerX + column * spacing,
                    centerY + row * spacing,
                    radius,
                    paint
                )
            }
        }
    }
}
