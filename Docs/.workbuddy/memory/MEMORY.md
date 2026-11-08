# Botgram 项目长期备注

## 仓库与部署拓扑

- 两个仓库：客户端 `W:\Work\Botgram`（GitHub `2580m/Nyarugram`，master），后端 `W:\Work\Botgram.Backend`（GitHub `Nyarugram-Program/Nyarugram.Backend`，master）。两个仓库独立提交。
- 正式服务有两套：
  1. 本机 Windows 8521 服务，目录 `D:\Botgram\Backend`，supervisor 为 `Run-BotgramBackend.ps1` / `Start-BotgramBackend.ps1`，通过 Windows 任务计划 `BotgramBackendPrimary` 脱离会话持久运行；
  2. 公网 Ubuntu + Caddy + Docker，目录 `/opt/botgram`，SSH `-p 22222 root@43.142.58.20` + `~/.ssh/id_ed25519`。已切换为 PostgreSQL 单权威：专用 `postgres:17-alpine` 容器 `botgram-postgres-1`（静态 172.28.52.4，卷 `botgram_pgdata`，库 `botgram_primary`）。
- 本机正式存储：PostgreSQL 16.15，仅监听 `127.0.0.1:55433`，库 `botgram_primary`；程序 `D:\Botgram\Backend\postgres`，数据 `D:\Botgram\Backend\pgdata`。连接串与口令只放在受限的 `primary-storage.json` 与发布目录 `appsettings.Production.json`，**不要写入仓库或报告**。
- 当前正式发布物：后端提交 `4597614`，本机 release `87b5b13a81fe`（DLL SHA-256 `87b5b13a81fe...`），实际从历史路径 `D:\Botgram\Backend\releases\3aaa21f63cec` 运行（supervisor 缓存启动配置）；上一版保留在 `releases/4fe7786d4042` 与 `3aaa21f63cec-old-20261107T133832Z`。公网镜像 `sha-4597614...`，上一版 `sha-1811809...` 保留。
- 回退备份：本机 `D:\Botgram\Backend\backups\20261107T133832Z-backend-87b5b13a81fe`；公网 `/opt/botgram/backups/upgrade-20261107T133720Z-pts`（配置 + pg_dump）。更早的切换备份仍保留。

## 现行约定

- 通信协议：**只允许 Protobuf**。业务 `/api/v1/rpc`；认证前 `/api/v1/auth`；账号 `/api/v1/account`（协议字段 200–209 为永久编号）。所有旧 JSON `/api/*` 与 JSON WS 子协议统一 410 `BOTGRAM_JSON_API_RETIRED` + `text/plain`。`/health` 也是非 JSON 键值格式。
- 正式存储模式 `Storage.Mode=NewStoragePrimary` + `Storage:ValidationBackend=RelationalBackend`（PostgreSQL 单权威），启动时强制 schema v9（1..9）校验，禁止回落 `LegacyOnly`。`FourComponent` 仍保留但生产未部署 Kafka/Scylla/Redis。
- 协议代码由固定 protoc 25.8 生成并入库（`Protocol/BotgramRpc.cs`、Android Java lite）；改动 `.proto` 后须跑 `tools/protobuf.ps1 -Action Check` 与 `tools/test-protobuf.ps1`。
- 迁移工具 `Storage.Admin`：`init` 只初始化 PostgreSQL schema；`prepare` 仍需 `FourComponent`；`import/export` 支持 `RelationalBackend`。规范指纹对 `JsonNode` 做确定性排序（PostgreSQL `jsonb` 会重排键）。
- CI：`.github/workflows/ghcr.yml`，含 PostgreSQL 16 service、JSON 弃用矩阵、Protobuf 业务/E2EE 双存储回归、E2EE 并发、Storage.Tests、专用 Outbox 生命周期回归，并构建推送 GHCR 镜像归档。
- 交付记录固定位置：`Docs/artifacts/postgresql-e2ee-json-retirement-delivery.md` 与 `artifacts/build-info.json` 的 `jsonRetirementPostgresE2eeVerification`、`postgresqlPrimaryCutover`、`reviewFixes20261107` 块。限制项必须如实记录（四组件未部署、真机未测等）。
- `/health` 在 RelationalBackend 下额外输出 `outbox_pending/outbox_completed/outbox_failures/outbox_worker`；PG-only Outbox 完成器默认启用，配置键见 `Storage:RelationalOutboxCompletion*`。
- 幂等重试必须返回原消息事件为当前用户分配的持久化 `pts`/`pts_count`，**不能**用当前 `sync.pts` 游标；缺失事件或接收人时返回 `BOTGRAM_MESSAGE_EVENT_PTS_MISSING`，不回退。

## 已知尾项

- 新 APK（`c39328c1...`）已签名但未分发，真机未测；旧 APK 访问旧 JSON 接口会收到 410。
- 公网仅 1 个既有会话的令牌摘要，无法用明文令牌做端到端会话验证。
- Kafka/Scylla/Redis 仍未部署；四组件代码路径保持原语义。
