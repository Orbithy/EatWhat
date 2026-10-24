# 中控后端 API v1（前端契约）

此模块默认关闭；允许 `app.control.environment-kind=isolated-acceptance` 或 `managed-deployment`（后者禁用故障代理和本机测试任务）。未启用时不注册控制路由、测试任务或故障代理。管理服务与业务站分开部署。集群部署执行器独立默认关闭；配置及执行协议见 [control-deployment.md](../../docs/architecture/control-deployment.md)。SSH 服务器管理、独立业务站首次安装及单服务器更新使用单独的 `app.control.servers.installation-enabled` 开关，契约见 [control-servers-api.md](../../docs/architecture/control-servers-api.md)。

## HTTP 与认证

浏览器基路径 `/admin/control/v1`，沿用现有 Sa-Token 登录及 `admin` 角色。使用实际登录返回的 token 名称和 token 值；不能硬编码测试 token 名。生产默认 token 名通常为 `satoken`，以运行配置为准。所有请求均检查角色。成功响应直接为下面的 JSON 对象或数组，**不套业务 Result 的 code/data/msg**。错误使用 HTTP 状态及 `{ "code": "ADMIN_REQUIRED", "timestamp": "2026-10-16T00:00:00Z" }`。401 需登录、403 非管理员、400 参数错误、404 未配置／未找到、409 当前状态不允许、423 部署锁定、503 节点／依赖不可用。

前端建议每 5 秒刷新 sites、活动 test-runs；刷新失败必须显示未知／过期，不能继续显示在线。所有数据使用安全文本渲染，禁止拼接为 HTML。不要在 URL、日志或 localStorage 中保存管理员 token。跨来源开发使用已有白名单 CORS，优先同源反向代理，不能添加通配来源。

## 路由

| 方法与路径 | 请求 | 返回 |
|---|---|---|
| GET `/capabilities` | 无 | apiVersion, environmentKind, deploymentExecutionEnabled（是否配置启用）, remoteFaultMatrixAutomated=false, testSuites, configuredChannels |
| GET `/sites` | 无 | 下面的 Sites |
| POST `/sites/{site}/faults` | `{ "channel":"events", "action":"BLOCK", "seconds":30 }` | `{requestId, channel:{state,expiresAt,acceptedConnections,forwardedBytes,routingVerified}}` |
| GET `/test-suites` | 无 | `[{id,scope,enabled}]` |
| POST `/test-runs` | `{ "suite":"business" }` | HTTP 202, Run |
| GET `/test-runs` | 无 | Run[]，当前和最多 1000 份本机持久历史 |
| GET `/test-runs/{uuid}` | 无 | Run，支持按 ID 读取持久历史 |
| POST `/test-runs/{uuid}/cancel` | 无 | Run |
| GET `/releases` | 无 | `[{id,sha256,acceptanceEvidenceId}]`，不含本机路径 |
| POST `/deployment-plans` | `{ "releaseId":"candidate-1", "sites":["campus","external"] }` | HTTP 201, Plan |
| GET `/deployment-plans/{uuid}` | 无 | Plan |
| POST `/deployment-plans/{uuid}/execute` | 无 | HTTP 202 持久部署任务；未启用执行器时 HTTP 423 `DEPLOYMENT_LOCKED` |
| GET `/deployments/{uuid}` | 无 | 部署任务与逐站结果 |
| POST `/deployments/{uuid}/reconcile` | 无 | 只读核对远端 journal，不重发更新 |
| GET `/audit?limit=50` | limit 1–200 | `[{requestId,actor,action,target,outcome,at,details}]`，时间升序的最新 N 项；故障请求 details.seconds 保留请求时限 |

`site` 只能是运行配置的 `campus` 或 `external`；`channel` 只能是节点已配置的固定通道。action 为 BLOCK/RESTORE，seconds 必填 1–300 且不得超节点 channel.maximumBlockSeconds 时限，RESTORE 不需要等倒计时。RESTORE 在数据库不可达时仍可执行。界面操作前应显示目标站点、通道、持续时间及影响，并要求一次明确确认。

`capabilities.configuredChannels` 是中控操作员预先配置的恢复清单，例如 `{"campus":[{"name":"events","maximumBlockSeconds":60,"source":"HUB_CONFIG","state":"NOT_OBSERVED","routingVerified":false}],"external":[]}`。读取此字段不访问节点，节点失联时初次登入仍可从清单选择 RESTORE。清单为空时不能猜测名称。此字段不证明节点在线、代理状态或 worker 路由，不可用于满足 BLOCK 的实时观测条件；BLOCK 上限以最新观测的 `sites[site].channels[channel].maximumBlockSeconds` 为准。RESTORE 不使用 seconds 控制时长，但接口仍要求 1–300。

### Sites

```json
{
  "observedAt":"2026-10-16T00:00:00Z",
  "pairStatus":"UNVERIFIED",
  "sites":{
    "campus":{"observationStatus":"UNKNOWN","reason":"NODE_NOT_CONFIGURED","observedAt":"2026-10-16T00:00:00Z"},
    "external":{"observationStatus":"UNKNOWN","reason":"NODE_NOT_CONFIGURED","observedAt":"2026-10-16T00:00:00Z"}
  }
}
```

pairStatus 为 UNVERIFIED / VERIFIED_DISTINCT / SAME_DATABASE_REJECTED。OBSERVED 表示数据库身份、V35 和协调守卫已经核对，**不等于站点健康、存储健康或同步收敛**。OBSERVED 节点数据如下（字段值仅用于解释结构，不作为验收数据）：

```json
{
  "siteId":"campus-acceptance","instanceId":"11111111-1111-4111-8111-111111111111","mode":"CAMPUS",
  "observedAt":"2026-10-16T00:00:00Z","observationStatus":"OBSERVED",
  "readiness":"NOT_CHECKED","storageReachability":"NOT_CHECKED","deploymentExecutionEnabled":false,
  "channels":{"events":{"state":"FORWARDING","expiresAt":"","acceptedConnections":0,"forwardedBytes":0,"routingVerified":false}},
  "database":{
    "siteId":"campus-acceptance","instanceId":"11111111-1111-4111-8111-111111111111","mode":"CAMPUS",
    "databaseFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","v35":true,"restrictedRole":true,"coordinationGuard":true,
    "delivery":{"READY":2,"ACKED":4},"inbox":{"APPLIED":4},"transfers":{"READY":1},
    "replicas":{"AVAILABLE_LOCAL":1},"publication":{"LOCAL_ONLY":1},"activeReferences":1,
    "unpublishedReceipts":0,"multipartJobs":0,"cdn":{"READY":1},"gc":{"PREPARING":1},
    "recentGc":[{"file_id":"22222222-2222-4222-8222-222222222222","revision":1,"operation_id":"33333333-3333-4333-8333-333333333333","role":"COORDINATOR","state":"PREPARING","peer_prepared":false,"peer_deleted":false,"updated_at":"2026-10-16T00:00:00Z"}]
  }
}
```

状态分组键随数据变化，前端不能假设固定数量。recentGc 最多 20 条，不含对象 key、引用主体、用户资料或事件 envelope。数据库查询失败时无 database 字段。channels 的 FORWARDING 仅指代理放行，routingVerified 始终 false：必须另行确认实际 worker 流量已接入代理，不能显示“跨站网络正常”。BLOCKED 会关闭已有测试连接并拒绝新连接，到期自动放行；它不改变 GC 冻结状态。

### Run

```json
{"id":"44444444-4444-4444-8444-444444444444","suite":"business","scope":"LOCAL_BUSINESS_RUSTFS","state":"QUEUED","createdAt":"2026-10-16T00:00:00Z","deploymentPerformed":false,"remoteAcceptance":"NOT_RUN"}
```

suite 固定 business / coordinated-gc / full-verify。scope 分别 LOCAL_BUSINESS_RUSTFS / LOCAL_DUAL_SITE_PROTOCOL / LOCAL_CLEAN_VERIFY。不接受任意命令、Maven 参数、路径、环境变量或 URL。只有配置了 repository 与 java-home 才启用。一次只能运行一个任务。state 为 QUEUED / RUNNING / COMMAND_SUCCEEDED / FAILED / CANCELLED / INTERRUPTED；结束后可能增加 exitCode、completedAt、reportStatus=REVIEW_REQUIRED 或 reason。重启后无法拥有的旧运行任务显示 INTERRUPTED，不继续显示正在运行。**COMMAND_SUCCEEDED 只表示 Maven 退出 0，不能显示“远端验收通过”**。取消任务不会撤销已完成测试操作，需另查资源清理。日志留在私有状态目录，不通过 API 暴露。

### Plan

包含 id、createdAt、releaseId、artifactSha256、sites、state=PREVIEW_ONLY、executionEnabled、observations、blockers、steps。候选 JAR 在计划阶段与执行阶段都重新校验 SHA-256。blockers 取自实际配置，包括执行器未启用、兼容升级未审查、恢复演练未登记、目标身份不符或更新中控自身。前端只有在能力与计划明确启用、零阻挡、成员身份仍符合并再次确认后才执行。步骤列表始终只是计划；实际结果通过部署任务读取。详细状态、逐站失败回退、持久互斥及未知结果核对见 [中控部署](../../docs/architecture/control-deployment.md)。

## 内部节点接口（不向前端暴露）

`GET /internal/control/v1/snapshot` 和 `POST /internal/control/v1/faults` 使用 `X-Control-Node-Token`。每站独立随机 token（至少 43 字符的 URL-safe 字符串），放绝对路径的权限 600 文件，只由服务器读取。节点 origin 固定于服务器配置，HTTPS 使用系统可信 CA、主机名验证且不跟随重定向；明文只允许字面量 `127.0.0.1` 的 SSH 隧道。浏览器不得持有节点 token。节点接口只在 control.enabled 与 node-enabled 同时为 true 时存在，使用专用私网／SSH 转发入口，不对公网发布。

节点 fault 请求为 `{requestId:UUID,channel,action,seconds}`；BLOCK 必须再次核对本站数据库身份，RESTORE 可在失联时执行。中控 BLOCK 还要求两站数据库指纹不同、观测新鲜（30 秒内）。传输失败返回 NODE_REQUEST_FAILED，并记录 OUTCOME_UNKNOWN；前端应重新读取状态，不假定未执行。

## 管理員手動切主

在同一邏輯權威的多個控制程序間交接，主機或備機入口均要求 `admin`。`GET /admin/control/v1/cluster/capabilities` 的 `leadershipHandoffEnabled` 表示功能已配置；不等同於邏輯權威轉移，`coordinatorTransferEnabled` 仍為 false。

| 方法與路徑 | 請求／回應 |
|---|---|
| GET `/admin/control/v1/cluster/leadership` | `localControllerId`、`leaderControllerId`、`generation`、`handoffEnabled`、`controllers:[{id,name,sharedState}]` 及最新 `handoff` |
| POST `/admin/control/v1/cluster/leadership/handoffs` | `{requestId:UUID,expectedGeneration:正整數,targetControllerId:UUID}`，202 交接記錄 |
| GET `/admin/control/v1/cluster/leadership/handoffs/{id}` | 200 持久交接記錄；不存在 404 |
| POST `/admin/control/v1/cluster/leadership/handoffs/{id}/cancel` | 僅 WAITING 可取消；已 CANCELLED 冪等返回，其他狀態 409 |

交接記錄包含 `id,sourceControllerId,targetControllerId,expectedGeneration,state,reason,requestedAt,finishedAt`；時間為資料庫時間字串，未完成 `finishedAt` 為空。沒有請求時 `handoff.state=NONE`。持久狀態為 WAITING / TRANSFERRING / COMPLETED / CANCELLED / FAILED。在線清單依 5 秒心跳存活期限，UUID 在程序重啟後改變；只允許 sharedState 明確為 true 且非當前主機的目標。

確認後重新讀取能力與租約，不沿用過期 generation。送出回應未知時保留原 requestId，先 GET 查詢；只有明確 404 才能以同一 requestId 重送。相同 UUID 改變目標或 generation 回 409 `IDEMPOTENCY_CONFLICT`。其他 409 包括 `CONTROL_GENERATION_CHANGED`、`CONTROL_TARGET_OFFLINE`、`CONTROL_SHARED_STATE_REQUIRED`、`CONTROL_HANDOFF_PENDING`。等待中的新管理寫入回 `CONTROL_HANDOFF_PENDING`；已接受部署仍繼續，部署核對仍可用。詳細流程見 [主備接管](../../docs/architecture/cluster-control-ha.md)。
