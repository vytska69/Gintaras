import com.rosasoft.wintalker.engine.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** End-to-end: word -> Transcriber -> Conversion -> CandidateSequencer, validated
 *  against seq_real.tsv (the live trans+translate unit sequence). */
public class PipeVal {
    static String hex(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(String.format("%02x%02x", c & 0xff, (c >> 8) & 0xff));
        }
        return b.toString();
    }
    public static void main(String[] a) throws Exception {
        byte[] dta = Files.readAllBytes(Paths.get("app/src/main/assets/Gintaras.dta"));
        VoiceDatabase db = VoiceDatabase.parse(dta);
        CandidateSequencer cs = new CandidateSequencer(db);
        List<String> lines = Files.readAllLines(Paths.get("/tmp/seq_real.tsv"), StandardCharsets.UTF_8);
        int ok = 0, total = 0; List<String> fails = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            String word = p[0], gold = p.length > 2 ? p[2] : "";
            int[] norm = Transcriber.normalise(word);
            List<String> ph = Transcriber.transcribe(norm, norm.length);
            String conv = Conversion.convert(ph);
            List<String> seq = cs.sequence(conv);
            StringBuilder got = new StringBuilder();
            for (int k = 0; k < seq.size(); k++) { if (k > 0) got.append(' '); got.append(hex(seq.get(k))); }
            total++;
            if (got.toString().equals(gold)) ok++; else fails.add(word);
        }
        System.out.printf("Full-pipeline exact-match: %d/%d (%.2f%%)%n", ok, total, 100.0 * ok / total);
        for (int i = 0; i < Math.min(fails.size(), 40); i++) System.out.println("FAIL " + fails.get(i));
    }
}
