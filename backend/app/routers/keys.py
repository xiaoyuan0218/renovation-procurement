"""API 密钥：给外部程序用的长期凭据，在网页的设置里生成与撤销。

完整明文只在创建响应里出现一次（库里只留 sha256），所以这里没有「查看
密钥」这种端点 —— 忘了就撤销，再建一把。
"""

from typing import List

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import auth
from ..db import get_db
from ..models import ApiKey
from ..schemas import ApiKeyCreatedOut, ApiKeyIn, ApiKeyOut, OkOut

router = APIRouter(prefix="/api/keys", tags=["keys"])


@router.get("", response_model=List[ApiKeyOut])
def list_keys(db: Session = Depends(get_db)):
    """列出现有密钥：名称、前缀、创建与最后使用时间，没有明文。"""
    return db.query(ApiKey).order_by(ApiKey.id).all()


@router.post("", response_model=ApiKeyCreatedOut)
def create_key(data: ApiKeyIn, db: Session = Depends(get_db)):
    """新建一把密钥，响应里带完整明文 —— 这是它唯一一次露面。

    名字必填：列表里只有名字和前缀能区分谁是谁，堆一把没名字的密钥，
    过两天就没人认得出来该撤销哪把了。
    """
    name = data.name.strip()
    if not name:
        raise HTTPException(400, "给它起个名字，方便以后认出是给谁用的")
    raw = auth.generate_api_key()
    row = ApiKey(name=name, prefix=raw[:11], key_hash=auth.hash_api_key(raw))
    db.add(row)
    db.commit()
    db.refresh(row)
    return ApiKeyCreatedOut(
        id=row.id, name=row.name, prefix=row.prefix, key=raw,
        created_at=row.created_at, last_used_at=row.last_used_at,
    )


@router.delete("/{key_id}", response_model=OkOut)
def revoke_key(key_id: int, db: Session = Depends(get_db)):
    """撤销：直接删行，下一次带它的请求就认不出来了。"""
    row = db.get(ApiKey, key_id)
    if row is None:
        raise HTTPException(404, "密钥不存在，可能已经被撤销了")
    db.delete(row)
    db.commit()
    return OkOut()
