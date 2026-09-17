"""清单级同步：整份导出、整份灌回、或者新建一份。

给手机单机版用 —— 它默认离线，需要时才把本地清单搬到服务器、或把服务器上的
清单带回手机。网页端用不到这三个接口：它本来就在服务器上，看到的就是同一份数据。

冲突检测靠**内容指纹**而不是版本号字段：版本号要在十几个写入点上都记得加一，
漏一处就永久失准；指纹每次现算，不可能漏。
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import migrations
from ..db import get_db
from ..models import ItemList
from ..schemas import SyncPayload, SyncPushIn
from ..services import list_transfer

router = APIRouter(prefix="/api/sync", tags=["sync"])


def _list_or_404(db: Session, list_id: int) -> ItemList:
    lst = db.get(ItemList, list_id)
    if lst is None:
        raise HTTPException(404, "清单不存在，可能已在其他设备上删除")
    return lst


def _snapshot(db: Session, lst: ItemList) -> dict:
    """导出 + 指纹。推送成功后也返回它，客户端一次往返就能对齐。"""
    payload = list_transfer.export_list(db, lst)
    return {"fingerprint": list_transfer.fingerprint(payload), "payload": payload}


@router.get("/lists/{list_id}")
def export_one(list_id: int, db: Session = Depends(get_db)):
    """导出一份清单的全量内容（手机端拿它覆盖本地，或者拷到本地新建一份）。"""
    return _snapshot(db, _list_or_404(db, list_id))


@router.put("/lists/{list_id}")
def replace_one(list_id: int, body: SyncPushIn, db: Session = Depends(get_db)):
    """整份替换一份清单的内容 —— 以推送方为准。

    带 `base_fingerprint`：与服务器当前指纹对不上就返回 409，说明两边都改过，
    该由用户决定谁说了算（正常合并由客户端先做完，走这里的是"以我为准"这条路径）。
    带 `force=true` 则跳过检查，并在覆盖前把整库留一份备份。
    """
    lst = _list_or_404(db, list_id)
    current = list_transfer.fingerprint(list_transfer.export_list(db, lst))
    if not body.force and body.base_fingerprint and body.base_fingerprint != current:
        raise HTTPException(409, detail={
            "message": "服务器上这份清单在你上次同步之后也改过",
            "current_fingerprint": current,
        })
    if body.force:
        # 用户明确选了"以我为准"：覆盖前留一份，选错了能救回来
        migrations.backup_db_file()
    list_transfer.import_list(db, body.model_dump(), target=lst)
    db.commit()
    return _snapshot(db, lst)


@router.post("/lists")
def create_one(body: SyncPayload, db: Session = Depends(get_db)):
    """新建一份清单并灌入内容（把手机上的清单搬上来，服务器原有数据不受影响）。"""
    try:
        lst = list_transfer.import_list(db, body.model_dump(), target=None)
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    db.commit()
    return {"list_id": lst.id, **_snapshot(db, lst)}
