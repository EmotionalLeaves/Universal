# Universe Explorer — Build Plan (for AI sessions)

Read this whole file before changing anything. The full illustrated plan lives in the owner's doc:
https://claude.ai/artifact/NHD6mj62sJNkgAwr8bdivB

## Goal

An Android app, built entirely by GitHub Actions, that lets you fly smoothly from the edge of the observable
universe down to standing on the ground of a single planet, with no loading screens. Measured things come from
real catalogs. Everything unmeasured is generated from rules, repeatably.

Three core tricks:
1. **Floating origin.** Positions are doubles on the CPU; the world is shifted around the camera every frame;
   the GPU only sees floats relative to the camera.
2. **Nested cells.** Space is split into cells containing smaller cells. Only load cells that are near and big
   enough on screen; everything else is a dot or a glow.
3. **Real first, generated fills gaps.** Load a cell's real objects from its tile, then a generator seeded by
   the cell ID adds what's missing.

## Owner constraints

- The owner only has a phone. Never ask them to run commands locally. GitHub Actions builds everything.
- One round at a time. A round is done only when the APK passes that round's checklist on the phone.
- Keep everything from earlier rounds working. Update `docs/PROGRESS.md` at the end of each round.

## Locked-in decisions (do not change)

| Decision | Choice |
| --- | --- |
| Language | Kotlin |
| 3D graphics | OpenGL ES 3.0, written directly (no game engine) |
| UI on top of 3D | Jetpack Compose overlay |
| Position math | Doubles on the CPU, floats only relative to the camera |
| Coordinate frames | Each object is placed relative to its parent: universe → galaxy → star system → planet → surface tile |
| Depth | Logarithmic depth buffer |
| Data prep | Python scripts in `tools/`, run by GitHub Actions |
| Data format | Small binary tiles, one per cell |
| In the APK | Base pack ~100 MB: solar system, bright stars, nearby galaxies |
| Extra data | Downloadable packs hosted as GitHub Release files, each under 2 GB |
| Signing | Fixed keystore `keystore/universe-explorer.jks` committed to the repo (non-secret for now) |
| Real vs generated | Every object has `source = REAL` or `GENERATED` |
| Build | Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0, Java 17, minSdk 26, targetSdk 35 |

## Scale ladder

1. Observable universe (~93 billion ly) — Planck microwave map as the outer shell
2. Cosmic web (hundreds of millions of ly) — galaxies as points/clouds along filaments
3. Galaxy groups (~10 million ly) — Local Group
4. Galaxy (~100,000 ly) — bar, arms, dust as one glow
5. Star neighborhood (tens to thousands of ly) — individual stars, nebulae, clusters
6. Star system (~100 AU) — star, planets, orbit lines
7. Planet (thousands of km) — world, moons, atmosphere
8. Surface (100 km down to 1 m) — terrain tiles

## Real data sources

| Level | Real data | Generated |
| --- | --- | --- |
| Edge | Planck CMB all-sky map | — |
| Cosmic web | GLADE+ (~22.5M galaxies, ~750k quasars; 6 GB text) — pack | Faint galaxies beyond catalog reach, filaments |
| Nearby galaxies | Cosmicflows-4 (55,877 galaxies with measured distances) | 3D shapes by type |
| Nebulae/clusters | OpenNGC (NGC/IC, CC BY-SA 4.0) | Volume shapes |
| Milky Way | No complete real map | Bar, arms, bulge, dust model |
| Stars | AT-HYG v3.3 (>2.5M stars, CC BY-SA 4.0); Gaia DR3 (1.81B sources) as optional slices; Gaia DR4 due 2 Dec 2026 | ~100+ billion unlisted stars, matched to model density |
| Exoplanets | NASA Exoplanet Archive (>6,000 confirmed) | Planets for all other stars |
| Solar system | JPL approximate Keplerian elements (ssd.jpl.nasa.gov/planets/approx_pos.html); Solar System Scope textures (CC BY 4.0) | Small moons, asteroids |
| Surfaces | NASA low-res elevation/color for Earth, Moon, Mars | Fractal detail; all other worlds |

Write data scripts so swapping a catalog means changing one download URL.

## Rules for generated data

1. **Same place, same result.** Seed every generator from the cell ID (hash of level + x + y + z). Never the clock, never unseeded `Random()`.
2. **Real always wins.** Load real objects first; generators fill only empty space and skip anything near a real object.
3. **Match the statistics.** Real star-type mix (mostly red dwarfs), real exoplanet occurrence odds, real galaxy types by environment.
4. **Parents decide children.** Galaxy type → star density; star mass/age → planets; planet size/temperature/distance → surface, atmosphere, roughness.
5. **Label everything.** `source = REAL | GENERATED`; info cards show a badge; settings can hide generated objects.
6. **Names for the unnamed.** Catalog-style names from cell ID, e.g. `GX-4F2A-0193 b`, searchable and bookmarkable.
7. **Version the generator.** Store a generator version; bump it when rules change so old bookmarks can warn.

## Rounds

| # | Round | Ask for | Phone checklist |
| --- | --- | --- | --- |
| 0 | App that builds itself | Empty Kotlin app, black screen "Universe Explorer"; workflow builds APK on push and attaches it to a Release; fixed keystore; crash screen with Copy | Workflow green + Release with APK; installs and opens; second build installs over first |
| 1 | 3D view you can touch | GLES 3.0 view, one lit sphere; one finger orbits, pinch zooms, two fingers pan; FPS counter | Smooth, ~60 fps; rotating phone doesn't crash |
| 2 | Scale engine (hardest) | Floating origin, doubles, frames, log depth; zoom speed scales with distance to nearest object; scale readout m/km/AU/ly/Mly/Gly; test scene: 1 m cube on Earth-sized sphere + marker 10 billion ly away | Pinch cube→marker→back under a minute; no shaking/flicker; readout units correct |
| 3 | Real solar system | Sun, 8 planets, Pluto, major moons from JPL elements, Solar System Scope textures; time controls; orbit lines; tap info card; Fly To | Today's positions roughly match a planetarium site; Saturn rings, Moon orbits Earth; Fly To smooth |
| 4 | Data factory | `tools/` Python + manual `build-data.yml`: download catalog, cut into binary tiles per cell, upload as Release files; start with AT-HYG ~119k subset; app reads tiles from assets | Data workflow green; log prints tile count and total size |
| 5 | Real stars | Points with real color/brightness; names; tap cards; close-up becomes glowing sphere; constellation lines toggle | Orion recognizable from Earth; Sirius glows blue-white, bigger than the Sun |
| 6 | Systems + exoplanets | NASA Exoplanet Archive pipeline; real planets; generated planets elsewhere per rules | TRAPPIST-1 has 7 planets; random star keeps same planets after restart; Real/Generated badge |
| 7 | Planet surfaces | Cube-sphere LOD terrain; real low-res Earth/Moon/Mars + fractal detail; generated terrain elsewhere; atmosphere glow; camera to ~1 m altitude | One zoom from Mars orbit to Olympus Mons slopes; can land on a generated exoplanet |
| 8 | Milky Way | Galaxy model; generated stars fill cells to model density; glow far, stars near | Zoom out shows galaxy + "You are here"; zoom back in returns to same real stars |
| 9 | Nebulae + clusters | OpenNGC as glowing volumes / star groups | Orion Nebula below Orion's belt; Pleiades tight cluster |
| 10 | Other galaxies | Cosmicflows-4 at measured distances; detailed Local Group; enter any galaxy (reuses Milky Way machinery with own seed) | Fly into Andromeda and land on a generated planet; same planet on return |
| 11 | Whole universe | Downloadable GLADE+ pack; generated filaments; Planck shell; points fade to density clouds when zooming out | Fully zoomed out shows ball with glowing shell; >30 fps throughout |
| 12 | Data packs | Data screen: packs with size, Download/Delete, storage used, Wi-Fi-only switch | Full AT-HYG pack adds stars; deleting it keeps app working |
| 13 | Finding your way | Search (incl. generated names), bookmarks, Home, breadcrumb, scale bar | "Kepler-22 b" search flies there; bookmarked generated planet reopens same place |
| 14 | Polish + performance | Low/Medium/High presets, battery saver, first-launch tips, one plain Settings screen; profile and fix slowest parts; Credits screen; remove test-crash button | 30 min no crash; not hot on Medium; someone else can use it unaided |

## Credits required (Credits screen in Round 14)

- AT-HYG / HYG — CC BY-SA 4.0 (tiles made from it must also be CC BY-SA)
- OpenNGC — CC BY-SA 4.0
- Solar System Scope textures — CC BY 4.0
- NASA Exoplanet Archive — credit the archive
- Gaia — check Gaia Archive terms before shipping a Gaia pack
- GLADE+ and Cosmicflows-4 — cite their papers

App code is MIT licensed; share-alike applies only to data files derived from those catalogs.
