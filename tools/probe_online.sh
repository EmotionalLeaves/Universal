#!/bin/bash
# Times Gaia bubble queries on each public Gaia server (one at a time, like a phone would).
out=probe; mkdir -p $out
t() { local name=$1; shift; local s=$(date +%s.%N); curl -sS -L -m 90 -A "UniverseExplorer/1.0" "$@" -o $out/$name.txt -w "%{http_code} %{size_download}" > $out/$name.code 2>$out/$name.err; local e=$(date +%s.%N); echo "$name $(cat $out/$name.code) $(echo "$e - $s" | bc)s $(wc -l < $out/$name.txt)rows" | tee -a $out/summary.txt; }
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
C="source_id, ra, dec, parallax, phot_g_mean_mag, bp_rp"
Q1="WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',83.8,-5.4,14.48)) AND parallax BETWEEN 8 AND 13.33 AND parallax_over_error > 5"
Q2="WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',300.0,30.0,4.3)) AND parallax BETWEEN 2.33 AND 2.70 AND parallax_over_error > 10"
Q3="WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',266.4,-28.9,1.72)) AND parallax BETWEEN 0.647 AND 0.687 AND parallax_over_error > 10"
Q4="WHERE parallax > 40 AND parallax_over_error > 5"
for svc in esa ari gavo; do
  case $svc in
    esa) U="https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY="; T=gaiadr3.gaia_source; CC="$C";;
    ari) U="https://gaia.ari.uni-heidelberg.de/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY="; T=gaiadr3.gaia_source; CC="$C";;
    gavo) U="https://dc.g-vo.org/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY="; T=gaia.dr3lite; CC="source_id, ra, dec, parallax, phot_g_mean_mag, phot_bp_mean_mag - phot_rp_mean_mag AS bp_rp";;
  esac
  i=0; for Q in "$Q1" "$Q2" "$Q3" "$Q4"; do i=$((i+1)); QQ="$Q"; [ $svc = gavo ] && QQ="${Q/parallax_over_error > 5/parallax > 10*parallax_error}" && QQ="${QQ/parallax_over_error > 10/parallax > 10*parallax_error}"
    t ${svc}_q$i "$U$(enc "SELECT TOP 50000 $CC FROM $T $QQ")"
  done
done
t gavo_cols "https://dc.g-vo.org/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "SELECT TOP 1 * FROM gaia.dr3lite")"
t ari_n "https://gaia.ari.uni-heidelberg.de/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "SELECT COUNT(*) FROM gaiadr3.gaia_source WHERE parallax > 10 AND parallax_over_error > 10")"
for f in $out/*.txt; do echo "== $f"; head -c 600 $f; echo; done > $out/all_heads.txt
