#!/bin/bash
# Checks the online catalogue services the app streams from and records their replies.
out=probe; mkdir -p $out
t() { local name=$1; shift; local s=$(date +%s.%N); curl -sS -L -m 150 -A "UniverseExplorer/1.0" "$@" -o $out/$name.txt -w "%{http_code} %{size_download}" > $out/$name.code 2>$out/$name.err; local e=$(date +%s.%N); echo "$name $(cat $out/$name.code) $(echo "$e - $s" | bc)s $(wc -l < $out/$name.txt)rows" | tee -a $out/summary.txt; }
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
TAP="https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY="
C="source_id, ra, dec, parallax, phot_g_mean_mag, bp_rp"
t g_sun "$TAP$(enc "SELECT TOP 120000 $C FROM gaiadr3.gaia_source WHERE parallax > 20 AND parallax_over_error > 5")" &
t g_100 "$TAP$(enc "SELECT TOP 120000 $C FROM gaiadr3.gaia_source WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',83.8,-5.4,14.48)) AND parallax BETWEEN 8 AND 13.33 AND parallax_over_error > 5")" &
t g_400 "$TAP$(enc "SELECT TOP 120000 $C FROM gaiadr3.gaia_source WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',300.0,30.0,4.3)) AND parallax BETWEEN 2.33 AND 2.70 AND parallax_over_error > 10")" &
t g_1500gc "$TAP$(enc "SELECT TOP 120000 $C FROM gaiadr3.gaia_source WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',266.4,-28.9,1.72)) AND parallax BETWEEN 0.647 AND 0.687 AND parallax_over_error > 10")" &
t g_id "$TAP$(enc "SELECT $C FROM gaiadr3.gaia_source WHERE source_id = 4015522743604478848")" &
V="https://vizier.cfa.harvard.edu/viz-bin/asu-tsv?-source=VII/291/gladep"
t gl_cols "$V&-out.all&-out.max=3" &
t gl_cone "$V&-c=187.7%2012.4&-c.rd=8.6&dL=170..230&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag,HyperLEDA,GWGC,PGC&-out.max=60000" &
t gl_cone2 "$V&-c=30.0%2040.0&-c.rd=3.0&dL=400..450&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag&-out.max=60000" &
t gl_near "$V&dL=0..15&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag&-out.max=60000" &
t mpc_get -r 0-300000 "https://www.minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz" &
t simbad_betel "https://simbad.cfa.harvard.edu/simbad/sim-id?output.format=ASCII&Ident=Betelgeuse" &
t simbad_bad "https://simbad.cfa.harvard.edu/simbad/sim-id?output.format=ASCII&Ident=Xyzzyplugh" &
t simbad_m31 "https://simbad.cfa.harvard.edu/simbad/sim-id?output.format=ASCII&Ident=M31" &
wait
zcat -f $out/mpc_get.txt 2>/dev/null | head -c 60000 > $out/mpcorb_sample.txt
rm -f $out/mpc_get.txt
for f in $out/*.txt; do echo "== $f"; head -c 2500 $f; echo; done > $out/all_heads.txt
