# New API SSO 与额度经济部署手册

本文说明如何把独立部署的 WTFiB 接到 New API 主站。两边只共享一次性 SSO 授权和额度转账接口，不共享数据库，也不把主站密钥交给浏览器。

## 1. 资金口径

- 新账号、破产恢复和自助重置默认资金均为 `0`，不会凭空赠送可转出的本金。
- `1.00` 游戏金额对应 `NEW_API_QUOTA_PER_UNIT` 主站额度，默认 `500000`。
- 主站转入成功后，同额增加 WTFiB 的余额钱包和受保护本金；受保护本金不计作盈利。
- 股票、合约、预测市场、扑克、扫雷和 21 点继续使用 WTFiB 内部钱包，内部划转不会调用主站。
- 提现毛额只可来自真实盈利，并同时受可用现金、盈利比例和每日上限约束；主站实际收到的是扣除边际累进税后的净额。
- 每笔跨站转账都以同一个 `operation_id` 在两边持久化。超时重试只查询或重放同一操作，不会重新扣款。

## 2. 上线顺序

1. 备份 WTFiB PostgreSQL 和 New API 数据库。
2. 先部署包含外部应用接口的 New API。主节点启动时会自动迁移 `external_app_auth_codes` 和 `external_quota_operations`。
3. 对已有 WTFiB 数据库执行专用增量脚本；全新库仍直接执行 `sql/init.sql`。

   ```bash
   psql -v ON_ERROR_STOP=1 -U <user> -h <host> -d wiib \
     -f sql/migrations/20260730_new_api_quota_economy.sql
   ```

   不要为了本功能在生产旧库重跑整份 `sql/init.sql`；其中还包含其他历史升级语句。

4. 部署 WTFiB，但先在 Admin 页保持 New API 额度桥接关闭。
5. 在两边配置同一组 App ID/Secret，先启用 New API，再在 WTFiB Admin 页启用桥接。
6. 用测试账号完成第 5 节的冒烟检查，再向用户开放入口。

## 3. New API 配置

在 New API 的环境变量或 root 设置中配置：

```env
EXTERNAL_GAME_ENABLED=true
EXTERNAL_GAME_APP_ID=wtfib
EXTERNAL_GAME_APP_SECRET=<至少16字符的高强度随机密钥>
EXTERNAL_GAME_REDIRECT_URI=https://stocks.example.com/login
EXTERNAL_GAME_CODE_TTL_SECONDS=120
EXTERNAL_GAME_SIGNATURE_TOLERANCE_SECONDS=300
```

建议用 `openssl rand -hex 32` 生成共享密钥，并只保存在两边的服务端环境变量中。`EXTERNAL_GAME_REDIRECT_URI` 必须是 WTFiB 对外登录页 `/login`，生产环境必须使用 HTTPS；New API 会固定回跳到该地址，并附加 `provider=new-api`、一次性 `code` 和原样 `state`。

如果 New API 是多节点部署，只有 master 节点会执行数据库迁移，但所有节点必须使用相同的外部应用配置和 `SESSION_SECRET`。两台服务器还应保持 NTP 正常；默认 HMAC 时间容忍为 300 秒。

## 4. WTFiB 配置

推荐使用 WTFiB `/admin` 页中的“New API 额度桥接”卡片配置。它会把设置持久化到
`new_api_runtime_config`，保存后以整份运行时快照立即生效；App Secret 不会通过管理 API
回显，密钥框留空表示保留原值。

也可以通过 WTFiB 根目录 `.env` 设置部署级覆盖：

```env
NEW_API_ENABLED=true
NEW_API_BASE_URL=https://youzi.today
NEW_API_APP_ID=wtfib
NEW_API_APP_SECRET=<与主站完全相同的随机密钥>
NEW_API_QUOTA_PER_UNIT=500000

NEW_API_WITHDRAWAL_ENABLED=true
NEW_API_WITHDRAWAL_PROFIT_RATE=0.50
NEW_API_WITHDRAWAL_DAILY_LIMIT=100.00
NEW_API_WITHDRAWAL_MIN_AMOUNT=1.00
NEW_API_WITHDRAWAL_ZONE_ID=Asia/Shanghai
NEW_API_WITHDRAWAL_TAX_BRACKETS=20:0.05,50:0.10,100:0.15,*:0.20
```

注意：

- 环境变量优先级高于数据库。只要某个 `NEW_API_*` 变量存在，Admin 页对应字段就会只读；删除变量并重启后才改由数据库设置接管。
- `NEW_API_BASE_URL` 是主站根地址，不带 `/v1`，末尾斜杠可有可无。
- `APP_ID` 和 `APP_SECRET` 必须与 New API 完全一致。
- `NEW_API_QUOTA_PER_UNIT` 必须等于主站实际 `QuotaPerUnit`。登录换码时 WTFiB 会校验主站返回值，不一致会拒绝登录，避免错账。
- `NEW_API_WITHDRAWAL_ENABLED=false` 只关闭盈利转回，SSO 和主站额度转入仍可使用。
- 税档格式为 `累计毛额上限:边际税率`，阈值必须递增，最后必须以 `*` 收尾。税额按当天累计毛额的总税额差计算，拆单不会降低累计税额。

## 5. 冒烟检查

1. 确认 New API `/api/status` 与 WTFiB `/actuator/health` 正常。
2. 浏览器先登录 New API，再在 WTFiB 登录页点击“使用 Youzi API 登录”。授权应回到 `/login` 并进入同一主站身份对应的游戏账号。
3. 对已有 LinuxDo/密码账号，不要退出重登来“猜测合并”。在“主站额度钱包”中点击“绑定 New API 主站账户”；绑定会保留当前持仓和历史。一个主站身份只能绑定一个游戏账号，系统不会自动合并两个已有账号。
4. 转入 `1.00`：主站应减少 `500000` 额度，WTFiB 余额和受保护本金各增加 `1.00`，最近记录最终变为“已完成”。
5. 再次查询同一条记录或等待对账，不应发生第二次扣款。
6. 产生少量真实盈利后预览提现，确认“毛额、税、净额、今日上限”符合配置；提交后主站只增加税后净额对应的额度。
7. 在 WTFiB 数据库检查最近状态：

   ```sql
   SELECT operation_id, user_id, direction, amount, fee, net_amount,
          quota_amount, status, error_code, attempt_count, created_at
   FROM external_quota_transfer
   ORDER BY id DESC
   LIMIT 20;
   ```

正常终态是 `COMPLETED`。临时网络故障会保持 `PENDING` 并由调度器重试；主站明确拒绝的转入会标记 `FAILED`，明确拒绝的提现会把预留毛额完整退回后标记 `FAILED`。

## 6. 故障处理与回滚

- 只需紧急关闭提现：在 WTFiB Admin 页关闭“允许盈利转回主站”；也可用 `NEW_API_WITHDRAWAL_ENABLED=false` 覆盖并重启。已提交记录仍会继续对账。
- 需要停止所有新跨站操作：先在 Admin 页关闭额度桥接（或用 `NEW_API_ENABLED=false` 覆盖），但应让已有 `PENDING` 记录完成对账后再关闭 New API 的 `EXTERNAL_GAME_ENABLED`。
- 不要删除 `external_quota_transfer` 或 New API 的幂等操作表；删除会失去重试判定依据。
- 轮换共享密钥前先等待 `PENDING` 清零，再在两边的管理设置中更新同一密钥；只改一边会让服务间请求持续返回 401。环境变量托管密钥时仍需修改变量并重启。
- 回滚应用版本时保留新增列和表即可；它们不会影响旧版查询。

## 7. 信任边界

- 浏览器只接触公开授权 URL、随机 `state` 和短期一次性授权码，不接触共享密钥。
- `state` 由 WTFiB 浏览器生成并在回跳时校验；New API 只接受长度合规的值并原样带回。
- 换码、扣额度、加额度和状态查询均由 WTFiB 服务端发起，使用覆盖请求体、路径、方法与时间戳的 HMAC。
- New API 中额度变更与幂等记录在同一数据库事务；WTFiB 中本地钱包、账本和转账状态也在同一事务。网络位于两笔本地事务之间，由持久状态和重试收敛，不使用长时间跨库事务。
