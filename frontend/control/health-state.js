// 節點健康檢測（docs/architecture/control-health-api.md）：純函式，可在 Node 測試。
// - 就緒（application／database／redis）與 S3 桶存取（HEAD＋空列表，checkKind S3_HEAD_BUCKET_AND_EMPTY_LIST）分開；桶可存取不代表可讀寫。
// - 過期的 PASS、觀測已失效時的舊結果、UNKNOWN、檢測中、逾時都不顯示為健康。
// - 存儲讀寫探針的結果只代表「本次讀寫驗證」，不等於目前健康。
// - 缺欄位（舊後端）一律顯示「未檢查」，不補成正常；未知錯誤碼原樣保留。

export const HEALTH_CHECK_KEYS = Object.freeze(['application', 'database', 'redis', 'storage']);
export const HEALTH_CHECK_LABELS = Object.freeze({ application: '應用', database: '資料庫', redis: 'Redis', storage: 'S3 桶存取' });
export const HEALTH_FAST_POLL_MS = 2000;
export const HEALTH_FAST_POLL_LIMIT_MS = 120000;

const STATUS_COPY = {
  PASS: ['teal', '通過'], FAIL: ['red', '失敗'], UNKNOWN: ['amber', '未知'], CHECKING: ['amber', '檢測中'], STALE: ['amber', '已過期'], NOT_CHECKED: ['neutral', '未檢查'],
};
const READINESS_COPY = {
  READY: ['teal', '就緒'], NOT_READY: ['red', '未就緒'], CHECKING: ['amber', '檢測中'], UNKNOWN: ['amber', '未知'], STALE: ['amber', '已過期'], NOT_CHECKED: ['neutral', '未檢查'],
};
const STORAGE_COPY = {
  REACHABLE: ['teal', '桶可存取'], UNREACHABLE: ['red', '桶無法存取'], CHECKING: ['amber', '檢測中'], UNKNOWN: ['amber', '未知'], STALE: ['amber', '已過期'], NOT_CHECKED: ['neutral', '未檢查'],
};
const WRITE_COPY = {
  NOT_CHECKED: ['neutral', '尚未驗證'], RUNNING: ['amber', '讀寫驗證進行中'], PASSED: ['teal', '本次讀寫驗證通過'],
  FAILED: ['red', '讀寫驗證失敗'], CLEANUP_PENDING: ['amber', '清理待處理'],
};
export const WRITE_PHASE_TEXT = Object.freeze({ PREPARED: '已準備', WRITE: '寫入', READ: '讀取', CLEANUP: '清理', COMPLETE: '完成' });

// 後端安全碼的說明，對齊 ControlHealthChecks.java 與 ControlStorageCheck.java；未知碼不翻譯，畫面上一律同時保留原碼供複製。
export const HEALTH_CODE_TEXT = Object.freeze({
  // ControlHealthChecks：只讀檢測
  CHECK_IN_PROGRESS: '檢測進行中。',
  CHECK_TIMEOUT: '檢測逾時（每項上限 5 秒）；底層連線可能仍在結束。',
  CHECK_RESULT_EXPIRED: '結果已超過有效期限。',
  CHECK_CAPACITY_EXCEEDED: '同時進行的檢測過多，本次未執行。',
  DEPENDENCY_CHECK_FAILED: '依賴檢測執行失敗，結果未知。',
  APPLICATION_NOT_ACCEPTING_TRAFFIC: '應用目前不接受流量。',
  DATABASE_NOT_READY: '資料庫未就緒。',
  DATABASE_UNAVAILABLE: '資料庫無法連線。',
  REDIS_NOT_READY: 'Redis 未就緒。',
  REDIS_UNAVAILABLE: 'Redis 無法連線。',
  STORAGE_NOT_CONFIGURED: '應用沒有設定物件儲存。',
  STORAGE_ACCESS_DENIED: '存儲拒絕存取（401／403）：應用設定的憑據沒有這個桶的權限。',
  STORAGE_BUCKET_NOT_FOUND: '找不到設定的存儲桶。',
  STORAGE_UNREACHABLE: '存儲端點無法連線。',
  STORAGE_TIMEOUT: '存儲請求逾時。',
  STORAGE_TEMPORARILY_UNAVAILABLE: '存儲暫時不可用。',
  STORAGE_REQUEST_FAILED: '存儲請求失敗（其他 S3 錯誤）。',
  // 協議校驗：端點不是 S3 API 或回應不合法時，問題在入口或反向代理，不代表桶不存在。
  STORAGE_ENDPOINT_NOT_S3: '存儲端點回應的不是 S3 API（例如網頁的 HTML 404／403）。請檢查 S3 API 入口網址與反向代理設定；這不代表桶不存在。',
  STORAGE_INVALID_S3_RESPONSE: '端點回應 HTTP 200，但沒有合法的 S3 桶回應（桶名不符或回傳了物件）。請檢查 S3 API 入口與反向代理設定；這不代表桶不存在。',
  // ControlStorageCheck：存儲讀寫探針
  REQUEST_ID_REQUIRED: '缺少請求 UUID。',
  STORAGE_CHECK_CLEANUP_REQUIRED: '上次的臨時物件尚未清理；請先重試清理，不能開始新的寫入。',
  STORAGE_CHECK_CONFIGURATION_CHANGED: '存儲設定在驗證期間改變，結果作廢。',
  STORAGE_CHECK_HISTORY_FULL: '驗證記錄已滿，暫時不能開始新的驗證。',
  STORAGE_CHECK_INTERRUPTED_CLEANED: '驗證被中斷，已清理臨時物件。',
  STORAGE_CHECK_LOCKED: '另一個程序持有驗證鎖；臨時物件需重試清理。',
  STORAGE_CHECK_STATE_UNAVAILABLE: '驗證狀態暫時無法讀取。',
  STORAGE_CHECK_STOPPING: '中控正在停止，暫不接受新的驗證。',
  STORAGE_CLEANUP_IN_PROGRESS: '正在清理臨時物件。',
  STORAGE_READ_MISMATCH: '讀回的臨時物件內容與寫入不符。',
  STORAGE_PROBE_CLEANUP_FAILED: '臨時物件清理失敗；請重試清理。',
  STORAGE_PROBE_OWNERSHIP_MISMATCH: '臨時物件的歸屬不符，已停止，不會刪除不屬於本次驗證的物件。',
  // 既有共用碼
  CONTROL_LEASE_NOT_HELD: '這個入口目前不是主中控；存儲寫入驗證與重試清理只能由主中控發起。',
  NODE_TIMEOUT: '節點未在時限內回應。',
});
export const healthCodeText = (code) => (typeof code === 'string' && code ? HEALTH_CODE_TEXT[code] ?? null : null);

// 結果列的代碼說明：OK 是後端的成功碼（單項 PASS、讀寫驗證 PASSED），只在有效狀態本身是成功時才說成功；
// 過期／觀測失效的 PASS 說明是舊結果；狀態為 FAIL／UNKNOWN 等卻帶 OK 時如實標示不一致，狀態仍以 status 為準。
// 其他代碼照原表，未知碼仍顯示「未定義的錯誤碼」。
export function resultCodeText(code, status) {
  if (typeof code !== 'string' || !code) return null;
  if (code !== 'OK') return healthCodeText(code) ?? '未定義的錯誤碼';
  if (status === 'PASS') return '檢測成功。';
  if (status === 'PASSED') return '讀寫驗證成功。';
  if (status === 'STALE') return '上次檢測成功，但結果已過期或觀測不是最新，不代表目前健康。';
  return `回報代碼 OK 與狀態 ${status ?? '未知'} 不一致；以狀態為準。`;
}

const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const timeMs = (v) => { const t = typeof v === 'string' ? Date.parse(v) : NaN; return Number.isFinite(t) ? t : null; };

// 單項檢測的有效狀態：過期的 PASS → STALE；running → CHECKING；觀測失效時任何舊結果都不是目前的 PASS。
export function effectiveCheck(raw, { now, current }) {
  if (!isObj(raw)) return { status: 'NOT_CHECKED', provided: false, code: null, checkKind: null, startedAt: null, checkedAt: null, validUntil: null, durationMs: null, running: false, expired: false, finishing: false };
  const reported = Object.hasOwn(STATUS_COPY, raw.status) && raw.status !== 'NOT_CHECKED' ? raw.status : 'UNKNOWN';
  const validUntil = str(raw.validUntil);
  const until = timeMs(validUntil);
  const expired = until !== null && until <= now;
  // 只有實際回報 CHECKING 才是「檢測中」；running=true 搭配 UNKNOWN／FAIL／STALE（例如 CHECK_TIMEOUT 後底層連線仍在結束）
  // 保留原狀態，另外標示 finishing。
  let status = reported;
  if (reported === 'PASS' && (expired || !current || until === null)) status = 'STALE';
  return {
    status, provided: true, reported, code: str(raw.code), checkKind: str(raw.checkKind),
    startedAt: str(raw.startedAt), checkedAt: str(raw.checkedAt), validUntil,
    durationMs: Number.isFinite(raw.durationMs) ? raw.durationMs : null, running: raw.running === true, expired,
    finishing: raw.running === true && reported !== 'CHECKING',
  };
}

const aggregateValue = (value, table) => (typeof value === 'string' && Object.hasOwn(table, value) ? value : value === undefined || value === null ? 'NOT_CHECKED' : 'UNKNOWN');

// 從 node.control 推出畫面用的健康模型。current：本次觀測是否為最新且節點可達。
export function healthModel(control, { now = Date.now(), current = true } = {}) {
  const c = isObj(control) ? control : {};
  const raw = isObj(c.healthChecks) ? c.healthChecks : null;
  const checks = HEALTH_CHECK_KEYS.map((key) => ({ key, label: HEALTH_CHECK_LABELS[key], ...effectiveCheck(raw?.[key], { now, current }) }));
  const byKey = Object.fromEntries(checks.map((k) => [k.key, k]));

  let readiness = aggregateValue(c.readiness, READINESS_COPY);
  // 後端回報 READY，但構成就緒的三項不是有效 PASS（過期、觀測失效、缺漏）時，不顯示為就緒。
  if (readiness === 'READY' && (!current || ['application', 'database', 'redis'].some((k) => byKey[k].status !== 'PASS'))) {
    readiness = ['application', 'database', 'redis'].some((k) => byKey[k].status === 'CHECKING') ? 'CHECKING' : 'STALE';
  }
  let storage = aggregateValue(c.storageReachability, STORAGE_COPY);
  if (storage === 'REACHABLE' && (!current || byKey.storage.status !== 'PASS')) storage = byKey.storage.status === 'CHECKING' ? 'CHECKING' : 'STALE';

  const w = isObj(c.storageWriteCheck) ? c.storageWriteCheck : null;
  const writeState = w && Object.hasOwn(WRITE_COPY, w.state) ? w.state : w ? 'UNKNOWN' : 'NOT_CHECKED';
  const [writeTone, writeLabel] = WRITE_COPY[writeState] ?? ['amber', `未知狀態 ${String(w?.state)}`];
  const write = {
    state: writeState, tone: writeTone, label: writeLabel,
    phase: str(w?.phase), phaseText: WRITE_PHASE_TEXT[w?.phase] ?? null,
    code: str(w?.code), requestId: str(w?.requestId), startedAt: str(w?.startedAt), updatedAt: str(w?.updatedAt),
    provided: Boolean(w),
  };

  // 健康獨立讀取：資料庫故障時節點仍可只回報依賴檢測；身份只來自設定，不是資料庫身份核實。
  const dependencyOnly = c.observationStatus === 'DEPENDENCY_CHECKS_ONLY' || c.identityProof === 'CONFIGURATION_ONLY';

  return {
    provided: Boolean(raw) || c.readiness !== undefined || c.storageReachability !== undefined || Boolean(w),
    current,
    dependencyOnly,
    identityProof: str(c.identityProof),
    observationStatus: str(c.observationStatus),
    readiness: { value: readiness, tone: READINESS_COPY[readiness][0], label: READINESS_COPY[readiness][1], reported: c.readiness ?? null },
    storage: { value: storage, tone: STORAGE_COPY[storage][0], label: STORAGE_COPY[storage][1], reported: c.storageReachability ?? null },
    checks: checks.map((k) => ({ ...k, tone: STATUS_COPY[k.status][0], statusLabel: STATUS_COPY[k.status][1] })),
    write,
    anyRunning: checks.some((k) => k.status === 'CHECKING') || readiness === 'CHECKING' || storage === 'CHECKING' || writeState === 'RUNNING',
  };
}

// 立即檢測（只讀）：主控與備機都可；只需登入與節點可觀測。
export function readCheckBlockers({ observable, sessionActive }) {
  const reasons = [];
  if (!sessionActive) reasons.push('尚未登入。');
  if (!observable) reasons.push('此站不在觀測範圍。');
  return reasons;
}

// 存儲讀寫驗證（寫入）：只有主控；進行中或清理待處理時不能開新任務。
export function storageWriteBlockers(health, { leadWritable, leadReasons = [], current }) {
  const reasons = [];
  if (!leadWritable) reasons.push(...(leadReasons.length ? leadReasons : ['只有主中控可以發起存儲寫入驗證。']));
  if (!current) reasons.push('節點觀測不是最新，先等觀測恢復再驗證。');
  if (health.write.state === 'RUNNING') reasons.push('已有讀寫驗證在進行。');
  if (health.write.state === 'CLEANUP_PENDING') reasons.push('上次的臨時物件尚未清理；請先按「重試清理…」。');
  return reasons;
}

export function cleanupRetryBlockers(health, { leadWritable, leadReasons = [] }) {
  const reasons = [];
  if (health.write.state !== 'CLEANUP_PENDING') reasons.push('沒有待清理的讀寫驗證。');
  if (!health.write.requestId || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(health.write.requestId)) reasons.push('缺少原驗證的 requestId，無法安全重試清理。');
  if (!leadWritable) reasons.push(...(leadReasons.length ? leadReasons : ['只有主中控可以重試清理。']));
  return reasons;
}

// 總覽待處理問題：只列出明確不健康或狀態不明的項目；未檢查（舊後端）不列入。
export function healthIssues(siteId, health) {
  const issues = [];
  if (!health.provided) return issues;
  if (health.readiness.value === 'NOT_READY') issues.push({ tone: 'red', title: `${siteId} 未就緒`, detail: '應用、資料庫或 Redis 檢測失敗；詳見站點卡片。' });
  else if (['UNKNOWN', 'STALE'].includes(health.readiness.value)) issues.push({ tone: 'amber', title: `${siteId} 就緒狀態${health.readiness.label}`, detail: '沒有有效的就緒結果，不能視為健康。' });
  if (health.storage.value === 'UNREACHABLE') issues.push({ tone: 'red', title: `${siteId} S3 桶無法存取`, detail: '使用應用設定的憑據存取存儲桶失敗。' });
  else if (['UNKNOWN', 'STALE'].includes(health.storage.value)) issues.push({ tone: 'amber', title: `${siteId} S3 桶存取${health.storage.label}`, detail: '沒有有效的存取結果。' });
  if (health.write.state === 'CLEANUP_PENDING') issues.push({ tone: 'amber', title: `${siteId} 存儲探針清理待處理`, detail: '需由主中控重試清理；清理完成前不能開始新的讀寫驗證。' });
  return issues;
}
