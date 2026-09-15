import os

from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker

# 数据库文件位置可用环境变量覆盖，便于后续 Docker 挂卷
DATA_DIR = os.environ.get("RENOVATION_DATA_DIR",
                          os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "data"))
os.makedirs(DATA_DIR, exist_ok=True)
DB_PATH = os.environ.get("RENOVATION_DB", os.path.join(DATA_DIR, "renovation.db"))

engine = create_engine(f"sqlite:///{DB_PATH}", connect_args={"check_same_thread": False})


@event.listens_for(engine, "connect")
def _sqlite_pragmas(dbapi_conn, _record):
    """SQLite 默认不校验外键。打开后 items 删除会自动级联清掉布点/采购记录，
    任何绕过 ORM 的批量删除也不会再留下悬空数据。"""
    cur = dbapi_conn.cursor()
    cur.execute("PRAGMA foreign_keys=ON")
    cur.close()


SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
