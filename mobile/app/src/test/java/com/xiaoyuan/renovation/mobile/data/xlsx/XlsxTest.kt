package com.xiaoyuan.renovation.mobile.data.xlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * xlsx 读写的自检，外加一条硬验证：**读后端导出的文件**。
 *
 * 三个端（网页、网络版安卓、这台手机）共用同一套表格格式，所以手机导出的表
 * 电脑上要能导进去、电脑导出的表手机也要认得。`backend_export.xlsx` 是
 * 后端 `services/excel_io.export_xlsx` 真跑出来的产物（造了几条数据），
 * 存在 test resources 里当对照。
 */
class XlsxTest {

    @Test
    fun `写出来再读回来，内容和顺序都不变`() {
        val sheets = listOf(
            "物料汇总" to listOf(
                listOf("类目", "物料名称", "单位", "数量", "单价"),
                listOf("照明", "筒灯", "个", 6.0, 30.5),
                listOf("", "开关", "个", 2.0, 12.0),
            ),
        )

        val back = Xlsx.read(Xlsx.write(sheets))

        val rows = back["物料汇总"]
        assertNotNull("表名要能读回来", rows)
        assertEquals(listOf("类目", "物料名称", "单位", "数量", "单价"), rows!![0])
        assertEquals(listOf("照明", "筒灯", "个", "6.0", "30.5"), rows[1])
        assertEquals(listOf("", "开关", "个", "2.0", "12.0"), rows[2])
    }

    @Test
    fun `多张表各自读得回来`() {
        val sheets = listOf(
            "物料汇总" to listOf(listOf("物料名称"), listOf("筒灯")),
            "布点明细" to listOf(listOf("物料名称", "房间"), listOf("筒灯", "客厅")),
            "采购记录" to listOf(listOf("物料名称", "实付金额"), listOf("筒灯", 180.5)),
            "额外费用" to listOf(listOf("类型", "金额"), listOf("运费", 80.0)),
        )

        val back = Xlsx.read(Xlsx.write(sheets))

        assertEquals(setOf("物料汇总", "布点明细", "采购记录", "额外费用"), back.keys)
        assertEquals(listOf("筒灯", "客厅"), back["布点明细"]!![1])
    }

    @Test
    fun `特殊字符与空单元格不捣乱`() {
        val sheets = listOf(
            "对照" to listOf(
                listOf("名称", "备注", "金额"),
                listOf("灯 & 插座 <A>", "带\"引号\"的备注", 0.0),
                listOf("只有名字", "", ""),
            ),
        )

        val rows = Xlsx.read(Xlsx.write(sheets))["对照"]!!

        assertEquals("灯 & 插座 <A>", rows[1][0])
        assertEquals("带\"引号\"的备注", rows[1][1])
        assertEquals("0.0", rows[1][2])
        assertEquals("只有名字", rows[2][0])
    }

    @Test
    fun `后端导出的文件认得出来`() {
        val stream = javaClass.getResourceAsStream("/backend_export.xlsx")
            ?: error("找不到 backend_export.xlsx（后端导出格式的对照文件）")
        val sheets = Xlsx.read(stream.readBytes())

        assertTrue("四页都要在", sheets.keys.containsAll(
            setOf("物料汇总", "布点明细", "采购记录", "额外费用"),
        ))

        val items = sheets["物料汇总"]!!
        val header = items[0]
        // 列名要和手机端导出时写的完全一致，不然两个方向就对不上了
        assertEquals(
            listOf(
                "类目", "物料名称", "品牌", "型号", "单位", "数量", "单价", "优惠单价",
                "日常价", "实付数量", "实付金额", "未付数量", "未付金额",
                "日常价未付", "实际优惠", "日常价优惠",
                "已购", "备注", "物料ID",
            ),
            header,
        )
        val row = items[1]
        assertEquals("照明", row[header.indexOf("类目")])
        assertEquals("筒灯", row[header.indexOf("物料名称")])
        assertEquals("松下", row[header.indexOf("品牌")])
        // 数字按值比：openpyxl 把 6.0 写成 "6"，两家写法不同但值一样
        assertEquals(30.5, row[header.indexOf("单价")].toDouble(), 1e-9)
        assertEquals(6.0, row[header.indexOf("数量")].toDouble(), 1e-9)

        val allocs = sheets["布点明细"]!!
        val allocHeader = allocs[0]
        assertEquals(listOf("物料名称", "房间", "数量", "单价", "备注", "物料ID"), allocHeader)
        assertEquals("客厅", allocs[1][allocHeader.indexOf("房间")])

        val records = sheets["采购记录"]!!
        val recordHeader = records[0]
        assertEquals("京东", records[1][recordHeader.indexOf("商家")])
        assertEquals(180.5, records[1][recordHeader.indexOf("实付金额")].toDouble(), 1e-9)

        val expenses = sheets["额外费用"]!!
        assertEquals(listOf("类型", "金额", "日期", "商家", "订单号", "备注"), expenses[0])
        assertEquals("运费", expenses[1][0])
    }
}
