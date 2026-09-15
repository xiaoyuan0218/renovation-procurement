# 装修采购 · 安卓客户端

同一份数据的原生入口。后端一行不用改 —— 客户端直连 FastAPI，25 个接口全接。

## 构建

需要 JDK 17 与 Android SDK（platform 35 / build-tools 35）。

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 走 R8，约 1.9MB
./gradlew installDebug       # 装到已连接的设备/模拟器
```

SDK 路径放 `local.properties`（已在 .gitignore 里）：

```properties
sdk.dir=D\:\\android-dev\\android-sdk
```

依赖仓库在 `settings.gradle.kts` 里配了阿里云镜像优先、官方源兜底；国内网络可以直连构建。

## 服务器地址

后端地址是**运行时配置**，不写死在代码里：

- 首次启动进引导页，填地址 → 「测试连接」调 `GET /api/summary` 验证 → 保存
- 之后在「设置 → 服务器」里随时修改，改完立即生效（OkHttp 拦截器逐请求重写 host，不用重启 App）
- 地址规范化规则：省略 `http://` 补 http；只填主机没写端口补 `8000`；去掉尾部 `/`。
  输入时界面实时回显「将连接到 …」，规则对用户完全可见
- 明文 HTTP 通过 `usesCleartextTraffic` + `network_security_config` 放行（局域网自托管服务的默认形态）

## 结构

```
app/src/main/java/com/xiaoyuan/renovation/
├── MainActivity.kt / RenovationApp.kt / di/AppContainer.kt   单 Activity，手写依赖容器
├── data/
│   ├── model/Dtos.kt        与后端 schemas.py 一一对应的 DTO
│   ├── remote/              ApiService（25 个端点）+ 动态主机拦截器 + 客户端工厂
│   ├── prefs/SettingsStore  DataStore 存服务器地址
│   └── repo/                Repository（写操作成功后发出版本号）+ 统一错误映射
├── domain/
│   ├── Compute.kt           复刻后端 compute.py 的口径，仅用于表单实时预览
│   └── ServerAddress.kt     地址解析与规范化
└── ui/
    ├── theme/               蓝色调调色板（Color.kt 是唯一的颜色来源）
    ├── design/              玻璃卡、霓虹按钮、状态胶囊、表单控件、底部弹层、空/错/载状态
    ├── charts/              环形图 / 分组柱状 / 横向条形 / 迷你柱，全部 Canvas 手绘
    ├── nav/AppShell.kt      底部导航 + 三个全屏路由（主壳 / 服务器地址 / 物料编辑）
    ├── setup/               服务器配置引导页
    ├── dashboard/           总览：4 张统计卡 + 6 张图表 + 未采购清单
    ├── items/               清单：筛选/搜索/多选批删 + 全屏编辑页 + 记一笔弹层
    ├── matrix/              布点矩阵：列表态 / 矩阵态（冻结物料列 + 房间列横滑）
    └── settings/            服务器 · 房间 · 类目 · 数据备份
```

## 几个实现上的取舍

- **不引 Hilt**：一个 `AppContainer` 就够，省掉注解处理器的构建开销。
- **不引图表库**：环形图和条形图用 Compose 的 `Canvas`/`Box` 手绘，配色和圆角能完全贴设计。
- **数据刷新靠版本号**：`AppContainer.dataVersion` 在每次写操作成功后自增，各页面 `LaunchedEffect(dataVersion)` 重新拉数据。所以"在清单页记一笔，回到总览数字就变了"，页面之间不需要互相通知。
- **口径只信后端**：客户端算的金额只用于编辑表单的实时预览，保存后一律以后端返回值展示。
- **危险色独立**：蓝色是主色，删除/清零/断线用 `Ink.Danger`（红），避免和主色混淆。

## 设计规范

**视觉与交互的事实标准在 [DESIGN.md](DESIGN.md)** —— 调色板、材质三层结构、组件清单、负向约束、真机适配清单、布局约定都在那里。改样式前先看它，改完同步更新它。

速记：颜色全部集中在 `ui/theme/Color.kt`，改 `Ink` 里的值就会贯穿整个 App（背景光斑、玻璃卡、按钮渐变、状态胶囊、图表色板都从那里取）。应用图标是 `res/drawable/ic_launcher_foreground.xml` 与 `ic_launcher_background.xml` 两个矢量，渐变直接写在 XML 里。

## 功能对照

| 网页版 | 安卓端 |
|---|---|
| 顶部三 Tab 分段导航 | 底部导航 + 毛玻璃底栏 |
| 宽表格 | 卡片列表（手机上不可读的列合并进卡片信息行） |
| 物料编辑弹窗（四列） | 全屏页面（手机上弹窗放不下） |
| 记一笔 / 单元格编辑 | 底部弹层 |
| 布点矩阵宽表 | 列表态（按物料铺房间标签）+ 矩阵态（冻结首列横滑），可切换 |
| 导出 xlsx | 下载到私有目录 → 系统分享（可存文件/发微信/云盘） |
| 导入 xlsx | 系统文件选择器 → multipart 上传 → 展示导入报告（含 warnings） |
