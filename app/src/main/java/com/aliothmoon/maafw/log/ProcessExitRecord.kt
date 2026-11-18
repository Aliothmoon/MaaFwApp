package com.aliothmoon.maafw.log

import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 系统记下的一次进程退出；App 查自己、特权进程查目标应用，两边共用
 *
 * 跨 AIDL 走 JSON 不走 Parcelable：app 升级而旧特权进程还活着时，Parcelable 跟着类布局走会解不出来
 */
@Serializable
data class ProcessExitRecord(
    val timestamp: Long,
    val pid: Int,
    val processName: String,
    val reason: Int,
    val reasonName: String,
    val detail: Int,
    val detailName: String,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
) {

    internal data class Names(val reasonName: String, val detail: Int, val detailName: String)

    companion object {
        private val REASON = Regex("""\breason=-?\d+ \(([^)]*)\)""")
        private val DETAIL = Regex("""\bsubreason=(-?\d+) \(([^)]*)\)""")

        @RequiresApi(Build.VERSION_CODES.R)
        fun from(info: ApplicationExitInfo): ProcessExitRecord {
            val names = parseNames(info.toString())
            return ProcessExitRecord(
                timestamp = info.timestamp,
                pid = info.pid,
                processName = info.processName.orEmpty(),
                reason = info.reason,
                reasonName = names.reasonName,
                detail = names.detail,
                detailName = names.detailName,
                status = info.status,
                importance = info.importance,
                pssKb = info.pss,
                rssKb = info.rss,
                description = info.description,
            )
        }

        // detail 即系统的 subreason，getter 是隐藏 API；部分版本 toString 里带，特权进程另行反射补全
        internal fun parseNames(raw: String): Names {
            val detail = DETAIL.find(raw)
            return Names(
                reasonName = REASON.find(raw)?.groupValues?.get(1).orEmpty(),
                detail = detail?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                detailName = detail?.groupValues?.get(2).orEmpty(),
            )
        }
    }
}

/** 两端共用的线格式：旧特权进程少字段、新的多字段都得解得开 */
private val wireJson = Json { ignoreUnknownKeys = true }

private val wireSerializer = ListSerializer(ProcessExitRecord.serializer())

fun List<ProcessExitRecord>.toWireJson(): String = wireJson.encodeToString(wireSerializer, this)

fun processExitRecordsFromWire(json: String): List<ProcessExitRecord> = wireJson.decodeFromString(wireSerializer, json)
