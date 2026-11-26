package com.n1tools.box.ui.glass

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.n1tools.display.R

/**
 * Shared Apple Liquid Glass UI helper for display-core, app, and freedisplay.
 * Provides API 21+ specular glass dialogs, springy press micro-interactions,
 * and API 31+ progressive real-time window blur.
 */
object GlassUiHelper {

    private val pressDownInterpolator = DecelerateInterpolator()
    private val releaseInterpolator = OvershootInterpolator(1.4f)

    /**
     * Attaches an Apple-style springy press-scale micro-interaction (0.965f)
     * without intercepting click events.
     */
    fun applyPressEffect(vararg views: View?) {
        for (view in views) {
            view ?: continue
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.965f)
                            .scaleY(0.965f)
                            .alpha(0.88f)
                            .setDuration(100L)
                            .setInterpolator(pressDownInterpolator)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(180L)
                            .setInterpolator(releaseInterpolator)
                            .start()
                    }
                }
                false
            }
        }
    }

    /**
     * Enables real-time backdrop blur on API 31+ (Android 12+) windows when supported.
     */
    fun applyWindowBlurIfSupported(window: Window?, blurRadiusPx: Int = 36) {
        if (window == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val lp = window.attributes
                lp.blurBehindRadius = blurRadiusPx
                window.attributes = lp
            } catch (_: Throwable) {
                // Gracefully ignore on devices/ROMs where window blur is disabled
            }
        }
    }

    fun isLandscape(context: Context): Boolean {
        val cfg = context.resources.configuration
        if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) return true
        val dm = context.resources.displayMetrics
        return dm.widthPixels > dm.heightPixels
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Creates a unified Apple Glass dialog with 24dp rounded corners, specular rim,
     * pill action buttons, and dynamic width adaptation for portrait/landscape.
     */
    fun createGlassDialog(
        context: Context,
        title: String,
        contentView: View,
        positiveText: String? = null,
        onPositive: (() -> Unit)? = null,
        negativeText: String? = null,
        onNegative: (() -> Unit)? = null,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        cancelable: Boolean = true,
        portraitMaxWidthDp: Int = 440,
        landscapeMaxWidthDp: Int = 620,
        autoDismissOnPositive: Boolean = true
    ): AlertDialog {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_dialog)
            setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 18))
        }

        val tvTitle = TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.glass_text_primary))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(context, 12))
        }
        root.addView(tvTitle)

        // Content wrapper with weight=1 so long content scrolls inside without pushing buttons off-screen
        val contentLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        root.addView(contentView, contentLp)

        var dialogRef: AlertDialog? = null

        if (positiveText != null || negativeText != null || neutralText != null) {
            val btnBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 16), 0, 0)
            }

            if (neutralText != null) {
                val btnNeutral = createPillButton(context, neutralText, accent = false) {
                    onNeutral?.invoke()
                }
                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                btnBar.addView(btnNeutral)
                btnBar.addView(spacer)
            }

            if (negativeText != null) {
                val btnNeg = createPillButton(context, negativeText, accent = false) {
                    dialogRef?.dismiss()
                    onNegative?.invoke()
                }
                (btnNeg.layoutParams as LinearLayout.LayoutParams).marginEnd =
                    if (positiveText != null) dp(context, 10) else 0
                btnBar.addView(btnNeg)
            }

            if (positiveText != null) {
                val btnPos = createPillButton(context, positiveText, accent = true) {
                    if (autoDismissOnPositive) {
                        dialogRef?.dismiss()
                    }
                    onPositive?.invoke()
                }
                btnBar.addView(btnPos)
            }

            root.addView(btnBar)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .setCancelable(cancelable)
            .create()
        dialogRef = dialog

        dialog.setOnShowListener {
            val w = dialog.window ?: return@setOnShowListener
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            applyWindowBlurIfSupported(w, 40)

            val dm = context.resources.displayMetrics
            val landscape = isLandscape(context)
            val ratio = if (landscape) 0.88f else 0.90f
            val maxPx = dp(context, if (landscape) landscapeMaxWidthDp else portraitMaxWidthDp)
            val targetWidth = (dm.widthPixels * ratio).toInt().coerceAtMost(maxPx)
            w.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        return dialog
    }

    /**
     * Convenience helper for simple text-message glass dialogs.
     */
    fun createGlassMessageDialog(
        context: Context,
        title: String,
        message: CharSequence,
        positiveText: String? = "确定",
        onPositive: (() -> Unit)? = null,
        negativeText: String? = null,
        onNegative: (() -> Unit)? = null,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        cancelable: Boolean = true
    ): AlertDialog {
        val tv = TextView(context).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, R.color.glass_text_secondary))
            textSize = 14f
            setLineSpacing(dp(context, 4).toFloat(), 1.15f)
        }
        val scroll = android.widget.ScrollView(context).apply {
            addView(tv)
        }
        return createGlassDialog(
            context = context,
            title = title,
            contentView = scroll,
            positiveText = positiveText,
            onPositive = onPositive,
            negativeText = negativeText,
            onNegative = onNegative,
            neutralText = neutralText,
            onNeutral = onNeutral,
            cancelable = cancelable
        )
    }

    private fun createPillButton(
        context: Context,
        label: String,
        accent: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (accent) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            gravity = Gravity.CENTER
            setBackgroundResource(if (accent) R.drawable.bg_glass_pill_accent else R.drawable.bg_glass_pill)
            setPadding(dp(context, 13), dp(context, 8), dp(context, 13), dp(context, 8))
            minimumHeight = dp(context, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            applyPressEffect(this)
            setOnClickListener { onClick() }
        }
    }
}
