package com.aliothmoon.maafw.update

import timber.log.Timber

/**
 * 更新源分发：按所选源检查与解析，源失败原样返回，由文案引导用户自己切源
 *
 * 唯一的交叉是 GitHub 源先问一次 Mirror酱 定版本号，见 [GitHubUpdateClient]
 */
class UpdateService(
    clients: Collection<UpdateSourceClient>,
) {

    private val clientsBySource = clients.associateBy(UpdateSourceClient::source)

    suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
        val source = request.source
        Timber.tag("UpdateCheck").i(
            "source=%s currentVersion=%s channel=%s",
            source, request.currentVersion, request.channel,
        )
        return clientsBySource.getValue(source).check(request)
    }

    suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult {
        Timber.tag("UpdateResolve").i("source=%s channel=%s", request.source, request.channel)
        return clientsBySource.getValue(request.source).resolve(request)
    }
}
