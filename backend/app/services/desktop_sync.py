"""桌面端同步编排：把「导出 / 覆盖 / 新建」三个远程接口编排成用户看得懂的动作。

对应手机单机版的 `SyncEngine`。桌面端和手机端是同一套算法的两个实现，行为必须
一致 —— 同一份清单在两边同步，合并结果得一样，否则会来回打架。

四件事：**上传**（本地清单搬到服务器）、**拉取**（服务器清单带到本地）、
**双向同步**（已绑定清单对齐）、**自动同步**（本地一改动就安静对齐一次）。

冲突一律不擅自决定：把冲突列出来交给界面让用户选，选完再调一次
（`prefer_local` 决定听谁的）。
"""

import json

from sqlalchemy.orm import Session

from ..models import ItemList, RemoteSession, SyncBinding, utcnow
from . import codes, list_transfer, local_apply, merge, record_keys
from .remote_sync import RemoteClient, RemoteError


class SyncError(Exception):
    """本地就能判定的同步错误（没绑定、没登录之类），不用发请求。"""


def _client(db: Session, binding: SyncBinding | None = None) -> RemoteClient:
    session = get_session(db)
    if not session.token:
        raise SyncError("还没连服务器，先在上面填地址和账号")
    url = (binding.server_url if binding is not None and binding.server_url
           else session.server_url)
    return RemoteClient(url, session.token)


# ---------------------------------------------------------------- 远程登录凭证

def get_session(db: Session) -> RemoteSession:
    row = db.query(RemoteSession).first()
    if row is None:
        row = RemoteSession(server_url="", username="", token="")
        db.add(row)
        db.commit()
        db.refresh(row)
    return row


def login(db: Session, url: str, username: str, password: str) -> dict:
    client = RemoteClient(url)          # 地址不合法会在这里就抛出可读错误
    result = client.login(username.strip(), password)
    row = get_session(db)
    row.server_url = client.base_url
    row.username = result.get("username") or username.strip()
    row.token = result.get("token") or ""
    db.commit()
    return {"server_url": row.server_url, "username": row.username}


def logout(db: Session) -> None:
    row = get_session(db)
    # 只清账号和 token，地址留着 —— 重新登录时不用再输一遍
    row.username = ""
    row.token = ""
    db.commit()


def session_state(db: Session) -> dict:
    row = get_session(db)
    return {"server_url": row.server_url, "username": row.username,
            "logged_in": bool(row.token)}


# ---------------------------------------------------------------- 绑定状态

def binding_state(db: Session) -> dict:
    out = {}
    for row in db.query(SyncBinding).all():
        out[str(row.list_id)] = {
            "remote_list_id": row.remote_list_id,
            "remote_name": row.remote_name,
            "last_synced_at": row.last_synced_at.strftime("%Y-%m-%d %H:%M:%S")
            if row.last_synced_at else "",
            "auto_sync": bool(row.auto_sync),
        }
    return out


def unbind(db: Session, list_id: int) -> None:
    row = db.get(SyncBinding, list_id)
    if row is not None:
        db.delete(row)
        db.commit()


def set_auto_sync(db: Session, list_id: int, enabled: bool) -> None:
    row = db.get(SyncBinding, list_id)
    if row is None:
        raise SyncError("这份清单还没绑定服务器")
    row.auto_sync = bool(enabled)
    db.commit()


def remote_lists(db: Session) -> list:
    return _client(db).lists()


# ---------------------------------------------------------------- 基线

def _read_baseline(binding: SyncBinding) -> merge.Baseline:
    if not binding.baseline:
        return merge.Baseline()
    try:
        data = json.loads(binding.baseline)
    except (ValueError, TypeError):
        return merge.Baseline()
    if not isinstance(data, dict):
        return merge.Baseline()
    return merge.Baseline(payload=data.get("payload") or {},
                          local_map=data.get("local_map") or {})


def _save_binding(db: Session, list_id: int, remote_list_id: int, remote_name: str,
                  fingerprint: str, payload: dict, local_map: dict) -> None:
    """记下绑定关系与基线。已存在就更新，`auto_sync` 保持用户原来的选择。"""
    binding = db.get(SyncBinding, list_id)
    session = get_session(db)
    baseline = json.dumps({"payload": payload, "local_map": local_map},
                          ensure_ascii=False)
    if binding is None:
        binding = SyncBinding(list_id=list_id, auto_sync=True)
        db.add(binding)
    binding.server_url = session.server_url
    binding.remote_list_id = remote_list_id
    binding.remote_name = remote_name
    binding.fingerprint = fingerprint
    binding.baseline = baseline
    binding.last_synced_at = utcnow()
    db.commit()


def _adopt(db: Session, lst: ItemList, remote_list_id: int, snapshot: dict) -> None:
    """把推送/拉取回来的结果落到本地，并记下新的基线（含 id 映射）。

    兼容层：服务器还是 1.2.2 之前的老版本时，返回的 payload 里没有 is_deposit ——
    "没带这个字段"不等于"这条不是定金"，落地前先按业务键把本地旧值填进去
    （填在 payload 本身，基线也照它存），否则本地刚勾的定金会在同步回落时被抹掉，
    而且基线会与本地内容不一致，每轮都被当成"本地有新改动"反复推送。
    """
    record_keys.fill_missing_deposit(db, lst.id, snapshot["payload"])
    local_map = local_apply.apply_payload(db, lst, snapshot["payload"])
    db.commit()
    _save_binding(db, lst.id, remote_list_id,
                  snapshot["payload"]["list"].get("name", ""),
                  snapshot["fingerprint"], snapshot["payload"], local_map)


def _summarize(payload: dict) -> dict:
    """数一数一边有多少内容、最后是什么时候动的，让用户判断哪边更全更新。

    最后改动时间取**内容里最新的那个**，不是清单自身的 `updated_at` —— 后者只在
    改清单名/备注时才刷新，改物料根本不动它，拿它当「最后改动」会一直显示很久
    以前，反而误导。
    """
    stamps = [payload.get("list", {}).get("updated_at") or ""]
    for key in ("rooms", "categories", "items", "expenses"):
        stamps += [row.get("updated_at") or "" for row in payload.get(key, [])]
    stamps = [s for s in stamps if s]
    return {
        "items": len(payload.get("items", [])),
        "rooms": len(payload.get("rooms", [])),
        "categories": len(payload.get("categories", [])),
        "last_changed_at": max(stamps) if stamps else "",
    }


def _find_remote_by_code(client: RemoteClient, code: str):
    """按编号在服务器清单里找同一份；本地没编号（老数据）就返回 None。"""
    wanted = codes.normalize(code)
    if not wanted:
        return None
    for row in client.lists():
        if codes.normalize(row.get("code")) == wanted:
            return row.get("id")
    return None


# ---------------------------------------------------------------- 上传

def upload(db: Session, list_id: int, remote_list_id: int | None = None,
           force: bool = False) -> dict:
    """把本地清单搬到服务器。

    按**编号**决定这是新建还是撞上了已有那份。撞上了又分两种情况：之前绑过、
    有共同基线 → 直接走三方合并，两边改动都保住（常规路径）；从没同步过、
    没有基线 → 分不清谁改了什么，把两边样子带回去让用户定。
    """
    lst = db.get(ItemList, list_id)
    if lst is None:
        raise SyncError("清单不存在")
    client = _client(db)
    payload = list_transfer.export_list(db, lst)

    target = remote_list_id or _find_remote_by_code(client, lst.code)
    if target is None:
        created = client.create_list(payload)
        _adopt(db, lst, created["list_id"], created)
        return {"created_list_id": created["list_id"],
                "notice": "已上传到服务器，以后可以和这份清单双向同步"}

    binding = db.get(SyncBinding, list_id)
    if binding is not None and binding.baseline:
        return sync(db, list_id, prefer_local=force)

    remote = client.export_list(target)
    return {"needs_upload_decision": {
        "remote_list_id": target,
        "local": _summarize(payload),
        "remote": _summarize(remote["payload"]),
    }}


def resolve_upload(db: Session, list_id: int, remote_list_id: int,
                   choice: str, direction: str = "push") -> dict:
    """用户在两份撞上同一编号的选择框里选完之后真正执行。

    `direction` 是入口：上传撞车（push）还是拉取撞车（pull）。四种选法里只有
    「另存一份」两边含义相反 —— 上传时把**本机**这份存到服务器，拉取时把
    **服务器**那份存到本地；其余三种（合并、以某边为准）方向无关。
    """
    lst = db.get(ItemList, list_id)
    if lst is None:
        raise SyncError("清单不存在")
    client = _client(db)
    mine = list_transfer.export_list(db, lst)
    snapshot = client.export_list(remote_list_id)

    if choice == "keep_remote":
        # 听服务器的：本地换成它那份。这条不推送，服务器上原样不动
        _adopt(db, lst, remote_list_id, snapshot)
        return {"notice": "已改用服务器上的内容"}

    if choice == "create_new":
        if direction == "pull":
            # 拉取撞车时的「另存一份」：把**服务器那份**另存成本地的一份新清单
            # （编号自动换新，本地已有那份原样不动），两边的内容都留着。新份是
            # 独立副本，不建立绑定 —— 它和服务器那份编号不同，绑了反而让
            # "哪份是哪份"乱掉。
            remote_payload = snapshot["payload"]
            copy_name = (remote_payload.get("list", {}).get("name")
                         or lst.name or "副本")
            copy, _local_map = local_apply.create_list_from_payload(
                db, remote_payload, copy_name, fresh_code=True)
            db.commit()
            return {"created_list_id": copy.id,
                    "notice": "已把服务器那份另存为本地的一份新清单（编号自动换新），两份都留着"}
        # 上传撞车时的「另存一份」：把本机这份作为一份新清单传到服务器（编号由
        # 服务器分配，不会跟原来那份撞），本机跟着改用新编号并与它绑定；服务器
        # 原来那份原样不动，两份从此各走各的
        created = client.create_list(mine)
        _adopt(db, lst, created["list_id"], created)
        return {"notice": "已另存为服务器上的一份新清单，两边的内容都留着"}

    if choice == "overwrite_remote":
        to_push = mine
        # 覆盖是用户明确要的「以我为准」：带 force 跳过指纹校验
        pushed = client.push_list(remote_list_id, to_push, base_fingerprint=None,
                                  force=True)
        _adopt(db, lst, remote_list_id, pushed)
        return {"notice": "已用本机上的内容覆盖服务器"}

    # merge_both：按名字合并两边。基于刚取到的服务器内容算出来，就带上它的指纹 ——
    # 万一这中间服务器又变了，宁可报错让用户重来，也别把没算进去的改动抹掉
    merged = merge.merge_without_base(mine, snapshot["payload"])
    pushed = client.push_list(remote_list_id, merged,
                              base_fingerprint=snapshot["fingerprint"], force=False)
    _adopt(db, lst, remote_list_id, pushed)
    return {"notice": "两边已合并，各自独有的都留着"}


# ---------------------------------------------------------------- 拉取

def pull_as_new(db: Session, remote_list_id: int, name: str) -> dict:
    """把服务器上的一份清单拉到本地。

    按**编号**先看本地有没有同一份：没有就新建一份；有（说明它本来就在本地，
    比如解绑后还想再拉一遍）就**交给用户定怎么对齐** —— 与「上传撞上同一份」
    共用同一个选择框和同一个 resolve。

    为什么不能直接拿服务器那份覆盖本地：解绑之后共同基线没了，谁改了什么判不
    出来（从前这里直接覆盖，本地改的东西无声消失；反方向合并也会把服务器盖掉）。
    没有基线时正确的做法是让用户看着两边的内容选，而不是替他猜。
    """
    client = _client(db)
    snapshot = client.export_list(remote_list_id)
    payload = snapshot["payload"]
    remote_name = name or payload.get("list", {}).get("name") or "未命名清单"

    incoming = codes.normalize(payload.get("list", {}).get("code"))
    existing = None
    if incoming:
        existing = db.query(ItemList).filter(ItemList.code == incoming).first()

    if existing is not None:
        return {"needs_upload_decision": {
            "list_id": existing.id,
            "remote_list_id": remote_list_id,
            # 从拉取入口进来的：「另存一份」在两边含义相反，前端要把它带回来
            "direction": "pull",
            "local": _summarize(list_transfer.export_list(db, existing)),
            "remote": _summarize(payload),
        }}

    lst, local_map = local_apply.create_list_from_payload(db, payload, remote_name)
    db.commit()
    _save_binding(db, lst.id, remote_list_id, remote_name, snapshot["fingerprint"],
                  payload, local_map)
    return {"created_list_id": lst.id, "notice": "已拉到本地，之后可以和它双向同步"}


# ---------------------------------------------------------------- 双向同步

def sync(db: Session, list_id: int, prefer_local: bool = False) -> dict:
    """和服务器对齐。

    `prefer_local` 只在「两边都改过同一行」时起作用：false 听服务器的、true 听
    本机的。有冲突且用户还没表态时不会推送，先把冲突列出来让界面问。
    """
    binding = db.get(SyncBinding, list_id)
    if binding is None:
        raise SyncError("这份清单还没绑定服务器")

    client = _client(db, binding)
    try:
        snapshot = client.export_list(binding.remote_list_id)
    except RemoteError as exc:
        if exc.status == 404:
            # 服务器上那份被删了（多半是在网页版删的）：直接解除绑定，本地数据
            # 原样留着，变回一份纯本地清单
            name = binding.remote_name or "这份清单"
            db.delete(binding)
            db.commit()
            return {"remote_missing": True,
                    "notice": f"服务器上的「{name}」已被删除，已解除绑定；"
                              f"这份清单继续留在这台电脑上"}
        raise
    return _sync_with(db, binding, snapshot, prefer_local)


def _sync_with(db: Session, binding: SyncBinding, snapshot: dict,
               prefer_local: bool) -> dict:
    lst = db.get(ItemList, binding.list_id)
    if lst is None:
        raise SyncError("清单不存在")
    mine = list_transfer.export_list(db, lst)
    base = _read_baseline(binding)

    remote_changed = snapshot["fingerprint"] != binding.fingerprint
    if remote_changed:
        merged = merge.merge(base, mine, snapshot["payload"], prefer_local)
        if merged.conflicts and not prefer_local:
            return {"conflicts": merged.conflicts}
        payload = merged.payload
        conflicts = merged.conflicts
    else:
        # 服务器没动过：本地也跟基线一模一样时什么都别做。推上去会让服务器把
        # 整份内容重建一遍（id 全变），落回本地又是全新的 id，于是「内容变了」
        # 再次成立，自动同步会被自己一轮轮触发下去
        if _same_content(mine, base.payload):
            return {}
        payload = mine
        conflicts = []

    client = _client(db, binding)
    pushed = client.push_list(binding.remote_list_id, payload,
                              base_fingerprint=snapshot["fingerprint"],
                              force=remote_changed and prefer_local)
    _adopt(db, lst, binding.remote_list_id, pushed)
    # prefer_local 的这轮推送是用户在冲突框里拍板后的执行 —— 那些冲突已经按他的
    # 意愿解决了，再报回去会让他对着同一个冲突选两次
    return {"conflicts": [] if prefer_local else conflicts}


def _same_content(a: dict, b: dict) -> bool:
    """两份内容是不是「同一份」。用语义指纹比：忽略 id、时间戳与行序。

    必须忽略 id：本地是自增主键、服务器是另一套自增，同一份内容的 id 天然不同；
    覆盖之后本地行还会整体重排。带上它们比，就会把「没变」误判成「变了」——
    那正是自动同步自我触发的成因。
    """
    return list_transfer.fingerprint(a) == list_transfer.fingerprint(b)


# ---------------------------------------------------------------- 自动同步

def auto_sync(db: Session, list_id: int | None) -> bool:
    """本地一有改动就调它。

    已登录、这份清单已绑定、且开着自动对齐时才安静对齐一次；离线、失败、有冲突
    —— 一律返回 false 不动声色：自动同步不该打扰用户。

    返回值表示**本地内容或绑定关系是否真的变了**：变了前端要刷新；没变就别刷，
    刷界面会再触发一轮同步，白跑。
    """
    if list_id is None:
        return False
    session = get_session(db)
    if not session.token:
        return False
    binding = db.get(SyncBinding, list_id)
    if binding is None or not binding.auto_sync:
        return False

    lst = db.get(ItemList, list_id)
    if lst is None:
        return False
    before = list_transfer.fingerprint(list_transfer.export_list(db, lst))
    try:
        result = sync(db, list_id)
    except (SyncError, RemoteError):
        return False
    if result.get("conflicts"):
        return False
    if result.get("remote_missing"):
        return True
    db.expire_all()
    lst = db.get(ItemList, list_id)
    after = list_transfer.fingerprint(list_transfer.export_list(db, lst))
    return before != after
