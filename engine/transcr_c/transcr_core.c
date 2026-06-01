/*
 * transcr_core — Lithuanian grapheme→phoneme pipeline (clean reimplementation).
 *
 * Implements the top-level flow KircTranskr1 orchestrates:
 *   normalise → syllabify (skiem) → grapheme-to-phoneme (transkr) → stress
 *   (Kirciuoti) → durations (ilgiai) → tones (tonai).
 *
 * Built incrementally and verified by differential testing against the
 * deterministic emulator oracle over golden_big.tsv. This file currently provides
 * the normalisation + classification scaffolding using the verbatim-extracted
 * tables; the rule matcher and prosody stages are filled in stage by stage.
 */
#include <string.h>
#include "transcr_tables.h"

/* Membership test over a 0-terminated cp1257 byte set. */
int tr_in_set(const unsigned char *set, unsigned char c)
{
    for (; *set; set++)
        if (*set == c) return 1;
    return 0;
}

/* cp1257 uppercasing using the extracted Lithuanian case-fold pairs plus ASCII. */
unsigned char tr_upper(unsigned char c)
{
    if (c >= 'a' && c <= 'z') return (unsigned char)(c - 32);
    for (int i = 0; TR_CASEFOLD[i][0]; i++)
        if (TR_CASEFOLD[i][1] == c) return TR_CASEFOLD[i][0];
    return c;
}

int tr_is_vowel(unsigned char c)   { return tr_in_set(TR_SYLL_VOWELS, c); }
int tr_is_sonorant(unsigned char c){ return tr_in_set(TR_SONORANTS, c); }
int tr_is_obstruent(unsigned char c){ return tr_in_set(TR_SYLL_OBSTR, c); }

/* Whether an all-caps cp1257 word is a function word (clitic, no stress). */
int tr_is_function_word(const unsigned char *word)
{
    for (int i = 0; i < TR_FUNCTION_WORD_COUNT; i++)
        if (strcmp((const char *)word, (const char *)TR_FUNCTION_WORDS[i]) == 0)
            return 1;
    return 0;
}

/* Uppercase a cp1257 word in place (normalisation step before transcription). */
void tr_normalise(unsigned char *word)
{
    for (; *word; word++) *word = tr_upper(*word);
}
