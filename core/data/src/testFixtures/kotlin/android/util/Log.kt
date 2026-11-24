package android.util

// OkHttp, creando il suo primo client, chiede a Log.isLoggable i livelli dei suoi logger: su JVM, senza Robolectric,
// il Log dell'android.jar risponde "not mocked" e OkHttp stampa 83 righe su stderr. Sta nei testFixtures perché
// serve ai test JVM di ogni modulo che li usa (core:data, core:sync, feature:ai); sul dispositivo vince il Log di sistema.
// Qui c'è solo quel metodo: qualunque altra chiamata a Log dal codice sotto test continua a fallire (NoSuchMethodError).
object Log {
    const val DEBUG = 3
    const val INFO = 4

    @JvmStatic
    @Suppress("FunctionOnlyReturningConstant", "UnusedParameter") // firma imposta da android.util.Log
    fun isLoggable(tag: String?, level: Int): Boolean = false
}
