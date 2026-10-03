package com.pockettravel.core.data

import java.util.Locale

/** "en" se l'interfaccia e' in inglese, "it" altrimenti (le guide esistono solo in queste due lingue). */
fun currentGuidesLanguage(): String = if (Locale.getDefault().language == "en") "en" else "it"
