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

    buckets = Buckets(os.path.join(work, "gbuckets"))
    ov = open(os.path.join(out, "glade.bin"), "wb")
    ov.write(b"UXGL")
    ov.write(struct.pack("<i", 1))
    n_ov = n_tile = n_all = 0
    t0 = time.time()
    with cf.ThreadPoolExecutor(max_workers=6) as ex:
        futs = [ex.submit(fetch, b) for b in boxes]
        for k, f in enumerate(cf.as_completed(futs)):
            box, rows = f.result()
            for p in rows:
                try:
                    ra = float(p[0]); de = float(p[1]); dl = float(p[2])
                except Exception:
                    continue
                if not (dl > 0):
                    continue
                z = float(p[3]) if p[3].strip() else float("nan")
                dc = dl / (1 + z) if z == z and z > 0 else dl / (1 + dl / 4400.0)
                b = float(p[4]) if p[4].strip() else float("nan")
                absb = b - 5 * math.log10(dl * 1e6 / 10) if b == b else float("nan")
                pgc = int(p[5]) if p[5].strip().isdigit() else 0
                x, y, zz = place(ra, de, dc)
                n_all += 1
                if not (absb == absb):
                    absb = -19.5   # no B magnitude (infrared-only): a typical galaxy, streamed with the faint ones
                if absb < OVERVIEW_ABS:
                    ov.write(struct.pack("<ffff", x, y, zz, absb))
                    n_ov += 1
                    continue
                for tier, size, dmin, dmax in GTIERS:
                    if dmin <= dc < dmax:
                        break
                cell = (math.floor(x / size), math.floor(y / size), math.floor(zz / size))
                if max(abs(c) for c in cell) > 32000:
                    continue
                q = [min(65535, int((v / size - c) * 65535 + 0.5)) for v, c in zip((x, y, zz), cell)]
                buckets.add(tier, cell, GREC.pack(q[0], q[1], q[2], int(max(-32767, min(32767, absb * 100))), pgc) + b"\0" * 6)
                n_tile += 1
            if (k + 1) % 100 == 0:
                log(f"  {k + 1}/{len(boxes)} boxes, {n_all:,} galaxies ({n_ov:,} overview, {n_tile:,} tiles), {(time.time() - t0) / 60:.0f} min")
    ov.close()
    buckets.close()
    log(f"glade: {n_all:,} galaxies; overview {n_ov:,}; tiles {n_tile:,}")
    write_tiles(out, buckets.root, "galaxy", GTIERS, GREC.size, pad=6, sort_key=lambda r: struct.unpack_from("<h", r, 6)[0])


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
