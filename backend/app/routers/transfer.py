import datetime
import re
from urllib.parse import quote

from fastapi import APIRouter, Depends, File, Form, HTTPException, Response, UploadFile
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_list
from ..models import ItemList
from ..services import excel_io

router = APIRouter(prefix="/api", tags=["transfer"])


def _safe_name(name: str) -> str:
    """清单名会拼进下载文件名，去掉 Windows 不接受的那些字符。"""
    cleaned = re.sub(r'[\\/:*?"<>|\r\n\t]+', "_", name or "").strip(" .")
    return cleaned or "清单"


@router.get("/export")
def export(lst: ItemList = Depends(current_list), db: Session = Depends(get_db)):
    content = excel_io.export_xlsx(db, list_id=lst.id)
    filename = f"{_safe_name(lst.name)}_{datetime.date.today():%Y%m%d}.xlsx"
    return _xlsx_response(content, filename)


@router.get("/import/template")
def import_template_file():
    # 模板与清单无关：列结构固定，示例行是通用的
    content = excel_io.build_template()
    return _xlsx_response(content, "导入模板.xlsx")


@router.post("/import")
async def import_xlsx(file: UploadFile = File(...), mode: str = Form("replace"),
                      lst: ItemList = Depends(current_list),
                      db: Session = Depends(get_db)):
    if mode not in ("replace", "merge"):
        raise HTTPException(400, "mode 仅支持 replace / merge")
    data = await file.read()
    try:
        report = excel_io.import_template(db, data, mode, list_id=lst.id)
    except ValueError as e:
        raise HTTPException(400, f"导入失败：{e}")
    return report


def _xlsx_response(content: bytes, filename: str) -> Response:
    return Response(
        content=content,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"},
    )
