package me.rerere.rikkahub.data.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.File

object DatabaseSanitizer {
    private const val TAG = "DatabaseSanitizer"

    internal val restorableTables = listOf(
        "ConversationEntity",
        "conversation_branch_counters",
        "MemoryEntity",
        "GenMediaEntity",
        "ChatEpisodeEntity",
        "embedding_cache",
        "tool_result_archive",
        "tool_result_archive_chunk",
        "AIRequestLogEntity",
        "BackupLogEntity",
        "scheduled_tasks",
        "scheduled_task_runs",
        "daily_activity",
        "lorebook_entry_revision",
        "usage_stats",
        "model_quota_usage",
        "workspaces",
        "workspace_saf_details",
        "workspace_sandbox_details",
        "workspace_sandbox_mounts",
        "memory_summary_versions",
        "memory_summary_state",
        "memory_summary_changes",
        "memory_consolidation_records",
    )

    data class SanitizationResult(
        val totalRows: Int = 0,
        val skippedRows: Int = 0,
        val skippedBytes: Long = 0,
        val issuesFixed: Int = 0,
        val details: String = ""
    ) {
        operator fun plus(other: SanitizationResult) = SanitizationResult(
            totalRows = this.totalRows + other.totalRows,
            skippedRows = this.skippedRows + other.skippedRows,
            skippedBytes = this.skippedBytes + other.skippedBytes,
            issuesFixed = this.issuesFixed + other.issuesFixed,
            details = (this.details + "\n" + other.details).trim()
        )
    }

    fun isRikkaHubCompatDatabase(sourceDbFile: File): Boolean {
        return RikkaHubCompatDatabaseImporter.isCompatDatabase(sourceDbFile)
    }

    /**
     * Sanitizes the given source database file by copying valid data to a new database.
     * Returns the path to the sanitized database file.
     */
    fun sanitize(context: Context, sourceDbFile: File): Pair<File, SanitizationResult> {
        val targetDbName = "rikka_hub_sanitized"
        val targetDbFile = context.getDatabasePath(targetDbName)
        
        // Ensure clean state for target
        if (targetDbFile.exists()) {
            context.deleteDatabase(targetDbName)
        }

        // Initialize Target DB using Room to ensure schema creation
        val targetRoomDb = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            targetDbName
        )
            .allowMainThreadQueries() // Only for migration utility
            .build()
        
        // Force Open to create tables
        val targetDbInfo = targetRoomDb.openHelper.writableDatabase
        
        var totalResult = SanitizationResult()
        var sourceDb: SQLiteDatabase? = null

        try {
            val db = SQLiteDatabase.openDatabase(
                sourceDbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            sourceDb = db

            targetDbInfo.beginTransaction()
            try {
                val isRikkaHubCompat = RikkaHubCompatDatabaseImporter.matches(db)
                if (isRikkaHubCompat) {
                    val result = RikkaHubCompatDatabaseImporter.copyConversations(
                        source = db,
                        target = targetDbInfo,
                        currentFilesDir = context.filesDir.absolutePath,
                    )
                    totalResult += result
                    Log.i(TAG, "Converted RikkaHub conversations: $result")
                }

                val tables = restorableTables.filterNot { table ->
                    isRikkaHubCompat &&
                        (table == "ConversationEntity" || table == "workspaces")
                }

                for (table in tables) {
                    val exists = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(table),
                    ).use { cursor -> cursor.moveToFirst() }

                    if (exists) {
                        val result = copyTable(db, targetDbInfo, table)
                        totalResult += result
                        Log.i(TAG, "Sanitized table $table: $result")
                    } else {
                        Log.w(TAG, "Table $table not found in source database, skipping")
                    }
                }
                targetDbInfo.setTransactionSuccessful()
            } finally {
                targetDbInfo.endTransaction()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Critical error during sanitization", e)
            throw e
        } finally {
            sourceDb?.close()
            targetRoomDb.close()
        }

        return targetDbFile to totalResult
    }

    private fun copyTable(
        source: SQLiteDatabase,
        target: SupportSQLiteDatabase,
        tableName: String
    ): SanitizationResult {
        var rows = 0
        var copied = 0
        var skipped = 0
        var skippedBytes = 0L

        source.query(tableName, null, null, null, null, null, null).use { cursor ->
            // 旧版或外部数据库可能有当前表不认识的列，只拷贝两边共有的列。
            val targetColumns = target.query("PRAGMA table_info(`$tableName`)").use { tableInfo ->
                val nameIndex = tableInfo.getColumnIndex("name")
                buildSet {
                    while (nameIndex >= 0 && tableInfo.moveToNext()) {
                        add(tableInfo.getString(nameIndex))
                    }
                }
            }
            val columnNames = cursor.columnNames.filter { it in targetColumns }
            
            while (cursor.moveToNext()) {
                rows++
                val values = try {
                    val values = ContentValues()
                    var rowBytes = 0L
                    
                    for (colName in columnNames) {
                        val index = cursor.getColumnIndex(colName)
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                            Cursor.FIELD_TYPE_INTEGER -> values.put(colName, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> values.put(colName, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_STRING -> {
                                val str = cursor.getString(index)
                                values.put(colName, str)
                                rowBytes += str.length
                            }
                            Cursor.FIELD_TYPE_BLOB -> {
                                val blob = cursor.getBlob(index)
                                values.put(colName, blob)
                                rowBytes += blob.size
                            }
                        }
                    }
                    values
                } catch (e: Exception) {
                    Log.w(TAG, "Error copying row in $tableName", e)
                    skipped++
                    // We can't easily estimate bytes of a row specifically if we failed to read it, 
                    // but we can try to guess or just leave it.
                    // If the crash was in cursor.get...() then we missed it.
                    continue
                }

                check(
                    target.insert(
                        tableName,
                        android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                        values,
                    ) != -1L
                ) { "Failed to insert row into $tableName" }
                copied++
            }
            check(rows == 0 || copied > 0) {
                "All $rows source rows in $tableName were unreadable"
            }
        }

        return SanitizationResult(
            totalRows = rows, 
            skippedRows = skipped, 
            skippedBytes = skippedBytes,
            details = if (skipped > 0) "Skipped $skipped rows in $tableName" else ""
        )
    }
}
