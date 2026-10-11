package com.authrec

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView

/**
 * "About & licences": AuthRec is GPL-3.0 because it bundles x265 and x264 (GPL-2.0-or-later) for
 * Compress; the GPL asks interactive programs to show the licence, the lack of warranty and where
 * the source is. SVT-AV1's BSD-3-Clause-Clear and AOM patent licences ask for their texts to
 * come with the binary: all of them are in assets/licences, shown by "Licence texts".
 */
object About {
    fun show(activity: Activity) {
        val version = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: ""
        AlertDialog.Builder(activity)
            .setTitle("AuthRec $version")
            .setMessage(
                "Free software under the GNU General Public License, version 3 (GPL-3.0): you may use, share and " +
                "change it under its terms. It comes with ABSOLUTELY NO WARRANTY.\n" +
                "Source code: github.com/lahaan/AuthRec\n\n" +
                "Compress uses:\n" +
                "• x265 4.2, the HEVC encoder by MulticoreWare, Inc. and contributors (x265.org), GPL-2.0 or later\n" +
                "• x264, the H.264 encoder by the VideoLAN project and contributors (videolan.org), GPL-2.0 or later\n" +
                "• SVT-AV1 4.2.0, the AV1 encoder © Alliance for Open Media and contributors, BSD-3-Clause-Clear " +
                "with the Alliance for Open Media Patent License 1.0\n" +
                "Their source is included in AuthRec's.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Licence texts") { _, _ -> showTexts(activity) }
            .show()
    }

    private fun showTexts(activity: Activity) {
        val text = runCatching {
            activity.assets.list("licences").orEmpty().sorted().joinToString("\n\n\n") { name ->
                val title = name.removeSuffix(".txt").substringAfter('-').replace('-', ' ')
                "==== $title ====\n\n" + activity.assets.open("licences/$name").bufferedReader().use { it.readText() }
            }
        }.getOrElse { "Couldn't read the licence texts: ${it.message}" }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val view = ScrollView(activity).apply {
            addView(TextView(activity).apply {
                this.text = text
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(pad, pad, pad, pad)
            })
        }
        AlertDialog.Builder(activity)
            .setTitle("Licences")
            .setView(view)
            .setPositiveButton("OK", null)
            .show()
    }
}
