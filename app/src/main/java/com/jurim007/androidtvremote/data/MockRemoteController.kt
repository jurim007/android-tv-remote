package com.jurim007.androidtvremote.data

import android.util.Log
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.RemoteController

class MockRemoteController : RemoteController {
    override fun send(command: RemoteCommand) {
        val name = when (command) {
            is RemoteCommand.SendText -> "SendText(length=${command.text.length})"
            is RemoteCommand.LaunchApp ->
                "LaunchApp(packageName=${command.packageName})"
            else -> command.toString()
        }
        Log.d(TAG, "Development mock command: $name")
    }

    private companion object {
        const val TAG = "AndroidTvRemote"
    }
}

