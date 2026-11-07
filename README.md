# Universe Explorer

An Android app for flying through the whole universe in 3D: from standing on a planet's surface to the edge of the observable universe, without loading screens. Real catalogue data wherever it exists; repeatable generated data everywhere else, always labelled.

**Download:** the newest APK is on [Releases](../../releases/latest). Install it over the old one.

## What's inside

- **Solar system:** real orbits (JPL elements), rotation, textures, Saturn's rings, 21 major moons, time controls.
- **Walkable worlds:** real elevation for Earth (ETOPO1), Mars (MOLA) and the Moon (LOLA) plus fractal detail down to metres; generated terrain on every other rocky world.
- **Stars:** 109,000 real stars (HYG), plus 750,000 more as a download (AT-HYG/Gaia); constellation lines.
- **Exoplanets:** 6,400+ real planets (NASA Exoplanet Archive); every other star gets generated planets, moons and rings.
- **Milky Way:** a measured-parameter model with ~190 billion stars; individual stars generated around you, never where real ones are.
- **Nebulae and clusters:** 1,365 OpenNGC objects.
- **Galaxies:** 62,000 with real distances (Cosmicflows-4, OpenNGC), all enterable; millions more as a download (GLADE+).
- **The whole universe:** generated cosmic web to 46 billion light years, wrapped in the Planck CMB map.
- Search, bookmarks, breadcrumbs, data packs, quality presets, battery saver.

## How it's built

- Plan for AI sessions: [`docs/PLAN.md`](docs/PLAN.md). Progress and checklists: [`docs/PROGRESS.md`](docs/PROGRESS.md).
- Every push to `main` builds a signed APK with GitHub Actions (`.github/workflows/build-apk.yml`).
- App data is built by `tools/build_data.py` in `.github/workflows/build-data.yml` (Actions tab → Build data → Run workflow) and published to the `data-v1` release.

Code: MIT. Data credits are in the app (Menu → Credits) and in the plan.
