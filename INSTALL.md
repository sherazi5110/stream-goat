# Install Stream Goat

Everything is on the [Releases page](https://github.com/sherazi5110/stream-goat/releases). Pick the file for
your device:

| Your device | File | Section |
|---|---|---|
| Windows 10 or 11 | `streamgoat-windows-<version>.msi` (or the portable `.zip`) | [Windows](#windows-10-and-11) |
| Ubuntu, Debian, Mint, Pop!_OS, Zorin, elementary, ChromeOS (Linux) | `streamgoat-linux_<version>_amd64.deb` | [.deb](#ubuntu-debian-linux-mint-popos-zorin-elementary-os-chromeos-linux) |
| Any Linux distro with Flatpak (Fedora, Arch, openSUSE, Steam Deck…) | `streamgoat-linux.flatpak` | [Flatpak](#flatpak-any-distro) |
| Other x86-64 Linux without Flatpak | the `.deb` (unpacked by hand) | [Other Linux](#fedora-arch-manjaro-opensuse-and-other-x86-64-linux-without-flatpak) |
| Linux, if you already have Java 21 | `streamgoat-linux-<tag>.tar` | [.tar](#the-tar-file-java-21-already-installed) |
| Android phone or tablet | `streamgoat-android-arm64-v8a.apk` | [Android](#android-phones-tablets-and-android-tv) |
| Android TV, Google TV, Fire TV, TV boxes | `streamgoat-android-armeabi-v7a.apk` (or arm64) | [Android TV](#android-tv-google-tv-fire-tv) |
| Not sure which Android file | `streamgoat-android-universal.apk` (bigger, runs on all) | [Android](#android-phones-tablets-and-android-tv) |

The Linux `.deb` and `.flatpak` include their own Java, so you don't need to install Java.

Requirements for the Linux app:
- a 64-bit PC (x86-64 / amd64);
- **VLC** (the built-in player uses VLC's engine);
- a desktop session (GNOME, KDE, Xfce, Cinnamon, …).

---

## Windows 10 and 11

The Windows app is the same app as on Linux (same look, profiles, extensions, Stremio add-ons, Live TV, downloads),
with its own Java and **VLC included**: nothing else to install. 64-bit Windows 10 or 11.

**Installer (recommended)**
1. From the [Releases page](https://github.com/sherazi5110/stream-goat/releases/latest), download
   `streamgoat-windows-<version>.msi` and open it.
2. If Windows shows **"Windows protected your PC"** (the installer isn't code-signed yet), click **More info →
   Run anyway**.
3. It installs for your user (no admin password) and adds **Stream Goat** to the Start menu and the desktop.

**Portable:** download `streamgoat-windows-<version>-portable.zip`, unzip it anywhere (e.g. a USB stick) and run
`Stream Goat\Stream Goat.exe`.

- **Update:** install the new `.msi`; your data is kept.
- **Remove:** Settings → Apps → Installed apps → Stream Goat → Uninstall. Your data stays in `%APPDATA%\Stream Goat`
  (delete that folder too to remove everything).
- **Data:** `%APPDATA%\Stream Goat` (extensions, profiles, library, settings). Downloads go to your Downloads folder,
  in `Stream Goat`.
- **Command line:** `streamgoat-cli.exe` next to `Stream Goat.exe` runs the same commands as on Linux
  (`streamgoat-cli doctor`, `repo add`, `install`, `backup`, `restore`).
- `cloudstreamrepo://` repository links on websites open in Stream Goat after you've started it once.

---

## Ubuntu, Debian, Linux Mint, Pop!_OS, Zorin, elementary OS, ChromeOS (Linux)

### Install

**Option A: in the terminal (recommended)**
```bash
wget -O /tmp/cloudstream-linux.deb https://github.com/sherazi5110/stream-goat/releases/download/V8.0.1/streamgoat-linux_8.0.1_amd64.deb
sudo apt install /tmp/cloudstream-linux.deb
```

**Option B: with a file you downloaded in the browser**
```bash
cd ~/Downloads
sudo apt install ./streamgoat-linux_8.0.1_amd64.deb
```
Keep the `./`, which tells apt it's a file, not a package name.
apt installs VLC's libraries automatically.

On ChromeOS, first move the downloaded file from **Downloads** to **Linux files** in the
Files app, then use `cd ~` instead of `cd ~/Downloads`.

Then open **Stream Goat** from the app menu, or run `cloudstream-linux`.

> Use `apt`, not `dpkg -i` or double-clicking the file in some file managers:
> `apt` also installs the libraries the app needs.
> A message about `_apt` and "permission denied" when installing from `/tmp` is harmless.

### Update
Download the new `.deb` and install it the same way. Your extensions, history and
settings are kept.

### Remove
```bash
sudo apt remove cloudstream-linux
```
Also delete your extensions, history and settings (optional):
```bash
rm -rf ~/.local/share/cloudstream-linux
```
Remove libraries nothing else uses any more (optional; check the list before typing `Y`):
```bash
sudo apt autoremove
```

---

## Flatpak (any distro)

Flatpak runs the same way on every distro and includes its own copy of VLC, so you don't
need to install VLC. Every release has it as `streamgoat-linux.flatpak`.

```bash
# Once per computer, if Flathub isn't set up yet (it provides the runtime the app needs):
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo

wget -O /tmp/streamgoat-linux.flatpak https://github.com/sherazi5110/stream-goat/releases/latest/download/streamgoat-linux.flatpak
flatpak install --user /tmp/streamgoat-linux.flatpak
flatpak run io.github.sherazi5110.CloudStreamLinux
```
It also appears in your app menu as **Stream Goat**.

- **Update:** download the new `.flatpak` and run the same `flatpak install` line.
- **Remove:** `flatpak uninstall --user io.github.sherazi5110.CloudStreamLinux`
  (add `--delete-data` to also delete extensions, history and settings).

Inside the Flatpak, the app can only open files from your **Downloads** folder (for
subtitle files and side-loaded extensions), and **downloaded videos** are saved in `~/Downloads/Stream Goat`.

From **7.0.0** the Flatpak uses the Freedesktop **25.08** runtime (24.08 is end of life). Installing or updating
downloads it from Flathub once, so the Flathub line above must have been run. Afterwards you can remove the old one
with `flatpak uninstall --user --unused`.

---

## Fedora, Arch, Manjaro, openSUSE and other x86-64 Linux (without Flatpak)

These systems don't install `.deb` packages, but the app inside the `.deb` runs on them.
These steps unpack it into your home folder; no root access is needed except to install VLC.

### 1. Install VLC and the desktop libraries
```bash
# Fedora (if vlc isn't found, enable RPM Fusion first: https://rpmfusion.org/Configuration)
sudo dnf install vlc gtk3 libXtst mesa-libGL binutils

# Arch / Manjaro / EndeavourOS
sudo pacman -S --needed vlc gtk3 libxtst mesa binutils

# openSUSE (the Packman repository gives VLC all video codecs)
sudo zypper install vlc gtk3 libXtst6 Mesa-libGL1 binutils
```

### 2. Unpack the app
```bash
cd /tmp
wget -O cloudstream-linux.deb https://github.com/sherazi5110/stream-goat/releases/download/V8.0.1/streamgoat-linux_8.0.1_amd64.deb
mkdir -p cs-unpack && cd cs-unpack
ar x ../cloudstream-linux.deb
tar -xJf data.tar.xz
mkdir -p ~/.local/opt
rm -rf ~/.local/opt/cloudstream-linux
mv opt/cloudstream-linux ~/.local/opt/
cd /tmp && rm -rf cs-unpack cloudstream-linux.deb
```

### 3. Add the `cloudstream-linux` command and the app-menu entry
```bash
mkdir -p ~/.local/bin ~/.local/share/applications ~/.local/share/icons
ln -sf ~/.local/opt/cloudstream-linux/bin/cloudstream-linux ~/.local/bin/cloudstream-linux
cp ~/.local/opt/cloudstream-linux/lib/cloudstream-linux.png ~/.local/share/icons/cloudstream-linux.png
cat > ~/.local/share/applications/cloudstream-linux.desktop <<EOF
[Desktop Entry]
Type=Application
Name=Stream Goat
GenericName=Media Center
Comment=Run CloudStream extensions on Linux
Exec=$HOME/.local/opt/cloudstream-linux/bin/cloudstream-linux %u
Icon=$HOME/.local/share/icons/cloudstream-linux.png
Terminal=false
Categories=AudioVideo;Video;Player;
MimeType=x-scheme-handler/cloudstreamrepo;
StartupWMClass=com.lagradost.cloudstream3.linux.ui.DesktopApp
EOF
update-desktop-database ~/.local/share/applications 2>/dev/null || true
```
Open **Stream Goat** from the app menu. If the menu entry or the command doesn't show up
straight away, log out and back in once.

### Update
Repeat step 2 with the new `.deb`. Steps 1 and 3 only need to be done once.

### Remove
```bash
rm -rf ~/.local/opt/cloudstream-linux ~/.local/bin/cloudstream-linux \
       ~/.local/share/applications/cloudstream-linux.desktop ~/.local/share/icons/cloudstream-linux.png
```
Also delete your extensions, history and settings (optional):
```bash
rm -rf ~/.local/share/cloudstream-linux
```

---

## The `.tar` file (Java 21 already installed)

`streamgoat-linux-<tag>.tar` is the plain Java build, without its own Java. Use it if you already have Java 21 and
VLC, or want to run the app without installing anything:
```bash
sudo apt install openjdk-21-jre libvlc5 vlc-plugin-base   # or your distro's Java 21 and VLC packages
tar -xf cloudstream-linux-*.tar
./cloudstream-linux/bin/cloudstream-linux
```
To update, unpack the new `.tar` over the old folder. To remove, delete the folder (your data is in
`~/.local/share/cloudstream-linux`).

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "The built-in player needs VLC's libraries" | Install VLC (see above), then restart the app. |
| `E: Unsupported file … given on commandline` | You typed the file name without `./` in front; use `sudo apt install ./streamgoat-linux_8.0.1_amd64.deb`. |
| `ar: command not found` | Install `binutils` (included in the step 1 commands). |
| A site doesn't load | Settings → Network & DNS → pick a DNS-over-HTTPS provider. |
| An extension misbehaves | Settings → Developer & logs → **Test** next to it, then copy the log into a bug report. |

ARM computers (Raspberry Pi, ARM Chromebooks, Apple Silicon Linux) aren't supported by this
release.

---

## Android: phones, tablets and Android TV

The Android app has the same look and features as the Linux app (Home, Search, Live TV, profiles, Stremio add-ons,
TMDB and the VLC player), with a touch layout for phones and a remote layout for TV. It's **in testing**: expect
rough edges, and please report what you find.

It installs **next to** the official CloudStream app (it has its own app ID), so you can keep both. Extensions are the
same ones: add your repositories under Extensions.

### Which file?

| File | For |
|---|---|
| `streamgoat-android-arm64-v8a.apk` | most phones and tablets from the last ~8 years, newer TVs (Shield, Chromecast with Google TV, Fire TV 4K Max) |
| `streamgoat-android-armeabi-v7a.apk` | older or cheaper TV boxes and sticks (most Fire TV Sticks, many Android TV boxes) |
| `streamgoat-android-universal.apk` | any device; about twice the size. Use it when you're not sure |
| `streamgoat-android-x86_64.apk`, `-x86.apk` | emulators and a few Chromebooks |

If Android says "App not installed", the file doesn't match your device: try the universal one.
Needs Android 6.0 or newer.

### Phone or tablet

1. Open the [Releases page](https://github.com/sherazi5110/stream-goat/releases) on the phone and download the `.apk`.
2. Open it from the notification or the **Downloads** app.
3. When Android asks, allow **Install unknown apps** for your browser or file manager, go back and tap **Install**.
4. Open **Stream Goat**, go to **More → Extensions**, add a repository and install extensions.

### Android TV, Google TV, Fire TV

**With the Downloader app (easiest):**
1. Install **Downloader** (by AFTVnews) from the TV's app store.
2. Allow it to install apps:
   - **Fire TV:** Settings → My Fire TV → Developer options → Install unknown apps → Downloader → On.
     (If Developer options is missing: Settings → My Fire TV → About → click the device name 7 times.)
   - **Android TV / Google TV:** Settings → Apps → Security & restrictions → Unknown sources → Downloader → On.
3. In Downloader, enter the `.apk` link from the Releases page (right-click the file on a computer → *Copy link*),
   or `https://github.com/sherazi5110/stream-goat/releases/latest/download/streamgoat-android-armeabi-v7a.apk`,
   and choose **Install**.

**Other ways:** copy the `.apk` to a USB stick and open it with a file manager on the TV, or send it with the
*Send files to TV* app.

The app shows the TV layout automatically on TVs. If a box picks the wrong one, change it under
**Settings → Appearance → Layout**.

### From a computer (adb)

With USB or network debugging turned on in the device's developer options:
```bash
adb install -r streamgoat-android-arm64-v8a.apk
```

### Downloads on Android

Downloaded movies and episodes are saved in the app's own folder
(`Android/data/io.github.sherazi5110.cloudstream/files/Movies/Stream Goat`); no storage permission is needed.
On Android 13+ the app asks once to show notifications, for the download progress (downloads work without it).
**Uninstalling the app deletes the downloads**; updating keeps them.

### Update

Download the new `.apk` and install it the same way; your extensions, profiles, history and downloads are kept.
If Android refuses with "App not installed" or "package conflicts", the new file was signed with a different key:
uninstall the old app first (this deletes its data), then install the new one.

### Remove

Settings → Apps → Stream Goat → Uninstall (or long-press the icon → Uninstall).
