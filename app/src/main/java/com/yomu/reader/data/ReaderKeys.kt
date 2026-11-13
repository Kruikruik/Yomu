package com.yomu.reader.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Touches de volume transmises au lecteur (+1 = page suivante, -1 = page précédente). */
object ReaderKeys {
    @Volatile
    var active: Boolean = false

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val events: SharedFlow<Int> = _events

    fun send(direction: Int): Boolean = _events.tryEmit(direction)
}
