package com.aliothmoon.maafw.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Root 拉起的命令：进 init 的挂载命名空间、修回 root 建的目录，两者都要能退回原样 */
class RootSpawnCommandTest {

    @Test
    fun suSpawner_entersInitMountNamespace_withFallback() {
        val command = SuSpawner.wrapCommand("/x/liblauncher.so", "'/x/liblauncher.so' --token='t'")
        assertTrue(command.startsWith("if nsenter -t 1 -m -- true 2>/dev/null; then "))
        assertTrue(command.contains("nsenter -t 1 -m -- '/x/liblauncher.so' --token='t' >/dev/null 2>&1 &"))
        // nsenter 不可用时照旧后台拉起
        assertTrue(command.contains("else '/x/liblauncher.so' --token='t' >/dev/null 2>&1 & fi"))
    }

    @Test
    fun ownershipRepair_onlyRunsAsRoot_andQuotesPath() {
        val command = shellOwnershipRepairCommand("/data/local/tmp/it's")
        assertTrue(command.startsWith("[ \"$(id -u)\" = 0 ] && "))
        assertTrue(command.contains("find '/data/local/tmp/it'\"'\"'s' ! -user 2000 -exec chown -h 2000:2000 {} +"))
    }

    @Test
    fun externalFilesRepair_prefersUnderlyingMediaDir_repairsRootOwnedWithGroupWrite_andSupportsMultiUser() {
        val mainUser = externalFilesRepairCommand(
            externalFilesDir = "/storage/emulated/0/Android/data/com.example.app/files",
            packageName = "com.example.app",
            appUid = 10343,
        )
        assertTrue(mainUser.startsWith("[ \"$(id -u)\" = 0 ] && { "))
        // 优先走 /data/media 绕开 FUSE 对 chown 的拦截，不存在时退回 AppPaths.ROOT
        assertTrue(mainUser.contains("d='/data/media/0/Android/data/com.example.app/files'; [ -d \"\$d\" ] || d='/storage/emulated/0/Android/data/com.example.app/files'"))
        // 只动 uid 0 的遗留条目：补组写位（不碰软链）再改属主回 appUid:<目录组>
        assertTrue(mainUser.contains("find \"\$d\" -user 0 ! -type l -exec chmod ug+rwX {} +"))
        assertTrue(mainUser.contains("find \"\$d\" -user 0 -exec chown -h \"10343:\$g\" {} +"))

        val workProfile = externalFilesRepairCommand(
            externalFilesDir = "/storage/emulated/999/Android/data/com.example.app/files",
            packageName = "com.example.app",
            appUid = 99910343,
        )
        assertTrue(workProfile.contains("d='/data/media/999/Android/data/com.example.app/files'"))
        assertTrue(workProfile.contains("chown -h \"99910343:\$g\""))
    }

    @Test
    fun findProcesses_leavesShellExpansionToShell() {
        assertEquals(
            "p=$(pidof 'pkg:svc') && ps -o PID,ETIME -p \${p// /,}",
            findProcessesCommand("pkg:svc"),
        )
    }

    @Test
    fun elevatedProcessName_carriesUserIdOutsideMainUser() {
        assertEquals("pkg:svc", elevatedProcessName("pkg", "svc", 10343))
        // 分身与主用户同名会互相当残留杀
        assertEquals("pkg:svc_u999", elevatedProcessName("pkg", "svc", 99910343))
    }

    @Test
    fun parseProcesses_skipsHeaderAndKeepsElapsed() {
        assertEquals(
            listOf(ResidualProcess(42, "00:17"), ResidualProcess(7, "1-02:03:04")),
            parseProcesses(listOf("  PID     ELAPSED", "   42       00:17", "    7  1-02:03:04", "")),
        )
    }
}
