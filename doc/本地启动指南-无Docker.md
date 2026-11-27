# 本地启动指南（无 Docker）

本分支（`feat-no-docker`）去掉了 Docker：MySQL、后端、Web 都直接装在本机（宿主机）上运行，原来指向 Docker 内部网络的地址全部改为本机地址。
**带 Docker 的版本原样保留在 `main` 分支**，要回去见第 9 节。

验证级别：本文命令在 Linux 沙箱里逐条跑过（JDK 17.0.21、MySQL 8.4、Node 22、nginx 1.24、模拟好生意、演示数据）：后端 195 个测试全过，后端、Web、nginx 转发、APK 版本接口、备份都能用。沙箱里没有现成的 MySQL 8.4 安装包，验证时的 MySQL 8.4 是用官方 `mysql:8.4` 镜像映射到本机 3306 代替的，后端、测试、备份脚本都只当它是“本机 3306 上的 MySQL”来连。Windows、macOS 上的安装步骤未在实机走过；真秤、真打印机、真实好生意账套未经实物验证。

---

## 1 需要装的东西

| 软件 | 版本 | 用途 |
|---|---|---|
| MySQL | **8.4**（LTS） | 数据库 |
| JDK | **17** | 编译与运行后端（`backend/pom.xml` 里 `java.version` 已改为 17） |
| Node.js | 22 | 构建 Web 管理端、开发时跑 Vite |
| nginx | 任意近期版本 | 只在“部署模式”需要：提供 Web 静态文件、把 `/api` 转给后端、托管 APK。开发模式不需要 |
| Android SDK | 见 `README.md` 1.2 | 只在打 APP 时需要，与 Docker 无关，不变 |

参考装法（按自己的系统选一种）：

- **Ubuntu / Debian**：MySQL 8.4 用 MySQL 官方 APT 源（发行版自带的 `mysql-server` 多半是 8.0，不要用），装时选 “mysql-8.4-lts”；`sudo apt install openjdk-17-jdk nginx`；Node 22 用 NodeSource 或 nvm。
- **macOS**：`brew install mysql@8.4 openjdk@17 node@22 nginx`，按 brew 的提示把 `openjdk@17`、`mysql@8.4` 加进 PATH；`brew services start mysql@8.4`。
- **Windows**：MySQL 官网的 8.4 MSI 安装包（装成服务、端口 3306）；JDK 17 用 Eclipse Temurin 17 安装包；Node 22 安装包。后端的安装、建库、启动步骤见 [`Windows启动后端.md`](Windows启动后端.md)（用 PowerShell 与 `deploy/run-backend.ps1`，不需要 Git Bash）；`deploy/backup.sh`、`deploy/publish-apk.sh` 仍是 bash 脚本，要在 Git Bash 里执行。部署模式下的 nginx 可用 Windows 版 nginx。

装好后确认：

```
java -version        # 要显示 17
mysql --version      # 要显示 8.4.x
node -v              # v22.x
```

本机同时装了多个 JDK 时，跑后端前先指定：`export JAVA_HOME=<JDK 17 目录>`，`export PATH=$JAVA_HOME/bin:$PATH`。

---

## 2 建库与账号（只做一次）

建议在 MySQL 配置文件（Linux 一般是 `/etc/mysql/mysql.conf.d/mysqld.cnf` 或 `/etc/my.cnf`，Windows 是 `my.ini`）的 `[mysqld]` 段加上下面几行后重启 MySQL。与原来 Docker 里的 MySQL 启动参数一致：

```
[mysqld]
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
# 只给本机用，不对外开放 3306
bind-address=127.0.0.1
```

用 root 登录（`mysql -uroot -p`），执行：

```sql
-- 业务库与后端用的账号；密码与 deploy/.env 里的 EGG_DB_PASSWORD 一致
CREATE DATABASE IF NOT EXISTS egg CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'egg'@'localhost' IDENTIFIED BY 'egg';
CREATE USER IF NOT EXISTS 'egg'@'127.0.0.1' IDENTIFIED BY 'egg';
GRANT ALL ON egg.* TO 'egg'@'localhost';
GRANT ALL ON egg.* TO 'egg'@'127.0.0.1';

-- 只有跑后端自动化测试（make verify）才需要：测试会临时建 egg_test_xxx 库，测完删掉
CREATE USER IF NOT EXISTS 'egg_test'@'localhost' IDENTIFIED BY 'egg_test';
CREATE USER IF NOT EXISTS 'egg_test'@'127.0.0.1' IDENTIFIED BY 'egg_test';
GRANT ALL ON `egg\_test\_%`.* TO 'egg_test'@'localhost';
GRANT ALL ON `egg\_test\_%`.* TO 'egg_test'@'127.0.0.1';
```

上面的 `egg`/`egg` 是开发用的默认值（与 `backend/src/main/resources/application.yml` 的默认值一致）。正式部署把密码换掉，并同步写进 `deploy/.env`。
表不用手建：后端第一次启动时由 Flyway 自动建好 15 张表。

---

## 3 配置（`deploy/.env`）

```
cp deploy/.env.example deploy/.env
```

打开 `deploy/.env`，数据库相关几项要与第 2 节建的一致：

| 项 | 默认 | 说明 |
|---|---|---|
| `EGG_DB_HOST` | `127.0.0.1` | 本机 MySQL。原来是 Docker 内部的 `mysql` 主机名 |
| `EGG_DB_PORT` | `3306` | |
| `EGG_DB_NAME` | `egg` | |
| `EGG_DB_USER` / `EGG_DB_PASSWORD` | `egg` / `change-me-db` | 改成第 2 节建的账号密码 |
| `EGG_DB_ROOT_PASSWORD` | `change-me-root` | 只有 `deploy/backup.sh` 用 |
| `EGG_PORT` | `8080` | 后端端口 |

其余各项（演示数据、初始管理员、好生意）与原来相同，说明都写在 `.env.example` 里。
`deploy/.env` 不入库，里面的密码不要提交。

---

## 4 开发模式（改代码时用）

三个终端，分别：

```
# 1) 后端：连本机 3306 的 egg 库（application.yml 默认值，账号 egg/egg），监听 8080
cd backend && ./mvnw spring-boot:run

# 2) Web：Vite 开发服务器，/api 自动转到 http://localhost:8080
cd web && npm ci && npm run dev
```

浏览器打开 `http://localhost:5173`，用演示账号登录（管理员 王会计 `13900002222` / `admin-demo-1234`）。

- 开发模式不读 `deploy/.env`；要改库地址或账号，用环境变量覆盖，例如 `EGG_DB_URL='jdbc:mysql://127.0.0.1:3307/egg?characterEncoding=utf8&connectionTimeZone=Asia/Shanghai' EGG_DB_PASSWORD=xxx ./mvnw spring-boot:run`。
- 后端不在本机 8080 时，Web 用 `API_TARGET=http://<地址>:<端口> npm run dev`。
- 真手机连开发机：APP 登录页“服务器”填 `http://<开发机局域网 IP>:8080`（直连后端）；安卓模拟器填 `http://10.0.2.2:8080`。

### 跑自动化测试

```
make verify-backend        # 或 cd backend && ./mvnw verify
```

原来用 Testcontainers 在 Docker 里临时起 MySQL；现在改为连**本机** MySQL 8.4：每个测试上下文在本机建一个 `egg_test_xxxxxxxx` 空库，测完删掉，不碰 `egg` 库。
默认账号 `egg_test`/`egg_test`（第 2 节建的），可用环境变量改：`EGG_TEST_DB_HOST`、`EGG_TEST_DB_PORT`、`EGG_TEST_DB_USER`、`EGG_TEST_DB_PASSWORD`。
`make verify` 的 Web、APP 两步与 Docker 无关，不变。

---

## 5 部署模式（给手机和会计用）

```
# 1) 后端：读 deploy/.env，用 JDK 17 运行（还没打包时自动打一次），监听 127.0.0.1:8080
deploy/run-backend.sh

# 2) Web：打包成静态文件 web/dist
cd web && npm ci && npm run build

# 3) 站点：nginx 提供 web/dist，把 /api 转给 127.0.0.1:8080，托管 deploy/apk/
#    先把 deploy/nginx.conf 里的 /opt/egg 换成仓库在本机的实际目录，再放进 nginx 的配置目录
sudo cp deploy/nginx.conf /etc/nginx/conf.d/egg.conf
sudo nginx -t && sudo systemctl reload nginx

# 4) 检查
curl http://localhost/api/health        # {"status":"UP","db":"UP"}
```

浏览器打开 `http://<本机地址>`；手机 APP 登录页“服务器”填 `http://<本机局域网 IP>`（nginx 默认 80 端口，可省略）。
nginx 的运行账号要能读到 `web/dist` 与 `deploy/apk/`；仓库放在家目录下时，注意家目录的权限，或者把仓库放到 `/opt/egg`。
Ubuntu 自带的 `/etc/nginx/sites-enabled/default` 也监听 80，和本站冲突时把它删掉。

**后端常驻（可选）**：`deploy/run-backend.sh` 是前台运行，关掉终端就停了。要开机自启、挂了自动拉起，用 systemd：

```
sudo cp deploy/egg-backend.service.example /etc/systemd/system/egg-backend.service
# 改里面的 /opt/egg 与 User
sudo systemctl daemon-reload && sudo systemctl enable --now egg-backend
journalctl -u egg-backend -n 50 --no-pager     # 看日志
```

**发布 APP 新版本**、**备份**：与原来相同，`deploy/publish-apk.sh <apk>`、`deploy/backup.sh`。备份现在用本机的 `mysqldump`（装 MySQL 时带的客户端工具），读 `deploy/.env` 里的库名与 root 密码；每日定时见 `deploy/backup.cron.example`，恢复命令见 `deploy/backup.sh` 开头。

---

## 6 地址改动清单（Docker 内部地址 → 本机）

| 位置 | 原来（Docker） | 现在（本机） |
|---|---|---|
| 后端连数据库 `EGG_DB_URL` | `jdbc:mysql://mysql:3306/egg…`（compose 服务名 `mysql`） | `jdbc:mysql://127.0.0.1:3306/egg…`，由 `deploy/run-backend.sh` 按 `.env` 的 `EGG_DB_HOST/PORT/NAME` 拼出 |
| nginx 转发 `/api` | `proxy_pass http://backend:8080`（compose 服务名 `backend`） | `proxy_pass http://127.0.0.1:8080` |
| nginx 静态文件 | 容器内 `/usr/share/nginx/html` | 仓库的 `web/dist`（`deploy/nginx.conf` 里写 `/opt/egg/web/dist`，按实际目录改） |
| nginx APK 目录 | 容器内 `/usr/share/nginx/apk`（挂载 `deploy/apk`） | 仓库的 `deploy/apk/` |
| 备份 | `docker compose exec mysql mysqldump …` | 本机 `mysqldump -h127.0.0.1 -uroot …` |
| 后端集成测试 | Testcontainers 起 `mysql:8.4` 容器 | 本机 MySQL 上临时建 `egg_test_*` 库 |
| `application.yml`、`web/vite.config.ts`、APP | 本来就指向 `localhost` / 用户填写的地址 | 不变 |

删掉的文件：`deploy/compose.yml`、`deploy/compose.dev.yml`、`deploy/backend.Dockerfile`、`deploy/web.Dockerfile`；`backend/pom.xml` 去掉了三个 Testcontainers 依赖，`java.version` 由 21 改为 17。
新增的文件：`deploy/run-backend.sh`、`deploy/egg-backend.service.example`、本文。

---

## 7 原来的 Docker 命令怎么换

`README.md` 已按无 Docker 改写；《移交与适配指南》里仍是 Docker 的写法，照下表换：

| 原来 | 现在 |
|---|---|
| `docker compose -f deploy/compose.dev.yml up -d`（起开发库） | 本机 MySQL 8.4 服务启动着即可（`sudo systemctl start mysql` / `brew services start mysql@8.4` / Windows 服务） |
| `docker compose -f deploy/compose.yml up -d --build` | `deploy/run-backend.sh`（或 `systemctl restart egg-backend`）＋ `cd web && npm run build`，nginx 已配好时无需重启 |
| `docker compose -f deploy/compose.yml ps` | `curl http://localhost/api/health`；`systemctl status egg-backend mysql nginx` |
| `docker compose -f deploy/compose.yml logs backend` | 前台运行的终端；或 `journalctl -u egg-backend` |
| `docker compose -f deploy/compose.yml logs web` | `/var/log/nginx/access.log`、`error.log` |
| `docker compose … exec -T mysql sh -c 'MYSQL_PWD=… mysql -uroot … "$MYSQL_DATABASE" -e "…"'` | `mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 egg -e "…"` |
| `docker compose -f deploy/compose.yml stop web backend` / `start` | 停、启后端（Ctrl+C 后重跑 `deploy/run-backend.sh`，或 `systemctl stop/start egg-backend`） |
| `docker compose -f deploy/compose.yml down` | 停后端；数据一直在 MySQL 里 |
| `down -v`（删数据卷重建库） | `DROP DATABASE egg; CREATE DATABASE egg …;`（会丢数据，先备份） |
| 构建镜像时配代理 | 不再需要：Maven、npm 直接在本机跑，用本机的代理设置 |

---

## 8 常见问题

| 现象 | 先看 |
|---|---|
| 后端启动报 `Communications link failure` | MySQL 没启动，或 `.env` 里的主机、端口不对 |
| 报 `Access denied for user 'egg'` | `.env` 的 `EGG_DB_USER/PASSWORD` 与第 2 节建的不一致；或只建了 `'egg'@'localhost'`、没建 `'egg'@'127.0.0.1'` |
| 报 `Unsupported class file major version` 或 `release version 17 not supported` | 用的不是 JDK 17：`java -version`，按第 1 节设 `JAVA_HOME` |
| 测试全部报“连不上本机测试用 MySQL” | 没建 `egg_test` 账号（第 2 节），或用 `EGG_TEST_DB_*` 指到你的账号 |
| 浏览器打开是 nginx 欢迎页 | 默认站点没删：删 `/etc/nginx/sites-enabled/default` 后 reload |
| 页面打开是 403/404 | `deploy/nginx.conf` 里的目录没改成实际目录，或 nginx 读不到 `web/dist`（权限） |
| 页面能开、登录报网络错误 | 后端没起来：`curl http://127.0.0.1:8080/api/health` |
| 中文姓名乱码 | 运行后端的环境不是 UTF-8；`run-backend.sh` 已设 `LANG=C.UTF-8`，自己用 `java -jar` 起时也要设 |

---

## 9 回到 Docker 版

Docker 版没有删，就是 `main` 分支（本分支从它当时的状态拉出）：

```
git checkout main
docker compose -f deploy/compose.yml up -d --build
```

两边的数据不互通：Docker 版的数据在 Docker 数据卷里，本分支的数据在本机 MySQL 里。要把本机数据带回 Docker 版，先 `deploy/backup.sh` 导出，再按 `main` 上 `deploy/backup.sh` 开头写的恢复命令导入。
回 Docker 版前先停掉本机的后端和 nginx，或让它们换个端口，免得占着 80、8080（以及本机 MySQL 占着 3306 时，`compose.dev.yml` 的开发库要换 `EGG_DB_PORT`）。
