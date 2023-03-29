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

#pragma once

#include <memory>
#include <string>
#include <unordered_map>

#include "gen_cpp/PlanNodes_types.h"
#include "runtime/runtime_state.h"
#include "storage/chunk_helper.h"

namespace starrocks {

class ExprContext;
class ConnectorScanNode;
class RuntimeFilterProbeCollector;

namespace connector {

// DataSource defines how to read data from a single scan range.
// currently scan range is defined by `TScanRange`, I think it's better defined by DataSourceProvider.
// DataSourceProvider can split a single scan range further into multiple smaller & customized scan ranges.
// In that way fine granularity can be supported, multiple `DataSoruce`s can read data from a single scan range.
class DataSource {
public:
    virtual ~DataSource() = default;
    virtual Status open(RuntimeState* state) { return Status::OK(); }
    virtual void close(RuntimeState* state) {}
    virtual Status get_next(RuntimeState* state, ChunkPtr* chunk) { return Status::OK(); }
    virtual bool skip_predicate() const { return false; }

    // how many rows read from storage
    virtual int64_t raw_rows_read() const = 0;
    // how many rows returned after filtering.
    virtual int64_t num_rows_read() const = 0;
    // how many bytes read from external
    virtual int64_t num_bytes_read() const = 0;
    // CPU time of this data source
    virtual int64_t cpu_time_spent() const = 0;

    // following fields are set by framework
    // 1. runtime profile: any metrics you want to record
    // 2. predicates: predicates in SQL query(possibly including IN filters generated by broadcast join)
    // 3. runtime filters: local & global runtime filters(or dynamic filters)
    // 4. read limit: for case like `select xxxx from table limit 10`.
    void set_runtime_profile(RuntimeProfile* runtime_profile) { _runtime_profile = runtime_profile; }
    void set_predicates(const std::vector<ExprContext*>& predicates) { _conjunct_ctxs = predicates; }
    void set_runtime_filters(const RuntimeFilterProbeCollector* runtime_filters) { _runtime_filters = runtime_filters; }
    void set_read_limit(const uint64_t limit) { _read_limit = limit; }
    Status parse_runtime_filters(RuntimeState* state);

protected:
    int64_t _read_limit = -1; // no limit
    std::vector<ExprContext*> _conjunct_ctxs;
    const RuntimeFilterProbeCollector* _runtime_filters;
    RuntimeProfile* _runtime_profile;
    const TupleDescriptor* _tuple_desc = nullptr;
    void _init_chunk(ChunkPtr* chunk, size_t n) { *chunk = ChunkHelper::new_chunk(*_tuple_desc, n); }
};

using DataSourcePtr = std::unique_ptr<DataSource>;

class DataSourceProvider {
public:
    virtual ~DataSourceProvider() = default;

    // First version we use TScanRange to define scan range
    // Later version we could use user-defined data.
    virtual DataSourcePtr create_data_source(const TScanRange& scan_range) = 0;
    // virtual DataSourcePtr create_data_source(const std::string& scan_range_spec)  = 0;

    // non-pipeline APIs
    Status prepare(RuntimeState* state) { return Status::OK(); }
    Status open(RuntimeState* state) { return Status::OK(); }
    void close(RuntimeState* state) {}

    // For some data source does not support scan ranges, dop is limited to 1,
    // and that will limit upper operators. And the solution is to insert a local exchange operator to fanout
    // and let upper operators have better parallelism.
    virtual bool insert_local_exchange_operator() const { return false; }

    // If this data source accept empty scan ranges, because for some data source there is no concept of scan ranges
    // such as MySQL/JDBC, so `accept_empty_scan_ranges` is false, and most in most cases, these data source(MySQL/JDBC)
    // the method `insert_local_exchange_operator` is true also.
    virtual bool accept_empty_scan_ranges() const { return true; }

    virtual bool stream_data_source() const { return false; }

    virtual Status init(ObjectPool* pool, RuntimeState* state) { return Status::OK(); }

    const std::vector<ExprContext*>& partition_exprs() const { return _partition_exprs; }

    virtual bool always_shared_scan() const { return true; }
    virtual const TupleDescriptor* tuple_descriptor(RuntimeState* state) const = 0;

protected:
    std::vector<ExprContext*> _partition_exprs;
};
using DataSourceProviderPtr = std::unique_ptr<DataSourceProvider>;

enum ConnectorType {
    HIVE = 0,
    ES = 1,
    JDBC = 2,
    MYSQL = 3,
    FILE = 4,
    LAKE = 5,
    BINLOG = 6,
};

class Connector {
public:
    // supported connectors.
    static const std::string HIVE;
    static const std::string ES;
    static const std::string JDBC;
    static const std::string MYSQL;
    static const std::string FILE;
    static const std::string LAKE;
    static const std::string BINLOG;

    virtual ~Connector() = default;
    // First version we use TPlanNode to construct data source provider.
    // Later version we could use user-defined data.

    virtual DataSourceProviderPtr create_data_source_provider(ConnectorScanNode* scan_node,
                                                              const TPlanNode& plan_node) const = 0;

    // virtual DataSourceProviderPtr create_data_source_provider(ConnectorScanNode* scan_node,
    //                                                         const std::string& table_handle) const;

    virtual ConnectorType connector_type() const = 0;
};

class ConnectorManager {
public:
    static ConnectorManager* default_instance();
    const Connector* get(const std::string& name);
    void put(const std::string& name, std::unique_ptr<Connector> connector);

private:
    std::unordered_map<std::string, std::unique_ptr<Connector>> _connectors;
};

} // namespace connector
} // namespace starrocks
