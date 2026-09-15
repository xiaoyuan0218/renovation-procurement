import os
import tempfile

# 必须在导入 app 模块之前设置环境，db.py 在 import 时读取
_TMP = tempfile.mkdtemp(prefix="renovation_test_")
os.environ["RENOVATION_DATA_DIR"] = _TMP
os.environ["RENOVATION_DB"] = os.path.join(_TMP, "test.db")
