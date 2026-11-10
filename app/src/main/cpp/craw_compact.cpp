// SPDX-License-Identifier: Apache-2.0
// Container algorithm adapted from CinematicRAWStudio-Decoder/tools/craw_compact.cpp.
// Codec implementation remains the vendored upstream encoder/decoder, unchanged.
#include "craw_compact.h"
#include <craw/Reader.h>
#include <craw/Encoder.h>
#include "Crc32c.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
namespace crs::compact { namespace {
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
class Fd {
    int value_;
public:
    explicit Fd(int fd):value_(dup(fd)){require(value_>=0,"Cannot retain CRAW file descriptor");}
    ~Fd(){if(value_>=0)close(value_);}
    int get()const{return value_;}
};
struct stat stamp(int fd){struct stat s{};require(fstat(fd,&s)==0&&S_ISREG(s.st_mode),"CRAW conversion requires a seekable file");return s;}
bool sameFile(const struct stat&a,const struct stat&b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino;}
bool sameStamp(const struct stat&a,const struct stat&b){
#if defined(__APPLE__)
    return sameFile(a,b)&&a.st_size==b.st_size&&a.st_mtimespec.tv_sec==b.st_mtimespec.tv_sec&&a.st_mtimespec.tv_nsec==b.st_mtimespec.tv_nsec;
#else
    return sameFile(a,b)&&a.st_size==b.st_size&&a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec;
#endif
}
void readAt(int fd,uint64_t offset,void* output,size_t bytes){
    auto* p=static_cast<uint8_t*>(output);
    while(bytes){const auto n=pread(fd,p,bytes,off_t(offset));if(n<0&&errno==EINTR)continue;require(n>0,"Cannot read CRAW container");p+=n;bytes-=size_t(n);offset+=uint64_t(n);}
}
uint64_t le(const uint8_t*p,int count){uint64_t n=0;for(int i=0;i<count;++i)n|=uint64_t(p[i])<<(8*i);return n;}
void put(std::vector<uint8_t>& out,uint64_t n,int bytes){for(int i=0;i<bytes;++i)out.push_back(uint8_t(n>>(i*8)));}
bool tag(const craw::ChunkRef&c,const char* value){return std::memcmp(c.fourcc.data(),value,4)==0;}
uint64_t checkHeader(int fd,const craw::Reader& reader){
    require(!reader.recovered(),"Take is incomplete/recovered; repair it before compacting");
    require(reader.frameCount()>0,"Take has no frames");
    const auto s=stamp(fd);require(s.st_size>=52,"Incomplete CRAW container");
    char magic[8];readAt(fd,uint64_t(s.st_size)-8,magic,8);require(std::memcmp(magic,"CRAWEND\0",8)==0,"Take has no complete trailer");
    uint8_t header[16];readAt(fd,16,header,16);require(std::memcmp(header,"META",4)==0,"Missing CRAW metadata");
    const uint64_t bytes=le(header+8,8);require(bytes<=uint64_t(s.st_size)-36,"Invalid CRAW metadata length");
    craw::ChunkRef meta{{'M','E','T','A'},16,bytes,0};require(reader.crcOk(meta),"Metadata checksum failed");
    for(const auto&c:reader.chunks()){
        require(!tag(c,"META"),"Take index requires repair before compaction");
        if(tag(c,"INDX"))require(reader.crcOk(c),"Take index checksum failed");
    }
    return 36+bytes;
}
void equalBytes(int a,int b,uint64_t aOffset,uint64_t bOffset,uint64_t bytes){
    std::array<uint8_t,65536> x{},y{};
    while(bytes){const size_t n=size_t(std::min<uint64_t>(bytes,x.size()));readAt(a,aOffset,x.data(),n);readAt(b,bOffset,y.data(),n);
        require(std::memcmp(x.data(),y.data(),n)==0,"Container metadata or ancillary data changed");bytes-=n;aOffset+=n;bOffset+=n;}
}
class Verifier final:public Session {
    Fd source_,target_;
    craw::Reader a_,b_;
    struct stat sourceStamp_,targetStamp_;
    std::vector<craw::ChunkRef> left_,right_;
    size_t cursor_=0,frame_=0;
    unsigned threads_;
    Report report_;
public:
    Verifier(int source,int target,unsigned threads):source_(source),target_(target),a_(source_.get(),"original CRAW"),b_(target_.get(),"compacted CRAW"),
        sourceStamp_(stamp(source_.get())),targetStamp_(stamp(target_.get())),threads_(std::max(1u,threads)) {
        require(!sameFile(sourceStamp_,targetStamp_),"Original and Compact output must be different files");
        const auto aPrefix=checkHeader(source_.get(),a_),bPrefix=checkHeader(target_.get(),b_);
        require(aPrefix==bPrefix,"CRAW metadata length changed");equalBytes(source_.get(),target_.get(),0,0,aPrefix);
        require(a_.frameCount()==b_.frameCount(),"Frame count changed during conversion");
        for(const auto&c:a_.chunks())if(!tag(c,"INDX"))left_.push_back(c);
        for(const auto&c:b_.chunks())if(!tag(c,"INDX"))right_.push_back(c);
        require(left_.size()==right_.size(),"Container chunks were lost during conversion");
        report_.sourceBytes=uint64_t(sourceStamp_.st_size);report_.outputBytes=uint64_t(targetStamp_.st_size);
        const auto first=a_.frame(0);report_.sourceCodec=craw::probeFrame(first.payload,first.payloadSize).codec;
    }
    Progress step()override{
        if(cursor_==left_.size()){
            require(frame_==a_.frameCount(),"Not all frames were verified");
            require(sameStamp(sourceStamp_,stamp(source_.get()))&&sameStamp(targetStamp_,stamp(target_.get())),"A take changed while verification was running");
            return {true,true,1.0,report_};
        }
        const auto&x=left_[cursor_];const auto&y=right_[cursor_];
        require(x.fourcc==y.fourcc&&x.timestampNs==y.timestampNs,"Chunk type/order/timestamp changed");
        require(a_.crcOk(x)&&b_.crcOk(y),"Container checksum failed during verification");
        equalBytes(source_.get(),target_.get(),x.offset,y.offset,8); // FourCC and opaque chunk flags.
        if(tag(x,"FRAM")){
            const auto a=a_.frame(frame_),b=b_.frame(frame_);
            const auto ap=a_.payload(x),bp=b_.payload(y);
            const auto prefix=size_t(reinterpret_cast<const char*>(a.payload)-ap.data());
            require(prefix==size_t(reinterpret_cast<const char*>(b.payload)-bp.data()),"Frame metadata length changed");
            require(std::memcmp(ap.data(),bp.data(),prefix)==0,"Frame metadata/index/timestamp changed");
            std::vector<uint16_t> raw,back;
            const auto ai=craw::decodeFrame(a.payload,a.payloadSize,raw,threads_);
            const auto bi=craw::decodeFrame(b.payload,b.payloadSize,back,threads_);
            require(ai.width==bi.width&&ai.height==bi.height&&ai.cfa==bi.cfa&&ai.bitDepth==bi.bitDepth&&raw==back,"Compacted RAW samples do not match original");
            if(bi.codec!=4)require(a.payloadSize==b.payloadSize&&std::memcmp(a.payload,b.payload,a.payloadSize)==0,"Unexpected non-Compact frame");
            report_.compactFrames+=bi.codec==4;report_.retainedFrames+=a.payloadSize==b.payloadSize&&std::memcmp(a.payload,b.payload,a.payloadSize)==0;
            ++frame_;report_.frames=frame_;
        }else{
            require(x.size==y.size,"Ancillary chunk size changed");equalBytes(source_.get(),target_.get(),x.offset+16,y.offset+16,x.size+4);
        }
        ++cursor_;report_.chunks=cursor_;return {false,true,double(cursor_)/std::max<size_t>(1,left_.size()),report_};
    }
};
class Converter final:public Session {
    Fd source_,target_;
    craw::Reader reader_;
    struct stat sourceStamp_;
    uint64_t pos_=0;
    size_t cursor_=0,frame_=0;
    uint32_t count_=0;
    unsigned threads_;
    std::vector<uint8_t> index_,encoded_;
    std::vector<uint16_t> raw_,back_;
    std::unique_ptr<Verifier> verifier_;
    void write(const void* p,size_t size){const auto* bytes=static_cast<const uint8_t*>(p);while(size){const auto n=pwrite(target_.get(),bytes,size,off_t(pos_));if(n<0&&errno==EINTR)continue;require(n>0,"Cannot write Compact output (check free space)");bytes+=n;size-=size_t(n);pos_+=uint64_t(n);}}
    void copy(uint64_t offset,uint64_t bytes){std::array<uint8_t,65536> buffer{};while(bytes){const size_t n=size_t(std::min<uint64_t>(bytes,buffer.size()));readAt(source_.get(),offset,buffer.data(),n);write(buffer.data(),n);offset+=n;bytes-=n;}}
    void chunk(const char* name,uint32_t flags,const std::vector<uint8_t>& payload){std::vector<uint8_t> h(name,name+4);put(h,flags,4);put(h,payload.size(),8);write(h.data(),h.size());write(payload.data(),payload.size());std::vector<uint8_t> crc;put(crc,craw::detail::crc32c(payload.data(),payload.size()),4);write(crc.data(),crc.size());}
public:
    Converter(int source,int target,unsigned threads):source_(source),target_(target),reader_(source_.get(),"CRAW Compact source"),sourceStamp_(stamp(source_.get())),threads_(std::max(1u,threads)) {
        const auto out=stamp(target_.get());require(!sameFile(sourceStamp_,out),"Cannot overwrite the original take");require(out.st_size==0,"Compact destination must be a new empty file");
        copy(0,checkHeader(source_.get(),reader_));
    }
    Progress step()override{
        if(verifier_){auto p=verifier_->step();p.fraction=.75+p.fraction*.25;return p;}
        if(cursor_==reader_.chunks().size()){
            require(frame_==reader_.frameCount(),"Not all source frames were compacted");
            std::vector<uint8_t> payload;put(payload,count_,4);payload.insert(payload.end(),index_.begin(),index_.end());const auto offset=pos_;chunk("INDX",0,payload);
            std::vector<uint8_t> trailer;put(trailer,offset,8);const char magic[8]={'C','R','A','W','E','N','D',0};trailer.insert(trailer.end(),magic,magic+8);write(trailer.data(),trailer.size());
            require(fsync(target_.get())==0,"Cannot flush the Compact output");require(sameStamp(sourceStamp_,stamp(source_.get())),"Original changed while compacting");
            verifier_=std::make_unique<Verifier>(source_.get(),target_.get(),threads_);return {false,true,.75,{}};
        }
        const auto&c=reader_.chunks()[cursor_++];if(tag(c,"INDX"))return {false,false,.75*double(cursor_)/reader_.chunks().size(),{}};
        uint8_t header[16];readAt(source_.get(),c.offset,header,16);require(std::memcmp(header,c.fourcc.data(),4)==0&&le(header+8,8)==c.size,"Chunk header disagrees with source index");
        require(reader_.crcOk(c),"Source checksum failed");
        require(count_!=std::numeric_limits<uint32_t>::max(),"Too many source chunks");index_.insert(index_.end(),c.fourcc.begin(),c.fourcc.end());put(index_,0,4);put(index_,pos_,8);put(index_,uint64_t(c.timestampNs),8);++count_;
        if(!tag(c,"FRAM")){copy(c.offset,c.size+20);return {false,false,.75*double(cursor_)/reader_.chunks().size(),{}};}
        const auto record=reader_.frame(frame_++);const auto info=craw::decodeFrame(record.payload,record.payloadSize,raw_,threads_);
        bool smaller=false;
        if(info.codec!=4){craw::FrameDesc d;d.width=info.width;d.height=info.height;d.bitDepth=info.bitDepth;d.cfa=info.cfa;d.tile=info.tile;d.riceInit=info.riceInit;d.codec=4;d.frameCrc=true;
            craw::encodeFrame(raw_.data(),d.width,d,encoded_,threads_);craw::decodeFrame(encoded_.data(),encoded_.size(),back_,threads_);require(raw_==back_,"Compact round trip mismatch");smaller=encoded_.size()<record.payloadSize;}
        const auto original=reader_.payload(c);const auto prefix=size_t(reinterpret_cast<const char*>(record.payload)-original.data());std::vector<uint8_t> payload(original.begin(),original.begin()+prefix);
        if(smaller)payload.insert(payload.end(),encoded_.begin(),encoded_.end());else payload.insert(payload.end(),record.payload,record.payload+record.payloadSize);
        chunk("FRAM",uint32_t(le(header+4,4)),payload);
        return {false,false,.75*double(cursor_)/reader_.chunks().size(),{}};
    }
};
} // namespace
std::unique_ptr<Session> convert(int source,int target,unsigned threads){return std::make_unique<Converter>(source,target,threads);}
std::unique_ptr<Session> verify(int source,int target,unsigned threads){return std::make_unique<Verifier>(source,target,threads);}
std::string json(const Progress&p){const auto&r=p.report;return "{\"done\":"+std::string(p.done?"true":"false")+",\"verifying\":"+(p.verifying?"true":"false")+",\"progress\":"+std::to_string(p.fraction)+",\"frames\":"+std::to_string(r.frames)+",\"compactFrames\":"+std::to_string(r.compactFrames)+",\"retainedFrames\":"+std::to_string(r.retainedFrames)+",\"chunks\":"+std::to_string(r.chunks)+",\"sourceBytes\":"+std::to_string(r.sourceBytes)+",\"outputBytes\":"+std::to_string(r.outputBytes)+",\"sourceCodec\":"+std::to_string(r.sourceCodec)+"}";}
} // namespace crs::compact
