package com.pockettravel.core.sync

internal const val HTTP_PARTIAL_CONTENT = 206
internal const val HTTP_REQUEST_TIMEOUT = 408
internal const val HTTP_RANGE_NOT_SATISFIABLE = 416
internal const val HTTP_TOO_MANY_REQUESTS = 429
internal const val HTTP_SERVER_ERROR = 500
internal val HTTP_CLIENT_ERRORS = 400..499

/** Errore del server, tempo scaduto o troppe richieste: un problema temporaneo, per cui si riprova. */
internal fun isRetryableHttpStatus(code: Int): Boolean =
    code >= HTTP_SERVER_ERROR || code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS
