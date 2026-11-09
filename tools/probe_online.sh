#!/bin/bash
out=probe; mkdir -p $out
t() { local name=$1; shift; local s=$(date +%s.%N); curl -sS -L -m 120 -A "UniverseExplorer/1.0" "$@" -o $out/$name.txt -w "%{http_code} %{size_download}" > $out/$name.code 2>$out/$name.err; local e=$(date +%s.%N); echo "$name $(cat $out/$name.code) $(echo "$e - $s" | bc)s $(grep -vc '^#' $out/$name.txt)lines" | tee -a $out/summary.txt; }
G="-source=VII/291/gladep&-c=187.7%2012.4&-c.rd=2&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag,PGC&-out.max=100000"
for h in vizier.cfa.harvard.edu vizier.cds.unistra.fr cdsarc.cds.unistra.fr vizier.china-vo.org vizier.idia.ac.za vizier.inasan.ru; do t "viz_$h" "https://$h/viz-bin/asu-tsv?$G" & done
t glade_elte -r 0-2000 "https://glade.elte.hu/GLADE+.txt" &
t glade_elte2 -r 0-2000 "http://glade.elte.hu/GLADE+.txt" &
t cdsarc_ftp -I "https://cdsarc.cds.unistra.fr/ftp/VII/291/gladep.dat.gz" &
t cdsarc_ftp2 -I "http://cdsarc.u-strasbg.fr/ftp/VII/291/gladep.dat.gz" &
t heasarc_glade -I "https://heasarc.gsfc.nasa.gov/FTP/heasarc/dbase/tdat_files/heasarc_glade.tdat.gz" &
t cfa_ftp -I "https://vizier.cfa.harvard.edu/ftp/VII/291/gladep.dat.gz" &
wait
for f in $out/*.txt; do echo "== $f"; grep -v "^#" $f | head -c 800; echo; done > $out/all_heads.txt
