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
    pending = [(i * HPX12 // 96, (i + 1) * HPX12 // 96) for i in range(96)]
    total = 0
    tmp = os.path.join(work, "job.csv")
    t_start = time.time()
    while pending:
        a, b = pending.pop(0)
        lo, hi = a * (1 << 35), b * (1 << 35) - 1
        q = (f"SELECT TOP {PAGE} {cols} FROM gaiadr3.gaia_source AS g JOIN external.gaiaedr3_distance AS d "
             f"ON g.source_id = d.source_id WHERE g.source_id BETWEEN {lo} AND {hi} AND g.phot_g_mean_mag < {gmax}")
        t0 = time.time()
        async_query(q, tmp)
        n = 0
        kept = 0
        with open(tmp, "r") as f:
            header = f.readline().strip().split(",")
            for line in f:
                n += 1
        if n >= PAGE and b - a > 1:
            m = (a + b) // 2
            pending[:0] = [(a, m), (m, b)]
            log(f"  range {a}-{b}: {n} rows (capped), splitting")
            continue
        with open(tmp, "r") as f:
            f.readline()
            for line in f:
                p = line.rstrip("\n").split(",")
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
        total += kept
        log(f"  range {a}-{b}: {n} rows, kept {kept}, total {total:,} ({time.time() - t0:.0f} s; {len(pending)} ranges left; "
            f"{(time.time() - t_start) / 60:.0f} min so far)")
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


if __name__ == "__main__":
    main()
