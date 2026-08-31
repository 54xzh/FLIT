package me.rerere.rikkahub.data.db

import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.AIRequestLogDao
import me.rerere.rikkahub.data.db.dao.BackupLogDao
import me.rerere.rikkahub.data.db.dao.DailyActivityDAO
import me.rerere.rikkahub.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.data.db.dao.LorebookEntryRevisionDao
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryConsolidationDao
import me.rerere.rikkahub.data.db.dao.MemorySummaryDao
import me.rerere.rikkahub.data.db.dao.ScheduledTaskDao
import me.rerere.rikkahub.data.db.dao.ScheduledTaskRunDao
import me.rerere.rikkahub.data.db.dao.ToolResultArchiveDao
import me.rerere.rikkahub.data.db.dao.ToolResultArchiveChunkDao
import me.rerere.rikkahub.data.db.dao.ModelQuotaUsageDAO
import me.rerere.rikkahub.data.db.dao.UsageStatsDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDao
import me.rerere.rikkahub.data.db.entity.AIRequestLogEntity
import me.rerere.rikkahub.data.db.entity.BackupLogEntity
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.data.db.entity.ConversationBranchCounterEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.DailyActivityEntity
import me.rerere.rikkahub.data.db.entity.EmbeddingCacheEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.LorebookEntryRevisionEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationClaimEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationRecordEntity
import me.rerere.rikkahub.data.db.entity.MemorySummaryChangeEntity
import me.rerere.rikkahub.data.db.entity.MemorySummaryStateEntity
import me.rerere.rikkahub.data.db.entity.MemorySummaryVersionEntity
import me.rerere.rikkahub.data.db.entity.ModelQuotaUsageEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity
import me.rerere.rikkahub.data.db.entity.ToolResultArchiveEntity
import me.rerere.rikkahub.data.db.entity.ToolResultArchiveChunkEntity
import me.rerere.rikkahub.data.db.entity.UsageStatsEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.SafWorkspaceEntity
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceEntity
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceMountEntity
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        ConversationBranchCounterEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        ChatEpisodeEntity::class,
        EmbeddingCacheEntity::class,
        ToolResultArchiveEntity::class,
        ToolResultArchiveChunkEntity::class,
        AIRequestLogEntity::class,
        BackupLogEntity::class,
        ScheduledTaskEntity::class,
        ScheduledTaskRunEntity::class,
        DailyActivityEntity::class,
        LorebookEntryRevisionEntity::class,
        UsageStatsEntity::class,
        ModelQuotaUsageEntity::class,
        WorkspaceEntity::class,
        SafWorkspaceEntity::class,
        SandboxWorkspaceEntity::class,
        SandboxWorkspaceMountEntity::class,
        MemorySummaryVersionEntity::class,
        MemorySummaryStateEntity::class,
        MemorySummaryChangeEntity::class,
        MemoryConsolidationRecordEntity::class,
        MemoryConsolidationClaimEntity::class,
    ],
    version = 49,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        // 11->12 was manual migration in companion object
        AutoMigration(from = 13, to = 14),
        // 14->16 is manual migration
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        AutoMigration(from = 32, to = 33),
        AutoMigration(from = 33, to = 34),
        // 34->35 is manual migration (MIGRATION_34_35) - adds usage_stats table
        // 35->36 is manual migration (MIGRATION_35_36) - adds visible conversation search text
        // 36->37 is manual migration (MIGRATION_36_37) - adds model_quota_usage table
        // 37->38 is manual migration (MIGRATION_37_38) - adds session memories
        // 38->39 is manual migration (MIGRATION_38_39) - adds explicit Skill context ids
        // 39->40 is manual migration (MIGRATION_39_40) - adds workspaces table
        // 40->41 is manual migration (MIGRATION_40_41) - splits workspace backends
        // 41->42 is manual migration (MIGRATION_41_42) - adds branch counter (root_id, branch_number)
        // 42->43 is manual migration (MIGRATION_42_43) - persists monotonic branch counters
        // 43->44 is manual migration (MIGRATION_43_44) - adds conversation workspace override
        // 44->45 is manual migration (MIGRATION_44_45) - adds sandbox folder mounts
        // 45->46 is manual migration (MIGRATION_45_46) - adds memory summaries
        // 46->47 is manual migration (MIGRATION_46_47) - adds active memory summary state
        // 47->48 is manual migration (MIGRATION_47_48) - adds durable consolidation history
        // 48->49 is manual migration (MIGRATION_48_49) - adds request log metadata
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun chatEpisodeDao(): ChatEpisodeDAO

    abstract fun embeddingCacheDao(): EmbeddingCacheDAO

    abstract fun toolResultArchiveDao(): ToolResultArchiveDao

    abstract fun toolResultArchiveChunkDao(): ToolResultArchiveChunkDao

    abstract fun aiRequestLogDao(): AIRequestLogDao

    abstract fun backupLogDao(): BackupLogDao

    abstract fun scheduledTaskDao(): ScheduledTaskDao

    abstract fun scheduledTaskRunDao(): ScheduledTaskRunDao

    abstract fun dailyActivityDao(): DailyActivityDAO

    abstract fun lorebookEntryRevisionDao(): LorebookEntryRevisionDao

    abstract fun usageStatsDao(): UsageStatsDAO

    abstract fun modelQuotaUsageDao(): ModelQuotaUsageDAO

    abstract fun workspaceDao(): WorkspaceDao

    abstract fun memorySummaryDao(): MemorySummaryDao

    abstract fun memoryConsolidationDao(): MemoryConsolidationDao

    companion object {
        const val TAG = "AppDatabase"
        
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 11 to 12")
                // Add columns to MemoryEntity
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN embedding TEXT")
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")

                // Create ChatEpisodeEntity table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ChatEpisodeEntity` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `assistant_id` TEXT NOT NULL, 
                        `content` TEXT NOT NULL, 
                        `embedding` TEXT, 
                        `start_time` INTEGER NOT NULL, 
                        `end_time` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 12 to 13")
                db.execSQL("ALTER TABLE ChatEpisodeEntity ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_14_16 = object : Migration(14, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 14 to 16")
                
                // 1. Handle ChatEpisodeEntity
                // Create new table with correct schema
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ChatEpisodeEntity_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `assistant_id` TEXT NOT NULL, 
                        `content` TEXT NOT NULL, 
                        `embedding` TEXT, 
                        `start_time` INTEGER NOT NULL, 
                        `end_time` INTEGER NOT NULL, 
                        `last_accessed_at` INTEGER NOT NULL DEFAULT 0, 
                        `significance` INTEGER NOT NULL DEFAULT 5, 
                        `conversation_id` TEXT DEFAULT ''
                    )
                    """.trimIndent()
                )

                // Check if conversation_id exists in old table
                val cursor = db.query("PRAGMA table_info(ChatEpisodeEntity)")
                var hasConversationId = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "conversation_id") {
                        hasConversationId = true
                        break
                    }
                }
                cursor.close()

                // Copy data
                if (hasConversationId) {
                    db.execSQL(
                        """
                        INSERT INTO ChatEpisodeEntity_new (id, assistant_id, content, embedding, start_time, end_time, last_accessed_at, significance, conversation_id)
                        SELECT id, assistant_id, content, embedding, start_time, end_time, last_accessed_at, significance, conversation_id FROM ChatEpisodeEntity
                        """.trimIndent()
                    )
                } else {
                    db.execSQL(
                        """
                        INSERT INTO ChatEpisodeEntity_new (id, assistant_id, content, embedding, start_time, end_time, last_accessed_at, significance)
                        SELECT id, assistant_id, content, embedding, start_time, end_time, last_accessed_at, significance FROM ChatEpisodeEntity
                        """.trimIndent()
                    )
                }

                // Drop old and rename new
                db.execSQL("DROP TABLE ChatEpisodeEntity")
                db.execSQL("ALTER TABLE ChatEpisodeEntity_new RENAME TO ChatEpisodeEntity")

                // 2. Handle MemoryEntity (Ensure columns exist)
                // We can't easily check column existence and add IF NOT EXISTS in SQLite in one go, 
                // but we can catch exceptions or check pragma.
                // Since we are migrating to 16, let's ensure the schema is correct.
                // The safest way for MemoryEntity if we suspect issues is to recreate it too, 
                // but for now let's assume it's mostly fine or just needs columns.
                // However, since we are doing a manual migration, let's be safe and check/add columns if needed.
                
                val memoryColumns = mutableListOf<String>()
                val memCursor = db.query("PRAGMA table_info(MemoryEntity)")
                while (memCursor.moveToNext()) {
                    memoryColumns.add(memCursor.getString(1))
                }
                memCursor.close()

                if (!memoryColumns.contains("type")) {
                    db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                }
                if (!memoryColumns.contains("last_accessed_at")) {
                    db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0")
                }
                if (!memoryColumns.contains("created_at")) {
                    db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 34 to 35")
                // Create usage_stats table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `usage_stats` (
                        `id` INTEGER NOT NULL PRIMARY KEY,
                        `total_conversations` INTEGER NOT NULL DEFAULT 0,
                        `total_messages` INTEGER NOT NULL DEFAULT 0,
                        `input_tokens` INTEGER NOT NULL DEFAULT 0,
                        `output_tokens` INTEGER NOT NULL DEFAULT 0,
                        `cached_tokens` INTEGER NOT NULL DEFAULT 0,
                        `app_launches` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // Seed initial row
                db.execSQL("INSERT OR IGNORE INTO usage_stats (id, total_conversations, total_messages, input_tokens, output_tokens, cached_tokens, app_launches) VALUES (1, 0, 0, 0, 0, 0, 0)")
                // Seed total_conversations from existing conversation count
                db.execSQL("UPDATE usage_stats SET total_conversations = (SELECT COUNT(*) FROM ConversationEntity) WHERE id = 1")
                // Seed total_messages from daily_activity if available
                db.execSQL("UPDATE usage_stats SET total_messages = COALESCE((SELECT SUM(message_count) FROM daily_activity), 0) WHERE id = 1")
                Log.i(TAG, "migrate: migrate from 34 to 35 success")
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 35 to 36")
                db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN search_text TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN search_text_version INTEGER NOT NULL DEFAULT 0")
                Log.i(TAG, "migrate: migrate from 35 to 36 success")
            }
        }

        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 36 to 37")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `model_quota_usage` (
                        `model_id` TEXT NOT NULL PRIMARY KEY,
                        `input_tokens` INTEGER NOT NULL DEFAULT 0,
                        `output_tokens` INTEGER NOT NULL DEFAULT 0,
                        `cached_tokens` INTEGER NOT NULL DEFAULT 0,
                        `last_reset_at` INTEGER NOT NULL DEFAULT 0,
                        `last_updated_at` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                Log.i(TAG, "migrate: migrate from 36 to 37 success")
            }
        }

        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 37 to 38")
                db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN session_memories TEXT NOT NULL DEFAULT '[]'")
                Log.i(TAG, "migrate: migrate from 37 to 38 success")
            }
        }

        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 38 to 39")
                db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN explicit_skill_context_ids TEXT NOT NULL DEFAULT '[]'")
                Log.i(TAG, "migrate: migrate from 38 to 39 success")
            }
        }

        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 39 to 40")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspaces` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `tree_uri` TEXT NOT NULL,
                        `shell_status` TEXT NOT NULL DEFAULT 'DISABLED',
                        `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `last_access_at` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_tree_uri` ON `workspaces` (`tree_uri`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)"
                )
                Log.i(TAG, "migrate: migrate from 39 to 40 success")
            }
        }

        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 40 to 41")
                db.execSQL("ALTER TABLE workspaces RENAME TO workspaces_legacy")
                // SQLite 的索引名全库唯一；重建 workspaces 前先释放旧表保留的索引名。
                db.execSQL("DROP INDEX IF EXISTS `index_workspaces_updated_at`")
                db.execSQL("DROP INDEX IF EXISTS `index_workspaces_tree_uri`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspaces` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `last_access_at` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_saf_details` (
                        `workspace_id` TEXT NOT NULL PRIMARY KEY,
                        `tree_uri` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_sandbox_details` (
                        `workspace_id` TEXT NOT NULL PRIMARY KEY,
                        `rootfs_status` TEXT NOT NULL,
                        `rootfs_source_url` TEXT,
                        `rootfs_version` TEXT,
                        `rootfs_installed_at` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_type` ON `workspaces` (`type`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_saf_details_tree_uri` ON `workspace_saf_details` (`tree_uri`)")
                db.execSQL(
                    """
                    INSERT INTO workspaces (id, name, type, tool_approvals, created_at, updated_at, last_access_at)
                    SELECT id, name, 'LIGHTWEIGHT', tool_approvals, created_at, updated_at, last_access_at
                    FROM workspaces_legacy
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO workspace_saf_details (workspace_id, tree_uri)
                    SELECT id, tree_uri FROM workspaces_legacy
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE workspaces_legacy")
                Log.i(TAG, "migrate: migrate from 40 to 41 success")
            }
        }

        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 41 to 42")
                // 分支计数：root_id 记录所属分支树的根会话 id；branch_number 为树内编号（根会话/老数据 = NULL）。
                // 老数据全部当作独立根会话（rootId = 自己、branchNumber = NULL），不解析旧标题里的「分支 ·」前缀。
                // branch_number 可空，不加 NOT NULL，避免与根会话语义冲突。
                db.execSQL("ALTER TABLE conversationentity ADD COLUMN root_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversationentity ADD COLUMN branch_number INTEGER")
                // 每行 root_id 回填为自己的 id —— 全部视为独立根会话。
                db.execSQL("UPDATE conversationentity SET root_id = id")
                // 分支计数唯一索引: 同一棵树内分支编号唯一; 根会话(branch_number NULL)互不冲突。
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_conversationentity_root_id_branch_number` ON conversationentity(`root_id`, `branch_number`)")
                Log.i(TAG, "migrate: migrate from 41 to 42 success")
            }
        }

        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 42 to 43")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_branch_counters` (
                        `root_id` TEXT NOT NULL PRIMARY KEY,
                        `next_branch_number` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // 旧树从当前最大编号的下一位继续。迁移完成后删除会话不再影响计数。
                db.execSQL(
                    """
                    INSERT INTO conversation_branch_counters (root_id, next_branch_number)
                    SELECT root_id, COALESCE(MAX(branch_number), 0) + 1
                    FROM ConversationEntity
                    GROUP BY root_id
                    """.trimIndent()
                )
                Log.i(TAG, "migrate: migrate from 42 to 43 success")
            }
        }

        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 43 to 44")
                // 与 entity 的 @ColumnInfo(defaultValue = "NULL") 及 schema 44.json 保持一致，
                // 否则 Room 在 v43->v44 升级后会因 schema 校验不一致而崩溃。
                db.execSQL("ALTER TABLE conversationentity ADD COLUMN workspace_override_id TEXT DEFAULT NULL")
                Log.i(TAG, "migrate: migrate from 43 to 44 success")
            }
        }

        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 44 to 45")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_sandbox_mounts` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `workspace_id` TEXT NOT NULL,
                        `tree_uri` TEXT NOT NULL,
                        `source_path` TEXT NOT NULL,
                        `target_path` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_sandbox_mounts_workspace_id` ON `workspace_sandbox_mounts` (`workspace_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_sandbox_mounts_workspace_id_target_path` ON `workspace_sandbox_mounts` (`workspace_id`, `target_path`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_sandbox_mounts_workspace_id_source_path` ON `workspace_sandbox_mounts` (`workspace_id`, `source_path`)")
                Log.i(TAG, "migrate: migrate from 44 to 45 success")
            }
        }

        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 45 to 46")
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN updated_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE ChatEpisodeEntity ADD COLUMN updated_at INTEGER DEFAULT NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_summary_versions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assistant_id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `generated_at` INTEGER NOT NULL,
                        `update_mode` INTEGER NOT NULL,
                        `source_change_count` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_summary_versions_assistant_id_generated_at` ON `memory_summary_versions` (`assistant_id`, `generated_at`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_summary_changes` (
                        `assistant_id` TEXT NOT NULL,
                        `memory_type` INTEGER NOT NULL,
                        `memory_id` INTEGER NOT NULL,
                        `change_type` INTEGER NOT NULL,
                        `changed_at` INTEGER NOT NULL,
                        `change_token` TEXT NOT NULL,
                        PRIMARY KEY(`assistant_id`, `memory_type`, `memory_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_summary_changes_assistant_id_changed_at` ON `memory_summary_changes` (`assistant_id`, `changed_at`)")
                Log.i(TAG, "migrate: migrate from 45 to 46 success")
            }
        }

        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 46 to 47")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_summary_state` (
                        `assistant_id` TEXT NOT NULL,
                        `active_version_id` INTEGER NOT NULL,
                        `requires_full_update` INTEGER NOT NULL,
                        `revision` INTEGER NOT NULL,
                        PRIMARY KEY(`assistant_id`),
                        FOREIGN KEY(`active_version_id`) REFERENCES `memory_summary_versions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_summary_state_active_version_id` ON `memory_summary_state` (`active_version_id`)")
                db.execSQL(
                    """
                    INSERT INTO memory_summary_state (assistant_id, active_version_id, requires_full_update, revision)
                    SELECT versions.assistant_id, versions.id, 0, 0
                    FROM memory_summary_versions AS versions
                    WHERE versions.id = (
                        SELECT candidate.id
                        FROM memory_summary_versions AS candidate
                        WHERE candidate.assistant_id = versions.assistant_id
                        ORDER BY candidate.generated_at DESC, candidate.id DESC
                        LIMIT 1
                    )
                    """.trimIndent(),
                )
                Log.i(TAG, "migrate: migrate from 46 to 47 success")
            }
        }

        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 47 to 48")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_consolidation_records` (
                        `conversation_id` TEXT NOT NULL,
                        `assistant_id` TEXT NOT NULL,
                        `completed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`conversation_id`, `assistant_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_consolidation_records_assistant_id` " +
                        "ON `memory_consolidation_records` (`assistant_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_consolidation_claims` (
                        `conversation_id` TEXT NOT NULL,
                        `claim_token` TEXT NOT NULL,
                        `claimed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`conversation_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_consolidation_claims_claimed_at` " +
                        "ON `memory_consolidation_claims` (`claimed_at`)",
                )
                // Existing episodes are proof that the corresponding target assistant has
                // already been processed. Expired episodes are handled lazily from the legacy
                // conversation flag when first encountered.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO memory_consolidation_records
                        (conversation_id, assistant_id, completed_at)
                    SELECT conversation_id, assistant_id,
                        CASE WHEN updated_at IS NOT NULL THEN updated_at ELSE end_time END
                    FROM ChatEpisodeEntity
                    WHERE conversation_id IS NOT NULL AND trim(conversation_id) <> ''
                    """.trimIndent(),
                )
                Log.i(TAG, "migrate: migrate from 47 to 48 success")
            }
        }

        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 48 to 49")
                db.execSQL("ALTER TABLE AIRequestLogEntity ADD COLUMN metadata_json TEXT")
                Log.i(TAG, "migrate: migrate from 48 to 49 success")
            }
        }
    }
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}

val Migration_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ... (existing migration code) ...
        Log.i(AppDatabase.TAG, "migrate: start migrate from 6 to 7")
        db.beginTransaction()
        try {
            // 创建新表结构（不包含messages列）
            db.execSQL(
                """
                CREATE TABLE ConversationEntity_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    assistant_id TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                    title TEXT NOT NULL,
                    nodes TEXT NOT NULL,
                    usage TEXT,
                    create_at INTEGER NOT NULL,
                    update_at INTEGER NOT NULL,
                    truncate_index INTEGER NOT NULL DEFAULT -1
                )
            """.trimIndent()
            )

            // 获取所有对话记录并转换数据
            val cursor =
                db.query("SELECT id, assistant_id, title, messages, usage, create_at, update_at, truncate_index FROM ConversationEntity")
            val updates = mutableListOf<Array<Any?>>()

            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val assistantId = cursor.getString(1)
                val title = cursor.getString(2)
                val messagesJson = cursor.getString(3)
                val usage = cursor.getString(4)
                val createAt = cursor.getLong(5)
                val updateAt = cursor.getLong(6)
                val truncateIndex = cursor.getInt(7)

                try {
                    // 尝试解析旧格式的消息列表 List<UIMessage>
                    val oldMessages = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)

                    // 转换为新格式 List<MessageNode>
                    val newMessages = oldMessages.map { message ->
                        MessageNode.of(message)
                    }

                    // 序列化新格式
                    val newMessagesJson = JsonInstant.encodeToString(newMessages)
                    updates.add(
                        arrayOf(
                            id,
                            assistantId,
                            title,
                            newMessagesJson,
                            usage,
                            createAt,
                            updateAt,
                            truncateIndex
                        )
                    )
                } catch (e: Exception) {
                    // 如果解析失败，可能已经是新格式或者数据损坏，跳过
                    error("Failed to migrate messages for conversation $id: ${e.message}")
                }
            }
            cursor.close()

            // 批量插入数据到新表
            updates.forEach { values ->
                db.execSQL(
                    "INSERT INTO ConversationEntity_new (id, assistant_id, title, nodes, usage, create_at, update_at, truncate_index) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    values
                )
            }

            // 删除旧表
            db.execSQL("DROP TABLE ConversationEntity")

            // 重命名新表
            db.execSQL("ALTER TABLE ConversationEntity_new RENAME TO ConversationEntity")

            db.setTransactionSuccessful()

            Log.i(AppDatabase.TAG, "migrate: migrate from 6 to 7 success (${updates.size} conversations updated)")
        } finally {
            db.endTransaction()
        }
    }
}

@DeleteColumn(tableName = "ConversationEntity", columnName = "usage")
class Migration_8_9 : AutoMigrationSpec
