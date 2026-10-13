package com.pockettravel.core.data

import javax.inject.Qualifier

/**
 * Dispatcher per il lavoro di CPU: [kotlinx.coroutines.Dispatchers.Default] nell'app, un dispatcher di test nei test
 * (`StandardTestDispatcher`), cosi' `runTest` controlla anche questo lavoro.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
