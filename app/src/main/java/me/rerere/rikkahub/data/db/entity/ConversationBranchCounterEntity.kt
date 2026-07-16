package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 保存每棵会话树的下一个分支编号。
 *
 * 计数独立于会话记录，删除分支或清空未来的回收站都不会让编号回退。
 */
@Entity(tableName = "conversation_branch_counters")
data class ConversationBranchCounterEntity(
    @PrimaryKey
    @ColumnInfo(name = "root_id")
    val rootId: String,
    @ColumnInfo(name = "next_branch_number")
    val nextBranchNumber: Int,
)
