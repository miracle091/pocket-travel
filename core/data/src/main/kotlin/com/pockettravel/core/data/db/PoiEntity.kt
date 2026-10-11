package com.pockettravel.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un POI come lo leggono le query di [PoiDao]: non e' una tabella, le colonne category, osmTag, regionId, lat e lon
 * vengono dalla join con poi_code e poi_region e dagli interi in microgradi di [PoiRow]. Si legge e basta: per
 * scrivere si usa [PoiRow].
 */
data class PoiEntity(
    val id: Long = 0,
    val regionId: String,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val osmTag: String,
    val phone: String? = null,
    // Tag OSM "wheelchair" (yes, limited, no...), null se non indicato.
    val wheelchair: String? = null,
    // Solo per i POI con poiHasDetails (core:poi), null se OSM non li indica.
    val openingHours: String? = null,
    val address: String? = null,
    // Solo per alloggi, ambasciate e consolati (poiHasContacts, core:poi).
    val website: String? = null,
    val email: String? = null,
    // Ambasciate e consolati: paese rappresentato (ISO 3166-1 alpha-2), vedi GeneratePoi.kt.
    val country: String? = null,
    // Nome in inglese e in italiano (OSM name:en, name:it), solo se diverso da name: vedi Poi.displayName.
    val nameEn: String? = null,
    val nameIt: String? = null,
    // Tag OSM "toilets:wheelchair" (yes, limited, no...): bagni accessibili di un POI che ha dei bagni.
    val toiletsWheelchair: String? = null,
    // Tag OSM "capacity:disabled": posti auto per disabili di un parcheggio, null se non indicati.
    val capacityDisabled: Int? = null,
    // true per i POI del pacchetto extra (poi-extra.db): si importano e si eliminano a parte.
    val extra: Boolean = false,
)

// Tabella dei POI in forma compatta (un milione di POI: 172 MB con regionId, category e osmTag in chiaro e lat/lon REAL su
// ogni riga, 94 MB cosi'). La regione e la coppia (category, osmTag) stanno una volta sola in poi_region e poi_code e la riga
// le richiama con un intero; le coordinate sono interi in microgradi (11 cm di risoluzione), come nel poi.db pubblicato.
// Indice su regionRef: ogni query e ogni eliminazione dei POI filtra per regione, e le ricerche che leggono tutta la regione
// (INDEXED BY in PoiDao) la scorrono in ordine di rowid, in sequenza sul disco. Quello su (regionRef, latE6) serve ai segnalini
// e ai piu' vicini, che oltre alla regione limitano la latitudine. Quello su (regionRef, code) contiene tutte le colonne che
// servono a categoryTagsInRegion e transportCounts e la prima di embassiesOf: senza, a ogni apertura della mappa e della
// guida di una nazione grande (un milione di POI) SQLite leggeva tutta la tabella.
@Entity(tableName = "poi", indices = [Index("regionRef"), Index("regionRef", "latE6"), Index("regionRef", "code")])
data class PoiRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val regionRef: Long,
    val code: Long,
    val latE6: Long,
    val lonE6: Long,
    // true per i POI del pacchetto extra (poi-extra.db): si importano e si eliminano a parte.
    @ColumnInfo(defaultValue = "0") val extra: Boolean = false,
    val name: String,
    val phone: String? = null,
    val wheelchair: String? = null,
    val openingHours: String? = null,
    val address: String? = null,
    val website: String? = null,
    val email: String? = null,
    val country: String? = null,
    val nameEn: String? = null,
    val nameIt: String? = null,
    val toiletsWheelchair: String? = null,
    val capacityDisabled: Int? = null,
)

/** Le regioni che hanno POI, per [PoiRow.regionRef]: il testo dell'id della regione una volta sola invece che su ogni riga. */
@Entity(tableName = "poi_region", indices = [Index("regionId", unique = true)])
data class PoiRegionEntity(
    @PrimaryKey(autoGenerate = true) val ref: Long = 0,
    val regionId: String,
)

/** Le coppie (category, osmTag) dei POI di tutte le regioni, per [PoiRow.code]: sono poche centinaia, le righe milioni. */
@Entity(tableName = "poi_code", indices = [Index("category", "osmTag", unique = true)])
data class PoiCodeEntity(
    @PrimaryKey(autoGenerate = true) val code: Long = 0,
    val category: String,
    val osmTag: String,
)

/**
 * Stima dei byte che il POI occupa in region.db, riga della tabella piu' voci dei tre indici, per mostrare in Spazio di
 * archiviazione lo spazio vero e non la dimensione del file scaricato (poi.db e' compresso, la tabella Room no).
 * SQLite non dice quanto pesa una regione: dbstat non c'e' su Android, e la crescita del file e' falsata dalle pagine libere
 * riusate. Si stima dai dati: lunghezza in UTF-8 delle stringhe piu' la parte fissa della riga e di ogni voce d'indice (chiave
 * piu' rowid e intestazioni), con un 5% per le pagine non piene dei B-tree. Tarata sull'Italia (956 mila POI, stringhe 24,6
 * byte a riga): tabella e indici creati prima dell'inserimento, come fa Room, 93,6 MB misurati, stima 93,0 MB. Da ritarare se
 * cambiano colonne o indici.
 */
fun PoiRow.estimatedStorageBytes(): Long {
    val strings = utf8Length(name) + utf8Length(phone) + utf8Length(wheelchair) + utf8Length(openingHours) + utf8Length(address) +
        utf8Length(website) + utf8Length(email) + utf8Length(country) + utf8Length(nameEn) + utf8Length(nameIt) +
        utf8Length(toiletsWheelchair)
    return (FIXED_ROW_BYTES + strings + INDEXES_BYTES) * BTREE_FILL_PERCENT / PERCENT
}

// Parte fissa della riga: id, regionRef, code, latE6, lonE6, extra, capacityDisabled e l'intestazione dei 18 campi.
private const val FIXED_ROW_BYTES = 32L
// Le tre voci d'indice (chiave piu' rowid e intestazione): regionRef 9, (regionRef, latE6) 16, (regionRef, code) 11.
private const val INDEXES_BYTES = 36L
private const val BTREE_FILL_PERCENT = 105L
private const val PERCENT = 100L

private fun utf8Length(text: String?): Long = text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
