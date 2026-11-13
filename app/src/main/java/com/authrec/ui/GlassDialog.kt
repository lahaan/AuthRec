package com.authrec.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * A dialog in the Glass look: a tinted panel with the hairline rim over the screen blurred and
 * dimmed (Android 12's window blur; where the phone has it off, e.g. in battery saver, the panel
 * is darker instead), its buttons glass capsules with the main one lit. The system dialog's grey
 * card and text buttons didn't match the rest (owner, 2026-10-11).
 *
 * Its window covers the screen and the panel is placed in it, so that the panel can turn with the
 * controls when the phone is held upright (the screen itself stays landscape) and move above the
 * keyboard: both were wrong with a window the panel's size (QA, 2026-11-13).
 */
internal object GlassDialog {
    /** [danger]: it deletes something, and is red (text, or the fill of a [main] one). */
    class Action(val text: String, val main: Boolean = false, val danger: Boolean = false, val onClick: (() -> Unit)? = null)

    /**
     * For a dialog with a text field. The keyboard leaves a strip about 140 dp high (the screen is
     * landscape), which the panel's rows don't fit in: while it's up the buttons sit in [slot]
     * (beside the field) instead of under it, and [hide] (the explanation) goes.
     */
    class Typing(val slot: ViewGroup, val hide: List<View>)

    /** [turn]: the phone's pose as the camera's controls have it (0, or 90 / 270 held upright). */
    fun show(activity: Activity, kit: UiKit, content: View, actions: List<Action>, turn: Int = 0, typing: Typing? = null): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val blur = activity.windowManager.isCrossWindowBlurEnabled
        fun buttons(small: Boolean, shown: List<Action> = actions) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            shown.forEach { a ->
                addView(kit.button(a.text) {
                    dialog.dismiss()
                    a.onClick?.invoke()
                }.apply {
                    textSize = if (small) 13f else 14f
                    if (small) setPadding(activity.dp(13), activity.dp(8), activity.dp(13), activity.dp(8))
                    else setPadding(activity.dp(18), activity.dp(10), activity.dp(18), activity.dp(10))
                    if (a.main) kit.setOn(this, true, if (a.danger) Glass.DANGER_DEEP else Glass.ACCENT_DEEP)
                    else if (a.danger) setTextColor(Glass.DANGER)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = activity.dp(if (small) 6 else 10) })
            }
        }
        val upright = turn == 90 || turn == 270
        // Upright the panel is as wide as the screen is high: four buttons (Share, Delete, Cancel,
        // Rename) don't fit in a row there, so the last two (Cancel and the main one) go under the rest.
        val row = if (upright && actions.size > 3) LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(buttons(small = false, actions.dropLast(2)), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(buttons(small = false, actions.takeLast(2)), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        } else buttons(small = false)
        row.setPadding(activity.dp(24), activity.dp(10), activity.dp(24), 0)
        val typingRow = typing?.let { t -> buttons(small = true).also {
                it.visibility = View.GONE
                // (Its own width: a FrameLayout's default, match_parent, took the field's room.)
                t.slot.addView(it, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(activity.dp(26).toFloat(), solid = true).apply {
                baseColor = if (blur) 0x9914181C.toInt() else 0xEB14181C.toInt()
            }
            setPadding(0, 0, 0, activity.dp(14))
            isClickable = true  // a tap on the panel isn't one beside it (which closes the dialog)
            // Grows to its content, and gives way (scrolls) before the buttons do.
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
            addView(row)
        }
        // Scrolling text fades out at the panel's edges instead of being cut (as in the settings sheet).
        (content as? ScrollView)?.apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(activity.dp(28))
            isVerticalScrollBarEnabled = false  // it sat on the rim; the fade says there's more
        }
        val screen = activity.windowManager.currentWindowMetrics.bounds
        val frame = FrameLayout(activity)
        frame.addView(panel)
        frame.setOnClickListener { dialog.cancel() }
        var typingNow: Boolean? = null
        fun arrange(typingUp: Boolean) {
            if (typingUp == typingNow) return
            typingNow = typingUp
            // The keyboard comes up the screen's way round (landscape), so while it's up the panel
            // is too: above it, at the top. Otherwise it's in the middle, the way up the controls are.
            val turned = upright && !typingUp
            val across = if (turned) screen.height() else screen.width()
            panel.layoutParams = FrameLayout.LayoutParams(minOf(across - activity.dp(48), activity.dp(620)), WRAP_CONTENT,
                if (typingUp) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER)
            panel.rotation = if (turned) -turn.toFloat() else 0f
            if (typing != null && typingRow != null) {
                row.visibility = if (typingUp) View.GONE else View.VISIBLE
                typingRow.visibility = if (typingUp) View.VISIBLE else View.GONE
                typing.hide.forEach { it.visibility = if (typingUp) View.GONE else View.VISIBLE }
                panel.setPadding(0, 0, 0, activity.dp(if (typingUp) 8 else 14))
            }
        }
        arrange(false)
        // A margin around the panel keeps a tall one off the screen's edges, the bars and the keyboard.
        frame.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
            arrange(keyboard > 0)
            val margin = activity.dp(if (keyboard > 0) 6 else 16)
            v.setPadding(0, bars.top + margin, 0, maxOf(keyboard, bars.bottom) + margin)
            WindowInsets.CONSUMED
        }
        dialog.setContentView(frame, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, MATCH_PARENT)
            // The whole screen, the camera cut-out's side too (the panel is centred on the screen),
            // and under the keyboard: the insets come to the listener above. (Left to the system,
            // a dialog's window is fitted above the keyboard and the listener hears nothing of it.)
            setDecorFitsSystemWindows(false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            // The screens that open these hide the system's bars: a window of its own would bring them back.
            insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
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
    fun message(activity: Activity, kit: UiKit, title: String, text: String, actions: List<Action>, turn: Int = 0): Dialog =
        show(activity, kit, ScrollView(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(4))
                addView(kit.label(title, 18f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(kit.label(text, 14f, dim = true).apply { setLineSpacing(0f, 1.15f) },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(10) })
            })
        }, actions, turn)
}
