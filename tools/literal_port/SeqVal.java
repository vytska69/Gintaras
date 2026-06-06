import com.rosasoft.wintalker.engine.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Validate CandidateSequencer.java against the live translate output (seq_real.tsv).
 *  seq_real.tsv columns: word \t convHex \t "key1 key2 ..." (keys as UTF-16LE hex). */
public class SeqVal {
    static String hex(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(String.format("%02x%02x", c & 0xff, (c >> 8) & 0xff));
        }
        return b.toString();
    }
    static String fromHex(String h) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 3 < h.length(); i += 4) {
            int lo = Integer.parseInt(h.substring(i, i + 2), 16);
            int hi = Integer.parseInt(h.substring(i + 2, i + 4), 16);
            b.append((char) (lo | (hi << 8)));
        }
        return b.toString();
    }
    public static void main(String[] a) throws Exception {
        byte[] dta = Files.readAllBytes(Paths.get(a.length > 0 ? a[0]
                : "app/src/main/assets/Gintaras.dta"));
        VoiceDatabase db = VoiceDatabase.parse(dta);
        CandidateSequencer cs = new CandidateSequencer(db);

        List<String> lines = Files.readAllLines(Paths.get("/tmp/seq_real.tsv"), StandardCharsets.UTF_8);
        int ok = 0, total = 0; List<String> fails = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            String word = p[0];
            String conv = fromHex(p[1]);
            String gold = p.length > 2 ? p[2] : "";
            List<String> seq = cs.sequence(conv);
            StringBuilder got = new StringBuilder();
            for (int k = 0; k < seq.size(); k++) { if (k > 0) got.append(' '); got.append(hex(seq.get(k))); }
            total++;
            if (got.toString().equals(gold)) ok++;
            else fails.add(word + "\n  gold=" + gold + "\n  got =" + got);
        }
        System.out.printf("Unit-sequence exact-match: %d/%d (%.2f%%)%n", ok, total, 100.0 * ok / total);
        int show = Math.min(fails.size(), 60);
        for (int i = 0; i < show; i++) System.out.println("FAIL " + fails.get(i));
        if (fails.size() > show) System.out.println("... +" + (fails.size() - show) + " more");
    }
}
