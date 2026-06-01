#include <stdio.h>
#include <string.h>
int tr_transkr(const unsigned char*, int, char*);
// Convert a UTF-8 Lithuanian word to cp1257 uppercase
static int utf8_to_cp1257_upper(const char*in,unsigned char*out){
    int o=0;
    for(const unsigned char*p=(const unsigned char*)in;*p;){
        unsigned int cp;
        if(*p<0x80){cp=*p;p++;}
        else if((*p&0xe0)==0xc0){cp=(*p&0x1f)<<6|(p[1]&0x3f);p+=2;}
        else{cp=0x3f;p++;}
        // map unicode -> cp1257 uppercase
        unsigned char c;
        switch(cp){
            case 0x105:case 0x104:c=0xc0;break; // ą Ą
            case 0x10d:case 0x10c:c=0xc8;break; // č Č
            case 0x119:case 0x118:c=0xc6;break; // ę Ę
            case 0x117:case 0x116:c=0xcb;break; // ė Ė
            case 0x12f:case 0x12e:c=0xc1;break; // į Į
            case 0x161:case 0x160:c=0xd0;break; // š Š
            case 0x173:case 0x172:c=0xd8;break; // ų Ų
            case 0x16b:case 0x16a:c=0xdb;break; // ū Ū
            case 0x17e:case 0x17d:c=0xde;break; // ž Ž
            default:
                if(cp>='a'&&cp<='z')c=cp-32;
                else if(cp<128)c=cp;
                else c=0x3f;
        }
        out[o++]=c;
    }
    out[o]=0; return o;
}
int main(int argc,char**argv){
    FILE*f=fopen(argv[1],"r");char line[512];
    int total=0,match=0;int show=argc>2?atoi(argv[2]):0;int shown=0;
    while(fgets(line,sizeof line,f)){
        if(line[0]=='#')continue;
        char*c1=strchr(line,'\t');if(!c1)continue;*c1=0;
        char*word=line;char*rest=c1+1;
        char*c2=strchr(rest,'\t');if(!c2)continue;
        char*c3=strchr(c2+1,'\t');if(!c3)continue;
        char*c4=strchr(c3+1,'\t');
        char*phon=c3+1; if(c4){*c4=0;}
        unsigned char w[64];int n=utf8_to_cp1257_upper(word,w);
        char out[256];tr_transkr(w,n,out);
        total++;
        if(strcmp(out,phon)==0)match++;
        else if(show&&shown<show){printf("%-14s exp='%s' got='%s'\n",word,phon,out);shown++;}
    }
    printf("transkr accuracy: %d/%d = %.1f%%\n",match,total,total?100.0*match/total:0);
    return 0;
}
