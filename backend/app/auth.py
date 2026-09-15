"""登录鉴权：密码哈希、签名 token、请求守卫、失败限流。

只依赖标准库。运行镜像里不需要 bcrypt / passlib / python-jose —— PBKDF2 和
HMAC-SHA256 都是 hashlib/hmac 自带的，功能和安全性足够这个场景。

两种凭证携带方式共用同一个 token：
  - 网页：httpOnly Cookie。设置页里「导出数据 / 下载模板」是 <a href> 直接导航，
    没法加请求头，用 Cookie 才能自动带上；httpOnly 也让脚本偷不到。
  - 安卓：Authorization: Bearer。移动端存 header 比维护 CookieJar 自然。
"""

import base64
import hashlib
import hmac
import os
import secrets
import threading
import time

from fastapi import Depends, HTTPException, Request, Response, status
from sqlalchemy.orm import Session

from .db import DATA_DIR, get_db
from .models import User

PBKDF2_ITERATIONS = 200_000
COOKIE_NAME = "renovation_session"

# 登录失败限流：连续失败到上限就锁一段时间，挡住暴力破解
MAX_FAILURES = 5
LOCKOUT_SECONDS = 60
_FAILURE_TTL = 3600


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, "") or default)
    except ValueError:
        return default


SESSION_DAYS = _env_int("RENOVATION_SESSION_DAYS", 30)

# 默认 False：局域网是明文 HTTP，设成 True 浏览器会直接丢弃这个 Cookie，
# 表现是"登录成功但下一个请求又未登录"。只有上了 HTTPS 才该打开。
COOKIE_SECURE = (os.environ.get("RENOVATION_COOKIE_SECURE", "") or "").strip().lower() in (
    "1", "true", "yes", "on")


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _load_secret() -> bytes:
    """签名密钥：优先环境变量，否则在数据目录里生成一份并长期保留。

    必须落盘。每次启动随机生成的话，容器一重启所有人都被登出；
    而 Docker 部署里 /data 是挂载卷，所以放这里能跟着数据一起活下来。
    """
    env = (os.environ.get("RENOVATION_SECRET") or "").strip()
    if env:
        return env.encode()

    path = os.path.join(DATA_DIR, ".secret_key")
    try:
        with open(path, "rb") as f:
            existing = f.read().strip()
        if existing:
            return existing
    except FileNotFoundError:
        pass

    generated = secrets.token_urlsafe(48).encode()
    # 0600：拿到这个密钥就能伪造任意登录态
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(generated)
    return generated


SECRET = _load_secret()


# ---------------------------------------------------------------- 密码哈希

def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, PBKDF2_ITERATIONS)
    return "pbkdf2_sha256${}${}${}".format(PBKDF2_ITERATIONS, _b64(salt), _b64(digest))


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, iterations, salt_b64, digest_b64 = stored.split("$")
        if algo != "pbkdf2_sha256":
            return False
        expected = _unb64(digest_b64)
        actual = hashlib.pbkdf2_hmac(
            "sha256", password.encode(), _unb64(salt_b64), int(iterations))
    except (ValueError, TypeError):
        return False
    # 定长比较，避免从耗时差异里反推密码
    return hmac.compare_digest(expected, actual)


# ---------------------------------------------------------------- 签名 token

def _sign(body: str) -> str:
    return _b64(hmac.new(SECRET, body.encode(), hashlib.sha256).digest())


def _fingerprint(password_hash: str) -> str:
    """密码指纹。写进 token 里，改密码后签发的旧 token 就全部失效，
    不用为此再建一张会话表。"""
    return _b64(hashlib.sha256(password_hash.encode()).digest())[:16]


def create_token(user: User) -> str:
    expires = int(time.time()) + SESSION_DAYS * 86400
    payload = "{}|{}|{}".format(user.username, expires, _fingerprint(user.password_hash))
    body = _b64(payload.encode())
    return "{}.{}".format(body, _sign(body))


def verify_token(token: str, db: Session):
    """校验 token，通过则返回对应的 User，否则 None。"""
    try:
        body, signature = token.split(".", 1)
    except (ValueError, AttributeError):
        return None
    if not hmac.compare_digest(_sign(body), signature):
        return None
    try:
        username, expires, fingerprint = _unb64(body).decode().split("|")
        if int(expires) < time.time():
            return None
    except (ValueError, UnicodeDecodeError):
        return None
    user = db.query(User).filter(User.username == username).first()
    if user is None:
        return None
    if not hmac.compare_digest(_fingerprint(user.password_hash), fingerprint):
        return None
    return user


# ---------------------------------------------------------------- Cookie

def set_session_cookie(response: Response, token: str) -> None:
    response.set_cookie(
        COOKIE_NAME,
        token,
        max_age=SESSION_DAYS * 86400,
        httponly=True,
        samesite="lax",
        secure=COOKIE_SECURE,
        path="/",
    )


def clear_session_cookie(response: Response) -> None:
    response.delete_cookie(COOKIE_NAME, path="/")


# ---------------------------------------------------------------- 请求守卫

def _extract_token(request: Request):
    header = request.headers.get("authorization", "")
    if header[:7].lower() == "bearer ":
        token = header[7:].strip()
        if token:
            return token
    return request.cookies.get(COOKIE_NAME)


def current_user_or_none(request: Request, db: Session):
    """不抛异常的版本，给「查询登录态」这类端点用。"""
    token = _extract_token(request)
    return verify_token(token, db) if token else None


def require_user(request: Request, db: Session = Depends(get_db)) -> User:
    """挂在所有受保护路由上的依赖。"""
    user = current_user_or_none(request, db)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail="请先登录")
    return user


# ---------------------------------------------------------------- 失败限流
#
# 进程内计数。这个应用是单进程 uvicorn、单管理员账号，不值得为它引入 Redis；
# 重启后计数清零，代价可以接受。

_failures = {}
_failure_lock = threading.Lock()


def _prune(now: float) -> None:
    stale = [k for k, v in _failures.items() if now - v[2] > _FAILURE_TTL]
    for key in stale:
        _failures.pop(key, None)


def locked_seconds(key: str) -> int:
    """还能尝试返回 0，被锁则返回剩余秒数。"""
    now = time.time()
    with _failure_lock:
        entry = _failures.get(key)
        if not entry:
            return 0
        if entry[1] > now:
            return int(entry[1] - now) + 1
        return 0


def record_failure(key: str) -> None:
    now = time.time()
    with _failure_lock:
        count, locked_until, _ = _failures.get(key, (0, 0.0, now))
        count += 1
        if count >= MAX_FAILURES:
            count, locked_until = 0, now + LOCKOUT_SECONDS
        _failures[key] = (count, locked_until, now)
        _prune(now)


def clear_failures(key: str) -> None:
    with _failure_lock:
        _failures.pop(key, None)
