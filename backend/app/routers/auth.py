"""登录相关端点。这几个路由本身不挂鉴权（否则没法登录）。"""

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from sqlalchemy.orm import Session

from .. import auth
from ..db import get_db
from ..models import User
from ..schemas import (AuthStateOut, CredentialsIn, LoginOut, OkOut,
                       PasswordChangeIn)

router = APIRouter(prefix="/api/auth", tags=["auth"])


def _client_key(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def _count_users(db: Session) -> int:
    return db.query(User).count()


@router.get("/state", response_model=AuthStateOut)
def auth_state(request: Request, db: Session = Depends(get_db)):
    """前端启动时先问这里，决定进「创建管理员」还是「登录」还是主界面。"""
    user = auth.current_user_or_none(request, db)
    return AuthStateOut(
        initialized=_count_users(db) > 0,
        authenticated=user is not None,
        username=user.username if user else None,
    )


@router.post("/setup", response_model=LoginOut)
def setup(data: CredentialsIn, response: Response, db: Session = Depends(get_db)):
    """创建管理员账号。只在还没有任何账号时可用，建成即自动登录。"""
    if _count_users(db) > 0:
        raise HTTPException(409, "管理员账号已存在，请直接登录")
    user = User(username=data.username, password_hash=auth.hash_password(data.password))
    db.add(user)
    db.commit()
    db.refresh(user)
    token = auth.create_token(user)
    auth.set_session_cookie(response, token)
    return LoginOut(token=token, username=user.username)


@router.post("/login", response_model=LoginOut)
def login(data: CredentialsIn, request: Request, response: Response,
          db: Session = Depends(get_db)):
    key = _client_key(request)
    remaining = auth.locked_seconds(key)
    if remaining:
        raise HTTPException(429, f"登录失败次数过多，请 {remaining} 秒后再试")

    user = db.query(User).filter(User.username == data.username).first()
    if user is None or not auth.verify_password(data.password, user.password_hash):
        auth.record_failure(key)
        # 不区分「用户不存在」和「密码错」，避免被用来枚举账号
        raise HTTPException(401, "用户名或密码不正确")

    auth.clear_failures(key)
    token = auth.create_token(user)
    auth.set_session_cookie(response, token)
    return LoginOut(token=token, username=user.username)


@router.post("/logout", response_model=OkOut)
def logout(response: Response):
    # token 是无状态的，服务端只负责把 Cookie 清掉；安卓端自行丢弃本地 token
    auth.clear_session_cookie(response)
    return OkOut()


@router.get("/me")
def me(user: User = Depends(auth.require_user)):
    return {"username": user.username}


@router.post("/password", response_model=LoginOut)
def change_password(data: PasswordChangeIn, response: Response,
                    user: User = Depends(auth.require_user),
                    db: Session = Depends(get_db)):
    if not auth.verify_password(data.old_password, user.password_hash):
        raise HTTPException(401, "原密码不正确")
    user.password_hash = auth.hash_password(data.new_password)
    db.commit()
    db.refresh(user)
    # 密码指纹变了，其它设备上的旧 token 会自动失效；
    # 这里给当前这个会话补发一个新 token，免得改完密码把自己踢下线。
    token = auth.create_token(user)
    auth.set_session_cookie(response, token)
    return LoginOut(token=token, username=user.username)
