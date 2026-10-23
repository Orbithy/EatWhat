// 任務歷史與權威發布約束（docs/architecture/control-task-history-api.md、control-deployment-fence.md）：純函式。
// - 詳情位置只由「類型＋UUID」白名單組合，不使用回應中的 detailRoute。
// - 需要核對或狀態未收斂的任務一律顯示「結果未知」，不畫成成功。
// - 讀取失敗不當成空列表。

export const TASK_TYPES = Object.freeze(['INSTALL', 'UPDATE', 'RECOVER', 'ENROLLMENT', 'DEPLOYMENT', 'CONTROLLER_INSTALL']);
export const TASK_TYPE_LABEL = Object.freeze({ INSTALL: '首次安裝', UPDATE: '版本更新', RECOVER: '恢復安裝', ENROLLMENT: '接入集群', DEPLOYMENT: '集群部署', CONTROLLER_INSTALL: '中控與業務安裝' });
export const TASK_STATES = Object.freeze(['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN']);
export const TASK_PAGE_LIMIT = 20;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isTaskUuid = (v) => typeof v === 'string' && UUID_RE.test(v);
const str = (v) => (typeof v === 'string' && v !== '' ? v : null);

// 這些碼代表結果尚未收斂，不能顯示為成功。
export const UNRESOLVED_CODES = Object.freeze(['DEPLOYMENT_FENCE_FINALIZATION_REQUIRED', 'DEPLOYMENT_ADMISSION_OUTCOME_UNKNOWN']);

export const TASK_CODE_TEXT = Object.freeze({
  TASK_HISTORY_SCAN_LIMIT: '本地任務記錄超過掃描上限（10000 份），無法完整列出；需由管理員處理任務記錄容量（請聯絡維護）。仍可依 UUID 開啟既有任務詳情。',
  TASK_HISTORY_INVALID_RECORD: '有本地任務記錄損壞，無法列出；這不是「沒有任務」。',
  TASK_HISTORY_UNAVAILABLE: '任務記錄暫時無法讀取；這不是「沒有任務」。',
  INVALID_REQUEST: '查詢條件無效。',
  DEPLOYMENT_FENCE_UNAVAILABLE: '權威發布約束診斷不可用（未設定或無法讀取）；這不代表沒有未結束的發布。',
  DEPLOYMENT_FENCE_INVALID_RESPONSE: '權威發布約束診斷回應格式無效，狀態未知；這不代表沒有未結束的發布。',
  DEPLOYMENT_FENCE_FINALIZATION_REQUIRED: '權威發布約束尚未收斂，結果未知；需由目前主中控核對。',
  DEPLOYMENT_ADMISSION_OUTCOME_UNKNOWN: '部署接受結果未知；需核對，不會自動重送。',
  AUTHORITY_NO_DISPATCH_PROVEN: '已證明從未派發到遠端的失敗；如需重試，必須重新建立部署預覽。',
});
export const taskCodeText = (code) => (typeof code === 'string' && code ? TASK_CODE_TEXT[code] ?? null : null);

// 詳情位置：只允許已知類型與合法 UUID；回傳 { route, target } 或 null。
export function taskDetailTarget(type, id) {
  if (!isTaskUuid(id)) return null;
  switch (type) {
    case 'INSTALL': case 'UPDATE': case 'RECOVER': return { route: 'servers', target: 'server-job', id };
    case 'ENROLLMENT': return { route: 'servers', target: 'cluster-job', id };
    case 'DEPLOYMENT': return { route: 'deploy', target: 'deployment', id };
    // 中控＋業務安裝：只在任務歷史頁的只讀詳情面板開啟（固定 GET /controller-jobs/{UUID}）。
    case 'CONTROLLER_INSTALL': return { route: 'tasks', target: 'controller-job', id };
    default: return null;
  }
}

// 詳情錨點只用固定白名單 route→href；不在白名單的 route 沒有連結（中鍵／新分頁也不會導到其他頁）。
export const TASK_ROUTE_HREF = Object.freeze({ servers: '#/servers', deploy: '#/deploy', tasks: '#/tasks' });
export const taskDetailHref = (target) => (target && Object.hasOwn(TASK_ROUTE_HREF, target.route) ? TASK_ROUTE_HREF[target.route] : null);

export function normalizeTask(t) {
  if (!t || typeof t !== 'object' || !isTaskUuid(t.id) || typeof t.state !== 'string') return null;
  return {
    id: t.id, type: str(t.type) ?? 'UNKNOWN', state: t.state, stage: str(t.stage), code: str(t.code),
    createdAt: str(t.createdAt), updatedAt: str(t.updatedAt),
    serverId: isTaskUuid(t.serverId) ? t.serverId : null, siteId: str(t.siteId),
    artifactSha256: str(t.artifactSha256), releaseId: str(t.releaseId),
    requiresReconciliation: t.requiresReconciliation === true,
  };
}

export function normalizeTaskPage(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.items)) return null;
  const items = data.items.map(normalizeTask).filter(Boolean);
  return { items, dropped: data.items.length - items.length, nextCursor: str(data.nextCursor), observedAt: str(data.observedAt) };
}

// 游標分頁可能重複出現正在更新的任務：以 id 合併，保留 updatedAt 較新的一筆，維持首次出現的順序。
export function mergeTasks(existing, incoming) {
  const out = [...existing];
  const index = new Map(out.map((t, i) => [t.id, i]));
  for (const t of incoming) {
    if (!index.has(t.id)) { index.set(t.id, out.length); out.push(t); continue; }
    const i = index.get(t.id);
    const a = Date.parse(out[i].updatedAt ?? ''), b = Date.parse(t.updatedAt ?? '');
    if (!Number.isFinite(a) || (Number.isFinite(b) && b >= a)) out[i] = t;
  }
  return out;
}

export function taskQuery({ type = '', state = '', serverId = '', cursor = null, limit = TASK_PAGE_LIMIT } = {}) {
  const q = new URLSearchParams();
  q.set('limit', String(limit));
  if (TASK_TYPES.includes(type)) q.set('type', type);
  if (TASK_STATES.includes(state)) q.set('state', state);
  if (isTaskUuid(serverId)) q.set('serverId', serverId);
  if (cursor) q.set('cursor', cursor);
  return q.toString();
}

// 畫面狀態：需核對或未收斂碼 → 結果未知；已證明未派發 → 失敗（未執行）。
export function taskDisplay(task) {
  if (task.requiresReconciliation || task.state === 'OUTCOME_UNKNOWN' || UNRESOLVED_CODES.includes(task.code)) {
    return { tone: 'red', label: '結果未知 · 需核對', unresolved: true };
  }
  if (task.code === 'AUTHORITY_NO_DISPATCH_PROVEN') return { tone: 'red', label: '失敗 · 已證明未派發', unresolved: false };
  const copy = { QUEUED: ['amber', '排隊中'], RUNNING: ['amber', '執行中'], SUCCEEDED: ['teal', '已完成'], FAILED: ['red', '失敗'] }[task.state];
  return copy ? { tone: copy[0], label: copy[1], unresolved: false } : { tone: 'amber', label: task.state, unresolved: false };
}

// ---------- 權威發布約束 ----------

// available=true 但 active 缺失、不是陣列，或有記錄卻全部無效：視為格式無效（狀態未知），不是健康的空列表。
// 部分記錄無效時保留有效的記錄並回報 dropped，畫面需提示診斷不完整。
export function normalizeFences(data) {
  const invalid = { available: false, reason: 'DEPLOYMENT_FENCE_INVALID_RESPONSE', active: [], dropped: 0 };
  if (!data || typeof data !== 'object') return invalid;
  if (data.available !== true) return { available: false, reason: str(data.reason) ?? 'DEPLOYMENT_FENCE_UNAVAILABLE', active: [], dropped: 0 };
  if (!Array.isArray(data.active)) return invalid;
  const active = data.active
    .filter((f) => f && typeof f === 'object' && isTaskUuid(f.job_id))
    .map((f) => ({
      jobId: f.job_id, clusterId: str(f.cluster_id), stateId: str(f.state_id), holderId: str(f.holder_id),
      generation: Number.isFinite(f.generation) ? f.generation : null, epoch: Number.isFinite(f.epoch) ? f.epoch : null,
      // 只讀診斷：缺失或不是布林值時為 null（未知），不補成「未授權」。
      manifestHash: str(f.manifest_hash), state: str(f.state), dispatchAuthorized: typeof f.dispatch_authorized === 'boolean' ? f.dispatch_authorized : null,
      createdAt: str(f.created_at),
    }));
  const dropped = data.active.length - active.length;
  if (data.active.length > 0 && active.length === 0) return { ...invalid, dropped };
  return { available: true, reason: null, active, dropped };
}

// ---------- 中控與業務安裝任務（CONTROLLER_INSTALL，只讀詳情） ----------
// 來源：GET /admin/control/v1/controller-jobs/{UUID}（ControlBootstrapJournal.publicView）。
// - 只保留公開欄位；私有封存資料（審批正文、回執、權杖）本來就不在回應中，也不從其他欄位推測。
// - 結果未知、需要核對或需要重新批准一律不顯示為完成；成功只是歷史記錄，不保證目前健康。

export const CONTROLLER_PHASES = Object.freeze(['SOURCE_RUNTIME', 'TARGET_RUNTIME', 'SOURCE_PRIVATE_PEER', 'TARGET_PRIVATE_PEER', 'SOURCE_SHARED_STATE', 'TARGET_SHARED_STATE',
  'DEPENDENCY_LINK', 'CONTROLLER_CONFIG', 'CONTROLLER_VERIFY', 'BUSINESS_INSTALL', 'MEMBER_CONFIGURE', 'MEMBER_JOIN', 'VERIFY']);
export const CONTROLLER_PHASE_LABEL = Object.freeze({
  SOURCE_RUNTIME: '來源執行環境', TARGET_RUNTIME: '目標執行環境', SOURCE_PRIVATE_PEER: '來源私有連線', TARGET_PRIVATE_PEER: '目標私有連線',
  SOURCE_SHARED_STATE: '來源共享狀態匯出', TARGET_SHARED_STATE: '目標共享狀態掛載', DEPENDENCY_LINK: '依賴連線', CONTROLLER_CONFIG: '中控設定',
  CONTROLLER_VERIFY: '中控驗證', BUSINESS_INSTALL: '業務安裝', MEMBER_CONFIGURE: '成員設定', MEMBER_JOIN: '加入集群', VERIFY: '最終驗證',
});
// VERIFIED 是任務的終態標記，不是第十四個安裝階段。
export const CONTROLLER_STAGE_LABEL = Object.freeze({ ...CONTROLLER_PHASE_LABEL, VERIFIED: '最終驗證完成' });
export const CONTROLLER_OPERATION_LABEL = Object.freeze({ KEY: '目標金鑰', SOURCE: '來源授權', CONNECT: '連線驗證' });
// DEPENDENCY_LINK 的子操作：後端固定順序與角色。
export const CONTROLLER_DEPENDENCY_OPERATIONS = Object.freeze([['KEY', 'TARGET'], ['SOURCE', 'SOURCE'], ['CONNECT', 'TARGET']]);

// 依賴連線子操作是否「精確且全部完成」：恰好 KEY、SOURCE、CONNECT 三項、順序與角色相符、nativeId 為父階段 UUID、全部 SUCCEEDED。
export function dependencyOperationsComplete(step) {
  const ops = step?.operations;
  if (!step || !isTaskUuid(step.id) || !Array.isArray(ops) || ops.length !== CONTROLLER_DEPENDENCY_OPERATIONS.length) return false;
  return CONTROLLER_DEPENDENCY_OPERATIONS.every(([operation, role], i) => {
    const o = ops[i];
    return o && o.operation === operation && o.role === role && isTaskUuid(o.nativeId) && o.nativeId.toLowerCase() === step.id.toLowerCase() && o.state === 'SUCCEEDED';
  });
}
const STEP_COPY = Object.freeze({
  PENDING: ['neutral', '等待'], DISPATCHED: ['red', '已派發 · 結果未確認'], RUNNING: ['amber', '執行中'],
  OUTCOME_UNKNOWN: ['red', '結果未知'], SUCCEEDED: ['teal', '完成'], FAILED: ['red', '失敗'],
});
export const CONTROLLER_CODE_TEXT = Object.freeze({
  BOOTSTRAP_REMOTE_OBSERVATION_REQUIRED: '遠端階段的結果尚未確認；需要由後端觀察實際狀態，不能視為完成。',
  BOOTSTRAP_PHASE_RECONCILIATION_REQUIRED: '階段結果未知，需要核對；不會自動重送。',
  BOOTSTRAP_PHASE_FAILED_PRESERVED: '階段失敗；已保留目前狀態，不會自動重試或清除。',
  BOOTSTRAP_REAPPROVAL_REQUIRED: '需要重新批准；在重新批准前不能繼續。',
  BOOTSTRAP_JOB_NOT_FOUND: '中控沒有這個安裝任務。',
  BOOTSTRAP_JOURNAL_UNAVAILABLE: '安裝任務記錄暫時無法讀取；狀態未知。',
  BOOTSTRAP_JOURNAL_INVALID: '安裝任務記錄格式無效；狀態未知。',
  BOOTSTRAP_PLAN_EXPIRED: '安裝批准已過期。',
  BOOTSTRAP_PLAN_CHANGED: '安裝批准後條件已改變，原批准失效。',
  BOOTSTRAP_TASK_TERMINAL: '任務已結束。',
  BOOTSTRAP_CANCELLED_BEFORE_DISPATCH: '已在派發前撤銷：只撤銷了佇列，沒有在伺服器上執行任何安裝，也不是回滾。',
  BOOTSTRAP_DISPATCH_ALREADY_CLAIMED: '已有派發意圖落盤，不能再撤銷；請以原任務的實際狀態為準。',
});
export const controllerCodeText = (code) => (typeof code === 'string' && code ? CONTROLLER_CODE_TEXT[code] ?? null : null);

const SHA_RE = /^[0-9a-f]{64}$/;
const CODE_RE = /^[A-Z][A-Z0-9_]{0,95}$/;
const int = (v) => (Number.isSafeInteger(v) ? v : null);
const code = (v) => (typeof v === 'string' && CODE_RE.test(v) ? v : null);
const bool = (v) => (typeof v === 'boolean' ? v : null);
const REVIEW_KEYS = ['sourceAddress', 'targetAddress', 'targetEndpoint', 'wireguardPort', 'httpPort', 'appBaseUrl'];

function normalizeStepRow(r, childKeys) {
  const row = {};
  for (const k of childKeys) row[k] = k === 'state' || k === 'remoteState' || k === 'phase' || k === 'operation' || k === 'role' ? code(r[k]) : k === 'id' || k === 'nativeId' ? (isTaskUuid(r[k]) ? r[k] : null) : str(r[k]);
  return row;
}

// ---------- 目標物件儲存（storage，選填；ControlBootstrapStorage.Frozen.review） ----------
// 只認公開鍵：mode、provider、endpoint、region、bucket、pathStyle、public-base-url、cdn-endpoint、credentialsConfigured。
// 其他鍵（例如 access-key、secret-key、encryptedProperties、binding）一律不讀。網址只接受 http(s)、無使用者資訊、query、fragment。
export const STORAGE_PROVIDER_LABEL = Object.freeze({ GENERIC: '通用 S3', RUSTFS: 'RustFS', R2: 'Cloudflare R2' });
export const STORAGE_MODE_LABEL = Object.freeze({ SOURCE: '沿用來源站設定', CUSTOM: '目標站自訂' });
const CONTROL_CHARS = /[\u0000-\u0020\u007f]/;

export function safeStorageUrl(v) {
  if (typeof v !== 'string' || !v || v.length > 2048 || CONTROL_CHARS.test(v) || v.includes('?') || v.includes('#')) return null;
  let u;
  try { u = new URL(v); } catch { return null; }
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password || !u.hostname || u.search || u.hash) return null;
  return v;
}

const storageText = (v) => (typeof v === 'string' && v.length >= 1 && v.length <= 128 && !CONTROL_CHARS.test(v) ? v : null);
const enumCode = (v) => (typeof v === 'string' && /^[A-Z][A-Z0-9_]{0,31}$/.test(v) ? v : null);

// 回傳 { provided, valid, mode, modeRaw, provider, providerRaw, endpoint, region, bucket, pathStyle, publicBaseUrl, cdnEndpoint, credentialsConfigured, problems }。
export function normalizeControllerStorage(raw) {
  if (raw === undefined || raw === null) return { provided: false, valid: false, problems: [] };
  if (typeof raw !== 'object' || Array.isArray(raw)) return { provided: true, valid: false, problems: ['目標物件儲存欄位格式無效。'] };
  const problems = [];
  const mode = Object.hasOwn(STORAGE_MODE_LABEL, raw.mode) ? raw.mode : null;
  const provider = Object.hasOwn(STORAGE_PROVIDER_LABEL, raw.provider) ? raw.provider : null;
  if (!mode) problems.push('設定來源未知。');
  if (!provider) problems.push('提供者未知。');
  const url = (key, required, label) => {
    const v = raw[key];
    if (v === undefined || v === null || v === '') { if (required) problems.push(`缺少${label}。`); return null; }
    const safe = safeStorageUrl(v);
    if (!safe) problems.push(`${label}格式異常（需要 http／https，且不得含帳號密碼、查詢字串或片段），未顯示。`);
    return safe;
  };
  const text = (key, label) => {
    const v = storageText(raw[key]);
    if (!v) problems.push(raw[key] === undefined || raw[key] === null || raw[key] === '' ? `缺少${label}。` : `${label}格式異常，未顯示。`);
    return v;
  };
  const endpoint = url('endpoint', true, 'S3 API 地址');
  const bucket = text('bucket', '桶名稱');
  const region = text('region', '區域');
  const publicBaseUrl = url('public-base-url', false, '公開網址');
  const cdnEndpoint = url('cdn-endpoint', false, 'CDN 地址');
  const pathStyle = typeof raw.pathStyle === 'boolean' ? raw.pathStyle : null;
  const credentialsConfigured = typeof raw.credentialsConfigured === 'boolean' ? raw.credentialsConfigured : null;
  if (credentialsConfigured === null) problems.push('沒有明確說明憑據是否已封存。');
  return {
    provided: true, valid: true, mode, modeRaw: mode ? null : enumCode(raw.mode), provider, providerRaw: provider ? null : enumCode(raw.provider),
    endpoint, region, bucket, pathStyle, publicBaseUrl, cdnEndpoint, credentialsConfigured, problems,
  };
}

// expectedId：請求的 UUID；回應 id 不同（或不是 CONTROLLER_INSTALL）視為格式無效，回 null。
export function normalizeControllerJob(data, expectedId) {
  if (!data || typeof data !== 'object' || Array.isArray(data) || !isTaskUuid(data.id) || data.kind !== 'CONTROLLER_INSTALL') return null;
  if (expectedId && data.id.toLowerCase() !== String(expectedId).toLowerCase()) return null;
  const review = data.review && typeof data.review === 'object' && !Array.isArray(data.review)
    ? Object.fromEntries(REVIEW_KEYS.filter((k) => typeof data.review[k] === 'string' || Number.isSafeInteger(data.review[k])).map((k) => [k, String(data.review[k])])) : null;
  const perms = Array.isArray(data.permissionsRequired) && data.permissionsRequired.every((p) => code(p)) ? [...data.permissionsRequired] : null;
  const steps = Array.isArray(data.steps)
    ? data.steps.filter((r) => r && typeof r === 'object').map((r) => ({
      ...normalizeStepRow(r, ['phase', 'id', 'state', 'dispatchedAt', 'observedAt', 'remoteState', 'finishedAt']),
      operations: Array.isArray(r.operations) ? r.operations.filter((o) => o && typeof o === 'object').map((o) => normalizeStepRow(o, ['operation', 'nativeId', 'role', 'state', 'dispatchedAt', 'observedAt', 'remoteState', 'finishedAt'])) : null,
    })) : null;
  return {
    id: data.id, workflowVersion: int(data.workflowVersion),
    sourceServerId: isTaskUuid(data.sourceServerId) ? data.sourceServerId : null, serverId: isTaskUuid(data.serverId) ? data.serverId : null,
    sourceRevision: int(data.sourceRevision), serverRevision: int(data.serverRevision),
    clusterId: isTaskUuid(data.clusterId) ? data.clusterId : null, epoch: int(data.epoch), allocationSlot: int(data.allocationSlot),
    controllerSha256: typeof data.controllerSha256 === 'string' && SHA_RE.test(data.controllerSha256) ? data.controllerSha256 : null,
    releaseId: str(data.releaseId), artifactSha256: typeof data.artifactSha256 === 'string' && SHA_RE.test(data.artifactSha256) ? data.artifactSha256 : null,
    review, permissionsRequired: perms,
    // 目標物件儲存：只顯示用，不影響完成判定。
    storage: normalizeControllerStorage(data.storage),
    // 原預覽的 requestHash（批准時原樣送回）；執行診斷碼只是固定代碼與時間，不代表原生成功／失敗。
    requestHash: typeof data.requestHash === 'string' && /^[a-f0-9]{64}$/.test(data.requestHash) ? data.requestHash : null,
    executionCode: code(data.executionCode), executionObservedAt: str(data.executionObservedAt),
    state: str(data.state), stage: code(data.stage), code: code(data.code), effectsStarted: bool(data.effectsStarted),
    createdAt: str(data.createdAt), acceptedAt: str(data.acceptedAt), expiresAt: str(data.expiresAt), finishedAt: str(data.finishedAt),
    // 缺少或不是布林值時為 null（未知），不補成 false。
    requiresReapproval: bool(data.requiresReapproval), requiresReconciliation: bool(data.requiresReconciliation),
    // 撤銷未派發：只認明確布林；缺失或非布林為 null（不可撤銷、也不算已撤銷）。
    canCancelBeforeDispatch: bool(data.canCancelBeforeDispatch), cancelledBeforeDispatch: bool(data.cancelledBeforeDispatch),
    steps,
  };
}

export function controllerStepDisplay(state) {
  const c = STEP_COPY[state];
  return c ? { tone: c[0], label: c[1] } : { tone: 'amber', label: state ? `未知狀態 ${state}` : '狀態缺失' };
}

// 已撤銷（未派發）：FAILED＋BOOTSTRAP_CANCELLED_BEFORE_DISPATCH＋cancelledBeforeDispatch＝true＋effectsStarted＝false 全部一致才成立。
export function controllerJobCancelled(job) {
  return job?.state === 'FAILED' && job.code === 'BOOTSTRAP_CANCELLED_BEFORE_DISPATCH' && job.cancelledBeforeDispatch === true && job.effectsStarted === false;
}

// 可提供撤銷：只在後端明確回報 canCancelBeforeDispatch＝true，且有原 requestHash。
export function controllerJobCancellable(job) {
  return job?.canCancelBeforeDispatch === true && typeof job.requestHash === 'string' && /^[a-f0-9]{64}$/.test(job.requestHash) && !controllerJobCancelled(job);
}

// 整體顯示：只有 SUCCEEDED、兩個旗標都明確為 false、且全部 13 階段（含依賴子操作）都明確完成，才顯示「已完成（歷史記錄）」。
export function controllerJobDisplay(job) {
  const notes = [];
  if (!job) return { tone: 'amber', label: '狀態未知', complete: false, unresolved: true, notes };
  const stepStates = (job.steps ?? []).flatMap((r) => [r.state, ...(r.operations ?? []).map((o) => o.state)]);
  const uncertainStep = stepStates.some((s) => s === 'DISPATCHED' || s === 'OUTCOME_UNKNOWN');
  const phasesOk = Array.isArray(job.steps) && job.steps.length === CONTROLLER_PHASES.length && job.steps.every((r, i) => r.phase === CONTROLLER_PHASES[i]);
  if (!phasesOk) notes.push('階段清單缺失或與 13 個固定階段不符；逐階段狀態不完整。');
  if (job.requiresReconciliation === null) notes.push('回應沒有明確說明是否需要核對。');
  if (job.requiresReapproval === null) notes.push('回應沒有明確說明是否需要重新批准。');
  if (job.state === 'OUTCOME_UNKNOWN' || job.requiresReconciliation === true || uncertainStep) {
    return { tone: 'red', label: '結果未知 · 需核對', complete: false, unresolved: true, notes };
  }
  if (job.requiresReapproval === true) return { tone: 'amber', label: '需要重新批准', complete: false, unresolved: true, notes };
  if (controllerJobCancelled(job)) return { tone: 'neutral', label: '已撤銷（未派發）', complete: false, unresolved: false, cancelled: true, notes };
  if (job.code === 'BOOTSTRAP_CANCELLED_BEFORE_DISPATCH') notes.push('回報已撤銷，但狀態、cancelledBeforeDispatch 或 effectsStarted 不一致，不能確認是「未派發即撤銷」。');
  switch (job.state) {
    case 'SUCCEEDED': {
      const dependency = phasesOk ? job.steps[CONTROLLER_PHASES.indexOf('DEPENDENCY_LINK')] : null;
      const depOk = dependencyOperationsComplete(dependency);
      if (phasesOk && !depOk) notes.push('依賴連線的子操作缺失、重複、順序／角色／nativeId 不符或未全部完成。');
      const allDone = phasesOk && depOk && stepStates.every((s) => s === 'SUCCEEDED');
      if (allDone && job.requiresReapproval === false && job.requiresReconciliation === false) return { tone: 'teal', label: '已完成（歷史記錄）', complete: true, unresolved: false, notes };
      notes.push('整體回報 SUCCEEDED，但階段或旗標不完整，不能確認已完成。');
      return { tone: 'amber', label: '回報完成 · 未能確認', complete: false, unresolved: true, notes };
    }
    case 'FAILED': return { tone: 'red', label: '失敗', complete: false, unresolved: false, notes };
    case 'QUEUED': return { tone: 'amber', label: '排隊中', complete: false, unresolved: false, notes };
    case 'RUNNING': return { tone: 'amber', label: '執行中', complete: false, unresolved: false, notes };
    case 'PREVIEW_ONLY': return { tone: 'neutral', label: '僅預覽 · 尚未接受', complete: false, unresolved: false, notes };
    default: return { tone: 'amber', label: job.state ? `未知狀態 ${job.state}` : '狀態缺失', complete: false, unresolved: true, notes };
  }
}
