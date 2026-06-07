package com.rosasoft.wintalker.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text-normalization ("reading") layer for the Lithuanian TTS engine — the
 * missing port of the original {@code voicesynth} speak loop (root.53) plus the
 * {@code dictionary} module (dictconv / loaddictionary) and {@code translate}
 * number expansion. Given raw input text it produces the sequence of spoken
 * tokens the {@link Transcriber} / {@link DiphoneSynth} consume.
 *
 * <p>Pure Java with no Android dependency so it can be unit-tested standalone; the
 * Android service wires it up by feeding asset {@link InputStream}s.
 *
 * <h3>Pipeline (mirrors voicesynth root.53)</h3>
 * <ol>
 *   <li>Apply the per-character transliteration map from {@code ruleslit.rul}
 *       (Cyrillic / Greek source char → Latin/Lithuanian string) to the raw text.</li>
 *   <li>Tokenize: split on whitespace AND keep the punctuation characters
 *       {@code .!?,;:()[]{}} as separable tokens (root.53 const K[-13]).</li>
 *   <li>For each token:
 *     <ul>
 *       <li>punctuation char → its spoken word from the selected {@code punc<level>}
 *           table (dictconv with DICT["punc"+level+lang]); the symbol still drives
 *           the pause logic in the caller.</li>
 *       <li>a lone letter / acronym → the {@code spell} path (read letters one by
 *           one, slowed); with an empty {@code spelllit.dct} this falls back to
 *           reading each letter name.</li>
 *       <li>otherwise → expand digits ({@link NumberExpander}) and, when
 *           use_dictionary is on, apply the {@code std} dictionary (dictconv with
 *           DICT["std"+lang]).</li>
 *     </ul></li>
 * </ol>
 */
public final class TextNormalizer {

    /** Punctuation characters kept as separate tokens (voicesynth root.53 K[-13]). */
    static final String PUNCT = ".!?,;:()[]{}";

    /** Per-character transliteration from ruleslit.rul: source char → replacement. */
    private final Map<Character, String> rules;
    /** std dictionary: stem (lower-cased, no trailing '*') → spoken replacement. */
    private final Map<String, String> std;
    /** spell dictionary: letter token → spoken spelling (usually empty). */
    private final Map<String, String> spell;
    /** punctuation tables indexed by level 0..3: punctuation char → spoken word. */
    private final Map<Character, String>[] punc;

    private final NumberExpander numbers;

    @SuppressWarnings("unchecked")
    private TextNormalizer(Map<Character, String> rules, Map<String, String> std,
                           Map<String, String> spell, Map<Character, String>[] punc,
                           NumberExpander numbers) {
        this.rules = rules;
        this.std = std;
        this.spell = spell;
        this.punc = punc;
        this.numbers = numbers;
    }

    /** One normalized output token: the spoken text plus the punctuation symbol
     *  that produced it (0 when none) so the caller can keep its pause model. */
    public static final class Token {
        public final String text;     // spoken text to transcribe ("" = silent)
        public final char punctuation; // the punctuation char, or 0
        public final boolean spell;    // true if this is a spell-path token
        public final String[] phonemes; // pre-resolved phonemes (spelled letters); else null
        public Token(String text, char punctuation, boolean spell) {
            this(text, punctuation, spell, null);
        }
        public Token(String text, char punctuation, boolean spell, String[] phonemes) {
            this.text = text;
            this.punctuation = punctuation;
            this.spell = spell;
            this.phonemes = phonemes;
        }
    }

    /** Settings that change reading behaviour (SettingsActivity / arrays.xml). */
    public static final class Settings {
        public int punctuationLevel = 0; // punc file index (puncValues)
        public int numgroup = NumberExpander.NUMGROUP_FULL;
        public boolean useDictionary = true;
    }

    // ----- loading -------------------------------------------------------------

    /**
     * Build a normalizer from the asset streams. Any stream may be null/absent;
     * the corresponding feature is then skipped. {@code puncStreams} is indexed by
     * level 0..3 (punc0lit.dct .. punc3lit.dct).
     */
    @SuppressWarnings("unchecked")
    public static TextNormalizer create(VoiceDatabase db, InputStream rulesIn,
                                        InputStream stdIn, InputStream spellIn,
                                        InputStream[] puncStreams) throws IOException {
        Map<Character, String> rules = parseRules(rulesIn);
        Map<String, String> std = parseStd(stdIn);
        Map<String, String> spell = parseStd(spellIn); // same "key replacement" form
        Map<Character, String>[] punc = new Map[4];
        for (int lvl = 0; lvl < 4; lvl++) {
            InputStream s = (puncStreams != null && lvl < puncStreams.length) ? puncStreams[lvl] : null;
            punc[lvl] = parsePunc(s);
        }
        return new TextNormalizer(rules, std, spell, punc, new NumberExpander(db));
    }

    /** Read a UTF-16LE (BOM-prefixed) or ASCII file fully into a String. The punc0
     *  file is plain ASCII; the others are UTF-16LE — detect via the BOM. */
    private static String slurp(InputStream in) throws IOException {
        if (in == null) return null;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        byte[] b = bos.toByteArray();
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            // UTF-16LE BOM
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        return new String(b, StandardCharsets.UTF_8); // ASCII (punc0lit.dct)
    }

    /** ruleslit.rul: lines "D<src> <dst>" (\n-separated). 'D' prefix, one source
     *  char, a space, then the destination string. */
    private static Map<Character, String> parseRules(InputStream in) throws IOException {
        Map<Character, String> m = new LinkedHashMap<>();
        String txt = slurp(in);
        if (txt == null) return m;
        for (String line : txt.split("\n")) {
            line = stripCr(line);
            if (line.length() < 3 || line.charAt(0) != 'D') continue;
            char src = line.charAt(1);
            int sp = line.indexOf(' ', 2);
            if (sp < 0) continue;
            String dst = line.substring(sp + 1);
            m.put(src, dst);
        }
        return m;
    }

    /** stdlit.dct / spelllit.dct: lines "stem* replacement" (\n-separated, space
     *  separator). The trailing '*' on the key is a word-tail wildcard; we store
     *  the bare stem (lower-cased) → replacement. */
    private static Map<String, String> parseStd(InputStream in) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        String txt = slurp(in);
        if (txt == null) return m;
        for (String line : txt.split("\n")) {
            line = stripCr(line);
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            String key = line.substring(0, sp);
            String val = line.substring(sp + 1);
            if (key.endsWith("*")) key = key.substring(0, key.length() - 1);
            if (key.isEmpty()) continue;
            m.put(key.toLowerCase(), val);
        }
        return m;
    }

    /** punc{0..3}lit.dct: lines "*X* replacement" (\r\n-separated). X is the
     *  punctuation char; the replacement is its spoken form at that verbosity. */
    private static Map<Character, String> parsePunc(InputStream in) throws IOException {
        Map<Character, String> m = new LinkedHashMap<>();
        String txt = slurp(in);
        if (txt == null) return m;
        for (String line : txt.split("\n")) {
            line = stripCr(line);
            // expected form: *X* replacement
            if (line.length() < 3 || line.charAt(0) != '*') continue;
            char ch = line.charAt(1);
            int close = line.indexOf('*', 2);
            if (close < 0) continue;
            String rest = (close + 1 < line.length()) ? line.substring(close + 1) : "";
            if (rest.startsWith(" ")) rest = rest.substring(1);
            m.put(ch, rest);
        }
        return m;
    }

    private static String stripCr(String s) {
        if (!s.isEmpty() && s.charAt(s.length() - 1) == '\r') return s.substring(0, s.length() - 1);
        return s;
    }

    // ----- normalization -------------------------------------------------------

    /** Apply the ruleslit per-character transliteration to the raw text. The rule
     *  file only carries lower-case Cyrillic/Greek source chars (the original
     *  voicesynth root.53 tolower()s the input before "conversion"); to keep the
     *  spell-path acronym detection working on the original casing we do NOT
     *  lower-case the whole text — instead, for a char with no direct rule we fall
     *  back to its lower-case form's rule. This only ever fires for upper-case
     *  Cyrillic/Greek (no Latin/Lithuanian char has a rule), so plain ASCII and
     *  all-caps acronyms pass through unchanged. */
    public String transliterate(String text) {
        if (rules.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String r = rules.get(c);
            if (r == null) {
                char lc = Character.toLowerCase(c);
                if (lc != c) r = rules.get(lc);
            }
            sb.append(r != null ? r : String.valueOf(c));
        }
        return sb.toString();
    }

    /**
     * Normalize raw input into the ordered spoken tokens. Mirrors voicesynth
     * root.53: transliterate, tokenize keeping punctuation, then per-token apply
     * punc / spell / std + numbers.
     */
    public List<Token> normalize(String raw, Settings st) {
        List<Token> out = new ArrayList<>();
        String text = transliterate(raw);
        List<String> toks = tokenize(text);
        // A single-token input is a lone letter being read on its own (e.g. a
        // TalkBack keystroke) — only then do we spell a single letter by name; in a
        // sentence a one-letter token (o, į, …) is a real word and is read normally.
        boolean wholeIsSingle = toks.size() == 1;
        for (String tok : toks) {
            if (tok.isEmpty()) continue;
            if (tok.length() == 1 && PUNCT.indexOf(tok.charAt(0)) >= 0) {
                char ch = tok.charAt(0);
                Map<Character, String> table = punc[clampLevel(st.punctuationLevel)];
                String spoken = table.get(ch);
                // The punc replacement keeps the symbol so the spoken word is the
                // table value minus the trailing symbol char (e.g. "kablelis,").
                // We strip the punctuation char itself; what remains is read aloud.
                String word = spoken == null ? "" : stripSymbol(spoken, ch);
                out.add(new Token(word, ch, false));
                continue;
            }
            // Spell path: a lone typed letter (whole input) or an all-caps acronym
            // (anywhere) → read each letter by its name.
            boolean loneLetter = wholeIsSingle && tok.length() == 1
                    && Character.isLetter(tok.charAt(0));
            boolean acronym = tok.length() > 1 && isAllUpper(tok) && hasNoDigit(tok);
            if (loneLetter || acronym) {
                for (int k = 0; k < tok.length(); k++) {
                    String key = String.valueOf(Character.toLowerCase(tok.charAt(k)));
                    String[] ph = LETTER_PHONEMES.get(key);
                    if (ph != null) {
                        // Faithful spell: the original engine's own SpellZod->KircTranskr
                        // phonemes for this letter — synthesized directly (no re-transcribe).
                        out.add(new Token("", (char) 0, true, ph));
                    } else {
                        // Fallback (custom spelllit.dct entry or unknown char): read by name.
                        String nm = spell.get(key);
                        if (nm == null) nm = LETTER_NAMES.get(key);
                        out.add(new Token(nm != null ? nm : String.valueOf(tok.charAt(k)),
                                (char) 0, true));
                    }
                }
                continue;
            }
            // Word path: substitute non-Lithuanian x/q/w, then numbers + dictionary.
            String expanded = numbers.expand(subAlien(tok), st.numgroup);
            for (String w : expanded.split("\\s+")) {
                if (w.isEmpty()) continue;
                String spoken = st.useDictionary ? applyStd(w) : w;
                out.add(new Token(spoken, (char) 0, false));
            }
        }
        return out;
    }

    private static boolean hasNoDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static int clampLevel(int lvl) {
        return (lvl < 0 || lvl > 3) ? 0 : lvl;
    }

    /** Strip the trailing punctuation symbol from a punc-table value, leaving only
     *  the spoken word (or "" if the value is just the bare symbol, as in punc1). */
    private static String stripSymbol(String value, char ch) {
        String v = value;
        // remove a trailing copy of the symbol char
        int e = v.length();
        if (e > 0 && v.charAt(e - 1) == ch) v = v.substring(0, e - 1);
        return v.trim();
    }

    /** Tokenize on whitespace while emitting each PUNCT char as its own token. */
    static List<String> tokenize(String text) {
        List<String> toks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                flush(toks, cur);
            } else if (PUNCT.indexOf(c) >= 0) {
                flush(toks, cur);
                toks.add(String.valueOf(c));
            } else {
                cur.append(c);
            }
        }
        flush(toks, cur);
        return toks;
    }

    private static void flush(List<String> toks, StringBuilder cur) {
        if (cur.length() > 0) { toks.add(cur.toString()); cur.setLength(0); }
    }

    /** A token is spellable when it is a single alphabetic char or an all-uppercase
     *  multi-letter acronym (no digits). Matches root.53's "lone letter" detection. */
    static boolean isSpellable(String tok) {
        if (tok.isEmpty()) return false;
        boolean hasLetter = false;
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (Character.isDigit(c)) return false;
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (!Character.isUpperCase(c) && tok.length() > 1) return false;
            }
        }
        if (!hasLetter) return false;
        return tok.length() == 1 || isAllUpper(tok);
    }

    private static boolean isAllUpper(String tok) {
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (Character.isLetter(c) && !Character.isUpperCase(c)) return false;
        }
        return true;
    }


    /** Lithuanian (and foreign) letter NAMES for spelled-out letters. Recovered
     *  VERBATIM from the original engine's native speller (libtranscr {@code SpellZod})
     *  by running it over every letter — not hand-written. (The original additionally
     *  marks stress/length via ~`^ which the engine renders near-monotone; only the
     *  letter name is kept here.) Notable forms: w→"dablvė", y→"y ilgoji",
     *  į→"y nosinė", ų→"ū nosinė", ū→"ū ilgoji". */
    private static final Map<String, String> LETTER_NAMES = new java.util.HashMap<>();
    static {
        String[][] n = {
            {"a","a"},{"ą","ą nosinė"},{"b","bė"},{"c","cė"},{"č","čė"},{"d","dė"},
            {"e","e"},{"ę","ę nosinė"},{"ė","ė"},{"f","ef"},{"g","gė"},{"h","ha"},
            {"i","i"},{"į","y nosinė"},{"y","y ilgoji"},{"j","jot"},{"k","ka"},
            {"l","el"},{"m","em"},{"n","en"},{"o","o"},{"p","pė"},{"r","er"},
            {"s","es"},{"š","eš"},{"t","tė"},{"u","u"},{"ų","ū nosinė"},
            {"ū","ū ilgoji"},{"v","vė"},{"z","zė"},{"ž","žė"},
            {"w","dablvė"},{"x","iks"},{"q","kū"},
        };
        for (String[] e : n) LETTER_NAMES.put(e[0], e[1]);
    }

    /** Per-letter PHONEMES recovered VERBATIM from the original engine: the native
     *  speller {@code SpellZod} run over each letter, then the native transcriber
     *  {@code KircTranskr} — captured via the libtranscr oracle. These are fed
     *  straight to the synthesiser (no re-transcription), so spelled letters carry
     *  the original's exact stress/length (e.g. f="E f", y="iI i L g Oo j' i",
     *  w="d aA b' L' v' eE"). This is the faithful port of the spell path. */
    private static final Map<String, String[]> LETTER_PHONEMES = new java.util.HashMap<>();
    static {
        String[][] ph = {
            {"a","_ aA _ _"},
            {"ą","_ aA n oO s' i n' eE _ _"},
            {"b","_ b' eE _ _"},
            {"c","_ ts' eE _ _"},
            {"č","_ tS' eE _ _"},
            {"d","_ d' eE _ _"},
            {"e","_ eA _ _"},
            {"ę","_ eA n oO s' i n' eE _ _"},
            {"ė","_ eE _ _"},
            {"f","_ E f _ _"},
            {"g","_ g' eE _ _"},
            {"h","_ h aA _ _"},
            {"i","_ i _ _"},
            {"į","_ iI n oO s' i n' eE _ _"},
            {"y","_ iI i L g Oo j' i _ _"},
            {"j","_ j' O t _ _"},
            {"k","_ k aA _ _"},
            {"l","_ E L _ _"},
            {"m","_ E M _ _"},
            {"n","_ E N _ _"},
            {"o","_ oO _ _"},
            {"p","_ p' eE _ _"},
            {"q","_ k uU _ _"},
            {"r","_ E R _ _"},
            {"s","_ E s _ _"},
            {"š","_ E S _ _"},
            {"t","_ t' eE _ _"},
            {"u","_ U _ _"},
            {"ų","_ uU n oO s' i n' eE _ _"},
            {"ū","_ uU i L g Oo j' i _ _"},
            {"v","_ v' eE _ _"},
            {"w","_ d aA b' L' v' eE _ _"},
            {"x","_ i k s _ _"},
            {"z","_ z' eE _ _"},
            {"ž","_ Z' eE _ _"},
        };
        for (String[] e : ph) LETTER_PHONEMES.put(e[0], e[1].split(" "));
    }

    /** Substitute the non-Lithuanian letters x/q/w with their phonetic reading when
     *  they appear INSIDE a word (the transcriber drops them otherwise): x→"ks",
     *  q→"k", w→"v" (e.g. taxi→taksi, watas→vatas). Case preserved approximately. */
    static String subAlien(String w) {
        if (w.indexOf('x') < 0 && w.indexOf('X') < 0 && w.indexOf('q') < 0
                && w.indexOf('Q') < 0 && w.indexOf('w') < 0 && w.indexOf('W') < 0)
            return w;
        StringBuilder b = new StringBuilder(w.length() + 2);
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            switch (c) {
                case 'x': b.append("ks"); break; case 'X': b.append("KS"); break;
                case 'q': b.append('k');  break; case 'Q': b.append('K');  break;
                case 'w': b.append('v');  break; case 'W': b.append('V');  break;
                default:  b.append(c);
            }
        }
        return b.toString();
    }

    /** Apply the std dictionary with the trailing-wildcard stem match (dictconv,
     *  dictionary root.31.1): find the longest stem that is a prefix of the word;
     *  replace it with the dictionary value and keep the remaining inflectional
     *  ending to be transcribed normally. The original (dictionary root.27/27.2)
     *  CONCATENATES the replacement with the matched tail (no separator), e.g.
     *  "google'as" -> "gūgll" .. "'as", so we do the same. */
    String applyStd(String word) {
        if (std.isEmpty()) return word;
        String lower = word.toLowerCase();
        // longest stem prefix wins
        String bestKey = null;
        for (String stem : std.keySet()) {
            if (lower.startsWith(stem)) {
                if (bestKey == null || stem.length() > bestKey.length()) bestKey = stem;
            }
        }
        if (bestKey == null) return word;
        String repl = std.get(bestKey);
        String ending = word.substring(bestKey.length());
        return ending.isEmpty() ? repl : repl + ending;
    }
}
