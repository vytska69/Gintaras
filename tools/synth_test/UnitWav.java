import com.rosasoft.wintalker.engine.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Render PCM from an explicit engine unit-name sequence (hex UTF-16LE per line,
 *  as dumped by /tmp/units_ref_<word>.tsv col 2) so the DSP can be A/B'd against
 *  the original engine's reference PCM independent of unit selection.
 *  Usage: java UnitWav <Gintaras.dta> <units.tsv> <out.wav> */
public class UnitWav {
    public static void main(String[] a) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(a[0]));
        VoiceDatabase db = VoiceDatabase.parse(data);
        DiphoneSynth synth = new DiphoneSynth(db);
        List<String> units = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(a[1]))) {
            String[] cols = line.split("\t");
            if (cols.length < 2) continue;
            String hex = cols[1];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 3 < hex.length(); i += 4) {
                int lo = Integer.parseInt(hex.substring(i, i + 2), 16);
                int hi = Integer.parseInt(hex.substring(i + 2, i + 4), 16);
                sb.append((char) (lo | (hi << 8)));
            }
            units.add(sb.toString());
        }
        short[] pcm = synth.synthesizeUnits(units);
        SynthTest.writeWav(a[2], pcm, 22050);
        int peak = 0; for (short s : pcm) { int v = Math.abs(s); if (v > peak) peak = v; }
        System.out.printf("%s: %d units -> %d samples peak=%d%n", a[1], units.size(), pcm.length, peak);
    }
}
