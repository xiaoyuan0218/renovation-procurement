import io

import openpyxl
import pytest

from app.db import SessionLocal, engine
from app.models import Allocation, Category, Item, PurchaseRecord, Room
from app.seed import init_db
from app.services import excel_io


@pytest.fixture()
def db():
    init_db()
    session = SessionLocal()
    for table in (Allocation, Item, PurchaseRecord, Room, Category):
        session.query(table).delete()
    session.commit()
    yield session
    session.close()
    engine.dispose()


def _flat_wb():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "物料汇总"
    ws.append(["类目", "物料名称", "单位", "数量", "单价", "日常单价",
               "日常价", "实付数量", "实付金额", "未付数量", "未付金额",
               "已购", "备注"])
    ws.append(["照明", "筒灯", "个", 10, 49, 39.39, None, None, None, None, None, None, "否", ""])
    ws.append(["网络", "网线", "米", 150, 4, None, None, None, None, None, None, None, "否", ""])
    ws2 = wb.create_sheet("布点明细")
    ws2.append(["物料名称", "房间", "数量", "单价", "备注"])
    ws2.append(["筒灯", "客厅", 3, None, ""])
    ws2.append(["筒灯", "餐厅", 4, 59, "餐厅用贵的"])
    ws2.append(["网线", "客厅", 0, None, ""])  # 0 数量应被忽略
    ws3 = wb.create_sheet("采购记录")
    ws3.append(["物料名称", "实付数量", "实付金额", "付款日期", "备注"])
    ws3.append(["筒灯", 3, 150, "2026-09-01", "第一批"])
    ws3.append(["筒灯", 4, 236.09, "2026-09-10", "第二批"])
    ws3.append(["网线", 150, 661.2, None, ""])
    ws3.append(["网线", 0, 0, None, ""])  # 空记录应被忽略
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def test_template_import_replace(db):
    report = excel_io.import_template(db, _flat_wb(), mode="replace")
    assert report["items_created"] == 2
    assert report["allocations"] == 2  # 0 数量行被忽略
    assert report["records"] == 3  # 空记录被忽略

    db.expire_all()  # 清掉 session 内已加载的关系缓存
    items = {i.name: i for i in db.query(Item).all()}
    deng = items["筒灯"]
    # 有布点 → 总量以布点合计为准 (3+4)
    assert deng.allocations and len(deng.allocations) == 2
    from app.services import compute
    d = compute.item_dict(deng)
    assert d["total_qty"] == 7
    # 布点单价覆盖：3×49 + 4×59
    assert d["list_total"] == 3 * 49 + 4 * 59
    assert d["discount_total"] == pytest.approx(39.39 * 7, abs=0.001)
    # 两笔采购记录：数量金额分别累加，实付单价=金额÷数量
    assert d["paid_qty"] == 7
    assert d["paid"] == pytest.approx(150 + 236.09, abs=0.01)
    assert d["paid_price"] == pytest.approx((150 + 236.09) / 7, abs=0.01)
    assert d["status"] == "done"
    assert len(d["records"]) == 2 and d["records"][0]["note"] == "第一批"

    wire = items["网线"]
    dw = compute.item_dict(wire)
    assert wire.qty_total == 150
    assert wire.discount_price is None  # 留空 → 按原价
    assert dw["paid_qty"] == 150 and dw["status"] == "done"
    assert compute.item_discount_total(wire) == 600
    assert compute.item_unpaid(wire) == 0  # 网线已记录一笔 661.2 → 数量150全覆盖


def test_template_import_merge(db):
    excel_io.import_template(db, _flat_wb(), mode="replace")
    # 再导一次 merge：同名物料更新而不是新建
    report = excel_io.import_template(db, _flat_wb(), mode="merge")
    assert report["items_matched"] == 2
    assert report["items_created"] == 0
    assert db.query(Item).count() == 2
    assert db.query(Allocation).count() == 2


def test_template_import_bad_format(db):
    wb = openpyxl.Workbook()
    wb.active.append(["随便什么表"])
    buf = io.BytesIO()
    wb.save(buf)
    with pytest.raises(ValueError):
        excel_io.import_template(db, buf.getvalue(), mode="replace")


def test_export_roundtrip(db):
    excel_io.import_template(db, _flat_wb(), mode="replace")
    data = excel_io.export_xlsx(db)
    # 导出的文件能按模板回灌
    report = excel_io.import_template(db, data, mode="replace")
    import openpyxl as _op
    _wb2 = _op.load_workbook(io.BytesIO(data))
    print('第2次导入文件布点行:', [r for r in _wb2['布点明细'].iter_rows(min_row=2, values_only=True)])
    print('第2次导入解析allocs:', [(a['item_name'], a['room'], a['qty'], a['price_override']) for a in excel_io._parse_flat(data)['allocs']])
    assert report["items_created"] == 2
    assert report["allocations"] == 2
    from app.services import compute
    deng = db.query(Item).filter(Item.name == "筒灯").one()
    d = compute.item_dict(deng)
    assert d["list_total"] == 3 * 49 + 4 * 59
