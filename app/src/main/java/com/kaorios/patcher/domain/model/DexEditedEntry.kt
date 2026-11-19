package com.kaorios.patcher.domain.model

import kotlinx.serialization.Serializable

/** Schema item của `dex_edited.json`. */
@Serializable
data class DexEditedEntry(
    /** 0 = chưa chỉnh (bỏ qua khi build), 1 = sẽ build lại từ smali. */
    val value: Int = 0,
    /** Phiên bản dex (kết quả của DexUtil.verifyDexHeader). Default 39 nếu lỗi. */
    val version: Int = 39
)

/** Map `<dexName>` (ví dụ `classes`, `classes2`) → entry. */
typealias DexEditedMap = Map<String, DexEditedEntry>
