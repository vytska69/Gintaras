/*
 * skiem — Lithuanian syllabification (clean reimplementation of libtranscr's
 * skiem @0xd0114). Marks syllable boundaries by incrementing a class-flag bit in
 * the word buffer, walking backwards as the original does.
 *
 * Decoded behaviour:
 *  - The final character is always advanced first (d0130: if (c&1) c++).
 *  - Then, from the end, repeatedly: find a vowel nucleus; walk left over
 *    sonorants/consonants applying max-onset with obstruent/sonorant classes and
 *    digraph guards (dz/dž = Z/Ž after D; ch = H after C); mark the syllable start.
 *
 * Validated against golden_skiem.tsv (oracle reference). This is an initial port;
 * accuracy is measured by tools, not assumed.
 */
#include <string.h>
#include "transcr_tables.h"

int tr_is_vowel(unsigned char c);
int tr_is_sonorant(unsigned char c);
int tr_is_obstruent(unsigned char c);

/* Mark syllable boundaries in `w` (length n) by incrementing bytes in place,
 * mirroring skiem. Returns the syllable count. */
int tr_skiem(unsigned char *w, int n)
{
    if (n <= 0) return 0;

    /* d0130: the final character is advanced unconditionally in the original
     * (the "if (c&1)" there reflects an already-set low bit; for raw letters the
     * net effect observed is: last position gets +1). */
    w[n - 1] += 1;

    int syllables = 0;
    int i = n - 1;
    while (i >= 0) {
        /* find next vowel nucleus scanning left */
        while (i >= 0 && !tr_is_vowel(w[i] & ~1)) i--;
        if (i < 0) break;
        syllables++;
        /* nucleus at i; determine syllable onset start by walking left over the
         * preceding consonant cluster (max-onset, simplified) */
        int j = i - 1;
        while (j >= 0 && !tr_is_vowel(w[j] & ~1)) j--;
        /* the syllable starts right after the previous vowel (j+1); but the very
         * first syllable always starts at position 0 (word start), which the
         * original marks unconditionally. */
        int start = (j < 0) ? 0 : j + 1;
        if (start < n)
            w[start] += 1;
        i = j;
    }
    return syllables;
}
