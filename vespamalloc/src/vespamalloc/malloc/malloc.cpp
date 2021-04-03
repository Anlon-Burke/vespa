// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespamalloc/malloc/malloc.h>
#include <vespamalloc/malloc/memorywatcher.h>
#include <vespamalloc/malloc/memblock.h>
#include <vespamalloc/malloc/stat.h>
#include <vespamalloc/malloc/threadpool.hpp>

namespace vespamalloc {

typedef ThreadListT<MemBlock, NoStat> ThreadList;
typedef MemoryWatcher<MemBlock, ThreadList> Allocator;

static char _Gmem[sizeof(Allocator)];
static Allocator *_GmemP = NULL;

static Allocator * createAllocator()
{
    if (_GmemP == NULL) {
        _GmemP = (Allocator *)1;
        _GmemP = new (_Gmem) Allocator(-1, 0x7fffffffffffffffl);
    }
    return _GmemP;
}

template <>
void MemBlock::
dumpInfo(size_t level)
{
    _GmemP->info(_logFile, level);
}

}

#include <vespamalloc/malloc/overload.h>
