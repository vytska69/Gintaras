import com.rosasoft.wintalker.engine.VoiceDatabase;
import java.nio.file.*;
public class Validate {
    public static void main(String[] a) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(a[0]));
        VoiceDatabase db = VoiceDatabase.parse(data);
        int totalRecs = 0, numeric = 0, str = 0;
        long totalSamples = 0; int peak = 0;
        for (VoiceDatabase.SampleBlock b : db.blocks.values()) {
            totalSamples += b.samples.length;
            for (short s : b.samples) { int v = Math.abs(s); if (v > peak) peak = v; }
        }
        for (VoiceDatabase.Entry e : db.entries) {
            totalRecs += e.records.size();
            for (VoiceDatabase.Record r : e.records) { if (r.isNumeric()) numeric++; else str++; }
        }
        System.out.println("file size       = " + data.length);
        System.out.println("bytesConsumed   = " + db.bytesConsumed + "  clean=" + (db.bytesConsumed == data.length));
        System.out.println("sample blocks   = " + db.blocks.size());
        System.out.println("total samples   = " + totalSamples + " (" + (totalSamples*2) + " bytes)");
        System.out.println("waveform peak   = " + peak);
        System.out.println("dict entries    = " + db.entries.size());
        System.out.println("dict records    = " + totalRecs + " (numeric=" + numeric + " string=" + str + ")");
    }
}
