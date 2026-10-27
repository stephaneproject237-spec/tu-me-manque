package com.tumemanque.app

/** Adresse et clé publique (« anon / publishable ») de ton projet Supabase. Ces deux valeurs ne sont pas secrètes. */
object Config {
    const val SUPABASE_URL = "https://ahwxssgcwqzkxitpfvxy.supabase.co"
    /** Version web pour iPhone. */
    val WEB_URL: String = BuildConfig.WEB_URL
    /** Passe à true quand « Se connecter avec Apple » est configuré (compte développeur Apple). */
    val APPLE_LOGIN: Boolean = !BuildConfig.CASQUE
    const val SUPABASE_ANON_KEY = "sb_publishable_Q8zNDzwWjXlnlazogk3k9A_YbmxI_qM"
}
