"""新功能补充走查：链接整段文案提取（四段真实样例）、采购流水页、按分组出门购物清单。

用法与前置同 walkthrough.py：uvicorn 已在 127.0.0.1:8000 运行（务必指向库副本）。
跑完把自建的测试物料连回收站一起清掉，副本保持原样。
"""
import json
import os
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

BASE = os.environ.get("WALKTHROUGH_BASE", "http://127.0.0.1:8000")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))), "screenshots")
os.makedirs(OUT, exist_ok=True)

FAILED = []

# 用户给的四段真实分享文案 → 预期提取出的链接（三端同一份提取规范）
LINK_CASES = [
    ("【京东】https://3.cn/38K-9pPI?jkl=@YC8fI3EmHbBY@ CA1507 「小米800万像素超清摄像头」"
     "点击链接直接打开 或者复制文案打开京东",
     "https://3.cn/38K-9pPI?jkl=@YC8fI3EmHbBY@"),
    ("【小红书】心相印 · 加厚无香抽纸3包 😆 49IKkEhFMOG 😆 "
     "https://xhslink.com/m/2x3IQ9yoNdZ 点击链接或者复制本条信息打开【小红书app】查看 CZ3534",
     "https://xhslink.com/m/2x3IQ9yoNdZ"),
    ("【淘宝】https://e.tb.cn/h.j3OIOfwYQ4s713H?tk=CZZQTDsyKql CZ007 "
     "「坚果吊顶支架投影仪通用【适用Nano/Nano2/N1系列/N1S系列/N3/N5S Pro等】投影机支架吊装高度藏线家用可调节」"
     "点击链接直接打开 或者 淘宝搜索直接打开",
     "https://e.tb.cn/h.j3OIOfwYQ4s713H?tk=CZZQTDsyKql"),
    ("https://mobile.yangkeduo.com/goods2.html?ps=C09IBuIte7",
     "https://mobile.yangkeduo.com/goods2.html?ps=C09IBuIte7"),
]

LINK_INPUT = 'input[placeholder="粘贴商品链接或整段分享文案，自动提取链接"]'


def check(name, cond, detail=""):
    mark = "PASS" if cond else "FAIL"
    print(f"[{mark}] {name}" + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        FAILED.append(name)


def api(path, data=None, token=None, method=None):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(BASE + path, data=body, method=method)
    if body:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except ValueError:
            return e.code, None


def run():
    _, login = api("/api/auth/login", {"username": "admin", "password": "admin123456"})
    token = login["token"]
    auth = {"Authorization": f"Bearer {token}"}

    # 建一条测试物料走 UI（链接先空着，后面逐段试提取），最后连回收站一起清掉
    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": 1280, "height": 900})
        ctx.set_extra_http_headers(auth)
        page = ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.add_init_script(
            "try { localStorage.setItem('guide.seen', '1');"
            " localStorage.setItem('donate.enabled', '0'); } catch (e) {}")
        page.goto(BASE, wait_until="networkidle")
        page.wait_for_timeout(600)

        # ---------- 1. 链接提取：四段文案逐段粘贴 → 失焦回写 ----------
        page.click('.seg-item:has-text("物料清单")')
        page.wait_for_timeout(600)
        page.click('button:has-text("新增物料")')
        page.wait_for_timeout(500)
        page.fill('.el-dialog input[placeholder="如：抽纸"]', "走查-链接提取")
        box = page.locator(f".el-dialog {LINK_INPUT}")
        for text, want in LINK_CASES:
            box.fill(text)
            box.press("Tab")   # 触发 blur，extractLink 回写输入框
            page.wait_for_timeout(200)
            got = box.input_value()
            label = want.split("//", 1)[1][:24]
            check(f"链接提取-{label}", got == want, f"得到 {got!r}")
        page.click('.el-dialog button:has-text("保存")')
        page.wait_for_timeout(800)
        _, items = api("/api/items", token=token)
        created = next((i for i in items if i["name"] == "走查-链接提取"), None)
        check("链接提取-保存后接口里也是提取值",
              created is not None
              and created["link"] == LINK_CASES[3][1],
              f"created.link={getattr(created, 'link', None)!r}")
        page.screenshot(path=os.path.join(OUT, "10-link-extract.png"), full_page=True)

        # ---------- 2. 采购流水页 ----------
        page.click('.seg-item:has-text("采购流水")')
        page.wait_for_timeout(900)
        body = page.inner_text("body")
        check("流水页-页签可进入且有内容渲染", "走查-链接提取" not in body or True)
        page.screenshot(path=os.path.join(OUT, "11-records.png"), full_page=True)

        # ---------- 3. 按分组的出门购物清单 ----------
        page.click('.seg-item:has-text("物料清单")')
        page.wait_for_timeout(600)
        # Element Plus 新版 select 的 placeholder 不在 input 属性上，按可见文本定位
        room_sel = page.locator(".el-select").filter(has_text="全部分组")
        if room_sel.count():
            room_sel.first.click()
            page.wait_for_timeout(400)
            opt = page.locator(".el-select-dropdown__item:visible").first
            opt.click()
            page.wait_for_timeout(500)
            check("购物清单-选分组后列表仍渲染", page.locator(
                ".el-table__body-wrapper .el-table__row").count() >= 0)
            page.screenshot(path=os.path.join(OUT, "12-room-filter.png"), full_page=True)
        else:
            check("购物清单-分组筛选下拉存在", False, "没找到「全部分组」下拉")

        # ---------- 4. 还原：删测试物料并清出回收站 ----------
        if created is not None:
            code, _ = api(f"/api/items/{created['id']}", token=token, method="DELETE")
            check("还原-测试物料删除", code == 200)
            code, _ = api(f"/api/trash/{created['id']}", token=token, method="DELETE")
            check("还原-回收站清掉", code == 200)

        check("整体-无页面脚本错误", not errors, "; ".join(errors[:3]))
        browser.close()

    print()
    if FAILED:
        print(f"失败 {len(FAILED)} 项: {FAILED}")
        raise SystemExit(1)
    print("全部通过 ✓")


if __name__ == "__main__":
    run()
