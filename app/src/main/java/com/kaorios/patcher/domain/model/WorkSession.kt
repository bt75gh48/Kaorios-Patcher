package com.kaorios.patcher.domain.model

import kotlinx.serialization.Serializable

/** Mô tả 1 jar đã nạp vào workspace. */
@Serializable
data class JarSlot(
    /** Tên luận lý: `framework`, `services`, hoặc tên jar do user chọn (không .jar). */
    val logicalName: String,
    /** Tên file gốc (ví dụ `framework.jar`). */
    val originalFileName: String,
    /** Thư mục đã unzip raw (ví dụ `framework_jar_decomp`). */
    val jarDecompDir: String,
    /** Thư mục chứa smali đã decompile (ví dụ `framework_dex_decomp`). */
    val dexDecompDir: String
)

/** Metadata của 1 work session (1 lần user vào 1 mode). */
@Serializable
data class WorkSession(
    val mode: WorkMode,
    val workDir: String,
    val slots: List<JarSlot>,
    val createdAt: Long = System.currentTimeMillis()
)
