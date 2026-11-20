package com.jurim007.androidtvremote.domain

interface RemoteController {
    fun send(command: RemoteCommand)
}

