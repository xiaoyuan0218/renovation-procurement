"""清单编号：给每份清单一个稳定、好念的短码。

名字会被改、也允许重名（服务器上可以并存两份「装修采购」），
编号不会变 —— 手机上那份和服务器上那份编号相同，一眼就能确认是同一份。

字母表去掉了容易看错的 0/O/1/I/L：这串码是要拿眼睛对、甚至口头念的。
"""

import secrets

ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
LENGTH = 8


def new_code() -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(LENGTH))


def normalize(raw) -> str:
    """把用户/客户端给的编号收拾成标准样子；不合法就返回空串（由调用方重新发一个）。"""
    text = "".join(ch for ch in str(raw or "").upper() if ch in ALPHABET)
    return text if len(text) == LENGTH else ""
