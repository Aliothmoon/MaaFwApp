package com.aliothmoon.maafw.remote.internal

import android.annotation.SuppressLint
import android.os.Build
import android.os.IBinder
import com.aliothmoon.maafw.third.Command
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import java.lang.reflect.Method

/**
 * 提权进程内断/复 xmsf 网络（移植自 MaaMeow）：HyperOS 焦点岛云端鉴权为 fail-open，
 * 断网让鉴权拿 -400(No network) 即视为通过；联网被查到 -200 会移除岛
 *
 * 两级后端，shell 都能用：系统服务 binder（OEM deny 链）→ cmd connectivity。特权进程一律降到 shell，
 * 以前只有 root 才走得到的 iptables 那档删了；旧版 root 进程插下的 iptables 规则 shell 删不掉，重启才消失
 * 规则落在系统侧 netd，进程死亡不会自动失效：
 * 断网时记下实际生效的后端，恢复只回滚它；新实例启动后 [ensureRestored] 做一次全量兜底，
 * 兜底还没跑完就来了断网请求时先补跑它，免得兜底晚到把刚断的网又放开
 */
object XmsfFirewall {
    private const val TAG = "XmsfFirewall"
    const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    private const val RULE_DEFAULT = 0
    private const val RULE_DENY = 2

    // 对应 netd FIREWALL_CHAIN_OEM_DENY_3/2/1
    private val OEM_DENY_CHAINS = intArrayOf(9, 8, 7)

    private val SERVICE_BACKENDS = arrayOf(
        ServiceBackend("connectivity", $$"android.net.IConnectivityManager$Stub"),
        ServiceBackend("network_management", $$"android.os.INetworkManagementService$Stub"),
    )

    private enum class Backend { BINDER, CMD }

    private val applied = mutableSetOf<Backend>()

    private var bootRestored = false

    @Synchronized
    fun setNetworkingEnabled(packageName: String, enabled: Boolean): Boolean {
        if (packageName != XMSF_PACKAGE) {
            Ln.w("$TAG: refuse to toggle networking for $packageName")
            return false
        }
        return if (enabled) restore(full = false) else cut()
    }

    /** 新实例启动兜底：清上一实例可能残留的断网规则，本进程无记录故走全量；只跑一次 */
    @Synchronized
    fun ensureRestored() {
        if (bootRestored) return
        bootRestored = true
        restore(full = true)
    }

    @Synchronized
    fun restoreIfNeeded() {
        restore(full = true)
    }

    private fun cut(): Boolean {
        if (applied.isNotEmpty()) return true
        ensureRestored()
        val uid = resolveUid() ?: return false
        var ok = false
        if (binderApply(uid, deny = true) && currentState() == STATE_DENY) {
            applied += Backend.BINDER
            ok = true
        }
        if (!ok && cmdApply(deny = true)) {
            applied += Backend.CMD
            ok = currentState() == STATE_DENY
        }
        if (!ok) {
            // 没断成也要把已下发的痕迹清掉，别留半截规则；两级都失效时留证据
            Ln.w("$TAG: cut xmsf failed sdk=${Build.VERSION.SDK_INT}")
            restore(full = false)
        }
        Ln.i("$TAG: cut xmsf uid=$uid ok=$ok via=$applied")
        return ok
    }

    private fun restore(full: Boolean): Boolean {
        val targets = if (full) Backend.entries.toSet() else applied.toSet()
        // 常态快路径：本进程没下发过规则且系统侧放行
        if (targets.isEmpty() && currentState() != STATE_DENY) {
            Ln.d("$TAG: nothing to restore")
            return true
        }
        val uid = resolveUid()
        if (uid == null) {
            Ln.w("$TAG: restore skipped, uid unresolved")
            return false
        }
        if (Backend.BINDER in targets) binderApply(uid, deny = false)
        if (Backend.CMD in targets) cmdApply(deny = false)
        applied.clear()
        val restored = currentState() != STATE_DENY
        Ln.i("$TAG: restore xmsf uid=$uid full=$full restored=$restored")
        return restored
    }

    /** allow / deny / null(查询不可用) */
    private fun currentState(): String? = runCatching {
        Command.execReadLine(
            "cmd", "connectivity", "get-package-networking-enabled", XMSF_PACKAGE,
        )?.trim()?.lowercase()
    }.getOrNull()?.let { state ->
        when {
            state.endsWith(STATE_ALLOW) -> STATE_ALLOW
            state.endsWith(STATE_DENY) -> STATE_DENY
            else -> null
        }
    }

    private class ServiceBackend(val serviceName: String, val stubClass: String)

    private fun binderApply(uid: Int, deny: Boolean): Boolean {
        for (backend in SERVICE_BACKENDS) {
            val proxy = serviceProxy(backend) ?: continue
            if (applyFirewallRule(proxy, uid, deny)) return true
        }
        return false
    }

    // 跑在特权进程里，ServiceManager 与 Stub 只能反射拿
    @SuppressLint("PrivateApi")
    private fun serviceProxy(backend: ServiceBackend): Any? {
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, backend.serviceName) as? IBinder
                ?: return null
            Class.forName(backend.stubClass)
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Throwable) {
            Ln.w("$TAG: proxy ${backend.serviceName} failed: ${e.message}")
            null
        }
    }

    private fun applyFirewallRule(proxy: Any, uid: Int, deny: Boolean): Boolean {
        val rule = if (deny) RULE_DENY else RULE_DEFAULT
        for (chain in OEM_DENY_CHAINS) {
            if (deny) invoke(proxy, listOf("setFirewallChainEnabled"), chain, true)
            if (invoke(
                    proxy,
                    listOf("setUidFirewallRule", "setFirewallUidRule"),
                    chain,
                    uid,
                    rule
                )
            ) return true
            if (invoke(
                    proxy,
                    listOf("setUidFirewallRules", "setFirewallUidRules"),
                    chain, intArrayOf(uid), intArrayOf(rule),
                )
            ) {
                return true
            }
            // 不用 replaceFirewallChain：整链替换会抹掉其它应用的规则且无法还原
        }
        // 旧式 netd 全局链兜底
        if (invoke(proxy, listOf("setFirewallEnabled"), true)) {
            if (invoke(
                    proxy,
                    listOf("setUidFirewallRule", "setFirewallUidRule"),
                    uid,
                    deny
                )
            ) return true
            if (invoke(
                    proxy,
                    listOf("setUidFirewallRule", "setFirewallUidRule"),
                    uid,
                    rule
                )
            ) return true
        }
        return false
    }

    private fun invoke(proxy: Any, names: List<String>, vararg args: Any): Boolean {
        val methods = proxy.javaClass.methods
            .filter { it.name in names && it.parameterCount == args.size }
        for (method in methods) {
            val adapted = runCatching { adaptArgs(method, args) }.getOrNull() ?: continue
            val ok = runCatching {
                method.isAccessible = true
                method.invoke(proxy, *adapted)
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    private fun adaptArgs(method: Method, args: Array<out Any>): Array<Any> =
        Array(args.size) { i ->
            val param = method.parameterTypes[i]
            when (param) {
                Int::class.javaPrimitiveType -> (args[i] as Number).toInt()
                Boolean::class.javaPrimitiveType ->
                    if (args[i] is Boolean) args[i] else (args[i] as Number).toInt() != 0

                else -> args[i]
            }
        }

    private fun cmdApply(deny: Boolean): Boolean = try {
        if (deny) {
            Command.exec("cmd", "connectivity", "set-chain3-enabled", "true")
        }
        Command.exec(
            "cmd", "connectivity", "set-package-networking-enabled",
            if (deny) "false" else "true", XMSF_PACKAGE,
        )
        true
    } catch (e: Throwable) {
        Ln.w("$TAG: cmd connectivity failed: ${e.message}")
        false
    }

    private fun resolveUid(): Int? = runCatching {
        FakeContext.get().packageManager.getApplicationInfo(XMSF_PACKAGE, 0).uid
    }.onFailure {
        Ln.w("$TAG: resolve $XMSF_PACKAGE uid failed: ${it.message}")
    }.getOrNull()

    private const val STATE_ALLOW = "allow"
    private const val STATE_DENY = "deny"
}
