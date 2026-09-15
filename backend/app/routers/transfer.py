import datetime
from urllib.parse import quote

from fastapi import APIRouter, Depends, File, Form, HTTPException, Response, UploadFile
from sqlalchemy.orm import Session

from ..db import get_db
from ..services import excel_io

router = APIRouter(prefix="/api", tags=["transfer"])


@router.get("/export")
def export(db: Session = Depends(get_db)):
    content = excel_io.export_xlsx(db)
    filename = f"装修采购清单_{datetime.date.today():%Y%m%d}.xlsx"
    return _xlsx_response(content, filename)


@router.get("/import/template")
def import_template_file():
    content = excel_io.build_template()
    return _xlsx_response(content, "导入模板.xlsx")


@router.post("/import")
async def import_xlsx(file: UploadFile = File(...), mode: str = Form("replace"),
                      db: Session = Depends(get_db)):
    if mode not in ("replace", "merge"):
        raise HTTPException(400, "mode 仅支持 replace / merge")
    data = await file.read()
    try:
        report = excel_io.import_template(db, data, mode)
    except ValueError as e:
        raise HTTPException(400, f"导入失败：{e}")
    return report


def _xlsx_response(content: bytes, filename: str) -> Response:
    return Response(
        content=content,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"},
    )
