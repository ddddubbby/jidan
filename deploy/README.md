# deploy/

| 文件 | 作用 |
|---|---|
| `compose.yml` | 部署：MySQL + 后端 + 站点（Web 管理端、`/api` 转发、APK 下载）。只有站点对外开放端口 |
| `.env.example` | 全部配置项与说明；复制为 `deploy/.env`（不入库）后使用 |
| `nginx.conf`、`backend.Dockerfile`、`web.Dockerfile` | 站点配置与两个镜像的构建，部署机器上只需要 Docker |
| `publish-apk.sh` | 发布最新 APK：拷到 `apk/`、写 `version.json`，不用重启 |
| `backup.sh`、`backup.cron.example` | 导出一份数据库备份并清理过期备份；每日定时的写法 |
| `compose.dev.yml` | 开发用：只起一个 MySQL，后端与 Web 在本机跑 |

## 起全套

```
cp deploy/.env.example deploy/.env      # 第一次；至少把两个数据库密码换成自己的
docker compose -f deploy/compose.yml up -d --build
```

第一次要下载镜像并构建后端与 Web，需要几分钟；之后重启很快。浏览器打开 `http://<服务器地址>:<EGG_WEB_PORT>`，
默认模拟模式带演示数据，演示账号见 `.env.example`。停掉：`docker compose -f deploy/compose.yml down`（数据在
数据卷里，不会丢；加 `-v` 才会删库）。

## 发布 APP 新版本

```
deploy/publish-apk.sh 路径/app-sim-release.apk
```

手机上的 APP 在登录页与建单页查到比自己新的版本，就出现下载入口。没有 `aapt2`（Android SDK 的 build-tools）的机器上，
把版本号手填在后面：`deploy/publish-apk.sh xxx.apk <versionCode> <versionName>`。站点只有一个更新入口，
指向最近一次发布的 APK；模拟版与实机版包名不同、互不升级，一个站点上通常只发其中一个。

## 备份

```
deploy/backup.sh
```

默认放在 `deploy/backups/`，保留 14 天。每日定时见 `backup.cron.example`，恢复的命令写在 `backup.sh` 开头。
备份只在本机，要防整机损坏请自己把备份目录同步出去。

验证级别：以上均只在模拟环境（模拟好生意、演示数据）里走过，未经实物验证。
