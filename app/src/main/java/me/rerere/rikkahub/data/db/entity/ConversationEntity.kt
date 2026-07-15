package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [
        Index(value = ["assistant_id", "is_pinned", "update_at"]),
        // 分支计数安全网: 同一棵树内分支编号唯一。SQLite 把 NULL 视为不同值, 根会话(branch_number NULL)互不冲突。
        Index(value = ["root_id", "branch_number"], unique = true)
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo(name = "search_text", defaultValue = "''")
    val searchText: String = "",
    @ColumnInfo(name = "search_text_version", defaultValue = "0")
    val searchTextVersion: Int = 0,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("truncate_index", defaultValue = "-1")
    val truncateIndex: Int,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo(name = "is_consolidated", defaultValue = "0")
    val isConsolidated: Boolean = false,
    @ColumnInfo(name = "enabled_mode_ids", defaultValue = "[]")
    val enabledModeIds: String = "[]",
    @ColumnInfo(name = "explicit_skill_context_ids", defaultValue = "[]")
    val explicitSkillContextIds: String = "[]",
    @ColumnInfo(name = "root_id", defaultValue = "")
    val rootId: String = "",
    @ColumnInfo(name = "branch_number")
    val branchNumber: Int? = null,
    @ColumnInfo(name = "context_summary", defaultValue = "''")
    val contextSummary: String = "",
    @ColumnInfo(name = "context_summary_up_to_index", defaultValue = "-1")
    val contextSummaryUpToIndex: Int = -1,
    @ColumnInfo(name = "last_prune_time", defaultValue = "0")
    val lastPruneTime: Long = 0L,
    @ColumnInfo(name = "last_prune_message_count", defaultValue = "0")
    val lastPruneMessageCount: Int = 0,
    @ColumnInfo(name = "last_refresh_time", defaultValue = "0")
    val lastRefreshTime: Long = 0L,
    @ColumnInfo(name = "context_summary_boundaries", defaultValue = "[]")
    val contextSummaryBoundaries: String = "[]",
    @ColumnInfo(name = "session_memories", defaultValue = "[]")
    val sessionMemories: String = "[]",
)
