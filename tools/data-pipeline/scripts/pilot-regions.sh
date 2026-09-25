#!/usr/bin/env bash
# Elenco del lotto pilota — sorgente unica condivisa da
# build-pilot-regions.sh (esecuzione locale, tutte le regioni) e dal workflow publish-regions.yml
# (esecuzione selettiva via input workflow_dispatch), per evitare due copie che possono
# disallinearsi.
#
# Gli Stati Uniti sono pubblicati come una regione per stato (48 stati contigui + Distretto di
# Columbia, dal 2026-09-25) piu' Alaska e Hawaii: un'unica regione per i 48 stati era troppo grande
# (civici impossibili da estrarre, download di gigabyte per visitare una citta'). Le righe degli
# stati vengono da generate-subregion-rows.py (confini Natural Earth); la vecchia regione
# "stati-uniti" e' in REPLACED_REGIONS, piu' sotto. Allo stesso modo, sempre dal 2026-09-25: Canada
# (13 province e territori), Russia europea (8 regioni economiche a ovest dei 60° E), Cina (31
# province e municipalita') e Francia metropolitana (13 regioni). Ogni region e' un'unita'
# geografica arbitraria nello schema (RegionManifestEntry), non necessariamente una nazione intera.
#
# I bbox sono approssimazioni rettangolari (mapSource supporta solo bbox, non poligoni) —
# includono territorio confinante/mare, coerente con qualunque approccio a bounding-box.
#
# flagCode e' il codice ISO 3166-1 alpha-2 in minuscolo della nazione (le region statunitensi
# condividono lo stesso "us", non sono nazioni a se' - vedi nota sopra) - usato nella pagina
# indice statica del sito pubblicato (assemble-site.sh) per risolvere il file
# scripts/assets/flags/<flagCode>.svg (bandiera vettoriale, non emoji: gli emoji bandiera non si
# vedono su Windows, il font di sistema non li renderizza).
#
# groupName/groupLabel (opzionali, vuoti per le nazioni normali): quando piu' region condividono lo
# stesso groupName (es. gli stati USA), la pagina indice statica (assemble-site.sh) e l'elenco
# regioni dell'app le raccolgono sotto un'unica voce "groupName" con groupLabel come sotto-voce.
# Arrivano all'app nel manifest (campi groupName/groupLabel, scritti da mergeManifests). Non
# toccano ne' regionId ne' displayName.
#
# continent raggruppa ulteriormente la pagina indice sotto un'intestazione per continente - le
# righe vanno tenute ordinate per continente (qui gia' cosi': Europa, poi Asia, poi Nord America)
# perche' assemble-site.sh apre/chiude ogni sezione con un solo passaggio, senza riordinare.
# Hawaii e' geograficamente nel Pacifico ma resta "Nord America" qui: e' la stessa voce/gruppo
# "Stati Uniti d'America" delle altre region USA, non ha senso spezzare un gruppo a meta' tra
# due continenti.
#
# Formato per riga:
#   regionId|displayName|minLon|minLat|maxLon|maxLat|wikivoyagePageTitle|flagCode|groupName|groupLabel|continent
PILOT_REGIONS=(
  "san-marino|San Marino|12.40|43.89|12.52|43.99|San_Marino|sm|||Europa"
  "italia|Italia|6.60|35.29|18.60|47.10|Italy|it|||Europa"
  "albania|Albania|19.30|39.62|21.02|42.69|Albania|al|||Europa"
  "andorra|Andorra|1.41|42.43|1.79|42.66|Andorra|ad|||Europa"
  "austria|Austria|9.48|46.43|16.98|49.04|Austria|at|||Europa"
  "belgio|Belgio|2.51|49.53|6.16|51.48|Belgium|be|||Europa"
  "bielorussia|Bielorussia|23.20|51.32|32.69|56.17|Belarus|by|||Europa"
  "bosnia-erzegovina|Bosnia ed Erzegovina|15.75|42.65|19.60|45.23|Bosnia_and_Herzegovina|ba|||Europa"
  "bulgaria|Bulgaria|22.38|41.23|28.56|44.23|Bulgaria|bg|||Europa"
  "cipro|Cipro|32.26|34.57|34.00|35.17|Cyprus|cy|||Europa"
  "citta-del-vaticano|Citta' del Vaticano|12.445|41.900|12.458|41.908|Vatican_City|va|||Europa"
  "croazia|Croazia|13.66|42.48|19.39|46.50|Croatia|hr|||Europa"
  "danimarca|Danimarca|8.09|54.80|12.69|57.73|Denmark|dk|||Europa"
  "estonia|Estonia|23.34|57.47|28.13|59.61|Estonia|ee|||Europa"
  "finlandia|Finlandia|20.65|59.85|31.52|70.16|Finland|fi|||Europa"
  "francia-alta-francia|Francia - Alta Francia|1.37|48.84|4.26|51.09|Hauts-de-France|fr|Francia|Alta Francia|Europa"
  "francia-alvernia-rodano-alpi|Francia - Alvernia-Rodano-Alpi|2.05|44.12|7.17|46.80|Auvergne-Rhône-Alpes|fr|Francia|Alvernia-Rodano-Alpi|Europa"
  "francia-borgogna-franca-contea|Francia - Borgogna-Franca Contea|2.83|46.17|7.16|48.42|Bourgogne-Franche-Comté|fr|Francia|Borgogna-Franca Contea|Europa"
  "francia-bretagna|Francia - Bretagna|-5.14|47.29|-1.03|48.88|Brittany|fr|Francia|Bretagna|Europa"
  "francia-centro-valle-della-loira|Francia - Centro-Valle della Loira|0.06|46.35|3.12|48.94|Centre-Val_de_Loire|fr|Francia|Centro-Valle della Loira|Europa"
  "francia-corsica|Francia - Corsica|8.54|41.36|9.56|43.02|Corsica|fr|Francia|Corsica|Europa"
  "francia-grand-est|Francia - Grand Est|3.38|47.41|8.21|50.17|Grand_Est|fr|Francia|Grand Est|Europa"
  "francia-ile-de-france|Francia - Île-de-France|1.44|48.12|3.54|49.24|Île-de-France|fr|Francia|Île-de-France|Europa"
  "francia-normandia|Francia - Normandia|-1.95|48.18|1.79|50.07|Normandy|fr|Francia|Normandia|Europa"
  "francia-nuova-aquitania|Francia - Nuova Aquitania|-1.80|42.77|2.60|47.17|Nouvelle-Aquitaine|fr|Francia|Nuova Aquitania|Europa"
  "francia-occitania|Francia - Occitania|-0.32|42.32|4.85|45.05|Occitanie|fr|Francia|Occitania|Europa"
  "francia-paesi-della-loira|Francia - Paesi della Loira|-2.56|46.27|0.92|48.57|Pays_de_la_Loire|fr|Francia|Paesi della Loira|Europa"
  "francia-provenza-alpi-costa-azzurra|Francia - Provenza-Alpi-Costa Azzurra|4.20|42.98|7.70|45.13|Provence-Alpes-Côte_d'Azur|fr|Francia|Provenza-Alpi-Costa Azzurra|Europa"
  "germania|Germania|5.99|47.30|15.02|54.98|Germany|de|||Europa"
  "grecia|Grecia|20.15|34.92|26.60|41.83|Greece|gr|||Europa"
  "irlanda|Irlanda|-9.98|51.67|-6.03|55.13|Ireland|ie|||Europa"
  "islanda|Islanda|-24.33|63.50|-13.61|66.53|Iceland|is|||Europa"
  "kosovo|Kosovo|20.02|41.85|21.80|43.27|Kosovo|xk|||Europa"
  "lettonia|Lettonia|21.06|55.62|28.18|57.97|Latvia|lv|||Europa"
  "liechtenstein|Liechtenstein|9.47|47.05|9.64|47.27|Liechtenstein|li|||Europa"
  "lituania|Lituania|21.06|53.91|26.59|56.37|Lithuania|lt|||Europa"
  "lussemburgo|Lussemburgo|5.67|49.44|6.24|50.13|Luxembourg|lu|||Europa"
  "macedonia-del-nord|Macedonia del Nord|20.46|40.84|22.95|42.32|North_Macedonia|mk|||Europa"
  "malta|Malta|14.18|35.78|14.58|36.08|Malta|mt|||Europa"
  "moldavia|Moldavia|26.62|45.49|30.02|48.47|Moldova|md|||Europa"
  "monaco|Monaco|7.40|43.72|7.44|43.75|Monaco|mc|||Europa"
  "montenegro|Montenegro|18.45|41.88|20.34|43.52|Montenegro|me|||Europa"
  "norvegia|Norvegia|4.99|58.08|31.29|71.19|Norway|no|||Europa"
  "paesi-bassi|Paesi Bassi|3.31|50.80|7.09|53.51|Netherlands|nl|||Europa"
  "polonia|Polonia|14.07|49.03|24.03|54.85|Poland|pl|||Europa"
  "portogallo|Portogallo|-9.53|36.84|-6.39|42.28|Portugal|pt|||Europa"
  "regno-unito|Regno Unito|-7.57|49.96|1.68|58.64|United_Kingdom|gb|||Europa"
  "repubblica-ceca|Repubblica Ceca|12.24|48.56|18.85|51.12|Czech_Republic|cz|||Europa"
  "romania|Romania|20.22|43.69|29.63|48.22|Romania|ro|||Europa"
  "serbia|Serbia|18.83|42.25|22.99|46.17|Serbia|rs|||Europa"
  "slovacchia|Slovacchia|16.88|47.76|22.56|49.57|Slovakia|sk|||Europa"
  "slovenia|Slovenia|13.70|45.45|16.56|46.85|Slovenia|si|||Europa"
  "spagna|Spagna|-9.39|35.95|3.04|43.75|Spain|es|||Europa"
  "svezia|Svezia|11.03|55.36|23.90|69.11|Sweden|se|||Europa"
  "svizzera|Svizzera|6.02|45.78|10.44|47.83|Switzerland|ch|||Europa"
  "ucraina|Ucraina|22.09|44.36|40.08|52.34|Ukraine|ua|||Europa"
  "ungheria|Ungheria|16.20|45.76|22.71|48.62|Hungary|hu|||Europa"
  "isole-faroe|Isole Faroe (Danimarca)|-7.68|61.40|-6.25|62.40|Faroe_Islands|fo|||Europa"
  "svalbard-jan-mayen|Svalbard e Jan Mayen (Norvegia)|-9.10|70.80|33.50|80.85|Svalbard|sj|||Europa"
  "gibilterra|Gibilterra (Regno Unito)|-5.36|36.10|-5.33|36.16|Gibraltar|gi|||Europa"
  # La Russia non ha un unico bbox rettangolare sensato: il territorio attraverserebbe
  # l'antimeridiano (dalla Kaliningrad a ~19E fino alla Chukotka oltre i -169W) e ha l'exclave di
  # Kaliningrad non contigua col resto del paese. Come per gli Stati Uniti (vedi sopra), e' spezzata
  # in piu' region — qui per fascia di longitudine (Urali ~60E come confine Europa/Asia
  # convenzionale, poi ~120E) invece che per stato federato, dato che mapSource supporta solo bbox
  # rettangolari e i confini dei distretti federali non lo sono. Il lembo di terra oltre i 180E
  # (punta della Chukotka, isola Wrangel) e' omesso: territorio remoto e pressoche' disabitato,
  # richiederebbe una quinta region minuscola solo per quello.
  "russia-kaliningrad|Russia - Kaliningrad|19.50|54.25|22.90|55.35|Kaliningrad|ru|Russia|Kaliningrad (exclave)|Europa"
  "russia-caucaso-settentrionale|Russia - Caucaso settentrionale|32.50|41.19|48.58|50.23|North_Caucasus|ru|Russia|Caucaso settentrionale|Europa"
  "russia-centro|Russia - Centro|30.79|51.78|47.61|59.64|Central_Russia|ru|Russia|Centro|Europa"
  "russia-nord-europeo|Russia - Nord europeo|28.41|58.49|68.95|81.86|Northwestern_Russia|ru|Russia|Nord europeo|Europa"
  "russia-nord-ovest|Russia - Nord-Ovest|27.35|55.57|36.18|61.33|Northwestern_Russia|ru|Russia|Nord-Ovest|Europa"
  "russia-terra-nera-centrale|Russia - Terra Nera centrale|34.11|49.56|43.24|53.83|Central_Russia|ru|Russia|Terra Nera centrale|Europa"
  "russia-urali-europei|Russia - Urali europei|50.79|50.49|61.59|61.69|Urals|ru|Russia|Urali europei|Europa"
  "russia-volga|Russia - Volga|41.16|44.74|54.18|56.67|Volga_Region|ru|Russia|Volga|Europa"
  "russia-volga-vjatka|Russia - Volga-Vjatka|41.78|53.66|53.93|61.06|Volga_Region|ru|Russia|Volga-Vjatka|Europa"
  "giappone|Giappone|122.90|20.30|153.99|45.60|Japan|jp|||Asia"
  "russia-siberia|Russia - Siberia|60.00|45.00|120.00|82.00|Siberia|ru|Russia|Siberia|Asia"
  "russia-estremo-oriente|Russia - Estremo Oriente|120.00|41.50|180.00|77.50|Russian_Far_East|ru|Russia|Estremo Oriente|Asia"
  "afghanistan|Afghanistan|60.53|29.32|75.16|38.49|Afghanistan|af|||Asia"
  "armenia|Armenia|43.58|38.74|46.51|41.25|Armenia|am|||Asia"
  "azerbaigian|Azerbaigian|44.79|38.27|50.39|41.86|Azerbaijan|az|||Asia"
  "bahrein|Bahrein|50.38|25.60|50.83|26.29|Bahrain|bh|||Asia"
  "bangladesh|Bangladesh|88.08|20.67|92.67|26.45|Bangladesh|bd|||Asia"
  "bhutan|Bhutan|88.81|26.72|92.10|28.30|Bhutan|bt|||Asia"
  "brunei|Brunei|114.20|4.01|115.45|5.45|Brunei|bn|||Asia"
  "cambogia|Cambogia|102.35|10.49|107.61|14.57|Cambodia|kh|||Asia"
  "cina-anhui|Cina - Anhui|114.87|29.40|119.63|34.64|Anhui|cn|Cina|Anhui|Asia"
  "cina-chongqing|Cina - Chongqing|105.29|28.19|110.18|32.20|Chongqing|cn|Cina|Chongqing|Asia"
  "cina-fujian|Cina - Fujian|115.84|23.58|120.43|28.33|Fujian|cn|Cina|Fujian|Asia"
  "cina-gansu|Cina - Gansu|92.77|32.60|108.71|42.79|Gansu|cn|Cina|Gansu|Asia"
  "cina-guangdong|Cina - Guangdong|109.66|20.26|117.18|25.51|Guangdong|cn|Cina|Guangdong|Asia"
  "cina-guangxi|Cina - Guangxi|104.49|21.42|112.05|26.39|Guangxi|cn|Cina|Guangxi|Asia"
  "cina-guizhou|Cina - Guizhou|103.59|24.63|109.51|29.25|Guizhou|cn|Cina|Guizhou|Asia"
  "cina-hainan|Cina - Hainan|108.63|18.21|111.02|20.14|Hainan|cn|Cina|Hainan|Asia"
  "cina-hebei|Cina - Hebei|113.45|36.05|119.85|42.60|Hebei|cn|Cina|Hebei|Asia"
  "cina-heilongjiang|Cina - Heilongjiang|121.17|43.43|134.76|53.56|Heilongjiang|cn|Cina|Heilongjiang|Asia"
  "cina-henan|Cina - Henan|110.32|31.39|116.65|36.36|Henan|cn|Cina|Henan|Asia"
  "cina-hubei|Cina - Hubei|108.38|29.06|116.15|33.27|Hubei|cn|Cina|Hubei|Asia"
  "cina-hunan|Cina - Hunan|108.79|24.64|114.23|30.10|Hunan|cn|Cina|Hunan|Asia"
  "cina-jiangsu|Cina - Jiangsu|116.37|30.76|121.87|35.11|Jiangsu|cn|Cina|Jiangsu|Asia"
  "cina-jiangxi|Cina - Jiangxi|113.56|24.49|118.48|30.06|Jiangxi|cn|Cina|Jiangxi|Asia"
  "cina-jilin|Cina - Jilin|121.66|40.85|131.27|46.28|Jilin|cn|Cina|Jilin|Asia"
  "cina-liaoning|Cina - Liaoning|118.85|38.73|125.77|43.48|Liaoning|cn|Cina|Liaoning|Asia"
  "cina-mongolia-interna|Cina - Mongolia Interna|97.20|37.39|126.06|53.32|Inner_Mongolia|cn|Cina|Mongolia Interna|Asia"
  "cina-ningxia|Cina - Ningxia|104.35|35.26|107.66|39.37|Ningxia|cn|Cina|Ningxia|Asia"
  "cina-pechino|Cina - Pechino|115.42|39.44|117.43|41.04|Beijing|cn|Cina|Pechino|Asia"
  "cina-qinghai|Cina - Qinghai|89.43|31.54|103.05|39.33|Qinghai|cn|Cina|Qinghai|Asia"
  "cina-shaanxi|Cina - Shaanxi|105.49|31.70|111.23|39.57|Shaanxi|cn|Cina|Shaanxi|Asia"
  "cina-shandong|Cina - Shandong|114.83|34.39|122.67|38.28|Shandong|cn|Cina|Shandong|Asia"
  "cina-shanghai|Cina - Shanghai|120.88|30.68|121.88|31.81|Shanghai|cn|Cina|Shanghai|Asia"
  "cina-shanxi|Cina - Shanxi|110.23|34.58|114.55|40.75|Shanxi|cn|Cina|Shanxi|Asia"
  "cina-sichuan|Cina - Sichuan|97.36|26.03|108.52|34.30|Sichuan|cn|Cina|Sichuan|Asia"
  "cina-tianjin|Cina - Tianjin|116.68|38.55|118.01|40.26|Tianjin|cn|Cina|Tianjin|Asia"
  "cina-tibet|Cina - Tibet|78.38|27.31|99.11|36.45|Tibet|cn|Cina|Tibet|Asia"
  "cina-xinjiang|Cina - Xinjiang|73.60|34.33|96.38|49.17|Xinjiang|cn|Cina|Xinjiang|Asia"
  "cina-yunnan|Cina - Yunnan|97.52|21.15|106.20|29.23|Yunnan|cn|Cina|Yunnan|Asia"
  "cina-zhejiang|Cina - Zhejiang|118.00|27.20|122.41|31.18|Zhejiang|cn|Cina|Zhejiang|Asia"
  "georgia|Georgia|39.96|41.06|46.64|43.55|Georgia_(country)|ge|||Asia"
  "hong-kong|Hong Kong (Cina)|113.83|22.15|114.44|22.58|Hong_Kong|hk|||Asia"
  "macao|Macao (Cina)|113.52|22.10|113.60|22.22|Macau|mo|||Asia"
  "india|India|68.18|7.97|97.40|35.67|India|in|||Asia"
  "indonesia|Indonesia|95.29|-10.36|141.03|5.48|Indonesia|id|||Asia"
  "iran|Iran|44.11|25.08|63.32|39.71|Iran|ir|||Asia"
  "iraq|Iraq|38.79|29.10|48.57|37.39|Iraq|iq|||Asia"
  "israele|Israele|34.27|29.50|35.84|33.28|Israel|il|||Asia"
  "giordania|Giordania|34.92|29.20|39.20|33.38|Jordan|jo|||Asia"
  "kazakistan|Kazakistan|46.47|40.66|87.36|55.39|Kazakhstan|kz|||Asia"
  "kuwait|Kuwait|46.57|28.53|48.42|30.06|Kuwait|kw|||Asia"
  "kirghizistan|Kirghizistan|69.46|39.28|80.26|43.30|Kyrgyzstan|kg|||Asia"
  "laos|Laos|100.12|13.88|107.56|22.46|Laos|la|||Asia"
  "libano|Libano|35.13|33.09|36.61|34.64|Lebanon|lb|||Asia"
  "malesia|Malesia|100.09|0.77|119.18|6.93|Malaysia|my|||Asia"
  "maldive|Maldive|72.65|-0.75|73.75|7.10|Maldives|mv|||Asia"
  "mongolia|Mongolia|87.75|41.60|119.77|52.05|Mongolia|mn|||Asia"
  "myanmar|Myanmar|92.30|9.93|101.18|28.34|Myanmar|mm|||Asia"
  "nepal|Nepal|80.09|26.40|88.17|30.42|Nepal|np|||Asia"
  "corea-del-nord|Corea del Nord|124.27|37.67|130.78|42.99|North_Korea|kp|||Asia"
  "oman|Oman|52.00|16.65|59.81|26.40|Oman|om|||Asia"
  "pakistan|Pakistan|60.87|23.69|77.84|37.13|Pakistan|pk|||Asia"
  "palestina|Palestina|34.93|31.35|35.55|32.53|Palestinian_Territories|ps|||Asia"
  "filippine|Filippine|117.17|5.58|126.54|18.51|Philippines|ph|||Asia"
  "qatar|Qatar|50.74|24.56|51.61|26.11|Qatar|qa|||Asia"
  "arabia-saudita|Arabia Saudita|34.63|16.35|55.67|32.16|Saudi_Arabia|sa|||Asia"
  "singapore|Singapore|103.60|1.15|104.10|1.47|Singapore|sg|||Asia"
  "corea-del-sud|Corea del Sud|126.12|34.39|129.47|38.61|South_Korea|kr|||Asia"
  "sri-lanka|Sri Lanka|79.70|5.97|81.79|9.82|Sri_Lanka|lk|||Asia"
  "siria|Siria|35.70|32.31|42.35|37.23|Syria|sy|||Asia"
  "taiwan|Taiwan|120.11|21.97|121.95|25.30|Taiwan|tw|||Asia"
  "tagikistan|Tagikistan|67.44|36.74|74.98|40.96|Tajikistan|tj|||Asia"
  "thailandia|Thailandia|97.38|5.69|105.59|20.42|Thailand|th|||Asia"
  "timor-est|Timor Est|124.97|-9.39|127.34|-8.27|East_Timor|tl|||Asia"
  "turchia|Turchia|26.04|35.82|44.79|42.14|Turkey|tr|||Asia"
  "turkmenistan|Turkmenistan|52.50|35.27|66.55|42.75|Turkmenistan|tm|||Asia"
  "emirati-arabi-uniti|Emirati Arabi Uniti|51.58|22.50|56.40|26.06|United_Arab_Emirates|ae|||Asia"
  "uzbekistan|Uzbekistan|55.93|37.14|73.06|45.59|Uzbekistan|uz|||Asia"
  "vietnam|Vietnam|102.17|8.60|109.34|23.35|Vietnam|vn|||Asia"
  "yemen|Yemen|42.60|12.59|54.55|19.00|Yemen|ye|||Asia"
  "algeria|Algeria|-8.68|19.06|12.00|37.12|Algeria|dz|||Africa"
  "angola|Angola|11.64|-17.93|24.08|-4.44|Angola|ao|||Africa"
  "benin|Benin|0.77|6.14|3.80|12.24|Benin|bj|||Africa"
  "botswana|Botswana|19.90|-26.83|29.43|-17.66|Botswana|bw|||Africa"
  "burkina-faso|Burkina Faso|-5.47|9.61|2.18|15.12|Burkina_Faso|bf|||Africa"
  "burundi|Burundi|29.02|-4.50|30.75|-2.35|Burundi|bi|||Africa"
  "capo-verde|Capo Verde|-25.40|14.80|-22.65|17.20|Cape_Verde|cv|||Africa"
  "camerun|Camerun|8.49|1.73|16.01|12.86|Cameroon|cm|||Africa"
  "repubblica-centrafricana|Repubblica Centrafricana|14.46|2.27|27.37|11.14|Central_African_Republic|cf|||Africa"
  "ciad|Ciad|13.54|7.42|23.89|23.41|Chad|td|||Africa"
  "comore|Comore|43.20|-12.50|44.60|-11.35|Comoros|km|||Africa"
  "congo-rd|Repubblica Democratica del Congo|12.18|-13.26|31.17|5.26|Democratic_Republic_of_the_Congo|cd|||Africa"
  "congo|Repubblica del Congo|11.09|-5.04|18.45|3.73|Republic_of_the_Congo|cg|||Africa"
  "gibuti|Gibuti|41.66|10.93|43.32|12.70|Djibouti|dj|||Africa"
  "egitto|Egitto|24.70|22.00|36.87|31.59|Egypt|eg|||Africa"
  "guinea-equatoriale|Guinea Equatoriale|9.31|1.01|11.29|2.28|Equatorial_Guinea|gq|||Africa"
  "eritrea|Eritrea|36.32|12.46|43.08|18.00|Eritrea|er|||Africa"
  "eswatini|Eswatini|30.68|-27.32|32.13|-25.72|Eswatini|sz|||Africa"
  "etiopia|Etiopia|32.95|3.42|47.79|14.96|Ethiopia|et|||Africa"
  "gabon|Gabon|8.80|-3.98|14.43|2.33|Gabon|ga|||Africa"
  "gambia|Gambia|-16.84|13.13|-13.84|13.88|Gambia|gm|||Africa"
  "ghana|Ghana|-3.24|4.71|1.06|11.10|Ghana|gh|||Africa"
  "guinea|Guinea|-15.13|7.31|-7.83|12.59|Guinea|gn|||Africa"
  "guinea-bissau|Guinea-Bissau|-16.68|11.04|-13.70|12.63|Guinea-Bissau|gw|||Africa"
  "costa-avorio|Costa d'Avorio|-8.60|4.34|-2.56|10.52|Ivory_Coast|ci|||Africa"
  "kenya|Kenya|33.89|-4.68|41.86|5.51|Kenya|ke|||Africa"
  "lesotho|Lesotho|27.00|-30.65|29.33|-28.65|Lesotho|ls|||Africa"
  "liberia|Liberia|-11.44|4.36|-7.54|8.54|Liberia|lr|||Africa"
  "libia|Libia|9.32|19.58|25.16|33.14|Libya|ly|||Africa"
  "madagascar|Madagascar|43.25|-25.60|50.48|-12.04|Madagascar|mg|||Africa"
  "malawi|Malawi|32.69|-16.80|35.77|-9.23|Malawi|mw|||Africa"
  "mali|Mali|-12.17|10.10|4.27|24.97|Mali|ml|||Africa"
  "mauritania|Mauritania|-17.06|14.62|-4.92|27.40|Mauritania|mr|||Africa"
  "mauritius|Mauritius|57.30|-20.55|63.50|-19.90|Mauritius|mu|||Africa"
  "marocco|Marocco|-13.17|27.66|-1.12|35.92|Morocco|ma|||Africa"
  "mozambico|Mozambico|30.18|-26.74|40.78|-10.32|Mozambique|mz|||Africa"
  "namibia|Namibia|11.73|-29.05|25.08|-16.94|Namibia|na|||Africa"
  "niger|Niger|0.30|11.66|15.90|23.47|Niger|ne|||Africa"
  "nigeria|Nigeria|2.69|4.24|14.58|13.87|Nigeria|ng|||Africa"
  "ruanda|Ruanda|29.02|-2.92|30.82|-1.13|Rwanda|rw|||Africa"
  "sao-tome-principe|Sao Tome e Principe|6.45|0.02|7.47|1.70|Sao_Tome_and_Principe|st|||Africa"
  "senegal|Senegal|-17.63|12.33|-11.47|16.60|Senegal|sn|||Africa"
  "seychelles|Seychelles|46.20|-9.60|56.30|-3.70|Seychelles|sc|||Africa"
  "sierra-leone|Sierra Leone|-13.25|6.79|-10.23|10.05|Sierra_Leone|sl|||Africa"
  "somalia|Somalia|40.98|-1.68|51.13|12.02|Somalia|so|||Africa"
  "sudafrica|Sudafrica|16.34|-34.82|32.83|-22.09|South_Africa|za|||Africa"
  "sud-sudan|Sud Sudan|23.89|3.51|35.30|12.25|South_Sudan|ss|||Africa"
  "sudan|Sudan|21.94|8.62|38.41|22.00|Sudan|sd|||Africa"
  "tanzania|Tanzania|29.34|-11.72|40.32|-0.95|Tanzania|tz|||Africa"
  "togo|Togo|-0.05|5.93|1.87|11.02|Togo|tg|||Africa"
  "tunisia|Tunisia|7.52|30.31|11.49|37.35|Tunisia|tn|||Africa"
  "uganda|Uganda|29.58|-1.44|35.04|4.25|Uganda|ug|||Africa"
  "zambia|Zambia|21.89|-17.96|33.49|-8.24|Zambia|zm|||Africa"
  "zimbabwe|Zimbabwe|25.26|-22.27|32.85|-15.51|Zimbabwe|zw|||Africa"
  "riunione|Riunione (Francia)|55.22|-21.39|55.84|-20.87|Reunion|re|||Africa"
  "mayotte|Mayotte (Francia)|45.02|-13.00|45.30|-12.64|Mayotte|yt|||Africa"
  "stati-uniti-alabama|Stati Uniti - Alabama|-88.49|30.23|-84.92|35.03|Alabama|us|Stati Uniti d'America|Alabama|Nord America"
  "stati-uniti-arizona|Stati Uniti - Arizona|-114.84|31.32|-109.04|37.01|Arizona|us|Stati Uniti d'America|Arizona|Nord America"
  "stati-uniti-arkansas|Stati Uniti - Arkansas|-94.62|33.01|-89.68|36.51|Arkansas|us|Stati Uniti d'America|Arkansas|Nord America"
  "stati-uniti-california|Stati Uniti - California|-124.38|32.53|-114.12|42.01|California|us|Stati Uniti d'America|California|Nord America"
  "stati-uniti-carolina-del-nord|Stati Uniti - Carolina del Nord|-84.33|33.87|-75.45|36.62|North_Carolina|us|Stati Uniti d'America|Carolina del Nord|Nord America"
  "stati-uniti-carolina-del-sud|Stati Uniti - Carolina del Sud|-83.36|32.02|-78.56|35.22|South_Carolina|us|Stati Uniti d'America|Carolina del Sud|Nord America"
  "stati-uniti-colorado|Stati Uniti - Colorado|-109.05|37.00|-102.01|41.01|Colorado|us|Stati Uniti d'America|Colorado|Nord America"
  "stati-uniti-connecticut|Stati Uniti - Connecticut|-73.73|40.99|-71.79|42.06|Connecticut|us|Stati Uniti d'America|Connecticut|Nord America"
  "stati-uniti-dakota-del-nord|Stati Uniti - Dakota del Nord|-104.04|45.94|-96.55|49.00|North_Dakota|us|Stati Uniti d'America|Dakota del Nord|Nord America"
  "stati-uniti-dakota-del-sud|Stati Uniti - Dakota del Sud|-104.04|42.51|-96.45|45.95|South_Dakota|us|Stati Uniti d'America|Dakota del Sud|Nord America"
  "stati-uniti-delaware|Stati Uniti - Delaware|-75.79|38.45|-75.03|39.85|Delaware|us|Stati Uniti d'America|Delaware|Nord America"
  "stati-uniti-distretto-di-columbia|Stati Uniti - Distretto di Columbia|-77.13|38.80|-76.93|39.02|Washington,_D.C.|us|Stati Uniti d'America|Distretto di Columbia|Nord America"
  "stati-uniti-florida|Stati Uniti - Florida|-87.61|24.54|-80.04|31.01|Florida|us|Stati Uniti d'America|Florida|Nord America"
  "stati-uniti-georgia|Stati Uniti - Georgia|-85.63|30.37|-80.87|35.01|Georgia_(U.S._state)|us|Stati Uniti d'America|Georgia|Nord America"
  "stati-uniti-idaho|Stati Uniti - Idaho|-117.21|42.00|-111.05|49.00|Idaho|us|Stati Uniti d'America|Idaho|Nord America"
  "stati-uniti-illinois|Stati Uniti - Illinois|-91.51|36.99|-87.03|42.52|Illinois|us|Stati Uniti d'America|Illinois|Nord America"
  "stati-uniti-indiana|Stati Uniti - Indiana|-88.10|37.78|-84.78|41.77|Indiana|us|Stati Uniti d'America|Indiana|Nord America"
  "stati-uniti-iowa|Stati Uniti - Iowa|-96.63|40.37|-90.14|43.51|Iowa|us|Stati Uniti d'America|Iowa|Nord America"
  "stati-uniti-kansas|Stati Uniti - Kansas|-102.03|37.00|-94.61|40.01|Kansas|us|Stati Uniti d'America|Kansas|Nord America"
  "stati-uniti-kentucky|Stati Uniti - Kentucky|-89.57|36.49|-81.96|39.13|Kentucky|us|Stati Uniti d'America|Kentucky|Nord America"
  "stati-uniti-louisiana|Stati Uniti - Louisiana|-94.05|28.98|-88.81|33.02|Louisiana|us|Stati Uniti d'America|Louisiana|Nord America"
  "stati-uniti-maine|Stati Uniti - Maine|-71.09|43.07|-66.98|47.47|Maine|us|Stati Uniti d'America|Maine|Nord America"
  "stati-uniti-maryland|Stati Uniti - Maryland|-79.49|37.95|-75.03|39.73|Maryland|us|Stati Uniti d'America|Maryland|Nord America"
  "stati-uniti-massachusetts|Stati Uniti - Massachusetts|-73.51|41.24|-69.93|42.89|Massachusetts|us|Stati Uniti d'America|Massachusetts|Nord America"
  "stati-uniti-michigan|Stati Uniti - Michigan|-90.42|41.70|-82.13|48.31|Michigan|us|Stati Uniti d'America|Michigan|Nord America"
  "stati-uniti-minnesota|Stati Uniti - Minnesota|-97.23|43.50|-89.49|49.37|Minnesota|us|Stati Uniti d'America|Minnesota|Nord America"
  "stati-uniti-mississippi|Stati Uniti - Mississippi|-91.66|30.19|-88.08|35.01|Mississippi|us|Stati Uniti d'America|Mississippi|Nord America"
  "stati-uniti-missouri|Stati Uniti - Missouri|-95.77|35.99|-89.12|40.63|Missouri|us|Stati Uniti d'America|Missouri|Nord America"
  "stati-uniti-montana|Stati Uniti - Montana|-116.05|44.39|-104.00|49.00|Montana|us|Stati Uniti d'America|Montana|Nord America"
  "stati-uniti-nebraska|Stati Uniti - Nebraska|-104.03|40.00|-95.34|43.01|Nebraska|us|Stati Uniti d'America|Nebraska|Nord America"
  "stati-uniti-nevada|Stati Uniti - Nevada|-120.01|34.99|-114.04|42.01|Nevada|us|Stati Uniti d'America|Nevada|Nord America"
  "stati-uniti-new-hampshire|Stati Uniti - New Hampshire|-72.56|42.70|-70.73|45.30|New_Hampshire|us|Stati Uniti d'America|New Hampshire|Nord America"
  "stati-uniti-new-jersey|Stati Uniti - New Jersey|-75.53|38.94|-73.91|41.36|New_Jersey|us|Stati Uniti d'America|New Jersey|Nord America"
  "stati-uniti-new-york|Stati Uniti - New York|-79.77|40.51|-71.90|45.01|New_York_(state)|us|Stati Uniti d'America|New York|Nord America"
  "stati-uniti-nuovo-messico|Stati Uniti - Nuovo Messico|-109.05|31.32|-103.00|37.01|New_Mexico|us|Stati Uniti d'America|Nuovo Messico|Nord America"
  "stati-uniti-ohio|Stati Uniti - Ohio|-84.83|38.41|-80.51|42.33|Ohio|us|Stati Uniti d'America|Ohio|Nord America"
  "stati-uniti-oklahoma|Stati Uniti - Oklahoma|-103.01|33.64|-94.43|37.01|Oklahoma|us|Stati Uniti d'America|Oklahoma|Nord America"
  "stati-uniti-oregon|Stati Uniti - Oregon|-124.54|42.00|-116.47|46.23|Oregon|us|Stati Uniti d'America|Oregon|Nord America"
  "stati-uniti-pennsylvania|Stati Uniti - Pennsylvania|-80.53|39.72|-74.69|42.54|Pennsylvania|us|Stati Uniti d'America|Pennsylvania|Nord America"
  "stati-uniti-rhode-island|Stati Uniti - Rhode Island|-71.85|41.33|-71.23|42.02|Rhode_Island|us|Stati Uniti d'America|Rhode Island|Nord America"
  "stati-uniti-tennessee|Stati Uniti - Tennessee|-90.30|34.98|-81.65|36.70|Tennessee|us|Stati Uniti d'America|Tennessee|Nord America"
  "stati-uniti-texas|Stati Uniti - Texas|-106.67|25.87|-93.53|36.51|Texas|us|Stati Uniti d'America|Texas|Nord America"
  "stati-uniti-utah|Stati Uniti - Utah|-114.05|37.00|-109.04|42.01|Utah|us|Stati Uniti d'America|Utah|Nord America"
  "stati-uniti-vermont|Stati Uniti - Vermont|-73.43|42.73|-71.51|45.01|Vermont|us|Stati Uniti d'America|Vermont|Nord America"
  "stati-uniti-virginia|Stati Uniti - Virginia|-83.67|36.55|-75.22|39.46|Virginia|us|Stati Uniti d'America|Virginia|Nord America"
  "stati-uniti-virginia-occidentale|Stati Uniti - Virginia Occidentale|-82.62|37.20|-77.72|40.65|West_Virginia|us|Stati Uniti d'America|Virginia Occidentale|Nord America"
  "stati-uniti-washington|Stati Uniti - Washington|-124.71|45.59|-116.89|49.00|Washington_(state)|us|Stati Uniti d'America|Washington|Nord America"
  "stati-uniti-wisconsin|Stati Uniti - Wisconsin|-92.90|42.49|-86.26|47.31|Wisconsin|us|Stati Uniti d'America|Wisconsin|Nord America"
  "stati-uniti-wyoming|Stati Uniti - Wyoming|-111.06|41.00|-104.02|45.01|Wyoming|us|Stati Uniti d'America|Wyoming|Nord America"
  "stati-uniti-alaska|Stati Uniti - Alaska|-170.00|51.00|-129.90|71.60|Alaska|us|Stati Uniti d'America|Alaska|Nord America"
  "stati-uniti-hawaii|Stati Uniti - Hawaii|-160.30|18.90|-154.70|22.30|Hawaii|us|Stati Uniti d'America|Hawaii|Nord America"
  "canada-alberta|Canada - Alberta|-120.01|48.99|-109.99|60.01|Alberta|ca|Canada|Alberta|Nord America"
  "canada-columbia-britannica|Canada - Columbia Britannica|-139.06|48.32|-114.05|60.01|British_Columbia|ca|Canada|Columbia Britannica|Nord America"
  "canada-isola-del-principe-edoardo|Canada - Isola del Principe Edoardo|-64.41|45.96|-62.02|47.07|Prince_Edward_Island|ca|Canada|Isola del Principe Edoardo|Nord America"
  "canada-manitoba|Canada - Manitoba|-102.01|48.99|-88.94|60.01|Manitoba|ca|Canada|Manitoba|Nord America"
  "canada-nunavut|Canada - Nunavut|-120.69|51.94|-61.20|83.12|Nunavut|ca|Canada|Nunavut|Nord America"
  "canada-nuova-scozia|Canada - Nuova Scozia|-66.33|43.51|-59.72|47.01|Nova_Scotia|ca|Canada|Nuova Scozia|Nord America"
  "canada-nuovo-brunswick|Canada - Nuovo Brunswick|-69.06|44.62|-63.83|48.07|New_Brunswick|ca|Canada|Nuovo Brunswick|Nord America"
  "canada-ontario|Canada - Ontario|-95.17|41.67|-74.34|56.86|Ontario|ca|Canada|Ontario|Nord America"
  "canada-quebec|Canada - Québec|-79.72|45.00|-57.10|62.58|Quebec|ca|Canada|Québec|Nord America"
  "canada-saskatchewan|Canada - Saskatchewan|-110.01|48.99|-101.36|60.01|Saskatchewan|ca|Canada|Saskatchewan|Nord America"
  "canada-terranova-e-labrador|Canada - Terranova e Labrador|-67.77|46.62|-52.65|60.31|Newfoundland_and_Labrador|ca|Canada|Terranova e Labrador|Nord America"
  "canada-territori-del-nord-ovest|Canada - Territori del Nord-Ovest|-136.45|60.00|-101.98|78.76|Northwest_Territories|ca|Canada|Territori del Nord-Ovest|Nord America"
  "canada-yukon|Canada - Yukon|-141.01|60.00|-123.81|69.66|Yukon|ca|Canada|Yukon|Nord America"
  "messico|Messico|-117.13|14.54|-86.81|32.72|Mexico|mx|||Nord America"
  "guatemala|Guatemala|-92.23|13.74|-88.23|17.82|Guatemala|gt|||Nord America"
  "belize|Belize|-89.23|15.89|-88.11|18.50|Belize|bz|||Nord America"
  "honduras|Honduras|-89.35|12.98|-83.15|16.01|Honduras|hn|||Nord America"
  "el-salvador|El Salvador|-90.10|13.15|-87.72|14.42|El_Salvador|sv|||Nord America"
  "nicaragua|Nicaragua|-87.67|10.73|-83.15|15.02|Nicaragua|ni|||Nord America"
  "costa-rica|Costa Rica|-85.94|8.23|-82.55|11.22|Costa_Rica|cr|||Nord America"
  "panama|Panama|-82.97|7.22|-77.24|9.61|Panama|pa|||Nord America"
  "cuba|Cuba|-84.97|19.86|-74.18|23.19|Cuba|cu|||Nord America"
  "giamaica|Giamaica|-78.34|17.70|-76.20|18.52|Jamaica|jm|||Nord America"
  "haiti|Haiti|-74.46|18.03|-71.62|19.92|Haiti|ht|||Nord America"
  "repubblica-dominicana|Repubblica Dominicana|-71.95|17.60|-68.32|19.88|Dominican_Republic|do|||Nord America"
  "bahamas|Bahamas|-79.02|20.91|-72.73|27.25|The_Bahamas|bs|||Nord America"
  "trinidad-tobago|Trinidad e Tobago|-61.95|10.00|-60.90|10.89|Trinidad_and_Tobago|tt|||Nord America"
  "barbados|Barbados|-59.68|13.02|-59.40|13.34|Barbados|bb|||Nord America"
  "antigua-barbuda|Antigua e Barbuda|-61.90|16.95|-61.68|17.73|Antigua_and_Barbuda|ag|||Nord America"
  "dominica|Dominica|-61.50|15.20|-61.24|15.65|Dominica|dm|||Nord America"
  "grenada|Grenada|-61.80|11.98|-61.58|12.55|Grenada|gd|||Nord America"
  "saint-kitts-nevis|Saint Kitts e Nevis|-62.87|17.08|-62.53|17.42|Saint_Kitts_and_Nevis|kn|||Nord America"
  "saint-lucia|Saint Lucia|-61.08|13.70|-60.87|14.12|Saint_Lucia|lc|||Nord America"
  "saint-vincent-grenadine|Saint Vincent e Grenadine|-61.60|12.38|-61.10|13.40|Saint_Vincent_and_the_Grenadines|vc|||Nord America"
  "groenlandia|Groenlandia (Danimarca)|-73.00|59.00|-12.00|83.65|Greenland|gl|||Nord America"
  "guadalupa|Guadalupa (Francia)|-61.81|15.83|-61.00|16.51|Guadeloupe|gp|||Nord America"
  "martinica|Martinica (Francia)|-61.23|14.39|-60.81|14.88|Martinique|mq|||Nord America"
  "saint-pierre-miquelon|Saint-Pierre e Miquelon (Francia)|-56.42|46.75|-56.13|47.15|Saint_Pierre_and_Miquelon|pm|||Nord America"
  "saint-martin|Saint-Martin (Francia)|-63.16|18.02|-62.97|18.14|Saint_Martin|mf|||Nord America"
  "saint-barthelemy|Saint-Barthelemy (Francia)|-62.90|17.87|-62.78|17.96|Saint_Barthelemy|bl|||Nord America"
  "bermuda|Bermuda (Regno Unito)|-64.90|32.25|-64.65|32.40|Bermuda|bm|||Nord America"
  "isole-cayman|Isole Cayman (Regno Unito)|-81.42|19.25|-79.72|19.76|Cayman_Islands|ky|||Nord America"
  "isole-vergini-britanniche|Isole Vergini Britanniche (Regno Unito)|-64.75|18.27|-64.27|18.76|British_Virgin_Islands|vg|||Nord America"
  "turks-caicos|Turks e Caicos (Regno Unito)|-72.10|21.20|-71.05|21.95|Turks_and_Caicos_Islands|tc|||Nord America"
  "montserrat|Montserrat (Regno Unito)|-62.24|16.67|-62.14|16.82|Montserrat|ms|||Nord America"
  "anguilla|Anguilla (Regno Unito)|-63.17|18.13|-62.97|18.29|Anguilla|ai|||Nord America"
  "aruba|Aruba (Paesi Bassi)|-70.06|12.41|-69.86|12.63|Aruba|aw|||Nord America"
  "curacao|Curacao (Paesi Bassi)|-69.16|12.02|-68.74|12.39|Curacao|cw|||Nord America"
  "sint-maarten|Sint Maarten (Paesi Bassi)|-63.13|18.02|-63.00|18.09|Sint_Maarten|sx|||Nord America"
  "bonaire|Bonaire (Paesi Bassi)|-68.42|12.00|-68.19|12.30|Bonaire|bq|Bonaire, Sint Eustatius e Saba|Bonaire|Nord America"
  "sint-eustatius-saba|Sint Eustatius e Saba (Paesi Bassi)|-63.05|17.45|-62.95|17.65|Sint_Eustatius|bq|Bonaire, Sint Eustatius e Saba|Sint Eustatius e Saba|Nord America"
  "portorico|Portorico (Stati Uniti)|-67.95|17.88|-65.22|18.52|Puerto_Rico|pr|||Nord America"
  "isole-vergini-americane|Isole Vergini Americane (Stati Uniti)|-65.10|17.62|-64.56|18.42|United_States_Virgin_Islands|vi|||Nord America"
  "argentina|Argentina|-73.42|-55.25|-53.63|-21.83|Argentina|ar|||Sud America"
  "bolivia|Bolivia|-69.59|-22.87|-57.50|-9.76|Bolivia|bo|||Sud America"
  "brasile|Brasile|-73.99|-33.77|-34.73|5.24|Brazil|br|||Sud America"
  "cile|Cile|-75.64|-55.61|-66.96|-17.58|Chile|cl|||Sud America"
  "colombia|Colombia - Continentale|-78.99|-4.30|-66.88|12.44|Colombia|co|Colombia|Continentale|Sud America"
  "colombia-san-andres|Colombia - San Andres e Providencia|-81.80|12.15|-81.35|13.53|San_Andres_and_Providencia|co|Colombia|San Andres e Providencia|Sud America"
  "ecuador|Ecuador|-80.97|-4.96|-75.23|1.38|Ecuador|ec|||Sud America"
  "guyana|Guyana|-61.41|1.27|-56.54|8.37|Guyana|gy|||Sud America"
  "paraguay|Paraguay|-62.69|-27.55|-54.29|-19.34|Paraguay|py|||Sud America"
  "peru|Peru|-81.41|-18.35|-68.67|-0.06|Peru|pe|||Sud America"
  "suriname|Suriname|-58.04|1.82|-53.96|6.03|Suriname|sr|||Sud America"
  "uruguay|Uruguay|-58.43|-34.95|-53.21|-30.11|Uruguay|uy|||Sud America"
  "venezuela|Venezuela|-73.30|0.72|-59.76|12.16|Venezuela|ve|||Sud America"
  "guyana-francese|Guyana Francese (Francia)|-54.60|2.11|-51.62|5.78|French_Guiana|gf|||Sud America"
  "isole-falkland|Isole Falkland (Regno Unito)|-61.35|-52.35|-57.70|-51.00|Falkland_Islands|fk|||Sud America"
  "australia|Australia|113.34|-43.63|153.57|-10.67|Australia|au|||Oceania"
  "nuova-zelanda|Nuova Zelanda|166.51|-46.64|178.52|-34.45|New_Zealand|nz|||Oceania"
  # Figi e Kiribati attraversano davvero l'antimeridiano (non e' un artefatto della fonte dati come
  # per la Russia iniziale): sono spezzate in due region con lo stesso flag, stesso schema usato
  # sopra per Russia/Stati Uniti.
  "figi-occidentali|Figi - Isole occidentali|177.00|-20.70|180.00|-15.50|Fiji|fj|Figi|Isole occidentali (Viti Levu, Vanua Levu)|Oceania"
  "figi-lau|Figi - Isole Lau|-179.90|-21.05|-178.00|-15.60|Fiji|fj|Figi|Isole Lau|Oceania"
  "papua-nuova-guinea|Papua Nuova Guinea|141.00|-10.65|156.02|-2.50|Papua_New_Guinea|pg|||Oceania"
  "isole-salomone|Isole Salomone|155.68|-11.85|166.93|-6.59|Solomon_Islands|sb|||Oceania"
  "vanuatu|Vanuatu|166.53|-20.25|169.90|-13.07|Vanuatu|vu|||Oceania"
  "kiribati-gilbert|Kiribati - Isole Gilbert|172.60|-3.40|176.90|1.95|Kiribati|ki|Kiribati|Isole Gilbert|Oceania"
  "kiribati-line|Kiribati - Isole della Linea|-162.30|-11.45|-157.00|6.42|Kiribati|ki|Kiribati|Isole della Linea|Oceania"
  "isole-marshall|Isole Marshall|160.75|4.57|172.20|14.75|Marshall_Islands|mh|||Oceania"
  "micronesia|Micronesia|138.00|1.00|163.10|10.10|Federated_States_of_Micronesia|fm|||Oceania"
  "nauru|Nauru|166.90|-0.57|166.96|-0.49|Nauru|nr|||Oceania"
  "palau|Palau|131.10|2.80|134.72|8.10|Palau|pw|||Oceania"
  "samoa|Samoa|-172.80|-14.10|-171.40|-13.43|Samoa|ws|||Oceania"
  "tonga|Tonga|-175.60|-22.40|-173.70|-15.55|Tonga|to|||Oceania"
  "tuvalu|Tuvalu|176.00|-10.80|179.90|-5.60|Tuvalu|tv|||Oceania"
  "polinesia-francese|Polinesia Francese (Francia)|-154.75|-27.65|-134.45|-7.90|French_Polynesia|pf|||Oceania"
  "nuova-caledonia|Nuova Caledonia (Francia)|163.55|-22.70|168.15|-19.50|New_Caledonia|nc|||Oceania"
  "wallis-futuna|Wallis e Futuna (Francia)|-178.20|-14.35|-176.10|-13.15|Wallis_and_Futuna|wf|||Oceania"
  "isole-pitcairn|Isole Pitcairn (Regno Unito)|-130.15|-25.10|-124.75|-23.85|Pitcairn_Islands|pn|||Oceania"
  "isole-cook|Isole Cook (Nuova Zelanda)|-166.10|-22.15|-157.20|-8.80|Cook_Islands|ck|||Oceania"
  "niue|Niue (Nuova Zelanda)|-170.00|-19.20|-169.75|-18.95|Niue|nu|||Oceania"
  "tokelau|Tokelau (Nuova Zelanda)|-172.60|-9.45|-171.05|-8.35|Tokelau|tk|||Oceania"
  "isola-norfolk|Isola Norfolk (Australia)|167.90|-29.10|168.00|-29.00|Norfolk_Island|nf|||Oceania"
  "isola-christmas|Isola di Christmas (Australia)|105.53|-10.57|105.72|-10.38|Christmas_Island|cx|||Oceania"
  "isole-cocos|Isole Cocos (Australia)|96.80|-12.22|96.95|-11.95|Cocos_Islands|cc|||Oceania"
  "guam|Guam (Stati Uniti)|144.62|13.23|144.95|13.65|Guam|gu|||Oceania"
  "samoa-americane|Samoa Americane (Stati Uniti)|-171.10|-14.37|-169.40|-11.02|American_Samoa|as|||Oceania"
  "isole-marianne-settentrionali|Isole Marianne Settentrionali (Stati Uniti)|144.90|14.10|146.10|20.60|Northern_Mariana_Islands|mp|||Oceania"
)

# Release GitHub che ospita gli asset (poi.db, .rd5) di una regione: una per continente,
# "region-data-<continente>", perche' ogni release e' limitata a 1000 asset. Durante una run i
# nuovi asset si aggiungono ai vecchi, che la pulizia post-deploy toglie solo alla fine: una
# release deve reggere il doppio dei suoi asset stabili. L'Asia (760 asset al 2026-09-24, oltre
# 1500 durante una rigenerazione completa) e' divisa in due: le 5 regioni piu' grandi (367 asset)
# stanno in "region-data-asia-grandi". Gli asset gia' pubblicati restano dove sono finche' la
# regione non li rigenera: il manifest ha URL assoluti, quindi una regione puo' averli su tutte e due.
# I paesi divisi in regioni piu' piccole hanno una release propria, perche' le tile .rd5 di confine si
# ripetono tra regioni vicine (stima per eccesso: USA ~190, Canada ~250, Cina ~190, Russia europea
# ~110) e nella release del continente supererebbero il limite durante una rigenerazione. La
# Francia (13 regioni, ~26 tile) resta in quella dell'Europa.
region_release_tag() {
  local regionId="$1" continent="$2"
  case "$regionId" in
    cina|russia-siberia|russia-estremo-oriente|indonesia|india) echo "region-data-asia-grandi" ;;
    stati-uniti-*) echo "region-data-stati-uniti" ;;
    canada-*) echo "region-data-canada" ;;
    cina-*) echo "region-data-cina" ;;
    russia-caucaso-settentrionale|russia-centro|russia-nord-europeo|russia-nord-ovest|russia-terra-nera-centrale|russia-urali-europei|russia-volga|russia-volga-vjatka)
      echo "region-data-russia-europea" ;;
    *) echo "region-data-$(echo "$continent" | tr 'A-Z' 'a-z' | tr ' ' '-')" ;;
  esac
}

# Regioni senza nessun civico: zero indirizzi nelle z15 di Protomaps (run del 2026-09-24) e nessun
# dato in Overture (rilascio 2026-09-23.0). Il workflow non lancia build-addresses.sh per loro:
# niente estrazione inutile e niente riga nel riepilogo. Toglierle da qui se compare una fonte.
# canada-nunavut (scelta dell'utente, 2026-09-25): 1.569 civici in OSM per un'estrazione z15 da
# 4.403 MB, sopra ADDRESSES_MAX_EXTRACT_MB; escluso finche' non c'e' un modo piu' leggero di averli.
region_has_addresses() {
  case "$1" in
    figi-lau|guinea-equatoriale|kiribati-line|nauru|tokelau|wallis-futuna|canada-nunavut) return 1 ;;
    *) return 0 ;;
  esac
}

# Regioni divise in regioni piu' piccole: regionId della vecchia regione | groupName delle nuove.
# Tolta da PILOT_REGIONS, la vecchia regione sparisce dal manifest; mergeManifests scrive questo
# elenco nel campo "replacedRegions" del manifest, cosi' l'app propone le regioni del gruppo a chi
# ha ancora installata quella vecchia.
REPLACED_REGIONS=(
  "stati-uniti|Stati Uniti d'America"
  "canada|Canada"
  "russia-europea|Russia"
  "cina|Cina"
  "francia|Francia"
)
