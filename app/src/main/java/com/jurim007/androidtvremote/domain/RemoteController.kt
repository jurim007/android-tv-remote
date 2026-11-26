package com.jurim007.androidtvremote.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RemoteController {
    val connectionState: StateFlow<TvConnectionState>
    val discoveredDevices: StateFlow<List<TvDevice>>
    val messages: SharedFlow<String>

    fun startDiscovery()
    fun stopDiscovery()
    /**
     * Starts pairing. Returns the paired device when the TV approves the connection without a
     * code (webOS), or null when [completePairing] must be called with the code shown on the TV.
     */
    suspend fun beginPairing(device: TvDevice): TvDevice?
    suspend fun completePairing(pin: String, deviceName: String): TvDevice
    suspend fun connect(device: TvDevice)
    suspend fun cancelPendingPairing()
    suspend fun disconnect()

    fun send(command: RemoteCommand)
    fun startHolding(command: RemoteCommand, holdId: Long)
    fun stopHolding(holdId: Long)
    fun close()
}

