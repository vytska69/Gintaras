package com.rosasoft.wintalker.engine;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

public class PuncTest {
    static String A = "/home/user/Gintaras/.claude/worktrees/agent-a8fa07f3a324d921a/app/src/main/assets/";
    public static void main(String[] args) throws Exception {
        VoiceDatabase db = VoiceDatabase.parse(new FileInputStream(A + "Gintaras.dta"));
        InputStream[] punc = new InputStream[4];
        for (int i = 0; i < 4; i++) punc[i] = new FileInputStream(A + "punc" + i + "lit.dct");
        TextNormalizer tn = TextNormalizer.create(db,
                new FileInputStream(A + "ruleslit.rul"),
                new FileInputStream(A + "stdlit.dct"),
                new FileInputStream(A + "spelllit.dct"), punc);
        String s = "a; b: (c) [d] {e}! f? g.";
        for (int lvl = 0; lvl <= 3; lvl++) {
            TextNormalizer.Settings st = new TextNormalizer.Settings();
            st.punctuationLevel = lvl;
            StringBuilder sb = new StringBuilder();
            for (TextNormalizer.Token t : tn.normalize(s, st)) {
                if (t.punctuation != 0)
                    sb.append("{").append(t.punctuation).append("->'").append(t.text).append("'} ");
                else sb.append("'").append(t.text).append("' ");
            }
            System.out.println("lvl"+lvl+": "+sb.toString().trim());
        }
    }
}
