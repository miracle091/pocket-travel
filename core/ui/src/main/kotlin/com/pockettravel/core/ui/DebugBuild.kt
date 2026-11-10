package com.pockettravel.core.ui

import android.content.Context
import android.content.pm.ApplicationInfo

/** Vero nelle build di debug dell'app (debuggable): l'unico controllo per gli strumenti che le versioni stabili non mostrano. */
fun isDebugBuild(context: Context): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
