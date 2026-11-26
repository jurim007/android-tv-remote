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

    /**
     * Called when the app returns to the screen. Replaces a connection that died while the
     * phone was locked, and retries a pending reconnect without waiting out its delay.
     */
    fun checkConnection()

    fun send(command: RemoteCommand)
    fun startHolding(command: RemoteCommand, holdId: Long)
    fun stopHolding(holdId: Long)
    fun close()
}

