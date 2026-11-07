# Progress

Each round adds a short entry here: what was finished, and any known problems.

## Round 0 — An app that builds itself

**Status:** build 1 green.

Finished:
- Kotlin + Jetpack Compose app skeleton (`com.emotionalleaves.universe`, minSdk 26, targetSdk 35).
- Black home screen showing "Universe Explorer" and the installed version.
- `.github/workflows/build-apk.yml`: builds a signed APK on every push to `main` and publishes it as a GitHub Release named `Build N`.
- Fixed signing key at `keystore/universe-explorer.jks` (non-secret on purpose, see PLAN.md). Version code = GitHub run number, so each build installs over the last.
- Crash screen: any crash is saved to a file; on the next launch the app shows the full error with Copy and Continue buttons.
- A small "Test crash screen" button at the bottom of the home screen to check the crash screen works. Remove it in Round 14.

Pinned versions: Gradle 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, Java 17.

Phone checklist:
- [ ] The workflow turns green and a Release with an APK appears
- [ ] The APK installs and opens on your phone
- [ ] A second build installs over the first without uninstalling
- [ ] (extra) "Test crash screen" closes the app; reopening shows the crash screen; Copy works

Known problems: none yet.

## Round 1 — A 3D view you can touch

**Status:** build 2 green.

Finished:
- OpenGL ES 3.0 surface (`ui/UniverseView.kt`) with a renderer (`render/UniverseRenderer.kt`).
- Shader library with a logarithmic depth buffer (`render/Shaders.kt`), meshes, point batches.
- One lit, procedurally coloured planet and 6,000 background stars.
- Touch: one finger orbits (with coasting), pinch zooms (amplified, multiplicative), two fingers pan after a deliberate slide, twist rolls.
- On-screen frame rate, frame time and distance readout.
- Engine core in `core/` (doubles, big-range positions `UPos`, quaternions, units, seeded random, noise, orbital mechanics) is Android-free and compile-checked before every push.

Phone checklist:
- [ ] Controls feel smooth, and the counter stays near 60
- [ ] Rotating the phone doesn't crash the app

## Round 2 — The scale engine

**Status:** build 3 green.

Finished:
- `core/UPos`: positions as (integer cell of 2^40 m, double offset). Precise to well under 1 mm anywhere in the observable universe.
- Floating origin: every frame, positions are taken relative to the camera in doubles and only then turned into floats.
- Logarithmic depth buffer (vertex + fragment), infinite far plane.
- Camera zoom is multiplicative (pinch amplified ×2.2) with coasting, so speed scales with distance to what you are orbiting.
- Zoom slider on the right edge: logarithmic, from touching the surface to the whole observable universe.
- Scale readout (m, km, AU, ly, Mly, Gly) and a scale bar.
- Test scene: Earth-sized sphere, a 1 m orange cube on its surface, and a gold marker 10 billion light years away.
- Labels drawn over the 3D view, tap to select, double-tap or Fly To to travel, info card, Home button.
- Camera can't enter a planet.

Phone checklist:
- [ ] You can pinch from the cube to the far marker and back in under a minute
- [ ] Nothing shakes or flickers at any distance
- [ ] The scale readout changes units correctly

## Round 3 — The real solar system

**Status:** build 4 green.

Finished:
- `engine/SolarSystem.kt`: Sun, 8 planets, Pluto and 21 major moons with real radii.
- Planet positions from the JPL "Approximate Positions of the Planets" Keplerian elements (Table 1, 1800–2050; Pluto from the older table). Earth and Moon orbit their barycentre; the Moon uses the Astronomical Almanac low-precision series.
- Rotation from IAU pole directions and spin rates; Earth turns with Greenwich sidereal time, so day and night are correct for the date.
- Other moons: real distances and periods on circular orbits (positions approximate, labelled as such).
- Textures (loaded in the background, procedural colours until ready), Earth night lights and clouds, Saturn's rings with the planet's shadow, atmosphere glows.
- Orbit lines that fade in and out with distance; moon orbits appear near their planet.
- Time controls: slower/faster (down to 1 year per second, forwards or backwards), pause, Now.
- Info cards with facts; Home flies back to Earth.
- The Round 2 scale-test objects moved 3,000 AU away and are hidden by default (Settings will turn them on).

Phone checklist:
- [ ] Planet positions for today roughly match a planetarium website
- [ ] Saturn has rings and the Moon circles Earth
- [ ] Fly To lands you smoothly beside any planet

## Round 4 — The data factory

**Status:** data workflow green (run 1).

Finished:
- `tools/build_data.py` + `.github/workflows/build-data.yml` (run from the Actions tab, or push to the `data` branch).
- Every source is a separate step; a failing source logs a warning and the app falls back to generated data.
- Outputs go to the `data-v1` release: `base-data.zip` (bundled into every APK by `build-apk.yml`) and `pack-*.zip` (optional downloads).
- Star tiles: binary format `UXST` (200 pc tiles, 24 bytes per star). First run: 109,389 HYG stars in 623 tiles (2.6 MB); AT-HYG pack 750,619 extra stars in 10,601 tiles (18.4 MB).
- Also built: 6,464 exoplanets (4,857 hosts), 1,367 nebulae/clusters and 10,792 NGC/IC galaxies (OpenNGC), 55,877 Cosmicflows-4 galaxy distances + 6,294 redshift distances, 14 planet textures, ETOPO1/MOLA/LOLA elevation maps, the Planck CMB map (rotated to equatorial), constellation figures (Stellarium).
- The app reads tiles from assets (`engine/StarCatalog.kt`), verified locally against the real files (Sirius: RA 101.29°, Dec −16.72°, 8.6 ly).

Phone checklist:
- [x] The data workflow turns green
- [x] Its log prints how many tiles were made and their total size

## Round 5 — Real stars

**Status:** build 5 green.

Finished:
- 109,389 real stars drawn as points with blackbody colours from B−V and brightness from absolute magnitude and *your* distance (stars brighten as you approach them).
- Labels for the brightest named stars from wherever you are; tap any star (all 100,000+ are tappable) for an info card: distance, brightness from Earth, temperature, luminosity, radius, catalogue numbers.
- Constellation lines (Stellarium figures) in true 3D, switchable in Settings; they fade out once you are far from the Sun.

Phone checklist:
- [ ] Standing on Earth at night, Orion is recognizable
- [ ] You can fly to Sirius and it glows blue-white, bigger than the Sun

## Round 6 — Star systems and exoplanets

**Status:** build 5 green.

Finished:
- NASA Exoplanet Archive planets around their real host stars. Hosts are matched to HYG by HIP/HD number; hosts not in HYG (TRAPPIST-1, Kepler stars...) become extra real stars at their archive position and distance.
- Every other star gets generated planets (rules 1, 3, 4): count from the star's temperature/size, spacing by orbital ratios, sizes with more giants beyond the snow line, type from temperature (lava, rocky, ocean, ice, super-Earth, mini-Neptune, ice/gas giant, hot Jupiter). Same star → same planets, always (seeded by the star id + generator version).
- Transiting systems are oriented edge-on to Earth.
- When you approach a star its system switches on: star as a glowing sphere, planets with orbits, labels (green = real, purple = generated) and the Real/Generated badge.

Phone checklist:
- [ ] TRAPPIST-1 shows its seven planets
- [ ] A random star shows the same planets after restarting the app
- [ ] Info cards show the Real or Generated badge

## Round 7 — Planet surfaces

**Status:** build 6 green.

Finished:
- `engine/Terrain.kt`: cube-sphere quadtree (6 faces, tiles of 17×17 vertices + skirts), refined around the camera down to ~5 m tiles (21 levels on Earth). Tiles build on two worker threads (<1 ms each) and upload a few per frame.
- Real elevation: ETOPO1 (Earth), MOLA (Mars), LOLA (Moon), sampled bilinearly, plus seeded fractal detail from ~40 km down to ~2 m. Earth's oceans are flat water at sea level with sun glints. Verified: Himalaya ≈ 6,000 m, Denver ≈ 1,645 m.
- Every other solid world (Mercury, Pluto, rocky moons, every generated rocky/ocean/ice/lava planet) gets fully generated terrain; airless worlds get craters.
- Gas giants have no surface: you stop at the cloud tops.
- Camera turns with the planet you are near (no sliding over the ground), never goes underground, and stands ~1.7 m above the terrain at the lowest zoom.
- Near the ground the view tilts up to the horizon; one-finger horizontal drag turns, vertical drag walks; pinch and the slider change your height above the ground.
- Sky colour and haze from the atmosphere (blue Earth, orange Mars/Titan, sunsets), stars fade in daylight.

Phone checklist:
- [ ] One continuous zoom takes you from Mars orbit to the slopes of Olympus Mons
- [ ] A generated exoplanet has terrain you can land on

## Round 8 — The Milky Way

**Status:** pushed.

Finished:
- `engine/Galaxy.kt`: a density model of the Milky Way in stars/pc³: exponential thin + thick disk (scale length 2.6 kpc, height 300 pc), four logarithmic arms (12° pitch), a central bar at 27°, bulge and halo. Integrates to about 1.9 × 10¹¹ stars. Oriented with the real galactic pole and centre (RA 266.4°, Dec −29.0°, 26,700 ly away).
- Generated stars around the camera, deterministic per cell (rule 1): tier A (all stars, 10 pc cells) and tier B (luminous stars, 100 pc cells), with cells halved in dense regions so there are never more than ~40k extra points. Real stars win (rule 2): nothing generated within 25 pc of the Sun or brighter than magnitude 8 as seen from the Sun (where HYG is complete). Star mix follows the real initial mass function (rule 3): ~70 % red dwarfs, ~6 % white dwarfs, very few O/B stars.
- Generated stars have catalogue-style names (e.g. "MW D652-11"), can be tapped, bookmarked and visited (their planets are generated too).
- From far away the galaxy is a glow of 40,000 light samples (blue arms, yellow bulge) that fades out near you, where individual stars take over.
- A "You are here (Sun)" label when you are far from home.

Phone checklist:
- [ ] Zooming out from the Sun shows the whole galaxy and a "You are here" marker
- [ ] Zooming back in returns you to the same real stars

## Round 9 — Nebulae and clusters

**Status:** pushed.

Finished:
- 1,365 nebulae and clusters from OpenNGC at their real sky positions and angular sizes (dark nebulae skipped: an additive renderer can't darken).
- Distances: curated real values for ~120 famous objects (M42 1,344 ly, Pleiades 444 ly, Crab 6,500 ly...); the rest are estimated from the object type and marked "estimated" on the info card.
- Nebulae are soft, noise-shaped glowing volumes coloured by type (red emission, blue reflection, teal planetary rings, filamentary supernova remnants), fading as you fly inside them.
- Clusters glow from afar and resolve into generated member stars up close (Plummer-sphere distribution; old red giants in globulars, hot blue stars in young open clusters). Members bright enough to be in the real catalogue are skipped (rule 2).
- Labels for Messier and named objects; tap any for an info card.

Phone checklist:
- [ ] The Orion Nebula sits below Orion's belt
- [ ] The Pleiades look like a tight cluster when you fly through them
