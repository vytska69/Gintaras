import com.rosasoft.wintalker.engine.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Generate Java-pipeline PCM for a word (Transcriber -> Conversion ->
 *  CandidateSequencer -> DiphoneSynth) and, if a reference WAV is given,
 *  report the best lag-aligned cross-correlation against it. */
public class WavGen {
    static void writeWav(String path, short[] pcm, int rate) throws IOException {
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
        int nb = pcm.length * 2;
        o.writeBytes("RIFF"); le32(o, 36 + nb); o.writeBytes("WAVE"); o.writeBytes("fmt ");
        le32(o, 16); le16(o, 1); le16(o, 1); le32(o, rate); le32(o, rate * 2); le16(o, 2); le16(o, 16);
        o.writeBytes("data"); le32(o, nb);
        for (short s : pcm) le16(o, s);
        o.close();
    }
    static void le32(DataOutputStream o, int v) throws IOException { o.write(v); o.write(v>>8); o.write(v>>16); o.write(v>>24); }
    static void le16(DataOutputStream o, int v) throws IOException { o.write(v); o.write(v>>8); }
    static short[] readWav(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        int p = 12;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4);
            int sz = (b[p+4]&0xff)|((b[p+5]&0xff)<<8)|((b[p+6]&0xff)<<16)|((b[p+7]&0xff)<<24);
            if (id.equals("data")) {
                int n = sz / 2; short[] s = new short[n];
                for (int i = 0; i < n; i++) s[i] = (short)((b[p+8+2*i]&0xff)|(b[p+9+2*i]<<8));
                return s;
            }
            p += 8 + sz + (sz & 1);
        }
        return new short[0];
    }
    /** Normalised cross-correlation with best lag search over +/- maxLag samples. */
    static double xcorr(short[] a, short[] c) {
        int maxLag = 2000;
        double best = -2;
        for (int lag = -maxLag; lag <= maxLag; lag += 4) {
            double num=0, da=0, dc=0;
            int n = Math.min(a.length, c.length);
            for (int i = Math.max(0,-lag); i < n && i+lag < c.length; i++) {
                double av = a[i], cv = c[i+lag];
                num += av*cv; da += av*av; dc += cv*cv;
            }
            if (da>0 && dc>0) { double r = num/Math.sqrt(da*dc); if (r>best) best=r; }
        }
        return best;
    }
    public static void main(String[] args) throws Exception {
        String dta = "app/src/main/assets/Gintaras.dta";
        VoiceDatabase db = VoiceDatabase.parse(Files.readAllBytes(Paths.get(dta)));
        DiphoneSynth synth = new DiphoneSynth(db);
        // args: word [refWav] [outWav]
        String word = args[0];
        int[] norm = Transcriber.normalise(word);
        List<String> ph = Transcriber.transcribe(norm, norm.length);
        short[] pcm = synth.synthesize(ph.toArray(new String[0]));
        if (args.length >= 3) writeWav(args[2], pcm, 22050);
        String info = String.format("%-10s java=%d", word, pcm.length);
        if (args.length >= 2 && Files.exists(Paths.get(args[1]))) {
            short[] ref = readWav(args[1]);
            info += String.format(" ref=%d corr=%.4f", ref.length, xcorr(pcm, ref));
        }
        System.out.println(info);
    }
}
