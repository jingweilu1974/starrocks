// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.

#pragma once

#include <string>
#include <vector>

#include "common/compiler_util.h"
#include "common/logging.h"
#include "gen_cpp/StatusCode_types.h" // for TStatus
#include "util/slice.h"               // for Slice
#include "util/time.h"
#ifdef STARROCKS_ASSERT_STATUS_CHECKED
#include "util/stack_util.h"
#endif

namespace starrocks {

class StatusPB;
class TStatus;

template <typename T>
class StatusOr;
#ifdef STARROCKS_STATUS_NODISCARD
#define STATUS_ATTRIBUTE [[nodiscard]]
#else
#define STATUS_ATTRIBUTE
#endif

class STATUS_ATTRIBUTE Status {
public:
    Status() = default;

    ~Status() noexcept {
#ifdef STARROCKS_ASSERT_STATUS_CHECKED
        if (!_checked) {
            auto stack = get_stack_trace();
            fprintf(stderr, "Failed to check status %p:\n%s\n", this, stack.c_str());
            // TODO: abort on unhandled statuses
            // For now, log and continue on unhandled status due to widespread issues
            // To be fixed in refactoring to handle statuses correctly everywhere
            // In the future, should abort on unhandled statuses
            // std::abort();
        }
#endif
        if (!is_moved_from(_state)) {
            delete[] _state;
        }
    }

#if defined(ENABLE_STATUS_FAILED)
    static int32_t get_cardinality_of_inject();
    static inline std::unordered_map<std::string, bool> dircetory_enable;
    static void access_directory_of_inject();
    static bool in_directory_of_inject(const std::string&);
#endif

    // Copy c'tor makes copy of error detail so Status can be returned by value
    Status(const Status& s) : _state(s._state == nullptr ? nullptr : copy_state(s._state)) {
        s.mark_checked();
        must_check();
    }

    // Move c'tor
    Status(Status&& s) noexcept : _state(s._state) {
        s._state = moved_from_state();
        s.mark_checked();
        must_check();
    }

    // Same as copy c'tor
    Status& operator=(const Status& s) {
        if (this != &s) {
            Status tmp(s);
            std::swap(this->_state, tmp._state);
            tmp.mark_checked();
            must_check();
        }
        return *this;
    }

    // Move assign.
    Status& operator=(Status&& s) noexcept {
        if (this != &s) {
            Status tmp(std::move(s));
            std::swap(this->_state, tmp._state);
            tmp.mark_checked();
            must_check();
        }
        return *this;
    }

    // "Copy" c'tor from TStatus.
    Status(const TStatus& status); // NOLINT

    Status(const StatusPB& pstatus); // NOLINT

    // Inspired by absl::Status::Update()
    // https://github.com/abseil/abseil-cpp/blob/63c9eeca0464c08ccb861b21e33e10faead414c9/absl/status/status.h#L467
    //
    // Status::update()
    //
    // Updates the existing status with `new_status` provided that `this->ok()`.
    // If the existing status already contains a non-OK error, this update has no
    // effect and preserves the current data.
    //
    // `update()` provides a convenient way of keeping track of the first error
    // encountered.
    //
    // Example:
    //   // Instead of "if (overall_status.ok()) overall_status = new_status"
    //   overall_status.update(new_status);
    //
    void update(const Status& new_status);
    void update(Status&& new_status);

    // In case of intentionally swallowing an error, user must explicitly call
    // this function. That way we are easily able to search the code to find where
    // error swallowing occurs.
    //
    // Inspired by Status in RocksDB:
    // https://github.com/facebook/rocksdb/blob/05daa123323b1471bde4723dc441763d687fd825/include/rocksdb/status.h#L67
    void permit_unchecked_error() const { mark_checked(); }

    inline void must_check() const {
#ifdef STARROCKS_ASSERT_STATUS_CHECKED
        _checked = false;
#endif // STARROCKS_ASSERT_STATUS_CHECKED
    }

    static Status OK() { return Status(); }

    static Status Unknown(const Slice& msg) { return Status(TStatusCode::UNKNOWN, msg); }

    static Status PublishTimeout(const Slice& msg) { return Status(TStatusCode::PUBLISH_TIMEOUT, msg); }
    static Status MemoryAllocFailed(const Slice& msg) { return Status(TStatusCode::MEM_ALLOC_FAILED, msg); }
    static Status BufferAllocFailed(const Slice& msg) { return Status(TStatusCode::BUFFER_ALLOCATION_FAILED, msg); }
    static Status InvalidArgument(const Slice& msg) { return Status(TStatusCode::INVALID_ARGUMENT, msg); }
    static Status MinimumReservationUnavailable(const Slice& msg) {
        return Status(TStatusCode::MINIMUM_RESERVATION_UNAVAILABLE, msg);
    }
    static Status Corruption(const Slice& msg) { return Status(TStatusCode::CORRUPTION, msg); }
    static Status IOError(const Slice& msg) { return Status(TStatusCode::IO_ERROR, msg); }
    static Status NotFound(const Slice& msg) { return Status(TStatusCode::NOT_FOUND, msg); }
    static Status AlreadyExist(const Slice& msg) { return Status(TStatusCode::ALREADY_EXIST, msg); }
    static Status NotSupported(const Slice& msg) { return Status(TStatusCode::NOT_IMPLEMENTED_ERROR, msg); }
    static Status EndOfFile(const Slice& msg) { return Status(TStatusCode::END_OF_FILE, msg); }
    static Status InternalError(const Slice& msg, int16_t precise_code = 1, const Slice& msg2 = Slice()) {
        return Status(TStatusCode::INTERNAL_ERROR, msg);
    }
    static Status RuntimeError(const Slice& msg, int16_t precise_code = 1, const Slice& msg2 = Slice()) {
        return Status(TStatusCode::RUNTIME_ERROR, msg);
    }
    static Status Cancelled(const Slice& msg, int16_t precise_code = 1, const Slice& msg2 = Slice()) {
        return Status(TStatusCode::CANCELLED, msg);
    }

    static Status MemoryLimitExceeded(const Slice& msg, int16_t precise_code = 1, const Slice& msg2 = Slice()) {
        return Status(TStatusCode::MEM_LIMIT_EXCEEDED, msg);
    }

    static Status ThriftRpcError(const Slice& msg, int16_t precise_code = 1, const Slice& msg2 = Slice()) {
        return Status(TStatusCode::THRIFT_RPC_ERROR, msg);
    }
    static Status TimedOut(const Slice& msg, int16_t precise_code = 1, const Slice& msg2 = Slice()) {
        return Status(TStatusCode::TIMEOUT, msg);
    }
    static Status TooManyTasks(const Slice& msg, int16_t precise_code = 1, const Slice& msg2 = Slice()) {
        return Status(TStatusCode::TOO_MANY_TASKS, msg);
    }
    static Status ServiceUnavailable(const Slice& msg) { return Status(TStatusCode::SERVICE_UNAVAILABLE, msg); }
    static Status Uninitialized(const Slice& msg) { return Status(TStatusCode::UNINITIALIZED, msg); }
    static Status Aborted(const Slice& msg) { return Status(TStatusCode::ABORTED, msg); }
    static Status DataQualityError(const Slice& msg) { return Status(TStatusCode::DATA_QUALITY_ERROR, msg); }
    static Status VersionAlreadyMerged(const Slice& msg) {
        return Status(TStatusCode::OLAP_ERR_VERSION_ALREADY_MERGED, msg);
    }
    static Status DuplicateRpcInvocation(const Slice& msg) {
        return Status(TStatusCode::DUPLICATE_RPC_INVOCATION, msg);
    }
    static Status JsonFormatError(const Slice& msg) {
        // TODO(mofei) define json format error.
        return Status(TStatusCode::DATA_QUALITY_ERROR, msg);
    }

    static Status GlobalDictError(const Slice& msg) { return Status(TStatusCode::GLOBAL_DICT_ERROR, msg); }

    static Status TransactionInProcessing(const Slice& msg) { return Status(TStatusCode::TXN_IN_PROCESSING, msg); }
    static Status TransactionNotExists(const Slice& msg) { return Status(TStatusCode::TXN_NOT_EXISTS, msg); }
    static Status LabelAlreadyExists(const Slice& msg) { return Status(TStatusCode::LABEL_ALREADY_EXISTS, msg); }

    static Status ResourceBusy(const Slice& msg) { return Status(TStatusCode::RESOURCE_BUSY, msg); }

    static Status EAgain(const Slice& msg) { return Status(TStatusCode::SR_EAGAIN, msg); }

    static Status RemoteFileNotFound(const Slice& msg) { return Status(TStatusCode::REMOTE_FILE_NOT_FOUND, msg); }

    static Status CapacityLimitExceed(const Slice& msg) { return Status(TStatusCode::CAPACITY_LIMIT_EXCEED, msg); }

    bool ok() const {
        mark_checked();
        return _state == nullptr;
    }

    bool is_cancelled() const {
        mark_checked();
        return code() == TStatusCode::CANCELLED;
    }

    bool is_mem_limit_exceeded() const {
        mark_checked();
        return code() == TStatusCode::MEM_LIMIT_EXCEEDED;
    }

    bool is_capacity_limit_exceeded() const {
        mark_checked();
        return code() == TStatusCode::CAPACITY_LIMIT_EXCEED;
    }

    bool is_thrift_rpc_error() const {
        mark_checked();
        return code() == TStatusCode::THRIFT_RPC_ERROR;
    }

    bool is_end_of_file() const {
        mark_checked();
        return code() == TStatusCode::END_OF_FILE;
    }

    bool is_ok_or_eof() const {
        mark_checked();
        return ok() || is_end_of_file();
    }

    bool is_not_found() const {
        mark_checked();
        return code() == TStatusCode::NOT_FOUND;
    }

    bool is_already_exist() const {
        mark_checked();
        return code() == TStatusCode::ALREADY_EXIST;
    }

    bool is_io_error() const {
        mark_checked();
        return code() == TStatusCode::IO_ERROR;
    }

    bool is_not_supported() const {
        mark_checked();
        return code() == TStatusCode::NOT_IMPLEMENTED_ERROR;
    }

    bool is_corruption() const {
        mark_checked();
        return code() == TStatusCode::CORRUPTION;
    }

    bool is_resource_busy() const {
        mark_checked();
        return code() == TStatusCode::RESOURCE_BUSY;
    }

    /// @return @c true if the status indicates Uninitialized.
    bool is_uninitialized() const {
        mark_checked();
        return code() == TStatusCode::UNINITIALIZED;
    }

    // @return @c true if the status indicates an Aborted error.
    bool is_aborted() const {
        mark_checked();
        return code() == TStatusCode::ABORTED;
    }

    /// @return @c true if the status indicates an InvalidArgument error.
    bool is_invalid_argument() const {
        mark_checked();
        return code() == TStatusCode::INVALID_ARGUMENT;
    }

    // @return @c true if the status indicates ServiceUnavailable.
    bool is_service_unavailable() const {
        mark_checked();
        return code() == TStatusCode::SERVICE_UNAVAILABLE;
    }

    bool is_data_quality_error() const {
        mark_checked();
        return code() == TStatusCode::DATA_QUALITY_ERROR;
    }

    bool is_version_already_merged() const {
        mark_checked();
        return code() == TStatusCode::OLAP_ERR_VERSION_ALREADY_MERGED;
    }

    bool is_duplicate_rpc_invocation() const {
        mark_checked();
        return code() == TStatusCode::DUPLICATE_RPC_INVOCATION;
    }

    bool is_time_out() const {
        mark_checked();
        return code() == TStatusCode::TIMEOUT;
    }

    bool is_eagain() const {
        mark_checked();
        return code() == TStatusCode::SR_EAGAIN;
    }

    // Convert into TStatus. Call this if 'status_container' contains an optional
    // TStatus field named 'status'. This also sets __isset.status.
    template <typename T>
    void set_t_status(T* status_container) const {
        to_thrift(&status_container->status);
        status_container->__isset.status = true;
    }

    // Convert into TStatus.
    void to_thrift(TStatus* status) const;
    void to_protobuf(StatusPB* status) const;

    std::string get_error_msg() const {
        mark_checked();
        auto msg = message();
        return std::string(msg.data, msg.size);
    }

    /// @return A string representation of this status suitable for printing.
    ///   Returns the string "OK" for success.
    std::string to_string(bool with_context_info = true) const;

    /// @return A string representation of the status code, without the message
    ///   text or sub code information.
    std::string code_as_string() const;

    // This is similar to to_string, except that it does not include
    // the context info.
    //
    // @note The returned Slice is only valid as long as this Status object
    //   remains live and unchanged.
    //
    // @return The message portion of the Status. For @c OK statuses,
    //   this returns an empty string.
    Slice message() const;

    // Error message with extra context info, like file name, line number.
    Slice detailed_message() const;

    TStatusCode::type code() const {
        mark_checked();
        return _state == nullptr ? TStatusCode::OK : static_cast<TStatusCode::type>(_state[4]);
    }

    /// Clone this status and add the specified prefix to the message.
    ///
    /// If this status is OK, then an OK status will be returned.
    ///
    /// @param [in] msg
    ///   The message to prepend.
    /// @return A new Status object with the same state plus an additional
    ///   leading message.
    Status clone_and_prepend(const Slice& msg) const;

    /// Clone this status and add the specified suffix to the message.
    ///
    /// If this status is OK, then an OK status will be returned.
    ///
    /// @param [in] msg
    ///   The message to append.
    /// @return A new Status object with the same state plus an additional
    ///   trailing message.
    Status clone_and_append(const Slice& msg) const;

    Status clone_and_append_context(const char* filename, int line, const char* expr) const;

    Status(TStatusCode::type code, Slice msg) : Status(code, msg, {}) {}
    Status(TStatusCode::type code, Slice msg, Slice ctx);

private:
    static const char* copy_state(const char* state);
    static const char* copy_state_with_extra_ctx(const char* state, Slice ctx);

    // Indicates whether this Status was the rhs of a move operation.
    static bool is_moved_from(const char* state);
    static const char* moved_from_state();

    void mark_checked() const {
#ifdef STARROCKS_ASSERT_STATUS_CHECKED
        _checked = true;
#endif
    }

private:
    // OK status has a nullptr _state.  Otherwise, _state is a new[] array
    // of the following form:
    //    _state[0..1]                        == len1: length of message
    //    _state[2..3]                        == len2: length of context
    //    _state[4]                           == code
    //    _state[5.. 5 + len1]                == message
    //    _state[5 + len1 .. 5 + len1 + len2] == context
    const char* _state = nullptr;
#ifdef STARROCKS_ASSERT_STATUS_CHECKED
    // This field design follows Status in RocksDB
    // https://github.com/facebook/rocksdb/blob/05daa123323b1471bde4723dc441763d687fd825/include/rocksdb/status.h#L467
    mutable bool _checked = false;
#endif
};

inline void Status::update(const Status& new_status) {
    new_status.mark_checked();
    if (ok()) {
        *this = new_status;
        must_check();
    }
}

inline void Status::update(Status&& new_status) {
    new_status.mark_checked();
    if (ok()) {
        *this = std::move(new_status);
        must_check();
    }
}

inline Status ignore_not_found(const Status& status) {
    return status.is_not_found() ? Status::OK() : status;
}

inline std::ostream& operator<<(std::ostream& os, const Status& st) {
    return os << st.to_string();
}

inline const Status& to_status(const Status& st) {
    return st;
}

template <typename T>
inline const Status& to_status(const StatusOr<T>& st) {
    return st.status();
}

#ifndef AS_STRING
#define AS_STRING(x) AS_STRING_INTERNAL(x)
#define AS_STRING_INTERNAL(x) #x
#endif

#define RETURN_IF_ERROR_INTERNAL(stmt)                                                                \
    do {                                                                                              \
        auto&& status__ = (stmt);                                                                     \
        if (UNLIKELY(!status__.ok())) {                                                               \
            return to_status(status__).clone_and_append_context(__FILE__, __LINE__, AS_STRING(stmt)); \
        }                                                                                             \
    } while (false)

#if defined(ENABLE_STATUS_FAILED)
struct StatusInstance {
    static constexpr Status (*random[])(const Slice& msg) = {&Status::Unknown,
                                                             &Status::PublishTimeout,
                                                             &Status::MemoryAllocFailed,
                                                             &Status::BufferAllocFailed,
                                                             &Status::InvalidArgument,
                                                             &Status::MinimumReservationUnavailable,
                                                             &Status::Corruption,
                                                             &Status::IOError,
                                                             &Status::NotFound,
                                                             &Status::AlreadyExist,
                                                             &Status::NotSupported,
                                                             &Status::EndOfFile,
                                                             &Status::ServiceUnavailable,
                                                             &Status::Uninitialized,
                                                             &Status::Aborted,
                                                             &Status::DataQualityError,
                                                             &Status::VersionAlreadyMerged,
                                                             &Status::DuplicateRpcInvocation,
                                                             &Status::JsonFormatError,
                                                             &Status::GlobalDictError,
                                                             &Status::TransactionInProcessing,
                                                             &Status::TransactionNotExists,
                                                             &Status::LabelAlreadyExists,
                                                             &Status::ResourceBusy};

    static constexpr TStatusCode::type codes[] = {TStatusCode::UNKNOWN,
                                                  TStatusCode::PUBLISH_TIMEOUT,
                                                  TStatusCode::MEM_ALLOC_FAILED,
                                                  TStatusCode::BUFFER_ALLOCATION_FAILED,
                                                  TStatusCode::INVALID_ARGUMENT,
                                                  TStatusCode::MINIMUM_RESERVATION_UNAVAILABLE,
                                                  TStatusCode::CORRUPTION,
                                                  TStatusCode::IO_ERROR,
                                                  TStatusCode::NOT_FOUND,
                                                  TStatusCode::ALREADY_EXIST,
                                                  TStatusCode::NOT_IMPLEMENTED_ERROR,
                                                  TStatusCode::END_OF_FILE,
                                                  TStatusCode::SERVICE_UNAVAILABLE,
                                                  TStatusCode::UNINITIALIZED,
                                                  TStatusCode::ABORTED,
                                                  TStatusCode::DATA_QUALITY_ERROR,
                                                  TStatusCode::OLAP_ERR_VERSION_ALREADY_MERGED,
                                                  TStatusCode::DUPLICATE_RPC_INVOCATION,
                                                  TStatusCode::DATA_QUALITY_ERROR,
                                                  TStatusCode::GLOBAL_DICT_ERROR,
                                                  TStatusCode::TXN_IN_PROCESSING,
                                                  TStatusCode::TXN_NOT_EXISTS,
                                                  TStatusCode::LABEL_ALREADY_EXISTS,
                                                  TStatusCode::RESOURCE_BUSY};

    static constexpr int SIZE = sizeof(random) / sizeof(Status(*)(const Slice& msg));
};

#define RETURN_INJECT(index)                                                         \
    std::stringstream ss;                                                            \
    ss << "INJECT ERROR: " << __FILE__ << " " << __LINE__ << " "                     \
       << starrocks::StatusInstance::codes[index % starrocks::StatusInstance::SIZE]; \
    return starrocks::StatusInstance::random[index % starrocks::StatusInstance::SIZE](ss.str());

#define RETURN_IF_ERROR(stmt)                                                                             \
    do {                                                                                                  \
        uint32_t seed = starrocks::GetCurrentTimeNanos();                                                 \
        seed = ::rand_r(&seed);                                                                           \
        uint32_t boundary_value = RAND_MAX / (1.0 * starrocks::Status::get_cardinality_of_inject());      \
        /* Pre-condition of inject errors: probability and File scope*/                                   \
        if (seed <= boundary_value && starrocks::Status::in_directory_of_inject(__FILE__)) {              \
            RETURN_INJECT(seed);                                                                          \
        } else {                                                                                          \
            auto&& status__ = (stmt);                                                                     \
            if (UNLIKELY(!status__.ok())) {                                                               \
                return to_status(status__).clone_and_append_context(__FILE__, __LINE__, AS_STRING(stmt)); \
            }                                                                                             \
        }                                                                                                 \
    } while (false)
#else
#define RETURN_IF_ERROR(stmt) RETURN_IF_ERROR_INTERNAL(stmt)
#endif

#define EXIT_IF_ERROR(stmt)                        \
    do {                                           \
        auto&& status__ = (stmt);                  \
        if (UNLIKELY(!status__.ok())) {            \
            string msg = status__.get_error_msg(); \
            LOG(ERROR) << msg;                     \
            exit(1);                               \
        }                                          \
    } while (false)

#define VA_ARGS_HELPER(fmt, ...) fmt " " #__VA_ARGS__

#define RETURN_ERROR_IF_FALSE(condition, ...)                                       \
    if (GOOGLE_PREDICT_BRANCH_NOT_TAKEN(!(condition))) {                            \
        std::ostringstream oss;                                                     \
        oss << "Check failed: " #condition ". " << VA_ARGS_HELPER("", __VA_ARGS__); \
        std::string error_msg = oss.str();                                          \
        LOG(ERROR) << error_msg;                                                    \
        return Status::InternalError(error_msg);                                    \
    }

/// @brief Emit a warning if @c to_call returns a bad status.
#define WARN_IF_ERROR(to_call, warning_prefix)                \
    do {                                                      \
        auto&& st__ = (to_call);                              \
        if (UNLIKELY(!st__.ok())) {                           \
            LOG(WARNING) << (warning_prefix) << ": " << st__; \
        }                                                     \
    } while (0)

#define RETURN_IF_ERROR_WITH_WARN(stmt, warning_prefix)              \
    do {                                                             \
        auto&& st__ = (stmt);                                        \
        if (UNLIKELY(!st__.ok())) {                                  \
            LOG(WARNING) << (warning_prefix) << ", error: " << st__; \
            return std::move(st__);                                  \
        }                                                            \
    } while (0)

#define DCHECK_IF_ERROR(stmt)      \
    do {                           \
        auto&& st__ = (stmt);      \
        DCHECK(st__.ok()) << st__; \
    } while (0)

} // namespace starrocks

#define RETURN_IF(cond, ret) \
    do {                     \
        if (cond) {          \
            return ret;      \
        }                    \
    } while (0)

#define RETURN_IF_UNLIKELY_NULL(ptr, ret) \
    do {                                  \
        if (UNLIKELY(ptr == nullptr)) {   \
            return ret;                   \
        }                                 \
    } while (0)

#define RETURN_IF_UNLIKELY(cond, ret) \
    do {                              \
        if (UNLIKELY(cond)) {         \
            return ret;               \
        }                             \
    } while (0)

#define RETURN_IF_EXCEPTION(stmt)                   \
    do {                                            \
        try {                                       \
            { stmt; }                               \
        } catch (const std::exception& e) {         \
            return Status::InternalError(e.what()); \
        }                                           \
    } while (0)
