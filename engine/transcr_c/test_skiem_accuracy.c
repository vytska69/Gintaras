#include <stdio.h>
#include <string.h>
#include <stdlib.h>
int tr_skiem(unsigned char*, int);
// minimal cp1257: golden file words are lowercase UTF-8; we need cp1257 uppercased.
// Simpler: the golden file lists positions; we replicate by reading word + deltas
// but words are UTF-8. Instead compare on ASCII-only words to bootstrap accuracy.
int main(int argc,char**argv){
    FILE*f=fopen(argv[1],"r"); char line[512];
    int total=0, match=0, ascii=0;
    while(fgets(line,sizeof line,f)){
        if(line[0]=='#') continue;
        char*tab=strchr(line,'\t'); if(!tab) continue;
        *tab=0; char*word=line; char*deltas=tab+1;
        deltas[strcspn(deltas,"\n")]=0;
        // only ASCII words (no multibyte) for this bootstrap measurement
        int isascii=1; for(char*p=word;*p;p++) if((unsigned char)*p>=0x80){isascii=0;break;}
        if(!isascii) continue;
        ascii++;
        // uppercase
        unsigned char w[64]; int n=strlen(word);
        for(int i=0;i<n;i++){char c=word[i]; w[i]=(c>='a'&&c<='z')?c-32:c;}
        unsigned char before[64]; memcpy(before,w,n);
        tr_skiem(w,n);
        // expected positions
        int exp[64],ne=0;
        if(*deltas){char*t=strtok(deltas,","); while(t){exp[ne++]=atoi(t);t=strtok(NULL,",");}}
        // our positions (where incremented)
        int got[64],ng=0;
        for(int i=0;i<n;i++) if(w[i]!=before[i]) got[ng++]=i;
        total++;
        int ok = (ng==ne);
        for(int i=0;ok&&i<ne;i++){int found=0;for(int k=0;k<ng;k++)if(got[k]==exp[i])found=1;if(!found)ok=0;}
        if(ok) match++;
    }
    printf("ASCII words: %d, syllable-mark exact match: %d (%.1f%%)\n",ascii,match,total?100.0*match/total:0);
    return 0;
}
