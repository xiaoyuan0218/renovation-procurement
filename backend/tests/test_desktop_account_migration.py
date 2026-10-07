"""桌面端老账号的让位逻辑。

老版本"本机免登录"会在启动时自动建一个管理员，口令是随机串 —— 用户不知道，
也改不了（改密码要先登录进去）。免密去掉之后，这个账号会把用户挡在门外：
注册要求库里一个账号都没有，登录又需要那个随机口令。这组测试盯的就是
"该让位时让位、不该动时不动"。
"""

import pytest

from app import auth
from app.db import SessionLocal, engine
from app.models import User
from app.seed import init_db

import desktop


@pytest.fixture()
def db():
    init_db()
    session = SessionLocal()
    session.query(User).delete()
    session.commit()
    yield session
    session.close()
    engine.dispose()


def _make_admin(db):
    user = User(username="admin", password_hash=auth.hash_password("random-secret"))
    db.add(user)
    db.commit()
    return user


def test_password_marker_roundtrip(tmp_path, monkeypatch):
    """标记文件本身就是"有人知道密码"的唯一凭据，读写要一致。"""
    monkeypatch.setattr(auth, "DATA_DIR", str(tmp_path))
    assert auth.password_known() is False
    auth.mark_password_known()
    assert auth.password_known() is True


def test_drops_account_nobody_can_log_into(db, monkeypatch):
    """标记不在 = 账号是旧版本自动建的 → 删掉，让首次注册重走一遍。"""
    _make_admin(db)
    monkeypatch.setattr(auth, "password_known", lambda: False)

    desktop._drop_unusable_local_account(SessionLocal, User, auth)
    db.expire_all()
    assert db.query(User).count() == 0


def test_keeps_account_when_someone_knows_the_password(db, monkeypatch):
    """有人证明过知道密码（注册/登录成功/改密码）→ 一个都不许动。"""
    _make_admin(db)
    monkeypatch.setattr(auth, "password_known", lambda: True)

    desktop._drop_unusable_local_account(SessionLocal, User, auth)
    db.expire_all()
    assert db.query(User).count() == 1


def test_noop_when_there_is_no_account(db, monkeypatch):
    """全新安装：库里没账号，迁移什么也不做（正常进入注册流程）。"""
    monkeypatch.setattr(auth, "password_known", lambda: False)

    desktop._drop_unusable_local_account(SessionLocal, User, auth)
    db.expire_all()
    assert db.query(User).count() == 0
