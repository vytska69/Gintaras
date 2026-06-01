/* Unit checks for the reimplemented leaf functions. Each expected value is the
 * behaviour decompiled from the original libtranscr.so. Exit non-zero on mismatch
 * so CI gates regressions. */
#include <stdio.h>
#include <string.h>

int isalpha1(int c);
int isdigit1(int c);
char *strrev(char *s);

static int fails = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); fails++; } } while (0)

int main(void)
{
    /* isdigit1 */
    for (int c = 0; c < 256; c++)
        CHECK(isdigit1(c) == (c >= '0' && c <= '9'), "isdigit1");

    /* isalpha1 ASCII core */
    CHECK(isalpha1('A') == 1, "isalpha1 A");
    CHECK(isalpha1('Z') == 1, "isalpha1 Z");
    CHECK(isalpha1('a') == 1, "isalpha1 a");
    CHECK(isalpha1('z') == 1, "isalpha1 z");
    CHECK(isalpha1('0') == 0, "isalpha1 0");
    CHECK(isalpha1(' ') == 0, "isalpha1 space");
    CHECK(isalpha1('@') == 0, "isalpha1 @");

    /* strrev */
    char b1[] = "labas";       strrev(b1); CHECK(strcmp(b1, "sabal") == 0, "strrev labas");
    char b2[] = "";            strrev(b2); CHECK(strcmp(b2, "") == 0, "strrev empty");
    char b3[] = "a";           strrev(b3); CHECK(strcmp(b3, "a") == 0, "strrev single");
    char b4[] = "ab";          strrev(b4); CHECK(strcmp(b4, "ba") == 0, "strrev pair");

    if (fails == 0) { printf("all leaf-function checks passed\n"); return 0; }
    printf("%d check(s) failed\n", fails);
    return 1;
}
