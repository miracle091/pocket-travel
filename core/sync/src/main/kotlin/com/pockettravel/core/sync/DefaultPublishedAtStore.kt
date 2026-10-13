package com.pockettravel.core.sync

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPublishedAtStore @Inject constructor(preferences: PreferencesFactory) : PublishedAtStore {
    private val prefs = preferences.open("published_at")

    override fun last(kind: String): Long? = if (prefs.contains(kind)) prefs.getLong(kind, 0L) else null

    override fun save(kind: String, publishedAt: Long) = prefs.edit { putLong(kind, publishedAt) }

    // commit: subito dopo il cambio di catalogo l'app si riavvia, e una scrittura asincrona andrebbe persa.
    override fun forgetAllExcept(kept: String) = prefs.edit(commit = true) {
        prefs.all.keys.filter { it != kept }.forEach { remove(it) }
    }
}
