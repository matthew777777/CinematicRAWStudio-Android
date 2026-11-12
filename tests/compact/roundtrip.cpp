// SPDX-License-Identifier: Apache-2.0
#include "craw_compact.h"
#include <craw/Writer.h>
#include <craw/Reader.h>
#include "Crc32c.h"
#include <array>
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <stdexcept>
#include <unistd.h>
namespace fs=std::filesystem;
static void check(bool b,const char*m){if(!b)throw std::runtime_error(m);}
static void put(std::vector<uint8_t>&v,uint64_t n,int bytes){for(int i=0;i<bytes;++i)v.push_back(uint8_t(n>>(8*i)));}
static uint64_t le(const uint8_t*p,int n){uint64_t v=0;for(int i=0;i<n;++i)v|=uint64_t(p[i])<<(8*i);return v;}
static std::vector<uint8_t> read(const fs::path&p){std::ifstream f(p,std::ios::binary);return {std::istreambuf_iterator<char>(f),{}};}
static void injectOpaque(const fs::path&path){
    auto bytes=read(path);const auto offset=le(bytes.data()+bytes.size()-16,8);const auto oldSize=le(bytes.data()+offset+8,8);
    std::vector<uint8_t> index(bytes.begin()+offset+16,bytes.begin()+offset+16+oldSize);
    const auto count=le(index.data(),4);for(int i=0;i<4;++i)index[i]=uint8_t((count+1)>>(8*i));
    bytes.resize(size_t(offset));const std::vector<uint8_t> payload={'f','u','t','u','r','e',0,255,44};
    auto chunk=[&](const char*name,uint32_t flags,const std::vector<uint8_t>&p){bytes.insert(bytes.end(),name,name+4);put(bytes,flags,4);put(bytes,p.size(),8);bytes.insert(bytes.end(),p.begin(),p.end());put(bytes,craw::detail::crc32c(p.data(),p.size()),4);};
    chunk("FUTR",0x82a50001,payload);index.insert(index.end(),{'F','U','T','R'});put(index,0,4);put(index,offset,8);put(index,987654321,8);
    const auto ix=bytes.size();chunk("INDX",0,index);put(bytes,ix,8);const char magic[8]={'C','R','A','W','E','N','D',0};bytes.insert(bytes.end(),magic,magic+8);
    std::ofstream f(path,std::ios::binary|std::ios::trunc);f.write(reinterpret_cast<const char*>(bytes.data()),bytes.size());
}
static crs::compact::Report run(crs::compact::Session&session){for(int n=0;n<10000;++n){const auto p=session.step();if(p.done)return p.report;}throw std::runtime_error("Conversion did not finish");}
int main(){
    char root[]="/tmp/crs-compact-test-XXXXXX";check(mkdtemp(root)!=nullptr,"temp directory");const fs::path dir=root;
    try{
        int cases=0;
        for(int bits:{10,12,14,16})for(int phase=0;phase<5;++phase){
            const auto input=dir/("source-"+std::to_string(cases)+".craw"),output=dir/("compact-"+std::to_string(cases)+".craw");
            {
                craw::Writer writer(input.string(),"{\"sensor\":{\"uniqueModel\":\"Fixture\"},\"preserve\":\"exact JSON bytes\",\"fps\":30}",2);
                craw::FrameDesc d;d.width=64;d.height=48;d.bitDepth=uint8_t(bits);d.cfa=static_cast<craw::Cfa>(phase);d.tile=32;
                std::vector<uint16_t> raw(d.width*d.height);
                for(int frame=0;frame<4;++frame){d.codec=uint8_t(phase==4?2+(frame%3):1+frame);d.frameCrc=frame%2==0;
                    for(size_t i=0;i<raw.size();++i)raw[i]=uint16_t((i*31+(i/d.width)*47+frame*19)&((1u<<bits)-1));
                    if(phase==4)for(unsigned y=1;y<d.height;y+=2)for(unsigned x=1;x<d.width;x+=2)raw[size_t(y)*d.width+x]=0;
                    writer.addFrame(raw.data(),d.width,d,1'000'000LL+frame*33'333'333LL,"{\"iso\":400,\"frame\":"+std::to_string(frame)+"}");
                }
                const int16_t audio[]={1,-2,300,-400,32767,-32768};writer.addAudio(1'000'000,craw::AudioFormat::S16,2,48000,audio,3);
                const craw::ImuSample samples[]={{1'000'000,1.f,2.f,3.f},{2'000'000,-1.f,-2.f,-3.f}};writer.addImu(craw::ImuSensor::Gyroscope,samples,2);
                const float gains[]={1,1.1f,1.2f,1.3f,1.4f,1.5f,1.6f,1.7f,1,1,1,1,2,2,2,2};writer.addShading(1'000'000,7,2,2,gains);writer.close();
            }
            injectOpaque(input);const auto originalBytes=read(input);
            const int in=open(input.c_str(),O_RDONLY),out=open(output.c_str(),O_RDWR|O_CREAT|O_EXCL,0600);check(in>=0&&out>=0,"open files");
            auto conversion=crs::compact::convert(in,out,3);const auto report=run(*conversion);check(report.frames==4,"all frames verified");check(report.chunks>=7,"ancillary chunks verified");check(report.outputBytes<=report.sourceBytes,"do not enlarge take");
            auto verification=crs::compact::verify(in,out,2);check(run(*verification).frames==4,"reopen verification");
            bool rejected=false;try{auto bad=crs::compact::convert(in,in,1);}catch(const std::exception&){rejected=true;}check(rejected,"reject overwrite source");
            rejected=false;try{auto bad=crs::compact::verify(in,in,1);}catch(const std::exception&){rejected=true;}check(rejected,"reject self verification");
            rejected=false;try{auto bad=crs::compact::convert(in,out,1);}catch(const std::exception&){rejected=true;}check(rejected,"reject existing destination");
            // Valid CRC but altered audio must fail ancillary-byte comparison.
            {craw::Reader r(output.string());const auto audio=*std::find_if(r.chunks().begin(),r.chunks().end(),[](const auto&c){return std::memcmp(c.fourcc.data(),"AUDI",4)==0;});
                auto original=read(output);auto changed=original;changed[size_t(audio.offset+16+audio.size-1)]^=1;
                const auto crc=craw::detail::crc32c(changed.data()+audio.offset+16,size_t(audio.size));
                for(int k=0;k<4;++k)changed[size_t(audio.offset+16+audio.size+k)]=uint8_t(crc>>(8*k));
                check(pwrite(out,changed.data(),changed.size(),0)==ssize_t(changed.size()),"write changed audio");
                rejected=false;try{auto bad=crs::compact::verify(in,out,1);run(*bad);}catch(const std::exception&){rejected=true;}check(rejected,"reject changed audio even with valid checksum");
                check(pwrite(out,original.data(),original.size(),0)==ssize_t(original.size()),"restore audio fixture");
            }
            const auto b=read(output);auto info=craw::Reader(output.string());const auto c=*std::find_if(info.chunks().begin(),info.chunks().end(),[](const auto&v){return std::memcmp(v.fourcc.data(),"FRAM",4)==0;});
            uint8_t byte=b[c.offset+16+25]^1;check(pwrite(out,&byte,1,off_t(c.offset+16+25))==1,"corrupt fixture");
            rejected=false;try{auto bad=crs::compact::verify(in,out,1);run(*bad);}catch(const std::exception&){rejected=true;}check(rejected,"reject corrupt output");
            check(read(input)==originalBytes,"source remains byte identical");close(out);close(in);++cases;
        }
        // Different but valid RAW samples must never authorize original removal.
        const auto left=dir/"pixel-original.craw",right=dir/"pixel-changed.craw";
        for(int side=0;side<2;++side) {
            craw::Writer writer((side?right:left).string(),"{\"fps\":30}");craw::FrameDesc d;d.width=32;d.height=32;d.bitDepth=12;d.codec=side?4:3;
            std::vector<uint16_t> pixels(1024,512);pixels[17]+=side;writer.addFrame(pixels.data(),32,d,1000);writer.close();
        }
        {int a=open(left.c_str(),O_RDONLY),b=open(right.c_str(),O_RDONLY);bool rejected=false;
            try{auto bad=crs::compact::verify(a,b,1);run(*bad);}catch(const std::exception&e){rejected=std::strstr(e.what(),"RAW samples")!=nullptr;}
            check(rejected,"reject different RAW with valid CRC");close(a);close(b);}
        const auto recovered=dir/"recovered.craw",temporary=dir/"recovered-output.craw";fs::copy_file(left,recovered);fs::resize_file(recovered,fs::file_size(recovered)-16);
        {int a=open(recovered.c_str(),O_RDONLY),b=open(temporary.c_str(),O_RDWR|O_CREAT|O_EXCL,0600);bool rejected=false;
            try{auto bad=crs::compact::convert(a,b,1);}catch(const std::exception&){rejected=true;}check(rejected,"reject incomplete source");check(fs::file_size(temporary)==0,"do not write on recovery rejection");close(a);close(b);}
        std::printf("PASS: %d lossless Compact cases (Bayer phases + RGB, 10/12/14/16-bit, codecs 1–4, audio/IMU/shading/opaque chunks, valid-CRC sample/audio mismatch, corruption, incomplete-source and overwrite rejection)\n",cases);
        fs::remove_all(dir);return 0;
    }catch(const std::exception&e){std::fprintf(stderr,"FAIL: %s\n",e.what());fs::remove_all(dir);return 1;}
}
