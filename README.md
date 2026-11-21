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
can be configured in Settings with a package ID and an optional SVG file or HTTPS SVG URL.
YouTube, Netflix, Prime Video, and Disney+ are included by default. Existing shortcut lists
are kept and can be replaced using **Restore default shortcuts** in Settings.

Known apps use built-in launch links. Other apps use Android TV's package-launch link.
If the TV rejects package launches, install a compatible handler such as
[AVA Connect](https://play.google.com/store/apps/details?id=com.ava.connect) on the TV.
If prompted when tapping the shortcut, select that handler and choose **Always**.

The bundled YouTube and Netflix SVGs are from [Simple Icons](https://simpleicons.org/).
The [Prime Video SVG](https://cdn.jsdelivr.net/npm/simple-icons@11.0.0/icons/primevideo.svg)
is from Simple Icons v11 and the [Disney+ SVG](https://commons.wikimedia.org/wiki/File:Disney%2B_logo.svg)
is from Wikimedia Commons.

Build the debug APK with `./gradlew assembleDebug`. Pairing and command behavior should
be verified against the target TV; TV models may advertise different capabilities.
