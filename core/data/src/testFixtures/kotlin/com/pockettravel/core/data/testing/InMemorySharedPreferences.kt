package com.pockettravel.core.data.testing

import android.content.SharedPreferences
import com.pockettravel.core.data.PreferencesFactory

/**
 * SharedPreferences in memoria per i test JVM: stessi tipi, `commit`/`apply` immediati e listener chiamati a ogni
 * chiave cambiata, come quelle del sistema.
 */
class InMemorySharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (values[key] as Set<String>?)?.toSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            val changed = if (clear) values.keys.toMutableSet() else mutableSetOf()
            if (clear) values.clear()
            // Un valore null (remove o put di null) toglie la chiave, come nelle SharedPreferences del sistema.
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            changed += changes.keys
            changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) } }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

/** Un [InMemorySharedPreferences] per nome di file, condiviso fra le aperture successive come nel sistema. */
class InMemoryPreferencesFactory : PreferencesFactory {
    private val files = mutableMapOf<String, InMemorySharedPreferences>()
    override fun open(name: String): SharedPreferences = files.getOrPut(name) { InMemorySharedPreferences() }
}
