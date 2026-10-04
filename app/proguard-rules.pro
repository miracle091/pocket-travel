# Regole R8 della release, oltre a proguard-android-optimize.txt e alle consumer rules delle librerie
# (MapLibre, OkHttp, Room, Hilt, WorkManager, kotlinx.serialization le portano gia').

# BRouter (third-party/brouter-core): RoutingContext crea il modello del percorso con Class.forName dal nome
# scritto nel profilo .brf, e il resto del motore usa nomi di classe e campi letti dai file di profilo.
-keep class btools.** { *; }
-dontwarn btools.**

# llama.cpp via JNI (feature:ai): i simboli nativi Java_..._InferenceEngineImpl_* richiedono nome di classe e
# metodi invariati. proguard-android-optimize.txt tiene gia' i metodi native; qui anche la classe intera,
# perche' il codice nativo e' costruito sul suo nome completo.
-keep class com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl { *; }

# Stack trace leggibili nelle segnalazioni: numeri di riga conservati, nome dei file sorgente nascosto.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
