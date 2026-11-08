#!/bin/bash
# Checks the online catalogue services the app streams from and records their replies.
out=probe; mkdir -p $out
t() { local name=$1; shift; local s=$(date +%s.%N); curl -sS -L -m 120 -A "UniverseExplorer/1.0" "$@" -o $out/$name.txt -w "%{http_code} %{size_download}" > $out/$name.code 2>$out/$name.err; local e=$(date +%s.%N); echo "$name $(cat $out/$name.code) $(echo "$e - $s" | bc)s" | tee -a $out/summary.txt; }
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
Q="https://ssd-api.jpl.nasa.gov/sbdb_query.api"
t jpl_ast "$Q?fields=full_name,pdes,kind,class,a,e,i,om,w,ma,epoch,H,diameter&full-prec=1&limit=4&sb-kind=a" &
t jpl_com "$Q?fields=full_name,pdes,kind,class,a,e,i,om,w,ma,tp,q,epoch,M1,diameter&full-prec=1&limit=4&sb-kind=c" &
t jpl_page --compressed "$Q?fields=full_name,pdes,class,a,e,i,om,w,ma,epoch,H&full-prec=1&limit=100000&limit-from=200000&sb-kind=a" &
t jpl_page_raw -H "Accept-Encoding: gzip" "$Q?fields=full_name,pdes,class,a,e,i,om,w,ma,epoch,H&full-prec=1&limit=100000&limit-from=300000&sb-kind=a" &
t jpl_one "https://ssd-api.jpl.nasa.gov/sbdb.api?sstr=1P&phys-par=1&full-prec=1" &
t mpc_www -I "https://www.minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz" &
t mpc_data -I "https://data.minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz" &
t simbad_cfa "https://simbad.cfa.harvard.edu/simbad/sim-id?Ident=TON%20618&output.format=ASCII" &
t simbad_u "https://simbad.u-strasbg.fr/simbad/sim-id?Ident=TON%20618&output.format=ASCII" &
t sesame_u "http://cdsweb.u-strasbg.fr/cgi-bin/nph-sesame/-oxp/SNV?TON%20618" &
t sesame_cds "https://cds.unistra.fr/cgi-bin/nph-sesame/-oxp/SNV?TON%20618" &
t ned_m31 "https://ned.ipac.caltech.edu/srs/ObjectLookup?name=M31" &
t ned_ton "https://ned.ipac.caltech.edu/srs/ObjectLookup?name=TON%20618" &
t ned_post -X POST -d 'json={"name":{"v":"TON 618"}}' "https://ned.ipac.caltech.edu/srs/ObjectLookup" &
Q3="SELECT TOP 30000 source_id, ra, dec, parallax, phot_g_mean_mag, bp_rp FROM gaiadr3.gaia_source WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',266.4,-28.9,6.0)) AND parallax BETWEEN 0.8 AND 1.25 AND parallax_over_error > 5"
t esa_gaia_big "https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "$Q3")" &
Q4="SELECT source_id, ra, dec, parallax, phot_g_mean_mag, bp_rp FROM gaiadr3.gaia_source WHERE parallax > 50"
t esa_gaia_near "https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "$Q4")" &
Q5="SELECT TOP 5 * FROM external.gaiaedr3_gcns_main_1"
t esa_gcns "https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "$Q5")" &
Q6="SELECT TOP 3 * FROM external.gladeplus"
t esa_glade "https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=$(enc "$Q6")" &
t esa_tables "https://gea.esac.esa.int/tap-server/tap/tables" &
t vizier_cfa_glade "https://vizier.cfa.harvard.edu/viz-bin/asu-tsv?-source=VII/291/gladep&-c=187.7%2012.4&-c.rd=1&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag&-out.max=5000" &
t vizier_cfa_gaia "https://vizier.cfa.harvard.edu/viz-bin/asu-tsv?-source=I/355/gaiadr3&-c=83.8%20-5.4&-c.rd=0.5&-out=Source,RA_ICRS,DE_ICRS,Plx,Gmag,BP-RP&-out.max=50" &
t vizier_nao "https://vizier.nao.ac.jp/viz-bin/asu-tsv?-source=VII/291/gladep&-c=187.7%2012.4&-c.rd=1&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag&-out.max=50" &
t vizier_iucaa "https://vizier.iucaa.in/viz-bin/asu-tsv?-source=VII/291/gladep&-c=187.7%2012.4&-c.rd=1&-out=RAJ2000,DEJ2000,dL,zcmb,Bmag&-out.max=50" &
wait
grep -o '<table[^>]*name="[^"]*"' $out/esa_tables.txt | grep -i "glade\|gcns\|external" | head -40 > $out/esa_external_tables.txt
rm -f $out/esa_tables.txt
for f in $out/*.txt; do echo "== $f"; head -c 2500 $f; echo; done > $out/all_heads.txt
