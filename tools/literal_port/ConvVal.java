import com.rosasoft.wintalker.engine.Transcriber;
import com.rosasoft.wintalker.engine.Conversion;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Validate Conversion.java against the live trans root.8 output (conv_real.tsv). */
public class ConvVal {
    static String hex(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(String.format("%02x%02x", c & 0xff, (c >> 8) & 0xff));
        }
        return b.toString();
    }
    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("/tmp/conv_real.tsv"), StandardCharsets.UTF_8);
        int ok = 0, total = 0; List<String> fails = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t");
            String word = p[0], goldHex = p.length > 1 ? p[1] : "";
            int[] norm = Transcriber.normalise(word);
            List<String> ph = Transcriber.transcribe(norm, norm.length);
            String conv = Conversion.convert(ph);
            String got = hex(conv);
            total++;
            if (got.equals(goldHex)) ok++;
            else fails.add(word + "\n  gold=" + goldHex + "\n  got =" + got);
        }
        System.out.printf("Conversion exact-match: %d/%d (%.2f%%)%n", ok, total, 100.0 * ok / total);
        int show = Math.min(fails.size(), 40);
        for (int i = 0; i < show; i++) System.out.println("FAIL " + fails.get(i));
        if (fails.size() > show) System.out.println("... +" + (fails.size() - show) + " more");
    }
}
