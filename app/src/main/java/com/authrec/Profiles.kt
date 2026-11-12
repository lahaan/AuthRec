package com.authrec

import android.content.SharedPreferences
import com.authrec.CameraActivity.FrameAspect
import com.authrec.color.LogProfile
import com.authrec.record.VideoCodec
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named set of settings to pull out again in one tap, from the top of the Look menu (owner:
 * "whatever dials you had, lut, settings etc - you can create a profile and name it, for example
 * 'B&W Dreamy'", 2026-11-08): the look and everything on it (Adjust, eDR, the dials) and the video
 * format. Exposure and the lens stay out: they belong to the scene, not the style. So does colour
 * NR (owner, 2026-11-12: "that is up for whatever the user had on"; profiles saved with it load
 * without).
 */
internal data class Profile(
    val name: String,
    /** The Look menu's label of the look or LUT (`ViewEntry.label`). */
    val look: String,
    val lutStrength: Int,
    val saturation: Int,
    val vibrance: Int,
    val wbWarm: Int,
    val wbTint: Int,
    val edrOn: Boolean,
    val edrHi: Int,
    val edrLo: Int,
    val texture: Int,
    val clarity: Int,
    val dehaze: Int,
    val aspect: FrameAspect,
    val superpixel: Boolean,
    val fps: Int,
    val codec: VideoCodec,
    val bitrate: Int,
    val recordLook: Boolean,
    val logProfile: LogProfile,
) {
    /** The same settings, whatever the name. */
    fun sameSettings(other: Profile) = copy(name = "") == other.copy(name = "")

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("look", look)
        .put("lutStrength", lutStrength).put("saturation", saturation).put("vibrance", vibrance)
        .put("wbWarm", wbWarm).put("wbTint", wbTint)
        .put("edrOn", edrOn).put("edrHi", edrHi).put("edrLo", edrLo)
        .put("texture", texture).put("clarity", clarity).put("dehaze", dehaze)
        .put("aspect", aspect.name).put("superpixel", superpixel).put("fps", fps)
        .put("codec", codec.name).put("bitrate", bitrate).put("recordLook", recordLook)
        .put("logProfile", logProfile.name)

    companion object {
        private const val KEY = "profiles"
        const val MAX_NAME = 40

        /** The saved profiles in the order they were made; one that doesn't parse is skipped. */
        fun load(prefs: SharedPreferences): List<Profile> {
            val array = runCatching { JSONArray(prefs.getString(KEY, null) ?: return emptyList()) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i -> runCatching { fromJson(array.getJSONObject(i)) }.getOrNull() }
        }

        fun save(prefs: SharedPreferences, profiles: List<Profile>) {
            prefs.edit().putString(KEY, JSONArray(profiles.map { it.toJson() }).toString()).apply()
        }

        fun fromJson(o: JSONObject) = Profile(
            name = o.getString("name"),
            look = o.getString("look"),
            lutStrength = o.getInt("lutStrength"),
            saturation = o.getInt("saturation"),
            vibrance = o.getInt("vibrance"),
            wbWarm = o.getInt("wbWarm"),
            wbTint = o.getInt("wbTint"),
            edrOn = o.getBoolean("edrOn"),
            edrHi = o.getInt("edrHi"),
            edrLo = o.getInt("edrLo"),
            texture = o.getInt("texture"),
            clarity = o.getInt("clarity"),
            dehaze = o.getInt("dehaze"),
            aspect = FrameAspect.valueOf(o.getString("aspect")),
            superpixel = o.getBoolean("superpixel"),
            fps = o.getInt("fps"),
            codec = VideoCodec.valueOf(o.getString("codec")),
            bitrate = o.getInt("bitrate"),
            recordLook = o.getBoolean("recordLook"),
            logProfile = LogProfile.valueOf(o.getString("logProfile")),
        )
    }
}
