package com.lockin.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.lockin.security.PinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen overlay displayed when a child attempts to access restricted settings.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY window type — does not require SYSTEM_ALERT_WINDOW
 * permission. This window type is available to accessibility services and cannot be
 * dismissed by the user without entering the correct PIN.
 *
 * @param onPinResult Callback invoked with true on correct PIN, false on incorrect/cancel.
 */
class BlockedActivityOverlay(
    private val service: AccessibilityService,
    private val pinManager: PinManager,
    private val onPinResult: (Boolean) -> Unit,
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Shows the PIN prompt overlay. Idempotent — safe to call multiple times. */
    fun show() {
        if (overlayView != null) return  // Already showing

        val view = buildOverlayView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Remove FLAG_NOT_FOCUSABLE to allow PIN input
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            // Window manager may not be available (e.g., during boot)
        }
    }

    /** Dismisses the overlay. */
    fun dismiss() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* already removed */ }
            overlayView = null
        }
    }

    private fun buildOverlayView(): View {
        // Build the overlay programmatically to avoid layout inflation issues
        val container = android.widget.LinearLayout(service).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(240, 20, 20, 40))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val title = TextView(service).apply {
            text = "Restricted Area"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        val subtitle = TextView(service).apply {
            text = "This screen is restricted by LockIn.\nEnter the parent PIN to continue."
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }

        val pinInput = EditText(service).apply {
            hint = "Enter PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val submitButton = Button(service).apply {
            text = "Verify"
            setOnClickListener {
                val pin = pinInput.text.toString()
                scope.launch {
                    val correct = pinManager.verifyPin(pin)
                    if (correct) {
                        dismiss()
                        onPinResult(true)
                    } else {
                        pinInput.text.clear()
                        val remaining = pinManager.failureCount()
                        Toast.makeText(service, "Wrong PIN (attempt $remaining)", Toast.LENGTH_SHORT).show()
                        if (pinManager.isLockedOut()) {
                            val lockout = pinManager.remainingLockout()
                            Toast.makeText(service, "Locked out. Try again later.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(pinInput)
        container.addView(submitButton)

        return container
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            service.resources.displayMetrics).toInt()
}
