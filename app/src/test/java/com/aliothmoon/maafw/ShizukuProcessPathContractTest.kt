package com.aliothmoon.maafw

import com.aliothmoon.maafw.privileged.ShizukuSpawner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shizuku 进程路径的行为约定，防止实现回退到 bindUserService 或后台化拉起 */
class ShizukuProcessPathContractTest {

    @Test
    fun shizukuSpawnerExecsLauncherInForeground() {
        val launcher = "/data/app/x/lib/arm64/liblauncher.so"
        val invocation = "'$launcher' --token=t"
        val command = ShizukuSpawner.wrapCommand(launcher, invocation)

        assertTrue(
            "必须前台 exec，IRemoteProcess 才追踪到服务进程本身: $command",
            command.contains("exec $invocation"),
        )
        assertFalse(
            "禁止后台化：launcher 退出状态会丢，任何进程侧失败都只能等满超时: $command",
            command.trimEnd().endsWith("&"),
        )
        assertTrue(
            "stdio 必须断开，否则管道写满会卡住服务进程: $command",
            command.contains("</dev/null >/dev/null 2>&1"),
        )
        assertTrue(
            "不可执行须秒级失败而非等超时: $command",
            command.startsWith("test -x '$launcher' || exit 126;"),
        )
    }

    @Test
    fun launcherExecsDirectlyWhenAlreadyShell() {
        val source = TestSources.resolve("src/main/native/launcher.c").readText()
        val directExec = source.indexOf("getuid() == kShellUid")
        val fork = source.indexOf("fork()")
        assertTrue(
            "已是 shell 身份必须直接 exec 不 fork，否则 Shizuku 探活追踪的是 launcher 而非服务进程",
            directExec >= 0 && fork >= 0 && directExec < fork,
        )
    }

    @Test
    fun bindUserServiceStackIsGone() {
        val offenders = TestSources.resolveDir("src/main/java/com/aliothmoon/maafw/privileged")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("bindUserService") ||
                    text.contains("addUserService") ||
                    text.contains("ShizukuUserServiceBinder")
            }
            .map { it.name }
            .toList()
        assertTrue(
            "Shizuku manager App 不得再进入服务拉起链路: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun managerTimeoutsFollowConnector() {
        val source = TestSources.resolve("src/main/java/com/aliothmoon/maafw/privileged/RemoteServiceManager.kt").readText()
        assertTrue(
            "兜底超时必须由连接器 worstCaseConnectMs 推算",
            source.contains("worstCaseConnectMs"),
        )
        assertFalse(
            "兜底超时不得再手写常量，否则连接器超时一改就失配",
            source.contains("CONNECT_TIMEOUT_MS ="),
        )
        assertFalse(
            "getInstance / useService 默认等待不得写死数字：先于连接器超时就只剩裸超时、根因被截胡",
            Regex("""timeoutMs: Long = \d""").containsMatchIn(source),
        )
    }
}
