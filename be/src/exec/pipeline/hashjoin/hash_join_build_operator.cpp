// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "exec/pipeline/hashjoin/hash_join_build_operator.h"

#include <numeric>
#include <utility>

#include "exec/pipeline/query_context.h"
#include "exprs/runtime_filter_bank.h"
#include "runtime/current_thread.h"
#include "runtime/runtime_filter_worker.h"
#include "util/race_detect.h"
namespace starrocks::pipeline {

HashJoinBuildOperator::HashJoinBuildOperator(OperatorFactory* factory, int32_t id, const string& name,
                                             int32_t plan_node_id, int32_t driver_sequence, HashJoinerPtr join_builder,
                                             PartialRuntimeFilterMerger* partial_rf_merger,
                                             const TJoinDistributionMode::type distribution_mode)
        : Operator(factory, id, name, plan_node_id, driver_sequence),
          _join_builder(std::move(join_builder)),
          _partial_rf_merger(partial_rf_merger),
          _distribution_mode(distribution_mode) {}

Status HashJoinBuildOperator::push_chunk(RuntimeState* state, const ChunkPtr& chunk) {
    return _join_builder->append_chunk_to_ht(state, chunk);
}

Status HashJoinBuildOperator::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(Operator::prepare(state));

    _partial_rf_merger->incr_builder();

    // For prober.
    // HashJoinProbeOperator may be instantiated lazily, so join_builder is ref here
    // and unref when all the probers are finished in join_builder->decr_prober.
    _join_builder->ref();
    // For builder.
    _join_builder->ref();

    RETURN_IF_ERROR(_join_builder->prepare_builder(state, _unique_metrics.get()));

    return Status::OK();
}
void HashJoinBuildOperator::close(RuntimeState* state) {
    _join_builder->unref(state);

    Operator::close(state);
}

StatusOr<ChunkPtr> HashJoinBuildOperator::pull_chunk(RuntimeState* state) {
    const char* msg = "pull_chunk not supported in HashJoinBuildOperator";
    CHECK(false) << msg;
    return Status::NotSupported(msg);
}

size_t HashJoinBuildOperator::output_amplification_factor() const {
    if (_avg_keys_perf_bucket > 0) {
        return _avg_keys_perf_bucket;
    }

    _avg_keys_perf_bucket = _join_builder->avg_keys_perf_bucket();
    _avg_keys_perf_bucket = std::max<size_t>(_avg_keys_perf_bucket, 1);

    auto* counter = ADD_COUNTER(_unique_metrics, "AvgKeysPerBuckets", TUnit::UNIT);
    COUNTER_SET(counter, static_cast<int64_t>(_avg_keys_perf_bucket));

    return _avg_keys_perf_bucket;
}

Status HashJoinBuildOperator::set_finishing(RuntimeState* state) {
    ONCE_DETECT(_set_finishing_once);
    DeferOp op([this]() { _is_finished = true; });

    if (state->is_cancelled()) {
        return Status::Cancelled("runtime state is cancelled");
    }
    RETURN_IF_ERROR(_join_builder->build_ht(state));

    size_t merger_index = _driver_sequence;
    // Broadcast Join only has one build operator.
    DCHECK(_distribution_mode != TJoinDistributionMode::BROADCAST || _driver_sequence == 0);

    RETURN_IF_ERROR(_join_builder->create_runtime_filters(state));

    auto ht_row_count = _join_builder->get_ht_row_count();
    auto& partial_in_filters = _join_builder->get_runtime_in_filters();
    auto& partial_bloom_filter_build_params = _join_builder->get_runtime_bloom_filter_build_params();
    auto& partial_bloom_filters = _join_builder->get_runtime_bloom_filters();

    auto mem_tracker = state->query_ctx()->mem_tracker();
    SCOPED_THREAD_LOCAL_MEM_TRACKER_SETTER(mem_tracker.get());

    // retain string-typed key columns to avoid premature de-allocation when both probe side and build side
    // PipelineDrivers finalization before in-filers is merged.
    ((HashJoinBuildOperatorFactory*)_factory)
            ->retain_string_key_columns(_driver_sequence, _join_builder->string_key_columns());

    // add partial filters generated by this HashJoinBuildOperator to PartialRuntimeFilterMerger to merge into a
    // total one.
    StatusOr<bool> status;
    {
        SCOPED_TIMER(_join_builder->build_metrics().build_runtime_filter_timer);
        status = _partial_rf_merger->add_partial_filters(merger_index, ht_row_count, std::move(partial_in_filters),
                                                         std::move(partial_bloom_filter_build_params),
                                                         std::move(partial_bloom_filters));
    }
    if (!status.ok()) {
        return status.status();
    } else if (status.value()) {
        auto&& in_filters = _partial_rf_merger->get_total_in_filters();
        auto&& bloom_filters = _partial_rf_merger->get_total_bloom_filters();

        {
            size_t total_bf_bytes = std::accumulate(bloom_filters.begin(), bloom_filters.end(), 0ull,
                                                    [](size_t total, RuntimeFilterBuildDescriptor* desc) -> size_t {
                                                        auto rf = desc->runtime_filter();
                                                        total += (rf == nullptr ? 0 : rf->bf_alloc_size());
                                                        return total;
                                                    });
            COUNTER_UPDATE(_join_builder->build_metrics().partial_runtime_bloom_filter_bytes, total_bf_bytes);
        }

        // publish runtime bloom-filters
        state->runtime_filter_port()->publish_runtime_filters(bloom_filters);
        // move runtime filters into RuntimeFilterHub.
        runtime_filter_hub()->set_collector(_plan_node_id, std::make_unique<RuntimeFilterCollector>(
                                                                   std::move(in_filters), std::move(bloom_filters)));
    }

    _join_builder->enter_probe_phase();

    return Status::OK();
}

HashJoinBuildOperatorFactory::HashJoinBuildOperatorFactory(
        int32_t id, int32_t plan_node_id, HashJoinerFactoryPtr hash_joiner_factory,
        std::unique_ptr<PartialRuntimeFilterMerger>&& partial_rf_merger,
        const TJoinDistributionMode::type distribution_mode, SpillProcessChannelFactoryPtr spill_channel_factory)
        : OperatorFactory(id, "hash_join_build", plan_node_id),
          _hash_joiner_factory(std::move(hash_joiner_factory)),
          _partial_rf_merger(std::move(partial_rf_merger)),
          _distribution_mode(distribution_mode),
          _spill_channel_factory(std::move(spill_channel_factory)) {}

Status HashJoinBuildOperatorFactory::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(OperatorFactory::prepare(state));
    return _hash_joiner_factory->prepare(state);
}

void HashJoinBuildOperatorFactory::close(RuntimeState* state) {
    _hash_joiner_factory->close(state);
    OperatorFactory::close(state);
}

OperatorPtr HashJoinBuildOperatorFactory::create(int32_t dop, int32_t driver_sequence) {
    if (_string_key_columns.empty()) {
        _string_key_columns.resize(dop);
    }

    return std::make_shared<HashJoinBuildOperator>(this, _id, _name, _plan_node_id, driver_sequence,
                                                   _hash_joiner_factory->create_builder(dop, driver_sequence),
                                                   _partial_rf_merger.get(), _distribution_mode);
}

void HashJoinBuildOperatorFactory::retain_string_key_columns(int32_t driver_sequence, Columns&& columns) {
    _string_key_columns[driver_sequence] = std::move(columns);
}
} // namespace starrocks::pipeline
