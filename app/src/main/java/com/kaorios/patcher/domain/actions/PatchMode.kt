package com.kaorios.patcher.domain.actions

/** Patch scope compatible with the 2.0.6.2 patcher modes. */
enum class PatchMode {
    ORIGINAL,
    BUILD,
    ALL;

    fun includes(mode: PatchMode): Boolean = this == ALL || this == mode
}
