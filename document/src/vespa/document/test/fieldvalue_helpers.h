// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/document/fieldvalue/collectionfieldvalue.h>
#include <vespa/document/fieldvalue/weightedsetfieldvalue.h>

namespace document {
    
class CollectionHelper {
public:
    CollectionHelper(CollectionFieldValue &value) : _cfv(value) {}

    // Convenience functions for using primitives directly
    bool add(vespalib::stringref val) { return _cfv.add(*_cfv.createNested() = val); }
    bool add(int32_t val) { return _cfv.add(*_cfv.createNested() = val); }
    bool add(int64_t val) { return _cfv.add(*_cfv.createNested() = val); }
    bool add(float val) { return _cfv.add(*_cfv.createNested() = val); }
    bool add(double val) { return _cfv.add(*_cfv.createNested() = val); }

    bool contains(vespalib::stringref val) { return _cfv.contains(*_cfv.createNested() = val); }
    bool contains(int32_t val) { return _cfv.contains(*_cfv.createNested() = val); }
    bool contains(int64_t val) { return _cfv.contains(*_cfv.createNested() = val); }
    bool contains(float val) { return _cfv.contains(*_cfv.createNested() = val); }
    bool contains(double val) { return _cfv.contains(*_cfv.createNested() = val); }

    bool remove(vespalib::stringref val) { return _cfv.remove(*_cfv.createNested() = val); }
    bool remove(int32_t val) { return _cfv.remove(*_cfv.createNested() = val); }
    bool remove(int64_t val) { return _cfv.remove(*_cfv.createNested() = val); }
    bool remove(float val) { return _cfv.remove(*_cfv.createNested() = val); }
    bool remove(double val) { return _cfv.remove(*_cfv.createNested() = val); }
private:
    CollectionFieldValue & _cfv;
};

class WSetHelper {
public:
    WSetHelper(WeightedSetFieldValue & ws) : _ws(ws) { }

    // Utility functions for easy use of weighted sets of primitives

    bool add(vespalib::stringref val, int32_t weight = 1) { return _ws.add(*_ws.createNested() = val, weight); }
    bool add(int32_t val, int32_t weight = 1) { return _ws.add(*_ws.createNested() = val, weight); }
    bool add(int64_t val, int32_t weight = 1) { return _ws.add(*_ws.createNested() = val, weight); }
    bool add(float val, int32_t weight = 1) { return _ws.add(*_ws.createNested() = val, weight); }
    bool add(double val, int32_t weight = 1) { return _ws.add(*_ws.createNested() = val, weight); }

    int32_t get(vespalib::stringref val) const { return _ws.get(*_ws.createNested() = val); }
    int32_t get(int32_t val) const { return _ws.get(*_ws.createNested() = val); }
    int32_t get(int64_t val) const { return _ws.get(*_ws.createNested() = val); }
    int32_t get(float val) const { return _ws.get(*_ws.createNested() = val); }
    int32_t get(double val) const { return _ws.get(*_ws.createNested() = val); }

    void increment(vespalib::stringref val, int32_t weight = 1) { _ws.increment(*_ws.createNested() = val, weight); }
    void increment(int32_t val, int32_t weight = 1) { _ws.increment(*_ws.createNested() = val, weight); }
    void increment(int64_t val, int32_t weight = 1) { _ws.increment(*_ws.createNested() = val, weight); }
    void increment(float val, int32_t weight = 1) { _ws.increment(*_ws.createNested() = val, weight); }
    void increment(double val, int32_t weight = 1) { _ws.increment(*_ws.createNested() = val, weight); }

    void decrement(vespalib::stringref val, int32_t weight = 1) { _ws.decrement(*_ws.createNested() = val, weight); }
    void decrement(int32_t val, int32_t weight = 1) { _ws.decrement(*_ws.createNested() = val, weight); }
    void decrement(int64_t val, int32_t weight = 1) { _ws.decrement(*_ws.createNested() = val, weight); }
    void decrement(float val, int32_t weight = 1) { _ws.decrement(*_ws.createNested() = val, weight); }
    void decrement(double val, int32_t weight = 1) { _ws.decrement(*_ws.createNested() = val, weight); }
private:
    WeightedSetFieldValue & _ws;
};
}
