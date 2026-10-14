"""版本接口与部署标识：网页上的打赏开关靠它判断"是不是更新过了"。

deploy_id = 版本号 + 构建时间 —— 发布新版本、或者只是重新构建部署（版本号
没变），它都会变；同一个镜像重启容器则保持不变（重启不是新部署，不该让所有人
的打赏开关无缘无故重开）。
"""

from fastapi.testclient import TestClient

from app.main import _deploy_id, app


def test_deploy_id_combines_version_and_built_at():
    assert _deploy_id("1.2.5", "") == "1.2.5"
    assert _deploy_id("1.2.5", "2026-10-14T15:00:00Z") == "1.2.5+2026-10-14T15:00:00Z"


def test_same_version_rebuilt_also_counts_as_new_deploy():
    """同版本号重新构建（构建时间变了）也要算一次新部署 —— 否则"重新部署后
    打赏开关重开"这条就落不了地。"""
    assert _deploy_id("1.2.5", "2026-10-14T15:00:00Z") != \
        _deploy_id("1.2.5", "2026-10-15T01:00:00Z")


def test_version_endpoint_reports_deploy_id():
    with TestClient(app) as client:
        body = client.get("/api/version").json()

    assert body["deploy_id"], "接口要带上部署标识"
    expected = f"{body['version']}+{body['built_at']}" if body["built_at"] else body["version"]
    assert body["deploy_id"] == expected
