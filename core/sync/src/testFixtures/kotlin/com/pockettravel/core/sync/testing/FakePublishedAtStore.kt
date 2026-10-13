package com.pockettravel.core.sync.testing

import com.pockettravel.core.sync.PublishedAtStore

class FakePublishedAtStore(private val values: MutableMap<String, Long> = mutableMapOf()) : PublishedAtStore {
    override fun last(kind: String): Long? = values[kind]
    override fun save(kind: String, publishedAt: Long) {
        values[kind] = publishedAt
    }
    override fun forgetAllExcept(kept: String) {
        values.keys.retainAll(setOf(kept))
    }
}
