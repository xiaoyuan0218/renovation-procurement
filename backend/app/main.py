import os

from fastapi import Depends, FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.openapi.docs import get_swagger_ui_html
from fastapi.openapi.utils import get_openapi
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session

from .auth import require_user
from .db import get_db
from .routers import (auth, base_data, backup, expenses, items, keys, lists,
                      matrix, sync,
                      summary, transfer, trash)
from .routers.items import records_router
from .seed import init_db

# docs / redoc / openapi 都关掉自带的，文件末尾自己注册 —— 自带的那几个没
# 法挂鉴权，而接口清单本身就是一张系统结构图，不该让局域网里谁都能翻。
app = FastAPI(title="采知道 服务端", docs_url=None, redoc_url=None,
              openapi_url=None)

# 不需要 allow_credentials：网页靠 Cookie 认证，而生产是同源（后端自己发前端），
# 开发走 vite 代理也是同源，CORS 根本不参与。keep 住不带 credentials 的
# 通配配置，跨站请求就带不上 Cookie。
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

init_db()


@app.get("/api/health", include_in_schema=False)
def health():
    """公开的存活探针，给容器健康检查和客户端的「测试连接」用。
    不能拿 /api/summary 当探针——它现在需要登录。"""
    return {"status": "ok"}


# 登录接口自身不能挂守卫，否则没法登录
app.include_router(auth.router)

# 其余业务接口一律需要登录
_guard = [Depends(require_user)]
app.include_router(items.router, dependencies=_guard)
app.include_router(records_router, dependencies=_guard)
app.include_router(base_data.router, dependencies=_guard)
app.include_router(matrix.router, dependencies=_guard)
app.include_router(summary.router, dependencies=_guard)
app.include_router(transfer.router, dependencies=_guard)
app.include_router(lists.router, dependencies=_guard)
app.include_router(expenses.router, dependencies=_guard)
app.include_router(trash.router, dependencies=_guard)
app.include_router(backup.router, dependencies=_guard)
app.include_router(sync.router, dependencies=_guard)
app.include_router(keys.router, dependencies=_guard)


# 前端构建产物目录可用环境变量覆盖（本地开发走 vite:5173，不经过这里）
DIST_DIR = os.environ.get(
    "RENOVATION_DIST",
    os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))), "frontend", "dist"))


# ---------------------------------------------------------------- 接口文档
#
# 自己注册而不是用 FastAPI 的 docs_url：那两个没法挂鉴权。登录态和 API
# 密钥都认 —— 用浏览器打开时本来就登录着（走 Cookie），外部程序带密钥也
# 能取 schema。

def _custom_openapi():
    """在 schema 里声明两种安全方案，文档页右上角的 Authorize 才知道往哪
    塞凭据；不声明的话，点「试调」发出去的请求是不带钥匙的。"""
    if app.openapi_schema:
        return app.openapi_schema
    schema = get_openapi(title=app.title, version=app.version, routes=app.routes)
    schema.setdefault("components", {})["securitySchemes"] = {
        "ApiKeyAuth": {"type": "apiKey", "in": "header", "name": "X-API-Key"},
        "BearerAuth": {"type": "http", "scheme": "bearer"},
    }
    # 两种任选其一：浏览器里登录着就用 Cookie，外部程序填密钥
    schema["security"] = [{"ApiKeyAuth": []}, {"BearerAuth": []}]
    app.openapi_schema = schema
    return schema


app.openapi = _custom_openapi


@app.get("/api/openapi.json", include_in_schema=False)
def openapi_schema(request: Request, db: Session = Depends(get_db)):
    require_user(request, db)
    return app.openapi()


# Swagger 的静态资源由前端构建产出：frontend/scripts/copy-swagger.mjs 从
# swagger-ui-dist 拷进 dist/swagger。既不走 CDN（实测拉一次要六秒，文档页
# 在浏览器里直接超时），也不把那份 1.4MB 的压缩 JS 提交进仓库 —— 代码扫描
# 会在里面报出一堆误报。
SWAGGER_DIR = os.path.join(DIST_DIR, "swagger")
if os.path.isdir(SWAGGER_DIR):
    app.mount("/api/docs-assets", StaticFiles(directory=SWAGGER_DIR),
              name="docs-assets")


@app.get("/api/docs", include_in_schema=False)
def api_docs(request: Request, db: Session = Depends(get_db)):
    require_user(request, db)
    if not os.path.isdir(SWAGGER_DIR):
        # 本地开发没构建前端时会走到这里，给一句能照做的提示
        return HTMLResponse(
            "<p>接口文档的静态资源还没生成，先跑一次前端构建：</p>"
            "<pre>cd frontend &amp;&amp; npm install &amp;&amp; npm run build</pre>"
            "<p>接口结构本身在 <a href=\"/api/openapi.json\">/api/openapi.json</a>。</p>",
            status_code=503)
    return get_swagger_ui_html(
        openapi_url="/api/openapi.json",
        title="采知道 接口文档",
        swagger_js_url="/api/docs-assets/swagger-ui-bundle.js",
        swagger_css_url="/api/docs-assets/swagger-ui.css",
        swagger_favicon_url="/icon.svg",
    )

if os.path.isdir(DIST_DIR):
    app.mount("/assets", StaticFiles(directory=os.path.join(DIST_DIR, "assets")),
              name="assets")

    @app.get("/{full_path:path}", include_in_schema=False)
    def spa(full_path: str):
        target = os.path.join(DIST_DIR, full_path)
        if full_path and os.path.isfile(target):
            return FileResponse(target)
        return FileResponse(os.path.join(DIST_DIR, "index.html"))
