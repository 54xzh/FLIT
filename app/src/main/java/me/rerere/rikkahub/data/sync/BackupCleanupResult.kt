package me.rerere.rikkahub.data.sync

/**
 * Result of backup cleanup operation.
 * Tracks how much unsupported/deprecated data was cleaned during restore.
 */
data class BackupCleanupResult(
    /** Bytes of unknown/unsupported zip entries that were skipped */
    val unsupportedZipEntriesBytes: Long = 0,
    /** Number of assistants with invalid searchMode.Provider indices that were reset to Off */
    val invalidSearchModeCount: Int = 0,
    /** Number of orphaned tag references removed from assistants */
    val orphanedTagReferences: Int = 0,
    /** Number of orphaned favorite model references removed */
    val orphanedModelReferences: Int = 0,
    /** Number of avatar paths that were fixed to match the current device's file system */
    val fixedAvatarPaths: Int = 0,
) {
    /** Total count of issues found and fixed */
    val totalIssuesFixed: Int
        get() = invalidSearchModeCount + orphanedTagReferences + orphanedModelReferences + fixedAvatarPaths

    /** Whether any cleanup was performed */
    val hasCleanup: Boolean
        get() = unsupportedZipEntriesBytes > 0 || totalIssuesFixed > 0

    /** Combine two results (useful when aggregating from multiple sources) */
    operator fun plus(other: BackupCleanupResult) = BackupCleanupResult(
        unsupportedZipEntriesBytes = unsupportedZipEntriesBytes + other.unsupportedZipEntriesBytes,
        invalidSearchModeCount = invalidSearchModeCount + other.invalidSearchModeCount,
        orphanedTagReferences = orphanedTagReferences + other.orphanedTagReferences,
        orphanedModelReferences = orphanedModelReferences + other.orphanedModelReferences,
        fixedAvatarPaths = fixedAvatarPaths + other.fixedAvatarPaths,
    )
}
