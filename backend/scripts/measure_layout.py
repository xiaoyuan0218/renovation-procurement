"""布局实测：三页表格是否撑满容器、表头是否固定、页面是否整体滚动。

用法：项目根目录下  .venv/Scripts/python backend/scripts/measure_layout.py
前置：uvicorn 已在 127.0.0.1:8000 运行（托管前端 dist）。

接口现在都要登录，沿用走查脚本的登录逻辑（凭证走 WALKTHROUGH_USER /
WALKTHROUGH_PASSWORD，实例没有账号时会自动创建）。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from walkthrough import auth_token  # noqa: E402  同目录，复用登录流程

BASE = os.environ.get("WALKTHROUGH_BASE", "http://127.0.0.1:8000")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))), "screenshots")
os.makedirs(OUT, exist_ok=True)

FAILED = []
CONSOLE_ERRORS = []


def check(name, cond, detail=""):
    mark = "PASS" if cond else "FAIL"
    line = f"[{mark}] {name}"
    if detail:
        line += f" — {detail}"
    print(line)
    if not cond:
        FAILED.append(name)


PROBE = """
() => {
  const r = (el) => el ? el.getBoundingClientRect() : null;
  const dim = (el) => el ? { h: Math.round(el.getBoundingClientRect().height),
                             w: Math.round(el.getBoundingClientRect().width) } : null;
  const main = document.querySelector('.el-main');
  const wrap = document.querySelector('.page-wrap');
  const panel = document.querySelector('.glass.panel');
  const box = document.querySelector('.table-box');
  const table = document.querySelector('.el-table');
  const hdr = document.querySelector('.el-table__header-wrapper');
  const body = document.querySelector('.el-table__body-wrapper');
  const scroller = document.querySelector('.el-table__body-wrapper .el-scrollbar__wrap');
  const ftr = document.querySelector('.el-table__footer-wrapper');
  const out = {
    innerH: window.innerHeight,
    pageScrollH: document.scrollingElement.scrollHeight,
    pageClientH: document.scrollingElement.clientHeight,
    mainH: main ? Math.round(main.clientHeight) : null,
    mainScrollH: main ? main.scrollHeight : null,
    wrapH: wrap ? Math.round(wrap.clientHeight) : null,
    panelH: panel ? Math.round(panel.clientHeight) : null,
    panelScrollH: panel ? panel.scrollHeight : null,
    boxH: box ? Math.round(box.clientHeight) : null,
    table: dim(table),
    tableOffsetTop: table ? Math.round(table.getBoundingClientRect().top) : null,
    hdr: dim(hdr),
    bodyH: scroller ? Math.round(scroller.clientHeight) : null,
    bodyScrollH: scroller ? scroller.scrollHeight : null,
    bodyCanScrollY: scroller ? scroller.scrollHeight > scroller.clientHeight + 1 : null,
    footerH: ftr ? Math.round(ftr.getBoundingClientRect().height) : null,
    gapBelowTable: (panel && table)
      ? Math.round(panel.getBoundingClientRect().bottom - table.getBoundingClientRect().bottom
                   - parseFloat(getComputedStyle(panel).paddingBottom))
      : null,
  };
  return out;
}
"""


def probe(page, label):
    m = page.evaluate(PROBE)
    print(f"  [{label}] {m}")
    return m


def run():
    from playwright.sync_api import sync_playwright

    token = auth_token()

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1280, "height": 900})
        ctx.set_extra_http_headers({"Authorization": f"Bearer {token}"})
        page = ctx.new_page()
        page.on("console", lambda m: CONSOLE_ERRORS.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: CONSOLE_ERRORS.append(str(e)))

        # ---------- 物料清单 ----------
        page.goto(BASE, wait_until="networkidle")
        page.click("text=物料清单")
        page.wait_for_selector(".glass.panel .el-table__row", timeout=8000)
        page.wait_for_timeout(700)
        m = probe(page, "物料清单")
        check("清单-页面无整体滚动",
              m["pageScrollH"] <= m["pageClientH"] + 1,
              f"scrollH={m['pageScrollH']} clientH={m['pageClientH']}")
        check("清单-main 无整体滚动",
              m["mainScrollH"] <= m["mainH"] + 1,
              f"mainScrollH={m['mainScrollH']} mainH={m['mainH']}")
        check("清单-表格撑满视口（无底部空白）",
              m["gapBelowTable"] is not None and abs(m["gapBelowTable"]) <= 3,
              f"gap={m['gapBelowTable']} tableH={m['table']['h']} boxH={m['boxH']}")
        check("清单-表头在表格内顶部（不随内容滚）",
              m["hdr"] and m["hdr"]["h"] > 20 and m["tableOffsetTop"] is not None,
              f"hdr={m['hdr']} tableTop={m['tableOffsetTop']}")
        check("清单-表格内容内部滚动",
              m["bodyCanScrollY"] is True,
              f"bodyH={m['bodyH']} bodyScrollH={m['bodyScrollH']}")
        # 滚动表格内部，确认表头位置不动
        before = page.evaluate("() => Math.round(document.querySelector('.el-table__header-wrapper').getBoundingClientRect().top)")
        page.evaluate("() => { const b = document.querySelector('.el-table__body-wrapper .el-scrollbar__wrap'); b.scrollTop = 300 }")
        page.wait_for_timeout(200)
        after = page.evaluate("() => Math.round(document.querySelector('.el-table__header-wrapper').getBoundingClientRect().top)")
        check("清单-滚动内容后表头不动", before == after, f"{before} -> {after}")
        page.screenshot(path=os.path.join(OUT, "120-items-fill.png"))

        # ---------- 布点矩阵 ----------
        page.click("text=布点矩阵")
        page.wait_for_selector(".glass.panel .el-table__row", timeout=8000)
        page.wait_for_timeout(700)
        m = probe(page, "布点矩阵")
        check("矩阵-页面无整体滚动",
              m["pageScrollH"] <= m["pageClientH"] + 1,
              f"scrollH={m['pageScrollH']} clientH={m['pageClientH']}")
        check("矩阵-表格撑满视口（无底部空白）",
              m["gapBelowTable"] is not None and abs(m["gapBelowTable"]) <= 3,
              f"gap={m['gapBelowTable']} tableH={m['table']['h']} boxH={m['boxH']}")
        check("矩阵-合计行固定在表格底部",
              m["footerH"] is not None and m["footerH"] > 20,
              f"footerH={m['footerH']}")
        check("矩阵-内容内部滚动",
              m["bodyCanScrollY"] is True,
              f"bodyH={m['bodyH']} bodyScrollH={m['bodyScrollH']}")
        before = page.evaluate("() => Math.round(document.querySelector('.el-table__header-wrapper').getBoundingClientRect().top)")
        page.evaluate("() => { const b = document.querySelector('.el-table__body-wrapper .el-scrollbar__wrap'); b.scrollTop = 300 }")
        page.wait_for_timeout(200)
        after = page.evaluate("() => Math.round(document.querySelector('.el-table__header-wrapper').getBoundingClientRect().top)")
        check("矩阵-滚动内容后表头不动", before == after, f"{before} -> {after}")
        page.screenshot(path=os.path.join(OUT, "121-matrix-fill.png"))

        # ---------- 看板 ----------
        page.click("text=总览")
        page.wait_for_selector(".dash-board", timeout=8000)
        page.wait_for_timeout(900)
        dash = page.evaluate("""
        () => {
          const card = (t) => [...document.querySelectorAll('.bottom-card')]
            .find((c) => c.innerText.includes(t));
          const chartC = card('未采购金额');
          const tableC = card('未采购清单');
          const fill = chartC.querySelector('.chart-fill');
          const canvas = chartC.querySelector('canvas');
          const box = tableC.querySelector('.table-box');
          const table = tableC.querySelector('.el-table');
          const body = tableC.querySelector('.el-table__body-wrapper .el-scrollbar__wrap');
          const D = (el) => el ? Math.round(el.getBoundingClientRect().height) : null;
          return {
            chartCardH: D(chartC), tableCardH: D(tableC),
            chartCardBottom: Math.round(chartC.getBoundingClientRect().bottom),
            tableCardBottom: Math.round(tableC.getBoundingClientRect().bottom),
            fillH: D(fill), canvasH: D(canvas),
            fillPadV: fill ? parseFloat(getComputedStyle(fill).paddingTop) + parseFloat(getComputedStyle(fill).paddingBottom) : null,
            tableBoxH: D(box), unboughtTableH: D(table),
            bodyH: D(body), bodyScrollH: body ? body.scrollHeight : null,
            pageScrollH: document.scrollingElement.scrollHeight,
            pageClientH: document.scrollingElement.clientHeight,
          };
        }
        """)
        print(f"  [看板] {dash}")
        check("看板-页面无整体滚动",
              dash["pageScrollH"] <= dash["pageClientH"] + 1,
              f"scrollH={dash['pageScrollH']} clientH={dash['pageClientH']}")
        expect_canvas = (dash["fillH"] or 0) - (dash["fillPadV"] or 0)
        check("看板-Top6 图表撑满卡片",
              dash["canvasH"] and abs(dash["canvasH"] - expect_canvas) <= 3,
              f"canvas={dash['canvasH']} 期望={expect_canvas} (容器 {dash['fillH']} - 内边距 {dash['fillPadV']})")
        check("看板-两张卡片底部对齐",
              abs(dash["chartCardBottom"] - dash["tableCardBottom"]) <= 4,
              f"{dash['chartCardBottom']} vs {dash['tableCardBottom']}")
        check("看板-清单表格撑满卡片",
              dash["unboughtTableH"] and dash["tableBoxH"]
              and abs(dash["unboughtTableH"] - dash["tableBoxH"]) <= 3,
              f"table={dash['unboughtTableH']} box={dash['tableBoxH']}")
        page.screenshot(path=os.path.join(OUT, "122-dash-fill.png"))

        if CONSOLE_ERRORS:
            print("\n控制台错误：")
            for e in CONSOLE_ERRORS:
                print("  !", e)

        browser.close()

    print()
    if FAILED:
        print(f"FAILED: {len(FAILED)} -> {FAILED}")
        sys.exit(1)
    print("全部布局检查通过")


if __name__ == "__main__":
    run()
