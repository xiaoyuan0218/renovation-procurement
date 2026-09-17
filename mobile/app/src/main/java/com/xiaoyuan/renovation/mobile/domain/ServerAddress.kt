package com.xiaoyuan.renovation.mobile.domain

/** 服务器地址解析结果。 */
sealed interface AddressParse {
    /** 输入为空 */
    data object Empty : AddressParse

    /** 输入无法识别为服务器地址 */
    data class Invalid(val reason: String) : AddressParse

    /** 解析成功，normalized 为最终会使用的地址 */
    data class Ok(val normalized: String, val host: String, val port: Int, val scheme: String) : AddressParse
}

/**
 * 把一个随手输入的服务器地址整理成可用的 baseUrl。
 *
 * 规则（对用户完全透明 —— 界面会实时回显最终地址）：
 * - 省略协议时补 `http://`（自托管后端通常是局域网明文）
 * - 只填主机、没写端口时补默认端口 8000（本项目后端的默认端口）
 * - 去掉末尾多余的 `/`
 */
object ServerAddress {

    const val DEFAULT_PORT = 8000

    private val HOST_PATTERN = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$")

    fun parse(raw: String): AddressParse {
        val input = raw.trim()
        if (input.isEmpty()) return AddressParse.Empty

        val withScheme = if (input.startsWith("http://", true) || input.startsWith("https://", true)) {
            input
        } else {
            "http://$input"
        }

        val scheme = withScheme.substringBefore("://").lowercase()
        var rest = withScheme.substringAfter("://")

        // 去掉用户信息片段（本后端无鉴权，容错处理）
        rest = rest.substringAfter('@', rest)

        val pathPart = rest.substringAfter('/', "")
        val authority = rest.substringBefore('/')

        if (authority.isEmpty()) {
            return AddressParse.Invalid("地址里没有主机名")
        }

        val hostPart = authority.substringBeforeLast(':', authority)
        val portPart = if (authority.contains(':')) authority.substringAfterLast(':') else ""

        // IPv6 / 非法端口
        if (hostPart.isEmpty()) return AddressParse.Invalid("地址里没有主机名")
        if (portPart.isNotEmpty() && portPart.toIntOrNull() == null) {
            return AddressParse.Invalid("端口「$portPart」不是数字")
        }
        val port = portPart.toIntOrNull() ?: DEFAULT_PORT
        if (port !in 1..65535) return AddressParse.Invalid("端口 $port 超出范围")

        if (!HOST_PATTERN.matches(hostPart) && !hostPart.startsWith("[") && hostPart != "localhost") {
            return AddressParse.Invalid("主机名「$hostPart」看起来不对")
        }

        val path = pathPart.trim('/')
        val normalized = buildString {
            append(scheme).append("://").append(hostPart).append(':').append(port)
            if (path.isNotEmpty()) append('/').append(path)
        }
        return AddressParse.Ok(normalized, hostPart, port, scheme)
    }

    /** 供界面展示的友好说明。 */
    fun describe(raw: String): String = when (val r = parse(raw)) {
        AddressParse.Empty -> "请输入服务器地址，例如 192.168.1.9:8000"
        is AddressParse.Invalid -> r.reason
        is AddressParse.Ok -> "将连接到 ${r.normalized}"
    }
}
