// SPDX-License-Identifier: Apache-2.0
// Android FD/session adapter for the upstream craw_compact lossless container algorithm.
#pragma once
#include <cstdint>
#include <memory>
#include <string>
namespace crs::compact {
struct Report {
    uint64_t frames=0,compactFrames=0,retainedFrames=0,chunks=0,sourceBytes=0,outputBytes=0;
    int sourceCodec=0;
};
struct Progress {bool done=false,verifying=false;double fraction=0;Report report;};
class Session {
public:
    virtual ~Session()=default;
    virtual Progress step()=0;
};
std::unique_ptr<Session> convert(int sourceFd,int outputFd,unsigned threads);
std::unique_ptr<Session> verify(int sourceFd,int compactFd,unsigned threads);
std::string json(const Progress& progress);
}
