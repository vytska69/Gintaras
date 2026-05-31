/* libtranscr public ABI — recovered verbatim from main.bin LuaJIT FFI cdef.
 * Lithuanian linguistic front-end (grapheme->phoneme, stress, durations, tones).
 * These 7 functions are the ONLY proprietary native dependency blocking arm64. */
#ifndef LIBTRANSCR_H
#define LIBTRANSCR_H
#ifdef __cplusplus
extern "C" {
#endif
void init_transcr(void);
void PradApdZod (const char*, char*, int, char);
void SpellZod   (const char*, char*, int, char);
int  KircTranskr (const char*, char*, int, char);
int  KircTranskr1(const char*, char*, int, int, char);
int  ilgiai (const char*, char*, int, int, char);
int  tonai  (const char*, char*, int, int, char);
int  tonai1 (const char*, char*, int, int, double, double, double, int, char);
#ifdef __cplusplus
}
#endif
#endif
