# deploy/

本分支不用 Docker：MySQL 8.4、后端（JDK 17）、nginx 都直接装在本机运行。完整步骤见 `doc/本地启动指南-无Docker.md`。

| 文件 | 作用 |
|---|---|
| `.env.example` | 全部配置项与说明；复制为 `deploy/.env`（不入库）后使用 |
| `run-backend.sh` | 读 `.env`、连本机 MySQL，用 JDK 17 起后端（还没打包时自动打一次） |
| `egg-backend.service.example` | 可选：用 systemd 让后端开机自启 |
| `nginx.conf` | 站点：提供 `web/dist`、把 `/api` 转到 `127.0.0.1:8080`、托管 APK |
| `publish-apk.sh` | 发布最新 APK：拷到 `apk/`、写 `version.json`，不用重启 |
| `backup.sh`、`backup.cron.example` | 用本机 `mysqldump` 导出一份数据库备份并清理过期备份；每日定时的写法 |

## 起全套

```
cp deploy/.env.example deploy/.env      # 第一次；数据库密码与在 MySQL 里建的账号一致
deploy/run-backend.sh                   # 后端
cd web && npm ci && npm run build       # Web 打包到 web/dist
sudo cp deploy/nginx.conf /etc/nginx/conf.d/egg.conf   # 改好里面的 /opt/egg 目录后
sudo nginx -t && sudo systemctl reload nginx
```

浏览器打开 `http://<服务器地址>`，默认模拟模式带演示数据，演示账号见 `.env.example`。

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
