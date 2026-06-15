package me.rerere.rikkahub.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.AIRequestLogDao
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

@Serializable
enum class StorageCategoryKey(val key: String) {
    IMAGES("images"),
    FILES("files"),
    CHAT_RECORDS("chat_records"),
    CACHE("cache"),
    HISTORY_FILES("history_files"),
    LOGS("logs"),
    ;

    companion object {
        fun fromKeyOrNull(key: String): StorageCategoryKey? = entries.firstOrNull { it.key == key }
    }
}

@Serializable
data class StorageCategoryUsage(
    val category: StorageCategoryKey,
    val bytes: Long,
    val fileCount: Int,
)

@Serializable
data class CacheTopLevelUsage(
    val name: String,
    val bytes: Long,
    val fileCount: Int,
    val isDirectory: Boolean,
)

@Serializable
data class StorageOverview(
    val totalBytes: Long,
    val categories: List<StorageCategoryUsage>,
    val requestLogCount: Int,
    val generatedAt: Long,
)

data class OrphanEntry(
    val absolutePath: String,
    val bytes: Long,
)

data class OrphanScanResult(
    val totalBytes: Long,
    val totalCount: Int,
    val preview: List<OrphanEntry>,
)

data class DeleteResult(
    val deletedCount: Int,
    val failedCount: Int,
    val deletedBytes: Long,
)

data class AssistantAttachmentStats(
    val imageCount: Int,
    val imageBytes: Long,
    val fileCount: Int,
    val fileBytes: Long,
)

data class AssistantImageEntry(
    val absolutePath: String,
    val bytes: Long,
    val lastModified: Long,
    val url: String,
)

data class AssistantFileEntry(
    val absolutePath: String,
    val bytes: Long,
    val lastModified: Long,
    val fileName: String,
    val mime: String,
)

data class ChatRecordsMonthEntry(
    val yearMonth: String,
    val conversationCount: Int,
)

enum class AssistantChatCleanupMode {
    RECORDS_ONLY,
    FILES_ONLY,
    RECORDS_AND_FILES,
}

class StorageManagerRepository(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationDAO: ConversationDAO,
    private val conversationRepository: ConversationRepository,
    private val genMediaDAO: GenMediaDAO,
    private val aiRequestLogDao: AIRequestLogDao,
) {
    private companion object {
        private const val ATTACHMENT_LIST_CACHE_MAX_AGE_MS = 10 * 60_000L
        private const val OVERVIEW_CACHE_MAX_AGE_MS = 30 * 60_000L
    }

    private val overviewCache = TimedSuspendCache<StorageOverview>(
        maxAgeMs = OVERVIEW_CACHE_MAX_AGE_MS,
    )

    private val allImageEntriesCache = TimedSuspendCache<List<AssistantImageEntry>>(
        maxAgeMs = ATTACHMENT_LIST_CACHE_MAX_AGE_MS,
    )

    private val allFileEntriesCache = TimedSuspendCache<List<AssistantFileEntry>>(
        maxAgeMs = ATTACHMENT_LIST_CACHE_MAX_AGE_MS,
    )

    private val cacheTopLevelUsageCache = TimedSuspendCache<List<CacheTopLevelUsage>>(
        maxAgeMs = OVERVIEW_CACHE_MAX_AGE_MS,
    )

    private val assistantImageEntriesCaches = ConcurrentHashMap<Uuid, TimedSuspendCache<List<AssistantImageEntry>>>()
    private val assistantFileEntriesCaches = ConcurrentHashMap<Uuid, TimedSuspendCache<List<AssistantFileEntry>>>()

    /**
     * On-disk snapshot of the last overview computation. Survives process death so the storage
     * page can render instantly on cold start while the fresh computation runs in the background.
     */
    private val overviewCacheFile = File(File(context.filesDir, "storage"), "overview_cache.json")

    private fun readDiskOverviewCache(): StorageOverview? = runCatching {
        val file = overviewCacheFile
        if (!file.exists() || !file.isFile) return null
        JsonInstant.decodeFromString<StorageOverview>(file.readText())
    }.getOrNull()

    private fun writeDiskOverviewCache(overview: StorageOverview) {
        runCatching {
            val file = overviewCacheFile
            file.parentFile?.mkdirs()
            // Use a process-unique tmp name so concurrent writers (e.g. the settings page and the
            // storage page both triggering loadOverview) cannot clobber each other's tmp file.
            val tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
            tmp.writeText(JsonInstant.encodeToString(StorageOverview.serializer(), overview))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    fun peekOverviewCache(): StorageOverview? = overviewCache.peek()?.value

    /**
     * Returns the most recent overview result available without triggering a recomputation.
     * Falls back to the on-disk snapshot (which may be stale) when the in-memory cache is empty,
     * e.g. right after a cold start. Use this to render the page instantly.
     */
    fun peekDiskOverviewCache(): StorageOverview? {
        overviewCache.peek()?.value?.let { return it }
        return readDiskOverviewCache()
    }

    fun peekAllImageEntriesCache(): List<AssistantImageEntry>? = allImageEntriesCache.peek()?.value
    fun peekAllFileEntriesCache(): List<AssistantFileEntry>? = allFileEntriesCache.peek()?.value

    fun peekAssistantImageEntriesCache(assistantId: Uuid): List<AssistantImageEntry>? {
        return assistantImageEntriesCaches[assistantId]?.peek()?.value
    }

    fun peekAssistantFileEntriesCache(assistantId: Uuid): List<AssistantFileEntry>? {
        return assistantFileEntriesCaches[assistantId]?.peek()?.value
    }

    fun invalidateOverviewCache() {
        overviewCache.invalidate()
    }

    private fun invalidateCacheUsageCaches() {
        cacheTopLevelUsageCache.invalidate()
    }

    private fun invalidateImageEntriesCaches() {
        allImageEntriesCache.invalidate()
        assistantImageEntriesCaches.values.forEach { it.invalidate() }
    }

    private fun invalidateFileEntriesCaches() {
        allFileEntriesCache.invalidate()
        assistantFileEntriesCaches.values.forEach { it.invalidate() }
    }

    suspend fun loadOverview(forceRefresh: Boolean = false): StorageOverview {
        val overview = overviewCache.get(forceRefresh = forceRefresh) { computeOverview() }
        writeDiskOverviewCache(overview)
        return overview
    }

    private suspend fun computeOverview(): StorageOverview = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val referencedSkillIds = settings.skills.map { it.id.toString() }.toSet()

        // The referenced-file set must be computed first: the per-directory walks below depend on it.
        val referencedFilePaths = buildReferencedFilePathSet(settings = settings)

        val uploadDir = File(context.filesDir, "upload")
        val imagesDir = File(context.filesDir, "images")
        val avatarsDir = File(context.filesDir, "avatars")
        val customIconsDir = File(context.filesDir, "custom_icons")
        val skillsDir = File(context.filesDir, "skills")

        // Run every independent I/O in parallel. Each directory walk + the DB counts + the DB file
        // size read have no data dependency on each other, so awaiting them concurrently turns the
        // previous sequential chain into a max-cost rather than a sum-cost.
        val requestLogCount = async { runCatching { aiRequestLogDao.countAll() }.getOrNull() ?: 0 }
        val conversationCount = async { runCatching { conversationDAO.getConversationCount() }.getOrNull() ?: 0 }
        val dbUsage = async { countDatabaseUsage() }
        val cacheUsage = async { countDirUsage(context.cacheDir) }
        val uploadUsage = async {
            countManagedFilesInDir(uploadDir, referencedFilePaths, treatAllAsImages = false)
        }
        val imagesUsage = async {
            countManagedFilesInDir(imagesDir, referencedFilePaths, treatAllAsImages = true)
        }
        val avatarsUsage = async {
            countManagedFilesInDir(avatarsDir, referencedFilePaths, treatAllAsImages = true)
        }
        val customIconsUsage = async {
            countManagedFilesInDir(customIconsDir, referencedFilePaths, treatAllAsImages = true)
        }
        val skillsUsage = async {
            countSkillsUsage(skillsDir, referencedSkillIds)
        }

        val requestLogCountVal = requestLogCount.await()
        val conversationCountVal = conversationCount.await()
        val dbUsageVal = dbUsage.await()
        val cacheUsageVal = cacheUsage.await()
        val uploadUsageVal = uploadUsage.await()
        val imagesUsageVal = imagesUsage.await()
        val avatarsUsageVal = avatarsUsage.await()
        val customIconsUsageVal = customIconsUsage.await()
        val skillsUsageVal = skillsUsage.await()

        val imagesBytes = uploadUsageVal.images.bytes +
            imagesUsageVal.images.bytes +
            avatarsUsageVal.images.bytes +
            customIconsUsageVal.images.bytes
        val imagesCount = uploadUsageVal.images.count +
            imagesUsageVal.images.count +
            avatarsUsageVal.images.count +
            customIconsUsageVal.images.count

        // "Files" in Storage Manager is scoped to assistant chat attachments referenced by
        // conversations (Document / Video / Audio in upload/). This is exactly what
        // countManagedFilesInDir already classifies as non-image referenced files, so we reuse it
        // instead of triggering a second full-table conversation scan via getAllFileEntries().
        // Skills packages are managed elsewhere and should not be surfaced here.
        val filesBytes = uploadUsageVal.files.bytes + imagesUsageVal.files.bytes
        val filesCount = uploadUsageVal.files.count + imagesUsageVal.files.count

        val historyBytes = uploadUsageVal.history.bytes +
            imagesUsageVal.history.bytes +
            avatarsUsageVal.history.bytes +
            customIconsUsageVal.history.bytes +
            skillsUsageVal.history.bytes
        val historyCount = uploadUsageVal.history.count +
            imagesUsageVal.history.count +
            avatarsUsageVal.history.count +
            customIconsUsageVal.history.count +
            skillsUsageVal.history.count

        val categories = listOf(
            StorageCategoryUsage(StorageCategoryKey.IMAGES, imagesBytes, imagesCount),
            StorageCategoryUsage(StorageCategoryKey.FILES, filesBytes, filesCount),
            StorageCategoryUsage(StorageCategoryKey.CHAT_RECORDS, dbUsageVal.bytes, conversationCountVal),
            StorageCategoryUsage(StorageCategoryKey.CACHE, cacheUsageVal.bytes, cacheUsageVal.count),
            StorageCategoryUsage(StorageCategoryKey.HISTORY_FILES, historyBytes, historyCount),
            StorageCategoryUsage(StorageCategoryKey.LOGS, bytes = 0L, fileCount = requestLogCountVal),
        )

        StorageOverview(
            totalBytes = categories
                .asSequence()
                .filterNot { it.category == StorageCategoryKey.LOGS }
                .sumOf { it.bytes },
            categories = categories,
            requestLogCount = requestLogCountVal,
            generatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Cached. The CACHE category page calls both [getCacheUsage] and [getCacheTopLevelUsage] on
     * entry, and each used to walk the entire cacheDir tree independently (twice per visit). We
     * now walk once to build the per-top-level breakdown, derive the total from it, and cache the
     * breakdown so repeat visits within the TTL are free.
     */
    suspend fun getCacheUsage(forceRefresh: Boolean = false): StorageCategoryUsage {
        // Derive the total from the cached per-top-level breakdown so the cacheDir tree is walked
        // at most once per TTL window instead of twice per page visit.
        val topLevel = getCacheTopLevelUsage(forceRefresh = forceRefresh)
        return StorageCategoryUsage(
            category = StorageCategoryKey.CACHE,
            bytes = topLevel.sumOf { it.bytes },
            fileCount = topLevel.sumOf { it.fileCount },
        )
    }

    suspend fun getCacheTopLevelUsage(forceRefresh: Boolean = false): List<CacheTopLevelUsage> {
        return cacheTopLevelUsageCache.get(forceRefresh = forceRefresh) { computeCacheTopLevelUsage() }
    }

    private suspend fun computeCacheTopLevelUsage(): List<CacheTopLevelUsage> = withContext(Dispatchers.IO) {
        context.cacheDir
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.exists() }
            .map { entry ->
                val usage = countDirUsage(entry)
                CacheTopLevelUsage(
                    name = entry.name.ifBlank { entry.absolutePath },
                    bytes = usage.bytes,
                    fileCount = usage.count,
                    isDirectory = entry.isDirectory,
                )
            }
            .sortedWith(
                compareByDescending<CacheTopLevelUsage> { it.bytes }
                    .thenBy { it.name.lowercase() }
            )
            .toList()
    }

    suspend fun getChatRecordsUsage(): StorageCategoryUsage = withContext(Dispatchers.IO) {
        val usage = countDatabaseUsage()
        val conversationCount = runCatching { conversationDAO.getConversationCount() }.getOrNull() ?: 0
        StorageCategoryUsage(
            category = StorageCategoryKey.CHAT_RECORDS,
            bytes = usage.bytes,
            fileCount = conversationCount,
        )
    }

    suspend fun getChatRecordsMonthEntries(assistantId: Uuid?): List<ChatRecordsMonthEntry> = withContext(Dispatchers.IO) {
        val rows = if (assistantId == null) {
            conversationDAO.getConversationMonthCounts()
        } else {
            conversationDAO.getConversationMonthCountsOfAssistant(assistantId.toString())
        }

        rows.map { ChatRecordsMonthEntry(yearMonth = it.yearMonth, conversationCount = it.count) }
    }

    suspend fun getChatRecordConversationsByYearMonth(
        assistantId: Uuid?,
        yearMonth: String,
    ): List<LightConversationEntity> = withContext(Dispatchers.IO) {
        val ym = runCatching { YearMonth.parse(yearMonth) }.getOrNull() ?: return@withContext emptyList()
        val zoneId = ZoneId.systemDefault()
        val startMs = ym.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = ym.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        if (assistantId == null) {
            conversationDAO.getLightConversationsByUpdateAtRange(startMs = startMs, endMs = endMs)
        } else {
            conversationDAO.getLightConversationsOfAssistantByUpdateAtRange(
                assistantId = assistantId.toString(),
                startMs = startMs,
                endMs = endMs,
            )
        }
    }

    suspend fun clearChatRecordsByYearMonths(
        assistantId: Uuid?,
        yearMonths: Set<String>,
    ): DeleteResult = withContext(Dispatchers.IO) {
        val zoneId = ZoneId.systemDefault()
        val ids = LinkedHashSet<String>(yearMonths.size * 8)

        yearMonths.forEach { yearMonth ->
            val ym = runCatching { YearMonth.parse(yearMonth) }.getOrNull() ?: return@forEach
            val startMs = ym.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endMs = ym.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

            val monthIds = if (assistantId == null) {
                conversationDAO.getConversationIdsByUpdateAtRange(startMs = startMs, endMs = endMs)
            } else {
                conversationDAO.getConversationIdsOfAssistantByUpdateAtRange(
                    assistantId = assistantId.toString(),
                    startMs = startMs,
                    endMs = endMs,
                )
            }
            ids.addAll(monthIds)
        }

        var deletedCount = 0
        var failedCount = 0
        ids.forEach { conversationId ->
            val ok = runCatching { conversationRepository.deleteConversationById(conversationId, deleteFiles = false) }
                .isSuccess
            if (ok) deletedCount += 1 else failedCount += 1
        }

        invalidateOverviewCache()
        invalidateImageEntriesCaches()
        invalidateFileEntriesCaches()
        DeleteResult(
            deletedCount = deletedCount,
            failedCount = failedCount,
            deletedBytes = 0L,
        )
    }

    suspend fun clearChatRecordsByConversationIds(
        conversationIds: Set<String>,
    ): DeleteResult = withContext(Dispatchers.IO) {
        if (conversationIds.isEmpty()) return@withContext DeleteResult(0, 0, 0L)

        var deletedCount = 0
        var failedCount = 0
        conversationIds.forEach { conversationId ->
            val ok = runCatching { conversationRepository.deleteConversationById(conversationId, deleteFiles = false) }
                .isSuccess
            if (ok) deletedCount += 1 else failedCount += 1
        }

        invalidateOverviewCache()
        invalidateImageEntriesCaches()
        invalidateFileEntriesCaches()
        DeleteResult(
            deletedCount = deletedCount,
            failedCount = failedCount,
            deletedBytes = 0L,
        )
    }

    suspend fun getLogsUsage(): StorageCategoryUsage = withContext(Dispatchers.IO) {
        val requestLogCount = runCatching { aiRequestLogDao.countAll() }.getOrNull() ?: 0
        StorageCategoryUsage(
            category = StorageCategoryKey.LOGS,
            bytes = 0L,
            fileCount = requestLogCount,
        )
    }

    suspend fun getAssistantAttachmentStats(assistantId: Uuid): AssistantAttachmentStats = withContext(Dispatchers.IO) {
        val conversations = conversationRepository.getConversationsOfAssistant(assistantId).first()
        val imageUrls = LinkedHashSet<String>()
        val fileUrls = LinkedHashSet<String>()

        conversations.forEach { conversation ->
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Image -> imageUrls += part.url
                            is UIMessagePart.Document -> fileUrls += part.url
                            is UIMessagePart.Video -> fileUrls += part.url
                            is UIMessagePart.Audio -> fileUrls += part.url
                            else -> Unit
                        }
                    }
                }
            }
        }

        val uploadDir = File(context.filesDir, "upload")

        val images = imageUrls
            .asSequence()
            .mapNotNull { StorageScanUtils.toExistingLocalFileOrNull(it, context.filesDir) }
            .distinctBy { StorageScanUtils.normalizePath(it) }
            .filter { file ->
                // Safety: only delete chat attachments in upload/, avoid touching avatars/images dirs.
                StorageScanUtils.isInChildOf(file, uploadDir)
            }
            .toList()

        val files = fileUrls
            .asSequence()
            .mapNotNull { StorageScanUtils.toExistingLocalFileOrNull(it, context.filesDir) }
            .distinctBy { StorageScanUtils.normalizePath(it) }
            .filter { file ->
                StorageScanUtils.isInChildOf(file, uploadDir)
            }
            .toList()

        AssistantAttachmentStats(
            imageCount = images.size,
            imageBytes = images.sumOf { it.lengthSafe() },
            fileCount = files.size,
            fileBytes = files.sumOf { it.lengthSafe() },
        )
    }

    suspend fun clearAssistantChatAttachments(
        assistantId: Uuid,
        clearImages: Boolean,
        clearFiles: Boolean,
    ): DeleteResult = withContext(Dispatchers.IO) {
        val conversations = conversationRepository.getConversationsOfAssistant(assistantId).first()
        val imageUrls = LinkedHashSet<String>()
        val fileUrls = LinkedHashSet<String>()

        conversations.forEach { conversation ->
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Image -> if (clearImages) imageUrls += part.url
                            is UIMessagePart.Document -> if (clearFiles) fileUrls += part.url
                            is UIMessagePart.Video -> if (clearFiles) fileUrls += part.url
                            is UIMessagePart.Audio -> if (clearFiles) fileUrls += part.url
                            else -> Unit
                        }
                    }
                }
            }
        }

        val uploadDir = File(context.filesDir, "upload")
        val targetFiles = (imageUrls + fileUrls)
            .asSequence()
            .mapNotNull { StorageScanUtils.toExistingLocalFileOrNull(it, context.filesDir) }
            .distinctBy { StorageScanUtils.normalizePath(it) }
            .filter { file ->
                StorageScanUtils.isInChildOf(file, uploadDir)
            }
            .toList()

        val result = deleteFiles(targetFiles)
        invalidateOverviewCache()
        if (clearImages) invalidateImageEntriesCaches()
        if (clearFiles) invalidateFileEntriesCaches()
        result
    }

    suspend fun clearAssistantChats(
        assistantId: Uuid,
        mode: AssistantChatCleanupMode,
    ): DeleteResult = withContext(Dispatchers.IO) {
        val result = when (mode) {
            AssistantChatCleanupMode.RECORDS_ONLY -> {
                conversationRepository.deleteConversationOfAssistant(assistantId, deleteFiles = false)
                DeleteResult(deletedCount = 0, failedCount = 0, deletedBytes = 0L)
            }

            AssistantChatCleanupMode.FILES_ONLY -> {
                clearAssistantChatAttachments(
                    assistantId = assistantId,
                    clearImages = true,
                    clearFiles = true,
                )
            }

            AssistantChatCleanupMode.RECORDS_AND_FILES -> {
                conversationRepository.deleteConversationOfAssistant(assistantId, deleteFiles = true)
                DeleteResult(deletedCount = 0, failedCount = 0, deletedBytes = 0L)
            }
        }
        invalidateOverviewCache()
        invalidateImageEntriesCaches()
        invalidateFileEntriesCaches()
        result
    }

    suspend fun getAssistantConversationCount(assistantId: Uuid): Int = withContext(Dispatchers.IO) {
        runCatching { conversationDAO.getConversationCountOfAssistant(assistantId.toString()) }
            .getOrNull()
            ?: 0
    }

    private fun getAssistantImageEntriesCache(assistantId: Uuid): TimedSuspendCache<List<AssistantImageEntry>> {
        return assistantImageEntriesCaches.computeIfAbsent(assistantId) {
            TimedSuspendCache(maxAgeMs = ATTACHMENT_LIST_CACHE_MAX_AGE_MS)
        }
    }

    suspend fun getAssistantImageEntries(
        assistantId: Uuid,
        forceRefresh: Boolean = false,
    ): List<AssistantImageEntry> {
        return getAssistantImageEntriesCache(assistantId)
            .get(forceRefresh = forceRefresh) { computeAssistantImageEntries(assistantId) }
    }

    private suspend fun computeAssistantImageEntries(assistantId: Uuid): List<AssistantImageEntry> = withContext(Dispatchers.IO) {
        val conversations = conversationRepository.getConversationsOfAssistant(assistantId).first()
        val imageUrls = LinkedHashSet<String>()

        conversations.forEach { conversation ->
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Image -> imageUrls += part.url
                            is UIMessagePart.Document -> if (part.mime.startsWith("image/")) imageUrls += part.url
                            else -> Unit
                        }
                    }
                }
            }
        }

        val uploadDir = File(context.filesDir, "upload")
        imageUrls
            .asSequence()
            .mapNotNull { StorageScanUtils.toExistingLocalFileOrNull(it, context.filesDir) }
            .distinctBy { StorageScanUtils.normalizePath(it) }
            .filter { file ->
                StorageScanUtils.isInChildOf(file, uploadDir)
            }
            .map { file ->
                val path = StorageScanUtils.normalizePath(file)
                AssistantImageEntry(
                    absolutePath = path,
                    bytes = file.lengthSafe(),
                    lastModified = runCatching { file.lastModified() }.getOrNull() ?: 0L,
                    url = "file://$path",
                )
            }
            .sortedByDescending { it.lastModified }
            .toList()
    }

    suspend fun getAllImageEntries(forceRefresh: Boolean = false): List<AssistantImageEntry> {
        return allImageEntriesCache.get(forceRefresh = forceRefresh) { computeAllImageEntries() }
    }

    private suspend fun computeAllImageEntries(): List<AssistantImageEntry> = withContext(Dispatchers.IO) {
        val conversations = conversationRepository.getAllConversations().first()
        val imageUrls = LinkedHashSet<String>()

        conversations.forEach { conversation ->
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Image -> imageUrls += part.url
                            is UIMessagePart.Document -> if (part.mime.startsWith("image/")) imageUrls += part.url
                            else -> Unit
                        }
                    }
                }
            }
        }

        val uploadDir = File(context.filesDir, "upload")
        imageUrls
            .asSequence()
            .mapNotNull { StorageScanUtils.toExistingLocalFileOrNull(it, context.filesDir) }
            .distinctBy { StorageScanUtils.normalizePath(it) }
            .filter { file ->
                StorageScanUtils.isInChildOf(file, uploadDir)
            }
            .map { file ->
                val path = StorageScanUtils.normalizePath(file)
                AssistantImageEntry(
                    absolutePath = path,
                    bytes = file.lengthSafe(),
                    lastModified = runCatching { file.lastModified() }.getOrNull() ?: 0L,
                    url = "file://$path",
                )
            }
            .sortedByDescending { it.lastModified }
            .toList()
    }

    private fun getAssistantFileEntriesCache(assistantId: Uuid): TimedSuspendCache<List<AssistantFileEntry>> {
        return assistantFileEntriesCaches.computeIfAbsent(assistantId) {
            TimedSuspendCache(maxAgeMs = ATTACHMENT_LIST_CACHE_MAX_AGE_MS)
        }
    }

    suspend fun getAssistantFileEntries(
        assistantId: Uuid,
        forceRefresh: Boolean = false,
    ): List<AssistantFileEntry> {
        return getAssistantFileEntriesCache(assistantId)
            .get(forceRefresh = forceRefresh) { computeAssistantFileEntries(assistantId) }
    }

    private suspend fun computeAssistantFileEntries(assistantId: Uuid): List<AssistantFileEntry> = withContext(Dispatchers.IO) {
        data class Candidate(
            val url: String,
            val fileName: String,
            val mime: String,
        )

        val conversations = conversationRepository.getConversationsOfAssistant(assistantId).first()
        val candidates = ArrayList<Candidate>(64)

        conversations.forEach { conversation ->
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Document -> {
                                if (!part.mime.startsWith("image/")) {
                                    candidates += Candidate(
                                        url = part.url,
                                        fileName = part.fileName,
                                        mime = part.mime,
                                    )
                                }
                            }

                            is UIMessagePart.Video -> candidates += Candidate(
                                url = part.url,
                                fileName = "",
                                mime = "video/*",
                            )

                            is UIMessagePart.Audio -> candidates += Candidate(
                                url = part.url,
                                fileName = "",
                                mime = "audio/*",
                            )

                            else -> Unit
                        }
                    }
                }
            }
        }

        val uploadDir = File(context.filesDir, "upload")
        val byPath = LinkedHashMap<String, AssistantFileEntry>()

        candidates.forEach { candidate ->
            val file = StorageScanUtils.toExistingLocalFileOrNull(candidate.url, context.filesDir) ?: return@forEach
            if (!StorageScanUtils.isInChildOf(file, uploadDir)) return@forEach

            val normalizedPath = StorageScanUtils.normalizePath(file)
            val bytes = file.lengthSafe()
            val lastModified = runCatching { file.lastModified() }.getOrNull() ?: 0L

            val fallbackName = File(normalizedPath).name
            val desiredName = candidate.fileName.trim().ifBlank { fallbackName }
            val desiredMime = candidate.mime.trim()

            val existing = byPath[normalizedPath]
            val merged = if (existing == null) {
                AssistantFileEntry(
                    absolutePath = normalizedPath,
                    bytes = bytes,
                    lastModified = lastModified,
                    fileName = desiredName,
                    mime = desiredMime,
                )
            } else {
                val existingFallbackName = File(existing.absolutePath).name
                val mergedName = when {
                    existing.fileName.isBlank() -> desiredName
                    desiredName.isBlank() -> existing.fileName
                    existing.fileName == existingFallbackName && desiredName != existingFallbackName -> desiredName
                    else -> existing.fileName
                }
                val mergedMime = if (existing.mime.isBlank()) desiredMime else existing.mime
                existing.copy(
                    bytes = bytes,
                    lastModified = maxOf(existing.lastModified, lastModified),
                    fileName = mergedName,
                    mime = mergedMime,
                )
            }
            byPath[normalizedPath] = merged
        }

        byPath.values
            .sortedByDescending { it.lastModified }
    }

    suspend fun getAllFileEntries(forceRefresh: Boolean = false): List<AssistantFileEntry> {
        return allFileEntriesCache.get(forceRefresh = forceRefresh) { computeAllFileEntries() }
    }

    private suspend fun computeAllFileEntries(): List<AssistantFileEntry> = withContext(Dispatchers.IO) {
        data class Candidate(
            val url: String,
            val fileName: String,
            val mime: String,
        )

        val conversations = conversationRepository.getAllConversations().first()
        val candidates = ArrayList<Candidate>(128)

        conversations.forEach { conversation ->
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Document -> {
                                if (!part.mime.startsWith("image/")) {
                                    candidates += Candidate(
                                        url = part.url,
                                        fileName = part.fileName,
                                        mime = part.mime,
                                    )
                                }
                            }

                            is UIMessagePart.Video -> candidates += Candidate(
                                url = part.url,
                                fileName = "",
                                mime = "video/*",
                            )

                            is UIMessagePart.Audio -> candidates += Candidate(
                                url = part.url,
                                fileName = "",
                                mime = "audio/*",
                            )

                            else -> Unit
                        }
                    }
                }
            }
        }

        val uploadDir = File(context.filesDir, "upload")
        val byPath = LinkedHashMap<String, AssistantFileEntry>()

        candidates.forEach { candidate ->
            val file = StorageScanUtils.toExistingLocalFileOrNull(candidate.url, context.filesDir) ?: return@forEach
            if (!StorageScanUtils.isInChildOf(file, uploadDir)) return@forEach

            val normalizedPath = StorageScanUtils.normalizePath(file)
            val bytes = file.lengthSafe()
            val lastModified = runCatching { file.lastModified() }.getOrNull() ?: 0L

            val fallbackName = File(normalizedPath).name
            val desiredName = candidate.fileName.trim().ifBlank { fallbackName }
            val desiredMime = candidate.mime.trim()

            val existing = byPath[normalizedPath]
            val merged = if (existing == null) {
                AssistantFileEntry(
                    absolutePath = normalizedPath,
                    bytes = bytes,
                    lastModified = lastModified,
                    fileName = desiredName,
                    mime = desiredMime,
                )
            } else {
                val existingFallbackName = File(existing.absolutePath).name
                val mergedName = when {
                    existing.fileName.isBlank() -> desiredName
                    desiredName.isBlank() -> existing.fileName
                    existing.fileName == existingFallbackName && desiredName != existingFallbackName -> desiredName
                    else -> existing.fileName
                }
                val mergedMime = if (existing.mime.isBlank()) desiredMime else existing.mime
                existing.copy(
                    bytes = bytes,
                    lastModified = maxOf(existing.lastModified, lastModified),
                    fileName = mergedName,
                    mime = mergedMime,
                )
            }
            byPath[normalizedPath] = merged
        }

        byPath.values
            .sortedByDescending { it.lastModified }
    }

    suspend fun deleteAssistantImageEntries(absolutePaths: List<String>): DeleteResult = withContext(Dispatchers.IO) {
        val uploadDir = File(context.filesDir, "upload")
        val files = absolutePaths
            .asSequence()
            .map { File(it) }
            .filter { file -> StorageScanUtils.isInChildOf(file, uploadDir) }
            .distinctBy { StorageScanUtils.normalizePath(it) }
            .toList()
        val result = deleteFiles(files)
        invalidateOverviewCache()
        invalidateImageEntriesCaches()
        result
    }

    suspend fun deleteAssistantFileEntries(absolutePaths: List<String>): DeleteResult = withContext(Dispatchers.IO) {
        val uploadDir = File(context.filesDir, "upload")
        val files = absolutePaths
            .asSequence()
            .map { File(it) }
            .filter { file -> StorageScanUtils.isInChildOf(file, uploadDir) }
            .distinctBy { StorageScanUtils.normalizePath(it) }
            .toList()
        val result = deleteFiles(files)
        invalidateOverviewCache()
        invalidateFileEntriesCaches()
        result
    }

    suspend fun scanOrphans(previewLimit: Int = 40): OrphanScanResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val referencedFilePaths = buildReferencedFilePathSet(settings = settings)
        val referencedSkillIds = settings.skills.map { it.id.toString() }.toSet()

        val preview = mutableListOf<OrphanEntry>()
        var totalBytes = 0L
        var totalCount = 0

        fun addOrphan(file: File) {
            val bytes = file.lengthSafe()
            totalBytes += bytes
            totalCount += 1
            if (preview.size < previewLimit) {
                preview += OrphanEntry(absolutePath = file.absolutePath, bytes = bytes)
            }
        }

        val roots = listOf(
            File(context.filesDir, "upload"),
            File(context.filesDir, "images"),
            File(context.filesDir, "avatars"),
            File(context.filesDir, "custom_icons"),
        )

        roots.forEach { root ->
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val normalized = StorageScanUtils.normalizePath(file)
                    if (normalized !in referencedFilePaths) {
                        addOrphan(file)
                    }
                }
        }

        // Orphan skills: folders under filesDir/skills/{uuid} not referenced by settings.skills.
        val skillsDir = File(context.filesDir, "skills")
        if (skillsDir.exists()) {
            skillsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { dir ->
                    val isUuidFolder = runCatching { Uuid.parse(dir.name) }.isSuccess
                    if (isUuidFolder && dir.name !in referencedSkillIds) {
                        dir.walkTopDown()
                            .filter { it.isFile }
                            .forEach(::addOrphan)
                    }
                }
        }

        OrphanScanResult(
            totalBytes = totalBytes,
            totalCount = totalCount,
            preview = preview,
        )
    }

    suspend fun clearAllOrphans(): DeleteResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val referencedFilePaths = buildReferencedFilePathSet(settings = settings)
        val referencedSkillIds = settings.skills.map { it.id.toString() }.toSet()

        var deletedCount = 0
        var failedCount = 0
        var deletedBytes = 0L

        fun deleteFile(file: File) {
            val bytes = file.lengthSafe()
            val ok = runCatching { file.delete() }.getOrNull() == true
            if (ok) {
                deletedCount += 1
                deletedBytes += bytes
            } else {
                failedCount += 1
            }
        }

        val roots = listOf(
            File(context.filesDir, "upload"),
            File(context.filesDir, "images"),
            File(context.filesDir, "avatars"),
            File(context.filesDir, "custom_icons"),
        )
        roots.forEach { root ->
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val normalized = StorageScanUtils.normalizePath(file)
                    if (normalized !in referencedFilePaths) {
                        deleteFile(file)
                    }
                }
        }

        val skillsDir = File(context.filesDir, "skills")
        if (skillsDir.exists()) {
            skillsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { dir ->
                    val isUuidFolder = runCatching { Uuid.parse(dir.name) }.isSuccess
                    if (!isUuidFolder) return@forEach
                    if (dir.name in referencedSkillIds) return@forEach

                    val usage = countDirUsage(dir)
                    val ok = runCatching { dir.deleteRecursively() }.getOrNull() == true
                    if (ok) {
                        deletedCount += usage.count
                        deletedBytes += usage.bytes
                    } else {
                        failedCount += 1
                    }
                }
        }

        val result = DeleteResult(
            deletedCount = deletedCount,
            failedCount = failedCount,
            deletedBytes = deletedBytes,
        )
        invalidateOverviewCache()
        result
    }

    suspend fun clearCache(): DeleteResult = withContext(Dispatchers.IO) {
        val root = context.cacheDir
        val children = root.listFiles().orEmpty()
        val targets = children.filter { it.exists() }
        val result = deleteFilesOrDirs(targets)
        invalidateOverviewCache()
        invalidateCacheUsageCaches()
        result
    }

    private data class Usage(val count: Int, val bytes: Long)

    private data class SplitUsage(
        val images: Usage,
        val files: Usage,
        val history: Usage,
    )

    private data class SkillSplitUsage(
        val files: Usage,
        val history: Usage,
    )

    private fun countDatabaseUsage(): Usage {
        val dbFile = context.getDatabasePath("rikka_hub")
        val walFile = File(dbFile.parentFile, "rikka_hub-wal")
        val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
        val files = listOf(dbFile, walFile, shmFile).filter { it.exists() && it.isFile }
        return Usage(
            count = files.size,
            bytes = files.sumOf { it.lengthSafe() },
        )
    }

    private fun countDirUsage(root: File): Usage {
        if (!root.exists()) return Usage(count = 0, bytes = 0)
        var count = 0
        var bytes = 0L
        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                count += 1
                bytes += file.lengthSafe()
            }
        return Usage(count = count, bytes = bytes)
    }

    private fun countManagedFilesInDir(
        rootDir: File,
        referencedFilePaths: Set<String>,
        treatAllAsImages: Boolean,
    ): SplitUsage {
        if (!rootDir.exists()) {
            return SplitUsage(images = Usage(0, 0), files = Usage(0, 0), history = Usage(0, 0))
        }

        var imagesCount = 0
        var imagesBytes = 0L
        var filesCount = 0
        var filesBytes = 0L
        var historyCount = 0
        var historyBytes = 0L

        rootDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val bytes = file.lengthSafe()
                val referenced = StorageScanUtils.normalizePath(file) in referencedFilePaths
                if (!referenced) {
                    historyCount += 1
                    historyBytes += bytes
                    return@forEach
                }

                val isImage = treatAllAsImages || StorageScanUtils.isImageExtension(file.extension)
                if (isImage) {
                    imagesCount += 1
                    imagesBytes += bytes
                } else {
                    filesCount += 1
                    filesBytes += bytes
                }
            }

        return SplitUsage(
            images = Usage(imagesCount, imagesBytes),
            files = Usage(filesCount, filesBytes),
            history = Usage(historyCount, historyBytes),
        )
    }

    private fun countSkillsUsage(
        skillsDir: File,
        referencedSkillIds: Set<String>,
    ): SkillSplitUsage {
        if (!skillsDir.exists()) return SkillSplitUsage(files = Usage(0, 0), history = Usage(0, 0))

        var filesCount = 0
        var filesBytes = 0L
        var historyCount = 0
        var historyBytes = 0L

        skillsDir.listFiles().orEmpty().forEach { entry ->
            if (!entry.exists()) return@forEach

            val isUuidFolder = entry.isDirectory && runCatching { Uuid.parse(entry.name) }.isSuccess
            val isReferenced = isUuidFolder && entry.name in referencedSkillIds
            val usage = countDirUsage(entry)

            if (isUuidFolder && !isReferenced) {
                historyCount += usage.count
                historyBytes += usage.bytes
            } else {
                filesCount += usage.count
                filesBytes += usage.bytes
            }
        }

        return SkillSplitUsage(
            files = Usage(filesCount, filesBytes),
            history = Usage(historyCount, historyBytes),
        )
    }

    private suspend fun buildReferencedFilePathSet(settings: Settings): Set<String> {
        val referenced = HashSet<String>(8_192)

        fun addUrl(url: String?) {
            if (url.isNullOrBlank()) return
            val file = StorageScanUtils.toLocalFileOrNull(url, context.filesDir) ?: return
            referenced += StorageScanUtils.normalizePath(file)
        }

        fun addAvatar(avatar: Avatar?) {
            when (avatar) {
                is Avatar.Image -> addUrl(avatar.url)
                else -> Unit
            }
        }

        // Global user profile assets.
        addAvatar(settings.displaySetting.userAvatar)

        settings.assistants.forEach { assistant ->
            addAvatar(assistant.avatar)
            addUrl(assistant.background)
        }

        // Prompt injection assets.
        settings.modes.forEach { mode ->
            mode.attachments.forEach { attachment ->
                addUrl(attachment.url)
            }
        }

        settings.lorebooks.forEach { lorebook ->
            addAvatar(lorebook.cover)
            lorebook.entries.forEach { entry ->
                entry.attachments.forEach { attachment ->
                    addUrl(attachment.url)
                }
                addUrl(entry.imageContent)
            }
        }

        // Generated images: GenMediaEntity.path uses relative paths like "images/xxx.png".
        val mediaList = try {
            genMediaDAO.getAllMedia()
        } catch (_: Exception) {
            emptyList()
        }
        mediaList.forEach { media ->
            val path = media.path.trim()
            if (path.isBlank()) return@forEach
            val file = File(context.filesDir, path)
            referenced += StorageScanUtils.normalizePath(file)
        }

        // Conversations: scan nodes JSON in a single pass and pick up file:// urls.
        // A single SELECT is far cheaper than many OFFSET-page queries (OFFSET is a linear scan
        // that gets slower as offset grows), at the cost of holding all rows in memory at once.
        val rows = try {
            conversationDAO.getNodesForScan()
        } catch (_: Exception) {
            emptyList()
        }
        rows.forEach { row ->
            referenced += StorageScanUtils.extractReferencedFilePathsFromText(row.nodes, context.filesDir)
        }

        return referenced
    }

    private fun File.lengthSafe(): Long = runCatching { length() }.getOrNull() ?: 0L

    private fun deleteFiles(files: List<File>): DeleteResult {
        var deletedCount = 0
        var failedCount = 0
        var deletedBytes = 0L

        files.forEach { file ->
            val bytes = file.lengthSafe()
            val ok = runCatching { file.delete() }.getOrNull() == true
            if (ok) {
                deletedCount += 1
                deletedBytes += bytes
            } else {
                failedCount += 1
            }
        }

        return DeleteResult(
            deletedCount = deletedCount,
            failedCount = failedCount,
            deletedBytes = deletedBytes,
        )
    }

    private fun deleteFilesOrDirs(entries: List<File>): DeleteResult {
        var deletedCount = 0
        var failedCount = 0
        var deletedBytes = 0L

        entries.forEach { entry ->
            val usage = countDirUsage(entry)
            val ok = runCatching {
                if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            }.getOrNull() == true
            if (ok) {
                deletedCount += usage.count
                deletedBytes += usage.bytes
            } else {
                failedCount += 1
            }
        }

        return DeleteResult(
            deletedCount = deletedCount,
            failedCount = failedCount,
            deletedBytes = deletedBytes,
        )
    }
}
