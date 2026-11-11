package com.pockettravel.core.data

import java.time.Instant

/** Un avviso del tabellone; la lingua si sceglie nell'interfaccia con [TransitText.pick]. [start] e [end]: il periodo attivo adesso, null se aperto. */
data class BoardAlert(
    val header: TransitText?,
    val description: TransitText?,
    val url: TransitText?,
    val effect: Int?,
    val network: String?,
    val start: Instant?,
    val end: Instant?,
)
