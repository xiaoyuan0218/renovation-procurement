"""Playwright 走查脚本：登录 → 看板数字 → 物料清单 → 分配矩阵编辑/还原 → 导出回灌导入 → 手机宽度截图。

用法：项目根目录下  .venv/Scripts/python backend/scripts/walkthrough.py
前置：uvicorn 已在 127.0.0.1:8000 运行（托管前端 dist）。

脚本会在开头和结尾各取一次全库快照，最后断言两次完全一致：走查里的写操作
（矩阵 +1 再还原、导出回灌、记一笔再删）净效应必须是零。只比"界面数字等于
接口数字"证明不了这一点 —— 数字一起变了它也会全绿。

注意：合并导入不是幂等的旧问题已经修掉，这条断言就是防止它复发。

接口现在都要登录。脚本会用一个账号登录后，把 token 作为 Authorization 头
带给所有请求和浏览器上下文：
  - 实例还没有账号时会自动创建，默认 admin / admin123456
  - 已经有账号时，用 WALKTHROUGH_USER / WALKTHROUGH_PASSWORD 传入凭证
"""

import json
import os
import sys
import urllib.error
import urllib.request

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


def api(path, data=None, token=None):
    """打一次后端，返回 (状态码, 解析后的 body)。"""
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(BASE + path, data=body)
    if body:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"null")
        except ValueError:
            return e.code, raw.decode(errors="replace")


def auth_token():
    user = os.environ.get("WALKTHROUGH_USER", "admin")
    password = os.environ.get("WALKTHROUGH_PASSWORD", "admin123456")

    _, state = api("/api/auth/state")
    if state and not state.get("initialized"):
        code, body = api("/api/auth/setup", {"username": user, "password": password})
        if code == 200:
            print(f"  实例还没有账号，已创建 {user} / {password}")
            return body["token"]

    code, body = api("/api/auth/login", {"username": user, "password": password})
    if code != 200:
        detail = body.get("detail") if isinstance(body, dict) else body
        print(f"登录失败（HTTP {code}）：{detail}")
        print("  这个实例已经建过账号了，请用正确的凭证重跑：")
        print("    WALKTHROUGH_USER=... WALKTHROUGH_PASSWORD=... python backend/scripts/walkthrough.py")
        sys.exit(2)
    return body["token"]


def full_snapshot(token):
    """全库快照：逐物料的金额口径 + 分配/记录明细。

    比"界面数字 == 接口数字"强得多 —— 后者在数据本身被改动时照样全绿，
    而"导出再回灌必须什么都不改"这条不变量恰恰只有前后对比才能保证。
    """
    _, items = api("/api/items", token=token)
    return {
        "count": len(items),
        "per_item": {
            i["id"]: (i["name"], i["total_qty"], i["list_total"], i["discount_total"],
                      i["paid"], i["unpaid"], i["status"])
            for i in items
        },
        "allocs": sorted(
            (a["item_id"], a["room_id"], a["qty"], a["price_override"], a["note"])
            for i in items for a in i["allocations"]
        ),
        "records": sorted(
            (r["item_id"], r["qty"], r["amount"], r["date"], r["note"])
            for i in items for r in i["records"]
        ),
    }


def describe_diff(before, after):
    """只说人话：哪一项变了、变成什么。"""
    notes = []
    if before["count"] != after["count"]:
        notes.append(f"物料数 {before['count']} → {after['count']}")
    changed = [f"{before['per_item'].get(k, ('?',))[0]}"
               for k in set(before["per_item"]) | set(after["per_item"])
               if before["per_item"].get(k) != after["per_item"].get(k)]
    if changed:
        notes.append("金额/数量变了：" + "、".join(sorted(changed)[:6]))
    for key, label in (("allocs", "分配"), ("records", "采购记录")):
        if before[key] != after[key]:
            notes.append(f"{label}明细变了（{len(before[key])} → {len(after[key])} 条）")
    return "；".join(notes) or "有差异（未归类）"


def run():
    from playwright.sync_api import sync_playwright

    token = auth_token()
    auth_header = {"Authorization": f"Bearer {token}"}
    before = full_snapshot(token)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)

        # ---------- 0. 未登录时应当看到登录页，而不是主界面 ----------
        anon = browser.new_context(viewport={"width": 1280, "height": 900})
        anon_page = anon.new_page()
        anon_page.goto(BASE, wait_until="networkidle")
        anon_page.wait_for_timeout(600)
        check("登录-未登录时显示登录页", anon_page.locator('input[placeholder="用户名"]').count() == 1)
        check("登录-未登录时看不到主界面", anon_page.locator(".seg-item").count() == 0)
        anon_page.screenshot(path=os.path.join(OUT, "00-login.png"), full_page=True)
        anon.close()

        ctx = browser.new_context(viewport={"width": 1280, "height": 900})
        # 带上 token 后，页面里的 fetch 和 ctx.request 都会自动鉴权
        ctx.set_extra_http_headers(auth_header)
        page = ctx.new_page()
        page.on("console", lambda m: CONSOLE_ERRORS.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: CONSOLE_ERRORS.append(str(e)))

        # ---------- 1. 看板 ----------
        # 只校验「界面数字与接口一致」，不绑定任何具体数据，方便在任意库上跑
        _, summary = api("/api/summary", token=token)
        totals, item_count = summary["totals"], summary["totals"]["item_count"]
        page.goto(BASE, wait_until="networkidle")
        body = page.inner_text("body")
        for label, key in (("原价合计", "list_total"), ("日常价合计", "discount_total"),
                           ("已付", "paid_total"), ("未付", "unpaid_total"),
                           ("实际优惠", "actual_discount_total"),
                           ("日常价优惠", "daily_discount_total"),
                           ("日常价未付", "daily_unpaid_total")):
            shown = f"{totals[key]:,.2f}"
            check(f"看板-{label}与接口一致（{shown}）", shown in body)
        # 两个口径的三段拆分必须在页面上真的加得起来（负数优惠也照样成立）
        check("看板-已付+实际优惠+未付=原价合计",
              round(totals["paid_total"] + totals["actual_discount_total"]
                    + totals["unpaid_total"], 2) == totals["list_total"])
        check("看板-已付+日常价优惠+日常价未付=日常价合计",
              round(totals["paid_total"] + totals["daily_discount_total"]
                    + totals["daily_unpaid_total"], 2) == totals["discount_total"])
        page.screenshot(path=os.path.join(OUT, "01-dashboard.png"), full_page=True)

        # ---------- 2. 物料清单 ----------
        page.click('.seg-item:has-text("物料清单")')
        page.wait_for_timeout(800)
        rows = page.locator(".el-table__body-wrapper .el-table__row")
        # 列表有分页：表格里只是当前页，总数看分页栏。
        # 每页几条是用户自己设定的（还能手输），所以这里只校验"有数据且不超过总数"。
        page.wait_for_timeout(400)
        check("清单-当前页有数据", 0 < rows.count() <= item_count, f"实际 {rows.count()}")
        check(f"清单-分页栏总数 {item_count}",
              f"共{item_count}条" in page.locator(".pager").inner_text().replace(" ", ""),
              page.locator(".pager").inner_text().strip())
        body = page.inner_text("body")
        check("清单-筛选统计出现", "日常价" in body and "实付" in body)
        page.screenshot(path=os.path.join(OUT, "02-items.png"), full_page=True)

        # ---------- 2b. 编辑物料弹窗布局 ----------
        page.locator(".el-table__body-wrapper .el-table__row").first.locator(
            'button:has-text("编辑")').click()
        page.wait_for_timeout(600)
        page.screenshot(path=os.path.join(OUT, "02b-item-dialog.png"), full_page=True)

        # ---------- 2c. 并发覆盖：弹窗开着的时候别处改了这条 ----------
        # 真实场景：这边网页开着编辑框，家里人在手机上记了一笔付款。
        # 保存时必须停下来问，而不是整条写回去把那笔付款抹掉。
        # 弹窗里那条物料才是这次要编辑的。列表默认按日常价降序排，跟接口返回的
        # 顺序（按 sort/id）不是一回事 —— 必须按名字对上号，否则"别处改的"和
        # "这边编辑的"根本不是同一条，409 永远不会触发。
        opened_name = page.locator(".el-dialog:visible input").first.input_value()
        _, items_now = api("/api/items", token=token)
        first_item = next((i for i in items_now if i["name"] == opened_name), items_now[0])
        st, after_rec = api(f"/api/items/{first_item['id']}/records",
                            {"qty": 1, "amount": 0.01, "note": "并发测试"}, token=token)
        check("并发-模拟另一台设备记了一笔", st == 200, f"HTTP {st}")
        new_rec_id = after_rec["records"][-1]["id"] if st == 200 else None

        page.locator('.el-dialog:visible input').first.fill("并发测试改的名字")
        page.locator('.el-dialog:visible button:has-text("保存")').click()
        page.wait_for_timeout(1200)
        conflict = page.locator('.el-message-box:visible')
        has_conflict = conflict.count() > 0 and "别处" in conflict.inner_text()
        check("并发-保存时拦下来并提示", has_conflict,
              (conflict.inner_text()[:60] if conflict.count() else "没有弹出提示"))
        page.screenshot(path=os.path.join(OUT, "02c-conflict.png"), full_page=True)
        if conflict.count():
            conflict.locator('button:has-text("取消")').click()
            page.wait_for_timeout(400)
        # 关掉编辑框。注意页面同时挂着物品编辑、设置、新建清单三个 dialog，
        # 只有可见的那个算数；而且它可能已经被关掉了，所以存在才点。
        close_btn = page.locator('.el-dialog:visible button:has-text("取消")')
        if close_btn.count():
            close_btn.first.click()
            page.wait_for_timeout(400)

        # 别处记的那笔必须还在，名字也不该被改掉
        _, fresh = api(f"/api/items/{first_item['id']}", token=token)
        check("并发-别处记的那笔仍在", fresh["paid"] >= 0.01, f"paid={fresh['paid']}")
        check("并发-名字没被覆盖", fresh["name"] == first_item["name"],
              f"{first_item['name']} -> {fresh['name']}")
        if new_rec_id:
            req = urllib.request.Request(
                f"{BASE}/api/records/{new_rec_id}", method="DELETE")
            req.add_header("Authorization", f"Bearer {token}")
            try:
                urllib.request.urlopen(req)
            except urllib.error.HTTPError:
                pass

        # ---------- 3. 分配矩阵：给某个已有分配的单元格 +1 再改回 ----------
        # 全部数值运行时从页面读取，不依赖具体物料，任何数据集都能跑
        page.click('.seg-item:has-text("分配矩阵")')
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
        check("矩阵-找到有分配的单元格", probe is not None, "没有可用单元格（数据为空？）")

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

        # ---------- 4b. 设置 → 账号（改密码入口） ----------
        page.click('.el-tabs__item:has-text("账号")')
        page.wait_for_timeout(400)
        dialog = page.locator(".el-dialog:visible")
        check("账号-显示当前登录账号",
              dialog.locator(".account-name").inner_text().strip() not in ("", "—"))
        check("账号-有改密码入口",
              dialog.locator('button:has-text("修改密码")').count() == 1)
        # 两次新密码不一致要被前端拦下（不真的提交，避免改掉测试实例的密码）
        page.locator('.el-tab-pane:visible input[type="password"]').nth(0).fill("whatever-old")
        page.locator('.el-tab-pane:visible input[type="password"]').nth(1).fill("newpass123")
        page.locator('.el-tab-pane:visible input[type="password"]').nth(2).fill("mismatch999")
        dialog.locator('button:has-text("修改密码")').click()
        page.wait_for_timeout(600)
        check("账号-两次密码不一致时拦住", "不一致" in page.inner_text("body"))
        page.screenshot(path=os.path.join(OUT, "04b-account.png"), full_page=True)

        page.keyboard.press("Escape")
        page.wait_for_timeout(400)

        # ---------- 5. 手机宽度 ----------
        mctx = browser.new_context(viewport={"width": 390, "height": 844},
                                   is_mobile=True, device_scale_factor=2)
        mctx.set_extra_http_headers(auth_header)
        mpage = mctx.new_page()
        mpage.goto(BASE, wait_until="networkidle")
        mpage.screenshot(path=os.path.join(OUT, "05-mobile-dashboard.png"), full_page=True)
        mpage.click('.seg-item:has-text("物料清单")')
        mpage.wait_for_timeout(800)
        mpage.screenshot(path=os.path.join(OUT, "06-mobile-items.png"), full_page=True)
        mpage.click('.seg-item:has-text("分配矩阵")')
        mpage.wait_for_timeout(800)
        mpage.screenshot(path=os.path.join(OUT, "07-mobile-matrix.png"), full_page=True)

        mpage.click('.seg-item:has-text("物料清单")')
        mpage.wait_for_timeout(600)
        mpage.locator(".el-table__body-wrapper .el-table__row").first.locator(
            'button:has-text("编辑")').click()
        mpage.wait_for_timeout(600)
        mpage.screenshot(path=os.path.join(OUT, "08-mobile-item-dialog.png"), full_page=True)

        browser.close()

    # ---------- 6. 走查的写操作净效应必须为零 ----------
    after = full_snapshot(token)
    check("整体-走查跑完数据一字未变",
          before == after,
          "到处都被改动了：" + describe_diff(before, after))

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
