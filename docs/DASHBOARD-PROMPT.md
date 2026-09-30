Design a live driver's dashboard for the centre screen of an Omoda 9 plug-in
hybrid (Desay SV head unit, Android 11). The style is Formula 1 onboard
telemetry crossed with 1980s Knight Rider: a black cockpit, a red scanner and
segmented light-bar gauges.

## The screen
- **1920 × 720 px, landscape, 8:3.** Density is 160 dpi, so 1 dp = 1 px. Draw
  every mock-up at exactly 1920 × 720.
- Full-screen and immersive, with no status or navigation bar. Keep a 24 px
  safe margin on every edge, and nothing important in the top 40 px in case the
  system bar appears.
- It's mounted mid-dash, glanced at while driving, often in direct sun. Aim for
  readability at arm's length in under half a second. The largest numerals
  should be 200 px or taller; nothing that has to be read should be under 28 px.
- Dark by default: true black background, which is OLED-friendly and doesn't
  glare at night. Provide a high-contrast daytime variant.

## The style
**F1 onboard telemetry:**
- a horizontal shift-light / power bar of LED segments along the top edge
- a huge central speed number in a condensed, squared, italic-leaning typeface
  (think telemetry fonts, not a real team's)
- sector-style colours (purple = session best, green = personal best, yellow =
  worse)
- thin carbon-fibre texture panels, chevron dividers and a delta readout

**Knight Rider:**
- a red LED scanner, the sweeping light bar, across the bottom that pulses with
  the car's state
- stacked rectangular segment meters (the voice-box and dash bars) for power
  and regen
- amber and red on black, faint scanline glow, and square monospaced labels

Blend them into one design; don't split the screen into two themes. Use no
real logos, team liveries, the F1 wordmark or the KITT name. Inspired by, not
copied.

## Data available, with real update rates
Design only around these. Each is decoded and confirmed from the car.

| Data | Update rate | Notes |
|---|---|---|
| Speed (km/h) | 10 Hz | primary number |
| Longitudinal g (acceleration / braking) | 50 Hz | from sensors plus speed; smoothed |
| Lateral g (cornering) | 50 Hz | from yaw rate × speed |
| Yaw rate (°/s) | ~10 Hz | |
| Steering angle (°, ±~500) | ~10 Hz | |
| Brake pedal (on/off) | on change | |
| Gear (P/R/N/D) | on change | |
| Indicators left/right | on change | |
| Auto-hold active | on change | |
| Battery SOC (%, 0.01 resolution) | 2 Hz | |
| EV range (km) | on change | |
| Energy flow: driving / regenerating / parked | on change | drives the scanner's colour |
| Drive mode (enum) and EV/hybrid mode | on change | |
| Trip distance, time, average speed, average kWh/100 km | 2 Hz | |
| Outside temperature (°C) | on change | |
| Tyre pressures ×4 (psi) | on change | |
| 0–50 / 0–100 km/h timer, peak g records | derived | "session best" style |

**Planned, not yet available:** design slots that show a clear standby
("ENGINE OFF", "—") until the data exists:
- **Power (kW):** motor output and regen as one bidirectional bar, for example
  +150 kW drive to −60 kW regen. Until the real signal is found, the bar shows
  the energy-flow state only (drive or regen, no magnitude).
- Engine RPM and engine torque (engine rarely runs; it's a PHEV)
- Accelerator position (%)

## Screens (swipe horizontally, 3 pages)
1. **RACE:** the main page.
   - Speed dominant, centre.
   - Power / regen segment bar.
   - A g-force circle (friction circle) with a fading 2-second trail and peak
     markers.
   - Brake and throttle bars in the F1 style (throttle stays greyed until it's
     available).
   - Gear and drive mode.
   - Top shift-light bar showing power as a percentage of max, green → red →
     blue flash.
   - Bottom Knight Rider scanner: slow red sweep when driving, green sweep
     reversing direction when regenerating, dim pulse when parked or on
     auto-hold, amber flashes left or right with the indicators.
2. **ENERGY:**
   - SOC as a large segmented battery meter.
   - EV range.
   - Live consumption against the trip average, with an F1-style delta
     (+/− kWh/100 km, green or red).
   - Regen time against drive time this trip.
   - Hybrid / EV mode, and an engine standby panel.
3. **CAR:**
   - Top-down car outline with the four tyre pressures (colour-coded against
     the placard).
   - Steering angle as a rotating wheel glyph.
   - Yaw rate.
   - Outside temperature.
   - Odometer and trip data.
   - A "session bests" timing-screen table: 0–50, 0–100, peak accel / brake /
     lateral g, top speed, with purple for the best ever and green for this
     drive.

Keep a permanent strip across all pages: a small REC indicator (the app also
records the drive), a clock, and Start/Stop/Export as small unobtrusive
controls. Those three stay; they're what the app does today.

## Motion
- Gauges animate at 60 fps but ease toward new values. Anything updating at
  2 Hz must glide rather than jump.
- The scanner never stops while the screen is on. It's the "alive" signal.
  When data goes stale for more than 2 s, it freezes and turns amber with
  "NO DATA".
- No motion that pulls the eye from the road: no flashing except indicators
  and a hard-brake flash (a single red frame pulse above 0.5 g).

## Deliverables
- **An interactive prototype**, one canvas at exactly 1920 × 720, with the
  three pages swipeable or tabbed.
- **Driven by simulated live data:** a scripted 60-second loop.
  1. Parked on auto-hold.
  2. Hard launch to 100 km/h at about 0.45 g.
  3. A left-hand bend at 0.4 g lateral with the indicator on.
  4. Regen braking at 0.3 g back to a stop.
  5. Reverse briefly.

  Update each value at the real rates in the table above, so the easing on the
  2 Hz values is visible. Include a "NO DATA" moment.
- **A state switcher** to jump straight to: parked, accelerating hard, regen
  braking, cornering, indicator on, no data.
- **A night / day toggle.**
- **A short style sheet** alongside:
  - colour tokens (hex)
  - the type scale, using Google Fonts only: a condensed display face and a
    monospace
  - the segment bar spec: segment count, size, gap, colour per state
  - the scanner spec: lamp count, trail length, sweep period per state
  - the friction circle and the timing table

The design will later be rebuilt natively in Jetpack Compose on the car, so
build everything from shapes, gradients and glows (CSS/SVG/canvas), with no
bitmap artwork or photos.
