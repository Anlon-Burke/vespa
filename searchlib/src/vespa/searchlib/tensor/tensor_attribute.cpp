// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "tensor_attribute.h"
#include "blob_sequence_reader.h"
#include "tensor_store_saver.h"
#include <vespa/document/base/exceptions.h>
#include <vespa/document/datatype/tensor_data_type.h>
#include <vespa/searchlib/attribute/address_space_components.h>
#include <vespa/searchlib/util/state_explorer_utils.h>
#include <vespa/searchcommon/attribute/config.h>
#include <vespa/vespalib/data/slime/cursor.h>
#include <vespa/vespalib/data/slime/inserter.h>
#include <vespa/vespalib/datastore/i_compaction_context.h>
#include <vespa/vespalib/util/shared_string_repo.h>
#include <vespa/eval/eval/fast_value.h>
#include <vespa/eval/eval/value_codec.h>
#include <vespa/eval/eval/tensor_spec.h>
#include <vespa/eval/eval/value.h>

using document::TensorDataType;
using document::TensorUpdate;
using document::WrongTensorTypeException;
using search::AddressSpaceComponents;
using search::StateExplorerUtils;
using vespalib::eval::FastValueBuilderFactory;
using vespalib::eval::TensorSpec;
using vespalib::eval::Value;
using vespalib::eval::ValueType;

namespace search::tensor {

namespace {

constexpr uint32_t TENSOR_ATTRIBUTE_VERSION = 0;

Value::UP
createEmptyTensor(const ValueType &type)
{
    const auto &factory = FastValueBuilderFactory::get();
    TensorSpec empty_spec(type.to_spec());
    return vespalib::eval::value_from_spec(empty_spec, factory);
}

vespalib::string makeWrongTensorTypeMsg(const ValueType &fieldTensorType, const ValueType &tensorType)
{
    return vespalib::make_string("Field tensor type is '%s' but other tensor type is '%s'",
                                 fieldTensorType.to_spec().c_str(),
                                 tensorType.to_spec().c_str());
}

}

TensorAttribute::TensorAttribute(vespalib::stringref name, const Config &cfg, TensorStore &tensorStore)
    : NotImplementedAttribute(name, cfg),
      _refVector(cfg.getGrowStrategy(), getGenerationHolder()),
      _tensorStore(tensorStore),
      _is_dense(cfg.tensorType().is_dense()),
      _emptyTensor(createEmptyTensor(cfg.tensorType())),
      _compactGeneration(0)
{
}

TensorAttribute::~TensorAttribute() = default;

const ITensorAttribute *
TensorAttribute::asTensorAttribute() const
{
    return this;
}

uint32_t
TensorAttribute::clearDoc(DocId docId)
{
    EntryRef oldRef(_refVector[docId].load_relaxed());
    updateUncommittedDocIdLimit(docId);
    _refVector[docId] = AtomicEntryRef();
    if (oldRef.valid()) {
        _tensorStore.holdTensor(oldRef);
        return 1u;
    }
    return 0u;
}

void
TensorAttribute::onCommit()
{
    // Note: Cost can be reduced if unneeded generation increments are dropped
    incGeneration();
    if (_tensorStore.consider_compact()) {
        auto context = _tensorStore.start_compact(getConfig().getCompactionStrategy());
        if (context) {
            context->compact(vespalib::ArrayRef<AtomicEntryRef>(&_refVector[0], _refVector.size()));
        }
        _compactGeneration = getCurrentGeneration();
        incGeneration();
        updateStat(true);
    }
}

void
TensorAttribute::onUpdateStat()
{
    vespalib::MemoryUsage total = update_stat();
    this->updateStatistics(_refVector.size(),
                           _refVector.size(),
                           total.allocatedBytes(),
                           total.usedBytes(),
                           total.deadBytes(),
                           total.allocatedBytesOnHold());
}

void
TensorAttribute::reclaim_memory(generation_t oldest_used_gen)
{
    _tensorStore.reclaim_memory(oldest_used_gen);
    getGenerationHolder().reclaim(oldest_used_gen);
}

void
TensorAttribute::before_inc_generation(generation_t current_gen)
{
    getGenerationHolder().assign_generation(current_gen);
    _tensorStore.assign_generation(current_gen);
}

bool
TensorAttribute::addDoc(DocId &docId)
{
    bool incGen = _refVector.isFull();
    _refVector.push_back(AtomicEntryRef());
    AttributeVector::incNumDocs();
    docId = AttributeVector::getNumDocs() - 1;
    updateUncommittedDocIdLimit(docId);
    if (incGen) {
        incGeneration();
    } else {
        reclaim_unused_memory();
    }
    return true;
}

void
TensorAttribute::checkTensorType(const vespalib::eval::Value &tensor) const
{
    const ValueType &fieldTensorType = getConfig().tensorType();
    const ValueType &tensorType = tensor.type();
    if (!TensorDataType::isAssignableType(fieldTensorType, tensorType)) {
        throw WrongTensorTypeException(makeWrongTensorTypeMsg(fieldTensorType, tensorType), VESPA_STRLOC);
    }
}

void
TensorAttribute::setTensorRef(DocId docId, EntryRef ref)
{
    assert(docId < _refVector.size());
    updateUncommittedDocIdLimit(docId);
    // TODO: validate if following fence is sufficient.
    std::atomic_thread_fence(std::memory_order_release);
    // TODO: Check if refVector must consist of std::atomic<EntryRef>
    EntryRef oldRef(_refVector[docId].load_relaxed());
    _refVector[docId].store_release(ref);
    if (oldRef.valid()) {
        _tensorStore.holdTensor(oldRef);
    }
}

vespalib::MemoryUsage
TensorAttribute::update_stat()
{
    vespalib::MemoryUsage result = _refVector.getMemoryUsage();
    result.merge(_tensorStore.update_stat(getConfig().getCompactionStrategy()));
    result.mergeGenerationHeldBytes(getGenerationHolder().get_held_bytes());
    return result;
}

vespalib::MemoryUsage
TensorAttribute::memory_usage() const
{
    vespalib::MemoryUsage result = _refVector.getMemoryUsage();
    result.merge(_tensorStore.getMemoryUsage());
    result.mergeGenerationHeldBytes(getGenerationHolder().get_held_bytes());
    return result;
}

void
TensorAttribute::populate_state(vespalib::slime::Cursor& object) const
{
    object.setLong("compact_generation", _compactGeneration);
    StateExplorerUtils::memory_usage_to_slime(_refVector.getMemoryUsage(),
                                              object.setObject("ref_vector").setObject("memory_usage"));
    StateExplorerUtils::memory_usage_to_slime(_tensorStore.getMemoryUsage(),
                                              object.setObject("tensor_store").setObject("memory_usage"));
}

void
TensorAttribute::populate_address_space_usage(AddressSpaceUsage& usage) const
{
    usage.set(AddressSpaceComponents::tensor_store, _tensorStore.get_address_space_usage());
    if (!_is_dense) {
        auto stats = vespalib::SharedStringRepo::stats();
        usage.set(AddressSpaceComponents::shared_string_repo,
                  vespalib::AddressSpace(stats.max_part_usage, 0, stats.part_limit()));
    }
}

vespalib::eval::Value::UP
TensorAttribute::getEmptyTensor() const
{
    return FastValueBuilderFactory::get().copy(*_emptyTensor);
}

vespalib::eval::TypedCells
TensorAttribute::extract_cells_ref(uint32_t /*docid*/) const
{
    notImplemented();
}

const vespalib::eval::Value&
TensorAttribute::get_tensor_ref(uint32_t /*docid*/) const
{
    notImplemented();
}

const vespalib::eval::ValueType &
TensorAttribute::getTensorType() const
{
    return getConfig().tensorType();
}

void
TensorAttribute::get_state(const vespalib::slime::Inserter& inserter) const
{
    auto& object = inserter.insertObject();
    populate_state(object);
}

void
TensorAttribute::clearDocs(DocId lidLow, DocId lidLimit, bool)
{
    assert(lidLow <= lidLimit);
    assert(lidLimit <= this->getNumDocs());
    for (DocId lid = lidLow; lid < lidLimit; ++lid) {
        AtomicEntryRef& atomic_ref = _refVector[lid];
        EntryRef ref = atomic_ref.load_relaxed();
        if (ref.valid()) {
            _tensorStore.holdTensor(ref);
            atomic_ref.store_release(EntryRef());
        }
    }
}

void
TensorAttribute::onShrinkLidSpace()
{
    // Tensors for lids > committedDocIdLimit have been cleared.
    uint32_t committedDocIdLimit = getCommittedDocIdLimit();
    assert(_refVector.size() >= committedDocIdLimit);
    _refVector.shrink(committedDocIdLimit);
    setNumDocs(committedDocIdLimit);
}

uint32_t
TensorAttribute::getVersion() const
{
    return TENSOR_ATTRIBUTE_VERSION;
}

TensorAttribute::RefCopyVector
TensorAttribute::getRefCopy() const
{
    uint32_t size = getCommittedDocIdLimit();
    assert(size <= _refVector.get_size());          // Called from writer only
    auto* ref_vector = &_refVector.get_elem_ref(0); // Called from writer only
    RefCopyVector result;
    result.reserve(size);
    for (uint32_t i = 0; i < size; ++i) {
        result.push_back(ref_vector[i].load_relaxed());
    }
    return result;
}

bool
TensorAttribute::onLoad(vespalib::Executor*)
{
    BlobSequenceReader tensorReader(*this);
    if (!tensorReader.hasData()) {
        return false;
    }
    setCreateSerialNum(tensorReader.getCreateSerialNum());
    assert(tensorReader.getVersion() == getVersion());
    uint32_t numDocs = tensorReader.getDocIdLimit();
    _refVector.reset();
    _refVector.unsafe_reserve(numDocs);
    vespalib::Array<char> buffer(1024);
    for (uint32_t lid = 0; lid < numDocs; ++lid) {
        uint32_t tensorSize = tensorReader.getNextSize();
        if (tensorSize != 0) {
            if (tensorSize > buffer.size()) {
                buffer.resize(tensorSize + 1024);
            }
            tensorReader.readBlob(&buffer[0], tensorSize);
            vespalib::nbostream source(&buffer[0], tensorSize);
            EntryRef ref = _tensorStore.store_encoded_tensor(source);
            _refVector.push_back(AtomicEntryRef(ref));
        } else {
            EntryRef invalid;
            _refVector.push_back(AtomicEntryRef(invalid));
        }
    }
    setNumDocs(numDocs);
    setCommittedDocIdLimit(numDocs);
    return true;
}

std::unique_ptr<AttributeSaver>
TensorAttribute::onInitSave(vespalib::stringref fileName)
{
    vespalib::GenerationHandler::Guard guard(getGenerationHandler().
                                             takeGuard());
    return std::make_unique<TensorStoreSaver>
        (std::move(guard),
         this->createAttributeHeader(fileName),
         getRefCopy(),
         _tensorStore);
}

void
TensorAttribute::update_tensor(DocId docId,
                               const document::TensorUpdate &update,
                               bool create_empty_if_non_existing)
{
    const vespalib::eval::Value * old_v = nullptr;
    auto old_tensor = getTensor(docId);
    if (old_tensor) {
        old_v = old_tensor.get();
    } else if (create_empty_if_non_existing) {
        old_v = _emptyTensor.get();
    } else {
        return;
    }
    auto new_value = update.apply_to(*old_v, FastValueBuilderFactory::get());
    if (new_value) {
        setTensor(docId, *new_value);
    }
}

std::unique_ptr<PrepareResult>
TensorAttribute::prepare_set_tensor(DocId docid, const vespalib::eval::Value& tensor) const
{
    (void) docid;
    (void) tensor;
    return std::unique_ptr<PrepareResult>();
}

void
TensorAttribute::complete_set_tensor(DocId docid, const vespalib::eval::Value& tensor,
                                     std::unique_ptr<PrepareResult> prepare_result)
{
    (void) docid;
    (void) tensor;
    (void) prepare_result;
}

attribute::DistanceMetric
TensorAttribute::distance_metric() const {
    return getConfig().distance_metric();
}

}
