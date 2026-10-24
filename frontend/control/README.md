# EatWhat 中控前端

> 2026-10-17 首次業務站恢復：Linux PostgreSQL 容器無法讀取 root-only 初始化腳本；已將不含憑據的腳本改為 644，私有父目錄與秘密檔案維持 700／600。既有資料卷保留，補齊初始化後完成實際備份恢復、節點身分、執行中 JAR 雜湊及初始管理員核對；Safari 實際頁面顯示已安裝 1 台、任務 SUCCEEDED。業務在目標主機 `127.0.0.1:18090` 就緒，`https://v50to.you/api` 的公網 TLS 尚未驗收通過。見 [修復與恢復驗證](../../docs/architecture/evidence/control-initdb-permissions-verification.json)。

> 2026-10-17 修補：已修正 SSH 預設 22 埠的 known_hosts 主機名稱格式，19 項單元測試與 5 項 HTTP 整合測試通過，並使用頁面已保存的本機 SSH 憑據實際連線驗證成功。刷新後重新按「連線檢測」即可更新卡片狀態，無需重新登記。見 [修補驗證](../../docs/architecture/evidence/control-ssh-port22-fix-verification.json)。

> 最新狀態（2026-10-17）：SSH 伺服器管理與獨立業務站首次安裝已上線，入口 `https://admin.v50to.you/control-ui/#/servers`。Claude 完成設計及介面實作；217 項前端測試、79 項相關 Java 測試、14 項 Python 測試、真實三站 31 個瀏覽器檢查點與隔離 Compose 首次安裝／資料庫恢復的 12 項檢查通過。線上 108 項檢查及 HTTPS 30 個靜態檔案核對通過。首次安裝執行器已啟用，集群發布執行器仍關閉；沒有自動建立業務站或修改既有帳密。新站先是獨立 CAMPUS 業務站，加入多站集群需另行配置。見 [操作與契約](../../docs/architecture/control-servers-api.md) 及 [驗證紀錄](../../docs/architecture/evidence/control-servers-verification.json)。

> 前次狀態（2026-10-17）：Claude 先設計、經 Codex 審閱後完成側欄分頁與帳號設定；新版已部署至 `https://admin.v50to.you/control-ui/`。190 項前端測試、138 項相關後端測試、本機真實三站 30 個瀏覽器流程與 54 項線上檢查通過，HTTPS 28 個靜態檔案雜湊一致。原帳密未修改，業務發布執行器保持關閉。下方較早的「未部署」敘述是歷史驗收狀態。完整證據見 [帳號與介面上線驗證](../../docs/architecture/evidence/control-account-redesign-verification.json)。

> 2026-10-16 更新：已增加默认关闭的 SSH + Docker Compose 部署执行、持久任务和远端结果核对。当前使用说明见[中控部署](../../docs/architecture/control-deployment.md)；下文原有“固定锁定”描述属于执行器加入前的验收基线，正式环境尚未部署。

> 2026-10-17：独立管理服务及本静态页面已在管理主机上线，重启后 32 项实际 HTTP 检查通过。业务节点尚未部署或加入，发布执行器仍关闭；浏览器经最终 Nginx 域名的访问尚未验收。见[管理端上线验证](../../docs/architecture/evidence/control-management-bootstrap-verification.json)。

多站叢集與明確雙站相容模式（`isolated-acceptance`）的管理員中控介面。純靜態 HTML / CSS / ES modules，**無建置步驟、無外部 CDN／字型／分析**；簡繁轉換使用隨頁部署的 OpenCC 字典與模組。後端契約見 [API_CONTRACT.md](API_CONTRACT.md)。

> 狀態：桌面版 Claude Code／Opus 5.5 已完成前端與現代化整理。2026-10-16 由 Codex 實際執行 **82 項 Node 測試及 19 個真實 Chromium 流程檢查點，全部通過**；390px 窄版全部技術明細展開後仍無整頁橫向溢出。未部署，部署執行保持鎖定。詳見 [驗收紀錄](../../docs/architecture/control-frontend-verification.md)。
>
> **多站叢集版本已驗收**：144 項 Node 測試及 30 個本機三站 Chromium 流程檢查點通過，1440px／390px（含展開明細）無整頁溢出。業務前端未修改，未部署。詳見 [本輪驗收紀錄](../../docs/architecture/cluster-control-frontend-verification.md)。

> 2026-10-17：增加管理員手動切主；等待當前部署及控制操作完成後，指定在線備機接管。單實例顯示沒有備機。見[主備接管](../../docs/architecture/cluster-control-ha.md)。

> 2026-10-17：登入頁移除角色、API 路徑及權杖儲存說明。登入後頂列可切換簡體／繁體，預設簡體；僅 `eatwhat.control.locale` 語言偏好寫入瀏覽器儲存，登入權杖仍只在記憶體。切換不重新載入頁面，保留表單和確認對話框；稽核操作者、原始資料及輸入值不轉換。轉換模組及授權位於 `vendor/opencc/`（opencc-js 1.4.2），不載入外部資源。

> 2026-10-17：介面改版——側欄導覽＋頁面路由（`#/overview`、`#/deploy`、`#/sites`、`#/control`、`#/advanced/gc|fault|tasks`、`#/audit`、`#/account`），首頁改為「待處理問題、主中控、目前部署、站點狀態」，新增帳號設定頁（修改自己的使用者名稱／密碼）。既有表單 ID、API、輪詢與確認後重新檢查不變；雙站相容模式在同一外框內保留。

## 檔案

| 檔案 | 用途 |
|---|---|
| `index.html` | 頁面結構、CSP、確認對話框 |
| `styles.css` | 深色中性色主題、響應式版面、收合區與總覽磁貼 |
| `app.js` | 進入點：登入／登出、依 `CONTROL_MODE` 啟動多站或雙站畫面、雙站輪詢與操作 |
| `cluster-app.js` | 多站控制器：兩條獨立輪詢、確認後重新檢查、冪等寫入、登出中止 |
| `cluster-state.js` | 多站純邏輯：單調時間新鮮度、成員檢視、動作條件、votes、GC、故障、預覽、冪等 intent |
| `cluster-render.js` | 多站畫面渲染（成員卡、進度條、逐站確認、GC、預覽、雙稽核） |
| `api.js` | 同源 API 用戶端（雙站與 `/cluster`）與登入；錯誤分類（`ApiError`），支援外部中止 |
| `session.js` | 權杖只存於閉包記憶體 |
| `state.js` | 純資料模型：新鮮度、站點降級、GC 對照、前置檢查、任務、部署鎖定 |
| `render.js` | 以安全 DOM 渲染各區段 |
| `dom.js` | `h()`：字串一律成為文字節點，禁止 inline 事件與 style |
| `format.js` | 繁體中文標籤、時間與錯誤訊息 |
| `validate.js` | 送出前檢查（站點、通道、秒數、UUID、套件、releaseId、稽核筆數） |
| `constants.js` | 固定路徑與限制 |
| `router.js` | `#/route` 分頁路由：只認 `#/` 開頭，以 `[data-page]` 控制可見性，進階運維子選單只在該區展開 |
| `home-state.js`／`home-render.js` | 多站總覽模型與畫面：只用真實讀取結果，沒有資料時呈現空狀態 |
| `account-state.js` | 帳號修改純邏輯：名稱／密碼規則（code point 計數、UTF-8 ≤72 bytes、不 trim）、只送變更欄位、錯誤分類、成功回應判定 |
| `servers-state.js` | SSH 伺服器與首次安裝／更新純邏輯：連線資料與私鑰（無保護密碼）驗證、主機指紋選取、只送契約欄位、安裝輸入（18090、禁止 18080／18081、對外網址）、正規化、環境檢測判讀、錯誤與阻擋條件文字、結果分類 |
| `servers-ui.js` | 伺服器頁（`#/servers`）：登記、指紋確認、連線檢測、安裝／更新計畫與確認、任務查詢與核對；憑據送出／離開／登出即清空；結果未知不重送；登出後舊回應丟棄 |
| `account-ui.js` | 帳號設定頁：確認框不顯示密碼、確認後重讀 `canEdit`、成功／結果未知清除密碼與權杖並回登入頁、不自動重送、登出後舊回應丟棄 |
| `tests/*.test.js` | `node:test` 測試（無依賴） |

## 提供方式（同源反向代理）

前端假設與後端**同源**：登入走 `/auth/login`，中控 API 走 `/admin/control/v1`，兩者皆為相對路徑，介面沒有任何可輸入 API 位址的欄位。請以同一個 origin 同時提供靜態檔與代理後端。

> 已驗證的組態：本次驗收使用 `http://localhost:5173` 作為同源代理，該來源在後端既有的 CORS 白名單內，且保留 Origin 與 CORS 檢查。下面的 nginx 範例只是**結構示意**：`control.example.internal` 是虛構名稱，換成任何新網域都**不保證可直接運作**。部署前必須核對：
>
> 1. 該來源（scheme + host + port）已在後端既有的允許來源白名單中；若不在，需由後端維護者依既有流程評估加入，**不可改成萬用來源，也不可移除 Origin／CORS 驗證**。
> 2. 反向代理是後端信任的代理，且正確還原原始 `Host` 與 scheme（例如 `X-Forwarded-Proto`），讓後端看到的請求來源與瀏覽器實際 Origin 一致；後端也需設定只信任該代理的轉送標頭。
> 3. 代理不得改寫或移除瀏覽器送出的 `Origin` 標頭。

例如 nginx（示意）：

```nginx
server {
    listen 443 ssl;
    server_name control.example.internal;

    # 靜態前端（本目錄）
    location /control-ui/ {
        alias /srv/eatwhat/frontend/control/;
        types { text/html html; text/css css; text/javascript js; }
        add_header Cache-Control "no-store";
        add_header X-Content-Type-Options "nosniff";
        add_header Referrer-Policy "no-referrer";
        add_header X-Frame-Options "DENY";
        # 若要由 header 送出 CSP，可與 index.html 的 meta 一致並加上 frame-ancestors 'none'
    }
    location ~ ^/control-ui/(tests/|package\.json|.*\.md$) { return 404; }

    # 後端（登入與中控 API）；保留 Host、scheme 與 Origin，供後端做既有來源檢查
    location /auth/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
    location /admin/control/v1/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

（nginx 預設會轉送瀏覽器的 `Origin`，請勿以 `proxy_set_header Origin` 覆寫。上述標頭是否被後端採信，取決於後端的可信代理設定，需另行核對。）

注意：

- `.js` 必須以 JavaScript MIME 提供，否則瀏覽器拒絕載入 module。
- 若後端掛在 `/api` 等前綴下，請在代理層改寫，不要修改前端成可設定的 URL。
- 不需要也不應該為前端加 CORS 萬用來源，也不要為了讓新網域運作而關閉來源驗證。
- 本機開發可用任何靜態伺服器（例如 `python3 -m http.server`）搭配同源代理；直接以 `file://` 開啟無法運作。

## 多站叢集模式（`CONTROL_MODE = 'cluster'`，預設）

模式由 `constants.js` 的 `CONTROL_MODE` 在部署時固定：`cluster`（本版預設）只使用 `/admin/control/v1/cluster`；`legacy` 保留下方的既有雙站畫面。沒有執行期切換。頂列副標與登入說明在載入時依 `CONTROL_MODE` 顯示（cluster：「多站叢集」；legacy：「雙站驗收」），HTML 靜態文字保持中性。cluster 模式登入時以 `/cluster/capabilities` 驗證入口（判定邏輯為 `cluster-state.js` 的 `isEntryRejection`）：401／403 拒絕；**404 留在登入畫面並顯示「此入口不是多站中控入口，或多站功能未開啟」，不自動降級、也不改呼叫舊 `/capabilities`**（連到非中控副站也可能 404）；503、逾時、網路錯誤仍進入畫面並標示未知。

**讀取與新鮮度**

- 簡短接口（status、changes、gc、gc/candidates、cluster audit、舊檔案 audit、舊 test-runs；首次與手動重新讀取另含 capabilities、profiles、channels、releases、test-suites）：每輪完成後隔 5 秒再排下一輪，8 秒逾時。
- `/observations`：**獨立的單一長請求**，逾時 `OBSERVATION_TIMEOUT_MS`（360 秒，可調）；進行中不發第二個；完成後隔 5 秒再排。登出時以 `AbortController` 中止。
- 新鮮度以 `performance.now()` 自**成功請求的開始時間**起算，輪詢進行中不延長舊資料。status 等 15 秒過期；觀測 30 秒過期。`node.control.observedAt` 原樣顯示，不用本機時鐘相減判定離線。
- 失敗或過期：資料標為失效、頂部警示、依賴最新狀態的寫入立即停用；503／逾時保留 Session，只有 401／403 登出。

**畫面**

- 總覽：epoch、ACTIVE 與參與數（上限來自 capabilities）、逐站觀測新鮮度與 UNKNOWN／未觀測數、進行中變更、未完成 GC、worker（`OK` 註明「不代表各站健康」，`running` 只表示已啟動）。
- 成員：完整名單取 `status.members`（依 slot）；`/observations` 只含 ACTIVE／DRAINING，以 siteId＋instanceId 精確對應，缺少即「未觀測」。UNKNOWN 時不採用任何計數（不顯示零積壓）。卡片顯示待投遞／Inbox／檔案傳輸、`dataPlaneReady`（註明為軟體能力）、readiness／storage（未檢查）、排空四項數量（缺值為未知）、提醒；instanceId、指紋、epoch、節點成員狀態等收進 `<details>`。RETIRED 另列為保留墓碑。
- 加入／排空／退役：表單依狀態只允許合法動作（REGISTERED：加入／取消；ACTIVE／DRAINING 的 EXTERNAL：退役；CAMPUS 權威站不可退役）；有未 APPLIED 變更時停用新變更；已保留的 siteId／UUID／slot 的 profile 不可登記。進度條顯示 PREPARING → COMMITTED → INSTALLED → APPLIED，逐站 PREPARED／APPLIED／RELEASED 確認時間依 instance_id 對應；列表保留後端順序，「最後確認時間」取最大 vote 時間。可續行未完成變更。
- 故障：BLOCK 僅 ACTIVE，需 30 秒內開始的成功觀測、REACHABLE、control OBSERVED、通道上限；RESTORE 可對任何保留身份且離線目錄 siteId＋instanceId 匹配的站（含 RETIRED），註明只恢復固定代理、不恢復成員資格，目錄 `routingVerified=false` 不畫成在線。
- 多方 GC：候選只有 ELIGIBLE 可發起（DETECTED／BLOCKED 停用並說明）；operationId 由前端產生；決策進度 PREPARING → COMMITTED → DELETED 與逐站 PREPARED／DELETED；註明 PREPARED 可能是 ABSENT 負面柵欄，不代表曾有副本。可續行。沒有 key／bucket／豁免。
- 部署預覽：制品取 `/releases`（格式不符者排除、不改寫），站點取 ACTIVE 成員（最多 16）；預覽綁定 epoch 與精確成員，epoch 改變即標示過期。執行器預設關閉；服務端與計畫明確啟用、零阻擋且領導／成員仍符合時，可確認執行逐站部署。
- 驗收任務：多站畫面保留既有固定 allowlist 測試控制，重用舊 `/test-suites`、`/test-runs` API 與 `state.js` 模型（`startBlockers`、`mergeRuns`、`runNotes`）。列出套件與不可啟動的原因；確認後啟動（確認後重新檢查：仍無其他任務、清單仍即時、套件仍啟用）；顯示目前任務、任務紀錄與依 UUID 查詢歷史（查詢到的執行中任務每輪重讀）；確認後取消（重新檢查仍是同一個目前任務）。不提供命令、參數、路徑或 URL 輸入，不自動啟動；runner 只執行已配置套件，不代表多站故障矩陣；`COMMAND_SUCCEEDED` 不是遠端驗收通過。
- 稽核：成員／GC 資料庫稽核（`/cluster/audit`）與故障／預覽／任務檔案稽核（舊 `/audit`）分兩欄並列。
- 中控不呼叫 `/auth/consent/*`；個人資料接收授權只由帳戶本人在權威站操作。

**控制領導（權威站主備租約）**

- 契約依 `docs/architecture/cluster-control-ha.md`：主備接管只發生在同一邏輯 CAMPUS 權威站的程序之間；`coordinatorTransferEnabled=false`，不支援不同站點間轉移權威身分，畫面也不提供此操作。
- 每輪簡短輪詢讀取 `GET /cluster/leadership`；它不可用時才採用同樣新鮮的 `status.leadership`。總覽第一格與「控制領導」區塊（`#cl-leadership`）顯示狀態、此程序是否持有租約、generation、到期時間（資料庫時鐘，原樣顯示，不與本機時鐘相減）與資料來源。
- 只有 `HELD`＋`localLeader=true`（或 `SINGLE_PROCESS`＋`localLeader=true`）開放控制寫入。備機（`HELD`＋非 leader）、`EXPIRED`、`UNCLAIMED`、未知狀態、讀取失敗或過期時，成員（加入／退役／取消）、登記、inventory 重讀、續行變更、故障注入（BLOCK／RESTORE）、GC 發起／續行與部署預覽一律停用，並在各表單列出原因；讀取與登入態保留。固定驗收任務沿用程序端 `/test-runs`，不受租約閘門影響。
- 開啟確認對話框時記下 generation；**確認後、送出前**重新核對領導權（含結果未知的重送）與成員 epoch，失租、轉為備機或 generation 改變就不送出並要求重新確認。
- 寫入回 `503 CONTROL_LEASE_NOT_HELD`：保留登入態與原 requestId，不登出；在新的領導讀取完成前，不採信該錯誤之前開始的領導讀取。

**寫入安全**

- 每個寫入都要求確認對話框；**確認後、送出前以當下狀態重新檢查**（status 是否新鮮、epoch 是否與確認時相同、目標身份／狀態是否不變；BLOCK 另要求觀測請求開始仍在 30 秒內且上限不變）。條件改變就不送出、重新讀取並要求重新確認，絕不把已確認的 epoch 換成新值。RESTORE 的重新檢查不依賴線上觀測。
- 帶 requestId／operationId 的寫入：結果未知（網路、逾時、中止、503、`NODE_REQUEST_FAILED`）時保留**原 UUID 與原 body**，再次送出同一操作完全重送；參數改變即為新操作、新 UUID。成功、明確拒絕或 `STALE_MEMBERSHIP_EPOCH` 後釋放。
- 後端可操作錯誤（`INITIAL_SYNC_PENDING`、`RETIREMENT_*_PENDING`、`LOCAL_ACCOUNT_ARCHIVE_PENDING`、`GC_DECISIONS_PENDING`、身份保留等）只說明下一步，明示不可由中控豁免。

## 範圍與功能（雙站相容畫面，`CONTROL_MODE = 'legacy'`）

- **兩站即時觀測**：每 5 秒讀取 `/sites`、`/test-runs`、`/audit`。顯示 pairStatus、各站 observationStatus／reason、節點觀測時間差（伺服器時間）、readiness／storageReachability（`NOT_CHECKED` 以琥珀色「未檢查」顯示）、資料庫身份核對、代理通道。
- **同步佇列摘要**：database 內任何「值皆為數字」的物件欄位都當成計數分組（delivery、inbox、transfers、replicas、publication、cdn、gc 或新出現的鍵），不假設固定數量；另列 activeReferences、unpublishedReceipts、multipartJobs 等數值。
- **GC 協調操作對照**：以 `operation_id` 並列兩站 `recentGc` 的 role、state、peer_prepared、peer_deleted、updated_at。只顯示 API 原值，**不推導物件已實體刪除**；未出現在某站清單只表示不在最近 20 筆。
- **通道 BLOCK／RESTORE**：站點固定為 campus／external。確認對話框列出目標站點、通道、持續時間（BLOCK 另列節點上限）與影響，按下明確的確認按鈕才送出。
  - BLOCK 通道只能從**最新有效觀測**的 `sites[site].channels` 選擇；秒數須為 1–300 整數，且不得超過該通道的 `maximumBlockSeconds`。表單顯示上限並設定輸入 `max`；上限缺值或非 1–300 整數時一律阻擋 BLOCK 並顯示「缺值」，不猜測、不以 300 代替、不使用設定清單的上限。
  - BLOCK 前置檢查：資料新鮮、pairStatus 為 `VERIFIED_DISTINCT`、目標站 OBSERVED、節點觀測差 ≤ 30 秒、通道存在且有上限。不通過即停用送出並列出原因（伺服器仍為最終判定）。
  - RESTORE 的通道選項合併三個來源並分別標示：目前觀測（無註記）、本頁最後已知觀測「（最後已知）」、`capabilities.configuredChannels`「（設定清單，未觀測）」。因此首次登入即遇節點 UNKNOWN 時，仍可從設定清單選擇 RESTORE；設定清單為空或未提供時不猜測名稱。設定清單不會被顯示為節點在線或代理已驗證，也不會滿足 BLOCK 的觀測條件。
  - RESTORE 不以秒數控制恢復時間，秒數欄位停用，依契約固定送出 `seconds=1`。
  - 中控能力區塊列出 configuredChannels 內容，並註明只是操作員設定的離線恢復清單。
  - 網路錯誤、逾時、`NODE_REQUEST_FAILED` 一律顯示「結果未知（OUTCOME_UNKNOWN）」並重新讀取，不假定未執行。
- **固定驗收任務**：只能執行 `/test-suites` 回傳且屬於 business／coordinated-gc／full-verify 的已啟用套件；一次一個；需確認。可取消（說明取消不撤銷已完成操作）。可依 UUID 查詢持久歷史；查詢到的執行中任務會在輪詢時重新讀取。
  - `COMMAND_SUCCEEDED` 顯示為「指令退出碼 0（非遠端驗收）」；`INTERRUPTED` 為終止狀態，不會被當成執行中；`remoteAcceptance`、`reportStatus`、`deploymentPerformed` 原值與說明一併顯示。
- **版本與部署計畫預覽**：只能選擇 `/releases` 回傳的制品；建立 `PREVIEW_ONLY` 計畫，顯示 SHA-256 對照、blockers、steps（標明未執行）與建立當下的觀測快照。部署按鈕初始 disabled，能力與計畫必須明確回報 true、零阻擋才開放；確認後重新讀取計畫／能力並核對當前狀態，再以計畫 UUID 執行。逐站任務結果、UUID 查詢與未知結果的遠端 journal 核對共用 `deployment-ui.js`。
- **操作稽核**：`/audit?limit=N`（20／50／100／200），以新到舊顯示；`details.seconds`（整數）顯示於「請求時限」欄，其餘 details 鍵值以文字原樣列出。
- **任務清單**：`/test-runs` 含目前任務與最多 1000 筆本機持久歷史；更早的可依 UUID 查詢。

## 安全設計

- 權杖只存在 `session.js` 的閉包變數，不寫入任何瀏覽器儲存、cookie、URL 或日誌；重新整理頁面即需重新登入。`toJSON()` 只回傳是否登入。
- 所有請求 `credentials: 'omit'`、`cache: 'no-store'`、`redirect: 'error'`、`referrerPolicy: 'no-referrer'`，並有 8 秒逾時。
- 登入：`POST /auth/login` `{username, password, device: 'WEB'}`；業務成功碼為 **0**；header 名稱取回傳的 `data.tokenName`，值為 `Bearer ` + `tokenValue`（若已含前綴不重複）。登入後以 `/capabilities` 驗證 admin 角色。
- 中控 API 成功回應直接當成 JSON 使用，不解包 `code/data/msg`。
- 401／403 時立即登出並清除權杖與所有密碼欄位；手動登出亦同。
- 所有遠端字串以 `textContent`／文字節點呈現；`dom.js` 拒絕 `on*`、`style`、`href`、`src` 等屬性。
- `index.html` 含 CSP：`default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'none'` 等。
- 輪詢失敗或超過 15 秒未成功即標示「資料已失效」，站點狀態降級為「資料失效」，配對狀態顯示為未知，不再呈現為在線。
- 所有寫入（BLOCK／RESTORE、啟動／取消任務、建立預覽計畫）只在使用者點擊後執行；載入與輪詢只做 GET。

## 測試

```bash
npm test
```

或直接：

```bash
node --test tests/api.test.js tests/state.test.js tests/validate-session-format.test.js tests/static-safety.test.js tests/layout.test.js tests/cluster-api.test.js tests/cluster-state.test.js tests/cluster-static.test.js tests/cluster-leadership.test.js
```

多站新增：`cluster-api.test.js`（路徑、請求內容白名單、輸入驗證、observation 獨立逾時、外部中止、錯誤碼、capabilities 404 保持 http 錯誤且只發一次請求、入口判定只拒絕 401／403／404）、`cluster-state.test.js`（單調新鮮度、成員檢視與 UNKNOWN、動作條件、votes 對應、GC 候選、故障前置檢查、確認後重新檢查、預覽過期、冪等 intent、錯誤說明）、`cluster-static.test.js`（模式常數、穩定 id、部署鎖定、無 consent／強制選項、寫入只在事件處理器、確認→重新檢查→取得 UUID→送出的順序、單一觀測請求與登出中止、多站畫面保留驗收任務且只有任務 ID 一個輸入欄位）。

需要 Node 18.17 以上，無需安裝套件。測試涵蓋 API 用戶端（路徑、header、登入成功碼、錯誤分類、輸入拒絕、部署只接受計畫 UUID）、狀態模型（失效降級、計數分組、GC 對照、BLOCK 上限與缺值、RESTORE 設定清單、前置檢查、任務狀態、部署鎖定、稽核 details）以及靜態安全掃描（無 HTML 注入、無持久儲存、無外部 URL、CSP、部署按鈕停用、寫入 API 只在事件處理器中呼叫），以及版面靜態檢查（grid 欄寬、fieldset、select、表格容器、無 overflow hidden）。DOM 渲染（`render.js`、`app.js`）沒有單元測試，需在瀏覽器驗證。

## 已由後端補齊／澄清的項目

- **離線恢復通道清單**：`capabilities.configuredChannels` 已提供，前端用於 RESTORE（見上方）。
- **BLOCK 上限**：`sites[site].channels[channel].maximumBlockSeconds` 已提供，前端據此阻擋超限與缺值。
- **RESTORE 的 seconds**：後端不使用此值控制恢復時間；前端固定送 1。
- **登入**：同源 `/auth/login`、`code=0`、header Bearer 模式已在真實 Spring HTTP 驗收通過（後端回報），不需要 cookie。
- **Plan 的 blockers／steps**：本版皆為 `string[]`；前端仍保留對物件或未知值的安全文字顯示。
- **稽核 details.seconds**：已顯示為「請求時限」。

## 仍未定義的值域（前端原樣顯示，不據此推論）

1. **錯誤碼清單**：只知道 `ADMIN_REQUIRED`、`DEPLOYMENT_LOCKED`、`NODE_REQUEST_FAILED`；其他 code 原樣顯示。若能列出 409／400 的具體 code（例如已有任務執行中、觀測不新鮮、秒數超限），可提供更精確的訊息。
2. **capabilities.testSuites 結構**：僅作列表顯示；可執行與否以 `/test-suites` 為準。
3. **Run 的 reason** 與 `remoteAcceptance` 除 `NOT_RUN` 外的值。
4. **稽核 `outcome` 值域**與 details 中 seconds 以外的鍵：只以字面決定顏色、以文字列出。
5. **configuredChannels 的 state／source 值域**：只原樣顯示（例如 `NOT_OBSERVED`、`HUB_CONFIG`），不據此判斷節點狀態。

## 驗證狀態

### 在新伺服器安裝中控與業務＋撤銷未派發（2026-10-19，Claude 實作，僅模擬 API 驗證）

- 入口：伺服器頁「在新伺服器安裝中控與業務…」（`bootstrap-ui.js`、`bootstrap-state.js`）；原有單獨業務安裝保留。能力只看 `GET /controller-plans`，404 說明未啟用，不改走其他安裝；`canPlan=false` 顯示原因碼。
- 分步表單：現有來源中控（任何已登記的伺服器，含沒有安裝器記錄、清單為 NOT_INSTALLED 的原已運行中控；已知 ACTIVE／校內排前，身份由後端以實際服務與權威資料庫核對）／空目標（仍需未安裝、不得與來源同一台）、業務版本、分配槽位、私網地址、WireGuard 入口與端口、業務端口、含 `/api` 的業務網址；儲存沿用來源或自訂通用 S3／RustFS／R2（R2 區域固定 auto）。不要求 epoch、私有配置／制品路徑或雜湊，也不預填地址。
- 密鑰只在建立預覽那一次請求中送出，送出前清除輸入；不進簽章、送出摘要、瀏覽器儲存、網址、記錄或畫面。每次建立預覽使用新 UUID；結果未知保留 UUID，只能「查詢原預覽」；查無才可重新建立（新 UUID）。
- 預覽以回應內容顯示，並與送出的非秘密輸入逐項比對（含自訂儲存的提供者、端點、區域、桶、path style、公開網址、CDN）；預覽後修改任何部署選項或重新輸入密鑰都會作廢預覽。權限逐項確認、不預先勾選；只以原 `requestHash` 與完整權限批准，確認後重讀原預覽與伺服器 revision。202 只代表已排隊，可關頁並連到任務歷史只讀詳情；批准結果未知只查任務，不重送。
- 任務詳情新增 `executionCode`／`executionObservedAt`（固定代碼與時間，不解讀成原生成功／失敗）。撤銷未派發：只在 `canCancelBeforeDispatch === true` 且有原 hash 時顯示，確認後重讀原任務再送 `{requestHash}`；不因 canManage 或交接等待單獨停用。`BOOTSTRAP_DISPATCH_ALREADY_CLAIMED` 或結果未知只重新讀取原任務；只有 FAILED＋`BOOTSTRAP_CANCELLED_BEFORE_DISPATCH`＋`cancelledBeforeDispatch=true`＋`effectsStarted=false` 才顯示「已撤銷（未派發）」。沒有重新批准、續期或已派發恢復按鈕。
- `npm test`：353 項通過（新增 `tests/bootstrap.test.js` 14 項、`tests/bootstrap-cancel.test.js` 4 項）。`scripts/verify-control-bootstrap-browser.mjs` 15 個本機模擬流程通過（含原始清單 installation=NOT_INSTALLED 的現有來源建立正確預覽、空機來源由模擬預檢拒絕）；停用「重新輸入密鑰作廢」、省略儲存選項比對，或把來源限回 ACTIVE 時此腳本失敗。既有 15 組瀏覽器回歸通過。未對真實後端或任何主機驗證。

### 中控與業務安裝任務：目標物件儲存（2026-10-19，Claude 實作，僅模擬 API 驗證）

- `tasks-state.js` `normalizeControllerStorage`／`safeStorageUrl`：只取公開鍵（mode、provider、endpoint、region、bucket、pathStyle、public-base-url、cdn-endpoint、credentialsConfigured），其他鍵一律不讀；網址只接受 http/https 且無帳密、query、fragment，否則不顯示並列出問題；未知提供者／mode 保留「未知」並只回顯安全代碼；布林缺值為未知，不補成已封存。
- 任務詳情新增「目標物件儲存」區塊：提供者、設定來源、S3 API 地址、桶、區域、憑據（「已封存（不代表可連線或健康）」）；公開網址、CDN、path style 收合。舊回應沒有 storage 時說明狀態未知。storage 不影響既有完成判定。
- `npm test`：335 項通過（新增 `tests/controller-storage.test.js` 8 項）。`scripts/verify-control-controller-jobs-browser.mjs` 增至 14 個本機模擬流程並通過（RustFS 沿用來源、R2 自訂、舊回應、異常秘密欄位與網址不回顯、簡體 390px）；停用網址過濾時此腳本失敗。

### 中控與業務安裝任務只讀詳情（CONTROLLER_INSTALL，2026-10-18，Claude 實作，僅模擬 API 驗證）

- 任務歷史的類型篩選與「依 UUID 開啟」加入「中控與業務安裝」。詳情只由類型＋UUID 固定組合 `GET /admin/control/v1/controller-jobs/{UUID}`（`api.js` `getControllerJob`），不跟隨列表的 `detailRoute`；在任務歷史頁的只讀面板顯示，不離開頁面。
- `tasks-state.js` `normalizeControllerJob` 只保留公開欄位，回應 UUID／kind 不符即格式無效；`controllerJobDisplay`：只有 SUCCEEDED＋兩個旗標明確 false＋13 階段與依賴子操作全部完成才是「已完成（歷史記錄）」，並說明不代表目前健康；結果未知／需核對／需要重新批准／缺欄位都不顯示為完成。
- 面板只有「重新讀取」「關閉」，沒有執行、重試派發、重新批准或核對；切換 UUID、離頁、登出時丟棄遲到回應；401／403 沿用既有登出；404 顯示說明與原碼。
- 完成判定另要求 DEPENDENCY_LINK 恰有 KEY／SOURCE／CONNECT 三個子操作（順序固定、角色 TARGET／SOURCE／TARGET、nativeId＝父階段 UUID、全部 SUCCEEDED）；缺失、空陣列、重複、順序／角色／nativeId 不符都顯示「回報完成 · 未能確認」。詳情錨點改用固定白名單 route→href（`TASK_ROUTE_HREF`），CONTROLLER_INSTALL 指向 `#/tasks`，中鍵／新分頁不再導到伺服器頁。
- `npm test`：327 項通過（`tests/controller-jobs.test.js` 共 11 項）。`scripts/verify-control-controller-jobs-browser.mjs` 11 個本機模擬流程通過；停用世代檢查、子操作檢查或白名單 href 時此腳本各自失敗。既有任務歷史、伺服器、部署等回歸通過。契約說明見 `docs/architecture/control-task-history-api.md`。
- 這只是只讀查詢；完整的建立／接受／執行新伺服器部署流程尚未在前端提供，也未對真實後端驗證。

### 成員設定失敗後恢復原設定（2026-10-18，Claude 實作，僅模擬 API 驗證）

- 入口：接入任務面板（含從「任務歷史」打開的任務）只對 `REMOTE_MEMBER`＋`FAILED`＋`MEMBER_CONFIGURATION_FAILED_PRESERVED` 顯示「預覽恢復原設定…」；結果未知、執行中、已 CONFIGURED、成功或其他失敗碼都沒有入口。能力沿用 `clusterEnrollmentEnabled` 與既有寫入許可（可管理、主中控租約）。
- 預覽：建立前重讀來源任務仍符合條件，才 `POST /server-cluster-jobs/{failedId}/recovery-plans`（無本文，`api.js` `createMemberRecoveryPlan`）。`REMOTE_MEMBER_CONFIGURATION_RECOVERY` 計畫顯示來源失敗 UUID、目標伺服器與 revision、原身份／分配／集群、原版本 SHA、保留原資料庫與舊失敗任務、恢復後仍 NOT_JOINED 需新接入計畫、中控不重啟、有效期限。READY、可解析且未過期的期限、`originalDatabasePreserved`、`controllerRestartExpected=false`、`newEnrollmentPlanRequired=true`、完整身份與 SHA、`failedId`／伺服器與入口一致，缺一即不能執行。
- 執行：沿用接入流程，確認後重讀計畫、伺服器 revision 與租約／可管理狀態再送出；掉回覆只以原 UUID 唯讀查詢。恢復計畫一旦送出，即使之後查詢回 404 也不重新開放送出、不建議重送，只提示以原 UUID 再查詢；結果未知時按鈕與確認框為「核對恢復記錄」（只讀回執，不恢復、不加入）。
- 結果：只有 `SUCCEEDED`＋`clusterMembership=NOT_JOINED`＋`newEnrollmentPlanRequired=true` 顯示「已恢復原設定；仍未加入集群」；恢復任務在任何情況都不視為已加入。新增錯誤碼說明：`FAILED_MEMBER_CONFIGURATION_REQUIRED`、`MEMBER_RECOVERY_PREFLIGHT_FAILED`、`MEMBER_RECOVERY_FAILED_PRESERVED`、`MEMBER_RECOVERY_RECONCILIATION_REQUIRED`；未知碼仍顯示原碼。
- 附帶修正：接入任務的遲到輪詢回應在使用者改看其他任務（例如從任務歷史打開）後丟棄，不再覆寫畫面上的任務。
- `npm test`：316 項通過（新增 `tests/member-recovery.test.js` 9 項）。新增 `scripts/verify-control-member-recovery-browser.mjs` 7 個本機模擬流程通過：結果未知／CONFIGURED 無入口；任務歷史打開失敗任務並預覽；確認後失租約不送出；期限非法／缺失不能執行；掉回覆只讀原 UUID 且成功後仍待接入；掉回覆後 GET 404 不建議重送、不重開送出、之後以原 UUID 查回並核對恢復記錄；簡體 390px。移除「404 後不重開送出」守衛時此腳本失敗。既有伺服器、接入、任務歷史、語系等回歸通過。預覽：`output/control-member-recovery-verification/`。
- 未驗證：真實 SSH 主機上的恢復、恢復後重新建立接入計畫的實際接入。

### 遠端接入任務的重啟說明（2026-10-17，Claude 實作，僅模擬 API 驗證）

- 問題：REMOTE_MEMBER 任務被接受後仍固定顯示「中控即將短暫重啟」。
- 修正：`normalizeClusterJob` 的 `controllerRestartExpected` 改為三態（true／false／缺失或非布林為 null）；`servers-state.js` 新增 `clusterAcceptedText`、`clusterUnknownSubmitText`，`servers-ui.js` 改用。true 才說中控預期重啟；false 說後端已接受、持續以原任務 UUID 唯讀查詢、中控不重啟；缺失時說明回應未說明是否重啟，不承諾行為。送出結果未知時仍不重送，只以原計畫 UUID 讀取；計畫明確 `controllerRestartExpected=false` 時不再提「可能正在重新啟動」。任務進行中的提示改為嚴格 `=== true`。阻擋、確認、重查與輪詢流程未改。
- `npm test`：307 項通過（新增 `tests/cluster-restart-text.test.js` 6 項）。新增 `scripts/verify-control-cluster-restart-text-browser.mjs` 5 個本機模擬流程通過（false、true、缺失、回應中斷只讀原 UUID 不重送、簡體 390px）；換回舊固定文案時此腳本失敗。遠端接入、首次接入等既有回歸通過。預覽：`output/control-cluster-restart-text-verification/`。

### 部署執行能力文案（建立預覽確認框與能力區塊，2026-10-17，Claude 實作，僅模擬 API 驗證）

- 問題：建立預覽確認框固定寫「執行介面固定回 423」，能力區塊寫「前端永久鎖定」／「回報異常，前端仍鎖定」，預覽的 `executionEnabled=true` 以紅色異常顯示；但服務端 `deploymentExecutionEnabled=true` 時預覽確實可在下一個確認框執行。
- 修正（`deployment-state.js` 共用函式）：
  - `previewCreateExecutionLine`：確認框一律說明「這一步只建立 PREVIEW_ONLY 預覽，不會部署。」；正式能力嚴格 `true` 才說可在下一個確認框另行確認執行，`false` 說不能執行，缺欄位／非布林／讀取失敗為未知。
  - `previewResultExecutionLine`：建立成功訊息沿用 `executionReasons`（能力＋預覽 `executionEnabled`、阻擋項等），全部滿足才說「可執行；尚未部署」，否則列出原因。
  - `executionCapabilityView`：叢集（`cluster-render.js`）與舊版雙站（`render.js`）能力摘要徽章與明細改為 true「已啟用；每次部署仍需預覽無阻擋並在確認框另行確認」、false「未啟用，預覽不能執行部署」、其他「未知或異常，不視為可執行」。
  - `planExecutionStat`：兩處預覽的 `executionEnabled` 統計 true 不再標紅，false 中性，其他琥珀色未知。
  - 實際執行閘門、阻擋項與確認／重查流程未改。節點觀測的 `deploymentExecutionEnabled` 由後端固定回 `false`（節點本身不執行部署），維持原顯示。
- `npm test`：301 項通過（`tests/preview-execution.test.js` 共 8 項）。`scripts/verify-control-preview-execution-browser.mjs` 7 個本機模擬流程通過（enabled／阻擋／disabled／缺欄位與非布林、叢集能力區塊、舊版能力區塊三態、簡體 390px），全程沒有呼叫執行介面；既有 14 組瀏覽器回歸通過。預覽：`output/control-preview-execution-verification/`。

### 交接提交回應與正式領導狀態合併（2026-10-17，Claude 實作，僅模擬 API 驗證）

- 問題：切主卡片上方已顯示 COMPLETED／指定中控已接管，下方仍保留提交當下的「交接 <同一 UUID> · WAITING。狀態會持續更新。」。
- 修正：`handoff-ui.js` 把提交與查回的回應保存為 `{id, state}`，每次重畫時以 `handoffResultView`（`handoff-state.js`）和 `leadership.handoff` 合併。只有領導讀取為最新（FRESH）、`handoff.id` 為同一 UUID、狀態為已知且比回應更前進（WAITING → TRANSFERRING → COMPLETED／CANCELLED／FAILED）時才同步，並標示「已依最新領導狀態更新」。不同 UUID、讀取失敗或過期、未知狀態、狀態倒退時保留原回應；FAILED／CANCELLED 照實顯示，不說成完成。其他提示（錯誤、狀態已改變）不受影響，登出仍清除。
- `npm test`：293 項通過（新增 `tests/handoff-result.test.js` 5 項）。新增 `scripts/verify-control-handoff-result-browser.mjs` 4 個本機模擬流程通過（同一 UUID 完成後同步、不同 UUID 不同步、讀取失敗期間不同步且恢復後 FAILED 照實、簡體 390px）；把修正換回舊寫法時此腳本會失敗。既有 `verify-handoff-browser.mjs` 等回歸通過。預覽：`output/control-handoff-result-verification/`（1440px、390px）。
- 未在真實兩台中控上重跑交接；本輪未部署。

### 中控專用密鑰引用、接管模式與健康成功碼（2026-10-17，Claude 實作，僅模擬 API 驗證）

- 新增 SSH 伺服器可選「中控已配置的專用密鑰」：`GET /servers` 的 `sshCredentials` 只含 `id`／`label`／`authentication`。缺欄位（舊後端）時表單與請求完全不變；格式錯、重複 id、未知 authentication 時整體停用引用、不猜選，手動輸入照常。選擇引用時固定私鑰認證，只送 `credentialId`，不讀取、不送出手動欄位；切換來源會清空手動值與選擇；空清單或未選擇不能新增。編輯時保持「手動輸入」並留空即保留原憑據，即使目前中控沒有該選項。卡片與確認框顯示 `credentialSource`（手動／中控密鑰 label 與 id／未知如實），不顯示內容或路徑。`api.js` 拒絕格式錯的 `credentialId`、非私鑰認證或同時帶 `credential`。
- 控制領導卡片唯讀顯示 `takeoverMode`：`MANUAL_HANDOFF_ONLY`「僅手動交接」、`AUTOMATIC`「租約過期後可自動接管」，缺少或未知為「接管模式未聲明」；不推斷接管權限、不新增操作。
- 健康結果的 `code=OK`（後端單項 PASS 與讀寫驗證 PASSED 的成功碼）：只有有效 PASS／PASSED 顯示成功說明；過期或觀測失效的 PASS 說明為舊結果；FAIL／UNKNOWN／CHECKING 帶 OK 時標示「與狀態不一致；以狀態為準」，狀態與顏色不變。其他未知碼仍顯示「未定義的錯誤碼」並保留原碼。
- `npm test`：288 項通過（新增 `tests/credential-reference.test.js` 7 項、`tests/health-ok.test.js` 5 項）。另由使用者提供的 `scripts/test-control-credential-references.mjs` 7 項通過。
- 瀏覽器（本機模擬 API）：新增 `scripts/verify-control-health-ok-takeover-browser.mjs` 4 個流程通過（OK 成功說明、OK 不蓋過過期／FAIL／未知碼、接管模式已知／缺少／未知、簡體 390px）；使用者提供的 `verify-control-credential-reference-browser.mjs` 5 個流程與既有 11 組回歸全數通過。預覽：`output/control-health-ok-takeover-verification/`（1440px 與 390px）。
- 未驗證：真實中控讀取已配置密鑰並連線 SSH、真實兩台中控的接管模式與交接；本輪未部署、未交接。

### SSH 執行權限（直接執行／免密 sudo，2026-10-17，Claude 實作，僅模擬 API 驗證）

- 清單 `sshPrivileges` 為含 SUDO 的陣列時才可選「免密 sudo」，新增預設直接執行並明確送 `privilege: "NONE"`；舊後端（缺欄位）或格式錯只允許直接執行且不送此欄位。省略的舊記錄視為 NONE；未知值不預選、需明確選擇。SUDO 只表示以 sudo -n 執行，不傳 sudo 密碼；root 實際權限以連線檢測與後端預檢為準。
- 卡片、計畫與確認框以「ubuntu@host:22（免密 sudo）」顯示實際使用者與方式。遠端成員接入的 root 前置條件改為 root 帳號，或後端宣告支援且選擇免密 sudo。
- `npm test`：276 項通過（新增 `tests/privilege.test.js`）。瀏覽器：`scripts/verify-control-privilege-browser.mjs` 7 個流程（模擬 API），截圖在 `output/control-privilege-verification/`。未在真實主機或後端驗證。

### 校外業務站遠端接入（REMOTE_MEMBER，2026-10-17，Claude 實作，僅模擬 API 驗證）

- 契約：`docs/architecture/control-remote-enrollment.md`。沿用 cluster-plans／server-cluster-jobs／reconcile 與任務歷史 ENROLLMENT。
- 只有清單 `remoteMemberEnrollmentSupported === true` 才開放；另需接入開關、目前領導權、已安裝 EXTERNAL、明確 NOT_JOINED、分配 1–1023 與原身份、root SSH。不要求中控本機。校內權威站維持 INITIAL_AUTHORITY。
- 計畫需 READY、有效期可解析且未過期、保留原資料、身份與 baseEpoch 完整；類型須與伺服器接入方式一致。`MULTISITE_AUTHORITY_SETUP_REQUIRED` 說明需先完成多站配置，不是一鍵部署第二中控。CONFIGURED／WAITING_FOR_JOIN 不算已加入；回執遺失只以原 UUID 查詢與核對。
- `npm test`：270 項通過（新增 `tests/remote-enrollment.test.js`）。瀏覽器：`scripts/verify-control-remote-enrollment-browser.mjs` 6 個流程（模擬 API），截圖在 `output/control-remote-enrollment-verification/`。未做真實 SSH、多站或第二中控驗收。

### 安裝身份、部署階段與手動恢復舊版（2026-10-17，Claude 實作，僅模擬 API 驗證）

- 契約：`docs/architecture/control-installation-roles.md`、`control-deployment-recovery-api.md`。新增 `deployment-recovery-state.js`。
- 安裝身份：只有 `installationModes`（陣列）與布林 `installationIdentityRequired` 同時存在才使用 `siteMode`／`allocationSlot`（CAMPUS 送 0，EXTERNAL 1–1023）；兩欄都不存在沿用舊契約；只缺一欄或格式錯顯示「身份能力未知」並停用建立計畫。EXTERNAL 不複製管理員，安裝後為「已安裝 · 等待接入」；已分配或未知身份不能用單機更新；恢復保留原角色與分配。已加入的卡片只寫「已加入集群」。
- 部署：目標顯示 stage／failureStage（保留原碼），刷新鍵包含階段。手動恢復舊版只在 `deploymentRecoveryEnabled === true` 且目前持有租約（localLeader 且 HELD）時可用；預覽有效期缺失／非法或過期都不能執行；確認後重查；回執遺失只核對，不重送、不換 UUID；恢復成功不等於新版部署成功。
- 權威診斷：dispatch_authorized 缺失為未知；重讀中標示「以下為上次結果」。
- `npm test`：262 項通過（新增 `tests/roles-recovery.test.js`）。瀏覽器：`scripts/verify-control-roles-recovery-browser.mjs` 10 個流程（模擬 API），截圖在 `output/control-roles-recovery-verification/`；其餘八個腳本重跑通過。未在真實後端或伺服器上驗證。

### 任務歷史、恢復安裝與權威發布約束（2026-10-17，Claude 實作）

- 契約：`docs/architecture/control-task-history-api.md`、`control-retained-install-recovery.md`、`control-deployment-fence.md`。新增 `tasks-state.js`、`tasks-ui.js`，路由 `#/tasks`（記錄與帳號 → 任務歷史）。
- 任務列表只讀；篩選類型／狀態／伺服器、不透明游標分頁（同頁請求合併、重複項以較新者合併）；詳情只由「類型＋UUID」白名單組合並交給既有面板，不使用回應中的 detailRoute。讀取失敗、掃描上限不顯示成空列表。
- 權威發布約束：ACTIVE 一律待核對；本地詳情不存在時提示恢復原共享任務狀態，不提供繞過按鈕；格式無效或不可用顯示狀態未知；頁內重讀以獨立世代丟棄遲到的列表與詳情回應。未收斂碼在任務頁、部署頁與總覽都是結果未知。
- 恢復安裝：只在來源任務讀回為已核實 FAILED 的 INSTALL／RECOVER 時提供；結果未知只提供核對；確認後重查 revision、計畫與來源任務。單機版本更新只限明確 NOT_JOINED，埠與網址唯讀。
- `npm test`：250 項通過（新增 `tests/tasks.test.js` 12 項）。瀏覽器：`scripts/verify-control-tasks-browser.mjs` 13 個流程通過（模擬 API），截圖在 `output/control-tasks-verification/`；其餘七個腳本重跑通過。

### 節點健康檢測（2026-10-17，Claude 實作）

- 契約：`docs/architecture/control-health-api.md`（含後續補充：running=true 不覆蓋 UNKNOWN／FAIL／STALE、錯誤碼對齊、DEPENDENCY_CHECKS_ONLY／CONFIGURATION_ONLY 不視為資料庫身份核實）。純邏輯在 `health-state.js`，畫面在站點卡片「健康檢測」區。
- 就緒（應用／資料庫／Redis）與 S3 桶存取（HEAD Bucket）分開；過期 PASS、觀測失效、UNKNOWN、逾時都不顯示健康；缺欄位顯示未檢查。存儲讀寫驗證結果只代表該次驗證；CLEANUP_PENDING 為琥珀色並只提供「重試清理…」（沿用原 requestId）。
- 「立即檢測」只讀，備機也可；「驗證存儲讀寫…」與重試清理只限主中控，確認後重查領導；結果未知保留 requestId、不自動重送。站點頁可見時以 2 秒加快觀測（最多 2 分鐘），離開或登出即停止並丟棄遲到回應；輪詢重繪保留按鈕焦點。
- S3 桶存取為 HEAD＋空列表（`S3_HEAD_BUCKET_AND_EMPTY_LIST`），不讀取業務物件內容；`STORAGE_ENDPOINT_NOT_S3`／`STORAGE_INVALID_S3_RESPONSE` 提示檢查 S3 API 入口與反向代理，不宣稱桶不存在。錯誤碼對齊 `ControlHealthChecks.java` 與 `ControlStorageCheck.java`。
- `npm test`：238 項通過（`tests/health.test.js` 12 項）。瀏覽器：`scripts/verify-control-health-browser.mjs` 13 個流程通過（模擬 API），截圖在 `output/control-health-verification/`；其餘六個腳本重跑通過。

### 已安裝伺服器接入集群（2026-10-17，Claude 實作）

- 契約：`docs/architecture/control-server-cluster-api.md`。只支援空集群的首個「中控本機」校內權威站（`singleSiteOnly`）；遠端首站、已有成員或功能未啟用時入口停用並說明原因。不描述為跨站同步已就緒。
- 只有 `installation.clusterMembership=ACTIVE`（後端核對真實 APPLIED／ACTIVE）才顯示「已加入集群 · 單站運行」；安裝成功或任務結果缺欄位都不算加入。
- 確認後重新讀取計畫與伺服器 revision 並套用主中控閘門；execute 不確定時不重送，只以原計畫 UUID 唯讀查詢。中控重啟期間（網路、502、503）顯示重新連線，每 4 秒起、上限 15 秒退避；401 走正常重新登入，登入後以清單 `clusterJobId` 自動查回。任務 UUID 只存在記憶體（不使用 sessionStorage）。
- `npm test`：225 項通過（`tests/servers.test.js` 新增 8 項接入測試，`tests/locale.test.js` 補「運行／執行」簡體轉換）。瀏覽器：`scripts/verify-control-server-cluster-browser.mjs` 9 個流程通過（模擬 API，不是真實成員證明），截圖在 `output/control-server-cluster-verification/`；其餘五個腳本重跑通過。

### SSH 伺服器與首次安裝（2026-10-17，Claude 實作）

- 契約：`docs/architecture/control-servers-api.md`。側欄「運維」新增「伺服器」（`#/servers`，兩種模式共用一頁）；多站部署頁沒有成員時顯示「添加 SSH 伺服器」導引。
- 寫入閘門：清單 `canManage`／`reason`＋多站主中控租約（`cluster.leadership()`）；失租或交接中只讀，任務查詢仍可用。確認後重新讀取清單（revision）與計畫（READY、未過期、serverRevision）再送出。
- 任務只有總體狀態；`result.stage` 僅以文字顯示於明細。`installation.state` 為 `NOT_INSTALLED` 視為未安裝可重新計畫；`FAILED` 保留且不能重裝。
- `npm test`：217 項通過（新增 `tests/servers.test.js` 27 項）。瀏覽器：`scripts/verify-control-servers-browser.mjs` 13 個流程通過（本機模擬 API、非真實 SSH），截圖在 `output/control-servers-verification/`；既有改版（18）、語系（8）、切主（10）、部署（9）腳本重跑通過。

### 介面改版與帳號設定（2026-10-17，Claude 實作）

- `npm test`：190 項全數通過（新增 `tests/redesign.test.js` 27 項：唯讀原因代碼轉說明、路由解析、頁面結構與 ID、不載入示範資料、名稱／密碼規則與 code point／UTF-8 邊界、只送變更欄位、錯誤分類（含 503 `ACCOUNT_UPDATE_OUTCOME_UNKNOWN` 走結果未知、`ACCOUNT_UPDATE_UNAVAILABLE` 保留登入）、200 回應必須含 `reauthenticationRequired:true` 且名稱符合才算成功、帳號頁 UI 流程、總覽空狀態與排序）。
- 瀏覽器（本機模擬 API，非真實後端）：`scripts/verify-control-redesign-browser.mjs` 18 個流程檢查點通過，截圖在 `output/control-redesign-verification/`。既有 `verify-locale-browser.mjs`（8）、`verify-handoff-browser.mjs`（10）、`verify-deployment-browser.mjs`（9）改為先經側欄導覽到對應頁後全數通過，未刪除任何核心檢查；共用導覽在 `scripts/control-nav.mjs`。
- 三站真實 Spring 瀏覽器：Codex 修正驗收靜態伺服器的本地 OpenCC 模組白名單，Claude 明確指定繁體語系後，30 個流程全部通過（含站點加入／退役、預覽失效、故障恢復、任務、登出與 1440／390 排版）。證據：`output/playwright/cluster-control-7f7d727d399943958115d78de2f7d641/report.json`。
- 帳號後端已完成隔離真實 PostgreSQL／Redis／HTTP 驗證，相關後端測試共 138 項通過；正式環境只做讀取及無變更驗證，既有 32 項與新增 22 項線上檢查通過；HTTPS 28 個靜態檔案雜湊與已驗證版本一致，原帳密保持不變。稽核頁沒有帳號稽核清單 API，不顯示此類資料。

### 控制領導（主備租約）更新（2026-10-16，Claude 實作、Codex 複驗）

- `npm test`：154 項全數通過（新增 `tests/cluster-leadership.test.js` 10 項：租約正規化、寫入開放條件、讀取失敗／過期即停用、`status.leadership` 備援、`CONTROL_LEASE_NOT_HELD` 後不採信舊讀取、確認後重查與 generation 變更、失租寫入結果、`GET /leadership` 路徑、8 個控制寫入皆受閘門保護、看板與表單結構）。
- 瀏覽器（內建 Chromium 預覽）以**本機模擬後端**驗證，非真實 Spring 後端：已登入且有資料時，leader／備機／租約過期三種狀態的看板與總覽正確；備機時 8 個控制寫入按鈕全部停用、驗收任務啟動仍可用、部署執行仍鎖定；確認對話框開啟期間轉為備機，確認後不送出（網路紀錄無 POST）；確認瞬間失租時收到 503 `CONTROL_LEASE_NOT_HELD`，登入態與 requestId 保留、看板轉為備機；390px 與 1440px 全部收合區展開後 `scrollWidth` 等於視窗寬度；除刻意注入的 503 外沒有 JavaScript／CSP 錯誤。
- 尚未驗證：真實多 JVM 主備切換時穩定入口轉向、管理 Session 在新 leader 沿用、租約到期接管時序，以及鍵盤／螢幕閱讀器。

### 多站叢集版本（先前已驗證）

多站功能曾以本機三個真實 Spring 站點完成 144 項 Node 測試與 30 個 Chromium 流程檢查點；環境、涵蓋範圍與限制見[多站前端驗收紀錄](../../docs/architecture/cluster-control-frontend-verification.md)。本輪加入控制領導功能後，Node 測試增加至 154 項並已重跑；新的主備畫面瀏覽器流程使用本機模擬後端，結果與尚待真實節點驗證的項目如上。

### 雙站版本（先前已驗證）

2026-10-16 由 Codex 執行；Claude 負責前端檔案修改，全程以繁體中文交接。前端不含建置步驟。

- `npm test`：82 項通過，零失敗或跳過；包含手機排版靜態檢查與告警／摘要邏輯。
- 真實 Chromium 149：19 個流程檢查點通過。涵蓋一般帳號拒絕、管理員登入、資料庫讀取失敗顯示 UNKNOWN、首次登入以設定清單恢復外站、恢復後核對兩個不同 V35 資料庫、BLOCK 上限、取消確認不寫入、真實 TCP 阻斷與到期自動恢復、真實制品 SHA 預覽、部署鎖定、權杖不持久化及登出。
- 1440px 桌面與 390px 手機均無整頁橫向溢出；手機版在已登入、預覽已建立、稽核有資料且全部技術明細展開後仍通過。表格與導覽列可於自身容器橫捲。
- 沒有 JavaScript／CSP 錯誤。瀏覽器流程沒有啟動 Maven 任務或呼叫部署執行；任務執行器另由後端測試驗證。

先前版本曾在 390px 產生 607px 的頁面寬度。此次以 `minmax(0, 1fr)`、子項最小寬度、fieldset／select 限寬與區內橫捲修正，沒有用 `overflow-x: hidden` 隱藏問題，並經實際瀏覽器重測。

視覺整理包含：產品與環境頂列、五格總覽、核心狀態與提醒優先的站點卡片、原生技術明細收合、青綠／琥珀／紅狀態配色、主要操作層級與較清楚的表格節奏。頂列只在桌面固定；手機隨頁面捲動。既有確認對話框、API 契約與部署鎖定保留。

驗收使用本機 Spring、兩個隔離的真實 V35 資料庫與生產 ControlNode；外站 HTTP 是測試包裝，並非第二個完整業務服務。尚未驗收真實跨地域節點、worker 全流量路由、R2／遠端 RustFS 的中控探測、公共 CDN、實際部署或回滾。完整鍵盤／螢幕閱讀器與工具量測色彩對比也仍待驗證。
