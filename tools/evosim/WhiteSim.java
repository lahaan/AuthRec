import com.authrec.color.*;
import com.authrec.gl.Evo;
import java.io.File;

/** Prints how a look renders a grey ramp: java -cp ... WhiteSim.java <file.cube> */
public class WhiteSim {
    static void curve(String name, CubeLut lut, LogProfile profile, LogProfile input) {
        int n = lut.getSize();
        StringBuilder sb = new StringBuilder(name + " (white " + String.format("%.1f", Math.log(Evo.Companion.toneOfLook(lut, profile, input, 1f, 0f, 0f, 0f).getWhite() / 0.18) / Math.log(2)) + " stops):\n");
        for (int k = -8; k <= 28; k++) {
            double e = k / 4.0, y = 0.18 * Math.pow(2, e);
            float pos = (float) (Math.min(1, Math.max(0, (input == null ? profile : input).encode(y))) * (n - 1));
            int i = Math.min((int) pos, n - 2);
            float f = pos - i;
            double l = luma(lut, i) * (1 - f) + luma(lut, i + 1) * f;
            if (k % 2 == 0) sb.append(String.format("  %+.1f st: %.3f%n", e, l));
        }
        System.out.print(sb);
    }
    static double luma(CubeLut lut, int node) {
        int n = lut.getSize(), at = (node + node * n + node * n * n) * 3;
        float[] rgb = lut.getRgb();
        return 0.2126 * rgb[at] + 0.7152 * rgb[at + 1] + 0.0722 * rgb[at + 2];
    }
    public static void main(String[] a) throws Exception {
        curve("Chiseled Cyan, fed LogC3", CubeLut.Companion.parse(new File(a[0])), LogProfile.SLOG3, LogProfile.LOGC3);
        try { curve("Rec.709 look", Looks.INSTANCE.getREC709().toLut(LogProfile.SLOG3, 33), LogProfile.SLOG3, null); }
        catch (Throwable t) { System.out.println("Rec.709: " + t); }
    }
}
