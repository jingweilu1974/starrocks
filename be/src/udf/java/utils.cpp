// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

#include "udf/java/utils.h"

#include <bthread/bthread.h>

#include <memory>

#include "common/compiler_util.h"
#include "exec/workgroup/scan_executor.h"
#include "exec/workgroup/scan_task_queue.h"
#include "runtime/current_thread.h"
#include "runtime/exec_env.h"
#include "runtime/runtime_state.h"
#include "util/priority_thread_pool.hpp"

namespace starrocks {
PromiseStatusPtr call_function_in_pthread(RuntimeState* state, const std::function<Status()>& func) {
    PromiseStatusPtr ms = std::make_unique<PromiseStatus>();
    if (bthread_self()) {
        state->exec_env()->udf_call_pool()->offer([promise = ms.get(), state, func]() {
            Status st;
            {
                MemTracker* prev_tracker = tls_thread_status.set_mem_tracker(state->instance_mem_tracker());
                SCOPED_SET_TRACE_INFO({}, state->query_id(), state->fragment_instance_id());
                DeferOp op([&] { tls_thread_status.set_mem_tracker(prev_tracker); });
                st = func();
            }
            promise->set_value(st);
        });
    } else {
        ms->set_value(func());
    }
    return ms;
}

PromiseStatusPtr call_hdfs_scan_function_in_pthread(const std::function<Status()>& func) {
    PromiseStatusPtr ms = std::make_unique<PromiseStatus>();
    if (bthread_self()) {
<<<<<<< HEAD
        ExecEnv::GetInstance()->connector_scan_executor_without_workgroup()->submit(
                workgroup::ScanTask([promise = ms.get(), func]() { promise->set_value(func()); }));
=======
        ExecEnv::GetInstance()->connector_scan_executor()->submit(workgroup::ScanTask(
                ExecEnv::GetInstance()->workgroup_manager()->get_default_workgroup(),
                [promise = ms.get(), func](workgroup::YieldContext&) { promise->set_value(func()); }));
>>>>>>> 3317f49811 ([BugFix] Capture resource group for scan task (#51121))
    } else {
        ms->set_value(func());
    }
    return ms;
}

} // namespace starrocks
