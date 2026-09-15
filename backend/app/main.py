import os

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .routers import base_data, items, matrix, summary, transfer
from .routers.items import records_router
from .seed import init_db

app = FastAPI(title="装修采购清单", docs_url=None, redoc_url=None)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

init_db()

app.include_router(items.router)
app.include_router(records_router)
app.include_router(base_data.router)
app.include_router(matrix.router)
app.include_router(summary.router)
app.include_router(transfer.router)

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
