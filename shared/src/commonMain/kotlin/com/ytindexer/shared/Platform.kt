package com.ytindexer.shared

/**
 * Describes the platform the shared module is currently running on.
 *
 * This exists mainly to prove the expect/actual wiring works end-to-end from both
 * app modules. Real expect/actual declarations (TokenStore, SQLDelight driver,
 * WorkManager scheduling) land in the Phase 1 and Phase 2 tickets.
 */
expect fun platformName(): String
