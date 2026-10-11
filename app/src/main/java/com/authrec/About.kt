package com.authrec

import android.app.Activity
import android.app.AlertDialog

/**
 * "About & licences": AuthRec is GPL-3.0 because it bundles x265 (GPL-2.0-or-later) for Compress;
 * the GPL asks interactive programs to show the licence, the lack of warranty and where the
 * source is.
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
                "Compress uses x265 4.2, the HEVC encoder by MulticoreWare, Inc. and contributors, under the GNU " +
                "General Public License, version 2 or later (x265.org). Its source is included in AuthRec's.")
            .setPositiveButton("OK", null)
            .show()
    }
}
