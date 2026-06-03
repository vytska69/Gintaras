import com.rosasoft.wintalker.engine.VoiceDatabase;
import com.rosasoft.wintalker.engine.NumberExpander;
import java.io.FileInputStream;

public class NumTest2 {
    public static void main(String[] args) throws Exception {
        String dta = "/home/user/Gintaras/app/src/main/assets/Gintaras.dta";
        VoiceDatabase db = VoiceDatabase.parse(new FileInputStream(dta));
        NumberExpander ne = new NumberExpander(db);
        int[] vals = {110,111,115,1100,1010,1001,11000,210,1234567,1111111,
                      19000,1900,1019,100100,500000,999999,1000001,12000,
                      19,190,1900000,11,12,13,14,16,17,18,
                      100000000,10000000,1000000000,2000000,
                      40,50,60,70,80,90,400,500,
                      1000000000+1};
        for (int v : vals) {
            System.out.println(v + " => " + ne.expand(String.valueOf(v), 16));
        }
    }
}
