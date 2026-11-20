# Android TV Remote

A native Android remote-control UI built with Kotlin, Jetpack Compose, and Material 3.

## Build

Open the project in Android Studio or build the debug APK from the repository root:

```shell
./gradlew assembleDebug
```

TVControl discovers Android TV devices using `_androidtvremote2._tcp`, or accepts a
manually entered IP address. Pairing uses the TV's six-character code, Android Keystore
client identity, and a pinned TLS certificate for the subsequent remote-control session.
The app stores paired TV addresses and certificate fingerprints with DataStore. Commands
are sent only while a paired TV session is connected; unsupported actions are reported
instead of being simulated.

The device selector lets you pair, select, and manage TVs. Up to four TV app shortcuts
can be configured in Settings with a package ID, optional TV app launch URI, and an icon
uploaded from the phone or downloaded from an HTTPS image URL. Package IDs alone are not
treated as launch links. Shortcut data and the D-pad/touchpad preference are stored with
DataStore.

Build the debug APK with `./gradlew assembleDebug`. Pairing and command behavior should
be verified against the target TV; TV models may advertise different capabilities.