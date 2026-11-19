package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 更新源分发：按所选源检查与解析，源失败原样返回，由文案引导用户自己切源
 *
 * 唯一的交叉是 GitHub 源先问一次 Mirror酱 定版本号，见 [GitHubUpdateClient]
 *
 * 整体切到 Default：设置页在 Main 上直接调，GitHub 源最多三页 × 100 条 release 的解析、正文替换与挑包
 * 都是 CPU 活，留在调用方线程就卡界面
 */
class UpdateService(
    clients: Collection<UpdateSourceClient>,
) {

    private val clientsBySource = clients.associateBy(UpdateSourceClient::source)

    /** 源里能验 CDK 的那个；没有就一律判不出 */
    private val cdkVerifier = clients.filterIsInstance<CdkVerifier>().singleOrNull()

    suspend fun verifyCdk(request: CdkVerifyRequest): CdkVerification = withContext(MaaDispatchers.Default) {
        cdkVerifier?.verifyCdk(request) ?: CdkVerification.Unverified
    }

    suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
        val source = request.source
        Timber.tag("UpdateCheck").i(
            "source=%s currentVersion=%s channel=%s",
            source, request.currentVersion, request.channel,
        )
        return withContext(MaaDispatchers.Default) { clientsBySource.getValue(source).check(request) }
    }

    suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult {
        Timber.tag("UpdateResolve").i("source=%s channel=%s", request.source, request.channel)
        return withContext(MaaDispatchers.Default) { clientsBySource.getValue(request.source).resolve(request) }
    }
}
