"""比对手机（单机版）本地库与服务端 ORM 的表结构。

手机端要和服务器互搬清单数据，前提是**同一张表用同一套列名** —— 手机清单
是以 JSON 形式搬过去的，列名对不上就会搬错或静默丢字段。这个脚本把手机库
拉下来跑一遍，逐表列出两边的列差异。

拉手机库（记得把 -wal / -shm 一起拉，否则 SQLite 读不到最新内容）：
    adb exec-out run-as com.xiaoyuan.renovation.mobile.debug cat databases/renovation.db > local.db
    adb exec-out run-as com.xiaoyuan.renovation.mobile.debug cat databases/renovation.db-wal > local.db-wal
    adb exec-out run-as com.xiaoyuan.renovation.mobile.debug cat databases/renovation.db-shm > local.db-shm

再跑：
    python scripts/compare_local_schema.py local.db

退出码 0 表示只存在预期内的差异（手机端有自己的 Room 元数据表，服务端有 users）。
"""

import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import models  # noqa: E402,F401  导入后才能拿到全部表定义
from app.db import Base  # noqa: E402

# 手机端专有：Room 自己维护的元数据表，不参与同步
LOCAL_ONLY_OK = {"room_master_table", "android_metadata", "sqlite_sequence"}
# 服务端专有：账号只存在于服务端（单机版没有登录）
SERVER_ONLY_OK = {"users"}


def read_local(path):
    conn = sqlite3.connect(path)
    try:
        tables = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")]
        out = {}
        for t in tables:
            out[t] = {r[1]: (r[2] or "").upper() for r in
                      conn.execute(f'PRAGMA table_info("{t}")')}
        return out
    finally:
        conn.close()


def read_server():
    return {
        t.name: {c.name: str(c.type).upper() for c in t.columns}
        for t in Base.metadata.sorted_tables
    }


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    if not Path(path).exists():
        print(f"找不到文件：{path}")
        return 2

    local = read_local(path)
    server = read_server()
    problems = []

    shared = sorted(set(local) & set(server))
    print(f"共同表 {len(shared)} 张，逐列比对：\n")
    for name in shared:
        lcols, scols = local[name], server[name]
        missing = sorted(set(scols) - set(lcols))
        extra = sorted(set(lcols) - set(scols))
        if not missing and not extra:
            print(f"  {name:<18} ✓ {len(scols)} 列一致")
            continue
        problems.append(name)
        print(f"  {name:<18} ✗")
        if missing:
            print(f"      手机端缺少：{', '.join(missing)}")
        if extra:
            print(f"      手机端多出：{', '.join(extra)}")
        # 类型只作提示：SQLite 按亲和性存储，VARCHAR 与 TEXT 可以互通
        for col in sorted(set(lcols) & set(scols)):
            if lcols[col] != scols[col]:
                print(f"      类型提示：{col} 本地 {lcols[col] or '无'} / 服务端 {scols[col]}")

    only_local = sorted(set(local) - set(server))
    only_server = sorted(set(server) - set(local))

    print("\n只在手机端有的表：")
    for t in only_local:
        mark = "预期" if t in LOCAL_ONLY_OK else "**意外**"
        print(f"  {t:<18} {mark}")
        if t not in LOCAL_ONLY_OK:
            problems.append(t)

    print("\n只在服务端有的表：")
    for t in only_server:
        mark = "预期" if t in SERVER_ONLY_OK else "**意外**"
        print(f"  {t:<18} {mark}")
        if t not in SERVER_ONLY_OK:
            problems.append(t)

    print()
    if problems:
        print(f"发现 {len(problems)} 处需要处理的差异：{', '.join(problems)}")
        return 1
    print("结构与服务端一致，可以直接做清单级数据搬运。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
