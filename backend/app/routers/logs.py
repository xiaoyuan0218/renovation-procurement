"""操作日志：查询与回退。

回退的约束见 app/audit.py —— 只允许回退最近一次操作，中间夹了任何别的
写操作（包括无法撤销的导入、同步）就不能再撤更早的。
"""

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session

from .. import audit
from ..auth import require_user
from ..db import get_db
from ..models import OperationLog, utcnow

router = APIRouter(prefix="/api/logs", tags=["logs"])


def _view(row: OperationLog, undoable_id: int | None) -> dict:
    return {
        "id": row.id,
        "at": row.at.strftime("%Y-%m-%d %H:%M:%S") if row.at else "",
        "actor_kind": row.actor_kind,
        "actor_name": row.actor_name,
        "action": row.action,
        "method": row.method,
        "path": row.path,
        "status_code": row.status_code,
        # 只有「最新一条写操作且它带快照」才允许回退
        "can_undo": row.id == undoable_id and row.undo_kind is not None,
        "undone": row.undone_at is not None,
    }


@router.get("")
def list_logs(page: int = 1, page_size: int = 50,
              user=Depends(require_user), db: Session = Depends(get_db)):
    """操作日志，最新在前。can_undo 为 true 的那条是当前能一键回退的。"""
    page = max(1, page)
    page_size = min(max(1, page_size), 200)
    undoable = audit.undoable_id(db)
    total = db.query(OperationLog.id).count()
    rows = (db.query(OperationLog).order_by(OperationLog.id.desc())
            .offset((page - 1) * page_size).limit(page_size).all())
    return {
        "total": total,
        "undoable_id": undoable,
        "items": [_view(row, undoable) for row in rows],
    }


@router.post("/{log_id}/undo")
def undo_log(log_id: int, request: Request, user=Depends(require_user),
             db: Session = Depends(get_db)):
    """回退这条操作：把数据放回它发生之前的样子。"""
    row = db.get(OperationLog, log_id)
    if row is None or row.undo_kind is None:
        raise HTTPException(404, "这条日志不支持回退")
    if row.id != audit.undoable_id(db):
        raise HTTPException(
            409, "只能回退最近一次操作 —— 它之后已经有别的改动，"
                 "现在回退会把那些改动一起覆盖掉")
    message = audit.undo_log(db, row)
    # 快照消费掉了：标记后这条不再被视为「可回退」，连续回退时自动往前找
    row.undone_at = utcnow()
    db.commit()
    request.state.audit_subject = f"「{row.action}」"
    return {"ok": True, "message": message}
