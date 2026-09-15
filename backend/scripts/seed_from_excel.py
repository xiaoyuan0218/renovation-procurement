#!/usr/bin/env python
"""把三分区格式的历史表格（产品×房间矩阵 + 物料汇总 + 类目汇总）一次性写入数据库。

用法：
    python scripts/seed_from_excel.py [xlsx路径]
不传路径时自动使用 seed/ 目录下的 xlsx；目录为空则报错提示。
默认 replace 模式，会先清空现有物料数据。
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from app.db import SessionLocal  # noqa: E402
from app.seed import init_db  # noqa: E402
from app.services import excel_io  # noqa: E402

SEED_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))), "seed")


def _default_xlsx():
    """seed 目录里的表格：取第一个 xlsx，便于仓库保持无个人文件名。"""
    import glob
    files = sorted(glob.glob(os.path.join(SEED_DIR, "*.xlsx")))
    return files[0] if files else None


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else _default_xlsx()
    if not path:
        print(f"seed 目录里没有 xlsx，请把表格放到 {SEED_DIR} 或直接传入路径")
        sys.exit(1)
    if not os.path.isfile(path):
        print(f"文件不存在: {path}")
        sys.exit(1)
    with open(path, "rb") as f:
        data = f.read()
    db = SessionLocal()
    try:
        init_db()
        report = excel_io.import_original(db, data, mode="replace")
    finally:
        db.close()
    print("预置数据完成:", report)


if __name__ == "__main__":
    main()
