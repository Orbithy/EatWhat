// 中控前端固定常數。API 基底與登入路徑固定為同源相對路徑，不提供任何可輸入的 URL。

export const CONTROL_BASE = '/admin/control/v1';
export const LOGIN_PATH = '/auth/login';
export const LOGIN_DEVICE = 'WEB';
export const LOGIN_SUCCESS_CODE = 0;

export const POLL_INTERVAL_MS = 5000;
export const REQUEST_TIMEOUT_MS = 8000;
// 控制接口跨站讀取需容納網路往返；新鮮度仍由各資源的請求開始時間判定。
export const CONTROL_REQUEST_TIMEOUT_MS = 30000;
// 備用中控登入需跨站完成帳號與會話檢查，使用獨立等待上限。
export const LOGIN_TIMEOUT_MS = 45000;
// 超過此時間沒有成功讀取，即視為過期資料。
export const STALE_AFTER_MS = 15000;
// 契約：中控 BLOCK 要求觀測新鮮（30 秒內）。
export const BLOCK_FRESHNESS_MS = 30000;

export const SITE_IDS = Object.freeze(['campus', 'external']);

export const SUITE_IDS = Object.freeze(['business', 'coordinated-gc', 'full-verify']);
export const SUITE_SCOPES = Object.freeze({
  business: 'LOCAL_BUSINESS_RUSTFS',
  'coordinated-gc': 'LOCAL_DUAL_SITE_PROTOCOL',
  'full-verify': 'LOCAL_CLEAN_VERIFY',
});

export const RUN_STATES = Object.freeze([
  'QUEUED',
  'RUNNING',
  'COMMAND_SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
]);
// INTERRUPTED 是終止狀態：重啟後無法擁有的舊任務，不可視為仍在執行。
export const ACTIVE_RUN_STATES = Object.freeze(['QUEUED', 'RUNNING']);

export const FAULT_ACTIONS = Object.freeze(['BLOCK', 'RESTORE']);
export const FAULT_SECONDS_MIN = 1;
export const FAULT_SECONDS_MAX = 300;
// RESTORE 不以秒數控制恢復時間，但契約要求 1–300；固定送出最小值。
export const RESTORE_SECONDS = 1;

export const AUDIT_LIMIT_MIN = 1;
export const AUDIT_LIMIT_MAX = 200;
export const AUDIT_LIMIT_DEFAULT = 50;
export const AUDIT_LIMIT_CHOICES = Object.freeze([20, 50, 100, 200]);

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const CHANNEL_RE = /^[A-Za-z0-9_-]{1,64}$/;
export const RELEASE_ID_RE = /^[A-Za-z0-9._-]{1,128}$/;
// RFC 9110 token，用於驗證登入回傳的 tokenName 可作為 HTTP header 名稱。
export const HEADER_NAME_RE = /^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/;

// ---------- 多站叢集 ----------

// 部署時固定的中控模式：'cluster' 只使用多站中控入口；'legacy' 保留既有雙站畫面。
// 不提供執行期切換或自動降級：cluster 入口回 404 時只顯示「入口或功能未開啟」。
export const CONTROL_MODE = 'cluster';
export const CONTROL_MODES = Object.freeze(['cluster', 'legacy']);

export const CLUSTER_BASE = '/admin/control/v1/cluster';
// /observations 逐站執行多次 RPC 且沒有整體截止時間，使用獨立、可調的長逾時。
export const OBSERVATION_TIMEOUT_MS = 360000;
// 每輪完成後才排下一輪，間隔 5 秒；不重疊輪詢。
export const CLUSTER_POLL_INTERVAL_MS = 5000;
// 叢集狀態、變更、GC、稽核等簡短接口的過期門檻（自成功請求開始時間起算）。
export const CLUSTER_STATUS_STALE_MS = 15000;
// 觀測資料過期門檻與 BLOCK 新鮮度要求：成功請求開始時間距今 30 秒內。
export const OBSERVATION_STALE_MS = 30000;
export const CLUSTER_BLOCK_FRESHNESS_MS = 30000;

export const CLUSTER_LIST_LIMIT = 20;
export const CLUSTER_LIMIT_MIN = 1;
export const CLUSTER_LIMIT_MAX = 100;
export const CLUSTER_AUDIT_LIMIT_CHOICES = Object.freeze([20, 50, 100]);
export const CLUSTER_AUDIT_LIMIT_DEFAULT = 50;
export const CLUSTER_MAX_PREVIEW_SITES = 16;

// siteId、profileId、releaseId、通道名稱共用的契約格式。
export const CLUSTER_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

export const MEMBER_STATES = Object.freeze(['REGISTERED', 'JOINING', 'ACTIVE', 'DRAINING', 'RETIRED']);
// /observations 只回 ACTIVE 與 DRAINING。
export const OBSERVED_MEMBER_STATES = Object.freeze(['ACTIVE', 'DRAINING']);
export const PARTICIPATING_MEMBER_STATES = Object.freeze(['JOINING', 'ACTIVE', 'DRAINING']);
export const CHANGE_STATES = Object.freeze(['PREPARING', 'COMMITTED', 'INSTALLED', 'APPLIED']);
export const CHANGE_VOTE_PHASES = Object.freeze(['PREPARED', 'APPLIED', 'RELEASED']);
export const GC_STATES = Object.freeze(['PREPARING', 'COMMITTED', 'DELETED']);
export const GC_VOTE_PHASES = Object.freeze(['PREPARED', 'DELETED']);
