"""付款日期的规范化。

存进去的值统一是 `YYYY-MM-DD` 或空字符串。历史数据里混着从 Excel 单元格直接
`str()` 出来的值（"2026-09-14 00:00:00"），这类脏值不做回写清理，只在**读出来**
的时候归一化 —— 为了一个格式差异去改用户的钱账数据，风险远大于收益。

三个口径：
  - `clean`    写入口：归一化后必须是空的或合法的 YYYY-MM-DD，否则报错；
  - `for_read` 读出口：能认出来就归一化，认不出来原样返回（不丢信息）；
  - `month_of` 聚合用：取 YYYY-MM，认不出来返回 None。
"""

import datetime
import re

_LOOSE = re.compile(r"^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T].*)?$")


def _valid_iso(text: str) -> bool:
    """严格的 YYYY-MM-DD，而且要是真实存在的日期（挡掉 2026-02-31 这种）。"""
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", text):
        return False
    try:
        datetime.date.fromisoformat(text)
        return True
    except ValueError:
        return False


def for_read(value) -> str:
    if value is None:
        return ""
    if isinstance(value, datetime.datetime):
        return value.date().isoformat()
    if isinstance(value, datetime.date):
        return value.isoformat()
    text = str(value).strip()
    if not text:
        return ""
    if _valid_iso(text):
        return text
    loose = _LOOSE.match(text)
    if loose:
        year, month, day = (int(g) for g in loose.groups())
        try:
            return datetime.date(year, month, day).isoformat()
        except ValueError:
            return text
    return text


def clean(value) -> str:
    """写入口径。不合法时抛 ValueError，由接口层转成 4xx。"""
    text = for_read(value)
    if not text:
        return ""
    if not _valid_iso(text):
        raise ValueError("付款日期要写成 2026-09-14 这样的格式（或留空）")
    return text


def month_of(value) -> str | None:
    text = for_read(value)
    return text[:7] if _valid_iso(text) else None
