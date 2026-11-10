# Bifröst

**The DS Pokémon games on the AYN Thor, the way its two screens deserve.** The top screen in
16:9 widescreen at 60 fps on the Thor's main display; the DS's touch screen, sharp and filling
the width, on its lower display. Nothing doubled up, no black bars.

Bifröst is a Thor-only build of [Pokémon Fulldex](https://github.com/mamesj/pokemon-fulldex) by
mamesj. Fulldex does the hard part (recompiling the games, the widescreen world, the 60 fps
in-between frames, the game list); Bifröst rearranges its Android app around a dual-screen
handheld. *(In Norse myth, Bifröst is the rainbow bridge between two worlds; here, between
two screens.)*

You bring your own ROMs: the app contains none of the games.

## What's different from Fulldex

| | Fulldex on Android | Bifröst on the Thor |
|---|---|---|
| Main display | one widescreen picture with the touch screen's buttons and menus tucked into it | the widescreen world alone: the bottom screen's pieces stay on their own display |
| Lower display | optional (SECOND SCREEN), the DS screen with black bars | always on: the DS touch screen across the full width, edges continued instead of bars |
| Sharpness | – | "sharp bilinear" scaling: whole-pixel enlargement first, then a fraction of a pixel of smoothing to fit |
| Battles | command buttons in a band along the bottom of the view | the battle scene uncovered; you pick commands on the lower display |
| Screen settings | SCREEN LAYOUT, LARGE SCREEN, ASPECT RATIO, SECOND SCREEN, swap screens | hidden: there is one layout, made for the Thor |
| Heat | 3D world at 720 lines, displays at 120 Hz | 540 lines (half the main display, an even scale) and 60 Hz: a quieter fan |

Everything else (the game list, saves, save states, the pause menu, cheats, RetroAchievements,
controller support) is Fulldex's, unchanged.

**Games:** the DS games Fulldex supports (SoulSilver, HeartGold, Platinum, Diamond, Pearl, Black,
White, Black 2, White 2). Tested on the Thor: SoulSilver, HeartGold and White 2. GBA games play on
the main display only. Fulldex's [supported games list](https://github.com/mamesj/pokemon-fulldex#supported-games)
has the languages and ROM versions.

## Install

There is no download yet: build the APK yourself (below), then:

1. On the Thor, turn on **Developer options** (Settings › About › tap *Build number* 7 times) and
   **USB debugging**, connect it to your computer and allow the prompt.
2. `adb install -r build-android-dist/pokemon-ds-wide-android.apk`
3. Start **Bifröst**, tap the screen, allow access to files and pick the folder with your ROMs
   (any names; e.g. `roms/nds` on the SD card). Tap a game twice, or press A, to play.

**Coming from Fulldex?** Bifröst uses the same app ID, so it replaces Fulldex rather than sitting
beside it, and Android only installs it after Fulldex is uninstalled (it is signed with a
different key). Your saves are safe: they live in the `saves` folder inside your ROMs folder, not
in the app. The app's own settings (`Android/data/io.github.mamesj.pokemonds`) start fresh.

## Build

On macOS or Linux (WSL2 works too). You need git, CMake, Ninja, Python 3 with Pillow, curl, zip,
JDK 17, and the Android SDK with `build-tools;34.0.0`, `platforms;android-34` and
`ndk;27.2.12479018`.

```sh
export ANDROID_SDK=~/Library/Android/sdk     # macOS (Android Studio's); Linux: ~/android-sdk
export SSR_ANDROID_ABIS=arm64-v8a            # the Thor only
bash tools/android/build_all.sh /path/to/soulsilver-us.nds
```

The build reads a **Pokémon SoulSilver (USA)** ROM (game code `IPGE`) once, for the addresses
of its functions; nothing of the game goes into the APK. The first build fetches SDL2 and the
decompilations' symbol files and takes a while; later ones take a few minutes.

The result is `build-android-dist/pokemon-ds-wide-android.apk`. The first build creates a signing
key at `~/.config/pokemon-ds-wide/android-signing-key.json`: **keep it**, as Android only
installs an update over the app if it is signed with the same key.

`FULLDEX_THOR=OFF bash tools/android/build_all.sh …` builds plain Fulldex for Android instead
(with its usual screen layouts; the lower-display improvements stay).

## How it works

- **Main display:** each frame Fulldex picks a *layout*, a list of pieces cut from the two DS
  screens and placed in the widescreen view. With the bottom screen on its own display,
  `MergedTopScreenOnly` (`frontend/merged.h`) drops the bottom screen's pieces and whatever was
  only there to make room for them; Gen 4 battles skip their button band (`frontend/main.cpp`).
- **Lower display:** an Android `Presentation` (`SecondScreen.java`) draws the DS screen.
  `DsScreenGeometry.java` holds the scale shared by drawing and touch, so taps land on the right
  DS pixel.
- **Thor-only settings:** the `FULLDEX_THOR` CMake option (on in `tools/android/build_all.sh`).

## Credits and licence

Bifröst is a modified version of [Pokémon Fulldex](https://github.com/mamesj/pokemon-fulldex)
by mamesj, itself derived from [ssrecomp](https://github.com/ClickingMaster/ssrecomp) and
built on [melonDS](https://melonds.kuribo64.net/); see [NOTICE](NOTICE) for every project it
includes. Like them it is free software under the [GNU GPL v3](LICENSE). The changes made for
Bifröst are in this repository's history and in [CHANGELOG.md](CHANGELOG.md).

For everything Fulldex itself does (its features, settings, the pause menu, troubleshooting),
see [Fulldex's README](https://github.com/mamesj/pokemon-fulldex#readme).

Not affiliated with Nintendo, Game Freak, The Pokémon Company or AYN. Pokémon is a trademark of
Nintendo, Creatures and Game Freak. Please play games you own.
