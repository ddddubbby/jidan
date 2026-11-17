# 原型与 APP 渲染对照（F4-1）

把 `lab/app-sim.html`（原型）和 Android APP（模拟版）走同一批状态、各出一张图，再并排放在一起看。用来在验收 APP 界面时发现版式、间距、字号、折行上的差异。

## 怎么跑

三步，顺序无所谓，最后一步要前两步的图：

```bash
# 1. 渲染 APP（Robolectric，JVM 里跑，不要模拟器）。默认只出 393dp、字号 1.0 一套
cd android && ./gradlew --no-daemon :app:screens
#    六套尺寸（360 / 393 / 411dp × 字号 1.0 / 1.3）：加 -Pscreens.full=1
#    指定一套或几套：-Pscreens.matrix=393:1.3,360:1.0　　只出某几张：-Pscreens.only=11,16
#    网络受限时 Robolectric 的运行时 jar 可换镜像：-Probolectric.repo=<Maven 镜像地址>

# 2. 渲染原型（Playwright 用全局安装的：npm i -g playwright；不是仓库依赖）
node tools/ui-compare/proto-shots.mjs

# 3. 并排成图
python3 tools/ui-compare/compare.py                  # 默认配对 393-1.0
python3 tools/ui-compare/compare.py --config 393-1.3 # 配对别的一套
```

`:app:screens` 不在默认验收命令里（`:app:testSimDebugUnitTest` 不跑渲染测试），要下 Robolectric 运行时、跑得慢，机器内存吃紧时用 `--no-daemon`，不要同时开别的重任务。

## 输出

都在构建目录或 `tools/ui-compare/out/`，已加入 `.gitignore`，不入库。

| 路径 | 内容 |
|---|---|
| `android/app/build/screens/<宽度>-<字号>/<NN-name>.png` | APP 渲染，如 `393-1.0/11-ledger-sheet.png` |
| `android/app/build/screens/orphans.tsv` | 孤字行清单（各套合并）；每套自己的在各目录里 |
| `tools/ui-compare/out/proto/<NN-name>.png` | 原型渲染，名字与 APP 的一一对应 |
| `tools/ui-compare/out/compare/<NN-name>.png` | 左原型、右 APP |
| `tools/ui-compare/out/compare/contact-sheet.png` | 全部状态的总览 |

状态（共 18 个）：登录（空、填好）；建单（空、输入 1234、选了李四、四步齐了）；称重（选皮重、第 1 箱稳定中、第 1 箱已记录、10 箱）；流水弹层；单箱弹层与手工录入；设备弹层；放弃提示；汇总；定价规则弹层；完成页。

## 孤字行检查

渲染时遍历页面、弹层、对话框里所有可见的 TextView，多行文字的最后一行去掉空白后只剩一个字、或一个字加标点，就记一处。有未放行的，`:app:screens` 失败，清单在 `orphans.tsv`。

允许名单是 `android/app/screens-orphan-allow.tsv`，默认为空。每行四列（制表符分隔）：`config`、`screen`、`lastLine`、`reason`，前三列可写 `*`，`reason` 必填。

## 验证级别

L1：APP 在 Robolectric 原生图形下渲染，后端是 MockWebServer 回放，秤和打印机是 `FakeScale`、`FakePrinter`；原型在 Chromium 里渲染。不是真手机，字体、字重、行距可能与真机略有差别，折行位置尤其可能不同，所以孤字行是否真的出现在手机上，仍要真机确认。未经实物验证。

两边不完全等同：APP 的页面里带着模拟台（原型的模拟台在手机外面），APP 的称重页、建单页因此更长；原型图带手机外框。对照时看版式与文案，不比像素。
