package com.pockettravel.app.onboarding

/** I tre passi del primo avvio, nell'ordine in cui compaiono. */
internal enum class OnboardingStep { WELCOME, DESTINATIONS, READY }

/**
 * Se dal passo si puo' andare avanti (anche con il gesto). Lingua e nazionalita' hanno sempre un valore proposto dal
 * telefono ([hasNationality] manca solo se il telefono non ha un paese); i modi di spostarsi non hanno un valore
 * predefinito e vanno scelti ([hasUsageModes]). Le nazioni da scaricare sono facoltative.
 */
internal fun OnboardingStep.canAdvance(hasUsageModes: Boolean, hasNationality: Boolean): Boolean = when (this) {
    OnboardingStep.WELCOME -> hasUsageModes && hasNationality
    OnboardingStep.DESTINATIONS, OnboardingStep.READY -> true
}
