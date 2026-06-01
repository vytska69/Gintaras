/*
 * transkr — grapheme→phoneme mapping (clean reimplementation, initial rules).
 *
 * Maps a normalised cp1257 Lithuanian word to a phoneme sequence, derived from
 * and validated against the oracle golden corpus (golden_big.tsv). Rules covered:
 *  - vowels (short + their stressed/long variants in stressed position)
 *  - consonant palatalisation before front vowels (i e ė ę į y) → adds '
 *  - word-final / pre-obstruent voicing assimilation (b→p d→t g→k z→s ž→S ...)
 *  - boundary markers '_' around the word
 *
 * Output is a space-joined phoneme string matching the oracle's format. This is
 * an initial rule set; accuracy is measured against the corpus and refined.
 */
#include <string.h>
#include "transcr_tables.h"

int tr_is_vowel(unsigned char c);

/* front vowels trigger palatalisation of a preceding consonant */
static int is_front_vowel(unsigned char c)
{
    return c=='I' || c=='Y' || c==0xc1 /*Į*/ || c=='E' || c==0xcb /*Ė*/ || c==0xc6 /*Ę*/;
}

/* base (voiced/plain) phoneme for a consonant grapheme, before a vowel */
static const char *cons_voiced(unsigned char c)
{
    switch (c) {
        case 'B': return "b"; case 'C': return "ts"; case 'D': return "d";
        case 'F': return "f"; case 'G': return "g"; case 'H': return "h";
        case 'K': return "k"; case 'L': return "l"; case 'M': return "m";
        case 'N': return "n"; case 'P': return "p"; case 'R': return "r";
        case 'S': return "s"; case 'T': return "t"; case 'V': return "v";
        case 'Z': return "z"; case 'J': return "j'";
        case 0xc8: return "tS"; /* Č */ case 0xd0: return "S"; /* Š */
        case 0xde: return "Z";  /* Ž */
        default: return 0;
    }
}

/* devoiced form for word-final / pre-unvoiced position */
static const char *cons_devoiced(unsigned char c)
{
    switch (c) {
        case 'B': return "p"; case 'D': return "t"; case 'G': return "k";
        case 'Z': return "s"; case 0xde: return "S"; /* Ž→S */
        case 0xd0: return "S"; /* Š */ case 'C': return "ts";
        case 0xc8: return "tS"; /* Č */
        default: return cons_voiced(c); /* unvoiced consonants unchanged */
    }
}

/* short phoneme for a vowel grapheme (unstressed); stressed variants handled by
 * the stress stage later. */
static const char *vowel_phoneme(unsigned char c)
{
    switch (c) {
        case 'A': return "aA"; case 0xc0: return "aA"; /* Ą */
        case 'E': return "eA"; case 0xc6: return "eA"; /* Ę */
        case 0xcb: return "eE"; /* Ė */
        case 'I': return "i";  case 0xc1: return "iI"; /* Į */
        case 'Y': return "iI";
        case 'O': return "oO";
        case 'U': return "u";  case 0xdb: return "uU"; /* Ū */
        case 0xd8: return "uU"; /* Ų */
        default: return 0;
    }
}

/* A consonant at position i is palatalised if a front vowel or softness 'i'
 * follows, possibly through a chain of intervening consonants that are
 * themselves palatalised (regressive palatalisation: dirbti d' i R' p' t'). */
static int is_palatalised(const unsigned char *w, int n, int i)
{
    if (i + 1 >= n) return 0;
    unsigned char nx = w[i + 1];
    if (is_front_vowel(nx) || nx == 'I' || nx == 'J') return 1;
    if (vowel_phoneme(nx)) return 0;          /* a non-front vowel blocks it */
    if (cons_voiced(nx)) return is_palatalised(w, n, i + 1);
    return 0;
}

/* Append a phoneme token (space-separated) to out. */
static int emit(char *out, int pos, const char *ph)
{
    if (pos) out[pos++] = ' ';
    int n = (int)strlen(ph);
    memcpy(out + pos, ph, n);
    return pos + n;
}

/* Transcribe normalised word `w` (length n, cp1257, no trailing space) into
 * `out` as a space-joined phoneme string with '_' boundaries. Returns length. */
int tr_transkr(const unsigned char *w, int n, char *out)
{
    int pos = 0;
    pos = emit(out, pos, "_");
    for (int i = 0; i < n; i++) {
        unsigned char c = w[i];

        /* 'i'/'j' before a back/low vowel (a o u ą ų ū) is a softness marker that
         * also fronts the vowel: ia→eA, io→oO, iu→u. The 'i' is absorbed; the
         * following 'a/ą' surfaces as eA (front). Before a front vowel 'i' stays
         * (lie→l' i eA). Applies word-initially and after a consonant. */
        if (c == 'I' && !(i > 0 && vowel_phoneme(w[i - 1])) && (i + 1 < n)) {
            unsigned char nx = w[i + 1];
            int back = (nx=='A'||nx==0xc0||nx=='O'||nx=='U'||nx==0xd8||nx==0xdb);
            if (back) continue; /* absorbed; vowel fronting handled below */
        }

        /* Diphthong glides: i/u as the second element of a diphthong become J/W,
         * but ONLY after a SHORT vowel (a e o). After long/nasal vowels (ą ė ę į
         * ū ų y u) the i/u stays a separate vowel (ąi→aA i, not a glide). */
        if (i > 0) {
            unsigned char pv = w[i - 1];
            int short_vowel = (pv=='A'||pv=='E'||pv=='O'||pv=='U');
            if (short_vowel) {
                if (c == 'I') { pos = emit(out, pos, "J"); continue; }
                if (c == 'U' && pv != 'U') { pos = emit(out, pos, "W"); continue; }
            }
        }

        const char *vp = vowel_phoneme(c);
        if (vp) {
            /* Fronting: a back vowel 'a'/'ą' preceded by a softness 'i' or by 'j'
             * surfaces as the front vowel eA (ia→eA, ja→j' eA, vėjas→...j' eA s).
             * After 'i' only when the 'i' was an absorbed softness marker (not a
             * diphthong, i.e. not itself preceded by a vowel). */
            if ((c=='A' || c==0xc0) && i > 0 &&
                ((w[i-1]=='I' && !(i >= 2 && vowel_phoneme(w[i-2]))) ||
                  w[i-1]=='J')) {
                pos = emit(out, pos, "eA");
                continue;
            }
            pos = emit(out, pos, vp);
            continue;
        }

        /* Sonorants l m n take their uppercase variant in syllable coda — at word
         * end or before another consonant (knyga k'→N', mokykla→L, žmogus→M).
         * 'r' surfaces as uppercase R whenever it is not a simple single onset
         * before a vowel: i.e. in a coda, OR in a consonant cluster (after or
         * before another consonant) such as br, dr, tr (brolis→b R). The
         * palatalised ' is added afterwards by the palatal logic below, so emit
         * the bare uppercase and fall through is not possible — handle ' here. */
        {
            int next_is_cons = (i + 1 >= n) || (vowel_phoneme(w[i + 1]) == 0 &&
                                                 w[i+1] != 'I');
            int prev_is_cons = (i > 0) && (vowel_phoneme(w[i - 1]) == 0);
            int after_vowel  = (i > 0) && vowel_phoneme(w[i - 1]);
            int coda = after_vowel && next_is_cons;
            /* palatalisation flag for the uppercase sonorant */
            int pal = is_palatalised(w, n, i);
            /* Sonorants surface uppercase in a coda OR when adjacent to another
             * consonant in a cluster (knyga kn→k' N', žmogus žm→Z M, mokykla
             * kl→k L, brolis br→b R). */
            int clustered = coda || prev_is_cons || (next_is_cons && i + 1 < n &&
                                                      cons_voiced(w[i+1]));
            const char *up = 0;
            if (c == 'L' && clustered) up = "L";
            else if (c == 'M' && clustered) up = "M";
            else if (c == 'N' && clustered) up = "N";
            else if (c == 'R' && clustered) up = "R";
            if (up) {
                if (pal) { char b[4]; b[0]=up[0]; b[1]='\''; b[2]=0; pos = emit(out, pos, b); }
                else pos = emit(out, pos, up);
                continue;
            }
        }

        const char *cp = cons_voiced(c);
        if (!cp) continue; /* unknown char: skip */
        /* Voicing assimilation. An obstruent devoices at word end; within a
         * cluster it assimilates to the following obstruent's voicing
         * (regressive): voiced→unvoiced before an unvoiced obstruent
         * (bėgti g→k, dirbti b→p), unvoiced→voiced before a voiced obstruent
         * (trisdešimt s→z). Sonorants and vowels don't trigger it. */
        int final = (i == n - 1);
        const char *use = cp;
        if (final) {
            use = cons_devoiced(c);
        } else {
            unsigned char nx = w[i + 1];
            /* unvoiced obstruents: P T K S Š C Č F H; voiced: B D G Z Ž */
            int nx_unvoiced = (nx=='P'||nx=='T'||nx=='K'||nx=='S'||nx==0xd0||
                               nx=='C'||nx==0xc8||nx=='F'||nx=='H');
            int nx_voiced   = (nx=='B'||nx=='D'||nx=='G'||nx=='Z'||nx==0xde);
            if (nx_unvoiced) {
                use = cons_devoiced(c);                 /* G→k, B→p before unvoiced */
            } else if (nx_voiced) {
                /* voice an unvoiced obstruent before a voiced one (S→z, K→g) */
                switch (c) {
                    case 'S': use = "z"; break;
                    case 0xd0: use = "Z"; break;        /* Š→Z */
                    case 'K': use = "g"; break;
                    case 'P': use = "b"; break;
                    case 'T': use = "d"; break;
                    case 'C': use = "dz"; break;
                    case 0xc8: use = "dZ"; break;       /* Č→dZ */
                    default: use = cp; break;
                }
            }
        }
        /* palatalisation: before a front vowel, before a softness 'i'/'j', or
         * regressively before another palatalised consonant (a consonant whose
         * own following segment is a front vowel or softness i). */
        int palatal = is_palatalised(w, n, i);
        char buf[8];
        if (palatal && use[strlen(use)-1] != '\'') {
            int m = (int)strlen(use);
            memcpy(buf, use, m); buf[m] = '\''; buf[m+1] = 0;
            pos = emit(out, pos, buf);
        } else {
            pos = emit(out, pos, use);
        }
    }
    pos = emit(out, pos, "_");
    out[pos] = 0;
    return pos;
}
