package com.rosasoft.wintalker.engine;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

public class NormTest {
    static String A = "/home/user/Gintaras/.claude/worktrees/agent-a8fa07f3a324d921a/app/src/main/assets/";

    public static void main(String[] args) throws Exception {
        VoiceDatabase db = VoiceDatabase.parse(new FileInputStream(A + "Gintaras.dta"));
        InputStream[] punc = new InputStream[4];
        for (int i = 0; i < 4; i++) punc[i] = new FileInputStream(A + "punc" + i + "lit.dct");
        TextNormalizer tn = TextNormalizer.create(db,
                new FileInputStream(A + "ruleslit.rul"),
                new FileInputStream(A + "stdlit.dct"),
                new FileInputStream(A + "spelllit.dct"),
                punc);

        System.out.println("=== transliterate ===");
        System.out.println("Москва => " + tn.transliterate("Москва"));
        System.out.println("θεός (theos) => " + tn.transliterate("θεός"));

        System.out.println("=== applyStd ===");
        for (String w : new String[]{"google","wifi","facebook","google'as","googlui","wi-fi","skype","nelietuviskas"}) {
            System.out.println(w + " => " + tn.applyStd(w));
        }

        System.out.println("=== isSpellable / spell path ===");
        for (String w : new String[]{"ES","a","labas","NATO","Abc","b","123","Š"}) {
            System.out.println(w + " spellable=" + TextNormalizer.isSpellable(w));
        }

        System.out.println("=== punctuation levels on 'Labas, pasauli.' ===");
        for (int lvl = 0; lvl <= 3; lvl++) {
            TextNormalizer.Settings st = new TextNormalizer.Settings();
            st.punctuationLevel = lvl;
            System.out.println("level " + lvl + " => " + render(tn.normalize("Labas, pasauli.", st)));
        }

        System.out.println("=== spell path tokens ES, lone a ===");
        TextNormalizer.Settings st = new TextNormalizer.Settings();
        System.out.println("ES => " + render(tn.normalize("ES", st)));
        System.out.println("lone a => " + render(tn.normalize("sakau a garsiai", st)));

        System.out.println("=== whole pipeline ===");
        TextNormalizer.Settings sp = new TextNormalizer.Settings();
        sp.punctuationLevel = 3;
        System.out.println("'Turiu 25 google obuolius, ES.' =>\n  " +
                render(tn.normalize("Turiu 25 google obuolius, ES.", sp)));
    }

    static String render(List<TextNormalizer.Token> toks) {
        StringBuilder sb = new StringBuilder();
        for (TextNormalizer.Token t : toks) {
            sb.append("[");
            if (t.text != null && !t.text.isEmpty()) sb.append("'").append(t.text).append("'");
            else sb.append("<silent>");
            if (t.punctuation != 0) sb.append(" pc=").append(t.punctuation);
            if (t.spell) sb.append(" spell");
            sb.append("] ");
        }
        return sb.toString().trim();
    }
}
