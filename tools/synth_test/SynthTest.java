import com.rosasoft.wintalker.engine.*;
import java.nio.file.*;
import java.io.*;
public class SynthTest {
    static void writeWav(String path, short[] pcm, int rate) throws IOException {
        try(DataOutputStream o=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)))){
            int dataLen=pcm.length*2; int fileLen=36+dataLen;
            o.writeBytes("RIFF"); writeLE(o,fileLen); o.writeBytes("WAVE");
            o.writeBytes("fmt "); writeLE(o,16); writeLEShort(o,1); writeLEShort(o,1);
            writeLE(o,rate); writeLE(o,rate*2); writeLEShort(o,2); writeLEShort(o,16);
            o.writeBytes("data"); writeLE(o,dataLen);
            for(short s:pcm){ o.write(s&0xff); o.write((s>>8)&0xff); }
        }
    }
    static void writeLE(DataOutputStream o,int v)throws IOException{o.write(v&0xff);o.write((v>>8)&0xff);o.write((v>>16)&0xff);o.write((v>>24)&0xff);}
    static void writeLEShort(DataOutputStream o,int v)throws IOException{o.write(v&0xff);o.write((v>>8)&0xff);}
    public static void main(String[] a) throws Exception {
        byte[] data=Files.readAllBytes(Paths.get(a[0]));
        VoiceDatabase db=VoiceDatabase.parse(data);
        System.out.println("diphone index size: "+db.diphoneIndex().size());
        DiphoneSynth synth=new DiphoneSynth(db);
        // labas phonemes
        String[] phon={"_","l","aA","b","aA","s","_"};
        short[] pcm=synth.synthesize(phon);
        int peak=0; long nz=0;
        for(short s:pcm){int v=Math.abs(s); if(v>peak)peak=v; if(s!=0)nz++;}
        System.out.println("labas: "+pcm.length+" samples, peak="+peak+" nonzero="+nz);
        if(a.length>1) writeWav(a[1], pcm, 22050);
    }
}
