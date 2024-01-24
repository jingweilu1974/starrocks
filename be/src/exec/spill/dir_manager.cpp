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

#include "exec/spill/dir_manager.h"

#include <regex>

#include "common/config.h"
#include "storage/options.h"
#include "storage/storage_engine.h"
#include "storage/utils.h"

namespace starrocks::spill {

Status DirManager::init(const std::string& spill_dirs) {
    std::vector<starrocks::StorePath> spill_local_storage_paths;
    RETURN_IF_ERROR(parse_conf_store_paths(spill_dirs, &spill_local_storage_paths));
    if (spill_local_storage_paths.empty()) {
        return Status::InvalidArgument("cannot find spill_local_storage_dir");
    }

    auto storage_paths = starrocks::StorageEngine::instance()->get_store_paths();
    std::set<std::string> storage_path_set(storage_paths.begin(), storage_paths.end());

    for (auto iter = spill_local_storage_paths.begin(); iter != spill_local_storage_paths.end();) {
        const auto& path = iter->path;
        if (storage_path_set.find(path) != storage_path_set.end()) {
            return Status::InvalidArgument(fmt::format(
                    "spill_local_storage_dir {} already exists in storage_root_path, please use another path", path));
        }
        if (!starrocks::check_datapath_rw(path)) {
            if (starrocks::config::ignore_broken_disk) {
                LOG(WARNING) << fmt::format("read write test spill_local_storage_dir {} failed, ignore it", path);
                iter = spill_local_storage_paths.erase(iter);
            } else {
                return Status::IOError(
                        fmt::format("read write test spill_local_storage_dir {} failed, please make sure it is "
                                    "available and BE has permission to access",
                                    path));
            }
        } else {
            iter++;
        }
    }
    if (spill_local_storage_paths.empty()) {
        return Status::InvalidArgument("cannot find available spill_local_storage_dir");
    }

    std::regex query_id_pattern("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    for (const auto& path : spill_local_storage_paths) {
        std::string spill_dir_path = path.path;
        ASSIGN_OR_RETURN(auto fs, FileSystem::CreateSharedFromString(spill_dir_path));
        RETURN_IF_ERROR(fs->create_dir_if_missing(spill_dir_path));
        RETURN_IF_ERROR(fs->iterate_dir(spill_dir_path, [fs, &spill_dir_path,
                                                         &query_id_pattern](std::string_view sub_dir_v) {
            const std::string sub_dir = std::string(sub_dir_v.begin(), sub_dir_v.end());
            // if sub_dir can match query_id_pattern, we treat it as a residual dir of spilling data, clean it.
            // otherwise, we skip cleanning it to avoid accidental deletion
            std::string dir = spill_dir_path + "/" + sub_dir;
            if (std::regex_match(sub_dir, query_id_pattern)) {
                fs->delete_dir_recursive(dir);
            } else {
                LOG(INFO) << fmt::format("{} is not a directory generated by query spilling, skip cleanning it", dir);
            }
            return true;
        }));
        _dirs.emplace_back(std::make_shared<Dir>(spill_dir_path, std::move(fs)));
    }
    return Status::OK();
}

StatusOr<Dir*> DirManager::acquire_writable_dir(const AcquireDirOptions& opts) {
    // @TODO(silverbullet233): refine the strategy for dir selection
    size_t idx = _idx++ % _dirs.size();
    return _dirs[idx].get();
}

} // namespace starrocks::spill