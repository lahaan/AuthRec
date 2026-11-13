package com.authrec.gl

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.authrec.EventLog
import com.authrec.camera.FrameMeta
import com.authrec.camera.SensorInfo
import com.authrec.color.ColorMath
import com.authrec.color.CubeLut
import com.authrec.color.LogProfile
import com.authrec.color.ViewLut
import com.authrec.color.Primaries
import com.authrec.record.Recorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Owns the GL thread: runs the compute pipeline on each RAW frame at full sensor resolution,
 * draws the LUT view to the preview surface and, while recording, the log (or baked LUT) image
 * into the encoder's input surface.
 *
 * Preview-only: frames that arrive while the GPU is busy are skipped so the preview stays live.
 * Recording: every frame is processed in order; a frame is only lost if the camera itself has
 * to drop it, which shows up as a timestamp gap and is counted.
 */
class Renderer(
    private val sensor: SensorInfo,
    private val reader: ImageReader,
    /** The metadata of the capture with this sensor timestamp (or the latest). */
    private val metaSource: (Long) -> FrameMeta?,
    /** Whether the capture result with this sensor timestamp has arrived (see [pending]). */
    private val metaArrived: (Long) -> Boolean,
    /** Log-average linear brightness of the ISP's rendering (NaN if unknown); drives auto gain. */
    private val ispBrightness: () -> Float,
    /** Recordings will be 10-bit: the main EGL config is made for that (see EglCore). */
    private val tenBitSurfaces: Boolean,
    private val onStats: (Stats) -> Unit,
) {
    /** [gpuMs]: thread time per frame (submission + any blocking on the GPU or encoder). */
    /** [viewFps]: preview frames actually drawn per second (fewer than [fps] when the GL thread is behind). */
    data class Stats(val gpuMs: Double, val fps: Double, val meta: FrameMeta?, val droppedWhileRecording: Int,
                     val viewFps: Double)

    /** Settings read on the GL thread each frame; written from the UI thread. */
    /**
     * Whether the GPU can hand the encoder 10-bit frames; false once known otherwise (the
     * activity then records the 8-bit variant). The Xiaomi 12 (Adreno 730, Android 14) has
     * 10-bit window configs but none recordable: 10-bit recording failed at once with "no 10-bit
     * recordable EGL config", and through a non-recordable one the encoder failed on the first
     * frame (2026-10-11).
     */
    @Volatile var tenBitRecording: Boolean = true

    @Volatile var profile: LogProfile = LogProfile.APPLE_LOG
    /**
     * Gain before the log curve, automatic per lens. Phone ISPs expose RAW very differently per
     * lens (Xiaomi 14: main ~2.2 EV under its own rendering, telephoto/front ~0.6 EV), so the
     * auto part matches our brightness to the ISP's rendering of the same moment (+[AUTO_BIAS_EV],
     * fitted against ISP JPEGs in daylight). The log curves keep the sensor's clip point either way.
     */
    @Volatile var exposureOffsetEv: Float = 0f
        private set
    /**
     * What the activity decides about exposure in one go (applyExposure): [offsetEv] is
     * [exposureOffsetEv], and so on by name. One object, taken at the start of each frame, because
     * the parts only make sense together: an EVO step of the sensor arrives as a grant and the
     * digital gain that cancels it, and a frame that got one without the other (the grant is read
     * when the frame starts, the gain when it's drawn, the UI thread writes in between) came out a
     * sixth of a stop darker or brighter than its neighbours (X14, 2026-11-13).
     */
    class Exposure(val offsetEv: Float = 0f, val sensorCompEv: Float = 0f, val aeCompEv: Float = 0f,
                   val evoGrantedEv: Float = 0f, val evoUserEv: Float = 0f, val evoSensorAllowed: Boolean = false)
    @Volatile var exposure = Exposure()
    private fun takeExposure() {
        val x = exposure
        exposureOffsetEv = x.offsetEv
        sensorCompEv = x.sensorCompEv
        aeCompEv = x.aeCompEv
        evoGrantedEv = x.evoGrantedEv
        evoUserEv = x.evoUserEv
        evoSensorAllowed = x.evoSensorAllowed
    }
    /** Holds the auto gain where it is (manual exposure mode). */
    @Volatile var autoGainFrozen: Boolean = false
    /** Log-average linear brightness of the latest RAW frame (green), and the share of near-clipped samples. */
    @Volatile var rawBrightness: Float = Float.NaN
        private set
    @Volatile var rawClipFraction: Float = 0f
        private set
    /** ISO × exposure ns of the frame [rawBrightness] was measured on. */
    @Volatile var rawExposure: Double = Double.NaN
        private set
    /**
     * AE Priority: the exposure (ISO × ns) the picture should look like, or NaN (off). Each frame
     * eases towards it and is evened out by digital gain against that frame's own exposure, so the
     * loop's shutter/ISO changes never show as brightness steps, only as gradual noise changes
     * (owner: Priority was choppy).
     */
    @Volatile var exposureTarget: Double = Double.NaN
    private var shownExposure = Double.NaN
    private var exposureComp = 1f

    /**
     * AE compensation asked of the camera, in EV (AE Auto / Locked; 0 in the modes that don't use
     * it). The camera takes a few frames to get there, and how many is its business: the picture
     * is evened out against each frame's own exposure meanwhile ([compLagEv]), so a step of real
     * exposure (EVO's, in the phone's ⅙ or ⅓ stops) shows as a gradual change of noise, never as
     * a brightness step, and a drag of the EV slider no longer jumps and settles.
     */
    @Volatile var aeCompEv = 0f
        private set
    /** The compensation the frames really have so far (NaN: not started), on its way from [compFrom] to [compTo]. */
    private var compShown = Float.NaN
    private var compFrom = 0f
    private var compTo = 0f
    /** ISO × ns of the last frame before the change, and seconds since it. */
    private var compRef = Double.NaN
    private var compSince = 0f
    private var compProgress = 1f
    private var lastFrameExposure = Double.NaN
    /** EV of digital gain standing in for compensation the sensor hasn't delivered yet. */
    @Volatile private var compLagEv = 0f
    private var frameStepEv = 0f
    /** EVO asked for the change of compensation that's about to arrive (not the user's EV slider). */
    private var evoAsked = false
    /**
     * The real exposure EVO has as the frames show it, in EV: every change of ISO × shutter after
     * a request counts as its doing, until the exposure has stood still for a moment (1.2–3 s).
     * Measured, not assumed, because a camera metering a clipped scene moves further than it's
     * asked (X14: asked −1.2, went −2.0, and the picture came out 0.8 EV darker for it,
     * 2026-11-12). The picture is evened out against this ([evoCorrectionEv]), so a step never
     * shows. With no request left it goes back to nothing in a couple of seconds.
     */
    private var evoDelivered = 0f
    /** Seconds since the user's own EV last changed, and the value it had. */
    private var evoUserQuiet = 10f
    /** This frame is the first after a break of half a second or more (a new session). */
    private var frameBreak = false
    private var evoUserSeen = 0f
    private var evoWindow = 0f
    private var evoSinceAsk = 10f
    private var evoStillFrames = 0
    /** EV of digital gain making up the difference between what EVO asked of the sensor and what it got. */
    @Volatile private var evoCorrectionEv = 0f

    private fun updateCompLag(frameExposure: Double, dt: Float) {
        val asked = aeCompEv
        if (compShown.isNaN()) {
            // A new session starts with the compensation in place.
            compShown = asked
            compTo = asked
        } else if (asked != compTo) {
            compFrom = compShown
            compTo = asked
            compRef = lastFrameExposure
            compSince = 0f
            compProgress = 0f
            // EVO's own steps are evened out by what the sensor is measured to do (updateEvo), not here.
            if (evoAsked) compShown = asked
            evoAsked = false
        }
        if (compShown != compTo) {
            compSince += dt
            val span = compTo - compFrom
            var p = 0f
            if (!frameExposure.isNaN() && !compRef.isNaN() && compRef > 0 && abs(span) > 1e-3f) {
                p = (log2((frameExposure / compRef).toFloat()) / span).coerceIn(0f, 1f)
            }
            // A camera at the end of its range never gets there: then cross over slowly instead.
            val t = ((compSince - 0.6f) / 0.8f).coerceIn(0f, 1f)
            compProgress = maxOf(compProgress, p, t * t * (3f - 2f * t))
            compShown = if (compProgress >= 1f) compTo else compFrom + span * compProgress
        }
        compLagEv = compTo - compShown
        // How far this frame's exposure moved from the one before.
        frameStepEv = if (!frameExposure.isNaN() && !lastFrameExposure.isNaN() && lastFrameExposure > 0)
            log2((frameExposure / lastFrameExposure).toFloat()).coerceIn(-3f, 3f) else 0f
        lastFrameExposure = frameExposure
    }

    // ---- Short excursions of the camera's own exposure ----

    /**
     * EV of digital gain holding the picture where it was while the camera's exposure is briefly
     * somewhere else. Phones' AE makes short trips: the X14 shortens the shutter from 1/100 to
     * 1/120 for 4–10 frames every second or two and raises ISO to match, which shows nothing,
     * until ISO is at its limit (3200) and can't: then those frames are 0.26 EV darker, sharply,
     * and back (the owner's clips at dusk, 2026-11-13: "the exposure just goes wonky for
     * literally no reason"; reproduced against a screen fading down, where the 2.6x sits at ISO
     * 3200 and 1/100 over a stop and a half of light).
     *
     * So a change of ISO × shutter of up to [STEADY_MAX] EV is evened out for its first
     * [STEADY_FRAMES] frames (half a second): if the exposure comes back, nothing was seen. If it
     * stays, it was meant, and the picture goes there gently; a larger change is followed at
     * once, quicker the further off it is. Only in AE Auto and Locked (Priority has its own
     * evening, [exposureTarget]), and not while the user's EV or a step of EVO's is on its way
     * to the sensor: those changes are expected and evened out by their own counts.
     */
    @Volatile private var steadyEv = 0f
    /** log2 of the exposure (ISO × ns) the picture is shown as (NaN: not started). */
    private var steadyRef = Float.NaN
    private var steadyHeld = 0

    private fun updateSteady(frameExposure: Double, own: Boolean) {
        if (frameExposure.isNaN() || !exposureTarget.isNaN() || !evoSensorAllowed) {
            steadyRef = Float.NaN
            steadyEv = 0f
            return
        }
        // Another frame's exposure says nothing about this one: as it was.
        if (!own) return
        val e = log2(frameExposure.toFloat())
        if (steadyRef.isNaN() || frameBreak) steadyRef = e
        val off = e - steadyRef
        val expected = evoWindow > 0f || evoUserQuiet < 1f || (!compShown.isNaN() && compShown != compTo)
        when {
            // The change under way has its own evening, so none of it is taken up here (both at once
            // made a blip of the step's size); what was being held is let go over a few frames.
            expected -> {
                steadyRef = e + steadyEv * 0.7f
                steadyHeld = STEADY_FRAMES
            }
            abs(off) < 0.02f -> {
                steadyRef = e
                steadyHeld = 0
            }
            abs(off) <= STEADY_MAX && steadyHeld < STEADY_FRAMES -> steadyHeld++
            else -> {
                steadyRef += off * (abs(off) * 0.6f).coerceIn(0.12f, 0.7f)
                steadyHeld = STEADY_FRAMES
            }
        }
        steadyEv = steadyRef - e
    }

    // ---- EVO: the Exposure Value Optimizer (see Evo) ----

    /** Following the scene, holding (exposure locked, a photo burst under way) or off. */
    @Volatile var evoMode: Evo.Mode = Evo.Mode.OFF
    /** Photo mode: EVO may move several times as fast (Evo.quick). */
    @Volatile var evoQuick = false
    /** How the view shows each scene brightness (Evo.toneOfLook), and the scene brightness a clip keeps as white (Evo.update). */
    @Volatile var evoTone: Evo.Tone = Evo.Tone(FloatArray(89) { (it / 66f).coerceAtMost(1f) })
    @Volatile var evoRecordedWhite = 2f
    /**
     * What of EVO's real-exposure request is in the AE compensation the camera was asked for, in EV
     * (the activity rounds it to the phone's steps and fits it in the range beside the user's EV).
     */
    @Volatile var evoGrantedEv = 0f
        private set
    /** The user's own EV: taken out of what EVO looks at, so their bias stays on top of its choice. */
    @Volatile var evoUserEv = 0f
        private set
    /** EVO may ask for real exposure (AE Auto, kept in Locked). Priority keeps to the user's limits: the view only. */
    @Volatile var evoSensorAllowed = false
        private set
    /** 0 gentle, 1 balanced, 2 strong (Evo.strength). */
    @Volatile var evoStrength = 1
    /** Called on the GL thread when the real exposure EVO asks for changes (EV, whole AE steps). */
    @Volatile var onEvoSensor: ((Float) -> Unit)? = null
    /** For the screen: EVO's shift of the view and of real exposure (EV, as measured), and the share of the picture blown out with and without it. */
    @Volatile var evoViewEv = 0f
        private set
    @Volatile var evoSensorEv = 0f
        private set
    @Volatile var evoBlown = 0f
        private set
    @Volatile var evoBlownWithout = 0f
        private set
    /** The sensor's usable range at this ISO in stops, clip point over read noise (NaN: no frame yet). */
    @Volatile var sensorRangeStops = Float.NaN
        private set
    /** Debug (adb evodump=true): log EVO's averaged measure once, for the desktop simulation. */
    @Volatile var evoDump = false
    /** Debug (adb evodim=0..1): the weight of EVO's midtone guard (Evo.dimWeight); negative = its default. */
    @Volatile var evoDimWeight = -1f
    /** Debug (adb evoverbose=true): log EVO's state twice a second. */
    @Volatile var evoVerbose = false
    /** Debug (adb explog=frames): log every frame's exposure and each part of the gain for that many frames. */
    @Volatile var expLogFrames = 0
    private var expLogPrev = 0L
    /** Debug (adb evostep=EV, with explog): EVO asks this of the sensor and takes it back, every 75 logged frames. */
    @Volatile var evoTestStep = 0f
    private var evoLogged = 0
    private val evo = Evo()
    private val evoMeasure = Evo.Measure()
    private val gridX = FloatArray(GRID_COLS * GRID_ROWS)
    private val gridShade = FloatArray(GRID_COLS * GRID_ROWS)

    private fun updateEvo(meta: FrameMeta?, dt: Float) {
        val mode = evoMode
        val noise = meta?.noise?.getOrNull(1)?.takeIf { it > 0f }?.let { sqrt(it) } ?: 0f
        if (meta != null) sensorRangeStops = Evo.rangeStops(noise)
        if (!evoSensorAllowed && (evo.sensorEv != 0f || evoDelivered != 0f)) {
            evo.dropSensor()
            evoDelivered = 0f
            evoWindow = 0f
        }
        // EVO's real exposure as the frames have it.
        evoSinceAsk += dt
        evoStillFrames = if (abs(frameStepEv) > 0.01f) 0 else evoStillFrames + 1
        val userEv = evoUserEv
        if (userEv != evoUserSeen) {
            evoUserSeen = userEv
            evoUserQuiet = 0f
        }
        // A break in the frames (the camera reopened): its AE finds its feet again, which isn't EVO's doing either.
        if (frameBreak) evoUserQuiet = 0f
        evoUserQuiet += dt
        // While the user's own EV is on its way to the sensor the frames can't say whose change is
        // whose: EVO's share is then taken as asked, and it asks nothing new. (Measured through a
        // drag of the EV slider, the user's stops counted as EVO's and the picture was evened out
        // against them: "it started underexposing", owner 2026-11-12.)
        val userMoving = evoUserQuiet < 1f || (!compShown.isNaN() && compShown != compTo)
        if (userMoving) {
            evoWindow = 0f
            evoDelivered = evoGrantedEv
        } else if (evoWindow > 0f) {
            evoDelivered = (evoDelivered + frameStepEv).coerceIn(-3f, 3f)
            // Open while the camera is still on its way (it overshoots and comes back), 3 s at most.
            if ((evoSinceAsk >= 1.2f && evoStillFrames >= 6) || evoSinceAsk >= 3f) evoWindow = 0f
        } else if (evoGrantedEv == 0f) {
            evoDelivered *= kotlin.math.exp(-dt / 2f)
            if (abs(evoDelivered) < 0.005f) evoDelivered = 0f
        }
        // Whatever else moved the exposure meanwhile (a pan into the light, the user's EV), EVO's
        // share is somewhere between nothing (a camera at the end of its range) and well past what
        // was asked (X14: 1.7× in a clipped scene). Kept in there, a wrong count costs the picture
        // no more than the request itself, and is gone when the request is.
        val granted = evoGrantedEv
        val far = if (granted < 0f) min(granted * 2f, granted - 0.5f) else max(granted * 2f, granted + 0.5f)
        evoDelivered = if (granted == 0f) evoDelivered.coerceIn(-0.5f, 0.5f) else evoDelivered.coerceIn(min(far, 0f), max(far, 0f))
        evoCorrectionEv = granted - evoDelivered
        if (meta == null || (mode == Evo.Mode.OFF && evo.viewEv == 0f && evo.sensorEv == 0f)) {
            evoViewEv = evo.viewEv
            evoSensorEv = evoDelivered
            return
        }
        if (mode == Evo.Mode.RUN) evoMeasure.fill(gridX, gridShade, GRID_COLS, GRID_ROWS)
        // The picture's gain without EVO's shift and the user's EV; value × gain has middle grey at 0.18.
        val gain = currentGain() / 2f.pow(evo.viewEv + evoUserEv)
        // What the user's EV leaves of the AE compensation range (their EV past it is digital gain).
        val compLo = sensor.evRange.lower * sensor.evStep
        val compHi = sensor.evRange.upper * sensor.evStep
        val userComp = if (compLo <= compHi) userEv.coerceIn(compLo, compHi) else 0f
        val lo = if (evoSensorAllowed) compLo - userComp else 0f
        val hi = if (evoSensorAllowed) compHi - userComp else 0f
        // Brightening is for scenes with light to spare: a dim room stays as dim as the meter and the night limit left it.
        val push = meta.iso * gain < EVO_PUSH_MAX_ISO
        val actual = evoDelivered
        // The last request has shown its effect (the exposure has stopped moving), or isn't going to.
        val settled = !userMoving && ((evoSinceAsk >= 0.3f && evoStillFrames >= 4) || evoSinceAsk >= 1.2f)
        val asked = evo.sensorEv
        evo.strength = evoStrength
        evo.quick = evoQuick
        evo.dimWeight = if (evoDimWeight >= 0f) evoDimWeight else Evo.DIM_WEIGHT
        val tone = evoTone
        evo.update(evoMeasure, gain, tone, evoRecordedWhite, noise, actual, settled, sensor.evStep, lo, hi, push, mode, dt)
        if (evo.sensorEv != asked) {
            evoAsked = true
            evoWindow = 1.2f
            evoSinceAsk = 0f
            evoStillFrames = 0
            onEvoSensor?.invoke(evo.sensorEv)
        }
        if (evoDump) {
            evoDump = false
            android.util.Log.i("AuthRec", "EVOHIST gain=%.4f white=%.4f %s".format(gain, tone.white, evo.dump()))
        }
        if (evoVerbose && ++evoLogged % 15 == 0) android.util.Log.i("AuthRec",
            "EVO view %+.2f (wants %+.2f) sensor %+.2f (wants %+.2f, granted %+.2f, has %+.2f, evened %+.2f) blown %.1f%% -> %.1f%% surfaces %.1f%% sources %.1f%% gain %.2f white %.1f iso %d".format(
                evo.viewEv, evo.viewWanted, evo.sensorEv, evo.sensorWanted, evoGrantedEv, evoDelivered, evoCorrectionEv, evo.blownWithout * 100, evo.blownNow * 100,
                evoMeasure.surfaces * 100, evoMeasure.sources * 100, gain, tone.white, meta.iso))
        evoViewEv = evo.viewEv
        evoSensorEv = evoDelivered
        evoBlown = evo.blownNow
        evoBlownWithout = evo.blownWithout
    }
    /**
     * Share of the image the view shows blown out although the RAW still holds it (more than
     * ~3.5 stops over middle grey after the gain, sensor not clipped): what eDR brings back.
     */
    @Volatile var recoverableClipFraction: Float = 0f
        private set
    /** Region (0..1 image coords: left, top, right, bottom) whose sharpness is measured, or null. */
    @Volatile var sharpnessRegion: FloatArray? = null
    /** Contrast of [sharpnessRegion] in the latest frame (brightness-normalised gradient energy). */
    @Volatile var sharpness: Float = 0f
        private set
    /** Sensor timestamp of the frame [sharpness] was measured on. */
    @Volatile var sharpnessTimestamp: Long = 0L
        private set

    /** Frames processed so far; lets callers wait for frames taken after a lens move. */
    @Volatile var frameCounter: Long = 0L
        private set

    /** When the last RAW frame was processed (elapsedRealtime ms); for stall detection. */
    @Volatile var lastFrameMs: Long = 0L
        private set

    /** Current auto gain in EV, for display. */
    @Volatile var autoGainEv: Float = Float.NaN
        private set

    /** The auto gain was measured at EV 0 in AE Auto (or came from such a measurement): worth remembering. */
    @Volatile var autoGainTrusted = false
        private set
    /** Frames the auto gain has followed the scene since this renderer started (or was seeded). */
    private var gainFrames = 0
    private var gainFramesUnfrozen = 0

    /** Starts the auto gain at this lens's remembered value (NaN: none yet) instead of [DEFAULT_GAIN_EV]. */
    fun seedAutoGain(ev: Float) = post {
        if (ev.isNaN() || !autoGainEv.isNaN()) return@post
        autoGainEv = ev
        gainFrames = FIRST_GAIN_FRAMES
        autoGainTrusted = true
    }

    /**
     * Effective ISO (sensor ISO × auto gain) from which the auto gain gives way; 0 = never. Past
     * it, each further stop of amplification lifts the picture only half a stop, so a dim room
     * looks dim and a black subject stays dark: matching the ISP's night-brightened rendering
     * made black fabric mid-grey at ≈ISO 29000 (owner, 2026-10-15). Off in Manual, where the
     * user's ISO and shutter set the brightness.
     */
    @Volatile var nightKneeIso: Float = NIGHT_KNEE_ISO
    @Volatile var nightLimit: Boolean = true
    /**
     * The user's EV that went into sensor exposure (AE compensation, Priority's target): taken
     * out of the ISO the night limit looks at, or it would take back half of every +EV at night.
     */
    @Volatile var sensorCompEv: Float = 0f
        private set
    /** EV the night limit currently takes off the auto gain (eased, see [updateNightCut]). */
    @Volatile private var nightCut = 0f
    private var nightCutStarted = false

    /**
     * The night limit goes by how dark the scene is, which is ISO × shutter, not ISO alone: the
     * ISO the frame would have had at 1/50 s ([NIGHT_REF_NS], where phones sit in a dim room).
     * By ISO alone the picture changed with how the camera's AE splits the same exposure: the
     * X14's 2.6x goes from 1/50 at ISO 900 to 1/100 at ISO 1600 and back around one light level
     * (a sixth of a stop of compensation is enough to tip it), and each time the picture went
     * 0.4 EV darker or brighter over the next second (2026-11-13; the owner: "it just going back
     * and forth not knowing what exposure to use").
     */
    private fun updateNightCut(meta: FrameMeta?) {
        val auto = if (autoGainEv.isNaN()) DEFAULT_GAIN_EV else autoGainEv
        val knee = nightKneeIso
        if (meta == null || meta.iso <= 0) return
        // (As the picture is shown: a short trip of the exposure that's evened out isn't a darker scene.)
        val iso = (if (meta.exposureNs > 0) meta.iso * (meta.exposureNs.toFloat() / NIGHT_REF_NS) else meta.iso.toFloat()) * 2f.pow(steadyEv)
        val target = if (!nightLimit || knee <= 0f) 0f else
            // (Of EVO's part of the compensation, what the sensor really did: a camera at its ISO
            // limit ignores a step, and the night limit mustn't react to one that changed nothing.)
            (log2(iso * 2f.pow(auto - (sensorCompEv - evoCorrectionEv)) / knee) * (1f - NIGHT_SLOPE)).coerceAtLeast(0f)
        // ~0.4 s to follow, so ISO steps (Priority's, the ISP's) don't show as brightness steps;
        // a new lens starts where it belongs rather than bright and fading down.
        nightCut = if (nightCutStarted) nightCut + (target - nightCut) * 0.08f else target
        nightCutStarted = true
    }

    /** All digital gain before the log curve, in EV (auto gain or its default after the night limit, plus the user's). */
    val totalGainEv: Float get() = (if (autoGainEv.isNaN()) DEFAULT_GAIN_EV else autoGainEv) - nightCut + exposureOffsetEv + compLagEv +
        evoViewEv + evoCorrectionEv + steadyEv
    @Volatile var lutStrength: Float = 1f
    /**
     * Tone balance of the view, -1..1 each (see PipelineShaders.finish `balanceTone`): pulls the
     * highlights down / lifts the shadows before the look or LUT. The recorded log never gets it.
     */
    @Volatile var toneHighlights: Float = 0f
    @Volatile var toneShadows: Float = 0f
    /**
     * The user's white balance trim on top of the ISP's (gains for R, G, G, B; see
     * CameraActivity.setWbShift). Part of development like the ISP's own gains, so the log gets it too.
     */
    @Volatile var wbShift: FloatArray = floatArrayOf(1f, 1f, 1f, 1f)
    @Volatile var saturation: Float = 1f
    @Volatile var vibrance: Float = 0f
    /** 2×2 superpixel output at half resolution instead of demosaiced full resolution. */
    @Volatile var superpixel: Boolean = false
    /**
     * Full-resolution processing for the preview too. Off by default: the preview area is ~1600 px
     * wide, so between recordings the cheap superpixel path (about a quarter of the GPU work) looks
     * the same and keeps the phone cooler; recording always runs at the recorded resolution.
     */
    @Volatile var previewFullRes: Boolean = false
    /** 0 off, 1 fix defect pixels, 2 + temporal colour NR, 3 + stronger temporal colour NR. */
    @Volatile var cleanup: Int = 2
    /**
     * Colour NR's Fast variant (PipelineShaders.fastNr: per pixel, ~0.5.0's cost) instead of
     * Quality (the neighbourhood clamp, ~7 ms more per 4K frame on the X14).
     */
    @Volatile var nrFast: Boolean = false

    /**
     * Called (on the GL thread) when colour NR is switched off for the current recording because
     * frames took longer than the frame interval; the argument says why, for the event log.
     */
    @Volatile var onNrShed: ((String) -> Unit)? = null

    /**
     * [cleanup] as the pipeline runs it: without colour NR once a recording had to shed it. Photo
     * mode's viewfinder (and a photo's burst) has the pixel fix only, which the burst's merge
     * needs; colour NR runs once on the merged photo (owner: NR after, a lighter viewfinder).
     */
    private fun activeCleanup() = when {
        photo?.final == true -> 2
        photoMode || photo != null -> 1
        recording?.nrShed == true -> minOf(cleanup, 1)
        else -> cleanup
    }

    /** Colour NR's Fast variant runs now ([nrFast]); a photo always gets Quality. */
    private fun fastNr() = nrFast && photo == null

    /** Photo mode (the viewfinder's settings, see [activeCleanup]). */
    @Volatile var photoMode = false
    /**
     * Texture, Clarity and Dehaze, −1..1 (PipelineShaders.photoFinish), part of the view like the
     * look: on the viewfinder, photos and recordings with the look baked in, while [dialsActive]
     * (Pro and Photo; Simple stays plain).
     */
    @Volatile var photoTexture = 0f
    @Volatile var photoClarity = 0f
    @Volatile var photoDehaze = 0f
    @Volatile var dialsActive = false
    private fun finishOn() = dialsActive && (photoTexture != 0f || photoClarity != 0f || photoDehaze != 0f)

    /** Red focus-peaking overlay on the preview (never recorded). */
    @Volatile var peaking: Boolean = false
    /**
     * Share of the image's height a cropped frame keeps (16:9 from 4:3 = 0.75; 1 = open gate): the
     * preview darkens the rest so the framing shows while what's coming in stays visible.
     */
    @Volatile var frameCrop: Float = 1f
    /** Glass controls over the preview: drawn with a blurred copy of it behind them (see [GlassBackdrop]). */
    @Volatile var glassRects: List<GlassRect> = emptyList()
    /**
     * Debug (adb pipetiming=true): glFinish after every stage of [processFrame] and log the
     * average time of each, so the GPU's share of each pass shows. Slows everything down a little.
     */
    @Volatile var pipeTiming = false
    private val stageNs = LongArray(6)
    private var stageFrames = 0
    private var stageMark = 0L

    private fun stage(i: Int) {
        if (!pipeTiming) return
        GLES30.glFinish()
        val now = SystemClock.elapsedRealtimeNanos()
        if (i >= 0) stageNs[i] += now - stageMark
        stageMark = now
    }

    private fun logStages() {
        if (!pipeTiming || ++stageFrames < 60) return
        val t = developTarget()
        android.util.Log.i("AuthRec", "pipeline %s%s%s: CPU reads %.1f, upload %.1f, prep %.1f, change test %.1f, develop %.1f, encode %.1f ms".format(
            when (t.program) {
                superpixelProgram, fastSuperpixelProgram -> "superpixel"
                scaledProgram, fastScaledProgram -> "scaled ${t.width}x${t.height}"
                else -> "full size"
            } + if (fastNr()) " (fast NR)" else "",
            if (recording != null) " recording" else "",
            if (activeCleanup() >= 2) " colour NR" else "",
            *intArrayOf(0, 1, 2, 4, 3, 5).map { stageNs[it] / 60 / 1e6 }.toTypedArray()))
        stageNs.fill(0)
        stageFrames = 0
    }

    /** Debug: log what the glass backdrop costs (adb glasstiming=true). */
    @Volatile var glassTiming = false
    private var backdrop: GlassBackdrop? = null
    /**
     * The log encoding the current LUT expects, when it isn't ours: the view converts to it before
     * the lookup (the recorded log stays in [profile]). null = apply the LUT to our log as is.
     */
    @Volatile var lutInput: LogProfile? = null
    @Volatile private var pendingLut: CubeLut? = null
    @Volatile private var lutEnabled = false

    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val frameQueued = AtomicBoolean(false)
    /** Show the image left-right mirrored (selfie style); recordings keep what was set at their start. */
    @Volatile var mirror = false
    /**
     * Set on the GL thread by [release]. The camera keeps delivering frames until it's closed after
     * us, and a frame queued behind the teardown crashed on the destroyed context (EGL_BAD_CONTEXT
     * on a 2.6x → 1x switch, which replaces the renderer: another RAW size).
     */
    private var released = false

    private lateinit var egl: EglCore
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var viewW = 0
    private var viewH = 0
    private var rotation = 0

    private val w = sensor.size.width
    private val h = sensor.size.height
    private var rawTex = 0
    private var linearTex = 0
    private var viewTex = 0
    private var logTex = 0
    private var spViewTex = 0
    private var spLogTex = 0
    /** A recording's scaled develop target (0 when none), [scW]×[scH]. */
    private var scViewTex = 0
    private var scLogTex = 0
    private var scW = 0
    private var scH = 0
    private var scaledProgram = 0

    /** Where the develop pass writes this frame, and with which program. */
    private class DevelopTarget(val program: Int, val view: Int, val log: Int, val width: Int, val height: Int)

    private fun developTarget(): DevelopTarget {
        val fast = fastNr()
        if (fast && fastDevelopProgram == 0) {
            // Compiled when first chosen: three more large shaders at every renderer start for a
            // setting most leave alone would slow lens switches.
            fastDevelopProgram = Gl.computeProgram(PipelineShaders.developFast)
            fastSuperpixelProgram = Gl.computeProgram(PipelineShaders.developSuperpixelFast)
            fastScaledProgram = Gl.computeProgram(PipelineShaders.developScaledFast)
        }
        return when {
            activeSuperpixel() -> DevelopTarget(if (fast) fastSuperpixelProgram else superpixelProgram, spViewTex, spLogTex, w / 2, h / 2)
            recording?.scaled == true && scViewTex != 0 -> DevelopTarget(if (fast) fastScaledProgram else scaledProgram, scViewTex, scLogTex, scW, scH)
            else -> DevelopTarget(if (fast) fastDevelopProgram else developProgram, viewTex, logTex, w, h)
        }
    }
    private var fastDevelopProgram = 0
    private var fastSuperpixelProgram = 0
    private var fastScaledProgram = 0

    private fun releaseScaled() {
        if (scViewTex == 0) return
        GLES30.glDeleteTextures(2, intArrayOf(scViewTex, scLogTex), 0)
        scViewTex = 0
        scLogTex = 0
    }
    private var shadingTex = 0
    private var lutTex = 0
    private var lutSize = 0
    private var shadingDims = 0 to 0
    private var prepProgram = 0
    private var prepFixProgram = 0
    private var developProgram = 0
    private var coarseProgram = 0
    private var changeProgram = 0
    private var superpixelProgram = 0
    private var displayProgram = 0

    private var recording: Recording? = null

    private class Recording(
        val recorder: Recorder,
        val bakeLut: Boolean,
        val superpixel: Boolean,
        val quarterTurns: Int,
        /** Encoder frame size and the part of the image (texture x, y, width, height) it shows. */
        val outW: Int,
        val outH: Int,
        val crop: FloatArray,
        val context: EGLContext,
        val surface: EGLSurface,
        val frameNs: Long,
        val mirror: Boolean,
        /** The develop pass writes at the encoder's size (see PipelineShaders.scaledMain). */
        val scaled: Boolean,
        /** On the GL thread when the encoder's surface stops taking frames. */
        val onFailed: (String) -> Unit,
        /** [context] is a second context made for this recording (else it's the main one). */
        val ownContext: Boolean,
    ) {
        /** The encoder surface failed: nothing more is drawn into it (and it's already destroyed). */
        var failed = false
        /** Frames go to the encoder but don't count yet (see Recorder.commit). */
        @Volatile var preroll = true
        var commitPending = false
        var prerollSlowestMs = 0.0
        /** Sensor time of the first frame given to the encoder (its pts 0, also the audio's origin). */
        var originNs = -1L
        /** Sensor time of the clip's first frame (the commit); lost frames and timing count from it. */
        var firstTimestamp = -1L
        var lastTimestamp = -1L
        var dropped = 0
        // Where time went, for the drops some clips start with (see timingSummary).
        var slowestFrameMs = 0.0
        var slowestFrameAt = 0.0
        var slowestSwapMs = 0.0
        val gaps = StringBuilder()
        /** Colour NR switched off for the rest of this clip: the GPU couldn't keep up with it. */
        @Volatile var nrShed = false
        /** The last [BUDGET_WINDOW] frames' times (ms), to tell lasting slowness from a hiccup. */
        val recentMs = DoubleArray(BUDGET_WINDOW)
        var timedFrames = 0
        /** Frames lost during the pre-roll once past its first BUDGET_SKIP frames (GPU clocking up). */
        var prerollLost = 0
        var prerollFrames = 0
        var prerollLastTs = -1L
        /** Frames lost after the clip's first second (its start can stall whatever runs). */
        var droppedSettled = 0
        /** Time paused before the frames now being recorded (see Recorder.pauses). */
        var shiftNs = 0L
        /** The clip's frames, how many preview frames were drawn meanwhile (a choppy viewfinder shows here), and their total time on this thread. */
        var clipFrames = 0
        var viewFrames = 0
        var clipMs = 0.0
        /** Frames this thread let go undeveloped to keep the camera supplied (see fillPending). */
        var skipped = 0

        // What the exposure did, for clips whose brightness jumps (see exposureSummary).
        var isoLow = 0
        var isoHigh = 0
        var nsLow = 0L
        var nsHigh = 0L
        var foreignMeta = 0
        var exposureSteps = 0
        val exposureStepsAt = StringBuilder()
        var jumps = 0
        val jumpsAt = StringBuilder()
        private var lastShown = Float.NaN

        /**
         * One frame of the clip: its ISO and shutter ([own]: from its own capture result), how far
         * its exposure moved from the frame before ([stepEv]) and how bright it comes out
         * ([shownEv]: log2 of the RAW's average after all gain).
         */
        fun noteExposure(timestamp: Long, iso: Int, ns: Long, own: Boolean, stepEv: Float, shownEv: Float) {
            val at = (timestamp - firstTimestamp) / 1e9
            if (!own) foreignMeta++
            if (iso > 0 && ns > 0) {
                isoLow = if (isoLow == 0) iso else minOf(isoLow, iso)
                isoHigh = maxOf(isoHigh, iso)
                nsLow = if (nsLow == 0L) ns else minOf(nsLow, ns)
                nsHigh = maxOf(nsHigh, ns)
            }
            if (abs(stepEv) >= 0.08f) {
                exposureSteps++
                if (exposureStepsAt.length < 110) exposureStepsAt.append(" %.2f s %+.2f".format(at, stepEv))
            }
            if (!shownEv.isNaN() && !lastShown.isNaN() && abs(shownEv - lastShown) >= 0.2f) {
                jumps++
                if (jumpsAt.length < 110) jumpsAt.append(" %.2f s %+.2f".format(at, shownEv - lastShown))
            }
            lastShown = shownEv
        }

        /**
         * For the event log: the camera's ISO and shutter over the clip, the frame-to-frame steps
         * of its exposure, and the frames that came out brighter or darker than the one before by
         * a fifth of a stop or more (a pan does that too; a still scene shouldn't).
         */
        private fun exposureSummary() = if (isoHigh == 0) "" else
            ", ISO " + (if (isoLow == isoHigh) "$isoLow" else "$isoLow–$isoHigh") + " at 1/%.0f".format(1e9 / nsHigh) +
            (if (nsLow != nsHigh) "–1/%.0f".format(1e9 / nsLow) else "") +
            (if (exposureSteps > 0) ", $exposureSteps exposure steps (EV:$exposureStepsAt)" else "") +
            (if (jumps > 0) ", $jumps brightness jumps (EV:$jumpsAt)" else "") +
            (if (foreignMeta > 0) ", $foreignMeta frames without their own capture result" else "")

        /** One line for the event log: when frames were lost and what the GL thread was doing. */
        fun timingSummary() = "Recording timing: $dropped frames lost" +
            (if (gaps.isNotEmpty()) " (at$gaps)" else "") +
            (if (skipped > 0) ", $skipped skipped here to keep the camera supplied" else "") +
            ", average frame %.1f ms, slowest frame %.0f ms at %.2f s, slowest encoder hand-over %.0f ms, slowest pre-roll frame %.0f ms (%d lost), preview showed %d of %d frames"
                .format(if (clipFrames > 0) clipMs / clipFrames else 0.0, slowestFrameMs, slowestFrameAt, slowestSwapMs, prerollSlowestMs, prerollLost, viewFrames, clipFrames) +
            exposureSummary()
    }

    private var frameCount = 0
    private var viewCount = 0
    private var fpsWindowStart = 0L
    private var gpuMsAvg = 0.0

    init {
        handler.post { setupGl() }
    }

    /** Runs [block] on the GL thread unless [release] got there first. */
    private fun post(block: () -> Unit) = handler.post { if (!released) block() }

    fun attachSurface(surface: Surface, width: Int, height: Int, rotationQuarterTurns: Int) = post {
        releaseWindowSurface()
        windowSurface = egl.createWindowSurface(surface)
        // A viewfinder swap needn't wait for the display: with a full queue the newest frame
        // replaces the waiting one instead of blocking the thread that also feeds the encoder.
        egl.makeCurrent(windowSurface)
        EGL14.eglSwapInterval(egl.display, 0)
        viewW = width
        viewH = height
        rotation = rotationQuarterTurns
    }

    fun detachSurface() = post { releaseWindowSurface() }

    /** Called from the camera thread whenever a RAW frame lands. */
    fun onFrameAvailable() {
        if (frameQueued.compareAndSet(false, true)) post { renderFrames() }
    }

    /** null shows the plain log image. */
    fun setLut(lut: CubeLut?) {
        sourceLut = lut
        pendingLut = lut
        lutEnabled = lut != null
    }

    // ---- The view LUT with eDR, the LUT's input conversion and strength folded in (ViewLut) ----

    /** What a baked view LUT was made from; it's used only while all of this still holds. */
    private data class ViewKey(val source: CubeLut, val profile: LogProfile, val lutInput: LogProfile?,
                               val hi: Float, val lo: Float, val strength: Float)

    @Volatile private var sourceLut: CubeLut? = null
    /** Debug (adb bakedview=false): always compute the view per pixel, to compare. */
    @Volatile var bakeViewLut = true
    private val bakeThread = HandlerThread("view-lut").apply { start() }
    private val bakeHandler = Handler(bakeThread.looper)
    @Volatile private var bakeWanted: ViewKey? = null
    @Volatile private var pendingBaked: Pair<ViewKey, CubeLut>? = null
    private var bakedKey: ViewKey? = null
    private var bakedTex = 0
    private var bakedSize = 0

    private fun currentViewKey(): ViewKey? = sourceLut?.let {
        ViewKey(it, profile, lutInput, toneHighlights, toneShadows, lutStrength)
    }

    /** Bakes [key] on the bake thread (only the newest request is worked on). */
    private fun requestBake(key: ViewKey) {
        if (bakeWanted == key) return
        bakeWanted = key
        bakeHandler.post {
            if (bakeWanted != key) return@post // a newer request follows
            val lut = runCatching { ViewLut.bake(key.source, key.profile, key.lutInput, key.hi, key.lo, key.strength) }.getOrNull()
                ?: return@post
            pendingBaked = key to lut
        }
    }

    /**
     * Starts feeding [recorder] with frames of [outW]×[outH], the image's centre band of [cropHeight]
     * (share of its height; 1 = open gate), as pre-roll until [commitRecording]. [onFailed] runs on
     * the GL thread if the encoder surface can't be set up.
     */
    fun startRecording(recorder: Recorder, fps: Int, bakeLut: Boolean, outW: Int, outH: Int, cropHeight: Float,
                       onFailed: (String) -> Unit) = handler.post {
        if (released) return@post onFailed("camera closed")
        try {
            val tenBit = recorder.config.codec.tenBit
            // The main context draws into the encoder too when its config fits (the usual case,
            // see EglCore); a second, shared context only after a bit-depth change it doesn't fit.
            val direct = egl.recordableBits == (if (tenBit) 10 else 8)
            // Recordable only: a 10-bit window without the flag got past EGL on the Xiaomi 12, and
            // its encoder failed on the first frame.
            val config = if (direct) egl.mainConfig else egl.chooseConfig(tenBit = tenBit, recordable = true)
            if (config == null) {
                if (tenBit) tenBitRecording = false
                error("no ${if (tenBit) "10" else "8"}-bit recordable EGL config")
            }
            val ctx = if (direct) egl.mainContext else egl.createContext(config, egl.mainContext)
            val surface = runCatching { egl.createWindowSurface(recorder.inputSurface, config) }
                .onFailure { if (!direct) egl.destroyContext(ctx) }.getOrThrow()
            if (!direct) EventLog.log("Recording through a second GL context (${if (tenBit) 10 else 8}-bit recording, " +
                "${egl.recordableBits}-bit main config)")
            // Narrower than the image (an encoder that takes less): develop at that size instead.
            val scaled = !superpixel && outW < w * 0.95f
            releaseScaled()
            if (scaled) {
                scW = outW
                scH = (h.toLong() * outW / w).toInt()
                scViewTex = Gl.texture2D(GLES30.GL_RGBA16F, scW, scH, linear = true)
                scLogTex = Gl.texture2D(GLES30.GL_RGBA16F, scW, scH, linear = true)
            }
            // Half turns can be baked in at the same frame size (front camera is upside down in landscape).
            recording = Recording(recorder, bakeLut, superpixel, if (rotation == 2) 2 else 0, outW, outH,
                floatArrayOf(0f, (1f - cropHeight) / 2f, 1f, cropHeight), ctx, surface, 1_000_000_000L / fps, mirror, scaled, onFailed,
                ownContext = !direct)
            // Colour NR's history at the recorded size now, rather than inside the pre-roll's frames.
            if (cleanup >= 2) developTarget().let { ensureHistory(it.width, it.height) }
        } catch (e: Exception) {
            onFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Ends the pre-roll: the next frame is where the clip starts (its key frame follows soon after). */
    fun commitRecording() = post { recording?.commitPending = true }

    /**
     * Stops feeding the encoder; [onStopped] runs on the GL thread once no more frames will be drawn,
     * with a line about the recording's frame timing (see [Recording.timingSummary]).
     */
    fun stopRecording(onStopped: (String?) -> Unit) = handler.post {
        if (released) return@post onStopped(null)
        val summary = recording?.let {
            if (!it.failed) {
                egl.makeCurrent(EGL14.EGL_NO_SURFACE)
                egl.destroySurface(it.surface)
                if (it.ownContext) egl.destroyContext(it.context)
            }
            it.timingSummary()
        }
        recording = null
        releaseScaled()
        recFinish?.release()
        recFinish = null
        onStopped(summary)
    }

    /** Blocks (briefly) until GL is torn down, so a new renderer can take over the same Surface. */
    fun release() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            released = true
            pending.forEach { it.close() }
            pending.clear()
            recording?.takeIf { !it.failed }?.let {
                egl.destroySurface(it.surface)
                if (it.ownContext) egl.destroyContext(it.context)
            }
            recording = null
            photo?.let { if (!it.done) it.onDone(null) }
            photo = null
            pendingSnap?.onDone?.invoke(null)
            pendingSnap = null
            releasePhotoTextures()
            photoFinish?.release()
            photoFinish = null
            viewFinish?.release()
            viewFinish = null
            recFinish?.release()
            recFinish = null
            releaseScaled()
            backdrop?.release()
            backdrop = null
            releaseHistory()
            releaseWindowSurface()
            egl.release()
            thread.quitSafely()
            bakeThread.quitSafely()
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun setupGl() {
        egl = EglCore(preferTenBit = tenBitSurfaces)
        if (egl.chooseConfig(tenBit = true, recordable = true) == null) {
            tenBitRecording = false
            if (!tenBitLogged) {
                tenBitLogged = true
                EventLog.log("No recordable 10-bit EGL config; 10-bit configs: ${egl.tenBitConfigs()}")
            }
        }
        prepProgram = Gl.computeProgram(PipelineShaders.prep)
        prepFixProgram = Gl.computeProgram(PipelineShaders.prepFixDefects)
        developProgram = Gl.computeProgram(PipelineShaders.develop)
        coarseProgram = Gl.computeProgram(PipelineShaders.coarseColour)
        changeProgram = Gl.computeProgram(PipelineShaders.colourChange)
        motionBuffer = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, motionBuffer)
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 16, zeroInts(4), GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, motionBuffer)
        superpixelProgram = Gl.computeProgram(PipelineShaders.developSuperpixel)
        scaledProgram = Gl.computeProgram(PipelineShaders.developScaled)
        // Here rather than at REC, so a clip's start never waits for a shader compile.
        viewLogProgram = Gl.computeProgram(PipelineShaders.viewFromLog)
        displayProgram = Gl.renderProgram(PipelineShaders.displayVertex, PipelineShaders.displayFragment)

        rawTex = Gl.texture2D(GLES30.GL_R16UI, w, h, linear = false)
        linearTex = Gl.texture2D(GLES30.GL_R32F, w, h, linear = false)
        viewTex = Gl.texture2D(GLES30.GL_RGBA16F, w, h, linear = true)
        logTex = Gl.texture2D(GLES30.GL_RGBA16F, w, h, linear = true)
        noHistory = Gl.texture2D(GLES30.GL_RGBA16F, 1, 1, linear = false)
        spViewTex = Gl.texture2D(GLES30.GL_RGBA16F, w / 2, h / 2, linear = true)
        spLogTex = Gl.texture2D(GLES30.GL_RGBA16F, w / 2, h / 2, linear = true)
        uploadShading(floatArrayOf(1f, 1f, 1f, 1f), 1, 1)
        uploadLut(CubeLut("identity", 2, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)))
        Gl.check("setup")
    }

    private fun renderFrames() {
        frameQueued.set(false)
        val t0 = SystemClock.elapsedRealtimeNanos()
        var meta: FrameMeta? = null
        var processed = 0
        var behind = false
        val rec = recording
        fillPending(rec)
        if (rec != null) {
            // Every frame goes to the encoder, oldest first, but for at most PASS_BUDGET_NS per pass:
            // an encoder that can't keep up (Xiaomi 12 choking on 4096×3072: ~250 ms a frame) kept
            // this loop going for as long as the camera delivered, and the stop posted behind it
            // never ran. (A cap of 3 frames instead cost the X14 frames at the start of 4K clips.)
            while (true) {
                if (SystemClock.elapsedRealtimeNanos() - t0 > PASS_BUDGET_NS) {
                    behind = pending.isNotEmpty()
                    break
                }
                val image = pending.firstOrNull() ?: break
                if (!readyToDevelop(image, rec.frameNs, WAIT_RECORDING)) break // its capture result wakes us
                pending.removeFirst()
                val f0 = SystemClock.elapsedRealtimeNanos()
                val ts = image.timestamp
                meta = processFrame(image)
                if (debugSlowFrameMs > 0) SystemClock.sleep(debugSlowFrameMs.toLong())
                processed++
                val ms = (SystemClock.elapsedRealtimeNanos() - f0) / 1e6
                watchBudget(rec, ms)
                if (rec.preroll) {
                    rec.prerollSlowestMs = maxOf(rec.prerollSlowestMs, ms)
                } else if (!rec.recorder.pauses.paused) {
                    rec.clipMs += ms
                    if (ms > rec.slowestFrameMs) {
                        rec.slowestFrameMs = ms
                        rec.slowestFrameAt = (ts - rec.firstTimestamp) / 1e9
                    }
                }
                // The viewfinder: once caught up, and at least every VIEW_INTERVAL_NS whatever waits.
                // Only when caught up, a warm X14 at 4K (frames ~33 ms) drew 6 of 439 frames, "view
                // 0 fps" (owner: "choppy as hell"); drawing it costs a hot phone the odd frame, and
                // losing frames sheds colour NR (watchBudget), which gives the time back.
                fillPending(rec)
                val caughtUp = pending.firstOrNull()?.let { !readyToDevelop(it, rec.frameNs, WAIT_RECORDING) } ?: true
                if (caughtUp || SystemClock.elapsedRealtimeNanos() - lastViewNs >= VIEW_INTERVAL_NS) drawView(rec)
            }
        } else {
            // The preview needn't show every frame: the newest one whose result is in, else the
            // newest once the oldest can't wait any longer.
            val ready = pending.indexOfLast { metaArrived(it.timestamp) }
            val pick = if (ready >= 0) ready else pending.lastIndex.takeIf { it >= 0 && readyToDevelop(pending.first(), PREVIEW_FRAME_NS, MAX_PENDING) }
            if (pick != null) {
                repeat(pick) { pending.removeFirst().close() }
                meta = processFrame(pending.removeFirst())
                processed++
                drawView(null)
            }
        }
        if (processed == 0) return
        // More are waiting: come back after whatever else was posted meanwhile.
        if (behind && frameQueued.compareAndSet(false, true)) post { renderFrames() }
        updateStats((SystemClock.elapsedRealtimeNanos() - t0) / 1e6 / processed, meta, processed)
    }

    /** Draws the preview of the frame just developed (if there's a screen to draw on). */
    private fun drawView(rec: Recording?) {
        if (windowSurface == EGL14.EGL_NO_SURFACE) return
        drawToScreen()
        egl.swapWindow()
        lastViewNs = SystemClock.elapsedRealtimeNanos()
        viewCount++
        rec?.let { if (!it.preroll && !it.recorder.pauses.paused) it.viewFrames++ }
    }

    private var lastViewNs = 0L
    private var lastGlassNs = 0L

    /**
     * Frames taken from the reader, oldest first, waiting for their capture results: the HAL
     * delivers a RAW buffer before its result, so a frame developed at once got the previous
     * frame's exposure, colour and noise (the event log: "0 of 90 frames matched" in preview),
     * which undid the per-frame exposure evening (Priority's steps showed) and mismatched the auto
     * gain's measurements. [onFrameAvailable] also runs on every result.
     */
    private val pending = ArrayDeque<Image>()

    /**
     * Takes what the reader has, leaving room in it for the camera. While recording ([rec]) a
     * backlog beyond [MAX_PENDING_RECORDING] frames lets the oldest go: left waiting in the reader, they
     * used up the camera's buffers when a hot phone fell behind (X14 at 45 °C: 4K frames took
     * 43–58 ms), so the camera failed captures instead, and the 0.6x's HAL stopped delivering
     * altogether ("no frames for 2.5 s" ended the clip; feedback item 83). Those frames were lost
     * either way; this way the camera keeps running. Recordings take frames oldest first, so the
     * preview (which shows the newest) never builds a backlog. The limit is 8, not 4: at 4, a
     * 4K clip with Quality NR at 40 °C (21 ms a frame on average) lost single frames after
     * each 80 ms hiccup, which the waiting room absorbed before.
     */
    private fun fillPending(rec: Recording? = null) {
        val limit = if (rec != null) MAX_PENDING_RECORDING else MAX_PENDING
        while (true) {
            if (pending.size < limit) {
                pending.addLast(acquireNext() ?: return)
                continue
            }
            if (rec == null) return
            val next = acquireNext() ?: return
            pending.removeFirst().close()
            if (!rec.preroll) rec.skipped++
            pending.addLast(next)
        }
    }

    /**
     * Whether [image] may be developed now: its result is in, or isn't coming (a later frame's is
     * in: results arrive in order), or it can't wait longer: [room] frames are waiting, or it is
     * older than [room] and a half frame intervals ([frameNs]). Waiting only until the next
     * frame wasn't enough on the Xiaomi 15 Ultra, whose results come more than a frame late
     * (0–14 of 90 matched, 2026-10-19); nor was waiting for two: the X14's 2.6x delivers a result
     * about when the second frame after it arrives, so the limit let the frame go just before
     * (118 of 322 matched on a warm phone, and the preview, which then takes the newest frame,
     * the one furthest from its result, drew every other frame). A frame with another's result
     * gets the wrong exposure, and a step of real exposure is then evened out a frame or two
     * late: a dip or flash of the step's size (2026-11-13).
     */
    private fun readyToDevelop(image: Image, frameNs: Long, room: Int): Boolean {
        val ts = image.timestamp
        if (metaArrived(ts) || pending.size >= room) return true
        if (pending.any { it.timestamp > ts && metaArrived(it.timestamp) }) return true
        return sensor.timestampsAreBoottime && SystemClock.elapsedRealtimeNanos() - ts > frameNs * (2 * room + 1) / 2
    }

    /** acquireNextImage throws instead of returning null when all buffers are held; treat both as "none". */
    private fun acquireNext(): Image? = try {
        reader.acquireNextImage()
    } catch (e: IllegalStateException) {
        null
    }

    private fun processFrame(image: Image): FrameMeta? {
        egl.makeCurrent(windowSurface)
        // The photo the last frame's draw showed is done with (see finishPhoto).
        if (photo?.done == true) {
            photo = null
            photoFinish?.release()
            photoFinish = null
        }
        stage(-1)
        val timestamp = image.timestamp
        lastFrameMs = SystemClock.elapsedRealtime()
        val meta = metaSource(timestamp)
        takeExposure()
        photo?.takeIf { it.taken == 0 }?.let {
            it.refTimestamp = timestamp
            it.refMeta = meta
            it.onReference(timestamp)
        }
        updateNightCut(meta)
        val frameExposure = meta?.let { it.iso.toDouble() * it.exposureNs }?.takeIf { it > 0 } ?: Double.NaN
        rawLogAverage(image, meta)?.let { raw ->
            rawExposure = frameExposure
            updateAutoGain(raw)
        }
        updateExposureComp(frameExposure)
        val dt = if (lastTimestamp != 0L) ((timestamp - lastTimestamp) / 1e9f).coerceIn(0.001f, 0.2f) else 1f / 30
        frameBreak = lastTimestamp == 0L || timestamp - lastTimestamp > 500_000_000L || timestamp < lastTimestamp
        lastTimestamp = timestamp
        updateCompLag(frameExposure, dt)
        updateEvo(meta, dt)
        updateSteady(frameExposure, meta?.timestamp == timestamp)
        recording?.takeIf { !it.preroll && it.firstTimestamp >= 0 }?.noteExposure(timestamp, meta?.iso ?: 0, meta?.exposureNs ?: 0L,
            meta?.timestamp == timestamp, frameStepEv, if (rawBrightness > 0f) log2(rawBrightness) + totalGainEv else Float.NaN)
        if (expLogFrames > 0) {
            expLogFrames--
            evo.debugSensor = if (evoTestStep == 0f || expLogFrames == 0) Float.NaN else if ((expLogFrames / 75) % 2 == 0) evoTestStep else 0f
            android.util.Log.i("AuthRec", "EXP %d iso %d ns %d %s off %.1f prev %s raw %.5f isp %.5f auto %.3f night %.3f lag %.3f view %.3f corr %.3f deliv %.3f grant %.3f comp %.3f steady %.3f total %.3f".format(
                timestamp / 1_000_000, meta?.iso ?: 0, meta?.exposureNs ?: 0L, if (meta?.timestamp == timestamp) "own" else "other", ((meta?.timestamp ?: timestamp) - timestamp) / 1e6,
                if (expLogPrev != 0L && metaArrived(expLogPrev)) "in" else "missing",
                rawBrightness, ispBrightness(), autoGainEv, nightCut, compLagEv, evoViewEv, evoCorrectionEv, evoDelivered, evoGrantedEv, exposureComp, steadyEv, totalGainEv))
        }
        expLogPrev = timestamp
        sharpnessRegion?.let {
            sharpness = measureSharpness(image, it)
            sharpnessTimestamp = timestamp
        }
        frameCounter++
        stage(0)
        image.use {
            uploadRaw(it) // glTexSubImage2D copies the data before returning
            photo?.takeIf { p -> p.taken == 0 && p.keepRaw }?.let { p ->
                p.raw = runCatching { copyRaw(it) }.onFailure { e -> EventLog.log("Photo: RAW not kept", e) }.getOrNull()
            }
        }
        pendingLut?.let {
            uploadLut(it)
            pendingLut = null
        }
        stage(1)
        runPipeline(meta)
        recording?.let {
            finishForRecording(it)
            encodeFrame(it, timestamp)
        }
        pendingSnap?.let { snap ->
            pendingSnap = null
            snap.onDone(runCatching { takeSnapNow(snap, timestamp, meta) }.onFailure { EventLog.log("Snap failed", it) }.getOrNull())
        }
        photo?.takeIf { !it.done }?.let { if (++it.taken >= it.frames) finishPhoto(it) }
        stage(5)
        logStages()
        pendingLogCapture?.let { (width, onFrame) ->
            pendingLogCapture = null
            deliverLogCapture(width, onFrame)
        }
        return meta
    }

    /**
     * Colour NR off for the rest of the clip once frames get lost: 3 in the clip past its first
     * second (the X14 lost 3 in a 4K clip's first half second, the start hiccup that happens with
     * or without it, then none), or frames taking clearly too long on average. The Xiaomi 12 at
     * 4K (2880×2160 from the full RAW) lost 21 of 160 frames with it, none of 310 without. The
     * time a frame takes on this thread is only a hint (GL work overlaps; the X14 averaged 34 ms
     * in a 4K pre-roll and lost nothing), so it counts only when clearly hopeless, and not under
     * pipetiming, whose glFinish calls inflate it.
     *
     * The pre-roll doesn't count: its stalls are the encoder's input queue allocating buffers
     * lazily inside the first draws (ten 50 MB ones at 4K) and the GPU clocking up, which is what
     * it's there to absorb. (Priming the encoder with a burst of frames at REC allocated only 6 of
     * them and held this thread 200–500 ms, which starved the camera: 2026-10-18/19.) Counting 2 lost frames there switched NR
     * off in nearly every X14 4K clip (2026-10-18), where it otherwise runs without losses.
     */
    private fun watchBudget(rec: Recording, ms: Double) {
        if (rec.nrShed || activeCleanup() < 2 || rec.preroll) return
        rec.recentMs[rec.timedFrames % BUDGET_WINDOW] = ms
        rec.timedFrames++
        if (rec.timedFrames < BUDGET_SKIP + BUDGET_WINDOW) return
        val mean = rec.recentMs.average()
        val why = when {
            rec.droppedSettled >= 3 -> "${rec.droppedSettled} frames lost after the first second"
            !pipeTiming && mean > 1.25 * rec.frameNs / 1e6 -> "frames took %.0f ms on average (%.0f ms between frames)".format(mean, rec.frameNs / 1e6)
            else -> return
        }
        rec.nrShed = true
        onNrShed?.invoke(why)
    }

    private fun encodeFrame(rec: Recording, captured: Long) {
        if (rec.failed) return
        // Paused (Recorder.pause): this frame isn't recorded; after a pause, frames move back by
        // the time paused so the clip runs on without a gap.
        val shift = rec.recorder.pauses.shift(captured) ?: return
        if (shift != rec.shiftNs) {
            rec.shiftNs = shift
            rec.lastTimestamp = -1L // the frame interval across the pause isn't a loss
        }
        val timestamp = captured - shift
        if (rec.originNs < 0) {
            rec.originNs = timestamp
            rec.recorder.onFirstVideoFrame(timestamp)
        }
        if (rec.preroll) {
            if (++rec.prerollFrames > BUDGET_SKIP && rec.prerollLastTs >= 0) {
                rec.prerollLost += maxOf(0, ((timestamp - rec.prerollLastTs).toDouble() / rec.frameNs).roundToInt() - 1)
            }
            rec.prerollLastTs = timestamp
        }
        if (rec.commitPending) {
            // The clip starts here: timing and lost frames count from this frame on.
            rec.commitPending = false
            rec.preroll = false
            rec.recorder.commit(timestamp)
            rec.firstTimestamp = timestamp
            rec.lastTimestamp = -1L
        }
        if (!rec.preroll && rec.lastTimestamp >= 0) {
            val lost = ((timestamp - rec.lastTimestamp).toDouble() / rec.frameNs).roundToInt() - 1
            if (lost > 0) {
                rec.dropped += lost
                if (timestamp - rec.firstTimestamp > 1_000_000_000L) rec.droppedSettled += lost
                if (rec.gaps.length < 120) rec.gaps.append(" %.2f s ×%d".format((timestamp - rec.firstTimestamp) / 1e9, lost))
            }
        }
        rec.lastTimestamp = timestamp
        if (!rec.preroll) rec.clipFrames++

        // A second context shares our textures: it waits on the GPU (not the CPU) for the compute
        // passes. The main context's own commands are in order anyway.
        val fence = if (rec.ownContext) GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0).also { GLES30.glFlush() } else null
        // An abandoned encoder surface fails here (an uncaught exception on this thread before).
        runCatching { egl.makeCurrent(rec.surface, rec.context) }.onFailure {
            fence?.let { f -> GLES30.glDeleteSync(f) }
            return encoderSurfaceFailed(rec, it.message ?: "eglMakeCurrent failed")
        }
        fence?.let {
            GLES30.glWaitSync(it, 0, GLES30.GL_TIMEOUT_IGNORED)
            GLES30.glDeleteSync(it)
        }

        val target = developTarget()
        // The main context comes from the glass and the frame mask: draw into the surface itself, plainly.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, 0, rec.outW, rec.outH)
        drawTexture(
            recFinish?.out ?: if (rec.bakeLut) target.view else target.log,
            rotationQuarterTurns = rec.quarterTurns,
            crop = rec.crop,
            mirror = rec.mirror,
        )
        if (Recorder.debugBars) drawTestBars(rec.outW, rec.outH)
        // A second context: the next frame's develop pass rewrites the texture this draw reads, so
        // the main context waits (on the GPU) for the draw; and the texture is unbound again, so
        // the next frame's bind is a real one (bound for good, the driver kept state it had for it).
        val drawn = if (rec.ownContext) {
            Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, 0)
            GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0).also { GLES30.glFlush() }
        } else {
            null
        }
        EGLExt.eglPresentationTimeANDROID(egl.display, rec.surface, timestamp - rec.originNs)
        val s0 = SystemClock.elapsedRealtimeNanos()
        // Blocks when the encoder hasn't taken the previous frames yet.
        val swapped = EGL14.eglSwapBuffers(egl.display, rec.surface)
        val swapError = if (swapped) EGL14.EGL_SUCCESS else EGL14.eglGetError()
        val swapMs = (SystemClock.elapsedRealtimeNanos() - s0) / 1e6
        if (!rec.preroll) rec.slowestSwapMs = maxOf(rec.slowestSwapMs, swapMs)
        egl.makeCurrent(windowSurface)
        drawn?.let {
            GLES30.glWaitSync(it, 0, GLES30.GL_TIMEOUT_IGNORED)
            GLES30.glDeleteSync(it)
        }
        if (!swapped) encoderSurfaceFailed(rec, "eglSwapBuffers failed: 0x%x".format(swapError))
    }

    /**
     * The encoder's surface stopped taking frames (abandoned, or the encoder broke): stop drawing
     * into it at once and let the activity end the recording. Drawing on into a broken surface
     * is the kind of thing that ends in a crash inside the GPU driver.
     */
    private fun encoderSurfaceFailed(rec: Recording, why: String) {
        if (rec.failed) return
        rec.failed = true
        egl.makeCurrent(windowSurface)
        egl.destroySurface(rec.surface)
        if (rec.ownContext) egl.destroyContext(rec.context)
        android.util.Log.e("AuthRec", "recording surface: $why")
        EventLog.log("Recording stopped: the encoder's surface failed ($why)")
        rec.onFailed(why)
    }

    /**
     * Log-average of normalised green RAW samples on a grid (green: same CFA colour everywhere).
     * The samples and the lens shading gain at each stay in [gridX] / [gridShade] for EVO.
     */
    private fun rawLogAverage(image: Image, meta: FrameMeta?): Float? {
        meta ?: return null
        val plane = image.planes[0]
        val buf = plane.buffer
        val (rx, ry) = sensor.redOffset
        val black = meta.blackLevel[0]
        val range = meta.whiteLevel - black
        var sum = 0.0
        var n = 0
        var clipped = 0
        var recoverable = 0
        val gain = currentGain()
        val shading = meta.shading?.takeIf { meta.shadingCols > 0 && meta.shadingRows > 0 && it.size >= meta.shadingCols * meta.shadingRows * 4 }
        for (gy in 1..GRID_ROWS) for (gx in 1..GRID_COLS) {
            // Green on a red row: red's row, the other column parity.
            val x = ((w * gx / (GRID_COLS + 1)) and 1.inv()) + (1 - rx)
            val y = ((h * gy / (GRID_ROWS + 1)) and 1.inv()) + ry
            val v = ((buf.getShort(y * plane.rowStride + x * 2).toInt() and 0xFFFF) - black) / range
            sum += Math.log(v.coerceAtLeast(0f) + 1e-4)
            if (v > 0.9f) clipped++ else if (v * gain > 2f) recoverable++
            gridX[n] = v
            gridShade[n] = if (shading == null) 1f else
                shading[((gy * meta.shadingRows / (GRID_ROWS + 1)) * meta.shadingCols + gx * meta.shadingCols / (GRID_COLS + 1)) * 4 + 1].coerceAtLeast(1f)
            n++
        }
        rawClipFraction = clipped.toFloat() / n
        recoverableClipFraction = recoverable.toFloat() / n
        return Math.exp(sum / n).toFloat().also { rawBrightness = it }
    }

    /**
     * Focus measure for contrast AF: squared gradients (Tenengrad) of the green channel inside
     * [region], on 2×2-block averages of both greens to halve sensor noise, divided by the
     * mean, which cancels small exposure changes without amplifying noise in dark areas the way
     * dividing by the squared mean does. At most ~200×200 blocks.
     *
     * Weighted towards the region's centre (Gaussian, the edges count ~15 %): a tapped subject sits
     * in the middle, and with every block counted equally a smooth subject (a berry, a face) lost
     * to busy, out-of-focus background around it, which has far more texture once it's sharp.
     *
     * Runs on the GL thread for every frame while AF or a tapped spot is being watched, so the
     * rows are copied out in bulk: reading pixel by pixel through the ByteBuffer cost ~20 ms a frame.
     */
    private fun measureSharpness(image: Image, region: FloatArray): Float {
        val plane = image.planes[0]
        val pixels = plane.buffer.asShortBuffer() // keeps the buffer's (native) byte order
        val strideShorts = plane.rowStride / 2
        val (rx, ry) = sensor.redOffset
        val bx0 = ((region[0] * w).toInt() / 2).coerceIn(0, w / 2 - 3)
        val bx1 = ((region[2] * w).toInt() / 2).coerceIn(bx0 + 2, w / 2 - 2)
        val by0 = ((region[1] * h).toInt() / 2).coerceIn(0, h / 2 - 3)
        val by1 = ((region[3] * h).toInt() / 2).coerceIn(by0 + 2, h / 2 - 2)
        val step = ((bx1 - bx0) / 200).coerceAtLeast(1)
        // Sensor rows 2·by .. 2·by+3 (this block row and the next), columns 2·bx0 .. 2·(bx1+1)+1.
        val x0 = bx0 * 2
        val len = (bx1 - bx0 + 2) * 2
        if (rows.size != 4 || rows[0].size < len) rows = Array(4) { ShortArray(len) }
        // Separable Gaussian weights, sigma = 0.55 of the half-size on each axis.
        val cols = (bx1 - bx0 + step - 1) / step
        if (colWeights.size < cols) colWeights = FloatArray(cols)
        val halfX = (bx1 - bx0) / 2f
        for (k in 0 until cols) {
            val t = (k * step - halfX) / (0.55f * halfX)
            colWeights[k] = kotlin.math.exp(-0.5f * t * t)
        }
        val halfY = (by1 - by0) / 2f
        var energy = 0.0
        var level = 0.0
        var n = 0.0
        var by = by0
        while (by < by1) {
            val ty = (by - by0 - halfY) / (0.55f * halfY)
            val wy = kotlin.math.exp(-0.5f * ty * ty)
            for (k in 0 until 4) {
                pixels.position((by * 2 + k) * strideShorts + x0)
                pixels.get(rows[k], 0, len)
            }
            // Mean of the two greens of block column i (relative to bx0) in block row j (0 or 1).
            fun g(i: Int, j: Int): Float {
                val ge = rows[j * 2 + ry][i * 2 + 1 - rx].toInt() and 0xFFFF
                val go = rows[j * 2 + 1 - ry][i * 2 + rx].toInt() and 0xFFFF
                return (ge + go) * 0.5f
            }
            var i = 0
            var k = 0
            while (i < bx1 - bx0) {
                val c = g(i, 0)
                val dx = g(i + 1, 0) - c
                val dy = g(i, 1) - c
                val wgt = wy * colWeights[k]
                energy += wgt * (dx * dx + dy * dy)
                level += wgt * c
                n += wgt
                i += step
                k++
            }
            by += step
        }
        val mean = level / n
        return if (mean > 0) (energy / n / mean).toFloat() else 0f
    }

    private var rows = arrayOf<ShortArray>()
    private var colWeights = FloatArray(0)

    private fun updateAutoGain(raw: Float) {
        // Frozen (EV moved, Priority, Manual: the ISP's tone mapping partly undoes EV, and a gain
        // chasing it would cancel the user's) only once this lens has an estimate. One it knows
        // nothing about learns its first ~1.5 s anyway, slightly off by what the ISP undoes, rather
        // than run on DEFAULT_GAIN_EV: the X14 main camera's 2 EV, ~1.4 EV too bright on its
        // telephoto and front (owner, 2026-10-11, testing a Xiaomi 12 with EV moved).
        if (autoGainFrozen && gainFrames >= FIRST_GAIN_FRAMES) return
        val isp = ispBrightness()
        if (isp.isNaN() || raw <= 0f) return
        val target = (log2(isp / raw) + AUTO_BIAS_EV).coerceIn(0f, 5f)
        // ~1 s to settle at 30 fps, so exposure follows the scene without visible pumping.
        autoGainEv = if (autoGainEv.isNaN()) target else autoGainEv + (target - autoGainEv) * 0.08f
        gainFrames++
        if (!autoGainFrozen && ++gainFramesUnfrozen >= FIRST_GAIN_FRAMES) autoGainTrusted = true
    }

    private fun currentGain(): Float = 2f.pow(totalGainEv) * exposureComp

    /** Sensor timestamp of the frame before (0: none yet), for the time between frames. */
    private var lastTimestamp = 0L

    private fun updateExposureComp(frameExposure: Double) {
        val target = exposureTarget
        if (target.isNaN() || frameExposure.isNaN()) {
            shownExposure = Double.NaN
            exposureComp = 1f
            return
        }
        // ~0.3 s to settle at 30 fps; it starts from where the sensor is.
        shownExposure = if (shownExposure.isNaN()) frameExposure else shownExposure * (target / shownExposure).pow(0.12)
        exposureComp = (shownExposure / frameExposure).coerceIn(0.25, 4.0).toFloat()
    }

    private fun uploadRaw(image: Image) {
        val plane = image.planes[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
        // Camera rows can be padded; tell GL the real row length so no copy is needed.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride / plane.pixelStride)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, plane.buffer)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
    }

    private fun runPipeline(meta: FrameMeta?) {
        if (meta?.shading != null && (meta.shadingCols to meta.shadingRows) != shadingDims) {
            uploadShading(meta.shading, meta.shadingCols, meta.shadingRows)
        } else if (meta?.shading != null) {
            updateShading(meta.shading)
        }

        val shift = wbShift
        val wb = meta?.wbGains ?: floatArrayOf(1f, 1f, 1f, 1f)
        val prepWb = FloatArray(4) { wb[it] * shift[it] }

        // Pass 1: normalise RAW: with the pixel fix one thread per 2×2 quad, without one per pixel
        // (see PipelineShaders.prepFixDefects).
        val fix = activeCleanup() >= 1
        val prep = if (fix) prepFixProgram else prepProgram
        GLES30.glUseProgram(prep)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, rawTex)
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, shadingTex)
        GLES31.glBindImageTexture(0, linearTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
        GLES30.glUniform4fv(Gl.uniform(prep, "uBlack"), 1, meta?.blackLevel ?: floatArrayOf(0f, 0f, 0f, 0f), 0)
        GLES30.glUniform1f(Gl.uniform(prep, "uWhite"), meta?.whiteLevel ?: 1023f)
        GLES30.glUniform4fv(Gl.uniform(prep, "uWb"), 1, prepWb, 0)
        GLES30.glUniform2i(Gl.uniform(prep, "uRedOffset"), sensor.redOffset.first, sensor.redOffset.second)
        if (fix) GLES31.glDispatchCompute((w / 2 + 15) / 16, (h / 2 + 15) / 16, 1)
        else GLES31.glDispatchCompute((w + 15) / 16, (h + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        stage(2)
        photo?.let { burstFrame(it, meta, prepWb) }
        if (activeCleanup() >= 2) updateColourChange(prepWb, meta)
        stage(4)
        develop(meta)
    }

    /** Pass 2: demosaic, colour noise reduction, colour, log, the view (and the clean log). */
    private fun develop(meta: FrameMeta?) {
        val p = profile
        val ccm = meta?.ccm ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val camToTarget = ColorMath.multiply(ColorMath.convert(Primaries.REC709, p.primaries), ccm)
        val target = developTarget()
        val prog = target.program
        val outW = target.width
        val outH = target.height
        GLES30.glUseProgram(prog)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, linearTex)
        GLES31.glBindImageTexture(0, target.view, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES31.glBindImageTexture(1, target.log, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        if (prog == scaledProgram || prog == fastScaledProgram) GLES30.glUniform2f(Gl.uniform(prog, "uRawPerOut"), w.toFloat() / outW, h.toFloat() / outH)
        setViewUniforms(prog, p)
        setColourNr(prog, meta, outW, outH, p)
        GLES30.glUniform2i(Gl.uniform(prog, "uRedOffset"), sensor.redOffset.first, sensor.redOffset.second)
        GLES30.glUniformMatrix3fv(Gl.uniform(prog, "uCamToTarget"), 1, false, ColorMath.toGlColumnMajor(camToTarget), 0)
        GLES30.glUniform1f(Gl.uniform(prog, "uExposure"), currentGain())
        GLES30.glUniform1i(Gl.uniform(prog, "uCurve"), p.shaderId)
        val g = PipelineShaders.DEVELOP_GROUP
        GLES31.glDispatchCompute((outW + g - 1) / g, (outH + g - 1) / g, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        stage(3)
        if (activeCleanup() >= 2) logColourNrMotion()
    }

    // ---- Temporal colour noise reduction (see PipelineShaders.developBase) ----

    /**
     * Two history images (read one, write the other, swap) at half the develop output's size
     * (one colour per 2×2 quad, see PipelineShaders.developB); [historyW]×[historyH] is the output size.
     */
    private val history = IntArray(2)
    private var historyW = 0
    private var historyH = 0
    private var historyRead = 0
    private var historyFresh = true
    private var historyProfile: LogProfile? = null
    private var historyOnLog = false
    /** 1×1 stand-in bound while colour NR is off, so the shader never sees an empty unit. */
    private var noHistory = 0
    // Colour NR's change test (PipelineShaders.coarseColour, colourChange): this frame's 4×4 block
    // colours, and the running colour of each 8×8 / 16×16 window (read one, write the other).
    private var blocksTex = 0
    private var keepTex = 0
    private val model8 = IntArray(2)
    private val model16 = IntArray(2)
    private var modelWrite = 0
    private var changeFresh = true
    /** Per frame (alternating slots): the windows' summed z² for the develop pass, and their noise scales (debug). */
    private var motionBuffer = 0
    private var motionSlot = 0
    /** Debug (adb nrdebug=true): log the frame-wide motion measure now and then. */
    @Volatile var nrDebug = false
    private var nrDebugFrames = 0

    private fun updateColourChange(prepWb: FloatArray, meta: FrameMeta?) {
        val bw = w / 4
        val bh = h / 4
        if (blocksTex == 0) {
            blocksTex = Gl.texture2D(GLES30.GL_RGBA16F, bw, bh, linear = false)
            keepTex = Gl.texture2D(GLES30.GL_RGBA16F, bw, bh, linear = true)
            for (i in 0..1) {
                model8[i] = Gl.texture2D(GLES30.GL_RGBA16F, bw, bh, linear = false)
                model16[i] = Gl.texture2D(GLES30.GL_RGBA16F, bw, bh, linear = false)
            }
            changeFresh = true
        }
        val groups = intArrayOf((bw + 15) / 16, (bh + 15) / 16)
        GLES30.glUseProgram(coarseProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, linearTex)
        GLES31.glBindImageTexture(0, blocksTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform2i(Gl.uniform(coarseProgram, "uRedOffset"), sensor.redOffset.first, sensor.redOffset.second)
        GLES31.glDispatchCompute(groups[0], groups[1], 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)

        modelWrite = 1 - modelWrite
        motionSlot = 1 - motionSlot
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, motionBuffer)
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, motionSlot * 4, 4, zeroInts(1))
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, (2 + motionSlot) * 4, 4, zeroInts(1))
        GLES30.glUseProgram(changeProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, blocksTex)
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, shadingTex)
        Gl.bindTexture(2, GLES30.GL_TEXTURE_2D, model8[1 - modelWrite])
        Gl.bindTexture(3, GLES30.GL_TEXTURE_2D, model16[1 - modelWrite])
        GLES31.glBindImageTexture(0, model8[modelWrite], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES31.glBindImageTexture(1, model16[modelWrite], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES31.glBindImageTexture(2, keepTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform4fv(Gl.uniform(changeProgram, "uWb"), 1, prepWb, 0)
        val (ns, no) = rawNoise(meta)
        GLES30.glUniform2f(Gl.uniform(changeProgram, "uNoiseRaw"), ns, no)
        GLES30.glUniform1i(Gl.uniform(changeProgram, "uFresh"), if (changeFresh) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(changeProgram, "uSlot"), motionSlot)
        GLES30.glUniform2i(Gl.uniform(changeProgram, "uRawSize"), w, h)
        GLES31.glDispatchCompute(groups[0], groups[1], 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        changeFresh = false
    }

    /** Debug: the frame-wide motion measure of the frame just developed (stalls the GPU; adb only). */
    private fun logColourNrMotion() {
        if (!nrDebug || blocksTex == 0 || ++nrDebugFrames % 30 != 0) return
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, motionBuffer)
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 16, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            ?: return
        mapped.order(ByteOrder.nativeOrder())
        val sum = mapped.getInt(motionSlot * 4).toLong() and 0xffffffffL
        val scales = mapped.getInt((2 + motionSlot) * 4).toLong() and 0xffffffffL
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        val windows = 16.0 * (w / 4) * (h / 4)
        val excess = sum / windows - 2.0
        android.util.Log.i("AuthRec", "colour NR: motion excess %.2f (0 still, 1-3 moving), strength %.2f, noise vs profile ×%.2f".format(
            excess, 1 - 0.7 * smoothstep(1.0, 3.0, excess), scales / windows))
    }

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Noise of normalised RAW at brightness x: x · S + O (SENSOR_NOISE_PROFILE, else an estimate). */
    private fun rawNoise(meta: FrameMeta?): Pair<Float, Float> =
        meta?.noise?.let { it[0] to it[1] } ?: fallbackNoise(meta?.iso ?: 400)

    private fun zeroInts(n: Int): ByteBuffer = ByteBuffer.allocateDirect(4 * n).order(ByteOrder.nativeOrder())

    private fun setColourNr(prog: Int, meta: FrameMeta?, outW: Int, outH: Int, p: LogProfile) {
        if (activeCleanup() < 2) {
            // Shed mid-clip: keep the textures until the recording ends. Freeing ~70 MB under a
            // GPU that is already behind is no help, and they're needed again right after.
            if (recording == null) releaseHistory()
            for (unit in 2..3) Gl.bindTexture(unit, GLES30.GL_TEXTURE_2D, noHistory)
            GLES31.glBindImageTexture(2, noHistory, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES30.glUniform1i(Gl.uniform(prog, "uTnr"), 0)
            GLES30.glUniform1i(Gl.uniform(prog, "uTnrOnLog"), 0)
            return
        }
        ensureHistory(outW, outH)
        // The history holds the colour of what's recorded: the clean log when recording log,
        // else the view (preview, baked recordings). Another target or profile: start over.
        val onLog = recording?.bakeLut == false
        if (historyProfile != p || historyOnLog != onLog) {
            historyProfile = p
            historyOnLog = onLog
            historyFresh = true
        }
        GLES30.glUniform1i(Gl.uniform(prog, "uTnrOnLog"), if (onLog) 1 else 0)
        Gl.bindTexture(2, GLES30.GL_TEXTURE_2D, history[historyRead])
        // A photo's passes don't read the change test (uStill), which hasn't run for them.
        Gl.bindTexture(3, GLES30.GL_TEXTURE_2D, if (keepTex != 0) keepTex else noHistory)
        GLES30.glUniform1i(Gl.uniform(prog, "uSlot"), motionSlot)
        GLES30.glUniform1f(Gl.uniform(prog, "uWindows"), ((w / 4) * (h / 4)).toFloat())
        GLES31.glBindImageTexture(2, history[1 - historyRead], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform1i(Gl.uniform(prog, "uTnr"), if (historyFresh) 2 else 1)
        historyFresh = false
        historyRead = 1 - historyRead
        val still = photo?.final == true
        GLES30.glUniform1i(Gl.uniform(prog, "uStill"), if (still) 1 else 0)
        GLES30.glUniform1f(Gl.uniform(prog, "uTnrAlpha"), if (still) PHOTO_NR_ALPHA else if (activeCleanup() >= 3) TNR_STRONG else TNR_NORMAL)
        // Sensor noise for normalised RAW (S·x + O), carried through the prep pass's white
        // balance and shading gains (~1.8 on average: variance scales by gain × S and gain² × O).
        val (ns, no) = rawNoise(meta)
        GLES30.glUniform2f(Gl.uniform(prog, "uNoise"), ns * NR_GAIN, no * NR_GAIN * NR_GAIN)
    }

    /** History images for a develop output of [outW]×[outH] (new ones start over). */
    private fun ensureHistory(outW: Int, outH: Int) {
        val fast = fastNr()
        if (historyW == outW && historyH == outH && historyFast == fast) return
        if (historyW != 0) {
            GLES30.glDeleteTextures(2, history, 0)
            historyW = 0
        }
        // Quality: one colour per 2×2 quad, sampled bilinearly (the colour between quads comes out
        // smooth, like a 4:2:0 decode). Fast: one per pixel, its own layout, read as 2×2 means
        // (see PipelineShaders.fastNr).
        val (hw, hh) = if (fast) outW to outH else (outW + 1) / 2 to (outH + 1) / 2
        for (i in 0..1) history[i] = Gl.texture2D(GLES30.GL_RGBA16F, hw, hh, linear = true)
        historyW = outW
        historyH = outH
        historyFast = fast
        historyFresh = true
    }
    private var historyFast = false

    /** For a lens that doesn't report SENSOR_NOISE_PROFILE: roughly the X14 main camera's, by ISO. */
    private fun fallbackNoise(iso: Int): Pair<Float, Float> = (iso * 6.6e-7f) to (iso * iso * 1.2e-12f)

    private fun releaseHistory() {
        if (blocksTex != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(blocksTex, keepTex), 0)
            GLES30.glDeleteTextures(2, model8, 0)
            GLES30.glDeleteTextures(2, model16, 0)
            blocksTex = 0
        }
        if (historyW == 0) return
        GLES30.glDeleteTextures(2, history, 0)
        historyW = 0
        historyH = 0
    }

    /**
     * Binds the LUT (texture unit 1) and sets the view half's uniforms (PipelineShaders.viewCommon)
     * for [prog], with our log profile [p]: the baked view LUT when one matches the settings, else
     * the source LUT with eDR / conversion / strength done per pixel (while a bake catches up).
     */
    private fun setViewUniforms(prog: Int, p: LogProfile) {
        pendingBaked?.let { (key, lut) ->
            pendingBaked = null
            bakedTex = uploadLutTexture(lut, bakedTex)
            bakedSize = lut.size
            bakedKey = key
        }
        val key = currentViewKey()?.takeIf { bakeViewLut }
        val baked = key != null && key == bakedKey
        if (key != null && !baked) requestBake(key)
        Gl.bindTexture(1, GLES30.GL_TEXTURE_3D, if (baked) bakedTex else lutTex)
        // Writing the full-size log image costs ~100 MB of memory traffic a frame; skip it unless used.
        GLES30.glUniform1i(Gl.uniform(prog, "uWriteLog"), if (recording?.bakeLut == false || pendingLogCapture != null) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(prog, "uWriteView"), if (viewFromLog()) 0 else 1)
        GLES30.glUniform1i(Gl.uniform(prog, "uUseLut"), if (lutEnabled) 1 else 0)
        if (baked) {
            GLES30.glUniform1i(Gl.uniform(prog, "uLutConvert"), 0)
            GLES30.glUniform1i(Gl.uniform(prog, "uTone"), 0)
            GLES30.glUniform1f(Gl.uniform(prog, "uLutSize"), bakedSize.toFloat())
            GLES30.glUniform1f(Gl.uniform(prog, "uLutStrength"), 1f)
            GLES30.glUniform1f(Gl.uniform(prog, "uSaturation"), saturation)
            GLES30.glUniform1f(Gl.uniform(prog, "uVibrance"), vibrance)
            return
        }
        val lutIn = lutInput?.takeIf { it != p }
        GLES30.glUniform1i(Gl.uniform(prog, "uLutConvert"), if (lutIn != null) 1 else 0)
        if (lutIn != null) {
            GLES30.glUniform1i(Gl.uniform(prog, "uLutCurve"), lutIn.shaderId)
            GLES30.glUniformMatrix3fv(Gl.uniform(prog, "uLutGamut"), 1, false,
                ColorMath.toGlColumnMajor(ColorMath.convert(p.primaries, lutIn.primaries)), 0)
        }
        val toneOn = abs(toneHighlights) > 0.001f || abs(toneShadows) > 0.001f
        GLES30.glUniform1i(Gl.uniform(prog, "uTone"), if (toneOn) 1 else 0)
        if (toneOn) {
            GLES30.glUniform1f(Gl.uniform(prog, "uToneHi"), toneHighlights)
            GLES30.glUniform1f(Gl.uniform(prog, "uToneLo"), toneShadows)
            val toXyz = ColorMath.rgbToXyz(p.primaries)
            GLES30.glUniform3f(Gl.uniform(prog, "uLumaW"), toXyz[3].toFloat(), toXyz[4].toFloat(), toXyz[5].toFloat())
        }
        GLES30.glUniform1f(Gl.uniform(prog, "uLutSize"), lutSize.toFloat())
        GLES30.glUniform1f(Gl.uniform(prog, "uLutStrength"), lutStrength)
        GLES30.glUniform1f(Gl.uniform(prog, "uSaturation"), saturation)
        GLES30.glUniform1f(Gl.uniform(prog, "uVibrance"), vibrance)
    }

    private fun drawToScreen() {
        // Letterbox the (possibly rotated) image into the view.
        val quarter = rotation % 2 == 1
        val imgAspect = if (quarter) h.toFloat() / w else w.toFloat() / h
        val viewAspect = viewW.toFloat() / viewH
        val (vw, vh) = if (viewAspect > imgAspect) (viewH * imgAspect).toInt() to viewH else viewW to (viewW / imgAspect).toInt()
        val target = developTarget()
        val fromLog = viewFromLog()
        if (fromLog) makeViewFromLog(target)
        val texW = if (fromLog) w / 2 else target.width
        val texH = if (fromLog) h / 2 else target.height
        // The dials on the viewfinder (the photo just taken: its own finished image, once; a
        // recording with the look baked in: the frame it records; recording log: none, its GPU
        // time goes to the clip).
        val finished = photoFinish?.takeIf { photo?.done == true } ?: recFinish ?: if (finishOn() && recording == null && !fromLog) {
            viewFinish?.takeIf { it.width != texW || it.height != texH }?.let { it.release(); viewFinish = null }
            val octave = kotlin.math.log2(w.toFloat() / texW).roundToInt().coerceIn(0, 2)
            (viewFinish ?: FinishTarget(texW, texH).also { viewFinish = it }).also { applyFinish(target.view, it, viewOctave = octave) }
        } else {
            viewFinish?.release()
            viewFinish = null
            null
        }
        val tex = finished?.out ?: if (fromLog) spViewTex else target.view
        val letterbox = intArrayOf((viewW - vw) / 2, (viewH - vh) / 2, vw, vh)
        val rects = glassRects
        val glass = if (rects.isNotEmpty()) (backdrop ?: GlassBackdrop().also { backdrop = it }) else null
        val timingStart = if (glassTiming && glass == null) { GLES30.glFinish(); SystemClock.elapsedRealtimeNanos() } else 0L
        // The glass's offscreen passes go first so the window's render pass stays in one piece.
        glass?.let {
            it.timing = glassTiming
            // Only frosted panels and the darkening over bright scenes use the blur (clear glass
            // shows the image itself), so it needn't be fresh every frame: every other frame
            // between recordings (half of its ~1 ms), twice a second while recording, where every
            // millisecond of a viewfinder frame comes out of the encoder's budget.
            val now = SystemClock.elapsedRealtimeNanos()
            val refresh = now - lastGlassNs >= if (recording == null) GLASS_REFRESH_IDLE_NS else GLASS_REFRESH_RECORDING_NS
            if (refresh) lastGlassNs = now
            it.prepare(tex, texW, texH, rotation, mirror, letterbox, viewW, viewH, frameCrop, refresh = refresh)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(letterbox[0], letterbox[1], vw, vh)
        val across = if (quarter) texH else texW
        drawTexture(tex, rotation, peakingTexel = if (peaking) w.toFloat() / texW else 0f,
            smooth = beatsWithNoise(across.toFloat() / vw), mirror = mirror)
        drawFrameMask(letterbox[0], letterbox[1], vw, vh)
        glass?.drawShapes(rects, viewW, viewH, density)
        if (timingStart > 0) {
            GLES30.glFinish()
            screenNs += SystemClock.elapsedRealtimeNanos() - timingStart
            if (++screenFrames == 90) {
                android.util.Log.i("AuthRec", "screen draw without glass: %.2f ms a frame".format(screenNs / 90 / 1e6))
                screenFrames = 0
                screenNs = 0
            }
        }
    }

    private var screenFrames = 0
    private var screenNs = 0L

    /**
     * Recording log at full size: the develop pass writes only the log, and the viewfinder's
     * image is made from it at half size when it's drawn ([makeViewFromLog]).
     */
    private fun viewFromLog() = recording?.let { !it.bakeLut && !it.superpixel && !it.scaled } == true

    private var viewLogProgram = 0

    /** The view of the develop pass's log at half size, into the superpixel view texture (unused while recording full size). */
    private fun makeViewFromLog(target: DevelopTarget) {
        GLES30.glUseProgram(viewLogProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, target.log)
        GLES31.glBindImageTexture(0, spViewTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        val p = profile
        setViewUniforms(viewLogProgram, p)
        GLES30.glUniform1i(Gl.uniform(viewLogProgram, "uCurve"), p.shaderId)
        GLES31.glDispatchCompute((w / 2 + 15) / 16, (h / 2 + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    /** For the glass's hairline sizes. */
    private val density = android.content.res.Resources.getSystem().displayMetrics.density

    private var maskProgram = 0

    /**
     * Darkens what a cropped frame leaves out (above and below its band of the image at
     * x, y, w, h on screen) and draws thin frame lines at its edges.
     */
    private fun drawFrameMask(x: Int, y: Int, w: Int, h: Int) {
        val keep = frameCrop
        if (keep >= 0.999f) return
        if (maskProgram == 0) maskProgram = Gl.renderProgram(PipelineShaders.displayVertex, PipelineShaders.solidFragment)
        // The band runs along the screen's long side unless the image is shown turned a quarter.
        val horizontal = rotation % 2 == 0
        val span = if (horizontal) h else w
        val bar = ((1f - keep) / 2f * span).roundToInt()
        GLES30.glUseProgram(maskProgram)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val color = Gl.uniform(maskProgram, "uColor")
        fun fill(sx: Int, sy: Int, sw: Int, sh: Int, r: Float, g: Float, b: Float, a: Float) {
            GLES30.glScissor(sx, sy, sw, sh)
            GLES30.glUniform4f(color, r, g, b, a)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        val line = 3
        if (horizontal) {
            fill(x, y, w, bar, 0f, 0f, 0f, 0.62f)
            fill(x, y + h - bar, w, bar, 0f, 0f, 0f, 0.62f)
            fill(x, y + bar, w, line, 1f, 1f, 1f, 0.55f)
            fill(x, y + h - bar - line, w, line, 1f, 1f, 1f, 0.55f)
        } else {
            fill(x, y, bar, h, 0f, 0f, 0f, 0.62f)
            fill(x + w - bar, y, bar, h, 0f, 0f, 0f, 0.62f)
            fill(x + bar, y, line, h, 1f, 1f, 1f, 0.55f)
            fill(x + w - bar - line, y, line, h, 1f, 1f, 1f, 0.55f)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Debug (Recorder.debugBars): red, green, blue, white, 50 % grey across the top third of a [w]×[h] frame. */
    private fun drawTestBars(w: Int, h: Int) {
        if (maskProgram == 0) maskProgram = Gl.renderProgram(PipelineShaders.displayVertex, PipelineShaders.solidFragment)
        GLES30.glUseProgram(maskProgram)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val colours = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
            floatArrayOf(1f, 1f, 1f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val bw = w / colours.size
        for ((i, c) in colours.withIndex()) {
            GLES30.glScissor(i * bw, h - h / 3, bw, h / 3)
            GLES30.glUniform4f(Gl.uniform(maskProgram, "uColor"), c[0], c[1], c[2], 1f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    /** Mid-recording the mode is fixed by what the encoder was set up for; between recordings see [previewFullRes]. */
    private fun activeSuperpixel() = if (photo?.final == true) false else recording?.superpixel ?: (superpixel || !previewFullRes)

    /**
     * Whether drawing at [scale] source pixels per screen pixel makes noise beat. Bilinear
     * sampling averages two texels at some output pixels and copies one at others; when the scale
     * is close to a whole number that phase drifts slowly, so the noise's strength rises and falls
     * in bands: the front camera's 1632-px preview in a 1600-px box (×1.02) showed a grid every
     * ~50 px. Such scales get the smoothing filter instead (a slight blur, the same everywhere).
     */
    private fun beatsWithNoise(scale: Float): Boolean {
        val off = abs(scale - scale.roundToInt())
        return scale < 3f && off > 0.005f && off < 0.2f
    }

    /**
     * [peakingTexel] > 0 enables focus peaking, sampling that many full-res pixels apart. [crop]:
     * see Recording. [smooth]: B-spline instead of bilinear sampling (see [beatsWithNoise]).
     */
    private fun drawTexture(tex: Int, rotationQuarterTurns: Int, peakingTexel: Float = 0f, crop: FloatArray? = null,
                            smooth: Boolean = false, mirror: Boolean = false) {
        GLES30.glUseProgram(displayProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uImage"), 0)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uRotation"), rotationQuarterTurns)
        val c = crop ?: FULL_FRAME
        GLES30.glUniform4f(Gl.uniform(displayProgram, "uCrop"), c[0], c[1], c[2], c[3])
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uPeaking"), if (peakingTexel > 0) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uSmooth"), if (smooth) 1 else 0)
        GLES30.glUniform1i(Gl.uniform(displayProgram, "uMirror"), if (mirror) 1 else 0)
        GLES30.glUniform2f(Gl.uniform(displayProgram, "uTexel"), peakingTexel / w, peakingTexel / h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    // ---- Photo mode ----

    /** A photo being taken (GL thread only). */
    private var photo: PhotoBurst? = null

    private class PhotoBurst(val frames: Int, val merge: Boolean, val align: Boolean, val keepRaw: Boolean, val cropHeight: Float,
                             val onReference: (Long) -> Unit, val onDone: (Photo?) -> Unit) {
        /** Burst frames taken so far; the first ([taken] 0) is the reference. */
        var taken = 0
        var merged = 0
        /** Frames left out of the merge because their exposure differed from the reference's. */
        var otherExposure = 0
        /** The merged reference is being developed: the photo itself. */
        var final = false
        /** Handed over; the viewfinder shows the photo for one draw, then the preview goes on. */
        var done = false
        var refTimestamp = 0L
        var refMeta: FrameMeta? = null
        var refPrepWb: FloatArray? = null
        var raw: ByteBuffer? = null
        /** The lens's real noise over its profile's (variance), from the first pair; NaN until then. */
        var noiseScale = Float.NaN
        /** The last frame's offset from the reference as a whole, and its tiles' from that: the mean, the most (RAW pixels). */
        var shiftWhole = 0f
        var shiftLastMean = 0f
        var shiftMax = 0f
        /** When the merged frame's develop began (the burst was in). */
        var finalNs = 0L
        val startNs = SystemClock.elapsedRealtimeNanos()
    }

    /**
     * A photo: [image] is the view as the viewfinder shows it (look, eDR, the frame's band, upright
     * in the screen's landscape: the front camera turned, a mirrored one mirrored). [raw]: the
     * reference frame's RAW, tightly packed 16-bit (when asked for), [rawWidth]×[rawHeight].
     */
    class Photo(val image: Bitmap, val timestamp: Long, val meta: FrameMeta?, val raw: ByteBuffer?,
                val rawWidth: Int, val rawHeight: Int, val summary: String)

    /**
     * Photo mode's shutter. The next [frames] frames are merged in RAW into the first (each one
     * aligned to it, then averaged in where it matches within noise: PipelineShaders.photoAlign,
     * photoMerge) while the viewfinder goes on as it was; then the merged frame is developed at
     * full size with Quality colour NR (a few passes over it: see [finishPhoto]). [onDone] gets
     * its view ([cropHeight]: the frame's band, as in recordings) on the GL thread, or null when
     * the burst couldn't finish (renderer released, a recording, another photo). [keepRaw]: the
     * first frame's RAW as well (for a DNG); [onReference] gets that frame's timestamp as soon as
     * it's picked (GL thread), so its capture result can be kept.
     */
    fun takePhoto(frames: Int, keepRaw: Boolean, cropHeight: Float, onReference: (Long) -> Unit = {},
                  onDone: (Photo?) -> Unit) = handler.post {
        if (photo?.done == true) photo = null
        if (released || recording != null || photo != null) return@post onDone(null)
        photo = PhotoBurst(frames.coerceAtLeast(1), debugPhotoMerge, debugPhotoAlign, keepRaw, cropHeight, onReference, onDone)
    }

    /** Gives up on a photo still in its burst. */
    fun cancelPhoto() = post {
        photo?.takeIf { !it.done }?.let {
            photo = null
            releasePhotoTextures()
            it.onDone(null)
        }
    }

    /**
     * The activity leaves the foreground: a photo still in its burst is finished with the frames
     * it has (no more are coming once the camera closes), so the shot isn't lost.
     */
    fun finishPhotoNow() = post {
        val p = photo?.takeIf { !it.done } ?: return@post
        if (p.taken == 0 || windowSurface == EGL14.EGL_NO_SURFACE) {
            photo = null
            releasePhotoTextures()
            p.onDone(null)
            return@post
        }
        egl.makeCurrent(windowSurface)
        finishPhoto(p)
    }

    private var mergeProgram = 0
    private var noiseProgram = 0
    private var diffProgram = 0
    private var greyProgram = 0
    private var downProgram = 0
    private var alignProgram = 0
    private var globalProgram = 0
    private var globalPickProgram = 0
    /** photoGlobal's cost per offset, and the offset it picked (1×1, coarsest-level pixels). */
    private var globalBuffer = 0
    private var globalTex = 0
    /** 0 until tried; -1 when the merge shaders didn't compile (photos then come without the merge). */
    private var mergeState = 0
    /** The burst's first frame (prep output), Σ weight × value per pixel, Σ weight per 2×2 quad. */
    private var refTex = 0
    private var sumTex = 0
    private var weightTex = 0
    /** Per quad, its z² against the reference (PipelineShaders.photoDiff). */
    private var z2Tex = 0
    private var histBuffer = 0
    /** Alignment pyramids (grey, level 0 one value per quad: [PYR_FACTOR]) and each level's tile offsets. */
    private val refPyr = IntArray(PYR_LEVELS)
    private val altPyr = IntArray(PYR_LEVELS)
    private val shiftTex = IntArray(PYR_LEVELS)
    private val pyrW = IntArray(PYR_LEVELS)
    private val pyrH = IntArray(PYR_LEVELS)
    private val tilesW = IntArray(PYR_LEVELS)
    private val tilesH = IntArray(PYR_LEVELS)
    /** 1×1 zero offset: frames merged without alignment, and the coarsest level's guess. */
    private var noShiftTex = 0

    /** After a burst frame's prep pass: the first becomes the reference, the others are aligned and merged into it. */
    private fun burstFrame(p: PhotoBurst, meta: FrameMeta?, prepWb: FloatArray) {
        if (p.final || !ensureMerge()) return
        if (p.taken == 0) {
            p.refPrepWb = prepWb
            runMerge(MERGE_FIRST, 1f, 1f, meta, prepWb, noShiftTex, NO_SHAKE)
            if (p.align) buildPyramid(refPyr, 1f, NO_SHAKE)
            return
        }
        if (!p.merge) return
        val gain = (exposureOf(p.refMeta) / exposureOf(meta)).toFloat()
        // AE moved between frames: brightness scales, but clipped highlights don't. Rare inside ~0.3 s.
        if (!gain.isFinite() || abs(log2(gain)) > 0.25f) {
            p.otherExposure++
            return
        }
        val shake = debugShake(p.taken)
        val shift = if (p.align) align(gain, shake, p) else noShiftTex
        if (p.noiseScale.isNaN()) {
            // The quartile of pure noise's z² (chi-square, 1 degree of freedom) is 0.1015. From the
            // first pair, the closest in time: the least has moved within the scene. (Read back,
            // so it waits for the GPU: only this once.)
            val hist = noiseHistogram(gain, meta, prepWb, shift, shake)
            p.noiseScale = if (hist.sum() == 0) 1f else (histQuantile(hist, 0.25) / 0.1015).toFloat().coerceIn(0.15f, 4f)
        }
        runDiff(gain, meta, prepWb, shift, shake)
        runMerge(MERGE_ADD, gain, p.noiseScale, meta, prepWb, shift, shake)
        p.merged++
    }

    /** Debug (adb photoshake): burst frame [k]'s made-up offset in quads (even RAW pixels), the same for every pixel. */
    private fun debugShake(k: Int): IntArray {
        val s = debugPhotoShake
        if (s == 0) return NO_SHAKE
        val a = k * 2.4
        return intArrayOf((s * kotlin.math.cos(a)).roundToInt(), (s * kotlin.math.sin(a)).roundToInt())
    }

    /** ISO × exposure time of [meta]'s frame (NaN if unknown). */
    private fun exposureOf(meta: FrameMeta?): Double =
        meta?.let { it.iso.toDouble() * it.exposureNs }?.takeIf { it > 0 } ?: Double.NaN

    /** The merge's shaders and images, made at the first photo; false if they can't be (no merge then). */
    private fun ensureMerge(): Boolean {
        if (mergeState == 0) {
            mergeState = runCatching {
                mergeProgram = Gl.computeProgram(PipelineShaders.photoMerge)
                noiseProgram = Gl.computeProgram(PipelineShaders.photoNoise)
                diffProgram = Gl.computeProgram(PipelineShaders.photoDiff)
                greyProgram = Gl.computeProgram(PipelineShaders.photoGrey)
                downProgram = Gl.computeProgram(PipelineShaders.photoDown)
                alignProgram = Gl.computeProgram(PipelineShaders.photoAlign)
                globalProgram = Gl.computeProgram(PipelineShaders.photoGlobal)
                globalPickProgram = Gl.computeProgram(PipelineShaders.photoGlobalPick)
                globalBuffer = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, globalBuffer)
                GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4 * GLOBAL_OFFSETS, zeroInts(GLOBAL_OFFSETS), GLES30.GL_DYNAMIC_DRAW)
                globalTex = Gl.texture2D(GLES30.GL_RGBA32F, 1, 1, linear = false)
                histBuffer = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
                GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer)
                GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4 * HIST_BINS, zeroInts(HIST_BINS), GLES30.GL_DYNAMIC_DRAW)
                noShiftTex = Gl.texture2D(GLES30.GL_RGBA32F, 1, 1, linear = false)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT,
                    ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()))
                1
            }.getOrElse {
                EventLog.log("Photo: the burst merge's shaders failed; photos come without it", it)
                -1
            }
        }
        if (mergeState < 0) return false
        if (refTex == 0) {
            refTex = Gl.texture2D(GLES30.GL_R32F, w, h, linear = false)
            sumTex = Gl.texture2D(GLES30.GL_R32F, w, h, linear = false)
            weightTex = Gl.texture2D(GLES30.GL_R32F, w / 2, h / 2, linear = false)
            z2Tex = Gl.texture2D(GLES30.GL_R32F, w / 2, h / 2, linear = false)
            var lw = w / 2
            var lh = h / 2
            for (k in 0 until PYR_LEVELS) {
                if (k > 0) {
                    lw = (lw + PYR_FACTOR[k] - 1) / PYR_FACTOR[k]
                    lh = (lh + PYR_FACTOR[k] - 1) / PYR_FACTOR[k]
                }
                pyrW[k] = lw
                pyrH[k] = lh
                tilesW[k] = (lw + ALIGN_TILE[k] - 1) / ALIGN_TILE[k]
                tilesH[k] = (lh + ALIGN_TILE[k] - 1) / ALIGN_TILE[k]
                refPyr[k] = Gl.texture2D(GLES30.GL_R32F, lw, lh, linear = false)
                altPyr[k] = Gl.texture2D(GLES30.GL_R32F, lw, lh, linear = false)
                shiftTex[k] = Gl.texture2D(GLES30.GL_RGBA32F, tilesW[k], tilesH[k], linear = false)
            }
        }
        return true
    }

    /** ~150 MB at 4K: freed after each photo. */
    private fun releasePhotoTextures() {
        if (refTex == 0) return
        GLES30.glDeleteTextures(4, intArrayOf(refTex, sumTex, weightTex, z2Tex), 0)
        GLES30.glDeleteTextures(PYR_LEVELS, refPyr, 0)
        GLES30.glDeleteTextures(PYR_LEVELS, altPyr, 0)
        GLES30.glDeleteTextures(PYR_LEVELS, shiftTex, 0)
        refTex = 0
        sumTex = 0
        weightTex = 0
        z2Tex = 0
    }

    /** This frame's grey pyramid (PipelineShaders.photoGrey, photoDown) into [pyr]. */
    private fun buildPyramid(pyr: IntArray, gain: Float, shake: IntArray) {
        GLES30.glUseProgram(greyProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, linearTex)
        GLES31.glBindImageTexture(0, pyr[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
        GLES30.glUniform1f(Gl.uniform(greyProgram, "uGain"), gain)
        GLES30.glUniform2i(Gl.uniform(greyProgram, "uDebugShift"), shake[0], shake[1])
        GLES31.glDispatchCompute((pyrW[0] + 15) / 16, (pyrH[0] + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        GLES30.glUseProgram(downProgram)
        for (k in 1 until PYR_LEVELS) {
            Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, pyr[k - 1])
            GLES31.glBindImageTexture(0, pyr[k], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
            GLES30.glUniform1i(Gl.uniform(downProgram, "uFactor"), PYR_FACTOR[k])
            GLES31.glDispatchCompute((pyrW[k] + 15) / 16, (pyrH[k] + 15) / 16, 1)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        }
    }

    /**
     * Where each tile of the reference is in this frame (PipelineShaders.photoAlign), coarse to
     * fine; returns the last level's offsets (quads, per [PipelineShaders.PHOTO_SHIFT_TILE]²-quad
     * tile). Reads them back for the event log (a photo's burst already waits for the GPU).
     */
    private fun align(gain: Float, shake: IntArray, p: PhotoBurst): Int {
        buildPyramid(altPyr, gain, shake)
        // The whole frame's offset (PipelineShaders.photoGlobal): the coarsest tiles' guess.
        val top = PYR_LEVELS - 1
        GLES30.glUseProgram(globalProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, refPyr[top])
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, altPyr[top])
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, globalBuffer)
        GLES30.glUniform1i(Gl.uniform(globalProgram, "uRadius"), GLOBAL_RADIUS)
        GLES31.glDispatchCompute(GLOBAL_OFFSETS, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES30.glUseProgram(globalPickProgram)
        GLES31.glBindImageTexture(0, globalTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F)
        GLES30.glUniform1i(Gl.uniform(globalPickProgram, "uRadius"), GLOBAL_RADIUS)
        GLES31.glDispatchCompute(1, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)

        val prog = alignProgram
        GLES30.glUseProgram(prog)
        for (k in top downTo 0) {
            val coarsest = k == top
            Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, refPyr[k])
            Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, altPyr[k])
            Gl.bindTexture(2, GLES30.GL_TEXTURE_2D, if (coarsest) globalTex else shiftTex[k + 1])
            GLES31.glBindImageTexture(0, shiftTex[k], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F)
            GLES30.glUniform1i(Gl.uniform(prog, "uTile"), ALIGN_TILE[k])
            GLES30.glUniform1i(Gl.uniform(prog, "uRadius"), ALIGN_RADIUS[k])
            // The whole frame's offset is in this level's pixels already, one "tile" over everything.
            GLES30.glUniform1i(Gl.uniform(prog, "uCoarseScale"), if (coarsest) 1 else PYR_FACTOR[k + 1])
            GLES30.glUniform1i(Gl.uniform(prog, "uCoarseTile"), if (coarsest) 1 shl 20 else ALIGN_TILE[k + 1])
            GLES30.glUniform1i(Gl.uniform(prog, "uSubpixel"), if (k == 0) 1 else 0)
            GLES31.glDispatchCompute(tilesW[k], tilesH[k], 1)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        }
        // For the event log: the last frame's (reading back waits for the GPU).
        if (p.taken == p.frames - 1) runCatching { readShifts(p, shake) }
        return shiftTex[0]
    }

    /**
     * The last frame's offsets into [p]'s summary (RAW pixels, a debug shake's own taken off): the
     * whole frame's, and how far the tiles went from it, on average and at most.
     */
    private fun readShifts(p: PhotoBurst, shake: IntArray) {
        val global = readFloats(globalTex, 1, 1)
        val gx = global.get(0) * GLOBAL_PX / 2
        val gy = global.get(1) * GLOBAL_PX / 2
        p.shiftWhole = 2f * kotlin.math.hypot(gx + shake[0], gy + shake[1])
        val n = tilesW[0] * tilesH[0]
        val f = readFloats(shiftTex[0], tilesW[0], tilesH[0])
        var sum = 0.0
        for (i in 0 until n) {
            val len = 2f * kotlin.math.hypot(f.get(4 * i) - gx, f.get(4 * i + 1) - gy)
            sum += len
            if (len > p.shiftMax) p.shiftMax = len
        }
        p.shiftLastMean = (sum / n).toFloat()
    }

    /** An RGBA32F image read back (waits for the GPU). */
    private fun readFloats(tex: Int, width: Int, height: Int): java.nio.FloatBuffer {
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        val buf = ByteBuffer.allocateDirect(16 * width * height).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        return buf.asFloatBuffer()
    }

    private fun setMergeUniforms(prog: Int, gain: Float, meta: FrameMeta?, prepWb: FloatArray, shift: Int, shake: IntArray) {
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, shadingTex)
        Gl.bindTexture(2, GLES30.GL_TEXTURE_2D, shift)
        val (ns, no) = rawNoise(meta)
        GLES30.glUniform4fv(Gl.uniform(prog, "uWb"), 1, prepWb, 0)
        GLES30.glUniform2f(Gl.uniform(prog, "uNoise"), ns, no)
        GLES30.glUniform1f(Gl.uniform(prog, "uGain"), gain)
        GLES30.glUniform2i(Gl.uniform(prog, "uRedOffset"), sensor.redOffset.first, sensor.redOffset.second)
        GLES30.glUniform2i(Gl.uniform(prog, "uDebugShift"), shake[0], shake[1])
    }

    /** PipelineShaders.photoDiff: each quad's z² against the reference, for the merge's weights. */
    private fun runDiff(gain: Float, meta: FrameMeta?, prepWb: FloatArray, shift: Int, shake: IntArray) {
        val prog = diffProgram
        GLES30.glUseProgram(prog)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, linearTex)
        GLES31.glBindImageTexture(0, refTex, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F)
        GLES31.glBindImageTexture(1, z2Tex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
        setMergeUniforms(prog, gain, meta, prepWb, shift, shake)
        GLES31.glDispatchCompute((w / 2 + 15) / 16, (h / 2 + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    /** One PipelineShaders.photoMerge pass ([MERGE_FIRST], [MERGE_ADD] or [MERGE_RESOLVE] into the prep image). */
    private fun runMerge(mode: Int, gain: Float, scale: Float, meta: FrameMeta?, prepWb: FloatArray, shift: Int, shake: IntArray) {
        val prog = mergeProgram
        val resolve = mode == MERGE_RESOLVE
        GLES30.glUseProgram(prog)
        // Resolving writes the prep image, which isn't read then: the sampler gets the reference.
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, if (resolve) refTex else linearTex)
        Gl.bindTexture(3, GLES30.GL_TEXTURE_2D, z2Tex)
        GLES31.glBindImageTexture(0, if (resolve) linearTex else refTex, 0, false, 0, GLES31.GL_READ_WRITE, GLES30.GL_R32F)
        GLES31.glBindImageTexture(1, sumTex, 0, false, 0, GLES31.GL_READ_WRITE, GLES30.GL_R32F)
        GLES31.glBindImageTexture(2, weightTex, 0, false, 0, GLES31.GL_READ_WRITE, GLES30.GL_R32F)
        setMergeUniforms(prog, gain, meta, prepWb, shift, shake)
        GLES30.glUniform1i(Gl.uniform(prog, "uMode"), mode)
        GLES30.glUniform1f(Gl.uniform(prog, "uScale"), scale)
        GLES31.glDispatchCompute((w / 2 + 15) / 16, (h / 2 + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    /** PipelineShaders.photoNoise's histogram of this frame against the reference (waits for the GPU: photos only). */
    private fun noiseHistogram(gain: Float, meta: FrameMeta?, prepWb: FloatArray, shift: Int, shake: IntArray): IntArray {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer)
        GLES30.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4 * HIST_BINS, zeroInts(HIST_BINS))
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, histBuffer)
        val prog = noiseProgram
        GLES30.glUseProgram(prog)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, linearTex)
        GLES31.glBindImageTexture(0, refTex, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F)
        setMergeUniforms(prog, gain, meta, prepWb, shift, shake)
        GLES31.glDispatchCompute((w / 8 + 15) / 16, (h / 8 + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        val counts = IntArray(HIST_BINS)
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer)
        (GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4 * HIST_BINS, GLES30.GL_MAP_READ_BIT) as? ByteBuffer)?.let {
            it.order(ByteOrder.nativeOrder()).asIntBuffer().get(counts)
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        }
        return counts
    }

    /** The z² (profile scale) below which [share] of [hist]'s quads lie (bin k holds 2^((k-32)/4) up to the next). */
    private fun histQuantile(hist: IntArray, share: Double): Double {
        val target = hist.sum() * share
        var below = 0.0
        for (k in hist.indices) {
            if (hist[k] > 0 && below + hist[k] >= target) return 2.0.pow((k - 32 + (target - below) / hist[k]) / 4.0)
            below += hist[k]
        }
        return 2.0.pow((HIST_BINS - 32) / 4.0)
    }

    /**
     * The burst is in: the merged reference goes back into the prep image (just the reference
     * when nothing merged) and is developed at full size with Quality colour NR: [PHOTO_NR_PASSES]
     * passes over the same frame, each blending in the colour the last one left (half size,
     * clamped to its neighbours', see PipelineShaders.developB), a spatial filter on a frame the
     * merge already averaged in time. The burst's own frames moved (handheld), so colour NR
     * warmed over them left ghosts (owner, 2026-11-01). Then the dials, and the view is read back.
     */
    private fun finishPhoto(p: PhotoBurst) {
        p.finalNs = SystemClock.elapsedRealtimeNanos()
        val shot = runCatching {
            if (refTex != 0) {
                val meta = p.refMeta
                val wb = p.refPrepWb ?: floatArrayOf(1f, 1f, 1f, 1f)
                runMerge(MERGE_RESOLVE, 1f, 1f, meta, wb, noShiftTex, NO_SHAKE)
            }
            p.final = true
            historyFresh = true
            repeat(PHOTO_NR_PASSES) { develop(p.refMeta) }
            val image = if (finishOn()) {
                FinishTarget(w, h).also {
                    photoFinish = it
                    applyFinish(viewTex, it, viewOctave = 0)
                }.out
            } else viewTex
            readView(image, w, h, p.cropHeight)
        }.onFailure { EventLog.log("Photo failed", it) }.getOrNull()
        releasePhotoTextures()
        p.done = true
        val m = p.refMeta
        val summary = "Photo ${w}x${(h * p.cropHeight).roundToInt()} from ${p.taken} frames: ${p.merged} merged" +
            (if (p.merged > 0) " (noise ×%.2f the profile".format(p.noiseScale) +
                (if (p.align) ", aligned: the last frame %.1f px off, its tiles %.1f px from that on average, up to %.1f px)".format(p.shiftWhole, p.shiftLastMean, p.shiftMax)
                else ", not aligned)")
            else "") +
            (if (p.otherExposure > 0) ", ${p.otherExposure} left out (exposure changed)" else "") +
            (if (!p.merge) ", merge off" else if (mergeState < 0) ", no merge (shaders failed)" else "") +
            (if (debugPhotoShake > 0) ", debug shake $debugPhotoShake" else "") +
            ", ISO ${m?.iso ?: "-"} 1/${m?.exposureNs?.takeIf { it > 0 }?.let { (1e9 / it).roundToInt() } ?: "-"}, gain %+.1f EV".format(totalGainEv) +
            (if (finishOn()) ", texture %+.0f clarity %+.0f dehaze %+.0f".format(photoTexture * 100, photoClarity * 100, photoDehaze * 100) else "") +
            ", done in %.0f ms (burst %.0f, develop %.0f)".format((SystemClock.elapsedRealtimeNanos() - p.startNs) / 1e6,
                (p.finalNs - p.startNs) / 1e6, (SystemClock.elapsedRealtimeNanos() - p.finalNs) / 1e6)
        p.onDone(shot?.let { Photo(it, p.refTimestamp, m, p.raw, w, h, summary) })
    }

    /** [tex] ([width]×[height]) read back at 8 bits (the JPEG's), upright and cropped to [cropHeight] like a recording's frame. */
    private fun readView(tex: Int, width: Int, height: Int, cropHeight: Float): Bitmap {
        val keep = cropHeight.coerceIn(0.1f, 1f)
        val outH = ((height * keep).roundToInt() / 2 * 2).coerceAtMost(height)
        val band = outH.toFloat() / height
        val top = (1f - band) / 2f
        val out = Gl.texture2D(GLES30.GL_RGBA8, width, outH, linear = true)
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, out, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, 0, width, outH)
        // The band upside down (negative height): glReadPixels returns the bottom row first, so the
        // bitmap comes out upright without a flipped copy of 50 MB.
        drawTexture(tex, rotationQuarterTurns = if (rotation == 2) 2 else 0, crop = floatArrayOf(0f, top + band, 1f, -band), mirror = mirror)
        val pixels = ByteBuffer.allocateDirect(width * outH * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(out), 0)
        val bmp = Bitmap.createBitmap(width, outH, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bmp.copyPixelsFromBuffer(pixels)
        return bmp
    }

    // ---- Texture / Clarity / Dehaze (PipelineShaders.finishDown, photoFinish) ----

    /**
     * The dials' images for a view of [width]×[height]: the two pyramids, a texture per level, and
     * the finished view (16-bit float like the view: a 10-bit recording draws it).
     */
    private class FinishTarget(val width: Int, val height: Int) {
        val levelW = IntArray(FINISH_LEVELS)
        val levelH = IntArray(FINISH_LEVELS)
        val brightness = IntArray(FINISH_LEVELS)
        val glow = IntArray(FINISH_LEVELS)
        init {
            var lw = (width + 1) / 2
            var lh = (height + 1) / 2
            for (k in 0 until FINISH_LEVELS) {
                levelW[k] = lw
                levelH[k] = lh
                brightness[k] = Gl.texture2D(GLES30.GL_RGBA16F, lw, lh, linear = true)
                glow[k] = Gl.texture2D(GLES30.GL_RGBA16F, lw, lh, linear = true)
                lw = maxOf(1, (lw + 1) / 2)
                lh = maxOf(1, (lh + 1) / 2)
            }
        }
        val out = Gl.texture2D(GLES30.GL_RGBA16F, width, height, linear = true)
        /** finishUp's images, on the grid of the level read as the full photo's level 1 ([coarseLevel]). */
        var coarseLevel = -1
            private set
        var coarse = 0
            private set
        var coarseGlow = 0
            private set
        fun coarseAt(level: Int) {
            if (level == coarseLevel) return
            releaseCoarse()
            coarse = Gl.texture2D(GLES30.GL_RGBA16F, levelW[level], levelH[level], linear = true)
            coarseGlow = Gl.texture2D(GLES30.GL_RGBA16F, levelW[level], levelH[level], linear = true)
            coarseLevel = level
        }
        private fun releaseCoarse() {
            if (coarseLevel >= 0) GLES30.glDeleteTextures(2, intArrayOf(coarse, coarseGlow), 0)
            coarseLevel = -1
        }
        fun release() {
            GLES30.glDeleteTextures(FINISH_LEVELS, brightness, 0)
            GLES30.glDeleteTextures(FINISH_LEVELS, glow, 0)
            GLES30.glDeleteTextures(1, intArrayOf(out), 0)
            releaseCoarse()
        }
    }
    /** The viewfinder's (half size), and the photo's (full size, until the photo has been shown). */
    private var viewFinish: FinishTarget? = null
    private var photoFinish: FinishTarget? = null
    private var finishDownProgram = 0
    private var finishUpProgram = 0
    private var finishProgram = 0

    /** Photo mode's dials on [view] into [t] ([viewOctave]: 0 the full photo, 1 half its size). */
    private fun applyFinish(view: Int, t: FinishTarget, viewOctave: Int) {
        if (finishProgram == 0) {
            finishDownProgram = Gl.computeProgram(PipelineShaders.finishDown)
            finishUpProgram = Gl.computeProgram(PipelineShaders.finishUp)
            finishProgram = Gl.computeProgram(PipelineShaders.photoFinish)
        }
        GLES30.glUseProgram(finishDownProgram)
        for (k in 0 until FINISH_LEVELS) {
            Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, if (k == 0) view else t.brightness[k - 1])
            // (Level 0 reads no glow; never a texture this pass writes.)
            Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, if (k == 0) view else t.glow[k - 1])
            GLES31.glBindImageTexture(0, t.brightness[k], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES31.glBindImageTexture(1, t.glow[k], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES30.glUniform1i(Gl.uniform(finishDownProgram, "uLevel"), k)
            GLES31.glDispatchCompute((t.levelW[k] + 15) / 16, (t.levelH[k] + 15) / 16, 1)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        }
        // The level as blurred as level [n] of the full photo's, for a view [viewOctave] octaves smaller.
        fun level(n: Int) = (n - viewOctave).coerceIn(0, FINISH_LEVELS - 1)
        t.coarseAt(level(1))
        GLES30.glUseProgram(finishUpProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, t.brightness[level(3)])
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, t.brightness[level(5)])
        (1..5).forEachIndexed { i, n -> Gl.bindTexture(2 + i, GLES30.GL_TEXTURE_2D, t.glow[level(n)]) }
        GLES31.glBindImageTexture(0, t.coarse, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES31.glBindImageTexture(1, t.coarseGlow, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform1i(Gl.uniform(finishUpProgram, "uGlow"), if (photoDehaze < 0f) 1 else 0)
        GLES31.glDispatchCompute((t.levelW[t.coarseLevel] + 15) / 16, (t.levelH[t.coarseLevel] + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        GLES30.glUseProgram(finishProgram)
        Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, view)
        Gl.bindTexture(1, GLES30.GL_TEXTURE_2D, t.brightness[level(0)])
        Gl.bindTexture(2, GLES30.GL_TEXTURE_2D, t.brightness[level(1)])
        Gl.bindTexture(3, GLES30.GL_TEXTURE_2D, t.coarse)
        Gl.bindTexture(4, GLES30.GL_TEXTURE_2D, t.coarseGlow)
        GLES31.glBindImageTexture(0, t.out, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
        GLES30.glUniform1i(Gl.uniform(finishProgram, "uB0Self"), if (viewOctave > 0) 1 else 0)
        GLES30.glUniform1f(Gl.uniform(finishProgram, "uTexture"), photoTexture)
        GLES30.glUniform1f(Gl.uniform(finishProgram, "uClarity"), photoClarity)
        GLES30.glUniform1f(Gl.uniform(finishProgram, "uDehaze"), photoDehaze)
        GLES31.glDispatchCompute((t.width + 15) / 16, (t.height + 15) / 16, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    /** The dials on a recording with the look baked in (its develop size), drawn into the clip and the viewfinder. */
    private var recFinish: FinishTarget? = null

    private fun finishForRecording(rec: Recording) {
        if (!rec.bakeLut || !finishOn() || viewFromLog()) {
            recFinish?.release()
            recFinish = null
            return
        }
        val target = developTarget()
        recFinish?.takeIf { it.width != target.width || it.height != target.height }?.let { it.release(); recFinish = null }
        val t = recFinish ?: FinishTarget(target.width, target.height).also { recFinish = it }
        // The dials' radii are the full photo's: a 2K clip is a level smaller.
        val octave = kotlin.math.log2(w.toFloat() / target.width).roundToInt().coerceIn(0, 2)
        applyFinish(target.view, t, viewOctave = octave)
    }

    // ---- A still while recording ----

    private class Snap(val cropHeight: Float, val onDone: (Photo?) -> Unit)
    private var pendingSnap: Snap? = null

    /**
     * A still from the recording: the next frame as it's recorded (its size, colour NR and all),
     * with the look (made from the log when the look isn't baked in). [onDone] gets it on the GL
     * thread, null when there's no recording.
     */
    fun takeSnap(cropHeight: Float, onDone: (Photo?) -> Unit) = handler.post {
        if (released || recording == null || pendingSnap != null) return@post onDone(null)
        pendingSnap = Snap(cropHeight, onDone)
    }

    private fun takeSnapNow(snap: Snap, timestamp: Long, meta: FrameMeta?): Photo? {
        val rec = recording ?: return null
        val t0 = SystemClock.elapsedRealtimeNanos()
        val target = developTarget()
        // A clip with the look and the dials baked in: what it records.
        val view = recFinish?.out ?: if (viewFromLog()) {
            // Recording log at full size: the develop pass wrote only the log.
            GLES30.glUseProgram(viewLogProgram)
            Gl.bindTexture(0, GLES30.GL_TEXTURE_2D, target.log)
            GLES31.glBindImageTexture(0, viewTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            setViewUniforms(viewLogProgram, profile)
            GLES30.glUniform1i(Gl.uniform(viewLogProgram, "uCurve"), profile.shaderId)
            GLES31.glDispatchCompute((w + 15) / 16, (h + 15) / 16, 1)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            viewTex
        } else target.view
        val image = readView(view, target.width, target.height, snap.cropHeight)
        val summary = "Snap ${image.width}x${image.height} from the recording at %.1f s, ISO %s 1/%s, read in %.0f ms".format(
            if (rec.firstTimestamp >= 0) (timestamp - rec.firstTimestamp) / 1e9 else 0.0, meta?.iso ?: "-",
            meta?.exposureNs?.takeIf { it > 0 }?.let { (1e9 / it).roundToInt() } ?: "-",
            (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        return Photo(image, timestamp, meta, null, w, h, summary)
    }

    /** The RAW plane without row padding (DngCreator.writeByteBuffer wants it dense). */
    private fun copyRaw(image: Image): ByteBuffer {
        val plane = image.planes[0]
        val src = plane.buffer.duplicate()
        val row = w * 2
        val out = ByteBuffer.allocateDirect(row * h).order(ByteOrder.nativeOrder())
        for (y in 0 until h) {
            src.clear()
            src.limit(y * plane.rowStride + row)
            src.position(y * plane.rowStride)
            out.put(src)
        }
        out.rewind()
        return out
    }

    /**
     * Grabs the clean log image of the next frame at [width] px wide (8-bit is plenty for
     * previewing a look). [onFrame] runs on the GL thread.
     */
    fun captureLogFrame(width: Int, onFrame: (Bitmap) -> Unit) = post { pendingLogCapture = width to onFrame }

    /** Set on the GL thread; the next processed frame writes the log image and hands it over. */
    private var pendingLogCapture: Pair<Int, (Bitmap) -> Unit>? = null

    private fun deliverLogCapture(width: Int, onFrame: (Bitmap) -> Unit) {
        val height = width * h / w
        val tex = Gl.texture2D(GLES30.GL_RGBA8, width, height, linear = true)
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glViewport(0, 0, width, height)
        drawTexture(developTarget().log, rotationQuarterTurns = 0)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bmp.copyPixelsFromBuffer(pixels)
        // Our draw puts texture row 0 at the top of the viewport, which glReadPixels returns last.
        val upright = Bitmap.createBitmap(bmp, 0, 0, width, height, Matrix().apply { preScale(1f, -1f) }, false)
        bmp.recycle()
        onFrame(upright)
    }

    private fun uploadShading(gains: FloatArray, cols: Int, rows: Int) {
        if (shadingTex != 0) GLES30.glDeleteTextures(1, intArrayOf(shadingTex), 0)
        shadingTex = Gl.texture2D(GLES30.GL_RGBA16F, cols, rows, linear = true)
        shadingDims = cols to rows
        updateShading(gains)
    }

    private fun updateShading(gains: FloatArray) {
        val (cols, rows) = shadingDims
        val buf = ByteBuffer.allocateDirect(gains.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(gains)
        buf.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadingTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, cols, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
    }

    private fun uploadLut(lut: CubeLut) {
        lutTex = uploadLutTexture(lut, lutTex)
        lutSize = lut.size
    }

    /** [lut] as a new RGBA16F 3D texture (replacing [old], if any); returns the texture. */
    private fun uploadLutTexture(lut: CubeLut, old: Int): Int {
        if (old != 0) GLES30.glDeleteTextures(1, intArrayOf(old), 0)
        val n = lut.size
        val rgba = FloatArray(n * n * n * 4)
        for (i in 0 until n * n * n) {
            rgba[i * 4] = lut.rgb[i * 3]
            rgba[i * 4 + 1] = lut.rgb[i * 3 + 1]
            rgba[i * 4 + 2] = lut.rgb[i * 3 + 2]
            rgba[i * 4 + 3] = 1f
        }
        val buf = ByteBuffer.allocateDirect(rgba.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(rgba)
        buf.rewind()
        val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, GLES30.GL_RGBA16F, n, n, n)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, n, n, n, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        for (param in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_LINEAR)
        }
        for (param in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_CLAMP_TO_EDGE)
        }
        return tex
    }

    private fun updateStats(msPerFrame: Double, meta: FrameMeta?, frames: Int) {
        gpuMsAvg = if (gpuMsAvg == 0.0) msPerFrame else gpuMsAvg * 0.9 + msPerFrame * 0.1
        frameCount += frames
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1000) {
            val fps = frameCount * 1000.0 / (now - fpsWindowStart)
            val viewFps = viewCount * 1000.0 / (now - fpsWindowStart)
            frameCount = 0
            viewCount = 0
            fpsWindowStart = now
            onStats(Stats(gpuMsAvg, fps, meta, recording?.dropped ?: 0, viewFps))
        }
    }

    private fun releaseWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            egl.makeCurrent(EGL14.EGL_NO_SURFACE)
            egl.destroySurface(windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
    }

    companion object {
        /** The 10-bit config list goes to the event log once per process, not every lens switch. */
        @Volatile private var tenBitLogged = false

        /** Extra over "match the ISP's brightness", from daylight fits against ISP JPEGs. */
        const val AUTO_BIAS_EV = 0.3f
        /** Until the first measurement arrives. */
        const val DEFAULT_GAIN_EV = 2f
        /** Night limit (see [nightKneeIso]): where it starts (the EV slider's amber point) and how much of each stop above it still lifts. */
        /** The grid of RAW samples each frame is measured on (auto gain, clipping, EVO). */
        private const val GRID_COLS = 63
        private const val GRID_ROWS = 47
        /** EVO brightens only below this effective ISO (sensor ISO × gain): scenes with light to spare. */
        private const val EVO_PUSH_MAX_ISO = 1600f
        /** The largest change of exposure taken for a short trip, in EV, and the frames it's given to come back (see steadyEv). */
        private const val STEADY_MAX = 0.5f
        private const val STEADY_FRAMES = 15
        /** The shutter time the night limit's ISO is counted at: 1/50 s (see updateNightCut). */
        private const val NIGHT_REF_NS = 20_000_000f
        const val NIGHT_KNEE_ISO = 3200f
        private const val NIGHT_SLOPE = 0.5f
        /** Frames over which a recording's frame time is averaged (see watchBudget), and those skipped first. */
        private const val BUDGET_WINDOW = 15
        private const val BUDGET_SKIP = 10
        /** While recording and behind, the preview is still drawn this often (~10 fps). */
        private const val VIEW_INTERVAL_NS = 100_000_000L
        /** The glass's blur of the preview is refreshed at most this often: between recordings (every 2nd frame at 24–30 fps) and while recording. */
        private const val GLASS_REFRESH_IDLE_NS = 50_000_000L
        private const val GLASS_REFRESH_RECORDING_NS = 500_000_000L
        /** Frames taken from the reader at most (it has 10; the camera needs some): preview, recording. */
        private const val MAX_PENDING = 4
        private const val MAX_PENDING_RECORDING = 8
        /** Frames a recording lets wait for the oldest one's capture result (see readyToDevelop). */
        private const val WAIT_RECORDING = 5
        /** Debug (adb slowframe=ms): each recorded frame holds this thread that much longer, as a hot phone's GPU would. */
        @Volatile var debugSlowFrameMs = 0
        /** Debug (adb photomerge=false): photos without the burst merge. */
        @Volatile var debugPhotoMerge = true
        /** Debug (adb photoalign=false): the burst merged without aligning its frames (as before 2026-11-02). */
        @Volatile var debugPhotoAlign = true
        /** Debug (adb photoshake=quads): each burst frame read that far off (a known shake for the alignment to undo). */
        @Volatile var debugPhotoShake = 0
        /** For the preview's waiting rule (recordings use their own frame rate). */
        private const val PREVIEW_FRAME_NS = 33_333_333L
        /** Time spent on recorded frames before the GL thread looks at its other work again. */
        private const val PASS_BUDGET_NS = 500_000_000L
        /** Frames the auto gain needs to settle on a new lens (~1.5 s at 30 fps). */
        private const val FIRST_GAIN_FRAMES = 45
        /** History weight of colour NR where nothing moved: "+ Colour" / "+ Colour+". */
        private const val TNR_NORMAL = 0.7f
        private const val TNR_STRONG = 0.85f
        /** Average white balance × shading gain between the RAW and the prep pass's output. */
        private const val NR_GAIN = 1.8f
        /** Photo mode: colour NR's passes over the merged photo and each one's history weight (see [finishPhoto]). */
        private const val PHOTO_NR_PASSES = 4
        private const val PHOTO_NR_ALPHA = 0.75f
        /**
         * The alignment pyramid (level 0: one grey value per quad, then ÷2, ÷4: 1/16 of the RAW),
         * each level's tile side and search radius around its guess (the last level's tile is
         * PipelineShaders.PHOTO_SHIFT_TILE quads, 32 RAW px).
         */
        private const val PYR_LEVELS = 3
        private val PYR_FACTOR = intArrayOf(1, 2, 4)
        private val ALIGN_TILE = intArrayOf(PipelineShaders.PHOTO_SHIFT_TILE, 8, 8)
        private val ALIGN_RADIUS = intArrayOf(1, 3, 4)
        /** The whole frame's offset is looked for within this many coarsest-level pixels (16 RAW px each): ±96 px. */
        private const val GLOBAL_RADIUS = 6
        private const val GLOBAL_OFFSETS = (2 * GLOBAL_RADIUS + 1) * (2 * GLOBAL_RADIUS + 1)
        /** RAW pixels per coarsest-level pixel. */
        private const val GLOBAL_PX = 16
        private val NO_SHAKE = intArrayOf(0, 0)
        /** The dials' pyramid levels: down to 1/64 of the full photo (PipelineShaders.photoFinish). */
        private const val FINISH_LEVELS = 6
        /** PipelineShaders.photoMerge's modes, and photoNoise's histogram size. */
        private const val MERGE_FIRST = 0
        private const val MERGE_ADD = 2
        private const val MERGE_RESOLVE = 3
        private const val HIST_BINS = 64
        private val FULL_FRAME = floatArrayOf(0f, 0f, 1f, 1f)
    }

    private fun EglCore.swapWindow() = EGL14.eglSwapBuffers(display, windowSurface)
}
