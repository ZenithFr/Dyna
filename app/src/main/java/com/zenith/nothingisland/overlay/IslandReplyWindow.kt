package com.zenith.nothingisland.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Small focusable overlay used for notification inline reply. It is a separate short-lived window
 * under the island so the main pill can stay non-focusable and never steal input from the app.
 *
 * If the system refuses the window or the IME (some OEM builds restrict accessibility-overlay
 * focus), the caller falls back to firing the notification action directly.
 */
class IslandReplyWindow(private val serviceContext: Context) {

    private val windowManager: WindowManager? =
        serviceContext.getSystemService(WindowManager::class.java)

    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null

    val isShowing: Boolean get() = root != null

    /** @return true when the window was attached. */
    fun show(anchorBottomPx: Int, hintText: CharSequence, onSubmit: (String) -> Unit): Boolean {
        hide()
        val manager = windowManager ?: return false

        val density = serviceContext.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).roundToInt()

        val edit = EditText(serviceContext).apply {
            setHint(hintText)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(110, 255, 255, 255))
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(0, 0, 0, 0)
            textSize = 14f
        }

        val send = TextView(serviceContext).apply {
            text = "SEND"
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 11f
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = roundedBackground(dp(14f), Color.argb(28, 255, 255, 255))
        }

        val row = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(9f), dp(10f), dp(9f))
            background = roundedBackground(dp(22f), Color.parseColor("#FF070707")).apply {
                setStroke(dp(1f), Color.argb(40, 255, 255, 255))
            }
            addView(
                edit,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                send,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8f) },
            )
        }

        val screenWidth = manager.currentWindowMetrics.bounds.width()
        val width = min(screenWidth - dp(32f), dp(380f))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - width) / 2
            y = anchorBottomPx + dp(6f)
            softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "NothingIslandReply"
        }

        fun submit() {
            val reply = edit.text?.toString()?.trim().orEmpty()
            if (reply.isEmpty()) return
            onSubmit(reply)
            hide()
        }

        edit.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isSend) {
                submit()
                true
            } else {
                false
            }
        }
        send.setOnClickListener { submit() }
        row.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hide()
                true
            } else {
                false
            }
        }
        row.isFocusableInTouchMode = true

        return runCatching {
            manager.addView(row, params)
            root = row
            edit.requestFocus()
            handler.post {
                val imm = serviceContext.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
            true
        }.onFailure {
            Log.w(TAG, "Unable to show inline reply window", it)
            root = null
        }.getOrDefault(false)
    }

    fun hide() {
        val view = root ?: return
        root = null
        val imm = serviceContext.getSystemService(InputMethodManager::class.java)
        runCatching { imm?.hideSoftInputFromWindow(view.windowToken, 0) }
        runCatching { windowManager?.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "Reply window was already detached", it) }
    }

    private fun roundedBackground(radiusPx: Int, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }

    private companion object {
        const val TAG = "IslandReply"
    }
}
