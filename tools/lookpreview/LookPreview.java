import com.authrec.color.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Renders a look (the app's own Looks.render) on the desktop, for judging parameters before they
 * go on a phone. See preview.sh.
 *   LookPreview <params.txt> chart <out.raw>                 a colour chart, grey ramp and memory colours; prints "W H"
 *   LookPreview <params.txt> log <in.raw> <W> <H> <wbWarm> <out.raw>   an S-Log3 frame (rgb48le); wbWarm: the clip's WB shift to undo
 * Output: rgb24.
 */
public class LookPreview {
    static LookParams load(String file) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(file))) { int i = line.indexOf('='); if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim()); }
        return new LookParams(f(m, "exposure", 0), f(m, "contrast", 0), f(m, "highlights", 0), f(m, "shadows", 0), f(m, "fade", 0),
            f(m, "temperature", 0), f(m, "tint", 0), f(m, "saturation", 1), f(m, "vibrance", 0), f(m, "shadowHue", 210), f(m, "shadowTint", 0),
            f(m, "highlightHue", 40), f(m, "highlightTint", 0), "true".equals(m.get("mono")), list(m, "mixHue"), list(m, "mixSat"), list(m, "mixLum"));
    }
    static float f(Map<String, String> m, String k, float d) { return m.containsKey(k) ? Float.parseFloat(m.get(k)) : d; }
    static List<Float> list(Map<String, String> m, String k) {
        List<Float> out = new ArrayList<>();
        String v = m.getOrDefault(k, "");
        for (String s : v.split(",")) if (!s.isBlank()) out.add(Float.parseFloat(s.trim()));
        while (out.size() < 8) out.add(0f);
        return out;
    }
    static double srgbDecode(double v) { return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }

    // Macbeth-style patches (sRGB 8-bit), then sky / foliage / skin / neon extras.
    static final int[][] PATCHES = {
        {115,82,68},{194,150,130},{98,122,157},{87,108,67},{133,128,177},{103,189,170},
        {214,126,44},{80,91,166},{193,90,99},{94,60,108},{157,188,64},{224,163,46},
        {56,61,150},{70,148,73},{175,54,60},{231,199,31},{187,86,149},{8,133,161},
        {243,243,242},{200,200,200},{160,160,160},{122,122,121},{85,85,85},{52,52,52},
        {110,160,225},{60,110,200},{45,90,40},{120,150,70},{225,180,150},{150,100,75},{255,60,160},{255,140,40}};

    public static void main(String[] a) throws Exception {
        LookParams p = load(a[0]);
        Looks looks = Looks.INSTANCE;
        if (a[1].equals("chart")) {
            int cell = 80, cols = 8, rows = PATCHES.length / cols, rampH = 60, w = cols * cell, h = rows * cell + 2 * rampH;
            byte[] out = new byte[w * h * 3];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double[] lin;
                if (y < rows * cell) {
                    int[] s = PATCHES[(y / cell) * cols + x / cell];
                    lin = new double[]{srgbDecode(s[0] / 255.0), srgbDecode(s[1] / 255.0), srgbDecode(s[2] / 255.0)};
                } else if (y < rows * cell + rampH) {
                    double v = 0.18 * Math.pow(2, -7 + 12.0 * x / (w - 1));          // grey, 7 stops under middle grey to 5 over
                    lin = new double[]{v, v, v};
                } else {
                    double v = 0.18 * Math.pow(2, -5 + 9.0 * x / (w - 1));           // skin tone through the same range
                    lin = new double[]{v * 1.55, v * 0.95, v * 0.68};
                }
                double[] c = looks.render(lin, p);
                for (int k = 0; k < 3; k++) out[(y * w + x) * 3 + k] = (byte) Math.round(Math.max(0, Math.min(1, c[k])) * 255);
            }
            Files.write(Paths.get(a[2]), out);
            System.out.println(w + " " + h);
            return;
        }
        int w = Integer.parseInt(a[3]), h = Integer.parseInt(a[4]);
        double warm = Math.pow(2, Double.parseDouble(a[5]) / 100.0 * 0.25);
        LogProfile profile = LogProfile.SLOG3;
        double[] to709 = ColorMath.INSTANCE.convert(profile.getPrimaries(), Primaries.Companion.getREC709());
        byte[] in = Files.readAllBytes(Paths.get(a[2]));
        byte[] out = new byte[w * h * 3];
        double[] lin = new double[3];
        for (int i = 0; i < w * h; i++) {
            for (int k = 0; k < 3; k++) {
                int v = (in[i * 6 + k * 2] & 0xFF) | ((in[i * 6 + k * 2 + 1] & 0xFF) << 8);
                lin[k] = profile.decode(v / 65535.0);
            }
            lin[0] /= warm; lin[2] *= warm;   // the clip's own white balance shift, taken back out
            double[] c = looks.render(ColorMath.INSTANCE.apply(to709, lin), p);
            for (int k = 0; k < 3; k++) out[i * 3 + k] = (byte) Math.round(Math.max(0, Math.min(1, c[k])) * 255);
        }
        Files.write(Paths.get(a[6]), out);
    }
}
