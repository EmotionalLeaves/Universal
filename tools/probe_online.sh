#!/bin/bash
out=probe; mkdir -p $out
t() { local name=$1; shift; local s=$(date +%s.%N); curl -sS -L -m 90 -A "UniverseExplorer/1.0" "$@" -o $out/$name.txt -w "%{http_code} %{size_download}" > $out/$name.code 2>$out/$name.err; local e=$(date +%s.%N); echo "$name $(cat $out/$name.code) $(echo "$e - $s" | bc)s $(grep -c '^[0-9]' $out/$name.txt)rows" | tee -a $out/summary.txt; }
V="https://vizier.cfa.harvard.edu/viz-bin/asu-tsv?-source=I/355/gaiadr3&-out=Source,RA_ICRS,DE_ICRS,Plx,Gmag,BP-RP,RUWE&-out.max=50000"
for k in 1 2; do
t viz_q1_$k "$V&-c=83.8%20-5.4&-c.rd=14.48&Plx=8..13.33&RUWE=%3C1.4"
t viz_q2_$k "$V&-c=300.0%2030.0&-c.rd=4.3&Plx=2.33..2.70&RUWE=%3C1.4"
t viz_q3_$k "$V&-c=266.4%20-28.9&-c.rd=1.72&Plx=0.647..0.687&RUWE=%3C1.4"
done
t viz_cds "https://vizier.cds.unistra.fr/viz-bin/asu-tsv?-source=I/355/gaiadr3&-out=Source,RA_ICRS,DE_ICRS,Plx,Gmag,BP-RP&-out.max=50000&-c=300.0%2030.0&-c.rd=4.3&Plx=2.33..2.70"
for f in $out/*.txt; do echo "== $f"; grep -v "^#" $f | head -6; done > $out/all_heads.txt
