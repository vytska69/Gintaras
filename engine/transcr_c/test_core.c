#include <stdio.h>
#include <string.h>
int tr_in_set(const unsigned char*, unsigned char);
unsigned char tr_upper(unsigned char);
int tr_is_vowel(unsigned char), tr_is_sonorant(unsigned char), tr_is_obstruent(unsigned char);
int tr_is_function_word(const unsigned char*);
void tr_normalise(unsigned char*);
int main(){
    int fails=0;
    // uppercasing including Lithuanian
    if(tr_upper('a')!='A'){printf("FAIL upper a\n");fails++;}
    if(tr_upper(0xe0)!=0xc0){printf("FAIL upper ą\n");fails++;}  // ą->Ą
    if(tr_upper(0xfe)!=0xde){printf("FAIL upper ž\n");fails++;}  // ž->Ž
    // vowel/consonant classes
    if(!tr_is_vowel('A')){printf("FAIL vowel A\n");fails++;}
    if(!tr_is_vowel(0xc0)){printf("FAIL vowel Ą\n");fails++;}
    if(!tr_is_sonorant('L')){printf("FAIL sonorant L\n");fails++;}
    if(!tr_is_obstruent('K')){printf("FAIL obstruent K\n");fails++;}
    if(tr_is_vowel('K')){printf("FAIL K not vowel\n");fails++;}
    // function words
    unsigned char w1[]="IR"; if(!tr_is_function_word(w1)){printf("FAIL funcword IR\n");fails++;}
    unsigned char w2[]="LABAS"; if(tr_is_function_word(w2)){printf("FAIL LABAS not funcword\n");fails++;}
    // normalise
    unsigned char w3[]={'l','a','b',0xe0,'s',0}; tr_normalise(w3);
    unsigned char exp[]={'L','A','B',0xc0,'S',0};
    if(memcmp(w3,exp,6)){printf("FAIL normalise\n");fails++;}
    if(!fails) printf("all core checks passed\n");
    return fails?1:0;
}
