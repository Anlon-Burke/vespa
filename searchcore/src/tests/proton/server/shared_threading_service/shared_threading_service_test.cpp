// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/searchcore/config/config-proton.h>
#include <vespa/searchcore/proton/server/shared_threading_service.h>
#include <vespa/searchcore/proton/server/shared_threading_service_config.h>
#include <vespa/searchcore/proton/test/transport_helper.h>
#include <vespa/vespalib/util/isequencedtaskexecutor.h>
#include <vespa/vespalib/util/sequencedtaskexecutor.h>
#include <vespa/vespalib/gtest/gtest.h>

using namespace proton;
using vespalib::ISequencedTaskExecutor;
using vespalib::SequencedTaskExecutor;
using ProtonConfig = vespa::config::search::core::ProtonConfig;
using ProtonConfigBuilder = vespa::config::search::core::ProtonConfigBuilder;

ProtonConfig
make_proton_config(double concurrency)
{
    ProtonConfigBuilder builder;
    // This setup requires a minimum of 4 shared threads.
    builder.documentdb.push_back(ProtonConfig::Documentdb());
    builder.documentdb.push_back(ProtonConfig::Documentdb());
    builder.flush.maxconcurrent = 1;

    builder.feeding.concurrency = concurrency;
    builder.feeding.sharedFieldWriterExecutor = ProtonConfig::Feeding::SharedFieldWriterExecutor::DOCUMENT_DB;
    builder.indexing.tasklimit = 255;
    return builder;
}

void
expect_shared_threads(uint32_t exp_threads, uint32_t cpu_cores)
{
    auto cfg = SharedThreadingServiceConfig::make(make_proton_config(0.5), HwInfo::Cpu(cpu_cores));
    EXPECT_EQ(exp_threads, cfg.shared_threads());
    EXPECT_EQ(exp_threads * 16, cfg.shared_task_limit());
}

TEST(SharedThreadingServiceConfigTest, shared_threads_are_derived_from_cpu_cores_and_feeding_concurrency)
{
    expect_shared_threads(4, 1);
    expect_shared_threads(4, 6);
    expect_shared_threads(4, 8);
    expect_shared_threads(5, 9);
    expect_shared_threads(5, 10);
}

class SharedThreadingServiceTest : public ::testing::Test {
public:
    Transport transport;
    std::unique_ptr<SharedThreadingService> service;
    SharedThreadingServiceTest()
        : transport(),
          service()
    { }
    ~SharedThreadingServiceTest() = default;
    void setup(double concurrency, uint32_t cpu_cores) {
        service = std::make_unique<SharedThreadingService>(
                SharedThreadingServiceConfig::make(make_proton_config(concurrency), HwInfo::Cpu(cpu_cores)), transport.transport());
    }
    SequencedTaskExecutor* field_writer() {
        return dynamic_cast<SequencedTaskExecutor*>(service->field_writer());
    }
};

void
assert_executor(SequencedTaskExecutor* exec, uint32_t exp_executors, uint32_t exp_task_limit)
{
    EXPECT_EQ(exp_executors, exec->getNumExecutors());
    EXPECT_EQ(exp_task_limit, exec->first_executor()->getTaskLimit());
}

TEST_F(SharedThreadingServiceTest, field_writer_can_be_shared_across_all_document_dbs)
{
    setup(0.75, 8);
    EXPECT_TRUE(field_writer());
    EXPECT_EQ(6, field_writer()->getNumExecutors());
    // This is rounded to the nearest power of 2 when using THROUGHPUT feed executor.
    EXPECT_EQ(256, field_writer()->first_executor()->getTaskLimit());
}

GTEST_MAIN_RUN_ALL_TESTS()
