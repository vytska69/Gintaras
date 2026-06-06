import com.rosasoft.wintalker.engine.Transcriber;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public class PhonDump {
  public static void main(String[] a) throws Exception {
    List<String> words = Files.readAllLines(Paths.get("/tmp/corpus_words.txt"), StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder();
    for (String w : words) {
      if (w.isEmpty()) continue;
      int[] norm = Transcriber.normalise(w);
      List<String> ph = Transcriber.transcribe(norm, norm.length);
      // join with literal \n marker for the probe (escaped)
      sb.append(w).append('\t');
      for (int i=0;i<ph.size();i++){ if(i>0) sb.append("\\n"); sb.append(ph.get(i)); }
      sb.append('\n');
    }
    Files.write(Paths.get("/tmp/phon_dump.tsv"), sb.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println("wrote "+words.size()+" words");
  }
}
