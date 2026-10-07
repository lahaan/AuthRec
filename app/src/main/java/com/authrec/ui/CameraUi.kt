package com.authrec.ui

import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/**
 * One of the camera screen's layouts ("Glass" or "Classic"). The activity owns all state and
 * actions and a few views both layouts place (the preview, info text, focus square, EV slider,
 * focus bar); a layout builds the rest and reflects the state in [update].
 */
internal interface CameraUi {
    val root: View
    /** Popup menus for lenses and looks attach here. */
    val lensAnchor: View
    val lookAnchor: View

    /** Brings every control in line with the activity's state. */
    fun update()

    /** Closes an open panel or sheet; true if one was open (a tap on the image then does only that). */
    fun dismissPanels(): Boolean

    /** A one-off suggestion with one action (e.g. the eDR hint); [onClose] runs either way. */
    fun showHint(text: String, action: String, onAction: () -> Unit, onClose: () -> Unit = {})

    /** About once a second with fresh camera metadata: auto exposure values and the like. */
    fun onStats() = Unit
}

/** "− value +" control (classic exposure bar, priority limits). */
internal class Stepper(kit: UiKit, onStep: (Int) -> Unit) {
    val minus: Button = kit.button("−") { onStep(-1) }
    val value: Button = kit.button("") { }.apply { isClickable = false }
    val plus: Button = kit.button("+") { onStep(1) }
    val views = listOf(minus, value, plus)
    var visible: Boolean = true
        set(v) {
            field = v
            views.forEach { it.visibility = if (v) View.VISIBLE else View.GONE }
        }

    fun addTo(row: LinearLayout) = views.forEach { row.addView(it) }
}
