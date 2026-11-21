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

Use the device selector to switch TVs or tap **+** to pair. Manage paired TVs under **Remote settings → Devices**. Up to eight TV app shortcuts fit in two rows. In Settings, type an app name to choose
a suggestion or enter a package ID. You can also supply an SVG file or HTTPS SVG URL.
YouTube, Netflix, Prime Video, and Disney+ are included by default. Existing shortcut lists
are kept and can be replaced using **Restore default shortcuts** in Settings.

Known apps use built-in launch links. Other apps use Android TV's package-launch link.
Many package-ID shortcuts need a compatible handler on the TV, including 4K Player on some TCL models.
If a shortcut fails to open, install and open
[AVA Connect](https://play.google.com/store/apps/details?id=com.ava.connect) on the TV.
Complete its on-TV setup before retrying the shortcut. See
[AVA's setup instructions](https://support.ava.com/hc/en-us/articles/38717944348061-Fix-for-Google-TV-Android-TV-App-Launching-Broken-by-Google-s-Play-Store-Update-in-August-2026).

In **Remote settings**, choose D-pad or touchpad mode and show or hide volume, channel,
Mute, Source, and app shortcuts. All optional controls are visible by default. Button
vibration is on by default. Phone volume keys control the TV only when enabled and
connected to a TV that supports volume control.

On TVs that advertise voice support, the microphone appears between Back and Home.
Tap it, allow microphone access on the phone, and speak. TVControl streams the audio
over the paired connection for the TV to handle. Tap Stop to finish. Voice recording
also stops when you leave the remote or after 20 seconds.

Shortcut icons are matched automatically by package ID. Most bundled SVGs come from
[Simple Icons](https://simpleicons.org/), including the
[Prime Video logo from v11](https://cdn.jsdelivr.net/npm/simple-icons@11.0.0/icons/primevideo.svg).
The CloudStream SVG was supplied by the user. Other wordmarks are from Wikimedia Commons:
[Disney+](https://commons.wikimedia.org/wiki/File:Disney%2B_logo.svg),
[ESPN](https://commons.wikimedia.org/wiki/File:ESPN_wordmark.svg),
[Hulu](https://commons.wikimedia.org/wiki/File:Hulu_logo_(2018).svg),
[Peacock](https://commons.wikimedia.org/wiki/File:NBCUniversal_Peacock_Logo_(2026).svg),
[Pluto TV](https://commons.wikimedia.org/wiki/File:Pluto_TV_logo_2024.svg), and
[Viki](https://commons.wikimedia.org/wiki/File:Rakuten_Viki_2022.svg).

Build the debug APK with `./gradlew assembleDebug`. Pairing and command behavior should
be verified against the target TV; TV models may advertise different capabilities.

## Release versions

Use GitHub release tags in the form `vMAJOR.MINOR.PATCH`, starting at `v1.0.0`.

- **Major** for a large change, such as adding support for Tizen.
- **Minor** for a new feature, such as a microphone or automatic updates.
- **Patch** for bug fixes and small styling adjustments.

Build the APK from the release tag before uploading it to GitHub. The About section
shows the version embedded in that APK. GitHub Actions reads the build tag and
repository owner. Local builds use the latest reachable version tag and the owner
from the GitHub `origin` URL. An untagged build starts at `1.0.0`. You can also
set the version with `-PreleaseVersion=1.2.3`.

The GitHub badge uses the GitHub mark from [Simple Icons](https://simpleicons.org/).
