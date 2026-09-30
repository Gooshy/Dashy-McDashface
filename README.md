# Dashy McDashface

> **Unofficial, and entirely at your own risk.** Not affiliated with or endorsed by Omoda, Jaecoo, Chery, Desay SV or any other maker. It reads undocumented parts of the car and can change a driving setting. No warranty, no liability: read the [Disclaimer](#disclaimer) before you install it.

A live driver's dashboard for the Omoda 9 plug-in hybrid's centre screen (Desay SV head unit, Android 11, 1920 × 720). The look is F1 onboard telemetry crossed with Knight Rider.

## What's on screen
The "Omoda Dash" Claude Design project (`design/Omoda Dash.dc.html`, the name it has in Claude Design), rebuilt in Compose at 1920 × 720, with three swipeable pages:
- **RACE:** speed, a g-force circle with a 2-second trail, the 0–60 timer, the power / regen bar, gear, mode, brake, and the shift lights.
- **ENERGY:** battery %, EV range, consumption, regen against drive time, and the engine panel.
- **CAR:** tyres, steering, yaw, outside temperature, session bests, and trip data.

It has no investigation code: no recording, discovery sweep, export or files. `LiveFeed` reads only the identified channels while the app is on screen:
- The VDBus modules holding identified channels are subscribed.
- The fast-moving channels are polled at 10 Hz and the rest at 1 Hz.
- Speed, gear and the brake pedal come from the car API.

## Header controls
- Tap the clock for the day / night theme.
- DEMO plays the design's scripted 60-second loop. It switches on by itself when there's no car bus, for example off the car. "THIS DRIVE" and the peak markers cover the time since the dash opened.

## Regen
tap the regen level tile in the bottom row to step the car's energy recovery level: Low → Medium → High → Low.
- This is the dash's only write to the car: one command, `NEW_ENERGY` 37, sent by `RegenControl.kt`.
- It sends 1, 2 or 3, while the car reports the same levels as 2, 1 and 0.
- Each tap steps from the level the car last reported. The tile shows `→ HIGH` until the car confirms, or `REGEN ✗` if the car doesn't confirm within 4 s.
- It never retries, and it does nothing in DEMO or with NO DATA.

## States and saved data
- If the car goes quiet for over 2 s, the pages dim and the DATA lamp shows NO DATA.
- Personal bests are kept on the car and drive the purple / green / yellow timing colours.

## Power (kW) is estimated
 The car's own kW figure never reaches Android, so `PowerEstimate.kt` works it out from:
- speed;
- the head unit's accelerometer, which includes hills;
- drag and rolling resistance;
- two efficiency factors fitted against the battery % on one drive.

It matched the battery's real draw over 5 s windows at r = 0.98 on the drive it was fitted on, and r = 0.984 on a later one. The bar runs from −60 kW regen to +250 kW drive, and the shift lights fill at 250 kW. With the engine running it shows the total the car is putting down, not just the battery's share.

## Waiting on data from the car
- Accelerator pedal position isn't on the bus.
- RPM shows while the engine runs, but the car only updates it every 5 s.
- Drive modes show their number (`MODE 6`) until the names are known.
- Tyres are flagged relative to each other, since the placard pressure isn't on the bus.

## Build

```bash
source scripts/env.sh          # Java 17, the Android SDK, and an IPv4 workaround for Gradle
./gradlew assembleRelease
# → dash/build/outputs/apk/release/dash-release.apk
```

Create `local.properties` with `sdk.dir=<path to your Android SDK>` first. It isn't committed.

The release build is signed with the debug key: the app is sideloaded onto one car.

## Install on the car
The app is `net.gooshy.dashy`. Up to version 1.3.1 it was "Omoda Dash" (`net.gooshy.omodadash`), which Android treats as a different app: uninstall that one. Personal bests start again.

The head unit blocks installs over ADB, so:
1. Copy `dash-release.apk` to a FAT32 or exFAT USB stick and plug it into the car.
2. Open the APK in the file manager.
3. Open Dashy McDashface parked and allow the permission prompts.

## Layout
| Path | What |
|---|---|
| `dash/…/LiveFeed.kt` | Reads the identified channels while the dashboard is on screen, plus the accelerometer. |
| `dash/…/Live.kt` | The latest decoded value of each channel. |
| `dash/…/DashEngine.kt` | Easing, session peaks, the 0–60 timer, and the live and demo inputs. |
| `dash/…/DashScreen.kt`, `DashTheme.kt` | The design, drawn in Compose. |
| `dash/…/PowerEstimate.kt` | Battery kW from speed, acceleration and slope. |
| `dash/…/RegenControl.kt` | The one write: the regen level. |
| `core/…/KnownChannels.kt` | The table of identified car channels: decode, unit and confidence. |
| `core/…/vdbus/` | A read-only client for the head unit's Desay VDBus. |
| `design/` | The Claude Design mock-up the app is built from, and the font licences (SIL OFL). |
| `docs/DASHBOARD-PROMPT.md` | The design brief. |

`core` is copied from the OmodaBoard recorder, where the car's channels are identified. When channels are added there, copy `core/` across again and change its packages from `net.gooshy.omodaboard` to `net.gooshy.dashy`.

## Licence
Apache License 2.0: see `LICENSE` and `NOTICE`. The fonts are under the SIL Open Font License 1.1 (`design/OFL-*.txt`). Anyone redistributing this project, or a modified version, must keep `NOTICE`, which carries this disclaimer.

## Disclaimer
**This is an unofficial hobby project. You use it entirely at your own risk.**

- **Not endorsed.** It isn't affiliated with, endorsed, sponsored or approved by Omoda, Jaecoo, Chery Automobile, Desay SV, or any other vehicle maker or supplier. All product names and trademarks belong to their owners and are used only to describe the hardware this runs on.
- **No warranty, no liability.** It's provided "as is", without warranty of any kind, under the Apache License 2.0 (`LICENSE`, sections 7 and 8). To the fullest extent the law allows, the author isn't liable for any damage, injury, loss, fine or other consequence of using it. That includes damage to your car or head unit, voided warranties, and effects on your insurance.
- **It talks to your car through undocumented interfaces.** Everything it knows about the car was worked out by observation, not from the maker. It may behave differently, or not at all, on another model, market or software version, and a software update from the maker could change that without warning.
- **It can change a driving setting.** The regen tile writes the energy recovery level, which changes how strongly the car slows down when you lift off the accelerator. Only change it when it's safe, and check the car's own settings screen if in doubt.
- **It isn't a safety system.** Power, g-force, timings and the other figures are estimates and can be wrong or late. Always rely on the car's own instruments and warnings.
- **Don't let it distract you.** Don't operate it while driving. You're responsible for following the laws where you drive on screen use and driver distraction.
- **Warranty and insurance.** Sideloading apps onto the head unit may affect your vehicle warranty or insurance. Check before you install.
- **Your car, your responsibility.** Use it only on a vehicle you own or have permission to modify, and in line with local law.
- **No support.** There's no guarantee of updates, fixes or help.

This disclaimer isn't legal advice.

