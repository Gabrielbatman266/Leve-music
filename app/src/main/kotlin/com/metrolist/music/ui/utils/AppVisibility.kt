package com.metrolist.music.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * True while the screen is actually visible (activity STARTED or later).
 * Polling loops keyed on this stop when the app goes to background or the screen turns off,
 * which `delay`-based loops otherwise keep running and waste battery.
 */
@Composable
fun rememberIsAppVisible(): State<Boolean> {
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(lifecycleState) {
        derivedStateOf { lifecycleState.value.isAtLeast(Lifecycle.State.STARTED) }
    }
}
