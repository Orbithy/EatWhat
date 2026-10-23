// 同源 API 用戶端。
// - 中控 API 成功時直接回傳 JSON，不解包 code/data/msg。
// - 登入沿用既有業務 Result：成功 code 為 0（不是 200）。
// - 所有請求 credentials: 'omit'：不送出也不接收 cookie，權杖只經由記憶體中的 header 傳遞。
// - 部署執行僅提交已建立計畫的 UUID。

import {
  CLUSTER_BASE,
  CONTROL_BASE,
  CONTROL_REQUEST_TIMEOUT_MS,
  HEADER_NAME_RE,
  LOGIN_DEVICE,
  LOGIN_PATH,
  LOGIN_SUCCESS_CODE,
  LOGIN_TIMEOUT_MS,
  OBSERVATION_TIMEOUT_MS,
  REQUEST_TIMEOUT_MS,
} from './constants.js';
import {
  isClusterId,
  isClusterLimit,
  isReleaseId,
  isSiteId,
  isSuiteId,
  isUuid,
  isValidAuditLimit,
  validateChangeRequest,
  validateClusterFault,
  validateClusterPreview,
  validateFaultRequest,
  validateGcBegin,
  validatePlanRequest,
} from './validate.js';

export class ApiError extends Error {
  constructor({ kind, status = 0, code = null, timestamp = null, serverMessage = null, details = null }) {
    // message 只含分類與狀態碼，絕不包含請求內容或權杖。
    super(`${kind}${status ? ` ${status}` : ''}${code ? ` ${code}` : ''}`);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.code = code;
    this.timestamp = timestamp;
    this.serverMessage = serverMessage;
    this.details = details;
  }

  // 對寫入操作而言，這些錯誤代表伺服器端可能已執行，必須重新讀取狀態。
  get outcomeUnknown() {
    return (
      this.kind === 'network' ||
      this.kind === 'timeout' ||
      this.kind === 'aborted' ||
      this.kind === 'parse' ||
      this.code === 'NODE_REQUEST_FAILED' ||
      this.code === 'OUTCOME_UNKNOWN'
    );
  }
}

export function bearerValue(tokenValue) {
  if (typeof tokenValue !== 'string') throw new TypeError('權杖格式無效');
  const trimmed = tokenValue.trim();
  const bare = /^bearer(\s|$)/i.test(trimmed) ? trimmed.replace(/^bearer\s*/i, '') : trimmed;
  if (!bare || /[\s]/.test(bare)) throw new TypeError('權杖格式無效');
  return `Bearer ${bare}`;
}

export function isValidHeaderName(name) {
  if (typeof name !== 'string' || !HEADER_NAME_RE.test(name)) return false;
  const lower = name.toLowerCase();
  // 避免伺服器回傳的名稱覆寫基本 header。
  return !['content-type', 'accept', 'host', 'content-length', 'cookie', 'origin', 'referer'].includes(lower);
}

function pickString(value) {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return null;
}

// signal：外部中止（例如登出）。逾時與外部中止分開分類：timeout／aborted。
async function sendJson(fetchImpl, url, { method = 'GET', headers = {}, body, timeoutMs, signal }) {
  const controller = typeof AbortController === 'function' ? new AbortController() : null;
  let timedOut = false;
  const timer = controller
    ? setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, timeoutMs)
    : null;
  const onExternalAbort = () => controller && controller.abort();
  if (signal && controller) {
    if (signal.aborted) controller.abort();
    else signal.addEventListener('abort', onExternalAbort, { once: true });
  }
  const abortedKind = () => {
    if (!controller || !controller.signal.aborted) return null;
    if (timedOut) return 'timeout';
    return signal && signal.aborted ? 'aborted' : 'timeout';
  };
  const aborted = () => abortedKind() !== null;
  try {
    let response;
    try {
      response = await fetchImpl(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        referrerPolicy: 'no-referrer',
        signal: controller ? controller.signal : undefined,
      });
    } catch {
      throw new ApiError({ kind: aborted() ? abortedKind() : 'network' });
    }
    let text;
    try {
      text = await response.text();
    } catch {
      throw new ApiError({ kind: aborted() ? abortedKind() : 'network', status: response.status });
    }
    let payload = null;
    let parseFailed = false;
    if (text.trim().length > 0) {
      try {
        payload = JSON.parse(text);
      } catch {
        parseFailed = true;
      }
    }
    if (!response.ok) {
      const obj = payload && typeof payload === 'object' ? payload : {};
      throw new ApiError({
        kind: 'http',
        status: response.status,
        code: pickString(obj.code),
        timestamp: typeof obj.timestamp === 'string' ? obj.timestamp : null,
        serverMessage: typeof obj.msg === 'string' ? obj.msg : null,
      });
    }
    if (parseFailed) throw new ApiError({ kind: 'parse', status: response.status });
    return { status: response.status, data: payload };
  } finally {
    if (timer) clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onExternalAbort);
  }
}

function defaultFetch(...args) {
  return globalThis.fetch(...args);
}

export async function login({ username, password }, { fetchImpl = defaultFetch, timeoutMs = LOGIN_TIMEOUT_MS } = {}) {
  if (typeof username !== 'string' || !username || typeof password !== 'string' || !password) {
    throw new ApiError({ kind: 'validation', details: ['請輸入帳號與密碼。'] });
  }
  const { data } = await sendJson(fetchImpl, LOGIN_PATH, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: { username, password, device: LOGIN_DEVICE },
    timeoutMs,
  });
  if (!data || typeof data !== 'object') {
    throw new ApiError({ kind: 'protocol', serverMessage: '登入回應不是物件' });
  }
  if (data.code !== LOGIN_SUCCESS_CODE) {
    throw new ApiError({
      kind: 'business',
      code: pickString(data.code),
      serverMessage: typeof data.msg === 'string' ? data.msg : null,
    });
  }
  const tokenName = data.data?.tokenName;
  const tokenValue = data.data?.tokenValue;
  if (!isValidHeaderName(tokenName) || typeof tokenValue !== 'string' || !tokenValue.trim()) {
    throw new ApiError({ kind: 'protocol', serverMessage: '登入回應缺少有效的 tokenName / tokenValue' });
  }
  // 預先驗證可組成 header 值；失敗時丟出不含權杖的錯誤。
  try {
    bearerValue(tokenValue);
  } catch {
    throw new ApiError({ kind: 'protocol', serverMessage: 'tokenValue 格式無效' });
  }
  return { tokenName, tokenValue };
}

function rejectValidation(details) {
  return Promise.reject(new ApiError({ kind: 'validation', code: 'CLIENT_VALIDATION', details }));
}

async function authedCall({ session, fetchImpl, base, method, path, body, timeoutMs, signal }) {
  const auth = session ? session.authHeader() : null;
  if (!auth) throw new ApiError({ kind: 'auth', status: 401, code: 'NOT_LOGGED_IN' });
  const headers = { Accept: 'application/json', [auth.name]: auth.value };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const { data } = await sendJson(fetchImpl, `${base}${path}`, { method, headers, body, timeoutMs, signal });
  return data;
}

const SERVER_PROBE_TIMEOUT_MS = 30000;
const SERVER_REMOTE_TIMEOUT_MS = 60000;

function exactKeys(body, required, optional) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return false;
  const keys = Object.keys(body);
  return required.every((k) => keys.includes(k)) && keys.every((k) => required.includes(k) || optional.includes(k));
}

const SERVER_REQUIRED = ['requestId', 'name', 'location', 'host', 'port', 'username', 'authentication', 'hostKey', 'confirmedFingerprint'];

function validServerBody(body, update) {
  if (!exactKeys(body, update ? [...SERVER_REQUIRED, 'expectedRevision'] : SERVER_REQUIRED, ['credential', 'privilege', 'credentialId'])) return false;
  if ('privilege' in body && !['NONE', 'SUDO'].includes(body.privilege)) return false;
  // credentialId 引用中控已配置的私鑰：格式固定、只能配私鑰認證，且不能同時帶非空 credential。
  if ('credentialId' in body && (typeof body.credentialId !== 'string' || !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(body.credentialId)
    || body.authentication !== 'PRIVATE_KEY' || ('credential' in body && body.credential !== ''))) return false;
  if (!isUuid(body.requestId) || !Number.isInteger(body.port)) return false;
  if (!['REMOTE', 'CONTROL_HOST'].includes(body.location) || !['PASSWORD', 'PRIVATE_KEY'].includes(body.authentication)) return false;
  if (update && !Number.isInteger(body.expectedRevision)) return false;
  if ('credential' in body && (typeof body.credential !== 'string' || body.credential === '')) return false;
  return ['name', 'host', 'username', 'hostKey', 'confirmedFingerprint'].every((k) => typeof body[k] === 'string' && body[k] !== '');
}

// 中控＋業務組合安裝（controller-plans）。請求本文只允許契約欄位；儲存密鑰只在建立預覽這一次送出。
const CONTROLLER_PLAN_REQUIRED = ['id', 'sourceServerId', 'sourceRevision', 'targetServerId', 'targetRevision', 'releaseId', 'allocationSlot',
  'sourceAddress', 'targetAddress', 'targetEndpoint', 'wireguardPort', 'httpPort', 'appBaseUrl'];
const CONTROLLER_STORAGE_REQUIRED = ['provider', 'endpoint', 'region', 'bucket', 'accessKey', 'secretKey', 'pathStyle'];
const CONTROLLER_PREVIEW_TIMEOUT_MS = 120000; // 預覽會做較慢的唯讀 SSH 探測；逾時後只以原 UUID 查詢

function validControllerPlanBody(b) {
  if (!exactKeys(b, CONTROLLER_PLAN_REQUIRED, ['storage'])) return false;
  if (![b.id, b.sourceServerId, b.targetServerId].every(isUuid) || b.sourceServerId === b.targetServerId) return false;
  if (![b.sourceRevision, b.targetRevision].every((v) => Number.isSafeInteger(v) && v > 0)) return false;
  if (!Number.isInteger(b.allocationSlot) || b.allocationSlot < 1 || b.allocationSlot > 1023) return false;
  if (![b.wireguardPort, b.httpPort].every((v) => Number.isInteger(v) && v >= 1024 && v <= 65535)) return false;
  if (!['releaseId', 'sourceAddress', 'targetAddress', 'targetEndpoint', 'appBaseUrl'].every((k) => typeof b[k] === 'string' && b[k] !== '')) return false;
  if ('storage' in b && b.storage !== null) {
    const st = b.storage;
    if (!exactKeys(st, CONTROLLER_STORAGE_REQUIRED, ['publicBaseUrl', 'cdnEndpoint'])) return false;
    if (!['GENERIC', 'RUSTFS', 'R2'].includes(st.provider) || typeof st.pathStyle !== 'boolean') return false;
    if (!['endpoint', 'region', 'bucket', 'accessKey', 'secretKey'].every((k) => typeof st[k] === 'string' && st[k] !== '')) return false;
    if (st.provider === 'R2' && st.region !== 'auto') return false;
    if (['publicBaseUrl', 'cdnEndpoint'].some((k) => k in st && (typeof st[k] !== 'string' || st[k] === ''))) return false;
  }
  return true;
}

function validControllerApproval(b) {
  return exactKeys(b, ['requestHash', 'permissions'], []) && typeof b.requestHash === 'string' && /^[a-f0-9]{64}$/.test(b.requestHash)
    && Array.isArray(b.permissions) && b.permissions.length > 0 && b.permissions.every((p) => typeof p === 'string' && /^[A-Z][A-Z0-9_]{1,95}$/.test(p))
    && new Set(b.permissions).size === b.permissions.length;
}

function validInstallBody(body) {
  // 安裝身份：siteMode 與 allocationSlot 必須同時出現；CAMPUS 只能 0，EXTERNAL 為 1–1023。不接受 clusterId／siteId／instanceId。
  if (body && ('siteMode' in body || 'allocationSlot' in body)) {
    const okMode = body.siteMode === 'CAMPUS' ? body.allocationSlot === 0
      : body.siteMode === 'EXTERNAL' ? Number.isInteger(body.allocationSlot) && body.allocationSlot >= 1 && body.allocationSlot <= 1023 : false;
    if (!okMode) return false;
  }
  return exactKeys(body, ['expectedRevision', 'releaseId', 'httpPort', 'appBaseUrl', 'installDocker'], ['siteMode', 'allocationSlot'])
    && (('siteMode' in body) === ('allocationSlot' in body))
    && Number.isInteger(body.expectedRevision) && typeof body.releaseId === 'string' && body.releaseId !== ''
    && Number.isInteger(body.httpPort) && typeof body.appBaseUrl === 'string' && body.appBaseUrl !== ''
    && typeof body.installDocker === 'boolean';
}

export function createControlClient({ session, fetchImpl = defaultFetch, timeoutMs = CONTROL_REQUEST_TIMEOUT_MS } = {}) {
  function call(method, path, body, { signal, timeout } = {}) {
    return authedCall({ session, fetchImpl, base: CONTROL_BASE, method, path, body, timeoutMs: timeout ?? timeoutMs, signal });
  }

  return Object.freeze({
    getCapabilities: () => call('GET', '/capabilities'),
    getSites: () => call('GET', '/sites'),

    postFault(site, request) {
      const check = validateFaultRequest({ site, ...request });
      if (!check.ok) return rejectValidation(check.errors);
      const { channel, action, seconds } = check.value;
      return call('POST', `/sites/${encodeURIComponent(site)}/faults`, { channel, action, seconds });
    },

    getTestSuites: (options) => call('GET', '/test-suites', undefined, options),

    startTestRun(suite, options) {
      if (!isSuiteId(suite)) return rejectValidation(['只能執行契約定義的固定套件。']);
      return call('POST', '/test-runs', { suite }, options);
    },

    listTestRuns: (options) => call('GET', '/test-runs', undefined, options),

    getTestRun(id, options) {
      if (!isUuid(id)) return rejectValidation(['任務 ID 必須是 UUID。']);
      return call('GET', `/test-runs/${encodeURIComponent(id)}`, undefined, options);
    },

    cancelTestRun(id, options) {
      if (!isUuid(id)) return rejectValidation(['任務 ID 必須是 UUID。']);
      return call('POST', `/test-runs/${encodeURIComponent(id)}/cancel`, undefined, options);
    },

    listReleases: (options) => call('GET', '/releases', undefined, options),

    createDeploymentPlan(request) {
      const check = validatePlanRequest(request);
      if (!check.ok) return rejectValidation(check.errors);
      if (!isReleaseId(check.value.releaseId) || !check.value.sites.every(isSiteId)) {
        return rejectValidation(['計畫參數無效。']);
      }
      return call('POST', '/deployment-plans', check.value);
    },

    reconcileDeployment(id, options) {
      if (!isUuid(id)) return rejectValidation(['部署任務 ID 必須是 UUID。']);
      return call('POST', `/deployments/${encodeURIComponent(id)}/reconcile`, undefined, options);
    },

    getDeployment(id, options) {
      if (!isUuid(id)) return rejectValidation(['部署任務 ID 必須是 UUID。']);
      return call('GET', `/deployments/${encodeURIComponent(id)}`, undefined, options);
    },

    // 部署中斷後手動恢復舊版：預覽只帶新 requestId 與 siteId；執行只帶預覽中的完整舊 SHA。
    createDeploymentRecoveryPlan(jobId, body, options) {
      if (!isUuid(jobId) || !exactKeys(body, ['requestId', 'siteId'], []) || !isUuid(body.requestId) || !isClusterId(body.siteId)) {
        return rejectValidation(['恢復預覽只接受新 requestId 與站點 ID。']);
      }
      return call('POST', `/deployments/${encodeURIComponent(jobId)}/recovery-plans`, body, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },

    getDeploymentRecoveryPlan(id, options) {
      if (!isUuid(id)) return rejectValidation(['恢復 UUID 無效。']);
      return call('GET', `/deployment-recovery-plans/${encodeURIComponent(id)}`, undefined, options);
    },

    executeDeploymentRecoveryPlan(id, body, options) {
      if (!isUuid(id) || !exactKeys(body, ['restoreSha256'], []) || !/^[0-9a-f]{64}$/i.test(body.restoreSha256)) {
        return rejectValidation(['恢復執行只接受預覽中的完整舊版 SHA-256。']);
      }
      return call('POST', `/deployment-recovery-plans/${encodeURIComponent(id)}/execute`, body, options);
    },

    reconcileDeploymentRecoveryPlan(id, options) {
      if (!isUuid(id)) return rejectValidation(['恢復 UUID 無效。']);
      return call('POST', `/deployment-recovery-plans/${encodeURIComponent(id)}/reconcile`, undefined, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },

    getDeploymentPlan(id) {
      if (!isUuid(id)) return rejectValidation(['計畫 ID 必須是 UUID。']);
      return call('GET', `/deployment-plans/${encodeURIComponent(id)}`);
    },

    executeDeployment(id, options) {
      if (!isUuid(id)) return rejectValidation(['計畫 ID 必須是 UUID。']);
      return call('POST', `/deployment-plans/${encodeURIComponent(id)}/execute`, undefined, options);
    },

    getAudit(limit, options) {
      if (!isValidAuditLimit(limit)) return rejectValidation(['稽核筆數必須是 1–200 的整數。']);
      return call('GET', `/audit?limit=${limit}`, undefined, options);
    },

    // 只讀寫目前登入者自己的帳號；不接受 userId 或角色。
    getAccount: (options) => call('GET', '/account', undefined, options),
    changeCredentials(body, options) {
      const keys = body && typeof body === 'object' ? Object.keys(body) : [];
      const allowed = ['currentPassword', 'newUsername', 'newPassword'];
      if (!keys.includes('currentPassword') || keys.some((k) => !allowed.includes(k)) || keys.length < 2
        || keys.some((k) => typeof body[k] !== 'string' || body[k] === '')) {
        return rejectValidation(['帳號修改只能包含 currentPassword 與至少一個 newUsername／newPassword。']);
      }
      return call('POST', '/account/credentials', body, options);
    },

    // ---------- SSH 伺服器與首次安裝／更新（docs/architecture/control-servers-api.md） ----------
    // 後端固定目錄、指令與映像；瀏覽器只送契約欄位。遠端探測最多 45 秒，前端等候 60 秒。
    listServers: (options) => call('GET', '/servers', undefined, options),

    probeHostKey(body, options) {
      if (!exactKeys(body, ['host', 'port'], []) || typeof body.host !== 'string' || !Number.isInteger(body.port)) {
        return rejectValidation(['主機指紋探測只接受 host 與 port。']);
      }
      return call('POST', '/servers/host-key', body, { ...options, timeout: SERVER_PROBE_TIMEOUT_MS });
    },

    createServer(body, options) {
      if (!validServerBody(body, false)) return rejectValidation(['伺服器資料不符合契約。']);
      return call('POST', '/servers', body, options);
    },

    updateServer(id, body, options) {
      if (!isUuid(id) || !validServerBody(body, true)) return rejectValidation(['伺服器資料不符合契約。']);
      return call('PUT', `/servers/${encodeURIComponent(id)}`, body, options);
    },

    removeServer(id, expectedRevision, options) {
      if (!isUuid(id) || !Number.isInteger(expectedRevision)) return rejectValidation(['移除需要伺服器 UUID 與 revision。']);
      return call('DELETE', `/servers/${encodeURIComponent(id)}?expectedRevision=${expectedRevision}`, undefined, options);
    },

    testServer(id, expectedRevision, options) {
      if (!isUuid(id) || !Number.isInteger(expectedRevision)) return rejectValidation(['連線檢測需要伺服器 UUID 與 revision。']);
      return call('POST', `/servers/${encodeURIComponent(id)}/test`, { expectedRevision }, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },

    createServerPlan(id, kind, body, options) {
      if (!isUuid(id) || !['INSTALL', 'UPDATE'].includes(kind) || !validInstallBody(body)) return rejectValidation(['安裝計畫參數不符合契約。']);
      const path = kind === 'INSTALL' ? 'installation-plans' : 'update-plans';
      return call('POST', `/servers/${encodeURIComponent(id)}/${path}`, body, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },

    // 保留資料的首次安裝恢復：只送 expectedRevision 與來源任務 UUID；預覽與執行復用 server-plans。
    createRecoveryPlan(id, body, options) {
      if (!isUuid(id) || !exactKeys(body, ['expectedRevision', 'sourceJobId'], []) || !Number.isInteger(body.expectedRevision) || !isUuid(body.sourceJobId)) {
        return rejectValidation(['恢復計畫只接受 expectedRevision 與來源任務 UUID。']);
      }
      return call('POST', `/servers/${encodeURIComponent(id)}/recovery-plans`, body, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },

    // 任務歷史：只讀；查詢字串由固定欄位組成。
    // ---------- 中控＋業務組合安裝（docs/architecture/control-controller-bootstrap-api.md） ----------
    getControllerCapabilities(options) {
      return call('GET', '/controller-plans', undefined, options);
    },

    createControllerPlan(body, options) {
      if (!validControllerPlanBody(body)) return rejectValidation(['組合安裝預覽的輸入不完整或格式無效。']);
      return call('POST', '/controller-plans', body, { ...options, timeout: CONTROLLER_PREVIEW_TIMEOUT_MS });
    },

    getControllerPlan(id, options) {
      if (!isUuid(id)) return rejectValidation(['組合安裝預覽 ID 必須是 UUID。']);
      return call('GET', `/controller-plans/${encodeURIComponent(id)}`, undefined, options);
    },

    // 批准：只送原 requestHash 與使用者逐項確認的完整權限；成功 202 只代表已排隊。
    executeControllerPlan(id, approval, options) {
      if (!isUuid(id) || !validControllerApproval(approval)) return rejectValidation(['批准需要預覽 UUID、原 requestHash 與完整權限清單。']);
      return call('POST', `/controller-plans/${encodeURIComponent(id)}/execute`, approval, options);
    },

    // 中控＋業務安裝任務：只讀詳情（固定路徑，不跟隨列表回應的 detailRoute）。
    getControllerJob(id, options) {
      if (!isUuid(id)) return rejectValidation(['安裝任務 ID 必須是 UUID。']);
      return call('GET', `/controller-jobs/${encodeURIComponent(id)}`, undefined, options);
    },

    // 撤銷尚未派發的排隊任務：只送剛讀到的原 requestHash；成功 200 回傳原任務的新詳情。結果不確定時只重新讀取，不重送。
    cancelControllerJob(id, body, options) {
      if (!isUuid(id) || !exactKeys(body, ['requestHash'], []) || typeof body.requestHash !== 'string' || !/^[a-f0-9]{64}$/.test(body.requestHash)) {
        return rejectValidation(['撤銷需要任務 UUID 與原 requestHash。']);
      }
      return call('POST', `/controller-jobs/${encodeURIComponent(id)}/cancel`, body, options);
    },

    listTasks(query, options) {
      if (typeof query !== 'string' || !/^limit=\d+(&(type|state|serverId|cursor)=[^&#]*)*$/.test(query)) return rejectValidation(['任務查詢條件無效。']);
      return call('GET', `/tasks?${query}`, undefined, options);
    },

    getServerPlan(id, options) {
      if (!isUuid(id)) return rejectValidation(['計畫 ID 必須是 UUID。']);
      return call('GET', `/server-plans/${encodeURIComponent(id)}`, undefined, options);
    },

    executeServerPlan(id, options) {
      if (!isUuid(id)) return rejectValidation(['計畫 ID 必須是 UUID。']);
      return call('POST', `/server-plans/${encodeURIComponent(id)}/execute`, undefined, options);
    },

    getServerJob(id, options) {
      if (!isUuid(id)) return rejectValidation(['任務 ID 必須是 UUID。']);
      return call('GET', `/server-jobs/${encodeURIComponent(id)}`, undefined, options);
    },

    // ---------- 已安裝伺服器接入集群（docs/architecture/control-server-cluster-api.md） ----------
    // 只支援空集群首個中控本機校內權威站；execute 不確定時絕不自動重送，只以 GET 查回。
    createClusterPlan(id, expectedRevision, options) {
      if (!isUuid(id) || !Number.isInteger(expectedRevision)) return rejectValidation(['接入計畫需要伺服器 UUID 與 revision。']);
      return call('POST', `/servers/${encodeURIComponent(id)}/cluster-plans`, { expectedRevision }, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },

    // 成員設定失敗後恢復原設定：以失敗任務 UUID 建立新的恢復計畫（無本文）；結果不確定時重按只會建立新的計畫，不會執行任何變更。
    createMemberRecoveryPlan(failedId, options) {
      if (!isUuid(failedId)) return rejectValidation(['恢復計畫需要失敗任務 UUID。']);
      return call('POST', `/server-cluster-jobs/${encodeURIComponent(failedId)}/recovery-plans`, undefined, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },

    getClusterPlan(id, options) {
      if (!isUuid(id)) return rejectValidation(['接入計畫 ID 必須是 UUID。']);
      return call('GET', `/server-cluster-plans/${encodeURIComponent(id)}`, undefined, options);
    },

    executeClusterPlan(id, options) {
      if (!isUuid(id)) return rejectValidation(['接入計畫 ID 必須是 UUID。']);
      return call('POST', `/server-cluster-plans/${encodeURIComponent(id)}/execute`, undefined, options);
    },

    getClusterJob(id, options) {
      if (!isUuid(id)) return rejectValidation(['接入任務 ID 必須是 UUID。']);
      return call('GET', `/server-cluster-jobs/${encodeURIComponent(id)}`, undefined, options);
    },

    reconcileClusterJob(id, options) {
      if (!isUuid(id)) return rejectValidation(['接入任務 ID 必須是 UUID。']);
      return call('POST', `/server-cluster-jobs/${encodeURIComponent(id)}/reconcile`, undefined, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },

    reconcileServerJob(id, options) {
      if (!isUuid(id)) return rejectValidation(['任務 ID 必須是 UUID。']);
      return call('POST', `/server-jobs/${encodeURIComponent(id)}/reconcile`, undefined, { ...options, timeout: SERVER_REMOTE_TIMEOUT_MS });
    },
  });
}

// ---------- 多站叢集管理 API ----------
// 只包含 /admin/control/v1/cluster 下的管理接口。
// 不提供業務 consent API、任意 key／bucket、強制或豁免選項。

function siteSegment(site) {
  return encodeURIComponent(site);
}

export function createClusterClient({
  session,
  fetchImpl = defaultFetch,
  timeoutMs = CONTROL_REQUEST_TIMEOUT_MS,
  observationTimeoutMs = OBSERVATION_TIMEOUT_MS,
} = {}) {
  function call(method, path, body, { signal, timeout } = {}) {
    return authedCall({ session, fetchImpl, base: CLUSTER_BASE, method, path, body, timeoutMs: timeout ?? timeoutMs, signal });
  }

  function changeCall(site, action, request, options) {
    if (!isClusterId(site)) return rejectValidation(['站點 ID 格式無效。']);
    const check = validateChangeRequest(request);
    if (!check.ok) return rejectValidation(check.errors);
    return call('POST', `/sites/${siteSegment(site)}/${action}`, check.value, options);
  }

  function limitQuery(limit) {
    return isClusterLimit(limit) ? `limit=${limit}` : null;
  }

  return Object.freeze({
    observationTimeoutMs,

    getStatus: (options) => call('GET', '', undefined, options),
    getCapabilities: (options) => call('GET', '/capabilities', undefined, options),
    // 控制租約狀態；備機（standby）也可讀。
    getLeadership: (options) => call('GET', '/leadership', undefined, options),
    requestHandoff(request, options) {
      if (!isUuid(request?.requestId) || !isUuid(request?.targetControllerId) || !Number.isSafeInteger(request?.expectedGeneration) || request.expectedGeneration < 1) return rejectValidation(['交接需要有效的請求 UUID、目標程序 UUID 與 generation。']);
      return call('POST', '/leadership/handoffs', { requestId: request.requestId, targetControllerId: request.targetControllerId, expectedGeneration: request.expectedGeneration }, options);
    },
    getHandoff(id, options) {
      if (!isUuid(id)) return rejectValidation(['交接 ID 必須是 UUID。']);
      return call('GET', `/leadership/handoffs/${encodeURIComponent(id)}`, undefined, options);
    },
    cancelHandoff(id, options) {
      if (!isUuid(id)) return rejectValidation(['交接 ID 必須是 UUID。']);
      return call('POST', `/leadership/handoffs/${encodeURIComponent(id)}/cancel`, undefined, options);
    },
    getProfiles: (options) => call('GET', '/profiles', undefined, options),
    reloadProfiles: (options) => call('POST', '/profiles/reload', undefined, options),
    // 單一長請求；逾時與其他接口分開設定。
    getObservations: (options = {}) => call('GET', '/observations', undefined, { signal: options.signal, timeout: observationTimeoutMs }),
    getChannels: (options) => call('GET', '/channels', undefined, options),

    registerSite(request, options) {
      const check = validateChangeRequest(request);
      const errors = check.ok ? [] : [...check.errors];
      if (!isClusterId(request?.profileId)) errors.push('profileId 格式無效。');
      if (errors.length) return rejectValidation(errors);
      return call('POST', '/sites', { ...check.value, profileId: request.profileId }, options);
    },
    cancelSite: (site, request, options) => changeCall(site, 'cancel', request, options),
    joinSite: (site, request, options) => changeCall(site, 'join', request, options),
    retireSite: (site, request, options) => changeCall(site, 'retire', request, options),

    listChanges(limit, options) {
      const q = limitQuery(limit);
      if (!q) return rejectValidation(['limit 必須是 1–100 的整數。']);
      return call('GET', `/changes?${q}`, undefined, options);
    },
    getChange(id, options) {
      if (!isUuid(id)) return rejectValidation(['變更 ID 必須是 UUID。']);
      return call('GET', `/changes/${encodeURIComponent(id)}`, undefined, options);
    },
    resumeChange(id, options) {
      if (!isUuid(id)) return rejectValidation(['變更 ID 必須是 UUID。']);
      return call('POST', `/changes/${encodeURIComponent(id)}/resume`, undefined, options);
    },

    // 健康檢測（docs/architecture/control-health-api.md）：只讀檢測備機也可；存儲讀寫探針只限主中控（後端另行把關）。
    // 權威發布約束診斷：只讀。
    getDeploymentFences: (options) => call('GET', '/deployment-fences', undefined, options),

    startHealthCheck(site, requestId, options) {
      if (!isClusterId(site) || !isUuid(requestId)) return rejectValidation(['健康檢測需要站點 ID 與請求 UUID。']);
      return call('POST', `/sites/${siteSegment(site)}/health-checks`, { requestId }, options);
    },

    startStorageCheck(site, requestId, options) {
      if (!isClusterId(site) || !isUuid(requestId)) return rejectValidation(['存儲讀寫驗證需要站點 ID 與請求 UUID。']);
      return call('POST', `/sites/${siteSegment(site)}/storage-checks`, { requestId }, options);
    },

    postFault(site, request, options) {
      const check = validateClusterFault({ site, ...request });
      if (!check.ok) return rejectValidation(check.errors);
      const { channel, action, seconds } = check.value;
      return call('POST', `/sites/${siteSegment(site)}/faults`, { channel, action, seconds }, options);
    },

    listGc({ limit, pendingOnly } = {}, options) {
      const q = limitQuery(limit);
      if (!q || typeof pendingOnly !== 'boolean') return rejectValidation(['limit 必須是 1–100，pendingOnly 必須是布林值。']);
      return call('GET', `/gc?${q}&pendingOnly=${pendingOnly}`, undefined, options);
    },
    listGcCandidates(limit, options) {
      const q = limitQuery(limit);
      if (!q) return rejectValidation(['limit 必須是 1–100 的整數。']);
      return call('GET', `/gc/candidates?${q}`, undefined, options);
    },
    getGc(id, options) {
      if (!isUuid(id)) return rejectValidation(['GC 操作 ID 必須是 UUID。']);
      return call('GET', `/gc/${encodeURIComponent(id)}`, undefined, options);
    },
    beginGc(request, options) {
      const check = validateGcBegin(request);
      if (!check.ok) return rejectValidation(check.errors);
      return call('POST', '/gc', check.value, options);
    },
    resumeGc(id, options) {
      if (!isUuid(id)) return rejectValidation(['GC 操作 ID 必須是 UUID。']);
      return call('POST', `/gc/${encodeURIComponent(id)}/resume`, undefined, options);
    },

    createPreview(request, options) {
      const check = validateClusterPreview(request);
      if (!check.ok) return rejectValidation(check.errors);
      return call('POST', '/deployment-plans', check.value, options);
    },
    getPreview(id, options) {
      if (!isUuid(id)) return rejectValidation(['預覽 ID 必須是 UUID。']);
      return call('GET', `/deployment-plans/${encodeURIComponent(id)}`, undefined, options);
    },

    executeDeployment(id, options) {
      if (!isUuid(id)) return rejectValidation(['計畫 ID 必須是 UUID。']);
      return call('POST', `/deployment-plans/${encodeURIComponent(id)}/execute`, undefined, options);
    },

    getAudit(limit, options) {
      const q = limitQuery(limit);
      if (!q) return rejectValidation(['limit 必須是 1–100 的整數。']);
      return call('GET', `/audit?${q}`, undefined, options);
    },
  });
}
