#!/bin/bash
# Checks the online catalogue services the app streams from and records their replies.
out=probe; mkdir -p $out
t() { local name=$1; shift; local s=$(date +%s.%N); curl -sS -L -m 60 -A "UniverseExplorer/1.0" "$@" -o $out/$name.txt -w "%{http_code} %{size_download}" > $out/$name.code 2>$out/$name.err; local e=$(date +%s.%N); echo "$name $(cat $out/$name.code) $(echo "$e - $s" | bc)s" | tee -a $out/summary.txt; }
S="https://simbad.cfa.harvard.edu/simbad/sim-id?output.format=ASCII&Ident="
t s1 "${S}Gaia%20BH3" &
t s2 "${S}NAME%20Gaia%20BH3" &
t s3 "${S}Kepler-452" &
t s4 "${S}Sirius%20B" &
t s5 "${S}Polaris" &
t s6 "${S}Gaia%20BH1" &
t s7 "${S}Crab%20Nebula" &
t n1 "https://ned.ipac.caltech.edu/srs/ObjectLookup?name=Gaia%20BH3" &
t tap "https://simbad.cfa.harvard.edu/simbad/sim-tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=tsv&QUERY=SELECT%20dist%2C%20unit%20FROM%20mesDistance%20WHERE%20oidref%20%3D%201575544" &
wait
for f in $out/*.txt; do echo "== $f"; head -c 1200 $f; echo; done > $out/all_heads.txt
