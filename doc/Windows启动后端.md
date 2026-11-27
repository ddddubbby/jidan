# 在 Windows 上启动后端（无 Docker）

本文是《本地启动指南-无Docker》在 Windows 上的写法，只讲后端；Web、nginx 见原指南。
验证级别：`deploy/run-backend.ps1` 与本文命令**未在 Windows 实机走过**（开发方只有 Linux 环境），做法与 Linux 上验证过的 `deploy/run-backend.sh` 一一对应。真秤、真打印机、真实好生意账套未经实物验证。

下面的命令都在 **PowerShell** 里执行（开始菜单搜 PowerShell）。cmd 的写法单独标出。

---

## 1 装 JDK 17

1. 装 Eclipse Temurin 17（adoptium.net 选 17 - LTS、Windows x64、`.msi`）。安装时勾上 **Set JAVA_HOME** 和 **Add to PATH**。
2. 新开一个 PowerShell，确认：

   ```powershell
   java -version      # 要显示 17
   ```

本机还有别的 JDK 时，只在当前窗口指定 17：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot'   # 换成实际目录
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## 2 装 MySQL 8.4 并建库

1. dev.mysql.com 下载 **MySQL Community Server 8.4（LTS）** 的 Windows MSI 安装包（不是 8.0，也不是 9.x）。
2. 安装后会打开 MySQL Configurator：端口 **3306**，设好 root 密码，勾 “Configure MySQL Server as a Windows Service”（服务名默认 `MySQL84`）。
3. 编辑 `C:\ProgramData\MySQL\MySQL Server 8.4\my.ini`（`ProgramData` 是隐藏目录，地址栏直接输入），在 `[mysqld]` 段加：

   ```
   character-set-server=utf8mb4
   collation-server=utf8mb4_0900_ai_ci
   bind-address=127.0.0.1
   ```

   然后用**管理员** PowerShell 重启服务：`Restart-Service MySQL84`。
4. 把 MySQL 命令行加进当前窗口的 PATH，用 root 登录：

   ```powershell
   $env:Path = "C:\Program Files\MySQL\MySQL Server 8.4\bin;$env:Path"
   mysql -uroot -p
   ```

5. 执行建库 SQL。和原指南第 2 节完全相同，复制过来即可：

   ```sql
   CREATE DATABASE IF NOT EXISTS egg CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   CREATE USER IF NOT EXISTS 'egg'@'localhost' IDENTIFIED BY 'egg';
   CREATE USER IF NOT EXISTS 'egg'@'127.0.0.1' IDENTIFIED BY 'egg';
   GRANT ALL ON egg.* TO 'egg'@'localhost';
   GRANT ALL ON egg.* TO 'egg'@'127.0.0.1';
   ```

   `egg`/`egg` 是开发默认值；要换密码，同时写进第 3 步的 `deploy\.env`。要跑自动化测试的话，再执行原指南第 2 节里建 `egg_test` 账号的几行。
   表不用手建，后端第一次启动时 Flyway 自动建好。

## 3 配置 `deploy\.env`

```powershell
cd <仓库目录>
Copy-Item deploy\.env.example deploy\.env
notepad deploy\.env
```

数据库几项改成第 2 步建的账号密码，其余按 `.env.example` 里的说明改。
好生意的 appKey、appSecret、软证书等**只写在 `deploy\.env` 里**（它不入库），不要写进 `backend\src\main\resources\application.yml`，那个文件是入库的。
记事本另存时编码选 UTF-8（Windows 10 1903 以后的记事本默认就是）。

## 4 启动

二选一：

**A. 部署方式（读 `deploy\.env`）**

```powershell
powershell -ExecutionPolicy Bypass -File deploy\run-backend.ps1
```

或者在资源管理器里双击 `deploy\run-backend.cmd`。
脚本做的事和 `deploy/run-backend.sh` 一样：读 `.env`（代替 bash 的 `set -a; . .env`），没给 `EGG_DB_URL` 时用主机、端口、库名拼出来，还没打包时先跑一次 `mvnw.cmd -DskipTests package`，然后 `java -jar` 前台运行。关掉窗口就停了。

**B. 开发方式（改代码时用，不读 `.env`）**

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

用的是 `application.yml` 的默认值（本机 3306 的 `egg` 库，账号 `egg`/`egg`，模拟好生意，带演示数据）。要改哪项就在**同一个窗口**先设环境变量：

```powershell
# PowerShell
$env:EGG_DB_PASSWORD = '你的库密码'
$env:CHANJET_MODE = 'real'
.\mvnw.cmd spring-boot:run
```

```bat
:: cmd
set EGG_DB_PASSWORD=你的库密码
set CHANJET_MODE=real
mvnw.cmd spring-boot:run
```

这样设的变量只在当前窗口有效，关掉就没了，也不会落到任何文件里。可设的变量名见 `deploy\.env.example`。

第一次运行 `mvnw.cmd` 会下载 Maven 和依赖，要几分钟，需要能上网。

## 5 确认启动成功

1. 窗口里最后出现 `Tomcat started on port 8080` 和 `Started EggApplication`，且没有红色的 `ERROR`。
2. 另开一个 PowerShell：

   ```powershell
   curl.exe http://127.0.0.1:8080/api/health
   ```

   返回 `{"status":"UP","db":"UP"}` 就是后端和库都通了。（PowerShell 里要写 `curl.exe`，单写 `curl` 是另一个命令；也可以直接用浏览器打开这个地址。）
3. 要给手机 APP 连，APP 登录页“服务器”填 `http://<这台电脑的局域网 IP>:8080`，并在 Windows 防火墙弹窗里允许 Java 访问“专用网络”。

## 6 Windows 上常见问题

| 现象 | 先看 |
|---|---|
| `无法加载文件 ...run-backend.ps1，因为在此系统上禁止运行脚本` | 用上面带 `-ExecutionPolicy Bypass` 的写法，或双击 `run-backend.cmd` |
| `'java' 不是内部或外部命令` / `release version 17 not supported` | JDK 17 没进 PATH，按第 1 步设 `JAVA_HOME` 和 `Path` 后新开窗口 |
| `Communications link failure` | MySQL 服务没起：`Get-Service MySQL84`，没在运行就 `Start-Service MySQL84`（管理员窗口） |
| `Access denied for user 'egg'` | `.env` 的账号密码与第 2 步建的不一致，或缺 `'egg'@'127.0.0.1'` |
| `Port 8080 was already in use` | `netstat -ano \| findstr :8080` 找到占用的进程；或在 `.env` 里改 `EGG_PORT` |
| 管理员中文姓名乱码 | `.env` 不是 UTF-8 保存的，用记事本另存为 UTF-8 |
| `mvnw.cmd` 下载失败 | 网络或代理问题：在 `%USERPROFILE%\.m2\settings.xml` 配代理，或换能上网的网络 |

跑后端自动化测试：`cd backend; .\mvnw.cmd verify`（Windows 上没有 `make`，这条等同 `make verify-backend`，要先建 `egg_test` 账号）。
