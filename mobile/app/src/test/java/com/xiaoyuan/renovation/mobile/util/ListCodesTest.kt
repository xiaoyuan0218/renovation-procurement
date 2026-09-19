package com.xiaoyuan.renovation.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单编号的规则表。
 *
 * 编号是清单的身份 —— 手机和服务器"是不是同一份"全靠它认，认错了就是
 * 把两份清单混在一起。所以这里把"什么样的输入算合法、什么样的算废码"
 * 一条条钉死，与后端 `services/codes.py` 是同一套规则。
 */
class ListCodesTest {

    /** 生成出来的码必须落在这个字母表里：没有 0/O/1/I/L。 */
    private val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    @Test
    fun `新编号是 8 位、全在字母表内`() {
        repeat(200) {
            val code = ListCodes.new()
            assertEquals("长度固定 8 位", 8, code.length)
            assertTrue("只含 $alphabet 里的字符：$code", code.all { it in alphabet })
        }
    }

    @Test
    fun `新编号不重复`() {
        // 31^8 的空间里抽 500 个，撞车概率低到可以当断言用；真撞了说明随机源坏了
        val codes = (1..500).map { ListCodes.new() }.toSet()
        assertEquals(500, codes.size)
    }

    @Test
    fun `新编号不含容易看错的字符`() {
        repeat(200) {
            val code = ListCodes.new()
            for (bad in listOf('0', 'O', '1', 'I', 'L')) {
                assertTrue("不该出现 $bad：$code", bad !in code)
            }
        }
    }

    @Test
    fun `合法编号原样通过`() {
        assertEquals("ABCDEFGH", ListCodes.normalize("ABCDEFGH"))
        assertEquals("23456789", ListCodes.normalize("23456789"))
    }

    @Test
    fun `小写会被转成大写`() {
        assertEquals("ABCDEFGH", ListCodes.normalize("abcdefgh"))
    }

    @Test
    fun `首尾空白会被忽略`() {
        // 从聊天记录、纸上抄过来时常带上空格
        assertEquals("ABCDEFGH", ListCodes.normalize("  ABCDEFGH  "))
    }

    @Test
    fun `容易看错的字符被丢掉，于是长度不够、判为废码`() {
        // 0 是 O 的误抄、1 是 I/L 的误抄 —— 这类字符根本不进字母表，
        // 过滤后剩 7 位，长度不对，宁可当废码让调用方重发一个
        assertEquals("", ListCodes.normalize("ABC0EFGH"))
        assertEquals("", ListCodes.normalize("ABCDEFG1"))
        assertEquals("", ListCodes.normalize("ABCDEFGI"))
        assertEquals("", ListCodes.normalize("ABCDEFGL"))
        assertEquals("", ListCodes.normalize("ABCDEFGO"))
    }

    @Test
    fun `长度不对就是废码`() {
        assertEquals("", ListCodes.normalize("ABCDEFG"))    // 7 位
        assertEquals("", ListCodes.normalize("ABCDEFGHJ"))  // 9 位
        assertEquals("", ListCodes.normalize(""))
        assertEquals("", ListCodes.normalize("   "))
    }

    @Test
    fun `空值和 null 都是废码`() {
        assertEquals("", ListCodes.normalize(null))
        assertEquals("", ListCodes.normalize(""))
    }

    @Test
    fun `夹了别的字符会让长度对不上、判为废码`() {
        // "ABCD-EFGH" 过滤掉连字符后正好 8 位 —— 这是设计上的取舍：
        // 分隔符被当作噪音丢掉，剩下的仍是合法码，用户可以照念照抄
        assertEquals("ABCDEFGH", ListCodes.normalize("ABCD-EFGH"))
        // 但夹了不在字母表里的字母就少一位，判废
        assertEquals("", ListCodes.normalize("ABCDEFG-"))
    }

    @Test
    fun `字母表里没有 0 和 1`() {
        // 手抄编号时最容易写错的两位；漏进来就会变成"认不出"或"认成另一份"
        assertTrue('0' !in alphabet)
        assertTrue('1' !in alphabet)
        assertEquals("", ListCodes.normalize("00000000"))
        assertEquals("", ListCodes.normalize("11111111"))
    }

    @Test
    fun `两份不同编号不会被认成同一份`() {
        val a = ListCodes.new()
        val b = ListCodes.new()
        assertNotEquals(ListCodes.normalize(a), ListCodes.normalize(b))
    }
}
