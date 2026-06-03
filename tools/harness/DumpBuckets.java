import com.rosasoft.wintalker.engine.VoiceDatabase;
import java.io.FileInputStream;
import java.util.*;

public class DumpBuckets {
    public static void main(String[] args) throws Exception {
        String dta = args.length > 0 ? args[0] : "/home/user/Gintaras/app/src/main/assets/Gintaras.dta";
        VoiceDatabase db = VoiceDatabase.parse(new FileInputStream(dta));
        Map<String, List<String>> b = db.numberBuckets();
        List<String> keys = new ArrayList<>(b.keySet());
        Collections.sort(keys);
        System.out.println("total N keys: " + keys.size());
        for (String k : keys) {
            System.out.println(k + " => " + b.get(k));
        }
    }
}
