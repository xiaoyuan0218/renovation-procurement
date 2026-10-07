package com.xiaoyuan.renovation.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日期读出口径。
 *
 * 重点是 Excel 序列号：用 Excel 打开文件、把日期列存成真日期格式之后，单元格
 * 就是一串天数（1900 起算）。后端用 openpyxl 能识别成真日期，手机这边是自己写的
 * 读取器、只拿得到文本 —— 不在这里补换算的话，同一份文件在电脑上导入是
 * 2024-07-27，在手机上就成了一串数字。
 */
class DateReadTest {

    @Test
    fun `Excel 序列号读成日期`() {
        assertEquals("2024-07-27", LocalCompute.forRead("45500"))
        assertEquals("2025-01-01", LocalCompute.forRead("45658"))
    }

    @Test
    fun `常见脏格式归一`() {
        assertEquals("2026-09-14", LocalCompute.forRead("2026-09-14 00:00:00"))
        assertEquals("2026-09-14", LocalCompute.forRead("2026/9/14"))
        assertEquals("2026-09-14", LocalCompute.forRead("2026.9.14"))
        assertEquals("", LocalCompute.forRead(null))
        assertEquals("", LocalCompute.forRead("  "))
    }

    @Test
    fun `认不出来的原样返回`() {
        assertEquals("不是日期", LocalCompute.forRead("不是日期"))
    }

    @Test
    fun `普通大数字不会被当成日期`() {
        // 超出序列号合理区间（> 200000）的原样返回，别把数量之类的数字当日期
        assertEquals("20260914", LocalCompute.forRead("20260914"))
    }
}
