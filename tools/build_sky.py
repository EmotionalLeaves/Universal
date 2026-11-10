#!/usr/bin/env python3
"""
Builds the big "everything we know" datasets that the app streams from the public data repo
(EmotionalLeaves/Universal-data):

  gaia_t1.bin, gaia_t2.bin, gaia_t3.bin  Every Gaia DR3 star brighter than G = GMAX, placed in 3D with
                                         Bailer-Jones distances, in cells (50 / 250 / 1000 pc) the app
                                         fetches one by one with HTTP range requests.
  milliquas.bin + milliquas_names.txt    The Million Quasars catalogue (v8, ~1 million).

Tile file ("UXTS"): int32 magic, version, tier; float32 cellPc; int32 recordSize, nCells; then nCells
index entries (int16 cx, cy, cz, pad; int32 count; int64 byteOffset), then records:
  uint16 qx, qy, qz (position inside the cell, 0..65535 = 0..cellPc), uint16 G*1000, int16 BP-RP*1000
  (-32768 = unknown), int64 source_id  = 18 bytes.
Cell coordinates are floor(xyz / cellPc) in Sun-centred equatorial parsecs.
"""
import argparse
import gzip
import io
import math
import os
import struct
import sys
import time
import urllib.parse
import urllib.request

GMAX = 15.0
TAP = "https://gea.esac.esa.int/tap-server/tap"
TIERS = [(1, 50.0, 0.0, 1200.0), (2, 250.0, 1200.0, 6000.0), (3, 1000.0, 6000.0, 40000.0)]
REC = struct.Struct("<HHHHhq")
PAGE = 2_500_000
THREADS = 8
BUDGET_S = 290 * 60
HPX12 = 12 * 4 ** 12


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def http(url, data=None, timeout=600):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "UniverseExplorer-data/1.0"})
    return urllib.request.urlopen(req, timeout=timeout)


def async_query(adql, dest):
    """Runs an ADQL query as an async job on the ESA archive; streams the CSV result to dest."""
    for attempt in range(4):
        try:
            body = urllib.parse.urlencode({"REQUEST": "doQuery", "LANG": "ADQL", "FORMAT": "csv", "PHASE": "RUN", "QUERY": adql}).encode()
            req = urllib.request.Request(TAP + "/async", data=body, headers={"User-Agent": "UniverseExplorer-data/1.0"})
            opener = urllib.request.build_opener(NoRedirect)
            try:
                r = opener.open(req, timeout=120)
                loc = r.headers.get("Location")
            except urllib.error.HTTPError as e:
                loc = e.headers.get("Location")
            if not loc:
                raise RuntimeError("no job location")
            t0 = time.time()
            while True:
                phase = http(loc + "/phase", timeout=60).read().decode().strip()
                if phase in ("COMPLETED", "ERROR", "ABORTED"):
                    break
                if time.time() - t0 > 3600:
                    raise RuntimeError("job took over an hour")
                time.sleep(4)
            if phase != "COMPLETED":
                err = ""
                try:
                    err = http(loc + "/error", timeout=60).read().decode()[:300]
                except Exception:
                    pass
                raise RuntimeError(f"job {phase}: {err}")
            with http(loc + "/results/result", timeout=1800) as r, open(dest, "wb") as f:
                while True:
                    b = r.read(1 << 20)
                    if not b:
                        break
                    f.write(b)
            return
        except Exception as e:
            log(f"  async attempt {attempt + 1} failed: {e}")
            time.sleep(20 * (attempt + 1))
    raise RuntimeError("async query failed 4 times")


import urllib.error


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class Buckets:
    """Spills records to per-(tier, supercell) files so 40 million stars never sit in memory."""

    def __init__(self, root):
        self.root = root
        os.makedirs(root, exist_ok=True)
        self.open = {}

    def path(self, tier, sx, sy, sz):
        return os.path.join(self.root, f"t{tier}_{sx}_{sy}_{sz}.raw")

    def add(self, tier, cell, rec):
        sx, sy, sz = cell[0] >> 4, cell[1] >> 4, cell[2] >> 4
        key = (tier, sx, sy, sz)
        f = self.open.get(key)
        if f is None:
            if len(self.open) > 400:
                for v in self.open.values():
                    v.close()
                self.open.clear()
            f = open(self.path(*key), "ab")
            self.open[key] = f
        f.write(struct.pack("<hhh", *cell) + rec)

    def close(self):
        for v in self.open.values():
            v.close()
        self.open.clear()


def place(ra, dec, d):
    r, de = math.radians(ra), math.radians(dec)
    c = math.cos(de)
    return d * c * math.cos(r), d * c * math.sin(r), d * math.sin(de)


def gaia(out, work, gmax):
    cols = ("g.source_id, g.ra, g.dec, g.parallax, g.parallax_over_error, g.ruwe, g.phot_g_mean_mag, g.bp_rp, "
            "d.r_med_geo, d.r_med_photogeo")
    buckets = Buckets(os.path.join(work, "buckets"))
    # The archive returns at most 50,000 rows per query (sync or async, for anonymous users), so the
    # sky is cut into thousands of source_id ranges (HEALPix pixels), split further where crowded,
    # and queried several at a time.
    import concurrent.futures as cf
    import threading
    CAP = 50000
    pending = [(i * HPX12 // 4096, (i + 1) * HPX12 // 4096) for i in range(4096)]
    total = [0]
    done = [0]
    lock = threading.Lock()
    t_start = time.time()

    def run(rng):
        a, b = rng
        lo, hi = a * (1 << 35), b * (1 << 35) - 1
        q = (f"SELECT {cols} FROM gaiadr3.gaia_source AS g JOIN external.gaiaedr3_distance AS d "
             f"ON g.source_id = d.source_id WHERE g.source_id BETWEEN {lo} AND {hi} AND g.phot_g_mean_mag < {gmax}")
        url = TAP + "/sync?" + urllib.parse.urlencode({"REQUEST": "doQuery", "LANG": "ADQL", "FORMAT": "csv", "QUERY": q})
        for attempt in range(6):
            try:
                with http(url, timeout=900) as r:
                    text = r.read().decode("utf-8", "replace")
                if not text.startswith("source_id"):
                    raise RuntimeError(text[:200])
                return rng, text.split("\n")[1:]
            except Exception as e:
                time.sleep(10 * (attempt + 1))
                last = e
        log(f"  range {a}-{b} failed 6 times ({last}); will retry in halves")
        return rng, None

    def keep(lines):
        kept = 0
        for line in lines:
            if not line:
                continue
            p = line.rstrip("\r").split(",")
            try:
                sid = int(p[0]); ra = float(p[1]); dec = float(p[2]); g = float(p[6])
            except Exception:
                continue
            plx = float(p[3]) if p[3] else float("nan")
            poe = float(p[4]) if p[4] else 0.0
            ruwe = float(p[5]) if p[5] else 9.0
            # Already in the built-in near-Sun file (same selection as gaia_near).
            if plx > 10 and poe > 10 and ruwe < 1.4:
                continue
            dist = p[9] or p[8]
            if not dist:
                if plx > 0 and poe > 5:
                    dist = 1000.0 / plx
                else:
                    continue
            d = float(dist)
            if not (d > 0) or d > TIERS[-1][3]:
                continue
            for tier, size, dmin, dmax in TIERS:
                if dmin <= d < dmax or (tier == 1 and d < dmin):
                    break
            x, y, z = place(ra, dec, d)
            cell = (math.floor(x / size), math.floor(y / size), math.floor(z / size))
            qx = min(65535, int((x / size - cell[0]) * 65535 + 0.5))
            qy = min(65535, int((y / size - cell[1]) * 65535 + 0.5))
            qz = min(65535, int((z / size - cell[2]) * 65535 + 0.5))
            bp = p[7]
            bpi = -32768 if not bp else max(-32767, min(32767, int(float(bp) * 1000)))
            buckets.add(tier, cell, REC.pack(qx, qy, qz, int(min(65.5, max(0.0, g)) * 1000), bpi, sid))
            kept += 1
        return kept

    with cf.ThreadPoolExecutor(max_workers=THREADS) as ex:
        futs = {ex.submit(run, r) for r in pending}
        while futs:
            finished, futs = cf.wait(futs, return_when=cf.FIRST_COMPLETED)
            for f in finished:
                (a, b), lines = f.result()
                if lines is None:
                    # The archive dropped this one: smaller pieces usually go through.
                    if b - a > 1:
                        m = (a + b) // 2
                        futs |= {ex.submit(run, (a, m)), ex.submit(run, (m, b))}
                    else:
                        log(f"  skipping single pixel {a} after repeated failures")
                    continue
                rows = sum(1 for l in lines if l)
                if rows >= CAP - 1 and b - a > 1:
                    m = (a + b) // 2
                    futs |= {ex.submit(run, (a, m)), ex.submit(run, (m, b))}
                    continue
                total[0] += keep(lines)
                done[0] += 1
                if done[0] % 100 == 0:
                    log(f"  {done[0]} ranges done, {len(futs)} in flight/queued, {total[0]:,} stars, {(time.time() - t_start) / 60:.0f} min")
            if time.time() - t_start > BUDGET_S:
                log("  time budget reached: stopping (the tiles will cover only what was fetched)")
                for f in futs:
                    f.cancel()
                break
    log(f"  fetched {total[0]:,} stars in {(time.time() - t_start) / 60:.0f} min")
    buckets.close()
    # Write one file per tier: index sorted by cell, then records cell by cell.
    for tier, size, _, _ in TIERS:
        files = sorted(fn for fn in os.listdir(buckets.root) if fn.startswith(f"t{tier}_"))
        cells = {}
        index = []
        dest = os.path.join(out, f"gaia_t{tier}.bin")
        body = dest + ".body"
        offset = 0
        with open(body, "wb") as bf:
            for fn in files:
                raw = open(os.path.join(buckets.root, fn), "rb").read()
                groups = {}
                for i in range(0, len(raw), 24):
                    cx, cy, cz = struct.unpack_from("<hhh", raw, i)
                    groups.setdefault((cx, cy, cz), []).append(raw[i + 6:i + 24])
                for cell in sorted(groups):
                    recs = groups[cell]
                    # Brightest first inside a cell (lets the app read just the head of a far cell).
                    recs.sort(key=lambda r: struct.unpack_from("<H", r, 6)[0])
                    blob = b"".join(recs)
                    bf.write(blob)
                    index.append((cell, len(recs), offset))
                    offset += len(blob)
        head = 4 * 6
        start = head + 20 * len(index)
        with open(dest, "wb") as f:
            f.write(b"UXTS")
            f.write(struct.pack("<iifii", 1, tier, size, REC.size, len(index)))
            for (cx, cy, cz), n, off in index:
                f.write(struct.pack("<hhhhiq", cx, cy, cz, 0, n, start + off))
            with open(body, "rb") as bf:
                while True:
                    b = bf.read(1 << 22)
                    if not b:
                        break
                    f.write(b)
        os.remove(body)
        log(f"wrote {dest}: {len(index):,} cells, {offset // REC.size:,} stars, {os.path.getsize(dest) / 1e6:.0f} MB")


GLADE_MIRRORS = ["https://vizier.cfa.harvard.edu", "https://vizier.idia.ac.za", "https://vizier.cds.unistra.fr"]
GTIERS = [(1, 20.0, 0.0, 300.0), (2, 100.0, 300.0, 1e9)]
GREC = struct.Struct("<HHHhi")
OVERVIEW_ABS = -20.5


# ---------------------------------------------------------------------------------------------
# Spectroscopic redshifts (exact distances) from DESI DR1 and SDSS DR17, merged into GLADE+.
# GLADE+ beyond a few hundred Mpc is mostly photometric redshifts, which put a galaxy up to ~100 Mpc
# too near or too far; a measured spectrum fixes that, and adds galaxies GLADE+ does not have.

DESI_ZCAT = "https://data.desi.lbl.gov/public/dr1/spectro/redux/iron/zcatalog/v1/zall-pix-iron.fits"
SDSS_ZCAT = "https://data.sdss.org/sas/dr17/sdss/spectro/redux/specObj-dr17.fits"
H0 = 70.0
OM = 0.3
MATCH_ARCSEC = 2.0


def comoving_table():
    import numpy as np
    zs = np.linspace(0, 8, 16001)
    inv = 1.0 / np.sqrt(OM * (1 + zs) ** 3 + (1 - OM))
    dc = np.concatenate([[0.0], np.cumsum((inv[1:] + inv[:-1]) * 0.5 * np.diff(zs))]) * 299792.458 / H0
    return zs, dc


def fits_stream(url, want, chunk_rows=100000):
    """Streams the first binary table of a (huge) FITS file over HTTP, yielding dicts of the [want]
    columns chunk by chunk; reconnects with a Range request where a transfer breaks."""
    import numpy as np
    pos = 0
    resp = None

    def connect(at):
        for attempt in range(10):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "UniverseExplorer-data/1.0", "Range": f"bytes={at}-"})
                return urllib.request.urlopen(req, timeout=300)
            except Exception as e:
                log(f"  reconnect {url} at {at}: {e}")
                time.sleep(15 * (attempt + 1))
        raise RuntimeError("cannot reach " + url)

    def read(n):
        nonlocal pos, resp
        buf = bytearray()
        while len(buf) < n:
            try:
                if resp is None:
                    resp = connect(pos)
                b = resp.read(min(n - len(buf), 1 << 24))
                if not b:
                    raise IOError("eof")
                buf += b
                pos += len(b)
            except Exception as e:
                if str(e) == "eof" and pos > 0 and len(buf) == 0:
                    raise
                log(f"  stream broke at {pos}: {e}")
                try:
                    resp.close()
                except Exception:
                    pass
                resp = None
        return bytes(buf)

    def header():
        cards = {}
        order = []
        while True:
            block = read(2880)
            done = False
            for i in range(0, 2880, 80):
                c = block[i:i + 80].decode("latin-1")
                k = c[:8].strip()
                if k == "END":
                    done = True
                    break
                if c[8:10] == "= ":
                    v = c[10:].split("/")[0].strip() if not c[10:].strip().startswith("'") else c[10:].strip().split("'")[1].strip()
                    cards[k] = v
                    order.append(k)
            if done:
                return cards

    h0 = header()
    naxis = int(h0.get("NAXIS", "0"))
    size = 0
    if naxis > 0:
        size = abs(int(h0["BITPIX"])) // 8
        for i in range(1, naxis + 1):
            size *= int(h0[f"NAXIS{i}"])
    if size:
        read(((size + 2879) // 2880) * 2880)
    h = header()
    rowlen = int(h["NAXIS1"]); nrows = int(h["NAXIS2"]); nf = int(h["TFIELDS"])
    sizes = {"L": 1, "B": 1, "I": 2, "J": 4, "K": 8, "A": 1, "E": 4, "D": 8, "C": 8, "M": 16, "P": 8, "Q": 16}
    np_fmt = {"L": "S1", "B": "u1", "I": ">i2", "J": ">i4", "K": ">i8", "E": ">f4", "D": ">f8"}
    names, formats, offsets = [], [], []
    off = 0
    import re
    for i in range(1, nf + 1):
        name = h[f"TTYPE{i}"]
        m = re.match(r"(\d*)([A-Z])", h[f"TFORM{i}"])
        rep = int(m.group(1)) if m.group(1) else 1
        code = m.group(2)
        nbytes = (rep + 7) // 8 if code == "X" else rep * sizes[code]
        if name in want:
            if code == "A":
                f = f"S{rep}"
            elif rep == 1:
                f = np_fmt[code]
            else:
                f = (np_fmt[code], (rep,))
            names.append(name); formats.append(f); offsets.append(off)
        off += nbytes
    assert off == rowlen, (off, rowlen)
    dt = np.dtype({"names": names, "formats": formats, "offsets": offsets, "itemsize": rowlen})
    log(f"  {url.split('/')[-1]}: {nrows:,} rows of {rowlen} bytes")
    left = nrows
    while left > 0:
        n = min(chunk_rows, left)
        arr = np.frombuffer(read(n * rowlen), dtype=dt)
        left -= n
        yield {k: arr[k] for k in names}, nrows - left, nrows


def spec_catalogue(work):
    """Every galaxy with a good spectroscopic redshift in DESI DR1 and SDSS DR17 (SDSS ones that DESI
    also measured dropped): arrays ra, dec, z, apparent r magnitude (nan if unknown)."""
    import numpy as np
    cache = os.path.join(work, "spec.npz")
    if os.path.exists(cache):
        d = np.load(cache)
        return d["ra"], d["dec"], d["z"], d["r"]
    parts = []
    t0 = time.time()
    try:
        cols = {"ZCAT_PRIMARY", "SPECTYPE", "ZWARN", "COADD_FIBERSTATUS", "DELTACHI2", "Z", "TARGET_RA", "TARGET_DEC", "FLUX_R"}
        for c, done, total in fits_stream(DESI_ZCAT, cols):
            ok = (c["ZCAT_PRIMARY"] == b"T") & (np.char.strip(c["SPECTYPE"]) == b"GALAXY") & (c["ZWARN"] == 0) & \
                 (c["COADD_FIBERSTATUS"] == 0) & (c["DELTACHI2"] > 25) & (c["Z"] > 0.0015) & (c["Z"] < 5)
            fr = c["FLUX_R"][ok].astype("f8")
            r = np.where(fr > 0, 22.5 - 2.5 * np.log10(np.maximum(fr, 1e-9)), np.nan)
            parts.append((c["TARGET_RA"][ok].astype("f8"), c["TARGET_DEC"][ok].astype("f8"), c["Z"][ok].astype("f4"), r.astype("f4"), np.zeros(ok.sum(), "u1")))
            if (done // 100000) % 20 == 0:
                log(f"  DESI {done:,}/{total:,} rows, {sum(len(p[0]) for p in parts):,} galaxies, {(time.time() - t0) / 60:.0f} min")
    except Exception as e:
        log(f"DESI failed: {e}")
    try:
        cols = {"SPECPRIMARY", "CLASS", "ZWARNING", "Z", "PLUG_RA", "PLUG_DEC", "SPECTROFLUX"}
        for c, done, total in fits_stream(SDSS_ZCAT, cols):
            ok = (c["SPECPRIMARY"].astype("i8") == 1) & (np.char.strip(c["CLASS"]) == b"GALAXY") & (c["ZWARNING"] == 0) & \
                 (c["Z"] > 0.0015) & (c["Z"] < 5)
            fr = c["SPECTROFLUX"][ok][:, 2].astype("f8")
            r = np.where(fr > 0, 22.5 - 2.5 * np.log10(np.maximum(fr, 1e-9)), np.nan)
            parts.append((c["PLUG_RA"][ok].astype("f8"), c["PLUG_DEC"][ok].astype("f8"), c["Z"][ok].astype("f4"), r.astype("f4"), np.ones(ok.sum(), "u1")))
            if (done // 100000) % 10 == 0:
                log(f"  SDSS {done:,}/{total:,} rows, {(time.time() - t0) / 60:.0f} min")
    except Exception as e:
        log(f"SDSS failed: {e}")
    if not parts:
        return None
    ra = np.concatenate([p[0] for p in parts]); dec = np.concatenate([p[1] for p in parts])
    z = np.concatenate([p[2] for p in parts]); r = np.concatenate([p[3] for p in parts]); src = np.concatenate([p[4] for p in parts])
    log(f"spectra: {len(ra):,} galaxies ({(src == 0).sum():,} DESI, {(src == 1).sum():,} SDSS) before removing repeats")
    # SDSS galaxies DESI also measured (and DESI/SDSS repeats among themselves) are dropped.
    from scipy.spatial import cKDTree
    v = unit(ra, dec)
    keep = np.ones(len(ra), bool)
    rad = math.radians(MATCH_ARCSEC / 3600.0)
    order = np.lexsort((src,))   # DESI first
    tree = cKDTree(v)
    pairs = tree.query_pairs(rad, output_type="ndarray")
    if len(pairs):
        a, b = pairs[:, 0], pairs[:, 1]
        # Drop the SDSS one of each pair, or the later of two from the same survey.
        drop = np.where(src[a] > src[b], a, np.where(src[b] > src[a], b, np.maximum(a, b)))
        keep[drop] = False
    del tree, pairs, order
    ra, dec, z, r = ra[keep], dec[keep], z[keep], r[keep]
    log(f"spectra: {len(ra):,} galaxies after removing repeats")
    np.savez(cache, ra=ra, dec=dec, z=z, r=r)
    return ra, dec, z, r


def unit(ra, dec):
    import numpy as np
    a = np.radians(ra); d = np.radians(dec)
    return np.stack([np.cos(d) * np.cos(a), np.cos(d) * np.sin(a), np.sin(d)], axis=1)



def glade(out, work):
    """All of GLADE+ (22.5 million galaxies): the bright ones (absolute B < OVERVIEW_ABS, or unknown) go in
    glade.bin (the pack the app downloads whole, UXGL format: float32 x, y, z Mpc, absB), the rest in
    galaxy_t1/t2.bin cells (UXTS format, 12-byte records: uint16 qx, qy, qz, int16 absB*100, int32 PGC)
    that the app streams around the camera."""
    import concurrent.futures as cf
    boxes = []
    step = 4.0
    dec = -90.0
    while dec < 90.0:
        d1 = min(90.0, dec + step)
        mid = max(abs(dec), abs(d1))
        w = step / max(0.05, math.cos(math.radians(min(89.0, mid))))
        n = max(1, int(math.ceil(360.0 / w)))
        for i in range(n):
            boxes.append((360.0 * i / n, 360.0 * (i + 1) / n, dec, d1))
        dec = d1
    log(f"glade: {len(boxes)} sky boxes")

    def fetch(box):
        ra0, ra1, de0, de1 = box
        cra, cde = (ra0 + ra1) / 2, (de0 + de1) / 2
        # A box a little larger than ours (VizieR's boxes are not exact at high declination); rows are
        # then kept only if they fall inside [ra0, ra1) x [de0, de1).
        wdeg = (ra1 - ra0) * math.cos(math.radians(cde)) + 1.0
        if abs(cde) > 80:
            q = f"-c={cra:.4f}%20{cde:+.4f}&-c.r={(de1 - de0) + (ra1 - ra0) * math.cos(math.radians(min(abs(de0), abs(de1)))) + 1:.3f}&-c.u=deg"
        else:
            q = f"-c={cra:.4f}%20{cde:+.4f}&-c.bd={wdeg:.4f}x{(de1 - de0) + 1.0:.4f}&-c.u=deg"
        for attempt in range(8):
            base = GLADE_MIRRORS[attempt % len(GLADE_MIRRORS)]
            url = f"{base}/viz-bin/asu-tsv?-source=VII/291/gladep&{q}&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag,PGC&-out.max=unlimited"
            try:
                with http(url, timeout=600) as r:
                    text = r.read().decode("latin-1")
                if "RAJ2000" not in text:
                    raise RuntimeError("no table: " + text[:120])
                rows = []
                for line in text.split("\n"):
                    if not line or line[0] in "#-RD" or line.startswith(" ") and not line.strip():
                        continue
                    p = line.split("\t")
                    if len(p) < 6:
                        continue
                    try:
                        ra = float(p[0]); de = float(p[1])
                    except Exception:
                        continue
                    if not (ra0 <= ra < ra1 and (de0 <= de < de1 or (de1 == 90.0 and de == 90.0))):
                        continue
                    rows.append(p)
                return box, rows
            except Exception as e:
                last = e
                time.sleep(5 * (attempt + 1))
        raise RuntimeError(f"box {box} failed: {last}")

    import numpy as np
    zs_tab, dc_tab = comoving_table()
    spec = spec_catalogue(work)
    # Spectra sorted by the sky box they fall in (same boxes as the GLADE+ queries).
    band_off, band_n = [], []
    k = 0
    dec_edges = sorted(set(b[2] for b in boxes))
    for de0 in dec_edges:
        n = sum(1 for b in boxes if b[2] == de0)
        band_off.append(k); band_n.append(n); k += n
    box_id = {b: i for i, b in enumerate(boxes)}
    if spec is not None:
        sra, sdec, sz, sr = spec
        band = np.clip(np.searchsorted(np.array(dec_edges), sdec, side="right") - 1, 0, len(dec_edges) - 1)
        bn = np.array(band_n)[band]
        sid = np.array(band_off)[band] + np.minimum(bn - 1, (np.mod(sra, 360.0) / 360.0 * bn).astype(np.int64))
        sorder = np.argsort(sid, kind="stable")
        sstart = np.searchsorted(sid[sorder], np.arange(len(boxes) + 1))
        sdc = np.interp(sz.astype("f8"), zs_tab, dc_tab)
        log(f"spectra sorted into {len(boxes)} sky boxes")
    rad = math.radians(MATCH_ARCSEC / 3600.0)
    from scipy.spatial import cKDTree

    buckets = Buckets(os.path.join(work, "gbuckets"))
    ov = open(os.path.join(out, "glade.bin"), "wb")
    ov.write(b"UXGL")
    ov.write(struct.pack("<i", 2))
    counts = {"all": 0, "ov": 0, "tile": 0, "fixed": 0, "added": 0}

    def emit(x, y, zz, dc, absb, pgc):
        counts["all"] += 1
        if absb < OVERVIEW_ABS:
            ov.write(struct.pack("<ffff", x, y, zz, absb))
            counts["ov"] += 1
            return
        for tier, size, dmin, dmax in GTIERS:
            if dmin <= dc < dmax:
                break
        cell = (math.floor(x / size), math.floor(y / size), math.floor(zz / size))
        if max(abs(c) for c in cell) > 32000:
            return
        q = [min(65535, int((v / size - c) * 65535 + 0.5)) for v, c in zip((x, y, zz), cell)]
        buckets.add(tier, cell, GREC.pack(q[0], q[1], q[2], int(max(-32767, min(32767, absb * 100))), pgc) + b"\0" * 6)
        counts["tile"] += 1

    def abs_from_r(r, dl):
        # r-band to B (typical galaxy colour), no K-correction: good to about a magnitude.
        return float(r) - 5 * math.log10(dl * 1e6 / 10) + 1.0 if r == r else -20.8

    t0 = time.time()
    with cf.ThreadPoolExecutor(max_workers=6) as ex:
        futs = [ex.submit(fetch, b) for b in boxes]
        for k, f in enumerate(cf.as_completed(futs)):
            box, rows = f.result()
            bid = box_id[box]
            # This box's spectra, and a tree to find the one (if any) under each GLADE+ galaxy.
            idx = sorder[sstart[bid]:sstart[bid + 1]] if spec is not None else np.zeros(0, np.int64)
            used = np.zeros(len(idx), bool)
            tree = cKDTree(unit(sra[idx], sdec[idx])) if len(idx) else None
            parsed = []
            for p in rows:
                try:
                    ra = float(p[0]); de = float(p[1]); dl = float(p[2])
                except Exception:
                    continue
                if not (dl > 0):
                    continue
                z = float(p[3]) if p[3].strip() else float("nan")
                b = float(p[4]) if p[4].strip() else float("nan")
                pgc = int(p[5]) if p[5].strip().isdigit() else 0
                parsed.append((ra, de, dl, z, b, pgc))
            hit = np.full(len(parsed), -1, np.int64)
            if tree is not None and parsed:
                arr = np.array([(q[0], q[1]) for q in parsed])
                dist, j = tree.query(unit(arr[:, 0], arr[:, 1]), distance_upper_bound=rad)
                ok = np.isfinite(dist)
                hit[ok] = j[ok]
            for (ra, de, dl, z, b, pgc), h in zip(parsed, hit):
                dc = dl / (1 + z) if z == z and z > 0 else dl / (1 + dl / 4400.0)
                absb = b - 5 * math.log10(dl * 1e6 / 10) if b == b else float("nan")
                if h >= 0:
                    used[h] = True
                    si = idx[h]
                    # A measured redshift beats GLADE+'s (mostly photometric) one, except for the
                    # nearest galaxies, whose GLADE+ distances are measured directly.
                    if dc > 40:
                        zz_ = float(sz[si]); dc = float(sdc[si]); dl2 = dc * (1 + zz_)
                        absb = b - 5 * math.log10(dl2 * 1e6 / 10) if b == b else abs_from_r(sr[si], dl2)
                        counts["fixed"] += 1
                if not (absb == absb):
                    absb = -19.5   # no B magnitude (infrared-only): a typical galaxy, streamed with the faint ones
                x, y, zz = place(ra, de, dc)
                emit(x, y, zz, dc, absb, pgc)
            # Galaxies only the spectroscopic surveys have.
            for h in np.nonzero(~used)[0]:
                si = idx[h]
                dc = float(sdc[si]); zz_ = float(sz[si])
                x, y, zz = place(float(sra[si]), float(sdec[si]), dc)
                emit(x, y, zz, dc, abs_from_r(sr[si], dc * (1 + zz_)), 0)
                counts["added"] += 1
            if (k + 1) % 100 == 0:
                log(f"  {k + 1}/{len(boxes)} boxes, {counts}, {(time.time() - t0) / 60:.0f} min")
    ov.close()
    buckets.close()
    log(f"glade: {counts}")
    shuffle_pack(os.path.join(out, "glade.bin"))
    write_tiles(out, buckets.root, "galaxy", GTIERS, GREC.size, pad=6, sort_key=lambda r: struct.unpack_from("<h", r, 6)[0])


def shuffle_pack(path):
    """Random order (fixed seed): any first N points are an even sample of the whole sky, so a phone
    can load just part of the pack and the renderer can draw a fraction when zoomed far out."""
    import numpy as np
    a = np.fromfile(path, dtype="<f4", offset=8).reshape(-1, 4)
    a = a[np.random.default_rng(0x5A11E5).permutation(len(a))]
    with open(path, "wb") as f:
        f.write(b"UXGL"); f.write(struct.pack("<i", 2)); f.write(a.astype("<f4").tobytes())
    log(f"pack: {len(a):,} galaxies, shuffled")


def write_tiles(out, root, prefix, tiers, rec_size, pad=0, sort_key=None):
    """Turns spilled bucket files into one cell file per tier (UXTS format)."""
    raw_size = 6 + rec_size + pad
    for tier, size, _, _ in tiers:
        files = sorted(fn for fn in os.listdir(root) if fn.startswith(f"t{tier}_"))
        index = []
        dest = os.path.join(out, f"{prefix}_t{tier}.bin")
        body = dest + ".body"
        offset = 0
        with open(body, "wb") as bf:
            for fn in files:
                raw = open(os.path.join(root, fn), "rb").read()
                groups = {}
                for i in range(0, len(raw) - raw_size + 1, raw_size):
                    cx, cy, cz = struct.unpack_from("<hhh", raw, i)
                    groups.setdefault((cx, cy, cz), []).append(raw[i + 6:i + 6 + rec_size])
                for cell in sorted(groups):
                    recs = groups[cell]
                    if sort_key:
                        recs.sort(key=sort_key)
                    blob = b"".join(recs)
                    bf.write(blob)
                    index.append((cell, len(recs), offset))
                    offset += len(blob)
        start = 24 + 20 * len(index)
        with open(dest, "wb") as f:
            f.write(b"UXTS")
            f.write(struct.pack("<iifii", 1, tier, size, rec_size, len(index)))
            for (cx, cy, cz), n, off in index:
                f.write(struct.pack("<hhhhiq", cx, cy, cz, 0, n, start + off))
            with open(body, "rb") as bf:
                while True:
                    b = bf.read(1 << 22)
                    if not b:
                        break
                    f.write(b)
        os.remove(body)
        log(f"wrote {dest}: {len(index):,} cells, {offset // rec_size:,} records, {os.path.getsize(dest) / 1e6:.0f} MB")


def milliquas(out, work):
    url = "https://heasarc.gsfc.nasa.gov/FTP/heasarc/dbase/tdat_files/heasarc_milliquas.tdat.gz"
    path = os.path.join(work, "milliquas.tdat.gz")
    with http(url, timeout=1800) as r, open(path, "wb") as f:
        f.write(r.read())
    fields = []
    rows = []
    in_data = False
    with gzip.open(path, "rt", encoding="latin-1") as f:
        for line in f:
            if line.startswith("field["):
                fields.append(line[6:line.index("]")])
            elif line.startswith("<DATA>"):
                in_data = True
            elif line.startswith("<END>"):
                break
            elif in_data:
                p = line.rstrip("\n").split("|")
                rows.append(p)
    ix = {n: i for i, n in enumerate(fields)}
    log(f"milliquas: {len(rows)} rows, fields {fields}")
    n = 0
    with open(os.path.join(out, "milliquas.bin"), "wb") as f, open(os.path.join(out, "milliquas_names.txt"), "w") as nf:
        f.write(b"UXMQ")
        f.write(struct.pack("<ii", 1, 0))
        for p in rows:
            try:
                ra = float(p[ix["ra"]]); de = float(p[ix["dec"]]); z = float(p[ix["redshift"]])
            except Exception:
                continue
            if not (z > 0.0001):
                continue
            def num(k):
                try:
                    return float(p[ix[k]])
                except Exception:
                    return float("nan")
            rmag = num("rmag")
            kind = (p[ix["broad_type"]] if "broad_type" in ix else "").strip()
            f.write(struct.pack("<ffff", ra, de, z, rmag))
            nf.write(p[ix["name"]].strip() + "\t" + kind + "\n")
            n += 1
        f.seek(8)
        f.write(struct.pack("<i", n))
    log(f"wrote milliquas.bin: {n} quasars with redshifts")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/sky")
    ap.add_argument("--only", default="gaia,milliquas")
    ap.add_argument("--gmax", type=float, default=GMAX)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    work = os.path.join(a.out, "work")
    os.makedirs(work, exist_ok=True)
    only = a.only.split(",")
    if "milliquas" in only:
        milliquas(a.out, work)
    if "gaia" in only:
        gaia(a.out, work, a.gmax)
    if "glade" in only:
        glade(a.out, work)


if __name__ == "__main__":
    main()
