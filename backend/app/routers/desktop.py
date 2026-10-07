"""桌面端专用接口：连远程服务器、把本地清单同步过去。

普通服务端部署用不到这些 —— 它们是「本机当客户端」这条路上的东西。守卫和其它
业务接口一样，登录后才能用。
"""

from contextlib import contextmanager

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from ..db import get_db
from ..services import desktop_sync
from ..services.remote_sync import RemoteError

router = APIRouter(prefix="/api/desktop", tags=["desktop"])


@contextmanager
def _as_http_error():
    """把同步层的可读错误翻成 400：消息和补充说明原样带给前端。"""
    try:
        yield
    except desktop_sync.SyncError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except RemoteError as exc:
        raise HTTPException(status_code=400, detail={
            "message": exc.message, "hint": exc.hint, "status": exc.status})


# ---------------------------------------------------------------- 请求体

class LoginIn(BaseModel):
    url: str
    username: str
    password: str


class UploadIn(BaseModel):
    list_id: int
    remote_list_id: int | None = None
    force: bool = False


class ResolveUploadIn(BaseModel):
    list_id: int
    remote_list_id: int
    choice: str          # merge_both / overwrite_remote / keep_remote


class PullIn(BaseModel):
    remote_list_id: int
    name: str = ""


class SyncIn(BaseModel):
    list_id: int
    prefer_local: bool = False


class ListIn(BaseModel):
    list_id: int


class AutoSyncIn(BaseModel):
    list_id: int
    enabled: bool


# ---------------------------------------------------------------- 登录与状态

@router.get("/state")
def state(db: Session = Depends(get_db)):
    """远程登录态 + 每份本地清单的绑定情况，设置页一进来就问这个。"""
    return {"session": desktop_sync.session_state(db),
            "bindings": desktop_sync.binding_state(db)}


@router.post("/login")
def login(data: LoginIn, db: Session = Depends(get_db)):
    with _as_http_error():
        return desktop_sync.login(db, data.url, data.username, data.password)


@router.post("/logout")
def logout(db: Session = Depends(get_db)):
    desktop_sync.logout(db)
    return {"ok": True}


@router.get("/remote-lists")
def remote_lists(db: Session = Depends(get_db)):
    """服务器上有哪些清单，供上传选目标、或拉取挑一份。"""
    with _as_http_error():
        return {"lists": desktop_sync.remote_lists(db)}


# ---------------------------------------------------------------- 上传 / 拉取 / 同步

@router.post("/upload")
def upload(data: UploadIn, db: Session = Depends(get_db)):
    with _as_http_error():
        return desktop_sync.upload(db, data.list_id, data.remote_list_id, data.force)


@router.post("/resolve-upload")
def resolve_upload(data: ResolveUploadIn, db: Session = Depends(get_db)):
    if data.choice not in ("merge_both", "overwrite_remote", "keep_remote"):
        raise HTTPException(400, "choice 只能是 merge_both / overwrite_remote / keep_remote")
    with _as_http_error():
        return desktop_sync.resolve_upload(db, data.list_id, data.remote_list_id, data.choice)


@router.post("/pull")
def pull(data: PullIn, db: Session = Depends(get_db)):
    with _as_http_error():
        return desktop_sync.pull_as_new(db, data.remote_list_id, data.name)


@router.post("/sync")
def sync(data: SyncIn, db: Session = Depends(get_db)):
    with _as_http_error():
        return desktop_sync.sync(db, data.list_id, data.prefer_local)


@router.post("/auto")
def auto(data: ListIn, db: Session = Depends(get_db)):
    """本地有改动时前端调它（防抖由前端做）。

    返回内容或绑定关系是否真的变了 —— 变了前端才刷新，否则刷一次又会触发一轮
    同步，自己把自己转起来。
    """
    with _as_http_error():
        return {"changed": desktop_sync.auto_sync(db, data.list_id)}


@router.post("/unbind")
def unbind(data: ListIn, db: Session = Depends(get_db)):
    desktop_sync.unbind(db, data.list_id)
    return {"ok": True}


@router.post("/set-auto-sync")
def set_auto_sync(data: AutoSyncIn, db: Session = Depends(get_db)):
    with _as_http_error():
        desktop_sync.set_auto_sync(db, data.list_id, data.enabled)
    return {"ok": True}
