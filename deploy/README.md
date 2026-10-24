# 校内 / 新加坡配置与部署样例（PR-05A–05D）

> 2026-10-16 更新：已增加默认关闭的 SSH + Docker Compose 部署执行、持久任务和远端结果核对。当前使用说明见[中控部署](../docs/architecture/control-deployment.md)；下文原有“固定锁定”描述属于执行器加入前的验收基线，正式环境尚未部署。

这些文件用于配置审查和后续隔离演练。当前项目尚未部署；已有隔离验证覆盖 Compose 渲染、临时测试容器初始化与迁移，PR-04 增加真实 HTTPS / 两库合成传输与 Spring 生命周期测试。没有启动此处 Compose 服务、操作生产数据库、配置真实桶或执行生产迁移。

已配置两套独立应用依赖、内部 UUID、数字 ID 分配域、统一省市字典及 Outbox / Inbox / 命令幂等基础。PR-04 实现独立 HTTPS 节点端口、mTLS、校内主动 push/pull 与逐事件 ACK；PR-05A 增加账号同意与注册登录，PR-05B 增加活动同步，PR-05C 增加六类关系集合、V22/V23 历史回填及 V25 稳定关系因果协议，PR-05D 追加 V24 资料、隐私与通知已读同步。PR-05C V25 当前完整本地验收 `./mvnw -o -B -Pintegration clean verify` 已通过，505 unit + 293 integration 共 798 项，failure / error / skip 均为 0；详情见[因果重授权报告](../docs/architecture/pr05c-causal-regrant.md)。PR-05D 原始全量失败及后续针对性重跑仍保留于[PR-05D 验收记录](../docs/architecture/pr05d-profile-privacy-notification-sync.md)。真实业务 capture、transport、账号、活动、关系、资料、隐私和通知开关均默认关闭，**不能据此开放真实双站业务写入或宣称双活已完成**。`app.account.enabled=false`、`app.sync.capture-enabled=false`、`app.sync.transport-enabled=false`、`app.sync.relation.enabled=false`、`app.sync.profile.enabled=false`、`app.sync.privacy.enabled=false`、`app.sync.notification.enabled=false` 均显式保留；capture=true 继续被拒绝。关系启用前，两端都必须升级到包含 V25 迁移和 V2 action/state handler 的同一兼容版本；V1 仍解析兼容事件，但不能据此混跑旧版 endpoint。多 epoch 无法证明的旧 V1 因果顺序会保持阻断，见[PR-05C 因果重授权报告](../docs/architecture/pr05c-causal-regrant.md)。阶段边界见[阶段计划](../docs/architecture/phased-plan.md)；这不是生产部署或真实站点验收。

## 文件和拓扑

| 文件 | 用途 |
| --- | --- |
| `application.properties` | 两站共用的专用 Spring 配置，连接参数和凭据从容器环境读取 |
| `compose.campus.yml` | `CAMPUS / RUSTFS`；应用、PostgreSQL / PostGIS、Redis、校内 RustFS |
| `compose.external.yml` | `EXTERNAL / R2`；应用、PostgreSQL / PostGIS、Redis；不包含 RustFS |
| `.env.campus.example` / `.env.external.example` | 纯占位参数，不含真实账户和密钥 |
| `sync.synthetic.example.properties` | 仅供审查的隔离合成传输补充模板；默认transport=false，不被现有Compose自动加载 |
| `application.properties` 与合成补充模板 | `app.sync.relation.enabled=false` 明确关闭关系同步；现有 Compose 不加载任何同步补充模板 |
| `initdb/20-create-application-db.sh` | 仅首次新数据库卷初始化：独立应用空库和非超级用户 |

两个 Compose 项目名分别为 `eatwhat-campus`、`eatwhat-external`。它们各自拥有默认网络、PostgreSQL 数据卷和 Redis 数据卷；只有校园项目具有 RustFS 数据卷。数据库和 Redis 不映射宿主端口，应用与 RustFS 端口只绑定本机回环。已有香港 Nginx 的网络接入、TLS、回源规则由后续部署阶段核实，这里不修改任何代理或 DNS。

应用使用明确指定的 Java 21 runtime，挂载经过测试的同一份 JAR；无需把仓库或本机配置复制进镜像。运行用户为 UID / GID 10001，JAR 及专用配置必须可读，根文件系统只读，临时文件使用 `/tmp`。镜像标签是样例基线，首次部署前记录审核过的版本 / digest；RustFS 故意保留不可直接运行的版本占位值。PostGIS 示例的 amd64 平台来自当前隔离测试；ARM 宿主上的模拟结果不能代替实际生产硬件验证。

## 配置边界

- Compose 为每站固定 `SITE_MODE` 和 `S3_PROVIDER`，绑定到 `app.site.mode` 与 `app.storage.s3.provider`。`SITE_ID` 必填、两站不同、后续不得随意复用。`SITE_INSTANCE_ID` 为每次数据库安装生成一次的非零 UUID，首次绑定后持久保留，重启不重新生成；示例 UUID 必须替换。校内 / 校外的新业务数字 ID 从 1000000001 / 1000000002 起按奇偶分配，保留数值接口；这不启用同步。
- JAR 命令行固定 `--spring.config.location=file:/config/application.properties`，并清空 additional-location / import。Compose 只向应用传入列出的变量，没有整文件 `env_file` 注入，不加载宿主的 `application.yml` 或仓库根 `.env`。
- 配置加载隔离不能阻止秘密被打包。本阶段 Maven 已排除本机 `application.yml` 和 `application.example.yml`，集成测试检查发布 JAR 保留迁移、`pc.json` 和安全的 `application.properties`，且能执行早期配置校验。每次发布仍须通过同一检查；不要挂载旧的含本机配置 JAR。
- 未绑定新站点的既有运行方式继续使用 `LEGACY / GENERIC` 和原 `app.storage.s3.*`。已绑定显式模式的数据库不能通过清除配置退回 LEGACY。首次显式模式要求业务空库；省市字典必须为空或为发布 manifest 的规范子集。遇到非空业务库或错字典会拒绝启动，不自动重编号。这些样例不能拿来覆盖运营者既有配置。
- 保留 Sa-Token、JWT、邮件、短信及学校回调机制。样例沿用现有会话策略：timeout=-1、active-timeout=259200、is-concurrent=false、is-share=false、Bearer、cookie-auto-fill-prefix=true；token-name 不配置，继续使用库默认 satoken。回调和 `APP_BASE_URL` 必须指向正确站点。暂不承诺跨站会话可互换，也不通过共享 Redis 实现互通。
- `.env` 中含 `$`、空格、引号的值需按 Compose dotenv 语法正确引用；不要用 shell `source` 执行。环境文件应限制本机权限；有权访问 Docker 的运维人员仍能查看容器环境。
- Sentry 默认关闭；远端邮件健康探测关闭且不在启动时连接。`/actuator/health/readiness` 检查本机应用状态、数据库和 Redis；`/actuator/health/liveness` 只表明进程存活。健康详情不公开。对象存储和对端不可用不应冒充本地数据库故障，也不会被一次本地健康成功所证明。

## 准备和只读验证

先在后续获准的隔离工作流中生成并验证 JAR。不要直接运行旧工作区配置来“试连接”。实际凭据只写入匹配的、被 Git 忽略的 `deploy/.env.campus` 或 `deploy/.env.external`；本目录的 example 文件必须保持占位内容。

| 参数组 | 要求 |
| --- | --- |
| `APP_JAR` / 镜像参数 | JAR 使用绝对路径；两站相同校验和，镜像指定审核版本 |
| `SITE_ID` / `SITE_INSTANCE_ID` / URL / 端口 | 站点 ID 唯一；安装 UUID 生成一次并持久保存，例如用 `uuidgen`；校内与新加坡各自 URL；同机演练端口不同 |
| `PG_*` | 两站独立凭据；管理员仅用于初始建库 / Flyway，业务账号不能与管理员相同 |
| `REDIS_PASSWORD` | 每站独立 Redis 密码；Redis AOF 仅是本机持久化，不是跨站复制 |
| `APP_JWT_KEY` / 邮件 / 短信 | 按现有认证契约配置，不能用示例占位值验收业务 |
| `S3_*` | 预建桶、限制到所需桶的应用凭据、浏览器可达的 API endpoint |
| `RUSTFS_ROOT_*` | 仅校园对象存储管理凭据，不与应用桶凭据混用 |

以下命令只读取**合成样例**，不启动服务、不访问数据库或桶，不输出解析后的密钥：

```sh
docker compose --env-file deploy/.env.campus.example -f deploy/compose.campus.yml config --quiet
docker compose --env-file deploy/.env.external.example -f deploy/compose.external.yml config --quiet
bash -n deploy/initdb/20-create-application-db.sh
```

准备实际环境文件后，使用相同命令，显式把 `--env-file` 改为对应站点文件，仍保留 `config --quiet`。宿主中已导出的同名变量可能覆盖 env 文件；应在受控 shell 中核对，避免把校内参数用于校外。不要对真实凭据运行并粘贴完整 `docker compose config`、`config --environment` 或 `docker inspect` 输出。

渲染成功只证明 Compose 格式及必要变量存在，不证明 JAR 可读、占位值已替换、凭据有效、桶存在或应用能够启动。后续真正启动应用会运行 Flyway 迁移、站点身份绑定、数字序列配置与字典初始化；应只针对明确授权的空库，不能把此操作描述为只读。

## PR-04 合成传输配置边界

`sync.synthetic.example.properties` 不是可直接启用的部署清单。现有 Compose **不会自动加载该文件、传入其 `SYNC_*` 变量、挂载 PKCS12 证书或映射节点端口**；原专用配置位置及关闭的 import / additional-location 保持。后续隔离演练须单独审核配置覆盖、环境变量、证书只读挂载和网络可达性，明确合入演练用专用配置；不能仅修改示例中的开关就声称通道已部署。

- EXTERNAL 启动独立 JDK HTTPS listener，路径仅为 `/internal/sync/v1/{push,pull,ack}`；主业务 MVC 端口不增加这些路由。CAMPUS 只主动发起 push / pull / ACK，没有同步 listener，不需要校内入站端口。
- 双向 TLS 同时校验受信 CA 与对端叶证书 SHA-256 固定值，配置支持一或两个 pin 以供轮换；保留证书有效期和客户端主机名验证。另固定对端 site ID / instance UUID，目标由配置确定，不从请求体指定任意节点。不信任代理转发的证书 / IP 头，也没有自制 nonce 协议；相同认证节点的正常重复请求由事件幂等与租约处理。
- 模板的 listener `127.0.0.1:9443` 只监听本地回环。容器演练需要另审容器内监听地址、宿主映射和防火墙 / 私网路径，不能假设现有业务端口映射使节点端口可达。校内 peer URL 必须是与证书主机名匹配的固定 HTTPS 地址，EXTERNAL 不配置主动 peer URL。
- `synthetic-only=true` 只允许有界 `synthetic.counter / counter.incremented`，拒绝真实业务事件、actor 和依赖。生产处理器仍为空，合法但不支持的版本可以持久隔离并确认收件；`DURABLY_QUEUED` 不代表业务应用或全站收敛。测试专用处理器不进入生产注册。
- 配置形状与文件可读性先校验；PKCS12 内容、密码、密钥与证书加载在传输生命周期启动时校验，可能晚于 Flyway。错误证书令传输启动失败，但不能据此断言数据库从未迁移或绑定身份。

关闭 transport 并重启会停止校内调度或校外 listener，保留现有队列。恢复使用原事件 ID、hash、数值 ID 与持久租约；不清理 Inbox 或未 ACK 事件。合成测试已经覆盖真实 Spring 调度、同一校内进程断网恢复、上下文关闭 / 重建和端口释放；完整回归结果见 [PR-04 记录](../docs/architecture/pr04-node-transport.md)。这不替代真实拓扑、长期积压、证书运营和备份恢复的部署验收。

## 数据库初始化和恢复

PostGIS 镜像首先在内置 `postgres` 数据库安装自身扩展。初始化脚本另从 `template0` 创建 `PG_DB`，因此应用数据库在首次 Flyway 前没有预建业务 / PostGIS 对象。Flyway 使用 `PG_ADMIN_USER` 创建既有迁移要求的 citext / PostGIS 和表；应用连接使用单独的 `NOSUPERUSER / NOCREATEDB / NOCREATEROLE / NOREPLICATION` 角色，获得 public schema 使用权及后续表 DML / sequence 使用权。

脚本用 psql 的环境变量读取和变量引用处理密码；不会将应用密码放在 psql 命令行或主动回显。数据库和角色名限定为 1–63 位小写字母 / 数字 / 下划线且首位非数字。迁移连接与业务连接指向同一个库。因应用启动仍持有 Flyway 管理凭据，这种角色分离不等于迁移凭据已经从应用进程隔离。

`baseline-on-migrate=false`、`validate-on-migrate=true`、`out-of-order=false`、`clean-disabled=true` 固定保留；PR-03A 追加 V16–V18，PR-03B 追加 V19，PR-04 不新增迁移，PR-05A 追加 V20，PR-05B 追加 V21，PR-05C 追加 V22/V23/V25，PR-05D 追加 V24；不修改旧迁移、不补 V9、不使用 baseline / repair 绕过未知非空库。显式模式不能关闭 Flyway 跳过身份初始化。运行角色只能查询身份映射，不能修改节点登记或回填元数据；事件 schema 单独授权 INSERT / SELECT 与状态列 UPDATE，不允许删除记录、改写事件或重置队列序列。迁移角色负责初始化及授权。V23 的 NULL-origin claim 仅验证 V18 冻结准备态的受控证明，不会自动激活任意旧非空库。PR-05D 三项功能开关和关系开关在应用与合成配置中都默认为关闭。V25 两端升级与因果修复验收要求见 [PR-05C 因果重授权报告](../docs/architecture/pr05c-causal-regrant.md)；完整迁移方案见 [database-migration.md](../docs/architecture/database-migration.md)。

首次绑定及后续启动检查数据库和安装身份；换库、改站点、错误分配域需要先报告，不能编辑登记表或重置 sequence 来让应用启动。完整数据库与配置一起克隆、旧快照恢复后的重发风险仍需外部 fencing / 恢复流程处理；本地自检不等于离线多机互斥证明。字典安装有自己的事务：若其失败，应用不就绪，已提交的身份绑定保留，使用同配置修复原因后重试。

镜像只对新卷执行 initdb 脚本。已有卷不会因修改 `PG_*` 或重新创建容器自动改密码、加权限或重建库；初始化中断也不能假设下次启动会自动补完。遇到部分建库 / 建角色失败时保留卷和日志，在隔离恢复步骤中逐项确认；不要把生产或已有开发卷删除后重试。

## 对象存储 URL 和公开权限

校内 `S3_ENDPOINT` 必须是客户端能访问的 RustFS S3 API 地址。`http://rustfs:9000` 仅在容器网络有效，不能发给浏览器作为上传地址。本例只提供 RustFS 服务及数据卷，桶、应用用户、TLS、桶策略和 CORS 仍需独立准备；管理控制台不应暴露公网。[RustFS Docker 官方指南](https://docs.rustfs.com/en/installation/container/docker)

新加坡固定使用 R2；本项目显式 R2 模式要求 `region=auto`，endpoint 为 `https://<32位账户ID>[.eu|.us|.fedramp].r2.cloudflarestorage.com`，须以实际桶的 API endpoint 为准。新加坡服务器位置不代表 R2 对象一定存储在新加坡。[R2 S3 API](https://developers.cloudflare.com/r2/api/s3/api/)

新模式的私有 GET 与 PUT 保留预签名 API 域名，不把 host 替换为 CDN 域名。R2 官方的自定义域名不支持 S3 预签名 URL；香港 Nginx 的私有签名转发也不能根据旧的字符串替换规则默认视为可用。[R2 预签名 URL](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)

`S3_PUBLIC_BASE_URL` 表示完整公开对象根。若代理需要桶路径，就由配置包含该路径，例如 `https://cdn.example.invalid/campus-bucket`；R2 绑定单桶的公开域名一般直接配置域名根。应用只在根后追加对象 key，不自动再插入 bucket。

默认 `S3_PUBLIC_READ_PREFIXES` 为空，所以所有列出的可读前缀仍走私有签名。样例可读前缀与当前 UploadBiz 一致：`avatar/`、`activity/`、`restaurants/`、`foods/`。只有明确批准公开的前缀才可加入公开列表，并同时配置公开对象根与实际代理 / 桶权限。**应用公开前缀列表不会约束 R2 已公开整桶的底层权限**；混有私有对象的桶不可仅因为应用有白名单就整体开启公开自定义域名。

预签名操作在本地生成 URL，并不证明远端上传成功。R2 不可用时客户端实际 PUT 可能失败，当前还没有上传完成校验、文件目录或恢复 worker；这些属于 PR-06A / B。用户已选择失败时明确失败，当前不提供磁盘 spool，不声称已实现故障补传。浏览器 CORS、签名 Content-Type、真实读写、公开 / 私有隔离和香港 CDN 回源均需后续实测。

## 回滚与验收边界

PR-02B 只改变配置；PR-03A 追加身份 / 回填 / 字典登记，显式模式会调整业务序列分配规则并固定省市 ID；PR-03B 追加事件、因果状态和去重记录；PR-04 在原 schema 上增加合成传输。回滚单位为经过检查的应用 JAR 加配套配置，保留两个项目名、数据库卷、身份元数据、事件元数据、Redis 卷和校园对象卷。不得执行 `docker compose down -v`、删除桶、删除未 ACK 事件、覆盖另一站快照或重置序列。开始新分配后不能回到增量 1 或不理解身份绑定的旧应用继续写入；优先向前修复。

若回退到只理解旧 S3 配置的版本，必须同时恢复兼容配置并验证签名 / 公开 URL；旧版不会理解 `public-base-url`。没有可验证映射时，先停止相关上传 / 公开链接入口，不把回退代码当成 URL 兼容证明。严禁重新发布打包了本机真实配置的旧 JAR。数据库凭据和角色不会随配置文件回滚，必须与保留的卷一致。

PR-02B 已在全新测试容器中执行此初始化脚本、完成当时的 14 份迁移，并验证特殊字符密码、业务角色 CRUD / sequence 权限及 DDL 拒绝。两个独立测试 JVM 的数据 / 会话隔离、对端停止后的本地运行也已验证；追加迁移及身份 / 字典验证见 PR-03A 报告。

尚待验证：这套 Compose 的整体启动、真实 RustFS / R2 / 邮件 / 短信 / 学校认证、实际节点网络与生产证书、CORS / CDN、实际硬件与备份恢复。隔离 mTLS 和合成传输测试不代替这套部署文件的完整启动验收。本阶段不执行首次上线；历史报告见 [PR-02B](../docs/architecture/pr02b-dual-mode-config.md)、[PR-03A](../docs/architecture/pr03a-global-identity.md)、[PR-03B](../docs/architecture/pr03b-transactional-events.md)和[PR-04](../docs/architecture/pr04-node-transport.md)，PR-05D 的原始全量命令、修正后受影响类回归与测试计数见 [PR-05D 记录](../docs/architecture/pr05d-profile-privacy-notification-sync.md)。

## PR-05A 账号合成联调

[账号配置样例](account.synthetic.example.properties) 默认关闭，不由 Compose 自动装载。账号 RPC 与 PR-04 事件端口独立，双方都需要通过私网到达对方的 mTLS listener；`peer-base-url` 必须是固定 HTTPS origin。配置中默认最长授权和 proof 有效期均为5分钟。首次离线注册、PROVISIONAL 与人工冲突状态见 [PR-05A记录](../docs/architecture/pr05a-account-consent.md)。

账号模块不是自动生产放行开关，synthetic-only 也不会把真实数据库自动匿名化。只可在隔离合成测试库使用，禁止通过本样例连接真实账号库。关闭模块后不能让已有授权账号的外站继续提供无门控业务：回滚先停外站业务入口，保留 V20 同意/撤回/任务与身份墓碑，再向前修复。

## PR-07B 文件事件通道

[file-events.synthetic.example.properties](file-events.synthetic.example.properties) 默认关闭，不由现有 Compose 自动加载。启用条件、V34 双站升级、元资料/墓碑/字节声明与应用 ACK、回滚和验证边界见 [PR-07B](../docs/architecture/pr07b-file-event-channel.md)。本阶段未执行部署；普通传输 ACK 或墓碑应用确认不允许对 PEER 副本执行物理 GC。
