import os
import tempfile

# 必须在导入 app 模块之前设置环境，db.py 在 import 时读取
_TMP = tempfile.mkdtemp(prefix="renovation_test_")
os.environ["RENOVATION_DATA_DIR"] = _TMP
os.environ["RENOVATION_DB"] = os.path.join(_TMP, "test.db")
# 固定签名密钥，否则 auth.py 会在临时目录里生成一份、测试之间还共享
os.environ["RENOVATION_SECRET"] = "test-secret-not-for-production"

# 测试账号：只建在临时测试库里，不是任何真实环境的凭据。集中在这里定义，
# 各测试文件从 conftest 导入，避免同一对假值散落在十几个文件里（也免得
# 被静态扫描当成泄露的真实口令）。可用环境变量覆盖。
TEST_USER = os.environ.get("TEST_AUTH_USER", "admin")
TEST_PASSWORD = os.environ.get("TEST_AUTH_PASSWORD", "test-only-pass")

