package com.kaorios.patcher.domain.actions

/**
 * Cấu hình động cho script "Update Kaorios Dex" — tag release GitHub user đã xác thực trước khi chạy.
 *
 * `FileBrowserViewModel` set [currentTag] SAU khi validate qua GitHub API thành công.
 * Script đọc giá trị này trong `afterCommit` để tải dex mới và import vào framework.
 *
 * Cùng repo với KaoriosToolbox — chỉ khác tag release.
 */
object UpdateKaoriosDexConfig {
    const val DEFAULT_TAG = "V2.0.6"

    @Volatile
    var currentTag: String = DEFAULT_TAG
        private set

    fun update(tag: String) {
        currentTag = tag.trim().ifEmpty { DEFAULT_TAG }
    }
}
