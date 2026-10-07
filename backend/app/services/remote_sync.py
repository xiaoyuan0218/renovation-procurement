"""桌面端主动去连远程服务器（同步的「客户端那一侧」）。

后端本身是纯服务端、从不主动外呼；桌面端要能当客户端，就得有这一层。它只做
一件事：把远程的 `/api/auth/login` 和 `/api/sync/*` 包成好用的方法，并把网络
与状态码错误翻成用户看得懂的中文 —— 文案与手机单机版（`SyncApi.kt`）逐条对齐，
同一个问题在两个端上应该给同样的说法。
"""

import urllib.parse

import httpx

# 连接 8 秒、读取 30 秒：局域网里连不上要立刻说，而整份清单的推送允许慢一点
_TIMEOUT = httpx.Timeout(30.0, connect=8.0)

# 服务器地址里省略端口时的默认值，与手机端 ServerAddress 一致
DEFAULT_PORT = 8000


class RemoteError(Exception):
    """连远程服务器时的可读错误。`status` 是 HTTP 状态码（网络层错误时为 None）。"""

    def __init__(self, message: str, hint: str = "", status: int | None = None):
        super().__init__(message)
        self.message = message
        self.hint = hint
        self.status = status

    def __str__(self) -> str:
        return f"{self.message}（{self.hint}）" if self.hint else self.message


def normalize_base_url(raw: str) -> str:
    """把用户随手填的地址收拾成合法 base url。

    `192.168.1.9:8000` 这种省略协议、甚至省略端口的写法必须支持 —— 原样塞给
    HTTP 客户端只会抛一串英文，用户看到的就是「连不上」。规则与手机端
    `ServerAddress.parse` 保持一致。
    """
    text = (raw or "").strip()
    if not text:
        raise RemoteError("先填服务器地址")
    if "://" not in text:
        text = "http://" + text

    parsed = urllib.parse.urlsplit(text)
    if parsed.scheme not in ("http", "https"):
        raise RemoteError(f"不认识的协议「{parsed.scheme}」，只支持 http 和 https")
    host = parsed.hostname
    if not host:
        raise RemoteError("地址里没看出主机名，例如 192.168.1.9:8000")
    try:
        port = parsed.port
    except ValueError:
        raise RemoteError("端口不是数字")
    if port is None:
        port = DEFAULT_PORT
    if not 1 <= port <= 65535:
        raise RemoteError(f"端口 {port} 超出范围")

    # IPv6 归一化后要加回方括号，否则拼出来的 URL 没法解析
    host_out = f"[{host}]" if ":" in host else host
    path = (parsed.path or "").rstrip("/")
    return f"{parsed.scheme}://{host_out}:{port}{path}"


def _raise_for_status(response: httpx.Response) -> None:
    """把非 2xx 翻成带中文说明的 RemoteError。"""
    if response.is_success:
        return
    # 旧版服务器没有 /api/sync，通配路由会把 index.html 吐回来（以 < 开头）
    if response.text.lstrip().startswith("<"):
        raise RemoteError("服务器上还是旧版，没有同步功能 —— 先把服务器升级到最新版再试",
                          status=426)
    status = response.status_code
    detail = ""
    try:
        body = response.json()
        detail = body.get("detail") if isinstance(body, dict) else ""
        if isinstance(detail, dict):
            detail = detail.get("message", "")
    except ValueError:
        detail = ""

    if status == 401:
        raise RemoteError(detail or "账号或密码不对", "账号和密码就是网页版登录用的那一套",
                          status=401)
    if status == 404:
        raise RemoteError(detail or "服务器上找不到这份清单，可能已被删除", status=404)
    if status == 409:
        raise RemoteError(detail or "服务器上这份清单在你上次同步之后也改过", status=409)
    if status == 422:
        raise RemoteError("提交的数据格式不正确", status=422)
    raise RemoteError(f"请求失败（{status}）{('：' + detail) if detail else ''}", status=status)


def _translate_network_error(exc: Exception) -> RemoteError:
    if isinstance(exc, httpx.ConnectTimeout):
        return RemoteError("连接超时", "网络能通但服务器一直没响应，确认服务还在运行")
    if isinstance(exc, httpx.ReadTimeout):
        return RemoteError("连接超时", "服务器响应太慢，稍后再试")
    if isinstance(exc, httpx.ConnectError):
        text = str(exc).lower()
        if "getaddrinfo" in text or "name or service not known" in text or "nodename" in text:
            return RemoteError("找不到这个地址", "检查服务器地址有没有输错")
        return RemoteError("连不上服务器", "地址和端口对不对？要和服务器在同一个网络里")
    return RemoteError("网络错误", f"确认与服务器在同一网络，以及地址、端口是否正确（{exc}）")


class RemoteClient:
    """一个远程服务器上的调用集合。token 为空时只能调 login。

    `transport` 只在测试里给：注入一个直接打到本进程 app 的传输层，省得起一个
    真的服务器。生产代码不传，走真实网络。
    """

    def __init__(self, base_url: str, token: str = "", transport=None):
        self.base_url = normalize_base_url(base_url)
        self.token = token or ""
        self._transport = transport

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.token}"} if self.token else {}

    def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        try:
            with httpx.Client(base_url=self.base_url, timeout=_TIMEOUT,
                              headers=self._headers(),
                              transport=self._transport) as client:
                response = client.request(method, path, **kwargs)
        except httpx.HTTPError as exc:
            raise _translate_network_error(exc) from exc
        _raise_for_status(response)
        return response

    # ---------------------------------------------------------------- 接口

    def login(self, username: str, password: str) -> dict:
        response = self._request("POST", "/api/auth/login",
                                 json={"username": username, "password": password})
        return response.json()

    def lists(self) -> list:
        return self._request("GET", "/api/lists").json()

    def export_list(self, remote_list_id: int) -> dict:
        """取远程清单的全量快照：`{fingerprint, payload}`。"""
        return self._request("GET", f"/api/sync/lists/{remote_list_id}").json()

    def push_list(self, remote_list_id: int, payload: dict,
                  base_fingerprint: str | None = None, force: bool = False) -> dict:
        body = dict(payload)
        body["base_fingerprint"] = base_fingerprint
        body["force"] = force
        return self._request("PUT", f"/api/sync/lists/{remote_list_id}", json=body).json()

    def create_list(self, payload: dict) -> dict:
        """在远程新建一份清单并灌入，返回 `{list_id, fingerprint, payload}`。"""
        return self._request("POST", "/api/sync/lists", json=payload).json()
