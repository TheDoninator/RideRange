# RideRange

A ride companion for electric scooters and one-wheel boards (Segway-Ninebot Max G2 and other Ninebots, Onewheel
Pint / Pint X / Pint S / XR / XR Classic / GT / GT-S / Onewheel+, VESC "float" boards, or any vehicle in manual mode):

- **Ride** – full-screen map with two range circles around you: **one-way** (blue) and **round trip** (green), a big
  speed readout (scooter speed when connected, GPS otherwise), battery %, and bike lanes/paths highlighted in green.
  Bike parking pins can be shown here too (P button).
- **Route** – search a place (or long-press the map) and pick **Bike trails**, **Battery saver**, **Least traffic** or
  **Fastest**. Shows distance, time, climb, battery used (Wh and %), battery left on arrival, whether it's in range
  and whether a round trip is possible. **Start** gives turn-by-turn navigation with voice prompts (≈500 ft, ≈150 ft,
  at the turn), keeps the screen on, and reroutes automatically when you're more than ~40 m off the route.
- **Parking** – bike parking (plus optional repair stations and charging points) from OpenStreetMap as pins and a list
  sorted by distance; tap one for details and "Navigate here".
- **Rules** – e-scooter and e-bike rules for the state you're in (all 50 states + DC) and city notes for St. George,
  Cedar City, Hurricane, Washington, Salt Lake City, Provo, Ogden, Logan and a few big cities, with source links.
  Utah was checked against the Utah Code on 2026-10-04 (including the 2026 helmet-under-21 rule and the 2027 changes
  for riders aged 8–15). You can pick another state manually.
- **Vehicle** – **Garage** (several vehicles, pick the one you ride, add / edit / delete), **Connect** (read-only
  Bluetooth for the active vehicle: live battery/voltage/power/temperature/odometer), **Trips** (per vehicle: automatic
  trip log, charts, GPX/CSV export) and **Settings** (range numbers, weight, units, servers, optional GitHub upload,
  privacy, licences, update check).

## Install

APK: download it from [Releases](https://github.com/TheDoninator/RideRange/releases).
To get update notices, enter `TheDoninator/RideRange` under Settings → About → Check for updates.
To build it yourself: `./gradlew assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`).

Copy it to the phone and open it (allow "install unknown apps" for your file manager once), or with the phone
connected: `adb install -r RideRange-1.1.0-debug.apk`. A new install starts with a short intro (units, your weight, your
first vehicle, what each permission is for). Installing 1.1.0 over 1.0.x keeps everything: the old settings, pairing
key, learned model and all trips become a first vehicle called "Max G2" and the intro is skipped.

## Garage (1.1.0)

Every vehicle has its own battery size and usable share, reserve, starting Wh/mi, detour factor, weight, top speed,
rated range, Bluetooth address and key, **learned consumption model, mass estimate and trip history**. The range
circles, route battery estimates, Battery saver and the Rules tab all follow the active vehicle. Presets (all editable)
come from published / retail figures, cited in `vehicle/Vehicle.kt`: Max G2 551 Wh; Onewheel+ 130 Wh, Pint 148 Wh,
Pint X 324 Wh, Pint S 324 Wh (retailers disagree), XR / XR Classic 324 Wh, GT 525 Wh, GT-S ~450 Wh (estimated); VESC
board 20s 576 Wh. One-wheel boards use their own physics (fat tyre: higher rolling resistance, upright rider, ~12 mph
cruise, stronger regen) and their own routing profiles: `onewheel-trails.brf` / `onewheel-traffic.brf` allow dirt
tracks, unpaved surfaces and MTB scale 0-2; scooters keep avoiding rough surfaces; steps are excluded for everyone.

## Vehicle connections (all read-only)

- **Ninebot / Segway** – unchanged protocol from Ninebot Bridge (see below).
- **VESC boards** – own implementation of the documented VESC frame format (0x02/0x03 framing, CRC-16/XMODEM) over
  the Nordic UART service. Only `COMM_FW_VERSION` and `COMM_GET_VALUES` can be encoded; nothing that drives the
  motor or changes configuration. Battery % comes from pack voltage / cells in series, speed and distance from ERPM /
  motor pole pairs and the tyre size (set these per vehicle).
- **Future Motion Onewheel** – connects, reads the firmware and hardware revision (readable without authentication)
  and only subscribes to live values on firmware that shares them with third-party apps: **Onewheel V1 / Onewheel+
  before the 2018 "Gemini" update (firmware < 4034)**. Gemini firmware (4034+ on Onewheel+, 4134+ on XR) needs a
  challenge-response with a secret from Future Motion's app; Pint (5xxx), XR hardware 4210+ and GT (6xxx) need a
  per-board key from Future Motion's servers. RideRange does **not** implement either (no bypass, no key extraction):
  those boards say so on the Connect page and run in **manual mode** (GPS speed + battery slider).

## Public release prep

No personal defaults: rider weight is asked in the intro (165 lb used until then), GitHub trip upload is an opt-in
advanced setting with no default repository, the map starts at the centre of the US until GPS arrives. Every network
service has a configurable base URL (Settings > Servers): the free public servers (brouter.de, Nominatim, Overpass)
don't allow a widely distributed app's traffic, so a public build should point them at its own or paid hosts.
Settings > About has the privacy policy (all data on the phone; which servers get what), licences/attribution
(OSM ODbL, OpenFreeMap/OpenMapTiles, BRouter, MapLibre, Open-Meteo...) and an optional "check for updates" against a
GitHub releases repository you enter. The Rules tab shows "Not legal advice - reviewed <date>" at the top.

## How the range works

usable energy = (battery % − 10 % reserve) × 551 Wh × 95 % usable. Road range = usable energy ÷ Wh per mile.
The circles are straight-line distances, so road range is divided by a detour factor (1.25): one-way radius =
road range ÷ 1.25, round-trip radius = half of that. All numbers are editable in Scooter → Settings.

Wh per mile starts at 16 and is then learned:
- every trip is recorded automatically (start: faster than 3 mph for 30 s; end: 3 min stopped; or Start/Stop on the
  Ride tab) at 1 sample per second: GPS, elevation (barometer if the phone has one, then corrected with terrain data
  from Open-Meteo after the trip), speed, and with the scooter connected: voltage, current, power, battery %,
  temperature and odometer;
- with ~20 mi of rides where the scooter was connected, the app fits
  `Wh/mi = a + b·speed² + c·climb% − d·descent% + e·(cold below 15 °C)` (regularised least squares towards a
  physics model) and uses it for the circles (at your typical speed over your typical terrain), the route battery
  estimates and Battery-saver scoring. Before that it uses the physics model scaled to your measured average
  (or the 16 Wh/mi setting);
- every trip stores what the model predicted vs. what the battery really used; the Trips screen shows the accuracy
  ("Range estimates are within ±6 % over your last 10 trips").

## Weight (1.0.2)

Scooter → Settings → Weight: rider weight (default 165 lb) + cargo (default 4 lb / 2 kg) + the 24.5 kg scooter gives
the mass the physics model uses. RideHub also estimates the whole rolling mass from rides with the scooter connected
(`MassEstimator`): for 1 Hz samples where the motor is pulling (>30 W, >3 m/s, not braking, steady telemetry) it fits
`P·eff/v − ½ρCdA·v² = m·(g·grade + a) + c` with Huber-weighted least squares (the intercept absorbs rolling
resistance). Flat steady cruising can't separate mass from rolling losses, so a trip only counts when grade /
acceleration varies enough; trips are combined by inverse variance, with trip-to-trip scatter as a floor on the ±.
It's "confident" with ≥2 such trips and ±≤8 %; then (switch "Use estimated weight", on by default) it replaces the
entered weight for the range circles, route battery predictions, Battery saver and the learned model's prior.
It assumes 80 % drivetrain efficiency (an error there biases the mass by about the same share). The learned model's
uphill coefficient gives a cross-check, and each uploaded trip JSON has a `mass` block (used, per-trip, combined).

## Routing, maps and data

Free OpenStreetMap services, no accounts or API keys: **OpenFreeMap** map tiles, **BRouter** routing (brouter.de),
**Nominatim** place search, **Overpass** bike parking, **Open-Meteo** elevation and temperature. Fair-use rate limits
are built in (Nominatim ≤1 request/s, BRouter and Overpass spaced out, parking cached for a day).

Route modes:
- *Bike trails*: BRouter "trekking" changed for scooters (bundled `assets/brouter/scooter-trails.brf`): no steps, no
  MTB trails or rough tracks, unpaved surfaces strongly avoided.
- *Least traffic*: the same plus big penalties on trunk/primary/secondary roads without bike lanes
  (`scooter-traffic.brf`). Both are uploaded to BRouter as custom profiles; if that fails the built-in
  trekking/safety profiles are used.
- *Battery saver*: asks for 5 candidate routes and picks the one the energy model says uses the fewest Wh ("Saves
  X Wh vs the fastest route").
- *Fastest*: BRouter "fastbike".

The last route is saved and reused if you plan the same destination while offline.

## Scooter connection

The Ninebot protocol (encryption, packets, pairing handshake, BLE link) is copied from `ninebot-bridge`. RideRange
opens the session **read-only**: only the handshake and READ commands can be sent; it never changes scooter
settings. Registers: speed ctrl 0x26 (4×/s), battery voltage+current BMS 0x33–0x34 (1×/s, for power), battery %
BMS 0x32, temperatures ctrl 0x3E / BMS 0x35 and odometer ctrl 0x29 (every 10 s). The first connection asks you to
short-press the power button to pair, unless you paste the key from Ninebot Bridge (Scooter tab → Pairing key).
The scooter accepts one Bluetooth connection at a time: close the Segway app / Ninebot Bridge first.
A foreground service keeps GPS, the scooter link, trip logging and navigation running with the screen off.

## GitHub trip upload (optional, off by default)

Scooter → Settings → "Upload trips to GitHub": enter a fine-grained token with *Contents: Read and write* on
your own repository (no default). The token is encrypted with an Android Keystore key and
never logged. After each trip the full JSON (1 Hz samples, stats, predicted vs actual Wh, model coefficients, app
version) goes to `trips/<yyyy-MM-dd>/<HHmmss>-<serial or noscooter>.json` (gzipped if over ~1 MB), plus a model
snapshot `model/<date>-model.json`. Uploads are queued with WorkManager (retry with backoff, optional Wi-Fi only);
each trip shows queued / uploaded / failed with a retry button, and Trips has "Upload all pending".

## Tests

`gradlew testDebugUnitTest` (104 tests): range estimator, energy model and Battery-saver scoring (with real recorded
BRouter routes), model fitting on synthetic rides, navigation (snapping, announcements, off-route + reroute rate
limit, arrival) on a real BRouter route, parsers against real BRouter / Overpass / Nominatim responses recorded
2026-10-04 (`app/src/test/resources/recorded/`), the regulations dataset (all 50 states + DC, Utah topics, city
lookup), trip stats / detection / exports, GitHub uploader against a fake HTTP layer, telemetry decoding using bytes
from the real Max G2 report, and the NbCrypto vectors copied from Ninebot Bridge. 1.1.0 adds: vehicle presets and
validation, per-vehicle physics/range and learned models, the 1.0.x to garage migration (plus the Room v2 SQL), VESC
framing/CRC/GET_VALUES decoding against independently built vectors (CRC check value 0x31C3, the published
`02 01 04 40 84 03` request), Onewheel characteristic parsing and the firmware gate, routing profile per vehicle type,
one-wheel board rules (UT, CA, default), server URLs and the update-check version compare.

`tools/gen_regulations.py` regenerates `assets/regulations.json`; `tools/check_links.py` checks every source link.

## Known gaps

- **Real scooter not tested**: the emulator has no scooter, so connecting, pairing, live telemetry, power logging
  and model learning from real rides are untested end to end (protocol code and register decoding are unit-tested).
- Only Utah is checked line by line against the statutes. Other states are short summaries marked "Summary" with
  links to the state code; some city entries (Cedar City, Hurricane, Washington, Ogden, Logan) say no specific
  ordinance was found. Municipal code sites block automated access, so check the linked code.
- **Onewheel and VESC links are untested on real hardware** (no board was available): the codecs are unit-tested,
  the Bluetooth paths only ran on the emulator. Most Future Motion boards in use today run firmware that refuses
  third-party apps, so expect manual mode on them.
- One-wheel board rules are specific for Utah (checked) and California (summary); other states show "not
  specifically addressed".
- A changed map style URL applies after restarting the app.
- Turn instructions have no street names (BRouter doesn't provide them).
- Range circles are straight-line, not a road/terrain-aware shape.
- Needs internet for maps, routing, parking and search (the last route is cached).
- Trip auto-detection only runs while the app is open or the ride service is running (scooter connected, navigating
  or recording).

## Next steps

- Ride with the scooter connected to check registers/power on the road and let the model learn.
- Range as a road-network shape (isochrone), offline maps and on-device routing.
- Elevation profile on the Route screen, Android Auto / widget.
