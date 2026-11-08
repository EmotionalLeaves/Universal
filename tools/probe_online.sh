#!/bin/bash
# Checks the online catalogue services the app streams from and records their replies.
out=probe; mkdir -p $out
t() { local name=$1; shift; local s=$(date +%s.%N); curl -sS -L -m 50 -A "UniverseExplorer/1.0" "$@" -o $out/$name.txt -w "%{http_code} %{size_download}" > $out/$name.code 2>$out/$name.err; local e=$(date +%s.%N); echo "$name $(cat $out/$name.code) $(echo "$e - $s" | bc)s" | tee -a $out/summary.txt; }
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
Q1="SELECT Source, RA_ICRS, DE_ICRS, Plx, Gmag, \"BP-RP\" FROM \"I/355/gaiadr3\" WHERE 1=CONTAINS(POINT('ICRS',RA_ICRS,DE_ICRS),CIRCLE('ICRS',101.28,-16.71,3.0)) AND Plx > 20"
t vtap_gaia "https://tapvizier.cds.unistra.fr/TAPVizieR/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=tsv&QUERY=$(enc "$Q1")" &
Q2="SELECT source_id, ra, dec, parallax, phot_g_mean_mag, bp_rp FROM gaiadr3.gaia_source WHERE source_id BETWEEN 2251799813685248 AND 2252899325313023 AND parallax > 2"
t esa_gaia "https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "$Q2")" &
Q3="SELECT TOP 20000 source_id, ra, dec, parallax, phot_g_mean_mag, bp_rp FROM gaiadr3.gaia_source WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',83.8,-5.4,2.0)) AND parallax BETWEEN 2 AND 3"
t esa_gaia_cone "https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "$Q3")" &
for m in vizier.cds.unistra.fr vizier.cfa.harvard.edu; do
t asu_gaia_$m "https://$m/viz-bin/asu-tsv?-source=I/355/gaiadr3&-c=83.8%20-5.4&-c.rd=2&Plx=2..3&-out=Source,RA_ICRS,DE_ICRS,Plx,Gmag,BP-RP&-out.max=20000" &
done
for n in "TON 618" "Betelgeuse" "M31" "Kepler-452" "Proxima Cen" "Sirius B" "NGC 1300" "Cas A" "Ceres" "Halley"; do
t "sesame_$(echo $n | tr ' ' _)" "https://cds.unistra.fr/cgi-bin/nph-sesame/-oxp/SNV?$(enc "$n")" &
done
t sesame_cfa "https://vizier.cfa.harvard.edu/viz-bin/nph-sesame/-oxp/SNV?$(enc "TON 618")" &
Q4="SELECT b.main_id, m.dist, m.unit, m.method FROM mesDistance m JOIN basic b ON m.oidref=b.oid WHERE b.main_id='NAME Betelgeuse' OR b.main_id='* alf Ori'"
t simbad_dist "https://simbad.cds.unistra.fr/simbad/sim-tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=tsv&QUERY=$(enc "$Q4")" &
t mpcorb_head -I "https://minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz" &
curl -sS -L -m 50 -r 0-200000 "https://minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz" -o $out/mpc_part.gz; (zcat $out/mpc_part.gz 2>/dev/null | head -c 20000) > $out/mpcorb_sample.txt; echo "mpc sample $(wc -c < $out/mpcorb_sample.txt)" >> $out/summary.txt
t comets "https://minorplanetcenter.net/iau/MPCORB/CometEls.txt" &
t lowell_head -I "https://ftp.lowell.edu/pub/elgb/astorb.dat.gz" &
t glade_cols "https://vizier.cds.unistra.fr/viz-bin/asu-tsv?-source=VII/291/gladep&-out.all&-out.max=5" &
t glade_cone "https://vizier.cds.unistra.fr/viz-bin/asu-tsv?-source=VII/291/gladep&-c=187.7%2012.4&-c.rd=1&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag,GWGC,HyperLEDA&-out.max=50000" &
Q5="SELECT COUNT(*) FROM \"VII/291/gladep\" WHERE 1=CONTAINS(POINT('ICRS',RAJ2000,DEJ2000),CIRCLE('ICRS',187.7,12.4,5)) AND dL < 300"
t vtap_glade "https://tapvizier.cds.unistra.fr/TAPVizieR/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=tsv&QUERY=$(enc "$Q5")" &
t sbdb "https://ssd-api.jpl.nasa.gov/sbdb.api?sstr=Ceres&phys-par=1" &
t sbdb_halley "https://ssd-api.jpl.nasa.gov/sbdb.api?sstr=Halley&phys-par=1" &
t sbdb_yr4 "https://ssd-api.jpl.nasa.gov/sbdb.api?sstr=2024%20YR4&phys-par=1" &
t sbdb_bad "https://ssd-api.jpl.nasa.gov/sbdb.api?sstr=Betelgeuse" &
t horizons_list "https://ssd-api.jpl.nasa.gov/sbdb_query.api?fields=full_name,a,e,i,om,w,ma,epoch,H&limit=5" &
wait
for f in $out/*.txt; do echo "== $f"; head -c 1500 $f; echo; done > $out/all_heads.txt
