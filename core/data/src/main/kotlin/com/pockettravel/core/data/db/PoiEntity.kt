package com.pockettravel.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Indice su regionId: ogni query e ogni eliminazione dei POI filtra per regione, e le ricerche che leggono tutta la regione
// (INDEXED BY in PoiDao) la scorrono in ordine di rowid, in sequenza sul disco. Quello su (regionId, lat) serve ai segnalini
// e ai piu' vicini, che oltre alla regione limitano la latitudine. Quello su (regionId, category, osmTag) contiene tutte le
// colonne di categoryTagsInRegion e transportCounts e la prima di embassiesOf: senza, a ogni apertura della mappa e della
// guida di una nazione grande (un milione di POI) SQLite leggeva tutta la tabella.
@Entity(tableName = "poi", indices = [Index("regionId"), Index("regionId", "lat"), Index("regionId", "category", "osmTag")])
data class PoiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    @ColumnInfo(defaultValue = "0") val extra: Boolean = false,
)

/**
 * Stima dei byte che il POI occupa in region.db, righe della tabella piu' voci dei tre indici, per mostrare in Spazio di
 * archiviazione lo spazio vero e non la dimensione del file scaricato (poi.db e' compresso e compatto, la tabella Room no).
 * SQLite non dice quanto pesa una regione: dbstat non c'e' su Android, e la crescita del file e' falsata dalle pagine libere
 * riusate. Si stima dai dati: lunghezza in UTF-8 delle stringhe piu' la parte fissa della riga e di ogni voce d'indice (chiave
 * piu' rowid e intestazioni), con un 5% per le pagine non piene dei B-tree. Tarata sull'Italia (956 mila POI): tabella Room con
 * i 3 indici riempita in SQLite 172,2 MB, stima 172,1 MB. Da ritarare se cambiano colonne o indici.
 */
fun PoiEntity.estimatedStorageBytes(): Long {
    val table = FIXED_ROW_BYTES + utf8Length(name) + utf8Length(category) + utf8Length(osmTag) + utf8Length(regionId) +
        utf8Length(phone) + utf8Length(wheelchair) + utf8Length(openingHours) + utf8Length(address) + utf8Length(website) +
        utf8Length(email) + utf8Length(country) + utf8Length(nameEn) + utf8Length(nameIt) + utf8Length(toiletsWheelchair)
    val region = utf8Length(regionId)
    val indexes = (region + INDEX_REGION_BYTES) + (region + INDEX_REGION_LAT_BYTES) +
        (region + utf8Length(category) + utf8Length(osmTag) + INDEX_REGION_TAG_BYTES)
    return (table + indexes) * BTREE_FILL_PERCENT / PERCENT
}

// Parte fissa della riga: id, lat, lon, capacityDisabled, extra e l'intestazione dei 19 campi.
private const val FIXED_ROW_BYTES = 44L
// Parte fissa delle voci d'indice oltre alle stringhe: rowid, intestazione, e per il secondo la latitudine (8 byte).
private const val INDEX_REGION_BYTES = 9L
private const val INDEX_REGION_LAT_BYTES = 17L
private const val INDEX_REGION_TAG_BYTES = 13L
private const val BTREE_FILL_PERCENT = 105L
private const val PERCENT = 100L

private fun utf8Length(text: String?): Long = text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
