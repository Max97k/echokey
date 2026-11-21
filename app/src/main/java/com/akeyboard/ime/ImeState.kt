package com.akeyboard.ime

enum class ImeErrorReason {
    NO_KEY,
    INVALID_KEY,
    NO_PERMISSION,
    NO_NETWORK,
    QUOTA_EXCEEDED,
    REGION_NOT_SUPPORTED,
    PASSWORD_FIELD,
    MIC_BUSY,
    MAX_DURATION,
    GENERIC
}

sealed class ImeState {
    data object Idle : ImeState()
    data object Warm : ImeState()
    data object Recording : ImeState()
    data object Finalizing : ImeState()
    data class Error(val reason: ImeErrorReason) : ImeState()
}
