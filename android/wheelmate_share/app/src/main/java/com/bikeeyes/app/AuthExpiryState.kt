package com.bikeeyes.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AuthExpiryState {
    private val _expired = MutableStateFlow(false)
    val expired = _expired.asStateFlow()

    fun notifyExpired() {
        _expired.value = true
    }

    fun acknowledge() {
        _expired.value = false
    }
}
