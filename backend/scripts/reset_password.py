#!/usr/bin/env python
"""忘记密码时的救急出口：命令行重设管理员密码。

用法：
    python scripts/reset_password.py                     # 交互式输入新密码
    python scripts/reset_password.py --password 新密码    # 直接指定
    python scripts/reset_password.py --username 新名字    # 顺便改用户名

容器里执行：
    docker compose exec app python scripts/reset_password.py
重设后之前签发的所有 token 会自动失效。
"""

import argparse
import getpass
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from app.auth import hash_password  # noqa: E402
from app.db import SessionLocal  # noqa: E402
from app.models import User  # noqa: E402
from app.seed import init_db  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description="重设管理员密码")
    parser.add_argument("--username", default="admin", help="账号名（不存在则创建）")
    parser.add_argument("--password", default=None, help="新密码（不传则交互式输入）")
    args = parser.parse_args()

    password = args.password
    if not password:
        password = getpass.getpass("新密码：")
        if password != getpass.getpass("再输入一次："):
            print("两次输入不一致", file=sys.stderr)
            return 1
    if len(password) < 6:
        print("密码至少 6 位", file=sys.stderr)
        return 1

    init_db()
    db = SessionLocal()
    try:
        users = db.query(User).all()
        if not users:
            user = User(username=args.username, password_hash=hash_password(password))
            db.add(user)
            print(f"已创建管理员账号「{user.username}」")
        else:
            user = users[0]
            user.username = args.username
            user.password_hash = hash_password(password)
            print(f"已重设账号「{user.username}」的密码")
        db.commit()
    finally:
        db.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
