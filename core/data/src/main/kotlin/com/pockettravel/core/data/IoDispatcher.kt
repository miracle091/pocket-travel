package com.pockettravel.core.data

import javax.inject.Qualifier

/**
 * Dispatcher per disco, file e SQLite: [kotlinx.coroutines.Dispatchers.IO] nell'app, un dispatcher di test nei test
 * (`StandardTestDispatcher`), cosi' `runTest` controlla anche il lavoro in background.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
