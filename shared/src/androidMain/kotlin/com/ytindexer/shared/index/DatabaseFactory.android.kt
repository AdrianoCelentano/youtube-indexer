package com.ytindexer.shared.index

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.ytindexer.shared.db.YtIndexerDatabase

/**
 * Opens the local index database.
 *
 * Kept in `:shared` so app modules never need a SQLDelight dependency of their own.
 */
fun createDatabase(context: Context): YtIndexerDatabase = YtIndexerDatabase(createDriver(context))

private fun createDriver(context: Context): SqlDriver =
    AndroidSqliteDriver(
        schema = YtIndexerDatabase.Schema,
        context = context.applicationContext,
        name = DATABASE_NAME,
    )

private const val DATABASE_NAME = "yt_indexer.db"
