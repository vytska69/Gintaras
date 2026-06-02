import com.rosasoft.wintalker.engine.*;
import java.nio.file.*;
import java.util.*;
public class SeqVal {
    static String low(String hex){ // utf16le hex -> cp1257 char string
        byte[] b=new byte[hex.length()/2];
        for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        StringBuilder s=new StringBuilder();
        for(int i=0;i+1<b.length;i+=2)s.append((char)(b[i]&0xff));
        return s.toString();
    }
    static String cp(String hex){ // 1-byte/char cp1257 hex -> char string
        StringBuilder s=new StringBuilder();
        for(int i=0;i<hex.length();i+=2)s.append((char)Integer.parseInt(hex.substring(i,i+2),16));
        return s.toString();
    }
    public static void main(String[] a) throws Exception {
        int total=0,match=0;
        for(String line:Files.readAllLines(Paths.get(a[0]))){
            if(!line.contains("\t"))continue;
            String[] p=line.split("\t");
            String inp=cp(p[0]);  // input letters (cp1257)
            String[] goldUnits=p[1].split(" ");
            List<String> exp=new ArrayList<>();
            for(String g:goldUnits)exp.add(low(g));
            List<String> got=UnitSequencer.sequence(inp);
            total++;
            boolean ok=got.equals(exp);
            if(ok)match++;
            else if(total<=12){
                System.out.println(vis(inp)+":");
                System.out.println("  exp="+visList(exp));
                System.out.println("  got="+visList(got));
            }
        }
        System.out.printf("UnitSequencer: %d/%d = %.1f%%%n",match,total,100.0*match/total);
    }
    static String vis(String s){StringBuilder b=new StringBuilder();for(char c:s.toCharArray())b.append(c<128?(""+c):String.format("<%02x>",(int)c));return b.toString();}
    static String visList(List<String> l){StringBuilder b=new StringBuilder();for(String s:l)b.append(vis(s)).append(" ");return b.toString();}
}
