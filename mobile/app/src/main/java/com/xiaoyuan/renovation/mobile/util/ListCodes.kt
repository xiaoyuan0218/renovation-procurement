package com.xiaoyuan.renovation.mobile.util

/**
 * 清单编号：给每份清单一个稳定、好念的短码。
 *
 * 名字会被改、上传时重名会被加后缀（「采购清单」到服务器上可能变成「采购清单 2」），
 * 编号不会变。手机上那份和服务器上那份编号相同，一眼就能确认是同一份。
 *
 * 字母表去掉了容易看错的 0/O/1/I/L —— 这串码是要拿眼睛对、甚至口头念的。
 * 与后端 `services/codes.py` 保持同一套规则。
 */
object ListCodes {

    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8

    fun new(): String = (1..LENGTH).map { ALPHABET.random() }.joinToString("")

    /** 把外部传来的编号收拾成标准样子；不合法返回空串（调用方重新发一个）。 */
    fun normalize(raw: String?): String {
        val text = raw.orEmpty().uppercase().filter { it in ALPHABET }
        return if (text.length == LENGTH) text else ""
    }
}
