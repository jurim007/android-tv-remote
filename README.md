# Android TV Remote

A native Android remote-control UI built with Kotlin, Jetpack Compose, and Material 3.

## Build

Open the project in Android Studio or build the debug APK from the repository root:

```shell
./gradlew assembleDebug
```

The app currently uses a development mock for remote commands. TV discovery, pairing,
and network control are not implemented, and the UI reports that no TV is connected.
Installed app shortcuts launch their apps on the phone; shortcut selections are saved
with DataStore on the phone.