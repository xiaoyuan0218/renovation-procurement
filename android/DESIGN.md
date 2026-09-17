# 安卓端设计规范

这份文档是安卓客户端视觉与交互的**事实标准**。改样式前先看这里，改完样式同步更新这里。

改配色只需要动 `ui/theme/Color.kt` 一个文件 —— 背景光斑、玻璃卡、按钮渐变、状态胶囊、图表色板全都从它取值。

## 1. 风格定位

**深色玻璃拟态 + 霓虹强调色**。结构语言来自一张霓虹落地页参考图（深色底、玻璃层、发光边框、超大字号层级、胶囊标签），色相用自己的蓝色调。

结构语言照搬，色相不照搬 —— 这是这套风格的第一个原则。参考图给的是"怎么做层次"，不是"用什么颜色"。

## 2. 调色板

### 背景（三层渐变）
| 值 | 用途 |
|---|---|
| `#050A18` BgTop | 屏幕顶部 |
| `#081428` BgMid | 中段 |
| `#0A1B36` BgBottom | 底部 |

### 强调色
| 值 | 用途 |
|---|---|
| `#3D9BFF` Blue | **主强调**。未买状态、主按钮、选中态、焦点边框、链接 |
| `#7FC0FF` BlueSoft | 品牌副标题等次级强调 |
| `#6366F1` Indigo | 次要强调。类目添加、矩阵模式切换、实付均价 |
| `#22D3EE` Cyan | 第三强调。日常价、导入导出、日常价未付（日常价口径的未付） |
| `#38BDF8` Sky | 仅用于背景光斑 |
| `#FBBF24` Amber | 部分已买、未付金额、"有备注/单独价"标记；也为负的优惠（实付价高于原价/日常价，比价签还贵） |
| `#34D399` Mint | 已买完、已付金额、成功提示；也为非负的优惠（实际优惠、日常价优惠） |

### 危险色（必须独立于主色）
| 值 | 用途 |
|---|---|
| `#F87171` Danger | 错误边框、错误文本、删除确认按钮 |
| `#FB7185` DangerSoft | 删除/清零/移除的可点击文字 |
| `#E11D48 → #FB7185` DangerGradient | 批量删除按钮 |

> 蓝色主题下红色不能跟着主色走，否则"删除"会失去警示性。

### 文字
| 值 | 用途 |
|---|---|
| `#E8EEF9` TextPrimary | 标题、金额、主要信息 |
| `#9BB0CE` TextSecondary | 说明文字、标签名、图例 |
| `#5C7096` TextMuted | 占位符、禁用态、次要脚注 |

### 玻璃层
| 值 | 用途 |
|---|---|
| `#12FFFFFF` GlassFill | 玻璃卡填充、输入框底 |
| `#1FFFFFFF` GlassFillStrong | 卡片填充顶部（渐变起点） |
| `#26FFFFFF` GlassBorder | 卡片亮边 |
| `#14FFFFFF` GlassBorderSoft | 空单元格、次级容器边框 |
| `#1AFFFFFF` Divider | 卡片内分割线 |

### 渐变
| 名称 | 值 | 用途 |
|---|---|---|
| PrimaryGradient | `#2E7BFF → #22D3EE` | 主按钮、选中态胶囊、标题高亮、底部导航选中项 |
| IndigoGradient | `#6366F1 → #3D9BFF` | 次级按钮（添加类目、导入） |
| CyanGradient | `#22D3EE → #3D9BFF` | 备用 |
| DangerGradient | `#E11D48 → #FB7185` | 批量删除 |

## 3. 材质：三层结构

**第一层 · 背景**（`GlowBackground`）
竖向渐变打底，叠 4 处径向光斑。光斑参数（相对屏幕宽高的位置 + 半径比例 + 透明度）：

| 颜色 | alpha | 中心 x | 中心 y | 半径 |
|---|---|---|---|---|
| Blue | 0.34 | 0.10 | 0.02 | 0.90 |
| IndigoDeep | 0.32 | 1.00 | 0.16 | 0.80 |
| Cyan | 0.16 | 0.02 | 0.94 | 0.72 |
| Sky | 0.14 | 0.95 | 0.88 | 0.62 |

光斑的 Brush 用 `drawWithCache` 只建一次 —— 1440p 屏上每帧重建 4 个径向渐变 shader 是纯浪费。

**第二层 · 玻璃卡**（`GlassCard` / `GlassPanel`）
半透明填充（顶部亮、底部暗的竖向渐变）+ 1dp 亮边 + 圆角。不依赖真实模糊 —— `Modifier.blur` 在 minSdk 26 上效果不一致，半透明 + 亮边在深色底上已经足够像玻璃。

**第三层 · 强调元素**（`NeonButton` 等）
渐变填充 + 外发光阴影（`shadow(spotColor = 渐变首色)`）。整屏最多 1~2 个发光元素，多了就成霓虹灯箱。

## 4. 组件清单

### `ui/design/Glass.kt`
| 组件 | 用途 | 关键约定 |
|---|---|---|
| `GlowBackground` | 全屏背景 | 只在 AppRoot 用一次，不要嵌套 |
| `GlassCard` | 主容器 | 圆角 22dp，内边距 16dp；带 `accent` 时按状态色染色 |
| `GlassPanel` | 次级容器 | 圆角 16dp，内边距 12dp |
| `NeonButton` | 主操作 | 胶囊形，PrimaryGradient，带发光 |
| `GhostButton` | 次操作 | 玻璃底 + 描边，无发光 |
| `GlassIconButton` | 顶栏图标 | 40dp 圆形玻璃底 |
| `TagPill` | 状态/标签 | 胶囊，底色为强调色 16% alpha + 35% 描边；`filled=true` 用于"去处理"这类行动标签 |
| `SectionTitle` | 区块标题 | 左侧 3dp 渐变竖条 + 标题 + 说明 |
| `StatTile` | 指标卡 | 大号数字，位数 ≥9 降一号、≥12 再降一号 |
| `KeyValueRow` | 键值行 | 汇总卡里成对出现 |
| `HintText` / `LegendDot` | 说明/图例 | — |

### `ui/design/Inputs.kt`
| 组件 | 用途 | 关键约定 |
|---|---|---|
| `AppTextField` | 单行/多行文本 | 玻璃底 + 圆角 14dp，聚焦时描边变主色 |
| `AppPasswordField` | 密码 | 默认 `PasswordVisualTransformation` 遮蔽，右侧眼睛可临时显示；键盘类型 `Password` |
| `AppNumberField` | 数字输入 | 只允许数字和一个小数点，最多 12 字符 |
| `AppSelect` | 下拉选择 | 只读 `OutlinedTextField` 当外框（标签浮动在边框内，和 `AppTextField` 并排时框高与标签完全对齐）+ 透明点击层 + `DropdownMenu`；支持禁用选项（如已占用的房间） |
| `ChoiceChips` | 横向单选 | 选中态实心主色，未选中玻璃底 |
| `AppDateField` | 日期 | `yyyy-MM-dd` 字符串 + 日历弹窗 + 今天/昨天/前天快捷键；`showQuickChips=false` 用于列表内 |
| `ConfirmDialog` | 确认 | 危险操作用 `danger=true`（红色确认按钮） |
| `TextPromptDialog` | 单行输入弹窗 | 房间/类目改名 |
| `InfoDialog` | 只读信息 | 导入报告 |

### `ui/design/States.kt`
`LoadingState`（居中转圈）、`EmptyState`（圆形图标底 + 标题 + 提示）、`ErrorState`（**必须显示当前连接的地址** + 重试 + 修改地址）、`InlineBanner`（顶部细条提示，成功绿/失败红）、`BrandHeader`（页面标题 + 副标题 + 右侧动作）、`GlassDivider`。

### `ui/design/Sheet.kt`
`GlassBottomSheet`：圆角 26dp（仅顶部）、渐变拖拽条、内容自带 `imePadding`。记一笔、改布点、选房间都用它。

### `ui/charts/Charts.kt`
不引第三方图表库，全部手绘：
- `DonutChart` / `DonutWithLegend` —— 环形图，带中心文字和入场动画
- `GroupedBarChart` —— 分组柱状（类目对比：原价/日常价/实付）
- `HorizontalBarChart` —— 横向条形（房间分布、未采购 Top）
- `MiniBars` —— 统计卡里的迷你柱
- `ThinProgressBar` —— 细进度条

## 5. 状态语义色

| 状态 | 颜色 | 说明 |
|---|---|---|
| 未买 `unbought` | Blue | 待办即焦点，用主色 |
| 部分已买 `partial` | Amber | 标签显示"部分 x/y" |
| 已买完 `done` | Mint | 名称加删除线 |
| 无需采购 `none` | TextMuted | — |

## 6. 类目色

按名称关键词匹配，**不用固定名字表**（真实类目叫"灯具照明"而不是"照明"，固定表会全部漏掉）：

灯/照明 → `#60A5FA`；开关/插座/面板 → `#34D399`；网络/弱电/智能 → `#FBBF24`；
家装/家具/软装 → `#F472B6`；卫浴/水/厨 → `#22D3EE`；五金/工具 → `#A78BFA`；
地/砖/石 → `#FB923C`；漆/墙/涂料 → `#818CF8`；其余按名称散列到备用色板（保证新增类目也有稳定颜色）。

## 7. 负向约束

- 不要用纯黑 `#000000` 做背景 —— 深海军蓝才有"夜里的蓝"而不是"熄屏"
- 不要给玻璃卡加大面积阴影 —— 深色底上阴影会糊成一团，用亮边和填充区分层次
- 不要让红色跟着主色走 —— 删除/清零/断线必须独立用 Danger
- 不要满屏发光按钮 —— 一屏 1~2 个
- 不要引第三方图表库 —— 手绘能完全贴这套配色的圆角与渐变
- 不要在列表页用固定高度 —— 系统字体放大后必然截断，见第 8 节

## 8. 真机适配清单（每次改 UI 都要过一遍）

模拟器和真机差异最大的三处，都是**在模拟器上看不出来**的：

### 8.1 软键盘遮挡输入框
模拟器默认带硬件键盘，系统不弹软键盘，所以这条极容易漏。
**凡是有输入框的页面/弹层都必须加 `Modifier.imePadding()`**，位置在滚动容器**之前**：
- `ui/nav/AppShell.kt` 的 Tab 内容 Box
- `ui/items/ItemEditScreen.kt` 的表单滚动列
- `ui/setup/ServerSetupScreen.kt`、`ui/settings/SettingsScreen.kt`
- `ui/design/Sheet.kt` 的弹层内容列

验证方法：把模拟器硬件键盘关掉（`config.ini` 里 `hw.keyboard=no`），
`adb shell dumpsys input_method | grep mInputShown` 应返回 `true`，截图确认字段完整在键盘上方。

### 8.2 系统字体放大
小米/华为用户常把字体调到 1.3 倍。所有并排的数字都要能容错：
- 清单卡片四栏指标：`LocalDensity.current.fontScale >= 1.15f` 时改成两行两列
- `StatTile` 大数字：按字符数降字号（≥9 → 19sp，≥12 → 17sp）
- 总览页统计卡两行：`Row(Modifier.height(IntrinsicSize.Max))` + 子项 `fillMaxHeight()` 保证等高
- 所有 `Text` 尽量带 `maxLines` + `overflow = Ellipsis`

### 8.3 高分辨率性能
1440×3200 的屏（小米 14 Pro）是 411×914dp —— 逻辑尺寸和 Pixel 7 一样，**版式不用改**，
但物理像素是 2.25 倍。任何每帧重建的昂贵对象都要缓存，背景光斑用 `drawWithCache`。

### 8.4 沉浸式系统栏
- 各页面内容顶部 `.statusBarsPadding()`
- 底部导航 `.navigationBarsPadding()`
- `MainActivity` 里 `enableEdgeToEdge()`
- Android 14 预测式返回：manifest 里 `android:enableOnBackInvokedCallback="true"`

## 9. 布局约定

- 屏幕左右边距 16dp，卡片间距 12dp，卡片内边距 14~16dp
- **并排的输入控件必须共用同一套外框**：`AppTextField` 与 `AppSelect` 底层都是 `OutlinedTextField`，这样框高、内边距、标签浮动高度由同一个组件决定。自绘外框（标签画在框外）和它们并排时必然错开一行 —— 布点行的「房间 + 数量」踩过这个坑
- 页面结构：`BrandHeader`（标题+副标题+刷新）→ 筛选区 → 内容 → 主操作按钮
- 顶部筛选区固定在滚动区之外（搜索框 + 类目 + 状态胶囊 + 合计条），列表只滚内容
- 手机上不做宽表：物料用卡片列表，矩阵用「列表态 / 矩阵态」双模式
- 弹层只用于短表单（记一笔、改布点），长表单（物料编辑）用全屏页
- **登录页**照引导页（`setup/ServerSetupScreen`）的版式：大标题两行（第二行走 PrimaryGradient）+ 说明文字 + 一张 `GlassCard` 装表单 + 主操作 `NeonButton(fillWidth=true)`，错误用 `InlineBanner(accent = Ink.Danger)`。它和引导页一样是整屏页面，**不套 AppShell**（没有底部导航）

## 10. 验证方式

改完视觉必须**截图验收**，不能只看代码：
1. `./gradlew installDebug` 装到模拟器
2. 逐屏截图（配置页 / 登录页 / 总览 / 清单 / 编辑 / 记一笔 / 矩阵两态 / 设置）
3. 至少跑一次 1440×3200@560 尺寸和一次 1.3 倍字体，确认不截断
4. 跨页数字要对得上（总览合计 = 清单筛选合计 = 后端 `/api/summary`）

截图存在根目录 `screenshots-android/`。
