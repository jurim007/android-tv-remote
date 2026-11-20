# Android TV Remote

A native Android remote-control UI built with Kotlin, Jetpack Compose, and Material 3.

## Build

Open the project in Android Studio or build the debug APK from the repository root:

```shell
./gradlew assembleDebug
```

The app currently uses a development mock for remote commands. TV discovery, pairing,
and network control are not implemented, and the UI reports that no TV is connected.
Configure up to four TV app shortcuts from the device selector's Settings menu. Each
shortcut stores the TV package ID and an icon uploaded from the phone or downloaded from
an HTTPS image URL. Shortcut data and the D-pad/touchpad preference are saved with DataStore.
App launch commands still use the development mock until TV connectivity is implemented.