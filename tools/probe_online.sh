#!/bin/bash
out=probe; mkdir -p $out
t() { local name=$1; shift; local s=$(date +%s.%N); curl -sS -L -m 300 -A "UniverseExplorer/1.0" "$@" -o $out/$name.txt -w "%{http_code} %{size_download}" > $out/$name.code 2>$out/$name.err; local e=$(date +%s.%N); echo "$name $(cat $out/$name.code) $(echo "$e - $s" | bc)s $(wc -l < $out/$name.txt)lines" | tee -a $out/summary.txt; }
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
TAP="https://gea.esac.esa.int/tap-server/tap"
# Gaia + Bailer-Jones distances, one HEALPix level-4 pixel (1/3072 of the sky), G < 15.
Q="SELECT g.source_id, g.ra, g.dec, d.r_med_photogeo, g.phot_g_mean_mag, g.bp_rp FROM gaiadr3.gaia_source AS g JOIN external.gaiaedr3_distance AS d ON g.source_id = d.source_id WHERE g.source_id BETWEEN 1000*34359738368*256 AND 1001*34359738368*256 AND g.phot_g_mean_mag < 15"
t gaia_bj_sync "$TAP/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "$Q")"
Q2="SELECT COUNT(*) FROM gaiadr3.gaia_source WHERE phot_g_mean_mag < 15 AND source_id BETWEEN 1000*34359738368*256 AND 1100*34359738368*256"
t gaia_count "$TAP/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "$Q2")"
# async job
loc=$(curl -sS -m 60 -o /dev/null -w "%{redirect_url}" -X POST "$TAP/async" --data-urlencode "REQUEST=doQuery" --data-urlencode "LANG=ADQL" --data-urlencode "FORMAT=csv" --data-urlencode "PHASE=RUN" --data-urlencode "QUERY=SELECT g.source_id, g.ra, g.dec, d.r_med_photogeo, g.phot_g_mean_mag, g.bp_rp FROM gaiadr3.gaia_source AS g JOIN external.gaiaedr3_distance AS d ON g.source_id = d.source_id WHERE g.source_id BETWEEN 1000*34359738368*256 AND 1100*34359738368*256 AND g.phot_g_mean_mag < 15")
echo "async job: $loc" | tee -a $out/summary.txt
s=$(date +%s); for i in $(seq 1 60); do ph=$(curl -sS -m 30 "$loc/phase"); [ "$ph" = "COMPLETED" -o "$ph" = "ERROR" ] && break; sleep 5; done; echo "async phase $ph after $(( $(date +%s) - s ))s" | tee -a $out/summary.txt
t gaia_async "$loc/results/result"
t milliquas_head -I "https://heasarc.gsfc.nasa.gov/FTP/heasarc/dbase/tdat_files/heasarc_milliquas.tdat.gz"
t milliquas_part -r 0-4000 "https://heasarc.gsfc.nasa.gov/FTP/heasarc/dbase/tdat_files/heasarc_milliquas.tdat.gz"
t glade_head -I "http://elysium.elte.hu/~dalyag/GLADE+.txt"
t glade_part -r 0-3000 "http://elysium.elte.hu/~dalyag/GLADE+.txt"
t vizier_cds "https://vizier.cds.unistra.fr/viz-bin/asu-tsv?-source=VII/291/gladep&-c=187.7%2012.4&-c.rd=1&-out=RAJ2000,DEJ2000,dL,Bmag&-out.max=100"
t heasarc_glade -I "https://heasarc.gsfc.nasa.gov/FTP/heasarc/dbase/tdat_files/heasarc_gladeplus.tdat.gz"
t mpc_full "https://www.minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz"
ls -la $out > $out/ls.txt
zcat -f $out/milliquas_part.txt 2>/dev/null | head -c 4000 > $out/milliquas_sample.txt
rm -f $out/mpc_full.txt
for f in $out/*.txt; do echo "== $f"; head -c 1500 $f; echo; done > $out/all_heads.txt
