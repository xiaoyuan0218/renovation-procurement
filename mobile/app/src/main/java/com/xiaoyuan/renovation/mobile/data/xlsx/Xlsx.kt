package com.xiaoyuan.renovation.mobile.data.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 最小可用的 xlsx 读写，只够本应用自己这套表格用。
 *
 * 不引 Apache POI（安卓上太重），也不做样式、公式、合并单元格 —— 只要
 * **和网页版/网络版导出的文件能互相认**就够了：导入端只认「物料汇总」和
 * 「布点明细」两张平表，导出端按同一套列名写。
 *
 * 写的时候用内联字符串（不用 sharedStrings 表），读的时候两种都认 ——
 * 文件被 Excel 打开另存过之后，Excel 会改成 sharedStrings。
 */
object Xlsx {

    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    /* ============================================================
       写
       ============================================================ */

    /** [sheets] 是「表名 → 若干行」，每行是若干单元格；第一行由调用方给表头。 */
    fun write(sheets: List<Pair<String, List<List<Any?>>>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(sheets.size))
            zip.put("_rels/.rels", rootRels())
            zip.put("xl/workbook.xml", workbookXml(sheets.map { it.first }))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            sheets.forEachIndexed { index, (_, rows) ->
                zip.put("xl/worksheets/sheet${index + 1}.xml", sheetXml(rows))
            }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.put(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbookXml(names: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="$NS" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("<sheets>")
        names.forEachIndexed { index, name ->
            append("""<sheet name="${escape(name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun sheetXml(rows: List<List<Any?>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="$NS"><sheetData>""")
        rows.forEachIndexed { rowIndex, row ->
            append("""<row r="${rowIndex + 1}">""")
            row.forEachIndexed { colIndex, value ->
                val ref = columnName(colIndex) + (rowIndex + 1)
                when (value) {
                    null, "" -> Unit   // 空单元格不用写
                    is Number -> append("""<c r="$ref"><v>$value</v></c>""")
                    else -> append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escape(value.toString())}</t></is></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /* ============================================================
       读
       ============================================================ */

    /** 返回「表名 → 行」，单元格都是字符串；空单元格是空串。 */
    fun read(bytes: ByteArray): Map<String, List<List<String>>> {
        val entries = unzip(bytes)
        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()

        // workbook.xml 里的 sheet 名与顺序，对应 rels 里的 worksheets/sheetN.xml
        val names = parseSheetNames(entries["xl/workbook.xml"] ?: return emptyMap())
        val rels = parseWorkbookRels(entries["xl/_rels/workbook.xml.rels"].orEmpty())

        val result = LinkedHashMap<String, List<List<String>>>()
        names.forEach { (name, relId) ->
            val path = rels[relId] ?: return@forEach
            val xml = entries[path] ?: return@forEach
            result[name] = parseSheet(xml, shared)
        }
        return result
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun document(xml: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            .documentElement

    private fun parseSharedStrings(xml: String): List<String> {
        val root = document(xml)
        val items = root.getElementsByTagName("si")
        return (0 until items.length).map { i ->
            val si = items.item(i) as Element
            val texts = si.getElementsByTagName("t")
            (0 until texts.length).joinToString("") {
                (texts.item(it) as Element).textContent.orEmpty()
            }
        }
    }

    /** 表名 → rId。 */
    private fun parseSheetNames(xml: String): List<Pair<String, String>> {
        val root = document(xml)
        val sheets = root.getElementsByTagName("sheet")
        return (0 until sheets.length).map { i ->
            val sheet = sheets.item(i) as Element
            sheet.getAttribute("name") to sheet.getAttribute("r:id")
        }
    }

    /** rId → zip 里的路径。 */
    private fun parseWorkbookRels(xml: String): Map<String, String> {
        if (xml.isBlank()) return emptyMap()
        val root = document(xml)
        val rels = root.getElementsByTagName("Relationship")
        val out = mutableMapOf<String, String>()
        for (i in 0 until rels.length) {
            val rel = rels.item(i) as Element
            val target = rel.getAttribute("Target").removePrefix("/")
            if (target.contains("worksheets/")) {
                out[rel.getAttribute("Id")] = if (target.startsWith("xl/")) target else "xl/$target"
            }
        }
        return out
    }

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val root = document(xml)
        val rows = root.getElementsByTagName("row")
        val out = mutableListOf<List<String>>()
        for (r in 0 until rows.length) {
            val row = rows.item(r) as Element
            val cells = row.getElementsByTagName("c")
            val line = mutableListOf<String>()
            for (c in 0 until cells.length) {
                val cell = cells.item(c) as Element
                val column = columnIndex(cell.getAttribute("r"))
                while (line.size < column) line.add("")
                line.add(cellValue(cell, shared))
            }
            out.add(line)
        }
        return out
    }

    private fun cellValue(cell: Element, shared: List<String>): String {
        val type = cell.getAttribute("t")
        return when (type) {
            "s" -> {
                val index = cell.getElementsByTagName("v").item(0)
                    ?.textContent?.trim()?.toIntOrNull()
                index?.let { shared.getOrNull(it) }.orEmpty()
            }

            "inlineStr" -> cell.getElementsByTagName("t").let { texts ->
                (0 until texts.length).joinToString("") {
                    (texts.item(it) as Element).textContent.orEmpty()
                }
            }

            else -> cell.getElementsByTagName("v").item(0)?.textContent?.trim().orEmpty()
        }
    }

    /** "C7" → 2（从 0 数）。 */
    private fun columnIndex(ref: String): Int {
        var value = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            value = value * 26 + (ch - 'A' + 1)
        }
        return (value - 1).coerceAtLeast(0)
    }
}
