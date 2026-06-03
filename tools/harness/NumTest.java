import com.rosasoft.wintalker.engine.VoiceDatabase;
import com.rosasoft.wintalker.engine.NumberExpander;
import java.io.FileInputStream;

public class NumTest {
    public static void main(String[] args) throws Exception {
        String dta = "/home/user/Gintaras/app/src/main/assets/Gintaras.dta";
        VoiceDatabase db = VoiceDatabase.parse(new FileInputStream(dta));
        NumberExpander ne = new NumberExpander(db);
        int[] vals = {0,1,2,3,5,9,10,11,12,15,19,20,21,30,99,100,101,123,200,999,
                      1000,1001,2000,2024,10000,100000,1000000,1000000000};
        System.out.println("=== full cardinal (numgroup 16) ===");
        for (int v : vals) {
            System.out.println(v + " => " + ne.expand(String.valueOf(v), 16));
        }
        System.out.println("=== grouping ===");
        System.out.println("numgroup1 123 => " + ne.expand("123", 1));
        System.out.println("numgroup1 112 => " + ne.expand("112", 1));
        System.out.println("numgroup2 2024 => " + ne.expand("2024", 2));
        System.out.println("numgroup3 123456 => " + ne.expand("123456", 3));
        System.out.println("=== neg/dec ===");
        System.out.println("-5 => " + ne.expand("-5", 16));
        System.out.println("3,14 => " + ne.expand("3,14", 16));
        System.out.println("2.5 => " + ne.expand("2.5", 16));
        System.out.println("-3,5 => " + ne.expand("-3,5", 16));
        System.out.println("=== edge ===");
        System.out.println("007 => " + ne.expand("007", 16));
        System.out.println("1000000000 => " + ne.expand("1000000000", 16));
        System.out.println("12345678901234 => " + ne.expand("12345678901234", 16));
    }
}
