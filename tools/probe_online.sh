#!/bin/bash
# Probes real galaxy redshift surveys from CI: file sizes and FITS columns (from the first few MB).
mkdir -p probe
D=https://data.desi.lbl.gov/public/dr1/spectro/redux/iron/zcatalog/v1
S=https://data.sdss.org/sas/dr17/sdss/spectro/redux
for u in $D/zall-pix-iron.fits $D/zpix-main-bright.fits $D/zpix-main-dark.fits $S/specObj-dr17.fits $S/v5_13_2/spAll-v5_13_2.fits; do
  curl -sSLI --max-time 60 "$u" | grep -i "content-length\|HTTP/" | tr '\n' ' ' >> probe/sizes.txt; echo " $u" >> probe/sizes.txt
  curl -sSL --max-time 120 -r 0-8000000 "$u" -o /tmp/head.fits
  python3 - "$u" >> probe/fits.txt <<'PY'
import sys
b = open('/tmp/head.fits', 'rb').read()
cards = [b[i:i+80].decode('latin-1') for i in range(0, len(b) - 80, 80)]
out = []
for c in cards:
    k = c[:8].strip()
    if k.startswith('TTYPE') or k.startswith('TFORM') or k in ('NAXIS1', 'NAXIS2', 'EXTNAME'):
        out.append(c.strip()[:60])
    if len(out) > 900: break
print(sys.argv[1]); print('\n'.join(out)); print()
PY
done
