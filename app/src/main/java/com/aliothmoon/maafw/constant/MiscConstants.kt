package com.aliothmoon.maafw.constant

import com.aliothmoon.maafw.BuildConfig

object MiscConstants {
    /** 应用身份标识，只给 MirrorChyan 的 user_agent query 识别接入应用用 */
    const val UA = "MaaFwApp/${BuildConfig.VERSION_NAME} Android"

    /** 非 MirrorChyan 的请求不暴露应用身份，统一伪装成常见移动端浏览器 */
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"
}
