package com.kaorios.patcher.domain.actions

/**
 * Cấu hình động cho KaoriosToolbox 1.0.9 — tag release GitHub user đã xác thực trước khi chạy.
 *
 * `FileBrowserViewModel` set `currentTag` SAU khi validate qua GitHub API thành công.
 * Script `KaoriosToolbox109Script.afterCommit` đọc giá trị này để download dex asset.
 *
 * Cùng repo với Toolbox 2036 — chỉ khác tag release.
 */
object Toolbox109Config {
    const val DEFAULT_TAG = "V1.0.9"

    @Volatile
    var currentTag: String = DEFAULT_TAG
        private set

    fun update(tag: String) {
        currentTag = tag.trim().ifEmpty { DEFAULT_TAG }
    }
}
