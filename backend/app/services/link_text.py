"""商品链接提取：从整段分享文案里取出第一个 http(s) URL。

电商分享文案的链接域五花八门（3.cn、xhslink.com、e.tb.cn、yangkeduo.com…），
白名单必漏；它们的共同点是文案里都带一条 http(s) URL，所以统一按「提取第一
个 http(s) URL」处理。

backend、web（ItemDialog.vue）、mobile/android（Kotlin 版 extractHttp）三端
照同一份 spec 逐字实现：同一文案在不同端提取出不同结果的话，同步就会互相
覆盖。改这里的正则或尾剥表时，另两端必须一起改。边界回归都收在
tests/test_item_link.py。
"""

import re

# URL 候选字符集：RFC 3986 的常用子集。中文字符、中文标点、全角空格、emoji
# 都不在集合内，URL 因此被整段文案天然截断，无需手工找边界。
_URL_RE = re.compile(r"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+",
                     re.IGNORECASE)

# 尾部剥除表：统一为纯 ASCII 十二字符。弯引号与中文标点进不了上面的字符集，
# 写进表里只会造成三端实现不一致的隐患。
_TAIL = frozenset(".,;:!?'\")]}")
# 收括号配对感知：候选串里对应开括号的出现次数**严格小于**该闭括号时，它才是
# 文案的包裹符而非 URL 的一部分（如「(see https://x.com/a))」）。朴素的字符集
# rstrip 会把「https://x.com/a_(page))」剥坏成「https://x.com/a_(page」——URL
# 自带的配对括号被误剥。'{'/'}' 进不了候选字符集，这里保留只为与另两端逐字同一。
_OPENING = {")": "(", "]": "[", "}": "{"}

# host 段非空：scheme:// 之后到第一个 / ? #（或串尾）之间至少要有一个 ASCII
# 字母或数字。没有这条，「https://...」经尾剥会穿透成「https://」入库，而各端
# 渲染前缀匹配都认它可点，用户点开是一串无效地址。
_HOST_RE = re.compile(r"^https?://[^/?#]*[A-Za-z0-9]", re.IGNORECASE)


def extract_link(value) -> str:
    """从文案里提取第一个 http(s) URL；提取不出、不合法或超长都返回空串。

    步骤：去首尾空白 → 正则找第一个候选 → 尾部逐字符剥句读（括号配对感知）
    → scheme 与 host 校验 → 超 500 字符清空。对已是纯 URL 的输入幂等，所以
    pydantic 入口与落地层双重收口互不冲突。
    """
    text = (value or "").strip()
    m = _URL_RE.search(text)
    if m is None:
        return ""
    cand = m.group(0)
    # 尾部剥除：普通标点直接摘；收括号只在"开括号比闭括号少"时才摘（说明
    # 它是包裹符）。单一 while 循环逐字符判断，不用字符集一次性 strip。
    while cand and cand[-1] in _TAIL:
        ch = cand[-1]
        if ch in _OPENING and cand.count(_OPENING[ch]) >= cand.count(ch):
            break
        cand = cand[:-1]
    # scheme 前缀 + host 非空，两条校验都过才放行
    if not cand.lower().startswith(("http://", "https://")):
        return ""
    if not _HOST_RE.match(cand):
        return ""
    # URL 不能截断（截了就坏）：超长整体清空。收口放在提取函数本体而不是
    # 只在某个入口，三端口径才一致，本地库也不会存进超长链接。
    if len(cand) > 500:
        return ""
    return cand
