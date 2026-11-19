package com.kaorios.patcher.domain.model

/**
 * Chế độ làm việc của app.
 *
 * Ghi chú: tên + mô tả hiển thị được bản địa hoá ở tầng UI (xem `values/strings.xml`
 * `mode_<kind>_title` / `mode_<kind>_desc`). Không giữ string ở đây để tránh lẫn với domain.
 */
enum class WorkMode {
    KAORIOS_PATCHER,
    OTHER_JAR
}
