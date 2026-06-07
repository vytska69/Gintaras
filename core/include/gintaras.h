/* Gintaras Lithuanian TTS core — C ABI (see core/src/ffi.rs).
 * Link the staticlib (libgintaras_core.a) into the iOS Speech Synthesis Provider
 * extension. All text is UTF-8; PCM is 16-bit mono at gintaras_sample_rate() Hz. */
#ifndef GINTARAS_H
#define GINTARAS_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct GintarasEngine GintarasEngine;

typedef struct GintarasParams {
    int32_t rate;              /* speech rate %, 100 = normal */
    int32_t pitch;             /* pitch %, 100 = normal */
    int32_t punctuation_level; /* punc table index (1 = off) */
    int32_t numgroup;          /* 16 = full cardinal; 1/2/3 = digit groups */
    int32_t use_dictionary;    /* 0/1 */
    int32_t pause_word;        /* % (50/100/300) */
    int32_t pause_sentence;    /* % (50/100/150) */
} GintarasParams;

/* Any asset buffer except dta may be NULL/0 (feature skipped). NULL on failure. */
GintarasEngine *gintaras_engine_create(
    const uint8_t *dta, size_t dta_len,
    const uint8_t *rules, size_t rules_len,
    const uint8_t *std_, size_t std_len,
    const uint8_t *spell, size_t spell_len,
    const uint8_t *punc0, size_t punc0_len,
    const uint8_t *punc1, size_t punc1_len,
    const uint8_t *punc2, size_t punc2_len,
    const uint8_t *punc3, size_t punc3_len);

uint32_t gintaras_sample_rate(const GintarasEngine *engine);

/* Returns a malloc'd 16-bit PCM buffer (sample count in *out_len), or NULL.
 * Free with gintaras_free_pcm(ptr, *out_len). */
int16_t *gintaras_synthesize(const GintarasEngine *engine,
                             const uint8_t *text, size_t text_len,
                             const GintarasParams *params, size_t *out_len);

void gintaras_free_pcm(int16_t *ptr, size_t len);
void gintaras_engine_destroy(GintarasEngine *engine);

#ifdef __cplusplus
}
#endif
#endif /* GINTARAS_H */
