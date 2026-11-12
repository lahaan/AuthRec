import com.authrec.color.*;
import com.authrec.gl.Evo;
import java.util.Random;

/** Runs the app's Evo class on made-up scenes (see run.sh): what it settles at, how fast it moves, a pan over time. */
public class EvoSim {
    static final int COLS = 63, ROWS = 47;
    static final float STEP = 1f / 6f;
    /** How much further than asked the camera moves (a clipped scene fools its metering): 1 = as asked. */
    static float OVER = 1f;
    static float GUARD = Evo.DIM_WEIGHT;
    static int STRENGTH = 1;

    /** A scene: brightness per grid point as the view shows it without EVO (middle grey 0.18). */
    interface Scene { float at(int i, int j, Random r); }

    static float[] render(Scene s, long seed) {
        Random r = new Random(seed);
        float[] l = new float[COLS * ROWS];
        for (int j = 0; j < ROWS; j++) for (int i = 0; i < COLS; i++) l[j * COLS + i] = s.at(i, j, r);
        return l;
    }

    static float u(Random r, float a, float b) { return a + (b - a) * r.nextFloat(); }
    static boolean disc(int i, int j, float cx, float cy, float rad) { return (i - cx) * (i - cx) + (j - cy) * (j - cy) <= rad * rad; }

    static Scene daylight = (i, j, r) -> j < 14 ? u(r, 0.9f, 1.3f) : u(r, 0.04f, 0.45f);
    static Scene sunset = (i, j, r) -> {
        if (disc(i, j, 40, 12, 1.5f)) return 150f;                       // the sun
        if (j < 22) return 2.5f + 6f * (1f - j / 22f) + u(r, 0f, 0.5f);  // bright sky, brighter towards the top
        if (j < 40) return u(r, 0.008f, 0.03f);                          // foliage
        return u(r, 0.03f, 0.12f);
    };
    static Scene plate = (i, j, r) -> {
        if (disc(i, j, 31, 23, 21)) return disc(i, j, 31, 23, 8) ? u(r, 0.1f, 0.5f) : u(r, 2.6f, 3.4f); // food on a white plate
        return u(r, 0.035f, 0.05f);                                                                      // dark grey table
    };
    static Scene night = (i, j, r) -> {
        if (j == 10 && i > 8 && i < 30) return 60f;                                 // neon tube
        if (j == 13 && i > 8 && i < 24) return 60f;
        if (disc(i, j, 45, 30, 0.8f) || disc(i, j, 50, 30, 0.8f)) return 300f;      // headlights
        if (disc(i, j, 45, 30, 2.5f) || disc(i, j, 50, 30, 2.5f)) return 1.2f;      // their glow
        if (disc(i, j, 12, 4, 0.8f) || disc(i, j, 55, 6, 0.8f)) return 200f;        // street lamps
        if (i > 34 && i < 44 && j > 14 && j < 20) return u(r, 1.0f, 1.8f);          // a lit shop window
        return u(r, 0.004f, 0.03f);                                                 // the street
    };
    // Through a LUT that goes white 2 stops over middle grey (white 0.67): an overcast sky over a garden, a bright white room.
    static Scene garden = (i, j, r) -> j < 12 ? u(r, 2.0f, 4.0f) : (j < 30 ? u(r, 0.03f, 0.2f) : u(r, 0.08f, 0.3f));
    static Scene room = (i, j, r) -> (i > 36 || j < 8) ? u(r, 0.8f, 2.6f) : u(r, 0.06f, 0.4f);
    static Scene dim = (i, j, r) -> u(r, 0.002f, 0.07f) * (j < 10 ? 4f : 1f);      // meter left it two stops under, nothing bright
    static Scene glints = (i, j, r) -> r.nextFloat() < 0.012f ? 20f : u(r, 0.04f, 0.6f); // daylight with a few specular highlights
    static Scene window = (i, j, r) -> (i > 20 && i < 44 && j > 8 && j < 30) ? u(r, 6f, 14f) : u(r, 0.02f, 0.25f); // interior, bright window

    static class Rig {
        final Evo evo = new Evo();
        final Evo.Measure m = new Evo.Measure();
        final float[] x = new float[COLS * ROWS], shade = new float[COLS * ROWS];
        float actual = 0f;   // EVO's sensor shift as the sensor has it (the camera's AE takes ~0.25 s)
        Rig() { java.util.Arrays.fill(shade, 1f); }

        boolean log = false;  // recording log: the file keeps what the sensor holds

        void frame(float[] scene, float gain, Evo.Tone tone, float noise, boolean push, Evo.Mode mode, float dt) {
            actual += (evo.getSensorEv() * OVER - actual) * (1f - (float) Math.exp(-dt / 0.25f));
            float k = (float) Math.pow(2, actual) / gain;
            for (int n = 0; n < x.length; n++) x[n] = Math.min(1f, scene[n] * k);
            m.fill(x, shade, COLS, ROWS);
            evo.update(m, gain / (float) Math.pow(2, actual), tone, log ? Evo.LOG_WHITE : tone.getWhite(), noise, actual,
                Math.abs(evo.getSensorEv() * OVER - actual) < 0.02f, STEP, -4f, 4f, push, mode, dt);
        }
    }

    static void settle(String name, Scene s, float gain, Evo.Tone white, float noise, boolean push) { settle(name, s, gain, white, noise, push, false); }

    static void settle(String name, Scene s, float gain, Evo.Tone white, float noise, boolean push, boolean log) {
        Rig rig = new Rig();
        rig.log = log;
        rig.evo.setDimWeight(GUARD);
        rig.evo.setStrength(STRENGTH);
        float maxSpeed = 0f, last = 0f;
        for (int f = 0; f < 30 * 20; f++) {
            rig.frame(render(s, f % 7), gain, white, noise, push, Evo.Mode.RUN, 1f / 30);
            if (f > 0) maxSpeed = Math.max(maxSpeed, Math.abs(rig.evo.getViewEv() - last) * 30);
            last = rig.evo.getViewEv();
        }
        System.out.printf("%-34s view %+.2f EV  sensor %+.2f EV  blown %4.1f%% -> %4.1f%%  sources %4.1f%%  fastest %.2f EV/s%n",
            name, rig.evo.getViewEv(), rig.evo.getSensorEv(), rig.evo.getBlownWithout() * 100, rig.evo.getBlownNow() * 100,
            rig.m.getSources() * 100, maxSpeed);
    }

    /**
     * A scene as the phone measured it (adb evodump → the EVOHIST log line saved to a file):
     *   dump <hist.txt> [file.cube [expects [dehaze]]]   what EVO would settle at, by strength and guard, and the cost of each shift.
     */
    static void dump(String[] a) throws Exception {
        String line = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[1]))).trim();
        float gain = Float.parseFloat(line.replaceAll(".*gain=([0-9.]+).*", "$1"));
        String[] parts = line.substring(line.indexOf("white=")).split(" ", 2)[1].split("\\|");
        Evo.Measure m = new Evo.Measure();
        float surf = 0;
        for (int k = 0; k < 3; k++) {
            String[] v = parts[k].split(",");
            float[] into = k == 0 ? m.getView() : k == 1 ? m.getSensor() : m.getClipped();
            for (int i = 0; i < into.length; i++) { into[i] = Float.parseFloat(v[i]); if (k == 2) surf += into[i]; }
        }
        CubeLut lut = a.length > 2 ? CubeLut.Companion.parse(new java.io.File(a[2])) : Looks.INSTANCE.getREC709().toLut(LogProfile.SLOG3, 33);
        LogProfile expects = a.length > 3 && !a[3].equals("-") ? LogProfile.valueOf(a[3]) : null;
        float dehaze = a.length > 4 ? Float.parseFloat(a[4]) : 0f;
        Evo.Tone tone = Evo.Companion.toneOfLook(lut, LogProfile.SLOG3, expects, 1f, 0f, 0f, dehaze);
        System.out.printf("gain %.2f, look goes white %.1f stops over grey and shows grey at %.0f%%, dehaze %+.2f%n", gain,
            Math.log(tone.getWhite() / 0.18) / Math.log(2), tone.at(0f) * 100, dehaze);
        for (float guard : new float[]{0f, Evo.DIM_WEIGHT}) for (int st = 0; st < 3; st++) {
            Evo evo = new Evo();
            evo.setDimWeight(guard); evo.setStrength(st);
            for (int f = 0; f < 600; f++) evo.update(m, gain, tone, tone.getWhite(), 6.6e-4f, 0f, true, STEP, -4f, 4f, false, Evo.Mode.RUN, 1f / 30);
            System.out.printf("guard %.1f strength %d: settles at %+.2f EV, blown %4.1f%% -> %4.1f%%%n", guard, st, evo.getViewEv(), evo.getBlownWithout() * 100, evo.getBlownNow() * 100);
            if (st == 1) { StringBuilder sb = new StringBuilder("   cost by shift:"); float[] c = evo.viewCosts();
                for (int k = 0; k <= 16; k += 2) sb.append(String.format("  %+.2f:%.3f", -2f + k * 0.125f, c[k])); System.out.println(sb); }
        }
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("dump")) { dump(a); return; }
        CubeLut plain = Looks.INSTANCE.getREC709().toLut(LogProfile.SLOG3, 33);
        // A look that clips early and shows middle grey bright, like a punchy LUT: the plain one two stops up.
        CubeLut early = new Look("early", new LookParams(2f, 0.3f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 210f, 0f, 40f, 0f, false,
            LookParams.Companion.getNO_MIX(), LookParams.Companion.getNO_MIX(), LookParams.Companion.getNO_MIX()), null).toLut(LogProfile.SLOG3, 33);
        Evo.Tone w = Evo.Companion.toneOfLook(plain, LogProfile.SLOG3, null, 1f, 0f, 0f, 0f);
        Evo.Tone wEdr = Evo.Companion.toneOfLook(plain, LogProfile.SLOG3, null, 1f, -0.75f, 0.3f, 0f);
        Evo.Tone wEarly = Evo.Companion.toneOfLook(early, LogProfile.SLOG3, null, 1f, 0f, 0f, 0f);
        Evo.Tone wBloom = Evo.Companion.toneOfLook(early, LogProfile.SLOG3, null, 1f, 0f, 0f, -0.66f);
        for (Evo.Tone t : new Evo.Tone[]{w, wEdr, wEarly, wBloom}) System.out.printf("tone: white %.1f stops over grey, grey shows at %.0f%%%n",
            Math.log(t.getWhite() / 0.18) / Math.log(2), t.at(0f) * 100);
        float n = 6.6e-4f;
        System.out.printf("range at this noise: %.1f stops%n", Evo.Companion.rangeStops(n));
        System.out.println("-- X14 main camera (gain 4.6), no eDR");
        settle("daylight, sky under white", daylight, 4.6f, w, n, true);
        settle("daylight, a few glints", glints, 4.6f, w, n, true);
        settle("white plate on dark grey", plate, 4.6f, w, n, true);
        settle("sunset sky over foliage", sunset, 4.6f, w, n, true);
        settle("interior with a bright window", window, 4.6f, w, n, true);
        settle("night: neon, cars, lamps (gain 16)", night, 16f, w, 6e-3f, false);
        settle("dim scene, metered low (push ok)", dim, 4.6f, w, n, true);
        settle("  the window, recording log", window, 4.6f, w, n, true, true);
        settle("  night, recording log", night, 16f, w, 6e-3f, false, true);
        System.out.println("-- the same with eDR -75");
        settle("white plate on dark grey", plate, 4.6f, wEdr, n, true);
        settle("sunset sky over foliage", sunset, 4.6f, wEdr, n, true);
        settle("interior with a bright window", window, 4.6f, wEdr, n, true);
        settle("night: neon, cars, lamps (gain 16)", night, 16f, wEdr, 6e-3f, false);
        System.out.println("-- telephoto (gain 1.5): the sensor clips before the view");
        settle("white plate on dark grey", plate, 1.5f, w, n, true);
        settle("sunset sky over foliage", sunset, 1.5f, w, n, true);

        for (float dw : new float[]{0f, Evo.DIM_WEIGHT}) for (int st = 0; st < 3; st++) {
            System.out.println("-- a look that clips early, strength " + st + ", guard " + dw);
            GUARD = dw; STRENGTH = st;
            settle("overcast sky over a garden", garden, 4.6f, wEarly, n, true);
            settle("  the same with a -66 bloom", garden, 4.6f, wBloom, n, true);
            settle("bright white room", room, 4.6f, wEarly, n, true);
            settle("white plate on dark grey", plate, 4.6f, wEarly, n, true);
        }
        GUARD = Evo.DIM_WEIGHT; STRENGTH = 1;
        System.out.println("-- a camera that moves 1.7x as far as it's asked (telephoto plate)");
        OVER = 1.7f;
        settle("white plate on dark grey", plate, 1.5f, w, n, true);
        OVER = 1f;

        if (a.length > 0) {
            System.out.println("-- pan: daylight 4 s, plate 8 s, daylight 8 s, then EVO off (every 0.5 s)");
            Rig rig = new Rig();
            for (int f = 0; f < 30 * 26; f++) {
                float t = f / 30f;
                Scene s = t < 4 || (t >= 12 && t < 20) ? daylight : plate;
                rig.frame(render(s, f % 7), 4.6f, w, n, true, t < 20 ? Evo.Mode.RUN : Evo.Mode.OFF, 1f / 30);
                if (f % 15 == 0) System.out.printf("%5.1f s  view %+.3f  sensor %+.2f (wanted %+.2f / %+.2f)%n", t,
                    rig.evo.getViewEv(), rig.evo.getSensorEv(), rig.evo.getViewWanted(), rig.evo.getSensorWanted());
            }
        }
    }
}
