# Stream Goat

<p align="center"><img src="packaging/linux/cloudstream-linux.png" width="128" alt="Stream Goat logo: a white goat head on black"></p>

<p align="center"><b>Movies, series, anime and Live TV on Android phones, tablets and TV, Windows, Mac, Linux and the web.</b><br>
Created by <b>Syed Ali Hamza</b></p>

<p align="center"><a href="https://github.com/sherazi5110/Goat-HomeApp"><img src="https://img.shields.io/badge/%F0%9F%93%BA%20Goat%20Home%20App-Try%20now-b5f23d?style=for-the-badge" alt="Goat Home App: try now"></a><br>
<sub>An ad-free home screen for Android TV and projectors, with CloudStream extensions and Live TV built in.</sub></p>

![Stream Goat: Home](docs/screenshots/home.jpg)

> **Testing notes (11.0.0).** The Mac and Linux apps are built and checked automatically but haven't been tested by hand yet, so please report anything odd. On Windows the first launch after installing can be slow and laggy for a minute while the app sets itself up and loads an earlier backup; give it a moment and it runs smoothly after that.

> **Disclaimer.** Stream Goat is only a media player and organiser. It does not host, provide, link to or endorse any movies, shows, channels or other content, and it ships with no content sources. You add your own extensions, add-ons and playlists and are responsible for having the rights to whatever you play. Titles, posters and logos belong to their owners and appear in the screenshots only to show how the app looks.

Stream Goat runs **CloudStream extensions and Stremio add-ons**, from the same repositories the official CloudStream
Android app uses, with no changes to the extensions. *(Formerly "CloudStream for Linux"; your extensions, profiles,
library and downloads carry over.)*

> [!NOTE]
> **This is a personal project, and it isn't perfect.** I add features and fix things over time, so some parts may
> not work as expected yet. If something breaks or you have an idea, please
> **[report it / create an issue](https://github.com/sherazi5110/stream-goat/issues/new)**. In the app you can also use
> *Report a glitch* (Settings → About, shake your phone, or F12 on a computer), which fills in your version, device and a screenshot for you.

<p align="center">
<a href="https://sherazi5110.github.io/stream-goat/"><img src="https://img.shields.io/badge/Website-e11d48?style=for-the-badge" alt="Website"></a>
<a href="https://github.com/sherazi5110/stream-goat/releases/latest"><img src="https://img.shields.io/badge/Download-16a34a?style=for-the-badge&logo=github&logoColor=white" alt="Download"></a>
<a href="https://sherazi5110.github.io/stream-goat/app/"><img src="https://img.shields.io/badge/Open%20the%20web%20version-2563eb?style=for-the-badge" alt="Open the web version"></a>
<a href="INSTALL.md"><img src="https://img.shields.io/badge/Full%20install%20guide-7c3aed?style=for-the-badge" alt="Full install guide"></a>
<a href="https://discord.gg/PUmEQzJSa"><img src="https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord"></a>
<a href="https://github.com/sherazi5110/stream-goat/releases/tag/test-build"><img src="https://img.shields.io/badge/Test%20pre--release-f59e0b?style=for-the-badge" alt="Test pre-release"></a>
</p>


## What it does

> **New in 11.0.0:** a big **WATCHED** stamp and Continue / New episodes badges matched across every extension,
> **one Google Drive backup** that keeps phone, TV and computer in sync, a **TV remote** that stays where you are (Back
> returns to the same place, every button reachable), and sound for **Dolby / DTS** films on every phone.
> [All changes](docs/releases/11.0.0.md) · 10.0.1 brought the **mpv** player on computers and small updates inside the
> app ([10.0.1 changes](docs/releases/10.0.1.md)).

- **One app on every screen:** Android phones, tablets and TV, Windows, Mac, Linux and the web, with the same look.
- **Your own sources:** CloudStream extensions and repositories, Stremio add-ons, and Live TV from M3U playlists,
  Xtream Codes logins or the free iptv-org lists by country. The app comes with none: you add what you use.
- **Live TV** with a TV guide, favourites, region-lock detection and an automatic check that hides channels with broken
  links.
- **Sports and music:** live scores for every big sport with news, and a music section with moods, playlists, liked
  songs and recommendations. YouTube's TV app (ads removed) has its own tab.
- **Two players (ExoPlayer and VLC):** subtitles from the source or online, subtitle style and sync, audio sync, translation of
  subtitles, skip intro, next-episode countdown, anime enhance and HDR.
- **Downloads** for offline watching, with fast multi-connection downloading and quality choice.
- **Profiles** with animal pictures, PINs and kids profiles.
- **Look:** themes including Liquid Glass, accent colours, the goat logo in rainbow or your theme colour, and a start
  animation over glowing waves.
- **Sync and backup:** WeTrakr scrobbling (now watching and watched at 80%), Google Drive backup every few minutes, and
  a backup file that moves between devices; restoring brings back your extensions too.
- **Metadata:** TMDB ratings, backdrops, cast and trailers, IMDb ratings and AniList scores.
- **Report a glitch:** shake the phone, press F12 (⌘⇧R on a Mac) or use the menu. It opens a filled-in GitHub issue
  with a screenshot; nothing is sent by the app. If the app closed unexpectedly, it offers the report next time.

## Screenshots

| | |
|---|---|
| ![Home](docs/screenshots/home.jpg) | ![Start animation](docs/screenshots/intro.jpg) |
| ![Sports: live scores](docs/screenshots/sports.jpg) | ![Music, with the player beside the list](docs/screenshots/music.jpg) |
| ![Live TV](docs/screenshots/livetv.jpg) | ![YouTube](docs/screenshots/youtube.jpg) |
| ![Updates inside the app](docs/screenshots/update.jpg) | ![Updates and backup](docs/screenshots/settings-updates.jpg) |
| ![About](docs/screenshots/about.jpg) | |

## Download for your device

Every file is on the **[latest release](https://github.com/sherazi5110/stream-goat/releases/latest)** page.

### Windows 10 and 11

| File | What it is |
|---|---|
| `streamgoat-windows-<version>.msi` | **Installer** (recommended). Installs for your user, no admin password; Start-menu and desktop shortcuts. |
| `streamgoat-windows-<version>-portable.zip` | **Portable**: unzip anywhere (even a USB stick) and run `Stream Goat\Stream Goat.exe`. |

Java and **VLC are included**, so there's nothing else to install. The installer isn't code-signed yet: if Windows says
*"Windows protected your PC"*, click **More info → Run anyway**. Your data lives in `%APPDATA%\Stream Goat`; installing
a newer `.msi` updates in place. [More: INSTALL.md → Windows](INSTALL.md)

### Android phones, tablets and Android TV

| File | For |
|---|---|
| `streamgoat-android-arm64-v8a.apk` | Most phones and tablets from the last ~8 years |
| `streamgoat-android-armeabi-v7a.apk` | Android TV, Google TV, Fire TV Stick and most TV boxes (the Google TV Streamer can also use arm64-v8a) |
| `streamgoat-android-universal.apk` | Not sure? This one runs everywhere (larger download) |

Open the `.apk` on the device and allow *Install unknown apps* when asked. On a TV (Fire TV Stick, Google TV, Android TV, TV boxes), install the **Downloader** app and
type the code **4564305** (TV version) or **2193283** (universal, any device), or use `adb install`. Both codes always
get the newest version. The app has a touch layout for phones and a remote-friendly layout for TV (a mouse works too),
ExoPlayer and VLC, and real **HDR** on HDR screens. **Updating:** 10.0.0 and later install new versions from inside the
app (Settings → Updates and backup); coming from 9.x, uninstall once first (back up, then restore). [More: INSTALL.md → Android](INSTALL.md#android-phones-tablets-and-android-tv)

**Google Play Protect warning or block:** on some devices Play Protect says Stream Goat "can install harmful apps",
blocks the install or its updates, or later disables or removes the app by itself (it "disappears" from the TV). That's
because Stream Goat isn't on the Play Store, installs its own updates (Android's "install unknown apps" permission) and
downloads extensions, like CloudStream; Play Protect flags apps that work this way. The app is open source: its code is
here for anyone to read. To install it anyway:
1. At the warning, press **More details → Install anyway**.
2. Still blocked, or the app keeps disappearing? Turn off Play Protect's scanning, then install again:
   **phone/tablet:** Play Store → profile picture → Play Protect → ⚙ → turn off *Scan apps with Play Protect*;
   **Google TV / Android TV:** Play Store → profile → Settings → Google Play Protect → turn off scanning.
3. App gone from the TV? Settings → Apps → See all apps: if it's there but disabled, press **Enable**; otherwise install it again.

Turning Play Protect off is your choice (it also stops checking other apps); turned back on, it may disable or remove
Stream Goat again. Only download Stream Goat from this GitHub page or [the website](https://sherazi5110.github.io/stream-goat/).

### Mac (Apple Silicon, from 9.0.1)

| File | For |
|---|---|
| `streamgoat-macos-arm64-<version>.dmg` | Macs with an M1 chip or newer. Open it and drag **Stream Goat** to Applications. |

The app isn't signed with an Apple certificate yet: the first time, macOS blocks it. Open **System Settings → Privacy &
Security**, scroll down and click **Open Anyway** (once).

### Web version

No install: open **[the web version](https://sherazi5110.github.io/stream-goat/app/)** in any browser, or add it to
your iPhone's home screen. It plays Stremio add-ons with a full player.

### Linux

| File | For |
|---|---|
| `streamgoat-linux_<version>_amd64.deb` | Ubuntu, Debian, Mint, Pop!_OS, Zorin, ChromeOS: `sudo apt install ./streamgoat-linux_<version>_amd64.deb` |
| `streamgoat-linux.flatpak` | Any distro with Flatpak (Fedora, Arch, openSUSE, Steam Deck): `flatpak install --user streamgoat-linux.flatpak` |
| `streamgoat-linux-<tag>.tar` | Any Linux with Java 21 and VLC already installed |

The `.deb` and Flatpak bring everything they need (VLC is installed with the `.deb`). The package is still named
`cloudstream-linux`, so a new `.deb` updates an older install. Run it as **Stream Goat** from your app menu, or
`streamgoat` in a terminal. [More: INSTALL.md → Linux](INSTALL.md)

## More

- [INSTALL.md](INSTALL.md): installing on every system, and building from source.
- [docs/](docs/): setup guides (Google Drive, WeTrakr, server mode) and release notes.
- Questions and ideas: the [Discord](https://discord.gg/PUmEQzJSa) or [GitHub issues](https://github.com/sherazi5110/stream-goat/issues).

## Support

Stream Goat is free, with no ads and no tracking. If you enjoy it, you can support it here:

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-%E2%98%95-ffdd00?style=for-the-badge&labelColor=1a1205)](https://buymeacoffee.com/syedalihamza)
[![Join the Discord](https://img.shields.io/badge/Discord-Join%20the%20community-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/PUmEQzJSa)

## License

GPL-3.0, the same as CloudStream, whose library sources this project compiles.

The built-in player uses [libVLC](https://www.videolan.org/vlc/libvlc.html) (LGPL-2.1, from your system's VLC packages) through [vlcj](https://github.com/caprica/vlcj) (GPL-3.0). Online subtitles come from the OpenSubtitles Stremio addon.

Made by Tennant Creek.
