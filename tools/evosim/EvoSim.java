import com.authrec.gl.Evo;
import java.util.Random;

/** Runs the app's Evo class on made-up scenes (see run.sh): what it settles at, how fast it moves, a pan over time. */
public class EvoSim {
    static final int COLS = 63, ROWS = 47;
    static final float STEP = 1f / 6f;
    /** How much further than asked the camera moves (a clipped scene fools its metering): 1 = as asked. */
    static float OVER = 1f;

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

        void frame(float[] scene, float gain, float white, float noise, boolean push, Evo.Mode mode, float dt) {
            actual += (evo.getSensorEv() * OVER - actual) * (1f - (float) Math.exp(-dt / 0.25f));
            float k = (float) Math.pow(2, actual) / gain;
            for (int n = 0; n < x.length; n++) x[n] = Math.min(1f, scene[n] * k);
            m.fill(x, shade, COLS, ROWS);
            evo.update(m, gain / (float) Math.pow(2, actual), white, log ? Evo.LOG_WHITE : white, noise, actual,
                Math.abs(evo.getSensorEv() * OVER - actual) < 0.02f, STEP, -4f, 4f, push, mode, dt);
        }
    }

    static void settle(String name, Scene s, float gain, float white, float noise, boolean push) { settle(name, s, gain, white, noise, push, false); }

    static void settle(String name, Scene s, float gain, float white, float noise, boolean push, boolean log) {
        Rig rig = new Rig();
        rig.log = log;
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

    public static void main(String[] a) {
        float w = 1.99f, wEdr = 8.09f;  // a plain look, and the same under eDR -75
        float n = 6.6e-4f;
        System.out.printf("white: %.2f plain, %.2f with eDR -75; range at this noise: %.1f stops%n", w, wEdr, Evo.Companion.rangeStops(n));
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
