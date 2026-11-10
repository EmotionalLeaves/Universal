#!/bin/bash
# Probes real galaxy redshift surveys from CI: directory listings, file sizes and FITS columns.
mkdir -p probe
B=https://data.desi.lbl.gov/public/dr1
for d in survey/catalogs/dr1/LSS/iron/LSScats/v1.5/ survey/catalogs/dr1/LSS/iron/LSScats/ spectro/redux/iron/zcatalog/v1/ vac/dr1/; do
  n=$(echo "$d" | tr '/' '_')
  curl -sSL --max-time 120 "$B/$d" -o "probe/desi_$n.html" -w "%{http_code} $d\n" >> probe/status.txt
done
# SDSS DR17 spectroscopic catalogue
for u in https://data.sdss.org/sas/dr17/sdss/spectro/redux/ https://data.sdss.org/sas/dr17/eboss/lss/catalogs/DR16/ https://data.sdss.org/sas/dr12/boss/lss/; do
  n=$(echo "$u" | md5sum | cut -c1-6)
  curl -sSL --max-time 120 "$u" -o "probe/sdss_$n.html" -w "%{http_code} $u\n" >> probe/status.txt
done
# 2MRS / 6dF
for u in "http://tdc-www.harvard.edu/2mrs/" "https://www.6dfgs.net/"; do
  curl -sSL --max-time 60 -o /dev/null -w "%{http_code} $u\n" "$u" >> probe/status.txt
done
# Sizes of candidate DESI files
for f in BGS_ANY_NGC_clustering.dat.fits BGS_BRIGHT_NGC_clustering.dat.fits LRG_NGC_clustering.dat.fits ELG_LOPnotqso_NGC_clustering.dat.fits QSO_NGC_clustering.dat.fits BGS_ANY_full_HPmapcut.dat.fits LRG_full_HPmapcut.dat.fits; do
  curl -sSLI --max-time 60 "$B/survey/catalogs/dr1/LSS/iron/LSScats/v1.5/$f" | grep -i "content-length\|HTTP/" | tr '\n' ' ' >> probe/sizes.txt
  echo " $f" >> probe/sizes.txt
done
pip install -q astropy >/dev/null 2>&1
python3 - <<'EOF' >> probe/fits.txt 2>&1
from astropy.io import fits
import urllib.request
base = "https://data.desi.lbl.gov/public/dr1/survey/catalogs/dr1/LSS/iron/LSScats/v1.5/"
for f in ["QSO_NGC_clustering.dat.fits", "BGS_ANY_full_HPmapcut.dat.fits"]:
    try:
        urllib.request.urlretrieve(base + f, "/tmp/" + f)
        h = fits.open("/tmp/" + f)
        print(f, len(h[1].data), h[1].columns.names)
    except Exception as e:
        print(f, "ERR", e)
EOF
df -h / >> probe/status.txt
