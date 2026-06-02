import com.rosasoft.wintalker.engine.*;
import java.nio.file.*;
import java.util.*;
public class CandVal {
    static String low(String hex){byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);StringBuilder s=new StringBuilder();for(int i=0;i+1<b.length;i+=2)s.append((char)(b[i]&0xff));return s.toString();}
    static String cp(String hex){StringBuilder s=new StringBuilder();for(int i=0;i<hex.length();i+=2)s.append((char)Integer.parseInt(hex.substring(i,i+2),16));return s.toString();}
    public static void main(String[] a) throws Exception {
        VoiceDatabase db=VoiceDatabase.parse(Files.readAllBytes(Paths.get(a[1])));
        CandidateSequencer cs=new CandidateSequencer(db);
        int total=0,match=0;List<String> fail=new ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(a[0]))){
            if(!line.contains("\t"))continue;String[] p=line.split("\t");
            String inp=cp(p[0]);List<String> exp=new ArrayList<>();for(String g:p[1].split(" "))exp.add(low(g));
            List<String> got=cs.sequence(inp);total++;
            if(got.equals(exp))match++;else if(fail.size()<14)fail.add(vis(inp)+" exp="+visL(exp)+" got="+visL(got));
        }
        System.out.printf("CandidateSequencer: %d/%d = %.1f%%%n",match,total,100.0*match/total);
        for(String f:fail)System.out.println("  "+f);
    }
    static String vis(String s){StringBuilder b=new StringBuilder();for(char c:s.toCharArray())b.append(c<128?(""+c):"?");return b.toString();}
    static String visL(List<String> l){StringBuilder b=new StringBuilder();for(String s:l)b.append(vis(s)).append(",");return b.toString();}
}
