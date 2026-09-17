#!/usr/bin/env bash
# Generato da generate-weekly-schedule.sh il 2026-09-17 — NON MODIFICARE A MANO.
# Rilancia generate-weekly-schedule.sh per rigenerarlo (es. dopo una modifica a
# pilot-regions.sh). Ogni WEEKLY_SCHEDULE_DAY_N e' l'elenco regionId, separati da virgola,
# da processare nel giorno N (1=lunedi ... 7=domenica) — vedi
# .claude/docs/weekly-manifest-update-plan.md per l'algoritmo di assegnazione.
#
# Carico misurato (tile land) per giorno al momento della generazione:
#   giorno 1: 308 tile land
#   giorno 2: 309 tile land
#   giorno 3: 308 tile land
#   giorno 4: 309 tile land
#   giorno 5: 317 tile land
#   giorno 6: 331 tile land
#   giorno 7: 308 tile land

WEEKLY_SCHEDULE_DAY_1="thailandia,burkina-faso,congo,eswatini,etiopia,mauritania,seychelles,tunisia,zambia,canada,nicaragua,barbados,saint-kitts-nevis,groenlandia,saint-pierre-miquelon,isole-vergini-britanniche,curacao,isole-vergini-americane,guyana,peru,figi-occidentali,figi-lau,isole-salomone,micronesia,samoa-americane,chagos,clipperton"
WEEKLY_SCHEDULE_DAY_2="cina,mongolia,singapore,sri-lanka,tagikistan,vietnam,benin,burundi,camerun,guinea-equatoriale,eritrea,gambia,guinea-bissau,kenya,mauritius,namibia,sudafrica,stati-uniti-hawaii,panama,trinidad-tobago,antigua-barbuda,saint-lucia,saint-martin,turks-caicos,sint-maarten,argentina,samoa,tonga,niue,tokelau,isola-christmas,isole-marianne-settentrionali,antartide"
WEEKLY_SCHEDULE_DAY_3="italia,austria,germania,grecia,regno-unito,russia-siberia,bahrein,bangladesh,hong-kong,macao,indonesia,filippine,timor-est,emirati-arabi-uniti,algeria,guinea,libia,sierra-leone,sud-sudan,guatemala,cuba,montserrat,bonaire,bolivia,suriname,isole-falkland,nuova-zelanda,kiribati-gilbert,nauru,palau,tuvalu,nuova-caledonia,isole-cocos,isola-bouvet,heard-mcdonald,georgia-sandwich-sud,isole-minori-usa"
WEEKLY_SCHEDULE_DAY_4="francia,spagna,russia-estremo-oriente,bhutan,brunei,iraq,israele,giordania,laos,libano,malesia,nepal,corea-del-nord,oman,corea-del-sud,siria,turchia,uzbekistan,capo-verde,repubblica-centrafricana,gabon,costa-avorio,mali,nigeria,togo,uganda,riunione,messico,belize,el-salvador,costa-rica,dominica,saint-vincent-grenadine,saint-barthelemy,anguilla,sint-eustatius-saba,cile,papua-nuova-guinea,wallis-futuna,isole-pitcairn,isola-norfolk,guam,terre-australi-francesi"
WEEKLY_SCHEDULE_DAY_5="andorra,bielorussia,estonia,finlandia,montenegro,polonia,romania,slovacchia,svezia,ungheria,gibilterra,russia-kaliningrad,giappone,kazakistan,taiwan,turkmenistan,angola,comore,gibuti,egitto,lesotho,madagascar,niger,sudan,giamaica,repubblica-dominicana,grenada,guadalupa,bermuda,aruba,brasile,colombia,uruguay,venezuela,kiribati-line,polinesia-francese"
WEEKLY_SCHEDULE_DAY_6="belgio,cipro,croazia,irlanda,kosovo,lettonia,liechtenstein,lituania,moldavia,paesi-bassi,portogallo,repubblica-ceca,serbia,slovenia,svizzera,ucraina,afghanistan,cambogia,india,pakistan,yemen,botswana,congo-rd,mozambico,somalia,zimbabwe,mayotte,stati-uniti,honduras,haiti,bahamas,martinica,isole-cayman,portorico,colombia-san-andres,ecuador,paraguay,guyana-francese,australia"
WEEKLY_SCHEDULE_DAY_7="san-marino,albania,bosnia-erzegovina,bulgaria,citta-del-vaticano,danimarca,islanda,lussemburgo,macedonia-del-nord,malta,monaco,norvegia,isole-faroe,svalbard-jan-mayen,russia-europea,armenia,azerbaigian,georgia,iran,kuwait,kirghizistan,maldive,myanmar,palestina,qatar,arabia-saudita,ciad,ghana,liberia,malawi,marocco,ruanda,sao-tome-principe,senegal,tanzania,stati-uniti-alaska,vanuatu,isole-marshall,isole-cook"
