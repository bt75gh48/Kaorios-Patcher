package com.kaorios.patcher.domain.actions

/**
 * Cấu hình động cho KaoriosToolbox 2.0.3.6 — tag release GitHub user đã xác thực trước khi chạy.
 *
 * `FileBrowserViewModel` set `currentTag` SAU khi validate qua GitHub API thành công.
 * Script `KaoriosToolbox2036Script.afterCommit` đọc giá trị này để download dex asset.
 */
object Toolbox2036Config {
    const val DEFAULT_TAG = "V2.0.6"

    @Volatile
    var currentTag: String = DEFAULT_TAG
        private set

    fun update(tag: String) {
        currentTag = tag.trim().ifEmpty { DEFAULT_TAG }
    }
}
