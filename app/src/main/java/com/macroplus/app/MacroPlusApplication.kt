package com.macroplus.app

import android.app.Application
import com.macroplus.app.data.AppContainer

/**
 * Owns the single, process-lifetime [AppContainer] (and the [io.github.jan.supabase.SupabaseClient]
 * it lazily builds). Activities must read [appContainer] from here rather than constructing their
 * own, so the Supabase client (which internally opens an HTTP engine and a Realtime websocket) is
 * created exactly once and survives Activity recreation (e.g. configuration changes such as
 * rotation) instead of being leaked on every recreation.
 */
class MacroPlusApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer() }
}
