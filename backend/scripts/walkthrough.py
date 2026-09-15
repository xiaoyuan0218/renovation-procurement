"""Playwright 走查脚本：看板数字 → 物料清单 → 布点矩阵编辑/还原 → 导出回灌导入 → 手机宽度截图。

用法：项目根目录下  .venv/Scripts/python backend/scripts/walkthrough.py
前置：uvicorn 已在 127.0.0.1:8000 运行（托管前端 dist）。
"""

import os
import sys

BASE = os.environ.get("WALKTHROUGH_BASE", "http://127.0.0.1:8000")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))), "screenshots")
os.makedirs(OUT, exist_ok=True)

FAILED = []
CONSOLE_ERRORS = []


def check(name, cond, detail=""):
    mark = "PASS" if cond else "FAIL"
    print(f"[{mark}] {name}" + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        FAILED.append(name)


def run():
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1280, "height": 900})
        page = ctx.new_page()
        page.on("console", lambda m: CONSOLE_ERRORS.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: CONSOLE_ERRORS.append(str(e)))

        # ---------- 1. 看板 ----------
        # 只校验「界面数字与接口一致」，不绑定任何具体数据，方便在任意库上跑
        import json as _json
        import urllib.request as _urllib
        summary = _json.loads(_urllib.urlopen(BASE + "/api/summary").read())
        totals, item_count = summary["totals"], summary["totals"]["item_count"]
        page.goto(BASE, wait_until="networkidle")
        body = page.inner_text("body")
        for label, key in (("原价合计", "list_total"), ("日常价合计", "discount_total"),
                           ("已付", "paid_total"), ("未付", "unpaid_total")):
            shown = f"{totals[key]:,.2f}"
            check(f"看板-{label}与接口一致（{shown}）", shown in body)
        page.screenshot(path=os.path.join(OUT, "01-dashboard.png"), full_page=True)

        # ---------- 2. 物料清单 ----------
        page.click('.seg-item:has-text("物料清单")')
        page.wait_for_timeout(800)
        rows = page.locator(".el-table__body-wrapper .el-table__row")
        check(f"清单-物料行数 {item_count}", rows.count() == item_count, f"实际 {rows.count()}")
        body = page.inner_text("body")
        check("清单-筛选统计出现", "日常价" in body and "实付" in body)
        page.screenshot(path=os.path.join(OUT, "02-items.png"), full_page=True)

        # ---------- 2b. 编辑物料弹窗布局 ----------
        page.locator(".el-table__body-wrapper .el-table__row").first.locator(
            'button:has-text("编辑")').click()
        page.wait_for_timeout(600)
        page.screenshot(path=os.path.join(OUT, "02b-item-dialog.png"), full_page=True)
        page.locator('.el-dialog button:has-text("取消")').click()
        page.wait_for_timeout(400)

        # ---------- 3. 布点矩阵：给某个已有布点的单元格 +1 再改回 ----------
        # 全部数值运行时从页面读取，不依赖具体物料，任何数据集都能跑
        page.click('.seg-item:has-text("布点矩阵")')
        page.wait_for_timeout(800)
        page.screenshot(path=os.path.join(OUT, "03-matrix.png"), full_page=True)

        probe = page.evaluate("""() => {
          const rows = [...document.querySelectorAll('.el-table__body-wrapper .el-table__row')];
          for (let r = 0; r < rows.length; r++) {
            const tds = [...rows[r].querySelectorAll('td')];
            for (let c = 1; c < tds.length - 1; c++) {          // 跳过首列物料、末列合计
              const btn = tds[c].querySelector('button.cell');
              const qty = btn ? parseFloat(btn.innerText.trim()) : NaN;
              if (btn && qty > 0) {
                const total = parseFloat(tds[tds.length - 1].innerText.trim());
                return { row: r, col: c, qty, total,
                         item: tds[0].innerText.split(String.fromCharCode(10))[0],
                         room: document.querySelectorAll('.el-table__header-wrapper th')[c].innerText.trim() };
              }
            }
          }
          return null;
        }""")
        check("矩阵-找到有布点的单元格", probe is not None, "没有可用单元格（数据为空？）")

        if probe:
            row = page.locator(".el-table__body-wrapper .el-table__row").nth(probe["row"])
            expect_plus = probe["total"] + 1
            row.locator("td").nth(probe["col"]).locator("button").click()
            page.wait_for_timeout(400)
            dlg = page.locator(".el-dialog:visible")
            check("矩阵-弹窗标题", probe["item"] in dlg.inner_text() and probe["room"] in dlg.inner_text(),
                  dlg.inner_text()[:40])
            dlg.locator(".el-input-number input").first.fill(str(probe["qty"] + 1))
            dlg.locator('button:has-text("保存")').click()
            page.wait_for_timeout(800)
            after = page.locator(".el-table__body-wrapper .el-table__row").nth(
                probe["row"]).locator("td").nth(-1).inner_text()
            check(f"矩阵-改数量后合计 {expect_plus:g}",
                  after.strip().startswith(f"{expect_plus:g}"), after[:40])

            # 还原
            page.locator(".el-table__body-wrapper .el-table__row").nth(
                probe["row"]).locator("td").nth(probe["col"]).locator("button").click()
            page.wait_for_timeout(400)
            page.locator(".el-dialog:visible .el-input-number input").first.fill(str(probe["qty"]))
            page.locator('.el-dialog:visible button:has-text("保存")').click()
            page.wait_for_timeout(800)
            back = page.locator(".el-table__body-wrapper .el-table__row").nth(
                probe["row"]).locator("td").nth(-1).inner_text()
            check(f"矩阵-还原后合计 {probe['total']:g}",
                  back.strip().startswith(f"{probe['total']:g}"), back[:40])

        # ---------- 4. 导出 → 回灌导入（merge，验证 32 条全部匹配） ----------
        export_path = os.path.join(OUT, "export_roundtrip.xlsx")
        resp = ctx.request.get(f"{BASE}/api/export")
        check("导出-HTTP 200", resp.ok)
        with open(export_path, "wb") as f:
            f.write(resp.body())

        page.click('button:has-text("设置 / 数据")')
        page.wait_for_timeout(500)
        page.click('.el-tabs__item:has-text("数据备份")')
        page.wait_for_timeout(300)
        page.click('label:has-text("按名称合并")')
        page.set_input_files('input[type="file"]', export_path)
        page.wait_for_timeout(1500)
        body = page.inner_text("body")
        check(f"导入-merge 匹配 {item_count} 条", f"匹配更新 {item_count}" in body)
        page.screenshot(path=os.path.join(OUT, "04-import.png"), full_page=True)
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)

        # ---------- 5. 手机宽度 ----------
        mctx = browser.new_context(viewport={"width": 390, "height": 844},
                                   is_mobile=True, device_scale_factor=2)
        mpage = mctx.new_page()
        mpage.goto(BASE, wait_until="networkidle")
        mpage.screenshot(path=os.path.join(OUT, "05-mobile-dashboard.png"), full_page=True)
        mpage.click('.seg-item:has-text("物料清单")')
        mpage.wait_for_timeout(800)
        mpage.screenshot(path=os.path.join(OUT, "06-mobile-items.png"), full_page=True)
        mpage.click('.seg-item:has-text("布点矩阵")')
        mpage.wait_for_timeout(800)
        mpage.screenshot(path=os.path.join(OUT, "07-mobile-matrix.png"), full_page=True)

        mpage.click('.seg-item:has-text("物料清单")')
        mpage.wait_for_timeout(600)
        mpage.locator(".el-table__body-wrapper .el-table__row").first.locator(
            'button:has-text("编辑")').click()
        mpage.wait_for_timeout(600)
        mpage.screenshot(path=os.path.join(OUT, "08-mobile-item-dialog.png"), full_page=True)

        browser.close()

    print()
    print("控制台错误:", len(CONSOLE_ERRORS))
    for e in CONSOLE_ERRORS[:10]:
        print("  -", e[:200])
    if FAILED:
        print("失败项:", FAILED)
        sys.exit(1)
    print("全部通过 ✓")


if __name__ == "__main__":
    run()
