# 装修采购清单

一个自托管的家装采购管理工具：把装修采购清单从表格搬进网页 —— 随手改数量、记付款、看进度，局域网内任意设备打开即用，数据存在自己机器的 SQLite 文件里。

**它解决什么**：装修要买几十上百种材料、分布在十几个房间，还得分次付款、随时看"总共多少 / 花了多少 / 还差多少"。用表格维护容易算错、改乱、看不出进度。这里把这些做成结构化数据：

- **物料 × 房间的布点矩阵**：每样东西装在哪些房间、各几个，一眼看全，某房间还能单独定价
- **多笔采购记录**：一笔付款可能覆盖多件物料，记录后自动算已付、未付与实付单价，状态自动变成"部分已买 / 已买完"
- **自动汇总看板**：总价、日常价、已付、未付、各类目与各房间的分布、未采购清单

界面为 iOS 毛玻璃风格，无需登录，SQLite 单文件存储，备份就是拷一个文件。

## 功能

- **总览看板**：原价合计 / 日常价合计 / 已付 / 未付 四张统计卡；实付构成、分类占比、采购进度三个环形图；类目对比、房间金额分布柱状图；未采购金额 Top 6 与未采购清单（可一键跳到清单页）。整屏自适应，无页面滚动
- **物料清单**：名称 / 品牌 / 型号 / 类目 / 数量 / 单价 / 日常价 / 已付·未付 / 采购状态 / 备注；支持关键词、类目、采购状态（全部 / 未买 / 部分已买 / 已买完）筛选；勾选后可批量删除
- **多笔采购记录**：点采购状态标签打开「记一笔采购」，可逐笔录入实付数量、实付金额与付款日期，自动算实付单价；也支持「按日常价付清」与「清零」（均有二次确认）。已付 = 各笔金额之和，未付 = 原价 − 已付
- **布点矩阵**：物料 × 房间矩阵，点单元格填数量 / 覆盖单价 / 备注；按采购记录把已买齐的房间标绿；底部固定合计行（各房间数量 + 总量与金额）
- **数据管理**：导出 xlsx；按模板导入（覆盖或按名称合并）；房间与类目可增改

## 快速开始（本地运行）

依赖：Python 3.11+，Node 18+

```bash
python -m venv .venv
.venv\Scripts\pip install -r backend\requirements.txt

# 前端（首次运行或改了前端代码后）
cd frontend && npm install && npm run build && cd ..

# 启动
cd backend && ..\.venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

打开 `http://127.0.0.1:8000`，首次启动会自动建库并写入默认房间与类目。

Windows 下也可直接双击根目录的 `启动服务.bat`（前台）或 `启动服务-后台运行.bat`。
开发模式（前端改动实时生效）：`cd frontend && npm run dev`（5173 端口，自动代理 `/api` 到 8000）。

> 注意：改**后端**代码需要重启服务才会生效（uvicorn 未开 `--reload`）。

## 安卓 App

`android/` 下是同一套数据的原生安卓客户端（Kotlin + Jetpack Compose），功能对齐网页版：总览看板、物料清单（增删改 + 记一笔采购）、布点矩阵（列表 / 矩阵双模式）、房间与类目管理、Excel 导入导出。**后端不需要任何改动**。

**服务器地址可自定义**：首次启动填写后端地址，之后可在「设置」里随时修改，改完立即生效、不用重启。地址规则很宽松 —— 省略 `http://` 和端口都行，默认按 `8000` 处理，输入时界面会实时回显最终连接的地址（如「将连接到 `http://192.168.1.9:8000`」）。连接失败时会明确显示连的是哪个地址，并提供「重试」与「修改地址」。

### 构建

```bash
cd android
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 经 R8 压缩，约 1.9MB
```

需要 JDK 17 与 Android SDK（platform 35 / build-tools 35）。SDK 路径写在 `android/local.properties`（该文件不入库）；依赖仓库配置了阿里云镜像，国内网络可直接构建。

### 安装与使用

把 APK 传到手机点击安装即可（debug 与 release 都使用 debug 签名，个人自用足够）。手机需与后端在同一个局域网。模拟器里调试宿主机上的后端，地址填 `10.0.2.2:8000`。

界面是深色玻璃拟态 + 霓虹蓝配色，视觉规范见 [`android/DESIGN.md`](android/DESIGN.md)。

也可以由 GitHub Actions 自动构建：`.github/workflows/android.yml` 在 `android/` 有改动时产出 APK artifact。

## Docker 部署

镜像由 GitHub Actions 构建并推送到 GitHub Container Registry，`main` 分支每次推送自动更新：

```
ghcr.io/xiaoyuan0218/renovation-procurement:latest     # 最新
ghcr.io/xiaoyuan0218/renovation-procurement:sha-xxxxxx # 按提交回滚
```

服务器上只需要一个 compose 文件（仓库根目录的 `docker-compose.yml`，镜像地址已填好）：

```yaml
services:
  app:
    image: ghcr.io/xiaoyuan0218/renovation-procurement:latest
    container_name: renovation-procurement
    restart: unless-stopped
    ports: ["8000:8000"]
    volumes: ["./data:/data"]        # 数据库落在宿主机，备份拷这个目录
    environment:
      TZ: Asia/Shanghai
      RENOVATION_DATA_DIR: /data
```

```bash
mkdir -p /opt/renovation-procurement && cd /opt/renovation-procurement
# 保存上面的 docker-compose.yml

# 镜像包若为私有：先登录一次（PAT 勾选 read:packages）；设为 Public 则免登录
echo "<你的PAT>" | docker login ghcr.io -u xiaoyuan0218 --password-stdin

docker compose up -d                          # 启动并拉取镜像
docker compose logs -f app                    # 查看日志
docker compose pull && docker compose up -d   # 更新到最新构建
```

打开 `http://<服务器IP>:8000`。镜像内已含构建好的前端与后端，服务器无需安装 Node/Python。

**不用 CI、本机构建**：`docker build -t renovation-procurement .`，把 compose 的 `image:` 换成 `renovation-procurement:latest` 即可。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `RENOVATION_DB` | `<项目>/data/renovation.db` | SQLite 文件完整路径 |
| `RENOVATION_DATA_DIR` | `<项目>/data` | 数据目录 |
| `RENOVATION_DIST` | `<项目>/frontend/dist` | 前端静态文件目录 |

端口由启动命令的 `--port` 决定（compose 里映射 8000）。容器内数据目录固定挂 `/data`。

## Excel 导入 / 导出

- **导出**：一键导出 xlsx，含「物料汇总 / 布点明细 / 采购记录」三个 sheet，可再次导入
- **按模板导入**：下载模板填写后导入，支持「覆盖」与「按名称合并」两种模式
- **从历史表格预置**：如果已有一份"产品×房间布点 + 物料汇总 + 类目汇总"分区的表格，可用
  `backend/scripts/seed_from_excel.py` 一次性导入（解析房间布点与房间单独价、把"实付"折算成采购记录，并以表内合计行校验金额口径）：

  ```bash
  cd backend && ..\.venv\Scripts\python scripts/seed_from_excel.py <你的表格.xlsx>
  ```

- **差异核对**：`backend/scripts/compare_with_excel.py` 会把 Excel 与当前数据库逐项配对，
  输出数量 / 单价 / 已付 / 布点的差异清单与汇总差额，方便确认手工录入是否与表格一致

## 测试与回归脚本

```bash
cd backend && ..\.venv\Scripts\python -m pytest tests -q      # 单测：金额口径 / 删除级联 / 模板导入导出

# 以下需要先启动服务（默认 127.0.0.1:8000，可用 WALKTHROUGH_BASE 指向其他实例）
.venv\Scripts\python backend\scripts\walkthrough.py           # 功能走查：看板数字、矩阵编辑、导入导出
.venv\Scripts\python backend\scripts\measure_layout.py        # 布局回归：无整页滚动、表格撑满、图表填充
```

## 目录结构

```
backend/    FastAPI + SQLAlchemy + SQLite
  app/        模型、路由(items/base_data/matrix/summary/transfer)、金额计算、excel 导入导出
  scripts/    seed_from_excel.py 历史表格导入、compare_with_excel.py 差异核对、
              walkthrough.py 功能走查、measure_layout.py 布局回归
  tests/      pytest
frontend/   Vue3 + Vite + Element Plus + ECharts（总览 / 物料清单 / 布点矩阵）
android/    Kotlin + Jetpack Compose 原生客户端（服务器地址可自定义）
data/       renovation.db（运行时生成，备份拷这个文件即可）
```

## 技术要点

- **金额口径**：原价 = Σ数量×单价（含房间覆盖价）；日常价 = Σ数量×日常单价；已付 = Σ采购记录金额；未付 = 原价 − 已付
- **采购状态**由采购记录推导：未买 / 部分已买 / 已买完；无数量需求时显示"无需采购"
- **表格撑满容器**：面板 flex 布局 + 独立滚动视口 + `ResizeObserver` 同步 `<el-table :height>`，表头固定、只有内容区滚动
- **玻璃卡片间隙**：阴影用负 spread 只向下投射，避免相邻卡片阴影在窄缝里叠加成灰条
- **安卓端口径一致**：客户端把 `compute.py` 的公式复刻了一份用于表单实时预览，但保存后的权威结果始终以后端返回为准；任何写操作成功后只发一个版本号，各页面据此重新拉数据
