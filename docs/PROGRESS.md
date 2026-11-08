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

**Status:** build 7 green.

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

**Status:** build 8 green.

Finished:
- 1,365 nebulae and clusters from OpenNGC at their real sky positions and angular sizes (dark nebulae skipped: an additive renderer can't darken).
- Distances: curated real values for ~120 famous objects (M42 1,344 ly, Pleiades 444 ly, Crab 6,500 ly...); the rest are estimated from the object type and marked "estimated" on the info card.
- Nebulae are soft, noise-shaped glowing volumes coloured by type (red emission, blue reflection, teal planetary rings, filamentary supernova remnants), fading as you fly inside them.
- Clusters glow from afar and resolve into generated member stars up close (Plummer-sphere distribution; old red giants in globulars, hot blue stars in young open clusters). Members bright enough to be in the real catalogue are skipped (rule 2).
- Labels for Messier and named objects; tap any for an info card.

Phone checklist:
- [ ] The Orion Nebula sits below Orion's belt
- [ ] The Pleiades look like a tight cluster when you fly through them

## Round 10 — Other galaxies

**Status:** build 9 green.

Finished:
- 62,171 galaxies at real positions: 55,877 with measured distances from Cosmicflows-4, 6,294 more from OpenNGC redshifts (Hubble's law), plus the Magellanic Clouds and other Local Group members. Verified: Andromeda 2.44 Mly, LMC 160,000 ly, Whirlpool 27 Mly, M87 55 Mly.
- Shapes from the Hubble type (spiral, barred, lenticular, elliptical, irregular), sizes from the catalogue's angular size, tilt from position angle and axis ratio. Where a value is missing it is generated (seeded) and listed under "Generated" on the info card.
- Far away: points whose brightness follows a zoom-dependent exposure, so distant galaxies stay visible as you zoom out. A few pixels across: shaped discs with arms and bulges, tilted in 3D.
- Any galaxy can be entered: within ~40 radii its glow is built from its own density model, and inside it stars (then planets, then terrain) are generated with that galaxy's seed. Same galaxy, same stars, every time.
- Generated star names use a short galaxy code ("Andromeda 4F2A-12", "LMC 0B00-3").

Phone checklist:
- [ ] You can fly into Andromeda and land on a generated planet there
- [ ] Leaving and coming back shows the same planet

## Round 11 — The whole universe

**Status:** green (in build 11).

Finished:
- Generated cosmic web (`engine/CosmicWeb.kt`): 220,000 galaxies placed along a seeded ridged-noise filament field (~160 Mpc structure), from where the real catalogues end out to the edge of the observable universe (14 Gpc ≈ 46 billion ly). Fixed count and seed, so ids are stable. Any of them can be tapped and entered (generated names such as "GX-ED98-0123").
- GLADE+ pack support: up to millions of real galaxies as points (Low 0.4 M, Medium 1.2 M, High 3 M drawn). When the pack is installed, generated structure starts beyond its 2,000 Mpc reach (real wins).
- The Planck CMB map (SMICA) as the outer shell at 46 billion ly, fading in once you zoom out to cosmic scales; seen from outside, the observable universe is a ball wrapped in it.
- Galaxy points use a zoom-dependent exposure, so the large-scale structure stays visible as clouds and filaments when zoomed out.
- "Observable universe" is a searchable object you can fly out to.

Phone checklist:
- [ ] Zoomed all the way out, the observable universe is a ball with the glowing shell
- [ ] Frame rate stays above 30 the whole way

## Round 12 — Data packs

**Status:** build 11 green.

Finished:
- Menu (☰, top right) → Data packs screen: each pack with size, Download / Cancel / Delete, progress bar, total storage used, and a Wi-Fi-only switch (on by default).
- Packs are files in the `data-v1` GitHub Release (found through the GitHub API, downloaded with redirects followed), unzipped into the app's private storage, and loaded live without restarting:
  - "750,000 more real stars" (AT-HYG to magnitude 11). When installed, generated stars must be fainter than magnitude 10.5 as seen from the Sun (rule 2).
  - "Millions of real galaxies" (GLADE+, trimmed to a phone-friendly sample). When installed, the generated cosmic web moves out beyond its reach.
- Deleting a pack instantly falls back to the bundled data plus generated content.

Phone checklist:
- [ ] Downloading the full AT-HYG pack adds visible stars
- [ ] Deleting it leaves the app still working

## Round 13 — Finding your way

**Status:** green (in build 13).

Finished:
- Search (menu → Search): planets and moons, named stars and Bayer designations, HIP/HD numbers ("HIP 32349"), 6,400+ exoplanets and their host stars ("Kepler-22 b"), Messier/NGC objects and common names ("Orion Nebula", "M31"), 10,000+ named galaxies, PGC numbers, and any generated object you have visited or bookmarked (rule 6). Ranked exact → prefix → word → substring.
- Bookmarks: ★ on any info card or "Bookmark this view"; saves the object id plus the exact camera position, distance and orientation. Generated objects are rebuilt from their ids, so a bookmarked generated planet reopens in the same place.
- Breadcrumb trail above the focus name: Universe › Milky Way › Sun › Earth (or Universe › Andromeda Galaxy › Andromeda 4F2A-12 › b).
- Home button and scale bar (from Round 2).

Phone checklist:
- [ ] Searching "Kepler-22 b" flies you there
- [ ] A bookmarked generated planet reopens to the same place

## Round 14 — Polish and performance

**Status:** build 13 green.

Finished:
- Performance: catalogue tiles merged into 1,600 pc super-tiles (HYG: ~600 draw calls → a few dozen; AT-HYG pack: ~10,600 → ~100). Galaxy shape selection computed once per frame.
- Quality presets (Settings): Low / Medium / High change galaxy glow particles (20k / 40k / 70k), GLADE+ points drawn (0.4 M / 1.2 M / 3 M) and how early terrain refines.
- Battery saver: caps rendering at ~30 fps.
- Settings screen (one short list): quality, battery saver, labels, orbits, constellation lines, generated objects on/off, star brightness, frame-rate counter, scale-test objects, show tips again.
- First-launch tips (5 short cards), How-to-use screen, Credits screen with every data source and licence.
- Android back button closes any open panel. The Round 0 test-crash button is gone (the crash screen itself stays).

Phone checklist:
- [ ] 30 minutes of exploring with no crash
- [ ] The phone doesn't get hot on Medium
- [ ] Someone else can use the app without you explaining it

## Improvement passes (after Round 14)

| Pass | Focus | What changed |
| --- | --- | --- |
| 1 | Bugs | Walking uphill no longer gets stuck (camera lifts over terrain instead of undoing the move); no ground on the Sun, gas/ice giants or under Venus' clouds; rotation speed inside galaxies fixed; the Milky Way no longer shows as a blazing point in Sagittarius; labels no longer block taps; lower memory for big packs (`largeHeap`, lazy id index). |
| 2 | Visuals | Galaxies drawn with surface brightness relative to the current exposure (Andromeda is a faint glow from Earth, a bright spiral up close); nebulae dimmed by their real catalogue magnitude, brightening as you approach; the Milky Way stays visible as a whole from outside. |
| 3 | UX | Info card: Land (stand on any rocky world), Out ↑ (zoom out to what contains it: moon → planet → star → galaxy → universe), ★ bookmark, collapsible facts. Quick 🔍 search button. Status panel moved left so it never collides with the buttons. |
| 4 | Navigation | Flights to planets and moons swing round to arrive on the sunlit side. An arrow at the screen edge points to the selected object when it is off screen or behind you. |
| 5 | Content | Generated moons (1–5 around giants, Roman numerals, real orbital periods from the planet's mass, kept inside the Hill sphere), rings on ~30 % of generated giants, moon orbits and labels, breadcrumb includes the planet. |
| 6 | Performance | Galaxy glow blob size capped by quality (the biggest GPU cost from inside the Milky Way); stars and the cosmic web skip drawing when they can't be visible. |
| 7 | Information | "Light takes 8.6 years" next to distances; "Loading stars / galaxies / cosmic web" chip; "not now" hint when simulated time differs from the clock. |
| 8 | Robustness | A layer that throws is logged and skipped instead of crashing; Settings shows the last problem with Copy; Back closes panels, then clears the selection; search index refreshes as catalogues finish loading. |
| 9 | Visuals + safety | Diffraction spikes on the brightest stars; sun glints on Earth's oceans; removed GLSL undefined behaviour (reversed `smoothstep` edges, `pow` of negatives) that can break on some GPUs. |
| 10 | Review | Docs (README, this file) and a full re-read of the build. |

## How to test the whole thing

1. Install the newest APK from Releases (it installs over older builds).
2. First launch: 5 tips, then Earth from space at the current date and time.
3. Work through the phone checklists above, round by round. If something fails, open Settings → Last problem → Copy report, or after a crash reopen the app and tap Copy, and paste it into a new AI session with `docs/PLAN.md`.

## Navigation update (after user feedback: "hard to find stuff")
- [x] ✦ Explore screen: famous places by category (Solar system, stars, exoplanets, nebulae, galaxies, universe), one tap to fly.
- [x] Surprise me: random generated planet around a real star (alien world or any planet).
- [x] Zoom slider scale labels (Ground → Universe) that jump straight to that scale.
- [x] "↩ Back to …" chip when drifting into empty space.
- [x] Top bar: 🔍 🏠 ☰ icons plus Explore; tips and How to use updated.
- [x] Readable pack-download errors (private repo / no internet).

## Round 15: Everything visible, tappable, enterable
- [x] Tier C star field: supergiants (M < -4.5) in 500 pc cells out to ~1.25 kpc, so the brightest stars stay individually tappable far away (any galaxy).
- [x] Tap anywhere on a galaxy's glow: the app walks the tap ray, finds where the light comes from, and generates the actual star there (deterministic id, bookmarkable).
- [x] Galaxy field: ordinary galaxies (4 Mpc cells) and dwarfs (1 Mpc cells) generated around the camera anywhere in the universe, along a filament field; real catalogues win (apparent mag limit 14, 17 with GLADE+); none inside the Local Group. Enterable like any galaxy (ids gal:f…).
- [x] Galaxy cards show star counts and an Explore hint; universe card shows total stars.

## Pass 11 (polish)
- [x] "Finding Mars…" / "Picking a world…" shown while Explore and Surprise me work.
- [x] ↩ Back to … always returns to the object it names.
- [x] Zoom slider shrinks on short and landscape screens.
- [x] Star cards say how many planets the star has (real or generated).
- [x] Empty search offers ✦ Browse famous places; Explore remembers open sections.
- [x] Low quality skips the supergiant tier (faster phones stay smooth).

## Pass 12 (polish)
- [x] 🎲 Galaxy: fly to a random far-away galaxy; Explore dice buttons laid out evenly.
- [x] Updated first-run tour (shown once again to everyone) with "tap the glow" and Explore tips.
- [x] Field galaxies join the galaxy list at most every 1.5 s (no stutter from repeated rebuilds).
- [x] Galaxy distances over 1,000 Mly shown in billions of light years.

## Round 16: Know more
- [x] Star cards: type (red dwarf … supergiant, white dwarf), colour, mass, lifetime, habitable zone, when its light left.
- [x] Planet and moon cards: gravity, your weight there, escape speed, density, habitable-zone verdict, starlight, "could you stand there?".
- [x] About texts: Wikipedia summaries (CC BY-SA, credited per card) for named objects; plain explanations for every generated type.
- [x] Info card scrolls; census screen (☰ → What's in the universe).

## Round 17: More stuff
- [x] Real: asteroids (H < 14.5) and comets (JPL SBDB) on Kepler orbits; every known moon (JPL mean elements; major moons get real orbits); pulsars (ATNF); quasars (Milliquas, R < 18.5); Abell galaxy clusters.
- [x] Generated: member galaxies for every real cluster; black holes (1 per 2,000 stars), neutron stars (1 per 200), rogue planets (~1 per star) around the camera in any galaxy (none near the Sun, where surveys would have found them).
- [x] Explore: asteroids & comets, more moons, strange things, 🎲 black hole / neutron star / rogue planet.
- [x] Data factory: incremental runs (`only` input) that start from the published base data.

## Round 18: Stories and a professional UI
- [x] Story.kt: a vivid description for every object from its own numbers (how its sun looks from a planet, jump height, temperature in everyday terms, sizes compared with cities and countries, when its light set off in human history).
- [x] Info card: kind and source chips, three headline numbers, story, Read more (Wikipedia summary + every fact), icon actions.
- [x] Design system (ui/Design.kt): palette, type scale, a drawn line-icon set replacing all emoji, glass panels, buttons, chips, list rows.
- [x] Restyled top bar, time bar, menu, Explore (Surprise me tiles, category list), search results with type icons, settings sections, census, welcome tour.

## Bug sweep (pass 13)
- [x] Pinching during a flight cancelled it and dropped the target; dragging mid-flight now stops but stays locked on.
- [x] Pinch drift was treated as a two-finger slide (detached you from the object); pan now needs the fingers to move together more than they spread.
- [x] One finger could not orbit after lifting the other from a pinch.
- [x] Status said "Empty space" and offered "Back to…" during every flight; now "Flying to X".
- [x] Search scanned ~300,000 names on the UI thread per keystroke; now off-thread with top-N selection.
- [x] Lost updates to the galaxy list from several threads; now locked.
- [x] Asteroids, pulsars and generated remnants showed as fake stars and labels in the sky from planets; now only at the right zoom.
- [x] Hundreds of small-moon labels and orbit rings from far away; now only close in.
- [x] Each newly met galaxy rebuilt the whole 62,000-galaxy batch (stutter); now incremental.
- [x] Galaxy labels scanned 62,000 entries per frame; now a cached list of named ones.
- [x] Duplicate labels for the selected object; card stayed expanded between selections.
- [x] "Out" from asteroids, pulsars, quasars, remnants went to the wrong parent.
- [x] Terrain on tiny moons had hills a sixth of their radius.
- [x] Pulsars had no close-up look and could be flown through.
- [x] Wikipedia rate limits: retried gently (1,257 summaries).

## Round 19: Land fix, glowing zoom-out, more of everything
- [x] Land: smooth turn (no camera jump), lands on the day side, ground loads on its own thread and starts loading during the descent; horizon tilts up smoothly.
- [x] Zoomed-out views glow: HazeLayer (soft blobs around 62,000 real galaxies + 140,000 generated filament blobs beyond the catalogues), brighter and larger galaxy points.
- [x] Generated star clusters and star-forming nebulae inside every galaxy (none within 1.5 kpc of the Sun, where catalogues cover them).
- [x] Generated asteroid belts and outer icy belts around other stars; every rock tappable (ids <star>/belt<k>/<i>).
- [x] Optional "Beyond the observable universe" (Settings, or Explore → Beyond the edge): 200,000 speculative galaxies out to 12× the observable radius, zoom range extended, CMB becomes a see-through bubble from outside.

## Round 20: 10^50+ things
- [x] RockField: belts and comet clouds of every star (and the Sun's main belt below the catalogue limit, Kuiper belt and Oort cloud) fill in around the camera in 7 size levels down to 1 m boulders (N ∝ D^-2.5), ~1.6 × 10^19 bodies per star, all tappable with stable ids (rock|…).
- [x] Beyond region extended to 9,000 × the observable radius (64-bit cells stay exact), on by default; field galaxies use unpacked cell ids; depth range 1e33 m; deep haze keeps the structure visible at any zoom.
- [x] Census headline: ≈ 3 × 10^42 things in the observable universe, ≈ 2 × 10^54 with the beyond region.

## Pass 14: black zoom-out and landing
- [x] Shaders: length()/normalize() of huge vectors overflowed 32-bit floats on phone GPUs (>~600 pc), making every distant galaxy, cosmic-web point and haze blob invisible. Added safeLength/safeNormalize.
- [x] Haze: smoothstep(0, 0, x) undefined for the deep haze; guarded.
- [x] Landing: terrain drawn during the descent (it hid the sphere but drew nothing until arrival), flight aims at the real ground height, waits up to 2.5 s for the ground and picks dry land near the spot on worlds with seas, no cloud shell from below.
- [x] Headless landing test with a real clock: Earth, Mars and Moon all land in ~2.5 s at ground level.

## Round 21: Free flight
- [x] Free-flight mode (paper-plane button beside Explore, or "Fly freely here" when zoomed far out): drag looks around in place, hold Forward/Back to fly, pinch hops, two fingers slide, twist rolls.
- [x] Auto speed: ~ distance to the nearest surface per 1.5 s (planets, stars, generated stars, rocks, remnants, galaxies, field galaxies), capped at 30 Mpc so it cannot run away; never flies into a surface at ×1. Warp ×100 / ×10,000 / ×1,000,000.
- [x] Hard edge at the modelled universe's boundary (9,000× or 3× the observable radius) keeps positions exact.
- [x] Headless test: eases from 14,000 km to ~60 km above Earth without hitting it; roams the beyond region at warp without escaping it.

## Pass 15: tested like a person would (emulator + headless renderer)
How it was tested: an Android emulator on GitHub (ui-test.yml + tools/ui_test.sh) taps through the app and screenshots every step; a headless renderer on the dev machine (Java 21 foreign-function bridge to Mesa llvmpipe, real shaders and data) screenshots any camera position. The emulator ignores gl_PointSize (stars and galaxies show as 1-px dots there), so rendering is judged on the headless renderer and UI flow on the emulator.
- [x] Far from home the sky went black between 1 Mpc and a few Gpc (the pre-made web is too sparse there). New WebLod: cosmic-web cells around whatever you look at, sized to the zoom level, anywhere in the observable universe.
- [x] Whole-universe view was a white blob (1.2M additive points at full brightness). Shared Exposure curve grows slower past 30 Mpc; quasars dimmer; Universe preset backs off so the ball fits a phone screen.
- [x] Field galaxies brighter (+1.5 mag) so neighbourhoods show.
- [x] After "Fly to" the card shrank and never came back, so Land and the facts were unreachable: it returns on arrival (but stays small after Land, a drag or free flight).
- [x] Labels and taps of things behind a planet (Rosette Nebula printed on Mars) and below the horizon when landed are hidden (line of sight through the world).
- [x] World labels kept off the zoom scale.
- [x] Free flight: warp ×1/×10/×100/×1000 (×10,000 flung you out of the universe in 3 s); holding Forward speeds up gently to ×10.
- [x] Search: the keyboard's search key opens the best match. Menu button always opens the menu.
- [x] Landed ground had no detail (photo maps are km per pixel): procedural soil/rock patches at ~2 km and ~150 m.
- [x] GPU upload and per-layer GL errors are logged (UniverseProblem/UniverseDiag) and kept in the problem report.
- [ ] Emulator showed rectangular holes in the ground just after landing (not reproduced on the headless renderer).

## Pass 16: no landing, faster start
- [x] Land removed (button, walk mode, ground tiles, height maps no longer bundled, "Ground" zoom label). Worlds stop one radius above the surface (closer was just a blurry patch of map); the zoom scale starts there.
- [x] Startup loads run on four threads side by side (stars, galaxies/nebulae, asteroids/quasars, cosmic web) instead of one queue; the generated cosmic web, haze and beyond-region clouds are saved to the cache after the first launch. Headless timing: everything loaded in 5.4 s before, 1.8 s on first launch and stars-limited after.
- [x] Quasar and cluster facts built when a card opens, not for all 56,000 at startup (was 1.5 s).
- [x] Free flight: speed can grow at most ~4.5× a second (it ran away exponentially at warp); steps towards the nearest thing never exceed the room left, so warp no longer jumps through planets; heading away is unlimited.

## Pass 17: selection, speed, comfort
- [x] Tap something again (not only double-tap) to fly there; tap reach a little larger.
- [x] Crowded spots: the card lists "Also here" — the other things under your finger, one tap each.
- [x] Flying to (or picking from Also here) a star starts building its planets straight away, so they are there on arrival.
- [x] Baseline profile: the app is compiled ahead of time at install (fresh installs ran interpreted; catalogues took close to a minute on the emulator).
- [x] Easier on the eyes: names slightly see-through and smaller (selection stays bright), at most 28 on screen, orbit lines and constellation figures dimmer.

## Pass 18: free travel to anything
- [x] Free-flight autopilot: in free flight, Fly to / tap twice / Explore / Search / Home all glide you there and you stay in free flight (it turns to face the target and closes the gap, faster when many orders of magnitude are left: 3–9 s from a moon next door to a galaxy billions of light years away). Holding Forward/Back or dragging the view takes back control.
- [x] "Go to nearest" button (and the Nearest line itself) in free flight: travels to the nearest thing you are not already at — planet, moon, star, rock, black hole or galaxy (field galaxies included).

## Pass 19: looks and load time
- [x] Bloom: the scene is drawn offscreen, its brightest parts blurred at 1/4 and 1/8 size and added back (soft glow round stars, suns, galaxy cores), plus a soft highlight shoulder and a light vignette. Off on Low quality or if the phone cannot make the offscreen images.
- [x] Galaxies: brightness capped to an even glow over the disk and bulge (cores burned to flat white discs), a bit less light in bulges and more in young arm stars so spiral arms read, dust lanes along the mid-plane, and for the Milky Way seen from the Sun the dusty path through the disk (the galactic centre is now a band with its dark rift, not a white slab; rebuilt without it when seen from outside).
- [x] Quasars dimmer at the whole-universe scale (the survey footprint showed as white patches).
- [x] Catalogues parsed with a fast tab-separated reader (no split/toDouble): interpreted-mode load of star names, galaxies, asteroids and quasars about 2× faster; galaxy card facts built only when a card opens.
- [x] Opens on Earth's sunlit side (it opened on whatever side was night at the time).
