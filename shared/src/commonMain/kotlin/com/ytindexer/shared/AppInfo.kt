package com.ytindexer.shared

/**
 * Trivial shared logic used by the scaffolding smoke screens on both form factors.
 *
 * Its only job right now is to prove that `commonMain` code is compiled into, and
 * callable from, both `:androidApp` and `:androidTvApp`.
 */
object AppInfo {
    const val NAME: String = "YouTube Indexer"

    fun greeting(surface: String): String = "$NAME running on $surface (${platformName()})"
}
