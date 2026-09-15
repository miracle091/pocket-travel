Bandiere vettoriali (SVG) da [flag-icons](https://github.com/lipis/flag-icons) di
Panayiotis Lipiridis, licenza MIT (vedi LICENSE in questa cartella) - copertura completa
ISO 3166-1 alpha-2 (variante 4x3), vendorizzate qui per uso offline/senza dipendenze esterne
nella pagina indice di assemble-site.sh. Per aggiornarle a una versione piu' recente:

  curl -sS -o /tmp/flag-icons.tgz https://registry.npmjs.org/flag-icons/-/flag-icons-<versione>.tgz
  tar -xzf /tmp/flag-icons.tgz -C /tmp
  cp /tmp/package/flags/4x3/??.svg .   # solo i codici ISO a 2 lettere, non le bandiere regionali/sovranazionali
  cp /tmp/package/LICENSE .
