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

        /* 'i' before a back/low vowel (a o u ą ų ū) is a pure softness marker:
         * it is absorbed (ia→eA, io→oO, iu→u), palatalising any preceding
         * consonant. Before a front vowel it stays as i (lie→l' i eA). */
        if (c == 'I' && !(i > 0 && vowel_phoneme(w[i - 1])) && (i + 1 < n)) {
            unsigned char nx = w[i + 1];
            int back = (nx=='A'||nx==0xc0||nx=='O'||nx=='U'||nx==0xd8||nx==0xdb);
            if (back) continue; /* absorbed */
        }

        /* i/u as the second element of a diphthong (immediately after a vowel)
         * become the glides J/W — including word-finally (ai→aA J, au→aA W).
         * 'y' after a vowel stays a long vowel iI (ay→aA iI), not a glide. */
        if (i > 0 && vowel_phoneme(w[i - 1])) {
            if (c == 'I') { pos = emit(out, pos, "J"); continue; }
            if (c == 'U') { pos = emit(out, pos, "W"); continue; }
        }

        const char *vp = vowel_phoneme(c);
        if (vp) {
            pos = emit(out, pos, vp);
            continue;
        }

        /* sonorants l m n r in syllable coda (after a vowel, at word end or
         * before a consonant) take their uppercase positional variant. */
        if (i > 0 && vowel_phoneme(w[i - 1])) {
            int coda = (i == n - 1) || !vowel_phoneme(w[i + 1]);
            if (coda) {
                if (c == 'L') { pos = emit(out, pos, "L"); continue; }
                if (c == 'M') { pos = emit(out, pos, "M"); continue; }
                if (c == 'N') { pos = emit(out, pos, "N"); continue; }
                if (c == 'R') { pos = emit(out, pos, "R"); continue; }
            }
        }

        const char *cp = cons_voiced(c);
        if (!cp) continue; /* unknown char: skip */
        /* voicing: devoice at word end or before another (unvoiced) obstruent */
        int final = (i == n - 1);
        const char *use = final ? cons_devoiced(c) : cp;
        /* palatalisation before a front vowel */
        int palatal = (i + 1 < n) && is_front_vowel(w[i + 1]);
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
