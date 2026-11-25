# DeltaDroid

An Android (Kotlin) take on [Delta](https://github.com/rileytestut/Delta), the multi-system iOS emulator by Riley Testut, with the same skin-based look and feel.

> DeltaDroid is an independent project. It is not affiliated with, endorsed by, or supported by Delta or Riley Testut.

## Features

- Multi-system library: NES, SNES, Game Boy / Game Boy Color, Game Boy Advance, Nintendo 64, Nintendo DS and Sega Genesis / Mega Drive.
- Delta controller skins (`.deltaskin`) rendered in portrait and landscape, including edge-to-edge variants.
- Game titles from openvgdb (matched by ROM SHA-1) and box art from libretro-thumbnails, with openvgdb's cover URL as fallback.
- Multitouch, d-pad diagonals, haptic feedback, N64 analog stick and C-buttons, DS touch screen, and physical gamepad support.
- Rotation without restarting emulation.
- Battery saves (SRAM) are stored automatically.
- Pause menu (MENU button on the skin, or Back) with save/load states, fast forward (3x), reset, screenshots, cheats, sustained buttons and mute.
- Material Design 3 UI in dark mode, tinted per system.

## Supported systems

| System | Libretro core | ROM extensions |
| --- | --- | --- |
| NES | fceumm | `nes`, `fds` |
| SNES | snes9x | `sfc`, `smc` |
| Game Boy / Game Boy Color | gambatte | `gb`, `gbc` |
| Game Boy Advance | mGBA | `gba` |
| Nintendo 64 | mupen64plus_next_gles3 | `n64`, `z64`, `v64` |
| Nintendo DS | melonDS | `nds` |
| Sega Genesis / Mega Drive | genesis_plus_gx | `md`, `gen`, `smd`, `bin` |

Cores are run through [LibretroDroid](https://github.com/Swordfish90/LibretroDroid). Delta's iOS emulator cores cannot run on Android, so DeltaDroid uses libretro cores instead.

## Requirements

- Android device with an **arm64-v8a** processor.
- Android 7.0 (API 24) or newer.
- To build: JDK 17 and Android SDK 34.
- Your own legally obtained ROMs, and (optionally) a copy of the Delta IPA to extract the skins and database from.

## Build

DeltaDroid does not include the Delta skins, the game database, or the libretro cores. Fetch them once from your own copy of the Delta IPA:

```sh
tools/fetch_assets.sh /path/to/Delta.ipa
```

The script does the following:

- Downloads the seven libretro arm64 cores from `buildbot.libretro.com` into `app/src/main/jniLibs/arm64-v8a`.
- Extracts Delta's Standard skins and `openvgdb.sqlite` from the IPA into `app/src/main/assets`.

Then build the debug APK:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The fetched assets are gitignored and must not be committed.

## Usage

1. Open DeltaDroid and tap **+** to import ROMs or `.deltaskin` skins using the Android file picker.
2. Browse the library with one swipeable page per system (use the page dots to switch systems). Use the cover grid or search to find a game.
3. Tap a game to start it. Press **MENU** on the skin, or **Back**, to open the pause menu.
4. To remove a game, long-press it in the library. Its saves are deleted with it.

Settings include haptic feedback and controller opacity in landscape.

### Pause menu

- **Save state / Load state**: one save-state slot per game.
- **Fast forward**: 3x speed.
- **Reset**, **Screenshot** (saved to `Pictures/DeltaDroid` on Android 10+, to the app folder on older versions).
- **Cheats**: add, enable and disable cheat codes per game. Game Genie, Action Replay and GameShark codes are supported as each libretro core allows.
- **Hold buttons**: tap a button to keep it pressed, tap again to release it. Turning the mode off releases all buttons.
- **Mute**.
- **Exit** and **Resume**.

## Project structure

All source files are in `app/src/main/java/com/deltadroid/`:

- `GameSystem.kt`: systems, libretro core and key mapping.
- `SkinView.kt`: `.deltaskin` renderer and touch input.
- `GameActivity.kt`: emulation host and pause menu.
- `LibraryActivity.kt`: Compose library screen.
- `Library.kt`: import, metadata and covers.
- `Ui.kt`: theme, settings and pause sheets.
- `Cheats.kt`: cheat code management.

## Known limitations

- arm64-v8a only.
- One save-state slot per game.
- ROMs inside `.zip` archives are not supported.
- Box art and metadata lookups need an internet connection.
- Tested so far only on a real device, with GB and NES test ROMs.

## Credits and legal

- **Delta** © Riley Testut, licensed under AGPL-3.0. DeltaDroid reuses Delta's `.deltaskin` format, its openvgdb-based game database, and its app icon as the launcher icon (from the [Delta repository](https://github.com/rileytestut/Delta)).
- **LibretroDroid** by Filippo Scognamiglio, licensed under GPL-3.0.
- **libretro cores** and their authors.
- **OpenVGDB**, for game metadata.
- **libretro-thumbnails**, for box art.

DeltaDroid does not distribute ROMs, BIOS files, or Delta skins and databases. You are responsible for the games you use, and you must have a legal right to them.

## License

DeltaDroid is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE). The GPL-3.0 license is required because LibretroDroid is GPL-3.0 licensed.
