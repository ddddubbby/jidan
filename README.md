# 收蛋 · 鸡蛋采购入库系统

磅秤员在安卓 APP 上连蓝牙秤逐箱称重、打箱贴、提交采购入库单；会计在 Web 管理端审核，审核后回传好业财/好生意（畅捷通开放平台）生成采购入库单。

本页介绍部署启动、日常维护和开发修改，每节标出《移交与适配指南》（`doc/开发规格/移交与适配指南.md`，下称“指南”）的对应章节。

> **接手联调的人先看指南第 1 章的交接清单（1.1）**：部署、开通好生意、打实机版 APP、联调秤和打印机、真手机实测、全链路验收，共七步，按顺序做。

**验证状态**：后端、Web、APP 的逻辑都在模拟环境里验证过（模拟秤、模拟打印机、模拟好生意）。真手机、真秤、真打印机和真实账套**未经实物验证**，按指南 1.1 的清单逐项做；各功能验证到哪一级，见指南第 4 章。

| 文档 | 用途 |
|---|---|
| `doc/产品需求/鸡蛋采购入库解决方案-需求规格说明书 v7.md` | 需求（以它为准） |
| `doc/开发规格/鸡蛋采购入库解决方案-开发规格说明书 v2.md` | 开发规格、任务看板（第 13 章）、偏离与变更记录（附录 E） |
| `doc/开发规格/移交与适配指南.md` | 部署、打包、联调、上线、维护的完整手册：交接清单（1.1）、验证状态表（第 4 章）、适配与排查（第 5 章） |
| `lab/app-sim.html`、`lab/web-sim.html` | 界面原型（浏览器直接打开） |

---

## 1 部署与启动

### 1.1 部署后端与 Web（指南 2.1）

准备一台已安装 Docker（含 `docker compose`）的机器。

```
cp deploy/.env.example deploy/.env
```

打开 `deploy/.env`，至少改 `EGG_DB_PASSWORD`、`EGG_DB_ROOT_PASSWORD`；端口默认 `EGG_WEB_PORT=80`。然后：

```
docker compose -f deploy/compose.yml up -d --build
docker compose -f deploy/compose.yml ps
curl http://localhost:<端口>/api/health
```

`/api/health` 返回 `{"status":"UP","db":"UP"}` 即成功。浏览器打开 `http://<服务器地址>:<端口>`。默认是演示模式（带演示数据，好生意用模拟实现），可用下面的演示账号登录：

| 角色 | 手机号 | 密码 | 用在 |
|---|---|---|---|
| 管理员 王会计 | 13900002222 | admin-demo-1234 | Web |
| 磅秤员 张师傅 | 13900001111 | scale-demo-1234 | APP |

机器要经代理上网时，构建写法见指南 2.1。

### 1.2 打 APP 并装到手机（指南 2.2）

准备 Android SDK：platform 35、build-tools 35.0.0；打实机版另需 NDK 27.0.12077973、CMake 3.22.1。

```
export ANDROID_HOME=<Android SDK 目录>
cd android
./gradlew :app:assembleSimDebug :app:assembleRealDebug
```

| 版本 | 文件 | 说明 |
|---|---|---|
| 模拟版 | `android/app/build/outputs/apk/sim/debug/app-sim-debug.apk` | 内置模拟秤、模拟打印机，没有设备也能走完全流程 |
| 实机版 | `android/app/build/outputs/apk/real/debug/app-real-debug.apk` | 连蓝石秤、佳博打印机；打包时要给蓝石 `companyKey`（指南 5.4） |

装到手机有两种办法：USB 用 `adb install -r <apk>`；或经站点发布，手机 APP 登录页会出现下载入口：

```
deploy/publish-apk.sh android/app/build/outputs/apk/sim/debug/app-sim-debug.apk
```

**演示版（给合作方看，不要服务器、不要登录）：**

```
cd android && ./gradlew :app:assembleSimDemo
```

产物 `android/app/build/outputs/apk/sim/demo/app-sim-demo.apk`，包名 `com.jidan.receiving.sim.demo`，桌面图标“收蛋（演示版）”，可与模拟版、实机版同装。装好直接打开，已是磅秤员“张师傅”登录，数据是 v7 演示数据（李四尾号 1234，10 箱走出 181 元/箱、1,810 元），秤和打印机用模拟台。接口由 APP 内的离线假后端回答，数据只在内存里，退出登录或杀掉进程后复位；没有更新入口，也连不上任何真实服务器。未经实物验证。详见指南 2.2。

APP 登录页的“服务器”填 `http://<服务器地址>:<端口>`，用磅秤员账号登录，然后按李四算例完成一次收货验证（指南 2.3）：10 箱、均重 46kg、181 元/箱、1810 元。

### 1.3 正式上线（指南 7.1、7.2）

`deploy/.env` 里：`EGG_SEED_DEMO=false`；填初始管理员 `EGG_ADMIN_PHONE`、`EGG_ADMIN_PASSWORD`、`EGG_ADMIN_NAME`；连真实好生意时 `CHANJET_MODE=real` 并填 appKey、appSecret、账套 ID、软证书（指南 5.3）。然后 `docker compose -f deploy/compose.yml up -d --build`。

---

## 2 日常维护

| 要做的事 | 命令 | 指南 |
|---|---|---|
| 看服务状态 | `docker compose -f deploy/compose.yml ps` | 5.7 |
| 看日志 | `docker compose -f deploy/compose.yml logs backend --tail 50`（站点换成 `web`） | 5.7 |
| 备份一次 | `deploy/backup.sh`（默认放 `deploy/backups/`，保留 14 天） | 7.3 |
| 每日自动备份 | `crontab -e` 加一行，写法见 `deploy/backup.cron.example`；备份只在本机，异机同步自己安排 | 7.3 |
| 从备份恢复 | 先停 `web backend`，再导入备份文件，再启动；三条命令见指南 | 7.3 |
| 升级后端与 Web | 先 `deploy/backup.sh`，拉新代码后 `docker compose -f deploy/compose.yml up -d --build`；数据在数据卷里，表结构由 Flyway 自动升级 | 7.4 |
| 发布新版 APP | 改 `android/app/build.gradle.kts` 的 `versionCode`、`versionName`，重新打包，`deploy/publish-apk.sh <apk>`；不用重启 | 7.4、2.2 |
| 忘了管理员密码 | 删库里的管理员账号，改 `.env` 的 `EGG_ADMIN_*` 后 `up -d` 重建 | 7.2 |
| 停服务 | `docker compose -f deploy/compose.yml down`（数据保留；加 `-v` 会删库，不可恢复） | 7.4 |

### 出了问题先看哪里（指南 5.7）

| 现象 | 先看 |
|---|---|
| Web 打不开 | `ps`；`logs web`、`logs backend` |
| 回传失败 | 单据详情里的失败原因；后端日志里的“好生意请求/响应” |
| 金额对不上 | 单据详情里的算式；待审核单按当前规则实时算，已审核的单冻结不变 |
| 手机连不上服务器 | 手机与服务器同一网络；防火墙放行站点端口；APP 服务器栏填对地址与端口 |

---

## 3 开发与修改

| 目录 | 内容 |
|---|---|
| `backend/` | Spring Boot 后端（计价只算在这里）、MyBatis-Plus、Flyway |
| `web/` | Web 管理端（Vue 3 + Vite） |
| `android/` | APP：`:core` 计价与称重逻辑、`:device` 秤与打印机、`:app` 界面；`sim`/`real` 两个版本 |
| `deploy/` | 部署、备份、APK 发布 |
| `fixtures/` | 计价、取整、箱数识别、备注的算例，后端与 APP 共用 |
| `api/openapi.json` | 接口快照 |
| `tools/ui-compare/` | APP 与原型的截图对照、孤字检查 |

本机开发（指南 7.5）：

```
docker compose -f deploy/compose.dev.yml up -d
cd backend && ./mvnw spring-boot:run
cd web && npm run dev
```

提交前跑全套验收（指南 7.6）：

```
make verify
```

改了 APP 界面，另跑截图对照（`tools/ui-compare/README.md`）：

```
cd android && ./gradlew --no-daemon :app:screens
```

秤、打印机、好生意的适配点各只在一个文件里，见指南 5.2 至 5.6；业务规则各只在一处，见指南 3.2。仓库约定（提交身份、隐私、任务流程）见 `CLAUDE.md`。
