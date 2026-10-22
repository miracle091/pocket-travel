Feed GTFS-RT TripUpdates registrati il 2026-10-22 e ridotti alle prime entità più quelle con corse soppresse e
fermate saltate o senza dati, per i test del lettore (`GtfsRealtimeDecoderTest`):

- `renfe-cercanias.pb`: Renfe Operadora, dataset "Horarios de viaje" di data.renfe.com, CC BY 4.0; ridotto.
- `sncf.pb`: SNCF Voyageurs, risorsa GTFS-RT del dataset "Horaires SNCF" di transport.data.gouv.fr, ODbL 1.0; ridotto.
- `autolinee-toscane.pb`: Regione Toscana, "Trasporto pubblico in tempo reale" di dati.toscana.it, CC BY; ridotto.

I tre file restano sotto la licenza della loro fonte, non la licenza MIT del repository. `sncf.pb` è una parte
del database SNCF modificata (ridotta) e si ridistribuisce sotto la stessa licenza, Open Database License 1.0
(https://opendatacommons.org/licenses/odbl/1-0/), con l'attribuzione sopra; gli altri due sotto CC BY 4.0
(https://creativecommons.org/licenses/by/4.0/) e CC BY, con l'attribuzione sopra e l'indicazione della riduzione.
