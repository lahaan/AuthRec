package com.authrec.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * A dialog in the Glass look for screens without the camera image behind them (Recordings): a
 * tinted panel with the hairline rim over the screen blurred and dimmed (Android 12's window blur;
 * where the phone has it off, e.g. in battery saver, the panel is darker instead), its buttons
 * glass capsules with the main one lit. The system dialog's grey card and text buttons didn't
 * match the rest (owner, 2026-10-11).
 */
internal object GlassDialog {
    class Action(val text: String, val main: Boolean = false, val onClick: (() -> Unit)? = null)

    fun show(activity: Activity, kit: UiKit, content: View, actions: List<Action>): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val blur = activity.windowManager.isCrossWindowBlurEnabled
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(activity.dp(26).toFloat(), solid = true).apply {
                baseColor = if (blur) 0x9914181C.toInt() else 0xEB14181C.toInt()
            }
            setPadding(0, 0, 0, activity.dp(14))
            // Grows to its content, and gives way (scrolls) before the buttons do.
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(activity.dp(24), activity.dp(10), activity.dp(24), 0)
                actions.forEach { a ->
                    addView(kit.button(a.text) {
                        dialog.dismiss()
                        a.onClick?.invoke()
                    }.apply {
                        textSize = 14f
                        setPadding(activity.dp(18), activity.dp(10), activity.dp(18), activity.dp(10))
                        if (a.main) kit.setOn(this, true)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = activity.dp(10) })
                }
            })
        }
        // Scrolling text fades out at the panel's edges instead of being cut (as in the settings sheet).
        (content as? ScrollView)?.apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(activity.dp(28))
            isVerticalScrollBarEnabled = false  // it sat on the rim; the fade says there's more
        }
        // The window is clear: a margin around the panel keeps a tall one off the screen's edges.
        dialog.setContentView(FrameLayout(activity).apply {
            setPadding(0, activity.dp(16), 0, activity.dp(16))
            addView(panel)
        })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val screen = activity.windowManager.currentWindowMetrics.bounds
            setLayout(minOf(screen.width() - activity.dp(48), activity.dp(620)), WRAP_CONTENT)
            setDimAmount(if (blur) 0.35f else 0.6f)
            if (blur) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply { blurBehindRadius = activity.dp(24) }
            }
        }
        dialog.show()
        kit.reveal(panel)
        return dialog
    }

    /** A title and a message (scrolls when long), for the simple cases. */
    fun message(activity: Activity, kit: UiKit, title: String, text: String, actions: List<Action>): Dialog =
        show(activity, kit, ScrollView(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(4))
                addView(kit.label(title, 18f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(kit.label(text, 14f, dim = true).apply { setLineSpacing(0f, 1.15f) },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(10) })
            })
        }, actions)
}
