// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "job_tracked_flush_target.h"
#include "job_tracked_flush_task.h"

using searchcorespi::IFlushTarget;
using searchcorespi::FlushTask;

namespace proton {

JobTrackedFlushTarget::JobTrackedFlushTarget(const IJobTracker::SP &tracker,
                                             const IFlushTarget::SP &target)
    : IFlushTarget(target->getName(), target->getType(), target->getComponent()),
      _tracker(tracker),
      _target(target)
{
}

JobTrackedFlushTarget::~JobTrackedFlushTarget() {}

FlushTask::UP
JobTrackedFlushTarget::initFlush(SerialNum currentSerial, std::shared_ptr<search::IFlushToken> flush_token)
{
    _tracker->start();
    FlushTask::UP targetTask = _target->initFlush(currentSerial, std::move(flush_token));
    _tracker->end();
    if (targetTask.get() != nullptr) {
        return std::make_unique<JobTrackedFlushTask>(_tracker, std::move(targetTask));
    }
    return FlushTask::UP();
}

uint64_t
JobTrackedFlushTarget::getApproxBytesToWriteToDisk() const
{
    return _target->getApproxBytesToWriteToDisk();
}

} // namespace proton
