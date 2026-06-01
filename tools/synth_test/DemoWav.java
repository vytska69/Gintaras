import com.rosasoft.wintalker.engine.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public class DemoWav {
    public static void main(String[] a) throws Exception {
        byte[] data=Files.readAllBytes(Paths.get(a[0]));
        VoiceDatabase db=VoiceDatabase.parse(data);
        DiphoneSynth synth=new DiphoneSynth(db);
        String[][] words={
            {"_","l","aA","b","aA","s","_"},
            {"_","l'","i","eA","t","u","v","aA","_"},
            {"_","g'","i","N","t","aA","r","aA","s","_"},
            {"_","s","aA","W","l'","eE","_"},
        };
        List<short[]> parts=new ArrayList<>();
        short[] pause=new short[(int)(0.25*22050)];
        for(String[] w:words){ parts.add(synth.synthesize(w)); parts.add(pause); }
        int tot=0; for(short[] p:parts)tot+=p.length;
        short[] all=new short[tot]; int o=0;
        for(short[] p:parts){System.arraycopy(p,0,all,o,p.length);o+=p.length;}
        SynthTest.writeWav(a[1],all,22050);
        int peak=0;for(short s:all){int v=Math.abs(s);if(v>peak)peak=v;}
        System.out.printf("demo: %d samples (%.2fs) peak=%d%n",all.length,all.length/22050.0,peak);
    }
}
