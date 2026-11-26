package com.floatingchargering

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class FloatingRingService : Service() {

    companion object {
        const val PREFS = "ring_prefs"
        const val KEY_SIZE = "size"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_THEME = "theme"
        const val KEY_MODE = "display_mode"
        const val KEY_HOUR24 = "hour24"
        const val KEY_OVERLAY_ON = "overlay_on"
        const val DEFAULT_MODE = 4        // 0 time, 1 date, 2 watt, 3 phone temperature, 4 battery percent, 5 current
        const val MODE_COUNT = 6
        const val KEY_SNAP = "snap_edge"
        const val KEY_OPACITY = "opacity"
        const val KEY_FONT = "font"
        const val KEY_DEC_PCT = "dec_pct"
        const val KEY_DEC_OTHER = "dec_other"
        const val DEFAULT_OPACITY = 100
        const val DEFAULT_DEC_PCT = 2
        const val DEFAULT_DEC_OTHER = 1
        const val DEFAULT_SIZE = 400
        const val MAX_SIZE = 1000
        const val ACTION_STOP = "com.floatingchargering.STOP"
        const val ACTION_STOP_ALERT = "com.floatingchargering.STOP_ALERT"
        private const val CHANNEL_ID = "floating_ring"
        private const val NOTIFICATION_ID = 1

        /** True when the ring window or any alert is switched on, i.e. the service should be running. */
        fun isWanted(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(KEY_OVERLAY_ON, false) ||
                prefs.getBoolean(AlertSettings.KEY_FULL_ON, true) ||
                prefs.getBoolean(AlertSettings.KEY_LOW_ON, true)

        /** The service is running (ring window and/or alerts). */
        @Volatile
        var running = false

        /** The ring window is currently on screen. */
        @Volatile
        var overlayShown = false
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var wm: WindowManager
    private var ring: RingView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenOn = true
    private var receiverRegistered = false
    private var alertMonitor: BatteryAlertMonitor? = null
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED) alertMonitor?.onPowerDisconnected()
            else alertMonitor?.onBattery(intent)
        }
    }

    private var snapAnimator: ValueAnimator? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_SIZE -> applySize()
            KEY_THEME -> ring?.setTheme(Themes.byId(prefs.getString(KEY_THEME, null)))
            KEY_MODE, KEY_HOUR24, KEY_FONT, KEY_DEC_PCT, KEY_DEC_OTHER, KEY_OPACITY -> ring?.let { applyAppearance(it) }
            KEY_SNAP -> snapToEdge()
            // The service runs while the ring is wanted or any alert is on; stop it when neither is.
            KEY_OVERLAY_ON, AlertSettings.KEY_FULL_ON, AlertSettings.KEY_LOW_ON -> if (!sync()) stopSelf()
        }
    }

    /** Display mode, font, decimals and opacity of the ring window. */
    private fun applyAppearance(view: RingView) {
        view.setMode(prefs.getInt(KEY_MODE, DEFAULT_MODE))
        view.setHour24(prefs.getBoolean(KEY_HOUR24, false))
        view.setDecimals(
            prefs.getInt(KEY_DEC_PCT, DEFAULT_DEC_PCT),
            prefs.getInt(KEY_DEC_OTHER, DEFAULT_DEC_OTHER)
        )
        view.setFont(prefs.getString(KEY_FONT, null))
        view.alpha = prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(20, 100) / 100f
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
            updateRunning()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "Stop" in the notification turns everything off so nothing restarts by itself.
            prefs.edit()
                .putBoolean(KEY_OVERLAY_ON, false)
                .putBoolean(AlertSettings.KEY_FULL_ON, false)
                .putBoolean(AlertSettings.KEY_LOW_ON, false)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_ALERT) {
            AlertPlayer.stop()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(BatteryAlertMonitor.ID_FULL)
            nm.cancel(BatteryAlertMonitor.ID_LOW)
            if (!running) {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        startAsForeground()
        if (!sync()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    /**
     * Brings the service in line with the settings: the ring window exists only while it is wanted
     * (and the overlay permission is granted), the alert monitor only while an alert is on.
     * Returns false when neither is needed any more.
     */
    private fun sync(): Boolean {
        val overlayWanted = prefs.getBoolean(KEY_OVERLAY_ON, false) && Settings.canDrawOverlays(this)
        if (overlayWanted) {
            if (ring == null) createOverlay()
        } else {
            removeOverlay()
        }
        val alertsWanted = prefs.getBoolean(AlertSettings.KEY_FULL_ON, true) ||
            prefs.getBoolean(AlertSettings.KEY_LOW_ON, true)
        if (alertsWanted) startAlertMonitor() else stopAlertMonitor()
        return ring != null || alertsWanted
    }

    override fun onDestroy() {
        running = false
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        removeOverlay()
        stopAlertMonitor()
        AlertPlayer.stop()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(BatteryAlertMonitor.ID_FULL)
        super.onDestroy()
    }

    private fun removeOverlay() {
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        snapAnimator?.cancel()
        ring?.let {
            it.setRunning(false)
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        ring = null
        params = null
        overlayShown = false
    }

    private fun stopAlertMonitor() {
        if (!batteryReceiverRegistered) return
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        batteryReceiverRegistered = false
        alertMonitor = null
        AlertPlayer.stop()
    }

    // ---- battery alerts --------------------------------------------------------------

    /** Event-driven (no polling): runs while this service runs, even with the screen off. */
    private fun startAlertMonitor() {
        if (batteryReceiverRegistered) return
        alertMonitor = BatteryAlertMonitor(this, prefs)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        batteryReceiverRegistered = true
    }

    // ---- foreground notification -------------------------------------------------

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating ring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FloatingRingService::class.java).setAction(ACTION_STOP), flags
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Charge Ring 2")
            .setContentText("مراقبة البطارية تعمل")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "إيقاف", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- overlay window ------------------------------------------------------------

    private fun createOverlay(): Boolean {
        val size = prefs.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(0, MAX_SIZE)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            max(size, 1), max(size, 1), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START

        val dm = resources.displayMetrics
        lp.x = prefs.getInt(KEY_X, (dm.widthPixels - size) / 2)
        lp.y = prefs.getInt(KEY_Y, (dm.heightPixels * 0.30f).toInt())
        clamp(lp)

        val view = RingView(this)
        view.setTheme(Themes.byId(prefs.getString(KEY_THEME, null)))
        applyAppearance(view)
        view.setOnTouchListener(dragListener(lp))

        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            return false
        }
        ring = view
        params = lp

        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
        overlayShown = true

        applySize()
        return true
    }

    /**
     * One finger: drag anywhere on the screen. A short press without movement is a tap and
     * switches what the ring shows (percent -> power -> temperature -> current).
     */
    private fun dragListener(lp: WindowManager.LayoutParams) = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                dragStartX = lp.x
                dragStartY = lp.y
                touchStartX = e.rawX
                touchStartY = e.rawY
                downTime = e.eventTime
                moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - touchStartX
                val dy = e.rawY - touchStartY
                if (!moved && hypot(dx, dy) > ViewConfiguration.get(this).scaledTouchSlop) moved = true
                lp.x = dragStartX + dx.toInt()
                lp.y = dragStartY + dy.toInt()
                clamp(lp)
                ring?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && e.eventTime - downTime < 400L) {
                    // Tap: undo the tiny shift and cycle the display mode.
                    lp.x = dragStartX
                    lp.y = dragStartY
                    ring?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    prefs.edit().putInt(KEY_MODE, (prefs.getInt(KEY_MODE, DEFAULT_MODE) + 1) % MODE_COUNT).apply()
                } else {
                    prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    snapToEdge()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                true
            }
            else -> false
        }
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var downTime = 0L
    private var moved = false

    /**
     * If "snap to edge" is on, slides the ring to the nearest screen edge after it is released.
     * It is optional so the ring can otherwise stay exactly where you drop it.
     */
    private fun snapToEdge() {
        val view = ring ?: return
        val lp = params ?: return
        if (!prefs.getBoolean(KEY_SNAP, false) || lp.width <= 1) return

        val dm = resources.displayMetrics
        val inset = (lp.width * 0.10f).toInt()   // the outer glow is transparent, so tuck it slightly
        val left = lp.x + lp.width / 2
        val top = lp.y + lp.height / 2
        val toLeft = left
        val toRight = dm.widthPixels - left
        val toTop = top
        val toBottom = dm.heightPixels - top
        val nearest = min(min(toLeft, toRight), min(toTop, toBottom))

        val horizontal = nearest == toLeft || nearest == toRight
        val start = if (horizontal) lp.x else lp.y
        val target = when (nearest) {
            toLeft -> -inset
            toRight -> dm.widthPixels - lp.width + inset
            toTop -> -inset
            else -> dm.heightPixels - lp.height + inset
        }
        if (start == target) return

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Int
                if (horizontal) lp.x = value else lp.y = value
                try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                }
            })
            start()
        }
    }

    /** Keeps the window centre on the screen so it can always be grabbed. */
    private fun clamp(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        lp.x = lp.x.coerceIn(-lp.width / 2, dm.widthPixels - lp.width / 2)
        lp.y = lp.y.coerceIn(-lp.height / 2, dm.heightPixels - lp.height / 2)
    }

    /** Size 0 hides the ring (1 px, untouchable window); 1..1000 is the diameter in pixels. */
    private fun applySize() {
        val view = ring ?: return
        val lp = params ?: return
        val newSize = prefs.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(0, MAX_SIZE)

        val centerX = lp.x + lp.width / 2
        val centerY = lp.y + lp.height / 2
        if (newSize <= 0) {
            lp.width = 1
            lp.height = 1
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            view.visibility = View.INVISIBLE
        } else {
            lp.width = newSize
            lp.height = newSize
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            view.visibility = View.VISIBLE
        }
        lp.x = centerX - lp.width / 2
        lp.y = centerY - lp.height / 2
        clamp(lp)
        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
        updateRunning()
    }

    private fun updateRunning() {
        val size = if (::prefs.isInitialized) prefs.getInt(KEY_SIZE, DEFAULT_SIZE) else DEFAULT_SIZE
        ring?.setRunning(screenOn && size > 0)
    }
}
