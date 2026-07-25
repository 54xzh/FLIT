package me.rerere.rikkahub.data.repository

import kotlin.uuid.Uuid

/**
 * 会话删除协调接口：在 data 层定义，由 app 层的 ChatService 实现。
 *
 * 为什么需要它：会话内容同时存在于内存（ChatService 的 StateFlow）和数据库。直接调
 * [ConversationRepository] 删 DB 后，若被删会话仍在内存中，之后切页面触发的「退出兜底保存」
 * 会因 getConversationById == null 走 insert 分支把它重新写回库（会话复活）。任何删除入口
 * 都必须先「置删除标记 + 清内存 + 取消生成」，再在写锁内删 DB，才能堵住这条复活路径。
 *
 * 把这套协调能力抽成接口放在 data 层，是为了让 [StorageManagerRepository]（data 层，不能反向
 * 依赖 app 层的 ChatService，否则循环依赖）也能走同一套删除协调。ChatService 实现此接口，
 * 在 DI 中作为 [ConversationDeletionCoordinator] 绑定。
 */
interface ConversationDeletionCoordinator {
    /**
     * 按 id 删除单个会话（无撤销窗口）。同步置删除标记 + 清内存，再在会话写锁内删 DB。
     *
     * @param deleteFiles 是否同时删除会话关联的附件文件与工作区; false 时只清 DB 记录,
     *   保留附件 (如存储管理「只清聊天记录」模式)。
     */
    suspend fun deleteConversationById(conversationId: Uuid, deleteFiles: Boolean = true)

    /**
     * 删除某助手下的所有会话。对每个会话都先同步标记 + 清内存，再在各自写锁内删 DB。
     *
     * @param deleteFiles 是否同时删除会话关联的附件文件; false 时只清 DB 记录 (如「只清聊天记录」模式)。
     */
    suspend fun deleteConversationsOfAssistant(assistantId: Uuid, deleteFiles: Boolean = true)
}