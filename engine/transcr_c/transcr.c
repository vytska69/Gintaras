/*
 * libtranscr — clean-room C reimplementation (arm64-capable).
 *
 * Reimplements the Lithuanian grapheme->phoneme front-end of the original
 * 32-bit-only libtranscr.so, function by function, each verified against the
 * deterministic emulator oracle (see _work/oracle). This file grows incrementally;
 * leaf helpers first, then syllabification / stress / durations / tones.
 *
 * Decompiled behaviour notes are kept next to each function so the mapping back
 * to the original ARM is auditable.
 */
#include <string.h>

/* The engine's custom isalpha: ASCII A-Z / a-z, plus Lithuanian letters in the
 * cp1257 high range handled via a lookup in the original (strchr over a letter
 * set). Original @0xd0ee0:
 *   if c in 'A'..'Z' or 'a'..'z' -> 1
 *   else strchr(LETTERS, c) != NULL -> 1 else 0
 * The LETTERS set (cp1257 Lithuanian) is provided by the caller/table; here we
 * accept the ASCII core and defer the high-range set to a table once extracted. */
int isalpha1(int c)
{
    unsigned uc = (unsigned)c & 0xff;
    if ((unsigned)((uc - 'A') & 0xff) <= 0x19u) return 1;
    if ((unsigned)((uc - 'a') & 0xff) <= 0x19u) return 1;
    /* TODO: high-range Lithuanian letters via extracted LETTERS table */
    return 0;
}

/* Original @0xd0f40: return (unsigned char)(c-'0') <= 9 */
int isdigit1(int c)
{
    return (unsigned)(((unsigned)c - '0') & 0xff) <= 9u;
}

/* Original @0xc7c04: in-place reverse of a C string. */
char *strrev(char *s)
{
    int n = (int)strlen(s);
    for (int i = 0, j = n - 1; i < j; i++, j--) {
        char t = s[i];
        s[i] = s[j];
        s[j] = t;
    }
    return s;
}
