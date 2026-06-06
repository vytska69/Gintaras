import com.rosasoft.wintalker.engine.*;
import java.nio.file.*;
import java.util.*;

/**
 * Validates {@link CandidateSequencer} against the ground-truth unit sequences
 * emitted by the ORIGINAL engine's {@code translate} module (captured by driving
 * the real LuaJIT bytecode over Gintaras.dta — see
 * engine/synth_research/gold_translate_seq.tsv).
 *
 * Gold format (tab-separated):
 *   col0 = conversion code units, space-separated 4-hex each (with 0x7c '|' marks)
 *   col1 = expected unit names, space-separated; each unit = comma-separated 4-hex
 *   col2 = source word (informational)
 *
 * Usage: java CandVal gold_translate_seq.tsv Gintaras.dta
 */
public class CandVal {
    static String fromCodeUnits(String spaceHex4) {
        StringBuilder s = new StringBuilder();
        for (String h : spaceHex4.trim().split("\\s+"))
            if (!h.isEmpty()) s.append((char) Integer.parseInt(h, 16));
        return s.toString();
    }
    static String fromUnit(String commaHex4) {
        StringBuilder s = new StringBuilder();
        for (String h : commaHex4.split(","))
            if (!h.isEmpty()) s.append((char) Integer.parseInt(h, 16));
        return s.toString();
    }
    public static void main(String[] a) throws Exception {
        VoiceDatabase db = VoiceDatabase.parse(Files.readAllBytes(Paths.get(a[1])));
        CandidateSequencer cs = new CandidateSequencer(db);
        int total = 0, match = 0, palTotal = 0, palMatch = 0;
        List<String> fail = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(a[0]))) {
            String[] p = line.split("\t");
            if (p.length < 2) continue;
            String inp = fromCodeUnits(p[0]);
            List<String> exp = new ArrayList<>();
            for (String g : p[1].trim().split("\\s+")) if (!g.isEmpty()) exp.add(fromUnit(g));
            List<String> got = cs.sequence(inp);
            total++;
            boolean isPal = inp.indexOf((char) 0x7c) >= 0;
            if (isPal) palTotal++;
            boolean ok = got.equals(exp);
            if (ok) { match++; if (isPal) palMatch++; }
            else if (fail.size() < 14)
                fail.add((p.length > 2 ? p[2] : vis(inp)) + " exp=" + visL(exp) + " got=" + visL(got));
        }
        System.out.printf("CandidateSequencer: %d/%d = %.1f%% (palatalised: %d/%d = %.1f%%)%n",
                match, total, 100.0 * match / total,
                palMatch, palTotal, palTotal == 0 ? 0 : 100.0 * palMatch / palTotal);
        for (String f : fail) System.out.println("  " + f);
    }
    static String vis(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c == 0x7c ? "|" : c < 128 ? ("" + c) : String.format("<%04x>", (int) c));
        return b.toString();
    }
    static String visL(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(vis(s)).append(",");
        return b.toString();
    }
}
