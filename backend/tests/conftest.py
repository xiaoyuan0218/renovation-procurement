import os
import tempfile

# 必须在导入 app 模块之前设置环境，db.py 在 import 时读取
_TMP = tempfile.mkdtemp(prefix="renovation_test_")
os.environ["RENOVATION_DATA_DIR"] = _TMP
os.environ["RENOVATION_DB"] = os.path.join(_TMP, "test.db")
# 固定签名密钥，否则 auth.py 会在临时目录里生成一份、测试之间还共享
os.environ["RENOVATION_SECRET"] = "test-secret-not-for-production"
