package com.aliothmoon.maafw.log

import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Clears all diagnostic products before an app restart. */
class LogCleanupService(
    private val appLogWriter: AppLogWriter,
    private val roots: () -> List<File>,
) {

    suspend fun clearAll(): Boolean = withContext(MaaDispatchers.IO) {
        appLogWriter.purge().join()
        roots()
            .map { root -> !root.exists() || root.deleteRecursively() }
            .all { it }
    }
}
