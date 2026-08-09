# 部署到 82.157.205.6

该方案与 `S:\videonest` 一致：在本机编译 Jar 和前端 `dist`，服务器只用 Docker Compose 运行产物，不在服务器下载 Maven/npm 依赖。

## 首次准备

服务器需安装 Docker Engine、Docker Compose v2，并在腾讯云安全组放行 TCP `8081`。数据库、Redis、MinIO API 和 MinIO 控制台都只在 Docker 内部使用，不占用宿主机端口。

本机复制并填写生产配置：

```powershell
Copy-Item .env.example .env
```

至少修改 `JWT_SECRET`、`DATABASE_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`APP_ADMIN_PASSWORD`，并填写实际使用的 API Key。公网地址变化时同步修改 `CORS_ALLOWED_ORIGINS`。不要提交 `.env`。

确认可以登录服务器：

```powershell
ssh ubuntu@82.157.205.6
```

默认使用 `ubuntu` 账号。如果服务器使用其他账号，部署时通过 `-RemoteUser` 指定；如果使用单独的私钥，传入 `-IdentityFile C:\path\server.pem`。

## 一键部署或更新

在项目根目录运行：

```powershell
.\deploy.cmd
```

完成后访问 `http://82.157.205.6:8081`。以后代码更新后仍执行同一条命令；PostgreSQL、Redis 和 MinIO 使用命名卷，更新容器不会清空数据。

常用命令：

```powershell
# 指定 SSH 用户和私钥
.\deploy.cmd -RemoteUser ubuntu -IdentityFile C:\keys\server.pem

# 已有本地部署包时跳过 Maven/npm 构建
.\deploy.cmd -SkipBuild
```

服务器排查命令：

```bash
cd /opt/heart-pilot
docker compose -f docker-compose.yml -f docker-compose.deploy.yml ps
docker compose -f docker-compose.yml -f docker-compose.deploy.yml logs -f --tail=200
```

停止服务（保留数据）使用 `docker compose -f docker-compose.yml -f docker-compose.deploy.yml down`。不要附加 `-v`，否则会删除数据卷。
