package com.authrec

import android.util.Base64
import com.authrec.color.CubeLut
import com.authrec.color.LogProfile
import com.authrec.color.Look
import com.authrec.color.LookParams
import com.authrec.color.Looks
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A profile as one file to pass on (owner, 2026-11-12: "make setting presets sharable, like
 * CineStill 800T, so a friend can download it, import it in their AuthRec and get the same
 * thing"): the profile and the look it names, whole. An imported LUT travels inside (gzipped
 * .cube text), a look made in the editor as its parameters; built-in looks and Log by name.
 * The video format in the file is the sender's and stays theirs (see [import]).
 * `<name>.authrec.json`: JSON so chat apps and file managers treat it as a document and Android
 * offers AuthRec to open it.
 */
internal object Presets {
    const val SUFFIX = ".authrec.json"
    const val MIME = "application/json"
    private const val VERSION = 1
    /** A preset file, and the LUT inside it unpacked, at most (a 65³ .cube is ~8 MB of text). */
    const val MAX_FILE_BYTES = 24 * 1024 * 1024
    private const val MAX_LUT_BYTES = 48 * 1024 * 1024

    /** The look a profile names, as it sits on this phone. */
    sealed class LookSource {
        object Log : LookSource()
        class BuiltIn(val name: String) : LookSource()
        class Made(val look: Look) : LookSource()
        /** [expects]: the log encoding the LUT is fed (null: ours as is). */
        class Cube(val file: File, val expects: LogProfile?) : LookSource()
    }

    fun encode(profile: Profile, look: LookSource, appVersion: String): String {
        val l = JSONObject()
        when (look) {
            LookSource.Log -> l.put("kind", "log")
            is LookSource.BuiltIn -> l.put("kind", "builtin").put("name", look.name)
            is LookSource.Made -> l.put("kind", "look").put("name", look.look.name).put("params", look.look.params.toJson())
            is LookSource.Cube -> l.put("kind", "cube").put("file", look.file.name).put("expects", look.expects?.name ?: "NONE")
                .put("gz", Base64.encodeToString(gzip(look.file.readBytes()), Base64.NO_WRAP))
        }
        return JSONObject().put("authrecPreset", VERSION).put("app", appVersion)
            .put("profile", profile.toJson()).put("look", l).toString()
    }

    /** What importing did: the profile as it is here now, and what to tell the user. */
    class Imported(val profile: Profile, val note: String)

    /**
     * Reads a preset file's [text], puts its look into [lutDir] (unless the same one is there
     * already; a different one of the same name gets a number) and returns the profile pointing
     * at it, named apart from [existing] ones. [setExpects] remembers what a LUT is fed. [here]
     * gives the profile this phone's own video format: the look is what's passed on, not the
     * sender's resolution, codec or bitrate (their 10-bit 4K on a phone that can't; the LUT is
     * fed what it expects whatever log profile is recorded). Throws with a message for the user
     * on anything that isn't a usable preset.
     */
    fun import(text: String, lutDir: File, existing: List<Profile>, here: (Profile) -> Profile,
               setExpects: (File, LogProfile?) -> Unit): Imported {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: error("not an AuthRec preset")
        val version = j.optInt("authrecPreset", 0)
        require(version >= 1) { "not an AuthRec preset" }
        require(version <= VERSION) { "made by a newer AuthRec (${j.optString("app", "?")}): update the app" }
        var profile = runCatching { Profile.fromJson(j.getJSONObject("profile")) }.getOrElse { error("its settings can't be read") }
        profile = profile.copy(name = profile.name.trim().take(Profile.MAX_NAME).ifEmpty { "Preset" })
        val l = j.optJSONObject("look") ?: error("it has no look")
        var extra = ""
        val label = when (l.optString("kind")) {
            "log" -> CameraActivity.ViewEntry.LogView.label
            "builtin" -> l.optString("name").also { n ->
                if (Looks.presets.none { it.name == n }) extra = " (its look \"$n\" isn't in this version)"
            }
            "look" -> {
                val params = LookParams.fromJson(l.getJSONObject("params"))
                val wanted = l.optString("name").trim().take(Profile.MAX_NAME).ifEmpty { profile.name }
                // The same look under the same name is used as it is; another gets a number.
                var name = wanted
                var n = 1
                while (true) {
                    val f = File(lutDir, Look.safeName(name) + Look.SUFFIX)
                    if (!f.exists()) {
                        Look(name, params).save(lutDir)
                        break
                    }
                    if (runCatching { Look.load(f).params == params }.getOrDefault(false)) break
                    name = "$wanted ${++n}"
                }
                CameraActivity.ViewEntry.UserLook(Look(name, params)).label
            }
            "cube" -> {
                val bytes = gunzip(Base64.decode(l.getString("gz"), Base64.DEFAULT))
                // The name is the sender's: only its last part, and only as a .cube.
                val base = File(l.optString("file", "preset.cube")).name.replace(Regex("[^A-Za-z0-9 _.()-]"), "_")
                    .removeSuffix(".cube").take(80).ifEmpty { "preset" }
                var file = File(lutDir, "$base.cube")
                var n = 1
                while (file.exists() && !file.readBytes().contentEquals(bytes)) file = File(lutDir, "$base (${++n}).cube")
                if (!file.exists()) {
                    val tmp = File(lutDir, ".incoming.tmp")
                    try {
                        tmp.writeBytes(bytes)
                        runCatching { CubeLut.parse(tmp) }.getOrElse { error("its LUT isn't a usable .cube (${it.message})") }
                        check(tmp.renameTo(file)) { "the LUT couldn't be saved" }
                    } finally {
                        tmp.delete()
                    }
                }
                setExpects(file, l.optString("expects").takeIf { it.isNotEmpty() && it != "NONE" }?.let { e -> runCatching { LogProfile.valueOf(e) }.getOrNull() })
                CameraActivity.ViewEntry.CubeFile(file).label
            }
            else -> error("its look is of a kind this version doesn't know")
        }
        profile = here(profile.copy(look = label))
        // The same preset again is the one that's there; another of the same name gets a number.
        existing.firstOrNull { it.sameSettings(profile) }?.let { return Imported(it, "\"${it.name}\" is already here$extra") }
        var name = profile.name
        var n = 1
        while (existing.any { it.name.equals(name, ignoreCase = true) }) name = "${profile.name} ${++n}"
        return Imported(profile.copy(name = name), "Preset \"$name\" imported$extra")
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** Unpacks at most [MAX_LUT_BYTES]: a file from someone else can claim anything. */
    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_LUT_BYTES) { "its LUT is too large" }
            }
        }
        return out.toByteArray()
    }
}
