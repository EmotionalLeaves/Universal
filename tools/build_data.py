#!/usr/bin/env python3
"""
Universe Explorer data factory.

Downloads real catalogs, converts them into small binary/TSV files the app reads, and writes:
  OUT/base/...   -> zipped into base-data.zip and bundled inside the APK (assets/)
  OUT/packs/...  -> optional downloadable data packs (GitHub Release files)

Every step is independent: if one source is down, the step logs a warning and the app falls
back to generated data for that part. Swapping a catalog means changing one URL below.

Run:  python3 tools/build_data.py --out build/data [--packs] [--only stars,planets,...]
"""
import argparse
import csv
import gzip
import io
import json
import math
import os
import struct
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import re
import zlib

# ----------------------------------------------------------------------------- sources

HYG_URL = "https://codeberg.org/astronexus/hyg/media/branch/main/data/hyg/CURRENT/hyg_v44.csv.gz"
ATHYG_PACK_URL = "https://codeberg.org/astronexus/athyg/media/branch/main/data/subsets/athyg_40_reduced_m11.csv.gz"
EXO_URL = ("https://exoplanetarchive.ipac.caltech.edu/TAP/sync?query=select+pl_name,hostname,pl_orbsmax,pl_rade,"
           "pl_bmasse,pl_orbper,pl_orbeccen,pl_orbincl,pl_eqt,ra,dec,sy_dist,st_teff,st_rad,st_mass,st_spectype,"
           "hd_name,hip_name,sy_pnum,disc_year,discoverymethod+from+pscomppars&format=csv")
OPENNGC_URLS = [
    "https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/NGC.csv",
    "https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/addendum.csv",
]
VIZIER = ["https://vizier.cfa.harvard.edu/viz-bin/asu-tsv", "https://vizier.cds.unistra.fr/viz-bin/asu-tsv",
          "https://vizier.iucaa.in/viz-bin/asu-tsv", "https://vizier.nao.ac.jp/viz-bin/asu-tsv"]
CF4_SOURCE = "J/ApJ/944/94/table2"
GLADE_SOURCE = "VII/291/gladep"
PLANCK_URL = ("https://irsa.ipac.caltech.edu/data/Planck/release_2/all-sky-maps/maps/component-maps/cmb/"
              "COM_CMB_IQU-smica_1024_R2.02_full.fits")
ETOPO_URL = ("https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO1/data/ice_surface/grid_registered/"
             "georeferenced_tiff/ETOPO1_Ice_g_geotiff.zip")
MOLA_URL = "https://planetarymaps.usgs.gov/mosaic/Mars_MGS_MOLA_DEM_mosaic_global_463m.tif"
CONSTELLATION_URLS = [
    "https://raw.githubusercontent.com/Stellarium/stellarium/master/skycultures/modern/index.json",
    "https://raw.githubusercontent.com/Stellarium/stellarium/master/skycultures/modern_st/index.json",
    "https://raw.githubusercontent.com/Stellarium/stellarium/v23.4/skycultures/modern/constellationship.fab",
]
CONSTELLATION_NAMES = {
    "And": "Andromeda", "Ant": "Antlia", "Aps": "Apus", "Aqr": "Aquarius", "Aql": "Aquila", "Ara": "Ara",
    "Ari": "Aries", "Aur": "Auriga", "Boo": "Boötes", "Cae": "Caelum", "Cam": "Camelopardalis", "Cnc": "Cancer",
    "CVn": "Canes Venatici", "CMa": "Canis Major", "CMi": "Canis Minor", "Cap": "Capricornus", "Car": "Carina",
    "Cas": "Cassiopeia", "Cen": "Centaurus", "Cep": "Cepheus", "Cet": "Cetus", "Cha": "Chamaeleon",
    "Cir": "Circinus", "Col": "Columba", "Com": "Coma Berenices", "CrA": "Corona Australis",
    "CrB": "Corona Borealis", "Crv": "Corvus", "Crt": "Crater", "Cru": "Crux", "Cyg": "Cygnus",
    "Del": "Delphinus", "Dor": "Dorado", "Dra": "Draco", "Equ": "Equuleus", "Eri": "Eridanus", "For": "Fornax",
    "Gem": "Gemini", "Gru": "Grus", "Her": "Hercules", "Hor": "Horologium", "Hya": "Hydra", "Hyi": "Hydrus",
    "Ind": "Indus", "Lac": "Lacerta", "Leo": "Leo", "LMi": "Leo Minor", "Lep": "Lepus", "Lib": "Libra",
    "Lup": "Lupus", "Lyn": "Lynx", "Lyr": "Lyra", "Men": "Mensa", "Mic": "Microscopium", "Mon": "Monoceros",
    "Mus": "Musca", "Nor": "Norma", "Oct": "Octans", "Oph": "Ophiuchus", "Ori": "Orion", "Pav": "Pavo",
    "Peg": "Pegasus", "Per": "Perseus", "Phe": "Phoenix", "Pic": "Pictor", "Psc": "Pisces",
    "PsA": "Piscis Austrinus", "Pup": "Puppis", "Pyx": "Pyxis", "Ret": "Reticulum", "Sge": "Sagitta",
    "Sgr": "Sagittarius", "Sco": "Scorpius", "Scl": "Sculptor", "Sct": "Scutum", "Ser": "Serpens",
    "Sex": "Sextans", "Tau": "Taurus", "Tel": "Telescopium", "Tri": "Triangulum", "TrA": "Triangulum Australe",
    "Tuc": "Tucana", "UMa": "Ursa Major", "UMi": "Ursa Minor", "Vel": "Vela", "Vir": "Virgo", "Vol": "Volans",
    "Vul": "Vulpecula",
}
LOLA_URL = "https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/ldem_3_8bit.jpg"

THREEX = "https://raw.githubusercontent.com/jeromeetienne/threex.planets/master/images/"
THREEJS = "https://raw.githubusercontent.com/mrdoob/three.js/dev/examples/textures/planets/"
SSS = "https://raw.githubusercontent.com/homer-jay/solar-system-textures/main/"
SSS2 = "https://raw.githubusercontent.com/homer-jay/solar-system-textures/master/"

# name -> list of candidate URLs (first that works wins)
TEXTURES = {
    "sun.jpg": [SSS + "sun.jpg", SSS2 + "sun.jpg", THREEX + "sunmap.jpg"],
    "mercury.jpg": [THREEX + "mercurymap.jpg"],
    "venus.jpg": [THREEX + "venusmap.jpg"],
    "earth.jpg": [THREEJS + "earth_day_4096.jpg", THREEJS + "earth_atmos_2048.jpg", THREEX + "earthmap1k.jpg"],
    "earth_night.jpg": [THREEJS + "earth_night_4096.jpg", THREEJS + "earth_lights_2048.png"],
    "earth_clouds.png": [THREEJS + "earth_clouds_1024.png"],
    "moon.jpg": [THREEJS + "moon_1024.jpg", SSS + "moon.jpg", THREEX + "moonmap1k.jpg"],
    "mars.jpg": [THREEX + "marsmap1k.jpg"],
    "jupiter.jpg": [THREEX + "jupitermap.jpg"],
    "saturn.jpg": [THREEX + "saturnmap.jpg"],
    "saturn_ring.jpg": [THREEX + "saturnringcolor.jpg"],
    "uranus.jpg": [THREEX + "uranusmap.jpg"],
    "neptune.jpg": [THREEX + "neptunemap.jpg"],
    "pluto.jpg": [THREEX + "plutomap1k.jpg"],
}
BUMP_FALLBACK = {
    "earth": THREEX + "earthbump1k.jpg",
    "mars": THREEX + "marsbump1k.jpg",
    "moon": THREEX + "moonbump1k.jpg",
}

PC_PER_MPC = 1e6
H0 = 70.0  # km/s/Mpc for redshift distances
C_KMS = 299792.458

# Curated distances (light years) for famous deep-sky objects that OpenNGC has no distance for.
DSO_DISTANCES_LY = {
    "M1": 6500, "M2": 37500, "M3": 33900, "M4": 7200, "M5": 24500, "M6": 1600, "M7": 980, "M8": 4100,
    "M9": 25800, "M10": 14300, "M11": 6200, "M12": 15700, "M13": 22200, "M14": 30300, "M15": 33600,
    "M16": 7000, "M17": 5500, "M18": 4900, "M19": 28700, "M20": 5200, "M21": 4250, "M22": 10600,
    "M23": 2150, "M24": 10000, "M25": 2000, "M26": 5000, "M27": 1360, "M28": 17900, "M29": 4000,
    "M30": 27100, "M34": 1500, "M35": 2800, "M36": 4100, "M37": 4500, "M38": 4200, "M39": 1000,
    "M41": 2300, "M42": 1344, "M43": 1600, "M44": 577, "M45": 444, "M46": 5400, "M47": 1600,
    "M48": 1500, "M50": 3200, "M52": 5000, "M53": 58000, "M54": 87400, "M55": 17600, "M56": 32900,
    "M57": 2300, "M62": 22500, "M67": 2700, "M68": 33600, "M69": 29700, "M70": 29300, "M71": 13000,
    "M72": 54600, "M75": 67500, "M76": 2500, "M78": 1350, "M79": 41000, "M80": 32600, "M92": 26700,
    "M93": 3600, "M97": 2030, "M103": 10000, "M107": 20900,
    "NGC7000": 2590, "IC1396": 2400, "NGC2237": 5200, "NGC2244": 5200, "NGC3372": 8500, "NGC6543": 3300,
    "NGC7293": 650, "NGC2392": 6500, "NGC869": 7500, "NGC884": 7500, "NGC104": 13000, "NGC5139": 17000,
    "NGC6960": 2400, "NGC6992": 2400, "NGC6995": 2400, "IC434": 1375, "NGC2070": 160000, "NGC7635": 7100,
    "IC5146": 4000, "NGC281": 9500, "NGC1499": 1000, "IC443": 5000, "NGC6888": 5000, "NGC2264": 2500,
    "NGC1977": 1500, "NGC2024": 1350, "NGC6826": 2200, "NGC3242": 1400, "NGC6302": 3400, "NGC2440": 4000,
    "NGC6826": 2200, "NGC246": 1600, "NGC6781": 2500, "NGC40": 3500, "IC2944": 6000, "NGC3603": 22000,
    "NGC6334": 5500, "NGC6357": 5900, "NGC2359": 12000, "IC1805": 7500, "IC1848": 7500, "NGC1333": 960,
    "NGC6231": 5200, "NGC2516": 1300, "IC2602": 480, "IC2391": 500, "NGC3532": 1300, "NGC4755": 6400,
    "NGC6397": 7800, "NGC6752": 13000, "NGC362": 28000, "NGC6388": 35000, "NGC2808": 31000,
    "B033": 1375, "C009": 2400,
}

# Absolute magnitudes / typical sizes used to estimate missing distances (flagged as generated).
DSO_TYPE_RANGES_LY = {
    "OCl": (800, 9000), "GCl": (10000, 60000), "Cl+N": (1500, 8000), "PN": (1500, 9000),
    "HII": (1500, 9000), "EmN": (1500, 8000), "Neb": (1000, 6000), "RfN": (400, 3000),
    "SNR": (2000, 15000), "DrkN": (400, 2000), "*Ass": (1000, 6000),
}

LOG = []


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    LOG.append(line)


def fetch(url, timeout=600, retries=3):
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "UniverseExplorer-data/1.0"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:
            last = e
            log(f"  fetch failed ({i + 1}/{retries}) {url}: {e}")
            time.sleep(3 * (i + 1))
    raise last


def fetch_to(url, path, timeout=3600):
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        return path
    req = urllib.request.Request(url, headers={"User-Agent": "UniverseExplorer-data/1.0"})
    tmp = path + ".part"
    with urllib.request.urlopen(req, timeout=timeout) as r, open(tmp, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    os.replace(tmp, path)
    return path


def fnum(s, default=None):
    try:
        if s is None:
            return default
        s = s.strip()
        if s == "":
            return default
        v = float(s)
        if math.isnan(v):
            return default
        return v
    except Exception:
        return default


# ----------------------------------------------------------------------------- colours

def bv_to_temp(bv):
    bv = max(-0.4, min(2.0, bv))
    return 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62))


def temp_to_rgb(t):
    """Approximate blackbody colour (Tanner Helland), returns 0..255 ints."""
    t = max(1000.0, min(40000.0, t)) / 100.0
    if t <= 66:
        r = 255
        g = 99.4708025861 * math.log(t) - 161.1195681661
        b = 0 if t <= 19 else 138.5177312231 * math.log(t - 10) - 305.0447927307
    else:
        r = 329.698727446 * ((t - 60) ** -0.1332047592)
        g = 288.1221695283 * ((t - 60) ** -0.0755148492)
        b = 255
    c = lambda v: int(max(0, min(255, v)))
    return c(r), c(g), c(b)


def spect_to_temp(sp):
    if not sp:
        return None
    table = {"O": 35000, "B": 15000, "A": 8800, "F": 6800, "G": 5700, "K": 4500, "M": 3300, "L": 2000,
             "T": 1300, "Y": 500, "W": 50000, "C": 3000, "S": 3200, "D": 12000}
    c = sp.strip()[:1].upper()
    return table.get(c)


# ----------------------------------------------------------------------------- stars

def write_star_tiles(stars, path, tile_pc):
    """
    stars: list of (x, y, z, absmag, r, g, b, flags, id)   [pc, equatorial]
    File layout (little endian):
      magic 'UXST', int32 version=1, int32 tileCount, float32 tileSize
      per tile: float64 ax, ay, az (anchor, pc), int32 first, int32 count
      per star (24 bytes): float32 dx, dy, dz, absmag; uint8 r, g, b, flags; int32 id
    """
    tiles = {}
    for s in stars:
        key = (math.floor(s[0] / tile_pc), math.floor(s[1] / tile_pc), math.floor(s[2] / tile_pc))
        tiles.setdefault(key, []).append(s)
    keys = sorted(tiles.keys())
    with open(path, "wb") as f:
        f.write(b"UXST")
        f.write(struct.pack("<iif", 1, len(keys), tile_pc))
        first = 0
        for k in keys:
            ax, ay, az = ((k[0] + 0.5) * tile_pc, (k[1] + 0.5) * tile_pc, (k[2] + 0.5) * tile_pc)
            f.write(struct.pack("<dddii", ax, ay, az, first, len(tiles[k])))
            first += len(tiles[k])
        for k in keys:
            ax, ay, az = ((k[0] + 0.5) * tile_pc, (k[1] + 0.5) * tile_pc, (k[2] + 0.5) * tile_pc)
            for s in tiles[k]:
                f.write(struct.pack("<ffffBBBBi", s[0] - ax, s[1] - ay, s[2] - az, s[3], s[4], s[5], s[6], s[7], s[8]))
    log(f"  wrote {path}: {len(stars)} stars in {len(keys)} tiles ({os.path.getsize(path) / 1e6:.1f} MB)")
    return len(keys)


def build_hyg(base):
    log("Stars: HYG v4.4")
    raw = gzip.decompress(fetch(HYG_URL)).decode("utf-8")
    rd = csv.DictReader(io.StringIO(raw))
    stars = []
    names = []
    for row in rd:
        sid = int(row["id"])
        if sid == 0:
            continue  # the Sun is drawn separately
        x, y, z = fnum(row["x"]), fnum(row["y"]), fnum(row["z"])
        dist = fnum(row["dist"])
        if x is None or dist is None or dist <= 0 or dist >= 100000:
            continue
        absmag = fnum(row["absmag"], 5.0)
        ci = fnum(row["ci"])
        t = bv_to_temp(ci) if ci is not None else (spect_to_temp(row["spect"]) or 5800)
        r, g, b = temp_to_rgb(t)
        proper = row.get("proper", "") or ""
        bayer = row.get("bayer", "") or ""
        flam = row.get("flam", "") or ""
        con = row.get("con", "") or ""
        desig = ""
        if bayer and con:
            desig = f"{bayer} {con}"
        elif flam and con:
            desig = f"{flam} {con}"
        flags = 1 if proper else 0
        stars.append((x, y, z, absmag, r, g, b, flags, sid))
        names.append("\t".join([
            str(sid), proper, desig, row.get("hip", "") or "", row.get("hd", "") or "", row.get("gl", "") or "",
            row.get("spect", "") or "", f"{dist:.4f}", row.get("mag", "") or "", f"{t:.0f}",
            row.get("lum", "") or "", row.get("base", "") or "", row.get("comp_primary", "") or "",
        ]))
    os.makedirs(os.path.join(base, "stars"), exist_ok=True)
    n_tiles = write_star_tiles(stars, os.path.join(base, "stars", "hyg.bin"), 200.0)
    with open(os.path.join(base, "stars", "hyg_names.tsv"), "w", encoding="utf-8") as f:
        f.write("id\tproper\tdesig\thip\thd\tgl\tspect\tdist_pc\tmag\tteff\tlum\tbase\tprimary\n")
        f.write("\n".join(names))
    log(f"Stars: {len(stars)} real stars, {n_tiles} tiles")
    return {"hyg_stars": len(stars), "hyg_tiles": n_tiles}


def build_athyg_pack(packs):
    log("Stars pack: AT-HYG v4.0 (to magnitude 11)")
    path = os.path.join("/tmp", "athyg_m11.csv.gz")
    fetch_to(ATHYG_PACK_URL, path)
    stars = []
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        rd = csv.DictReader(fh)
        for row in rd:
            try:
                sid = int(row["id"])
            except Exception:
                continue
            if row.get("hyg"):
                continue  # already in the base HYG set
            x, y, z = fnum(row.get("x0")), fnum(row.get("y0")), fnum(row.get("z0"))
            dist = fnum(row.get("dist"))
            if x is None or dist is None or dist <= 0 or dist >= 50000:
                continue
            absmag = fnum(row.get("absmag"), 5.0)
            ci = fnum(row.get("ci"))
            t = bv_to_temp(ci) if ci is not None else (spect_to_temp(row.get("spect")) or 5800)
            r, g, b = temp_to_rgb(t)
            stars.append((x, y, z, absmag, r, g, b, 2, 10_000_000 + sid))
    os.makedirs(os.path.join(packs, "stars-athyg"), exist_ok=True)
    out = os.path.join(packs, "stars-athyg", "athyg.bin")
    n_tiles = write_star_tiles(stars, out, 200.0)
    log(f"Stars pack: {len(stars)} extra stars, {n_tiles} tiles")
    return {"athyg_extra_stars": len(stars), "athyg_tiles": n_tiles}


def build_constellations(base):
    """Stick figures as pairs of Hipparcos numbers, from Stellarium's modern sky culture."""
    log("Constellations: Stellarium modern sky culture")
    lines_out = []
    for url in CONSTELLATION_URLS:
        try:
            txt = fetch(url, retries=2).decode("utf-8")
        except Exception:
            continue
        if url.endswith(".json"):
            data = json.loads(txt)
            for c in data.get("constellations", []):
                abbr = c.get("id", "").split()[-1]
                pairs = []
                for poly in c.get("lines", []):
                    hips = [h for h in poly if isinstance(h, int)]
                    for a, b in zip(hips, hips[1:]):
                        pairs += [a, b]
                if pairs:
                    lines_out.append((abbr, pairs))
        else:
            for line in txt.split("\n"):
                p = line.split()
                if len(p) < 4:
                    continue
                abbr = p[0]
                nums = [int(x) for x in p[2:] if x.isdigit()]
                lines_out.append((abbr, nums))
        if lines_out:
            log(f"  from {url}")
            break
    if not lines_out:
        raise RuntimeError("no constellation source reachable")
    os.makedirs(os.path.join(base, "stars"), exist_ok=True)
    with open(os.path.join(base, "stars", "constellations.tsv"), "w", encoding="utf-8") as f:
        for abbr, pairs in lines_out:
            f.write(f"{abbr}\t{CONSTELLATION_NAMES.get(abbr, abbr)}\t{' '.join(str(x) for x in pairs)}\n")
    log(f"Constellations: {len(lines_out)} figures")
    return {"constellations": len(lines_out)}


# ----------------------------------------------------------------------------- exoplanets

def build_exoplanets(base):
    log("Exoplanets: NASA Exoplanet Archive (pscomppars)")
    raw = fetch(EXO_URL).decode("utf-8")
    rows = list(csv.DictReader(io.StringIO(raw)))
    if not rows or "pl_name" not in rows[0]:
        raise RuntimeError("unexpected exoplanet response: " + raw[:300])
    os.makedirs(os.path.join(base, "planets"), exist_ok=True)
    cols = ["pl_name", "hostname", "pl_orbsmax", "pl_rade", "pl_bmasse", "pl_orbper", "pl_orbeccen", "pl_orbincl",
            "pl_eqt", "ra", "dec", "sy_dist", "st_teff", "st_rad", "st_mass", "st_spectype", "hd_name", "hip_name",
            "sy_pnum", "disc_year", "discoverymethod"]
    with open(os.path.join(base, "planets", "exoplanets.tsv"), "w", encoding="utf-8") as f:
        f.write("\t".join(cols) + "\n")
        for r in rows:
            f.write("\t".join((r.get(c) or "").replace("\t", " ") for c in cols) + "\n")
    hosts = len(set(r["hostname"] for r in rows))
    log(f"Exoplanets: {len(rows)} planets around {hosts} stars")
    return {"exoplanets": len(rows), "exo_hosts": hosts}


# ----------------------------------------------------------------------------- deep sky (OpenNGC)

import re
_CAT = re.compile(r"^(NGC|IC)0*(\d+)(.*)$")


def catalog_key(name):
    mm = _CAT.match(name)
    return f"{mm.group(1)}{mm.group(2)}" if mm else name


def pretty_name(name):
    mm = _CAT.match(name)
    if not mm:
        return name
    return f"{mm.group(1)} {mm.group(2)}{mm.group(3)}"


def parse_sexa(s, hours):
    s = (s or "").strip()
    if not s:
        return None
    sign = -1 if s.startswith("-") else 1
    p = s.replace("+", "").replace("-", "").split(":")
    v = float(p[0]) + float(p[1]) / 60 + float(p[2]) / 3600
    v *= sign
    return v * 15 if hours else v


def build_dso(base):
    log("Deep sky: OpenNGC")
    out_rows = []
    galaxies = []
    import random
    for url in OPENNGC_URLS:
        txt = fetch(url).decode("utf-8")
        rd = csv.DictReader(io.StringIO(txt), delimiter=";")
        for row in rd:
            t = row["Type"]
            name = row["Name"]
            if t in ("Dup", "NonEx", "*", "**", "Other", "Nova"):
                continue
            ra = parse_sexa(row["RA"], True)
            dec = parse_sexa(row["Dec"], False)
            if ra is None or dec is None:
                continue
            m = (row.get("M") or "").lstrip("0")
            key = "M" + m if m else catalog_key(name)
            common = (row.get("Common names") or "").split(",")[0].strip()
            maj = fnum(row.get("MajAx"), 0.0)
            minax = fnum(row.get("MinAx"), maj)
            pa = fnum(row.get("PosAng"), 0.0)
            vmag = fnum(row.get("V-Mag"))
            bmag = fnum(row.get("B-Mag"))
            mag = vmag if vmag is not None else bmag
            pretty = f"M{m}" if m else pretty_name(name)
            ident = row.get("Identifiers") or ""
            pgc = None
            for idt in ident.split(","):
                idt = idt.strip()
                if idt.startswith("PGC "):
                    try:
                        pgc = int(idt[4:])
                    except Exception:
                        pass
            if t in ("G", "GPair", "GTrpl", "GGroup"):
                rv = fnum(row.get("RadVel"))
                z = fnum(row.get("Redshift"))
                galaxies.append({
                    "name": pretty, "ngc": name, "common": common, "ra": ra, "dec": dec, "maj": maj, "min": minax,
                    "pa": pa, "mag": mag, "hubble": row.get("Hubble") or "", "pgc": pgc, "rv": rv, "z": z,
                    "m": m,
                })
                continue
            dist_ly = None
            src = "R"
            for k in (key, catalog_key(name), f"M{m}" if m else None):
                if k and k in DSO_DISTANCES_LY:
                    dist_ly = DSO_DISTANCES_LY[k]
                    break
            if dist_ly is None:
                lo, hi = DSO_TYPE_RANGES_LY.get(t, (1000, 8000))
                rnd = random.Random(zlib.crc32(name.encode()))
                dist_ly = lo * (hi / lo) ** rnd.random()
                src = "G"  # estimated distance
            out_rows.append([pretty, name, t, f"{ra:.5f}", f"{dec:.5f}", f"{maj:.2f}", f"{minax:.2f}", f"{pa:.0f}",
                             "" if mag is None else f"{mag:.2f}", m, common, f"{dist_ly:.0f}", src])
    os.makedirs(os.path.join(base, "dso"), exist_ok=True)
    with open(os.path.join(base, "dso", "dso.tsv"), "w", encoding="utf-8") as f:
        f.write("name\tid\ttype\tra\tdec\tmaj_arcmin\tmin_arcmin\tpa\tmag\tmessier\tcommon\tdist_ly\tdist_src\n")
        for r in out_rows:
            f.write("\t".join(str(x) for x in r) + "\n")
    log(f"Deep sky: {len(out_rows)} nebulae/clusters, {len(galaxies)} NGC/IC galaxies")
    return out_rows, galaxies


# ----------------------------------------------------------------------------- VizieR helpers

def vizier_tsv(source, extra, timeout=900, maxrows="unlimited"):
    last = None
    for base in VIZIER:
        url = f"{base}?-source={source}&-out.max={maxrows}&-oc.form=d{extra}"
        try:
            log(f"  VizieR {url}")
            txt = fetch(url, timeout=timeout, retries=2).decode("utf-8", "replace")
            lines = [l for l in txt.split("\n") if l and not l.startswith("#")]
            if len(lines) < 4:
                errs = [l for l in txt.split("\n") if "rror" in l or "not found" in l.lower()][:5]
                raise RuntimeError(f"empty VizieR response: {errs or txt[:400]!r}")
            header = lines[0].split("\t")
            data = [l.split("\t") for l in lines[3:]]  # skip units + dashes rows
            return header, data
        except Exception as e:
            log(f"  VizieR mirror failed: {e}")
            last = e
    raise last


def build_galaxies(base, ngc_galaxies):
    log("Galaxies: Cosmicflows-4 + OpenNGC")
    by_pgc = {g["pgc"]: g for g in ngc_galaxies if g["pgc"]}
    out = []
    used_pgc = set()
    try:
        header, data = vizier_tsv(CF4_SOURCE, "&-out.all&-out.add=_RA,_DE")
        log(f"  CF4 columns: {header}")
        idx = {h.strip(): i for i, h in enumerate(header)}

        def col(*names):
            for n in names:
                if n in idx:
                    return idx[n]
            return None
        i_ra, i_de = col("_RAJ2000", "_RA", "RAJ2000"), col("_DEJ2000", "_DE", "DEJ2000")
        i_pgc = col("PGC", "pgc")
        i_dm = col("DM", "DMav", "<DM>", "DMtot")
        i_d = col("Dist", "D", "dist")
        log(f"  CF4 using ra={i_ra} de={i_de} pgc={i_pgc} dm={i_dm} d={i_d}")
        for r in data:
            try:
                ra = float(r[i_ra]); de = float(r[i_de])
                if i_dm is not None and r[i_dm].strip():
                    d_mpc = 10 ** ((float(r[i_dm]) + 5) / 5) / 1e6
                elif i_d is not None and r[i_d].strip():
                    d_mpc = float(r[i_d])
                else:
                    continue
                pgc = int(r[i_pgc]) if i_pgc is not None and r[i_pgc].strip() else None
            except Exception:
                continue
            g = by_pgc.get(pgc)
            name = (g["common"] or g["name"]) if g else (f"PGC {pgc}" if pgc else "")
            out.append(make_galaxy(ra, de, d_mpc, g, name, pgc, "R"))
            if pgc:
                used_pgc.add(pgc)
        log(f"  CF4: {len(out)} galaxies with measured distances")
    except Exception as e:
        log(f"  WARNING Cosmicflows-4 unavailable: {e}")
    # NGC/IC galaxies not in CF4: distance from redshift (Hubble law)
    added = 0
    for g in ngc_galaxies:
        if g["pgc"] in used_pgc:
            continue
        v = g["rv"] if g["rv"] is not None else (g["z"] * C_KMS if g["z"] is not None else None)
        if v is None or v < 300:
            continue
        d_mpc = v / H0
        out.append(make_galaxy(g["ra"], g["dec"], d_mpc, g, g["common"] or g["name"], g["pgc"], "Z"))
        added += 1
    log(f"  OpenNGC redshift distances: {added} more galaxies")
    os.makedirs(os.path.join(base, "galaxies"), exist_ok=True)
    with open(os.path.join(base, "galaxies", "galaxies.tsv"), "w", encoding="utf-8") as f:
        f.write("name\tpgc\tra\tdec\tdist_mpc\tabsmag\tsize_kpc\ttype\tpa\tincl\tdist_src\tngc\n")
        for g in out:
            f.write("\t".join(str(x) for x in g) + "\n")
    log(f"Galaxies: {len(out)} total")
    return {"galaxies": len(out)}


def make_galaxy(ra, de, d_mpc, g, name, pgc, src):
    absmag = ""
    size_kpc = ""
    gtype = ""
    pa = ""
    incl = ""
    ngc = ""
    if g:
        if g["mag"] is not None and d_mpc > 0:
            absmag = f"{g['mag'] - 5 * math.log10(d_mpc * 1e6) + 5:.2f}"
        if g["maj"]:
            size_kpc = f"{math.radians(g['maj'] / 60.0) * d_mpc * 1000 / 2:.2f}"  # radius
        gtype = g["hubble"]
        pa = f"{g['pa']:.0f}"
        if g["maj"] and g["min"]:
            q = max(0.05, min(1.0, g["min"] / g["maj"]))
            incl = f"{math.degrees(math.acos(q)):.0f}"
        ngc = g["ngc"]
    return [name, pgc or "", f"{ra:.5f}", f"{de:.5f}", f"{d_mpc:.4f}", absmag, size_kpc, gtype, pa, incl, src, ngc]


def build_glade_pack(packs):
    """
    Large galaxy pack from GLADE+ (VizieR VII/291), in declination strips.
    Kept: every galaxy within 250 Mpc, then a thinning sample out to 2,000 Mpc so the pack stays phone-sized.
    Format: 'UXGL', int32 version=2, then per galaxy float32 x, y, z (Mpc, equatorial), float32 absolute B mag.
    """
    log("Galaxies pack: GLADE+ (VizieR VII/291)")
    os.makedirs(os.path.join(packs, "galaxies-glade"), exist_ok=True)
    out_path = os.path.join(packs, "galaxies-glade", "glade.bin")
    count = 0
    seen = 0
    cols_logged = False
    with open(out_path, "wb") as f:
        f.write(b"UXGL")
        f.write(struct.pack("<i", 2))
        for dlo in range(-90, 90, 10):
            extra = f"&-out=_RA,_DE,dL,Bmag,Kmag&DEJ2000={dlo}..{dlo + 10}&dL=0..2000"
            try:
                header, data = vizier_tsv(GLADE_SOURCE, extra, timeout=1800)
            except Exception as e:
                log(f"  strip {dlo}: {e}")
                continue
            if not cols_logged:
                log(f"  GLADE+ columns: {header}")
                cols_logged = True
            idx = {h.strip(): i for i, h in enumerate(header)}
            ira = idx.get("_RAJ2000", idx.get("_RA"))
            ide = idx.get("_DEJ2000", idx.get("_DE"))
            for r in data:
                try:
                    ra = float(r[ira]); de = float(r[ide]); d = float(r[idx["dL"]])
                except Exception:
                    continue
                if d <= 0:
                    continue
                seen += 1
                if d > 250:
                    keep = (250.0 / d) ** 1.6
                    h = zlib.crc32(f"{ra:.5f}{de:.5f}".encode()) / 4294967295.0
                    if h > keep:
                        continue
                bm = fnum(r[idx["Bmag"]]) if "Bmag" in idx else None
                absm = bm - 5 * math.log10(d * 1e6) + 5 if bm is not None else -20.5
                cra, sra = math.cos(math.radians(ra)), math.sin(math.radians(ra))
                cde, sde = math.cos(math.radians(de)), math.sin(math.radians(de))
                f.write(struct.pack("<ffff", d * cde * cra, d * cde * sra, d * sde, absm))
                count += 1
            log(f"  strip {dlo}..{dlo + 10}: kept {count} of {seen}")
    log(f"Galaxies pack: {count} GLADE+ galaxies kept of {seen} ({os.path.getsize(out_path) / 1e6:.1f} MB)")
    return {"glade_galaxies": count, "glade_seen": seen}


# ----------------------------------------------------------------------------- textures, heights, CMB

def build_textures(base):
    from PIL import Image
    log("Textures")
    os.makedirs(os.path.join(base, "textures"), exist_ok=True)
    ok = 0
    for name, urls in TEXTURES.items():
        for u in urls:
            try:
                data = fetch(u, retries=2)
                im = Image.open(io.BytesIO(data))
                im.load()
                maxw = 2048
                if im.width > maxw:
                    im = im.resize((maxw, max(1, im.height * maxw // im.width)), Image.LANCZOS)
                out = os.path.join(base, "textures", name)
                if name.endswith(".png"):
                    im.save(out, optimize=True)
                else:
                    im.convert("RGB").save(out, quality=88)
                log(f"  {name}: {im.width}x{im.height} from {u}")
                ok += 1
                break
            except Exception as e:
                log(f"  {name}: failed {u}: {e}")
    return {"textures": ok}


def write_heights(path, arr_m, w, h):
    """int16 metres, row 0 = north, column 0 = longitude -180. Header: 'UXHT', int32 w, int32 h."""
    with open(path, "wb") as f:
        f.write(b"UXHT")
        f.write(struct.pack("<ii", w, h))
        f.write(arr_m.astype("<i2").tobytes())


def build_heights(base):
    import numpy as np
    from PIL import Image
    log("Heights")
    os.makedirs(os.path.join(base, "heights"), exist_ok=True)
    stats = {}
    w, h = 2048, 1024
    # Earth: ETOPO1
    try:
        import rasterio
        from rasterio.enums import Resampling
        zp = fetch_to(ETOPO_URL, "/tmp/etopo1.zip")
        with rasterio.open(f"zip://{zp}!ETOPO1_Ice_g_geotiff.tif") as ds:
            arr = ds.read(1, out_shape=(h, w), resampling=Resampling.average)
        write_heights(os.path.join(base, "heights", "earth.bin"), arr, w, h)
        stats["earth_heights"] = f"ETOPO1 {arr.min()}..{arr.max()} m"
    except Exception as e:
        log(f"  Earth ETOPO1 failed: {e}")
        stats["earth_heights"] = bump_fallback(base, "earth", 8800, -11000)
    log(f"  earth: {stats['earth_heights']}")
    # Mars: MOLA
    try:
        import rasterio
        from rasterio.enums import Resampling
        tp = fetch_to(MOLA_URL, "/tmp/mola.tif")
        with rasterio.open(tp) as ds:
            arr = ds.read(1, out_shape=(h, w), resampling=Resampling.average)
        write_heights(os.path.join(base, "heights", "mars.bin"), arr, w, h)
        stats["mars_heights"] = f"MOLA {arr.min()}..{arr.max()} m"
    except Exception as e:
        log(f"  Mars MOLA failed: {e}")
        stats["mars_heights"] = bump_fallback(base, "mars", 21000, -8200)
    log(f"  mars: {stats['mars_heights']}")
    # Moon: LOLA 8-bit (range -9.15..+10.76 km per the NASA CGI Moon Kit)
    try:
        im = Image.open(io.BytesIO(fetch(LOLA_URL))).convert("L").resize((w, h), Image.BICUBIC)
        a = np.asarray(im).astype("float32") / 255.0
        arr = (-9150 + a * (10760 + 9150)).astype("int16")
        write_heights(os.path.join(base, "heights", "moon.bin"), arr, w, h)
        stats["moon_heights"] = f"LOLA {arr.min()}..{arr.max()} m"
    except Exception as e:
        log(f"  Moon LOLA failed: {e}")
        stats["moon_heights"] = bump_fallback(base, "moon", 10760, -9150)
    log(f"  moon: {stats['moon_heights']}")
    return stats


def bump_fallback(base, body, hi, lo):
    import numpy as np
    from PIL import Image
    try:
        im = Image.open(io.BytesIO(fetch(BUMP_FALLBACK[body]))).convert("L").resize((2048, 1024), Image.BICUBIC)
        a = np.asarray(im).astype("float32") / 255.0
        arr = (lo + a * (hi - lo)).astype("int16")
        write_heights(os.path.join(base, "heights", f"{body}.bin"), arr, 2048, 1024)
        return "bump-map approximation"
    except Exception as e:
        log(f"  {body} bump fallback failed: {e}")
        return "none"


def build_cmb(base):
    import numpy as np
    import healpy as hp
    from PIL import Image
    log("CMB: Planck SMICA (R2.02, nside 1024)")
    fp = fetch_to(PLANCK_URL, "/tmp/planck_smica_1024.fits")
    m = hp.read_map(fp, field=0)
    m = hp.ud_grade(m, 256)
    w, h = 2048, 1024
    lon = (np.arange(w) + 0.5) / w * 360.0 - 180.0
    lat = 90.0 - (np.arange(h) + 0.5) / h * 180.0
    LON, LAT = np.meshgrid(lon, lat)
    # Our sphere uses equatorial coordinates; Planck maps are galactic.
    rot = hp.Rotator(coord=["C", "G"])
    theta = np.radians(90.0 - LAT).ravel()
    phi = np.radians(LON).ravel()
    gt, gp = rot(theta, phi)
    vals = hp.get_interp_val(m, gt, gp).reshape(h, w)
    lim = np.percentile(np.abs(vals), 99.5)
    t = np.clip(vals / lim, -1, 1)
    # Planck-like colour map: blue - white - orange/red
    stops = np.array([[0.05, 0.1, 0.45], [0.2, 0.55, 0.95], [0.95, 0.95, 0.9], [1.0, 0.6, 0.15], [0.7, 0.1, 0.05]])
    x = (t + 1) / 2 * (len(stops) - 1)
    i0 = np.clip(np.floor(x).astype(int), 0, len(stops) - 2)
    fr = (x - i0)[..., None]
    rgb = stops[i0] * (1 - fr) + stops[i0 + 1] * fr
    img = Image.fromarray((rgb * 255).astype("uint8"), "RGB")
    os.makedirs(os.path.join(base, "cmb"), exist_ok=True)
    img.save(os.path.join(base, "cmb", "planck.jpg"), quality=85)
    log("CMB: planck.jpg 2048x1024 (equatorial)")
    return {"cmb": "Planck SMICA"}


# ----------------------------------------------------------------------------- more real objects
# Round 16: asteroids, comets, every known moon, pulsars, quasars, galaxy clusters, descriptions.

SBDB_API = "https://ssd-api.jpl.nasa.gov/sbdb_query.api"
SATS_ELEM_URL = "https://ssd.jpl.nasa.gov/sats/elem/"
SATS_PHYS_URL = "https://ssd.jpl.nasa.gov/sats/phys_par/"
WIKI_SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/"


def sbdb(fields, kind, cdata=None, limit=None):
    q = {"fields": ",".join(fields), "sb-kind": kind}
    if cdata:
        q["sb-cdata"] = json.dumps(cdata, separators=(",", ":"))
    if limit:
        q["limit"] = str(limit)
    url = SBDB_API + "?" + urllib.parse.urlencode(q)
    log(f"  SBDB {url}")
    js = json.loads(fetch(url, timeout=900).decode("utf-8"))
    if "fields" not in js or "data" not in js:
        raise RuntimeError(f"unexpected SBDB reply: {str(js)[:300]}")
    idx = {f: i for i, f in enumerate(js["fields"])}
    return [{f: row[idx[f]] for f in fields if f in idx} for row in js["data"]]


def clean_sb_name(full):
    full = (full or "").strip()
    # "     4 Vesta (A807 FA)" -> "4 Vesta"; "1P/Halley" stays; "(2010 AB)" -> "2010 AB"
    if "(" in full and not full.startswith("("):
        full = full[: full.index("(")].strip()
    return full.strip("() ")


def build_small_bodies(base):
    d = os.path.join(base, "smallbodies")
    os.makedirs(d, exist_ok=True)
    fields = ["full_name", "a", "e", "i", "om", "w", "ma", "epoch", "H", "diameter", "class"]
    try:
        rows = sbdb(fields, "a", {"AND": ["H|LT|14.5"]})
    except Exception as e:
        log(f"  SBDB with H filter failed ({e}); taking the first 40,000 numbered asteroids")
        rows = sbdb(fields, "a", None, 40000)
    n = 0
    with open(os.path.join(d, "asteroids.tsv"), "w", encoding="utf-8") as f:
        f.write("name\ta_au\te\ti\tom\tw\tma\tepoch_jd\tH\tdiam_km\tclass\n")
        for r in rows:
            a = fnum(r.get("a")); e = fnum(r.get("e"))
            if a is None or e is None or e >= 1:
                continue
            vals = [fnum(r.get(k), 0.0) for k in ("i", "om", "w", "ma", "epoch")]
            H = fnum(r.get("H"), 99.0); dm = fnum(r.get("diameter"), -1.0)
            f.write(f"{clean_sb_name(r.get('full_name'))}\t{a:.6f}\t{e:.6f}\t{vals[0]:.4f}\t{vals[1]:.4f}\t{vals[2]:.4f}\t{vals[3]:.4f}\t{vals[4]:.2f}\t{H:.2f}\t{dm:.2f}\t{r.get('class') or ''}\n")
            n += 1
    log(f"  asteroids: {n}")
    cfields = ["full_name", "e", "q", "i", "om", "w", "tp", "epoch", "M1"]
    crow = sbdb(cfields, "c")
    m = 0
    with open(os.path.join(d, "comets.tsv"), "w", encoding="utf-8") as f:
        f.write("name\tq_au\te\ti\tom\tw\ttp_jd\tM1\n")
        for r in crow:
            q = fnum(r.get("q")); e = fnum(r.get("e")); tp = fnum(r.get("tp"))
            if q is None or e is None or tp is None:
                continue
            vals = [fnum(r.get(k), 0.0) for k in ("i", "om", "w")]
            M1 = fnum(r.get("M1"), 15.0)
            f.write(f"{(r.get('full_name') or '').strip()}\t{q:.6f}\t{e:.6f}\t{vals[0]:.4f}\t{vals[1]:.4f}\t{vals[2]:.4f}\t{tp:.4f}\t{M1:.1f}\n")
            m += 1
    log(f"  comets: {m}")
    return {"asteroids": n, "comets": m}


class _Tables(__import__("html.parser").parser.HTMLParser):
    """Collects every table as rows of cell text, plus the last heading seen before each table."""
    def __init__(self):
        super().__init__()
        self.tables, self.heading, self._text, self._row, self._cell, self._in = [], "", [], None, None, None

    def handle_starttag(self, tag, attrs):
        if tag in ("h1", "h2", "h3", "h4", "caption"):
            self._in = tag; self._text = []
        elif tag == "table":
            self.tables.append({"heading": self.heading, "rows": []})
        elif tag == "tr" and self.tables:
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            self._cell = []

    def handle_endtag(self, tag):
        if tag == self._in:
            t = " ".join("".join(self._text).split())
            if t:
                self.heading = t
                if tag == "caption" and self.tables:
                    self.tables[-1]["heading"] = t
            self._in = None
        elif tag in ("td", "th") and self._cell is not None and self._row is not None:
            self._row.append(" ".join("".join(self._cell).split()))
            self._cell = None
        elif tag == "tr" and self._row is not None and self.tables:
            if self._row:
                self.tables[-1]["rows"].append(self._row)
            self._row = None

    def handle_data(self, data):
        if self._in:
            self._text.append(data)
        if self._cell is not None:
            self._cell.append(data)


PLANET_NAMES = ["Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "Pluto"]


def html_tables(url):
    p = _Tables()
    p.feed(fetch(url).decode("utf-8", "replace"))
    return p.tables


def build_moons(base):
    d = os.path.join(base, "planets")
    os.makedirs(d, exist_ok=True)
    tables = html_tables(SATS_ELEM_URL)
    log(f"  sats/elem: {len(tables)} tables")
    out = []
    for t in tables:
        rows = t["rows"]
        if len(rows) < 2:
            continue
        head = [h.lower() for h in rows[0]]
        log(f"   table '{t['heading'][:60]}' header {rows[0][:16]}")
        planet = next((pn for pn in PLANET_NAMES if pn.lower() in t["heading"].lower()), None)

        raw = rows[0]
        toks = [re.split(r"[\s(]", h.strip())[0] for h in raw]

        def col(*keys):
            # Exact first word of the header ("a (km)" -> "a"); Greek letters compared case-sensitively.
            for k in keys:
                for i, t in enumerate(toks):
                    if t == k or (k.isascii() and t.lower().rstrip(".") == k.lower().rstrip(".")):
                        return i
            return None
        ci = {k: col(*v) for k, v in {
            "name": ("Sat", "Satellite", "Name"), "a": ("a",), "e": ("e",), "w": ("ω", "w", "peri"), "M": ("M",),
            "i": ("i",), "node": ("node", "Ω"), "P": ("P",), "ra": ("R.A", "RA"), "dec": ("Dec",), "epoch": ("Epoch",),
            "planet": ("Planet",),
        }.items()}
        if ci["name"] is None or ci["a"] is None:
            continue
        for r in rows[1:]:
            def g(k):
                i = ci.get(k)
                return r[i] if i is not None and i < len(r) else ""
            pl = planet or next((pn for pn in PLANET_NAMES if pn.lower() == g("planet").lower()), None)
            a = fnum(g("a").replace(",", ""))
            if not pl or a is None:
                continue
            out.append([pl, g("name"), a, fnum(g("e"), 0), fnum(g("w"), 0), fnum(g("M"), 0), fnum(g("i"), 0),
                        fnum(g("node"), 0), fnum(g("P"), 0), fnum(g("ra"), -999), fnum(g("dec"), -999), g("epoch")])
    radii = {}
    try:
        for t in html_tables(SATS_PHYS_URL):
            rows = t["rows"]
            if len(rows) < 2:
                continue
            hdr = rows[0]
            body = rows[1:]
            # Two header rows: grouped columns ("Mean Radius (km)") split into value / sigma / ref.
            if body and body[0] and body[0][0].strip().lower() == "value":
                groups = len(body[0]) // 3
                fixed = len(hdr) - groups
                hdr = hdr[:fixed] + [x for h in hdr[fixed:] for x in (h, h + " sigma", "ref " + h)]
                body = body[1:]
            head = [h.lower() for h in hdr]
            rows = [hdr] + body
            log(f"   phys table header {hdr[:14]}; first row {body[0][:14] if body else []}")
            ri = next((i for i, h in enumerate(head) if "radius" in h and not h.startswith(("r_", "ref"))), None)
            ni = next((i for i, h in enumerate(head) if h.startswith("satellite") or h == "name"), None)
            if ni is None:
                ni = next((i for i, h in enumerate(head) if h.startswith("sat")), 0)
            if ri is None:
                continue
            for r in rows[1:]:
                if ri < len(r) and ni < len(r):
                    v = fnum(r[ri].split("±")[0].split("+/-")[0].replace(",", "").strip())
                    if v and v > 0:
                        radii[r[ni].strip().lower()] = v
    except Exception as e:
        log(f"  sats/phys_par failed: {e}")
    with open(os.path.join(d, "moons.tsv"), "w", encoding="utf-8") as f:
        f.write("planet\tname\ta_km\te\tw\tM\ti\tnode\tP_days\tpole_ra\tpole_dec\tepoch\tradius_km\n")
        for o in out:
            rad = radii.get(o[1].lower(), 0.0)
            f.write("\t".join(str(x) for x in o) + f"\t{rad}\n")
    log(f"  moons: {len(out)} (radii for {sum(1 for o in out if o[1].lower() in radii)})")
    if len(out) < 50:
        raise RuntimeError("too few moons parsed")
    return {"moons": len(out)}


def find_col(header, *names):
    low = [h.strip().lower() for h in header]
    for n in names:
        if n.lower() in low:
            return low.index(n.lower())
    for n in names:
        for i, h in enumerate(low):
            if h.startswith(n.lower()):
                return i
    return None


def comoving_mpc(z):
    """Comoving distance for flat LCDM (H0 = 70, Omega_m = 0.3)."""
    n = 400
    s = 0.0
    for k in range(n):
        zz = (k + 0.5) * z / n
        s += 1.0 / math.sqrt(0.3 * (1 + zz) ** 3 + 0.7)
    return 299792.458 / 70.0 * s * z / n


def coord(v, hours):
    x = fnum(v)
    if x is not None and ":" not in v and " " not in v.strip():
        return x
    try:
        parts = v.replace(":", " ").split()
        while len(parts) < 3:
            parts.append("0")
        return parse_sexa(":".join(parts[:3]), hours)
    except Exception:
        return None


def build_pulsars(base):
    d = os.path.join(base, "exotic")
    os.makedirs(d, exist_ok=True)
    header, data = vizier_tsv("B/psr/psr", "&-out.all")
    log(f"  pulsar columns: {header}")
    if data:
        log(f"  first pulsar row: {data[0][:40]}")
    cn = find_col(header, "PSRJ", "Name", "PSR")
    cra = find_col(header, "_RA.icrs", "_RAJ2000", "RAJ2000", "RA2000")
    cde = find_col(header, "_DE.icrs", "_DEJ2000", "DEJ2000", "DE2000")
    cd = find_col(header, "Dist", "DIST", "Dist_DM", "DistDM", "Dist1")
    if cd is None:
        cd = next((i for i, h in enumerate(header) if "dist" in h.lower() and not h.startswith(("e_", "r_"))), None)
    cp = find_col(header, "P0", "P")


    n = 0
    with open(os.path.join(d, "pulsars.tsv"), "w", encoding="utf-8") as f:
        f.write("name\tra\tdec\tdist_kpc\tperiod_s\n")
        for r in data:
            g = lambda i: r[i].strip() if i is not None and i < len(r) else ""
            ra, de = coord(g(cra), True), coord(g(cde), False)
            if ra is None or de is None:
                continue
            dist = fnum(g(cd))
            if not dist or dist <= 0:
                continue
            f.write(f"PSR {g(cn)}\t{ra:.5f}\t{de:.5f}\t{dist:.4f}\t{fnum(g(cp), 0.0)}\n")
            n += 1
    log(f"  pulsars: {n}")
    return {"pulsars": n}


def build_quasars(base):
    d = os.path.join(base, "exotic")
    os.makedirs(d, exist_ok=True)
    header, data = vizier_tsv("VII/294/catalog", "&-out.all&Rmag=%3C18.5&z=%3E0.01", maxrows=120000)
    log(f"  quasar columns: {header[:20]}")
    cn = find_col(header, "Name")
    cra = find_col(header, "_RAJ2000", "RAJ2000")
    cde = find_col(header, "_DEJ2000", "DEJ2000")
    cz = find_col(header, "z")
    cm = find_col(header, "Rmag")
    ct = find_col(header, "Type", "Cl")
    rows = []
    for r in data:
        g = lambda i: r[i].strip() if i is not None and i < len(r) else ""
        ra, de, z = fnum(g(cra)), fnum(g(cde)), fnum(g(cz))
        if ra is None or de is None or not z or z <= 0.01:
            continue
        if ct is not None and "Q" not in g(ct) and "A" not in g(ct):
            continue
        rows.append((g(cn), ra, de, z, fnum(g(cm), 19.0)))
    rows.sort(key=lambda x: x[4])
    rows = rows[:80000]
    with open(os.path.join(d, "quasars.tsv"), "w", encoding="utf-8") as f:
        f.write("name\tra\tdec\tz\tdist_mpc\tmag\n")
        for nm, ra, de, z, m in rows:
            f.write(f"{nm}\t{ra:.5f}\t{de:.5f}\t{z:.4f}\t{comoving_mpc(z):.1f}\t{m:.2f}\n")
    log(f"  quasars: {len(rows)}")
    return {"quasars": len(rows)}


def build_clusters(base):
    d = os.path.join(base, "exotic")
    os.makedirs(d, exist_ok=True)
    try:
        header, data = vizier_tsv("VII/110A/table3", "&-out.all")
    except Exception as e:
        log(f"  Abell table3 failed ({e}); trying the whole catalogue")
        header, data = vizier_tsv("VII/110A", "&-out.all")
    log(f"  Abell columns: {header[:20]}")
    if data:
        log(f"  first Abell row: {data[0][:20]}")
    cn = find_col(header, "ACO", "A")
    cra = find_col(header, "_RA.icrs", "_RAJ2000", "RAJ2000")
    cde = find_col(header, "_DE.icrs", "_DEJ2000", "DEJ2000")
    cz = find_col(header, "z", "Redshift")
    cm = find_col(header, "m10")
    cr = find_col(header, "Rich", "R")
    n = 0
    with open(os.path.join(d, "clusters.tsv"), "w", encoding="utf-8") as f:
        f.write("name\tra\tdec\tz\tdist_mpc\trichness\tdist_src\n")
        for r in data:
            g = lambda i: r[i].strip() if i is not None and i < len(r) else ""
            ra, de = coord(g(cra), True), coord(g(cde), False)
            if ra is None or de is None:
                continue
            z = fnum(g(cz)); src = "R"
            if not z or z <= 0:
                m10 = fnum(g(cm))
                if not m10:
                    continue
                z = 10 ** (0.2 * m10 - 4.2)   # rough brightest-galaxy magnitude/redshift relation
                src = "G"
            f.write(f"Abell {g(cn).lstrip('0')}\t{ra:.4f}\t{de:.4f}\t{z:.4f}\t{comoving_mpc(z):.1f}\t{g(cr)}\t{src}\n")
            n += 1
    log(f"  galaxy clusters: {n}")
    return {"clusters": n}


KEYWORDS = {
    "star": ("star",), "moon": ("moon", "satellite"), "planet": ("planet",), "dso": ("nebula", "cluster", "remnant", "galaxy"),
    "galaxy": ("galaxy",), "asteroid": ("asteroid", "minor planet", "dwarf planet", "object"), "comet": ("comet",),
}
SUFFIX = {"star": " (star)", "moon": " (moon)", "planet": " (planet)", "dso": "", "galaxy": "", "asteroid": "", "comet": ""}


def read_tsv(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


WIKI_STATS = {}


def wiki_summary(title):
    url = WIKI_SUMMARY + urllib.parse.quote(title.replace(" ", "_"), safe="")
    req = urllib.request.Request(url, headers={
        "User-Agent": "UniverseExplorerDataBuilder/1.1 (https://github.com/EmotionalLeaves/Universal; data build bot)",
        "Accept": "application/json",
    })
    for attempt in range(7):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                WIKI_STATS["200"] = WIKI_STATS.get("200", 0) + 1
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            WIKI_STATS[str(e.code)] = WIKI_STATS.get(str(e.code), 0) + 1
            if e.code in (429, 503):
                time.sleep(4.0 * (attempt + 1))
                continue
            return None
        except Exception as e:
            WIKI_STATS[type(e).__name__] = WIKI_STATS.get(type(e).__name__, 0) + 1
            time.sleep(1.0)
    return None


def build_wiki(base):
    """Short Wikipedia summaries (CC BY-SA 4.0) for named objects, shown as "About" in the app."""
    want = []   # (key shown in app, category)
    for n in PLANET_NAMES + ["Sun", "Moon", "Ceres", "Eris", "Haumea", "Makemake"]:
        want.append((n, "planet" if n not in ("Sun", "Moon") else ("star" if n == "Sun" else "moon")))
    for r in read_tsv(os.path.join(base, "planets", "moons.tsv")):
        want.append((r["name"], "moon"))
    for r in read_tsv(os.path.join(base, "stars", "hyg_names.tsv")):
        if r.get("proper"):
            want.append((r["proper"], "star"))
    for r in read_tsv(os.path.join(base, "dso", "dso.tsv")):
        if r.get("common"):
            want.append((r["common"], "dso"))
        if r.get("messier"):
            want.append(("Messier " + r["messier"].lstrip("0"), "dso"))
    for r in read_tsv(os.path.join(base, "galaxies", "galaxies.tsv")):
        nm = r.get("name", "")
        if nm and not nm.startswith(("PGC", "NGC", "IC", "UGC", "ESO")):
            want.append((nm, "galaxy"))
    ex = read_tsv(os.path.join(base, "planets", "exoplanets.tsv"))
    for r in ex:
        if fnum(r.get("sy_dist"), 1e9) < 25 or fnum(r.get("disc_year"), 3000) < 2005:
            want.append((r["pl_name"], "planet"))
    ast = read_tsv(os.path.join(base, "smallbodies", "asteroids.tsv"))
    ast.sort(key=lambda r: fnum(r.get("H"), 99))
    for r in ast[:300]:
        want.append((r["name"], "asteroid"))
    for r in read_tsv(os.path.join(base, "smallbodies", "comets.tsv")):
        nm = r["name"]
        if "P/" in nm[:6] and nm.split("P/")[0].isdigit() and int(nm.split("P/")[0]) <= 150:
            want.append((nm, "comet"))
    seen, todo = set(), []
    for k, c in want:
        if k and k.lower() not in seen:
            seen.add(k.lower()); todo.append((k, c))
    log(f"  wiki: {len(todo)} names")

    def one(item):
        key, cat = item
        tries = [key + SUFFIX[cat], key] if SUFFIX[cat] else [key]
        for t in tries:
            js = wiki_summary(t)
            if not js or js.get("type") != "standard":
                continue
            text = " ".join((js.get("extract") or "").split())
            blob = (text + " " + (js.get("description") or "")).lower()
            if len(text) < 40 or not any(w in blob for w in KEYWORDS[cat]):
                continue
            if len(text) > 900:
                cut = text[:900].rfind(". ")
                text = text[: cut + 1] if cut > 200 else text[:900] + "…"
            return key, js.get("title", t), text
        return None

    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(2) as pool:
        got = [r for r in pool.map(one, todo) if r]
    log(f"  wiki HTTP results: {WIKI_STATS}")
    d = os.path.join(base, "wiki")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "about.tsv"), "w", encoding="utf-8") as f:
        f.write("key\ttitle\ttext\n")
        for k, t, x in got:
            f.write(f"{k.lower()}\t{t}\t{x.replace(chr(9), ' ')}\n")
    log(f"  wiki: {len(got)} summaries")
    return {"wiki": len(got)}


# ----------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/data")
    ap.add_argument("--packs", action="store_true")
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    base = os.path.join(args.out, "base")
    packs = os.path.join(args.out, "packs")
    os.makedirs(base, exist_ok=True)
    os.makedirs(packs, exist_ok=True)
    only = set(x for x in args.only.split(",") if x)
    want = lambda k: not only or k in only
    report = {}
    old_manifest = os.path.join(base, "manifest.json")
    if only and os.path.exists(old_manifest):
        try:
            report = json.load(open(old_manifest))   # incremental run: keep earlier counts
        except Exception:
            report = {}
    report["built"] = time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime())
    steps = []
    if want("stars"):
        steps.append(("stars", lambda: build_hyg(base)))
    if want("constellations"):
        steps.append(("constellations", lambda: build_constellations(base)))
    if want("planets"):
        steps.append(("planets", lambda: build_exoplanets(base)))
    dso_state = {}
    if want("dso") or want("galaxies"):
        def dso():
            rows, gals = build_dso(base)
            dso_state["galaxies"] = gals
            return {"dso": len(rows)}
        steps.append(("dso", dso))
        steps.append(("galaxies", lambda: build_galaxies(base, dso_state.get("galaxies", []))))
    if want("textures"):
        steps.append(("textures", lambda: build_textures(base)))
    if want("heights"):
        steps.append(("heights", lambda: build_heights(base)))
    if want("cmb"):
        steps.append(("cmb", lambda: build_cmb(base)))
    if want("smallbodies"):
        steps.append(("smallbodies", lambda: build_small_bodies(base)))
    if want("moons"):
        steps.append(("moons", lambda: build_moons(base)))
    if want("pulsars"):
        steps.append(("pulsars", lambda: build_pulsars(base)))
    if want("quasars"):
        steps.append(("quasars", lambda: build_quasars(base)))
    if want("clusters"):
        steps.append(("clusters", lambda: build_clusters(base)))
    if want("wiki"):
        steps.append(("wiki", lambda: build_wiki(base)))
    if args.packs and want("packs"):
        steps.append(("athyg", lambda: build_athyg_pack(packs)))
        steps.append(("glade", lambda: build_glade_pack(packs)))
    failures = []
    for name, fn in steps:
        t0 = time.time()
        try:
            r = fn()
            if r:
                report.update(r)
            log(f"== {name} done in {time.time() - t0:.0f}s")
        except Exception as e:
            failures.append(name)
            log(f"== WARNING step '{name}' failed: {e}")
            traceback.print_exc()
    report["failed_steps"] = failures
    with open(os.path.join(base, "manifest.json"), "w") as f:
        json.dump(report, f, indent=1)
    total = 0
    for root, _, files in os.walk(base):
        for fn in files:
            total += os.path.getsize(os.path.join(root, fn))
    log(f"Base data total: {total / 1e6:.1f} MB")
    for root, _, files in os.walk(packs):
        for fn in files:
            log(f"Pack file {os.path.join(root, fn)}: {os.path.getsize(os.path.join(root, fn)) / 1e6:.1f} MB")
    log("Summary: " + json.dumps(report))


if __name__ == "__main__":
    main()
