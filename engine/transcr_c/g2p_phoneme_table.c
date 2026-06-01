/* Phoneme-output symbol table, extracted verbatim from libtranscr.so
 * .rodata @0xd2e3c (4-byte stride, 90 entries). transkr's rule matcher
 * emits these by index. Order is significant (vowels 0-32, consonants 33+). */
#include "transcr_tables.h"

const char *const TR_PHONEMES[90] = {
    "i",  /* 0 */
    "e",
    "a",
    "o",
    "u",
    "I",  /* 5 */
    "E",
    "A",
    "U",
    "ii",
    "Ii",  /* 10 */
    "iI",
    "ie",
    "Ie",
    "iE",
    "ee",  /* 15 */
    "Ee",
    "eE",
    "ea",
    "Ea",
    "eA",  /* 20 */
    "aa",
    "Aa",
    "aA",
    "oo",
    "Oo",  /* 25 */
    "oO",
    "uo",
    "Uo",
    "uO",
    "uu",  /* 30 */
    "Uu",
    "uU",
    "p",
    "p'",
    "b",  /* 35 */
    "b'",
    "t",
    "t'",
    "d",
    "d'",  /* 40 */
    "k",
    "k'",
    "g",
    "g'",
    "ts",  /* 45 */
    "ts'",
    "dz",
    "dz'",
    "tS",
    "tS'",  /* 50 */
    "dZ",
    "dZ'",
    "s",
    "s'",
    "z",  /* 55 */
    "z'",
    "S",
    "S'",
    "Z",
    "Z'",  /* 60 */
    "x",
    "x'",
    "h",
    "h'",
    "f",  /* 65 */
    "f'",
    "j'",
    "j",
    "J",
    "v",  /* 70 */
    "v'",
    "w",
    "W",
    "l",
    "l'",  /* 75 */
    "L",
    "L'",
    "r",
    "r'",
    "R",  /* 80 */
    "R'",
    "m",
    "m'",
    "M",
    "M'",  /* 85 */
    "n",
    "n'",
    "N",
    "N'",
};
const int TR_PHONEME_COUNT = 90;
