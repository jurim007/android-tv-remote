package com.jurim007.androidtvremote.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PressAndHoldController(
    private val scope: CoroutineScope,
    private val remoteController: RemoteController,
    private val initialRepeatDelayMs: Long = 420L,
    private val repeatIntervalMs: Long = 145L,
) {
    private var repeatJob: Job? = null

    fun start(command: RemoteCommand) {
        stop()
        repeatJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            remoteController.send(command)
            delay(initialRepeatDelayMs)
            while (isActive) {
                remoteController.send(command)
                delay(repeatIntervalMs)
            }
        }
    }

    fun stop() {
        repeatJob?.cancel()
        repeatJob = null
    }
}

