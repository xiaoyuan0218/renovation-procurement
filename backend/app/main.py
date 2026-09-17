import os

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .auth import require_user
from .routers import (auth, base_data, backup, expenses, items, lists, matrix,
                      sync,
                      summary, transfer, trash)
from .routers.items import records_router
from .seed import init_db

app = FastAPI(title="采知道 服务端", docs_url=None, redoc_url=None)

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

# 前端构建产物目录可用环境变量覆盖（本地开发走 vite:5173，不经过这里）
DIST_DIR = os.environ.get(
    "RENOVATION_DIST",
    os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))), "frontend", "dist"))

if os.path.isdir(DIST_DIR):
    app.mount("/assets", StaticFiles(directory=os.path.join(DIST_DIR, "assets")),
              name="assets")

    @app.get("/{full_path:path}", include_in_schema=False)
    def spa(full_path: str):
        target = os.path.join(DIST_DIR, full_path)
        if full_path and os.path.isfile(target):
            return FileResponse(target)
        return FileResponse(os.path.join(DIST_DIR, "index.html"))
