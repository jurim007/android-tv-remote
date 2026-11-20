package com.jurim007.androidtvremote.domain

class PressAndHoldController(
    private val remoteController: RemoteController,
) {
    private var activeHoldId: Long? = null
    private var nextHoldId = 0L

    fun start(command: RemoteCommand) {
        stop()
        val holdId = ++nextHoldId
        activeHoldId = holdId
        remoteController.startHolding(command, holdId)
    }

    fun stop() {
        val holdId = activeHoldId ?: return
        activeHoldId = null
        remoteController.stopHolding(holdId)
    }
}

