import com.rosasoft.wintalker.engine.VoiceDatabase;
import com.rosasoft.wintalker.engine.NumberExpander;
import java.io.FileInputStream;
import java.util.*;

/**
 * Faithful re-implementation of translate root.24.1 (the original Lua number
 * reader's key-construction/window-search), used as an oracle to validate the
 * production NumberExpander.readCardinal over an exhaustive numeric range.
 *
 * Algorithm (from translate.decomp.txt root.24.1, with UV0 == ""):
 *   digits = decimal string; N = digits.length()
 *   result[1..N] (1-based); each cell: List<String> fragment, or SENTINEL(true), or null
 *   R2=1 (start), R3=N (end)
 *   loop while R2 <= R3:
 *     scale = N - R3
 *     window = digits[R2-1 .. R3-1]   (1-based inclusive -> 0-based)
 *     key tries (in order): "N"+window+"+"+scale+"R", "N"+window+"+"+scale
 *     match = buckets[key] (first that exists)
 *     if match:
 *        result[R2] = match
 *        mark positions R2+1 .. N as consumed (SENTINEL) if not already set
 *        R3 = R2-1; if R3==0 done; else R2=1
 *     else:
 *        R2 = R2+1; if R3 < R2 then { R3 = R3-1; if R3==0 done; else R2=1 }
 *   then for any still-null result[k]: try "N"+digit[k] (bare single digit)
 *   flatten result (tables only) in order.
 */
public class RefCardinal {
    static final Object SENTINEL = new Object();
    final Map<String, List<String>> buckets;
    RefCardinal(VoiceDatabase db) { buckets = db.numberBuckets(); }

    List<String> read(String raw) {
        int s = 0;
        while (s < raw.length() - 1 && raw.charAt(s) == '0') s++;
        String digits = raw.substring(s);
        int N = digits.length();
        if (N == 1) {
            List<String> w = buckets.get("N" + digits);
            return w != null ? new ArrayList<>(w) : new ArrayList<>();
        }
        Object[] result = new Object[N + 1]; // 1-based
        int R2 = 1, R3 = N;
        while (R2 <= R3) {
            int scale = N - R3;
            String window = digits.substring(R2 - 1, R3); // [R2..R3] 1-based inclusive
            List<String> match = lookup(window, scale);
            if (match != null) {
                result[R2] = match;
                for (int k = R2 + 1; k <= N; k++) if (result[k] == null) result[k] = SENTINEL;
                R3 = R2 - 1;
                if (R3 == 0) break;
                R2 = 1;
            } else {
                R2 = R2 + 1;
                if (R3 < R2) {
                    R3 = R3 - 1;
                    if (R3 == 0) break;
                    R2 = 1;
                }
            }
        }
        // bare single-digit fallback for unfilled positions
        for (int k = 1; k <= N; k++) {
            if (result[k] == null) {
                List<String> w = buckets.get("N" + digits.charAt(k - 1));
                result[k] = (w != null) ? w : SENTINEL;
            }
        }
        List<String> out = new ArrayList<>();
        for (int k = 1; k <= N; k++)
            if (result[k] instanceof List) out.addAll((List<String>) result[k]);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> lookup(String window, int scale) {
        List<String> w = buckets.get("N" + window + "+" + scale + "R");
        if (w == null) w = buckets.get("N" + window + "+" + scale);
        return w;
    }

    public static void main(String[] args) throws Exception {
        String dta = "/home/user/Gintaras/app/src/main/assets/Gintaras.dta";
        VoiceDatabase db = VoiceDatabase.parse(new FileInputStream(dta));
        RefCardinal ref = new RefCardinal(db);
        NumberExpander ne = new NumberExpander(db);
        long mismatches = 0;
        List<String> samples = new ArrayList<>();
        for (long v = 0; v <= 300000; v++) samples.add(String.valueOf(v));
        long[] big = {1000000L,1000001L,2000000L,10000000L,100000000L,123456789L,
                999999999L,1000000000L,1000000001L,2024000000L,
                1900000L,500000000L,7000000000L,12000000000L};
        for (long b : big) samples.add(String.valueOf(b));
        int shown = 0;
        for (String sv : samples) {
            String a = String.join(" ", ne.expand(sv, 16).split("\\s+"));
            String e = String.join(" ", ref.read(sv));
            // ne.expand strips so compare token lists from readCardinal directly
            List<String> nList = invokeReadCardinal(ne, sv);
            String nJoin = String.join(" ", nList);
            String rJoin = String.join(" ", ref.read(sv));
            if (!nJoin.equals(rJoin)) {
                mismatches++;
                if (shown++ < 40)
                    System.out.println("MISMATCH " + sv + "\n  java=" + nJoin + "\n  ref =" + rJoin);
            }
        }
        System.out.println("total samples=" + samples.size() + " mismatches=" + mismatches);
    }

    // reflectively call package-private readCardinal
    static List<String> invokeReadCardinal(NumberExpander ne, String s) throws Exception {
        java.lang.reflect.Method m = NumberExpander.class.getDeclaredMethod("readCardinal", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(ne, s);
    }
}
