# WTFiB 在 Zeabur 的三服务部署

最终拓扑只有三个 Zeabur 服务：

| 服务 | 创建方式 | 公网 | 挂盘 |
|---|---|---|---|
| PostgreSQL | Zeabur 模板 | 否 | 使用模板默认数据卷 |
| Redis | Zeabur 模板 | 否 | 使用模板默认 `/data` 数据卷 |
| WTFiB | GitHub 仓库 | 是 | 不挂盘 |

WTFiB 镜像内部包含前端/Nginx，以及 feed、sim、quant 三个 Java 进程。它们仍保持代码和进程隔离，但在 Zeabur 中只表现为一个服务。

## 1. 创建数据库服务

在同一个 Zeabur 项目中添加 PostgreSQL 和 Redis 模板。保留模板自带的环境变量与数据卷，不需要开放公网端口，也不要给它们绑定域名。

默认模板会向同项目服务暴露 `POSTGRES_*` 和 `REDIS_*`。WTFiB 启动器会自动把 PostgreSQL 变量转换成应用使用的 `PG_*`，不需要手工初始化数据库。

## 2. 创建 WTFiB 服务

选择 GitHub 仓库 `Youzini-afk/wtfibought`，部署当前功能分支。Root Directory 留空，让 Zeabur 使用仓库根目录的 `Dockerfile`。

建议配置：

- 内存：至少 16 GiB，推荐 20 GiB；默认三组 JVM 上限不适合 12 GiB 容器
- CPU：6 核起步
- 健康检查：`/healthz`
- 数据卷：无
- 公网域名：只绑定到这个服务

镜像默认分别给 feed/sim/quant 设置 2 GiB、3 GiB、4 GiB 最大堆。需要压缩或放大时，可覆盖：

```env
FEED_JAVA_XMX=2g
SIM_JAVA_XMX=3g
QUANT_JAVA_XMX=4g
```

## 3. 首次登录变量

首次上线建议只添加：

```env
PASSWORD_LOGIN_ENABLED=true
ADMIN_PASSWORD=<强密码>
```

如果使用 LinuxDo OAuth，再添加：

```env
LINUXDO_CLIENT_ID=<client id>
LINUXDO_CLIENT_SECRET=<client secret>
LINUXDO_REDIRECT_URI=https://<WTFiB域名>/login
```

New API SSO/额度桥接不要写成 `NEW_API_*` 环境变量。启动后进入 `/admin` 配置，环境变量只用于需要锁定字段的部署级覆盖。

## 4. 自动数据库初始化

容器每次启动都会先等待 PostgreSQL，然后：

1. 创建迁移记录表 `wiib_schema_migration`；
2. 使用 PostgreSQL advisory lock，避免滚动发布时两个新旧容器同时迁移；
3. 空库自动执行 `sql/init.sql`；已有 user 表的数据库直接登记为已有基线，不重放初始化脚本；
4. 幂等写入 `sql/bstock.sql` 的静态标的；
5. 按文件名顺序执行尚未记录的 `sql/migrations/*.sql`。

任一 SQL 失败都会阻止 Java 服务启动，日志中会显示失败的迁移；修复后重新部署即可继续，已成功记录的迁移不会重跑。

## 5. 启动完成后的设置

1. 打开绑定的 WTFiB 域名并用管理员密码登录。
2. 在 `/admin` 配置 New API 主站地址、App ID/Secret、额度换算、提现比例和税档。
3. 配置 LLM 渠道和功能模型。
4. 在 New API 主站配置同一 App ID/Secret，并将游戏登录地址设为 `https://<WTFiB域名>/login`。
5. 用小额完成一次转入和转出，再按需要关闭密码登录。

## 非模板数据库的兼容变量

如果使用的不是 Zeabur PostgreSQL/Redis 模板，才需要手工提供：

```env
PG_HOST=<PostgreSQL私网地址>
PG_PORT=5432
PG_DB=<数据库名>
PG_USER=<用户名>
PG_PASSWORD=<密码>

REDIS_HOST=<Redis私网地址>
REDIS_PORT=6379
REDIS_DB=0
REDIS_PASSWORD=<密码，可为空>
```
