package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/**
 * A small draggable launcher bubble floating above the head unit's home screen, so DiPlay can be
 * started on OEM decks (Ford/Lincoln SYNC+) whose fixed launcher has no app list at all. Mirrors
 * the box-app icons those decks are used to: drag to move, tap to launch DiPlay, long-press to
 * turn the bubble off. Needs "display over other apps" (SYSTEM_ALERT_WINDOW).
 */
internal object HomeScreenBubble {
    const val TAG = "DiPlay-HomeBubble"
    private const val SIZE_DP = 56f
    private const val ICON_DP = 34f
    private const val MARGIN_DP = 16f
    private const val SHOW_DELAY_MILLIS = 2_000L
    private const val LONG_PRESS_MILLIS = 600L

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null

    fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Shows the bubble shortly after boot, unless overlays are not permitted or it was turned off. */
    fun scheduleShow(context: Context) {
        val app = context.applicationContext
        bootShowRunnable?.let { main.removeCallbacks(it) }
        val runnable = Runnable {
            if (!enabled(app) || !permitted(app)) return@Runnable
            show(app)
        }
        bootShowRunnable = runnable
        main.postDelayed(runnable, SHOW_DELAY_MILLIS)
    }

    private var bootShowRunnable: Runnable? = null

    val shown: Boolean get() = root != null

    /** Adds the bubble. Main thread. */
    fun show(context: Context): Boolean {
        if (root != null) return true
        if (!permitted(context)) return false
        val windows = context.getSystemService(WindowManager::class.java) ?: return false
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val size = (SIZE_DP * metrics.density).toInt()
        val margin = (MARGIN_DP * metrics.density).toInt()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, screenWidth - size - margin).coerceIn(0, (screenWidth - size).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, screenHeight / 2 - size / 2).coerceIn(0, (screenHeight - size).coerceAtLeast(0))
            title = "DiPlay launcher"
        }
        val iconSize = (ICON_DP * metrics.density).toInt()
        val icon = ImageView(context).apply {
            runCatching { context.packageManager.getApplicationIcon(context.packageName) }
                .getOrNull()
                ?.let { setImageDrawable(it) }
        }
        val bubble = FrameLayout(context).apply {
            setBackgroundColor(0xCC1C1C1E.toInt())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            addView(
                icon,
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER),
            )
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = (startX + dx).toInt().coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
                        runCatching { windows.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> when {
                    dragging -> prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    event.eventTime - event.downTime >= LONG_PRESS_MILLIS -> {
                        setEnabled(context, false)
                        hide()
                        Log.i(TAG, "bubble turned off by long-press")
                        android.widget.Toast.makeText(context, "DiPlay launcher bubble off", ToastLength).show()
                    }
                    else -> launchDiPlay(context)
                }
            }
            true
        }
        return try {
            windows.addView(bubble, params)
            root = bubble
            Log.i(TAG, "bubble shown ${size}x${size} at ${params.x},${params.y}")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "bubble failed", error)
            false
        }
    }

    private fun launchDiPlay(context: Context) {
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { context.startActivity(launch) }
            .onFailure { Log.w(TAG, "bubble could not launch DiPlayActivity", it) }
    }

    /** Removes the bubble. Main thread. */
    fun hide() {
        val view = root ?: return
        root = null
        runCatching { view.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        Log.i(TAG, "bubble hidden")
    }

    private const val ToastLength = android.widget.Toast.LENGTH_SHORT
    private const val PREFS = "diplay_home_bubble"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_ENABLED = "enabled"
}
