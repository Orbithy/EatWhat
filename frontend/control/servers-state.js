// SSH 伺服器登記、首次安裝與版本更新：純驗證、正規化與文字（可在 Node 測試）。
// 契約見 docs/architecture/control-servers-api.md。憑據只在送出時讀取，這裡不保存任何秘密。

export const LOCATIONS = Object.freeze(['REMOTE', 'CONTROL_HOST']);
export const AUTHENTICATIONS = Object.freeze(['PASSWORD', 'PRIVATE_KEY']);
export const CONTROL_HOST_ADDRESS = '127.0.0.1';
export const DEFAULT_HTTP_PORT = 18090;
export const RESERVED_HTTP_PORTS = Object.freeze([18080, 18081]);
export const SERVER_NAME_MAX = 64;
export const PASSWORD_MAX = 256;
export const PRIVATE_KEY_MAX_BYTES = 16 * 1024;
export const SSH_USERNAME_RE = /^[A-Za-z_][A-Za-z0-9_.@-]{0,127}$/;
export const PYTHON_MIN = [3, 10];

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;
const encoder = new TextEncoder();

export const isServerUuid = (v) => typeof v === 'string' && UUID_RE.test(v);
const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
const int = (v) => (Number.isInteger(v) ? v : null);

// ---------- 連線資料 ----------

export function nameProblem(name) {
  const trimmed = typeof name === 'string' ? name.trim() : '';
  if (!trimmed) return '請輸入名稱。';
  if ([...trimmed].length > SERVER_NAME_MAX) return `名稱最多 ${SERVER_NAME_MAX} 個字元。`;
  if (CONTROL_CHARS.test(trimmed)) return '名稱不能包含控制字元。';
  return null;
}

const IPV4_RE = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;
const HOSTNAME_RE = /^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\.?$/;
const IPV6_RE = /^[0-9A-Fa-f:.]+$/;

export function hostProblem(host) {
  if (typeof host !== 'string' || host === '') return '請輸入主機。';
  if (host !== host.trim()) return '主機前後不能有空白。';
  if (/[/?#@\s[\]]/.test(host) || host.includes('://')) return '主機只填主機名稱或 IP，不含協定、使用者、路徑或參數。';
  if (host.includes(':')) return IPV6_RE.test(host) && (host.match(/:/g) ?? []).length >= 2 ? null : 'IPv6 位址格式無效。';
  if (/^[\d.]+$/.test(host)) return IPV4_RE.test(host) ? null : 'IPv4 位址格式無效。';
  return HOSTNAME_RE.test(host) ? null : '主機名稱格式無效。';
}

export function parsePort(value) {
  if (typeof value === 'number') return Number.isInteger(value) ? value : null;
  if (typeof value !== 'string' || !/^\d{1,5}$/.test(value.trim())) return null;
  return Number(value.trim());
}

export function portProblem(value) {
  const port = parsePort(value);
  return port !== null && port >= 1 && port <= 65535 ? null : 'SSH 埠需為 1–65535 的整數。';
}

export function usernameProblem(username) {
  return typeof username === 'string' && SSH_USERNAME_RE.test(username) ? null : 'SSH 使用者需以英文字母或底線開頭，只能使用英文字母、數字與 _ . @ -，最多 128 個字元。';
}

// OpenSSH 新格式私鑰：openssh-key-v1\0 之後是 cipher 名稱；none 代表沒有保護密碼。
export function privateKeyEncryption(key) {
  if (/ENCRYPTED/.test(key)) return 'encrypted';
  const m = key.match(/-----BEGIN OPENSSH PRIVATE KEY-----([\s\S]*?)-----END OPENSSH PRIVATE KEY-----/);
  if (!m) return 'unknown';
  try {
    const bin = atob(m[1].replace(/\s+/g, ''));
    const magic = 'openssh-key-v1\0';
    if (!bin.startsWith(magic)) return 'unknown';
    const at = magic.length;
    const len = ((bin.charCodeAt(at) << 24) | (bin.charCodeAt(at + 1) << 16) | (bin.charCodeAt(at + 2) << 8) | bin.charCodeAt(at + 3)) >>> 0;
    if (len > 64) return 'unknown';
    return bin.slice(at + 4, at + 4 + len) === 'none' ? 'none' : 'encrypted';
  } catch {
    return 'unknown';
  }
}

export function credentialProblem(authentication, credential) {
  if (authentication === 'PASSWORD') {
    if ([...credential].length > PASSWORD_MAX) return `SSH 密碼最多 ${PASSWORD_MAX} 個字元。`;
    if (/[\r\n\0]/.test(credential)) return 'SSH 密碼不能包含換行或 NUL 字元。';
    return null;
  }
  if (encoder.encode(credential).length > PRIVATE_KEY_MAX_BYTES) return '私鑰不能超過 16 KiB。';
  if (!/-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/.test(credential)) return '請貼上完整的私鑰（含 BEGIN／END 行）。';
  if (privateKeyEncryption(credential) === 'encrypted') return '目前只支援沒有保護密碼的私鑰。';
  return null;
}

// 送出時位置為中控本機一律使用 127.0.0.1。
export function effectiveHost(form) {
  return form.location === 'CONTROL_HOST' ? CONTROL_HOST_ADDRESS : form.host;
}

// 新增、或修改位置／主機／埠／使用者時，必須重新取得並核對主機指紋。
export function addressSignature(form) {
  return JSON.stringify([form.location, effectiveHost(form), parsePort(form.port), form.username]);
}

export function needsHostKeyCheck(form, server) {
  if (!server) return true;
  return addressSignature(form) !== addressSignature({ location: server.location, host: server.host, port: server.port, username: server.username });
}

// ---------- SSH 執行權限（privilege：NONE／SUDO） ----------
// SUDO 只表示選擇以 sudo -n 執行；實際是否具 root 權限以連線檢測與後端預檢為準。不傳 sudo 密碼。

export const SSH_PRIVILEGES = Object.freeze(['NONE', 'SUDO']);
export const PRIVILEGE_LABEL = Object.freeze({ NONE: '直接執行', SUDO: '免密 sudo' });

// 只有清單 sshPrivileges 是陣列且含 SUDO 才能選免密 sudo；缺欄位（舊後端）或格式錯只允許直接執行，並不送 privilege。
export function privilegeCapability(list) {
  const p = list?.sshPrivileges;
  if (p === undefined) return { supported: false, sudo: false, reason: null };
  if (!Array.isArray(p) || !p.includes('NONE')) return { supported: false, sudo: false, reason: '後端回報的執行權限能力無效，只允許直接執行。' };
  return { supported: true, sudo: p.includes('SUDO'), reason: p.includes('SUDO') ? null : '後端未提供免密 sudo，只允許直接執行。' };
}

// 卡片與確認框顯示的連線：實際使用者＋執行方式。
export function sshTarget(server) {
  const base = `${server?.username ?? '?'}@${server?.host ?? '?'}:${server?.port ?? '?'}`;
  if (server?.privilege === 'SUDO') return `${base}（免密 sudo）`;
  if (server?.privilege === 'UNKNOWN') return `${base}（執行權限未知）`;
  return base;
}

// root 前置條件：root 帳號，或後端宣告支援且已選擇免密 sudo。後端預檢仍是最終判斷。
export function rootCapable(server, list) {
  if (server?.username === 'root') return true;
  return server?.privilege === 'SUDO' && privilegeCapability(list).sudo;
}

// ---------- 中控已配置的專用密鑰（credentialId） ----------
// 清單 sshCredentials 只含 id／label／authentication；私鑰與路徑留在中控伺服器，不經過瀏覽器。

export const CREDENTIAL_ID_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
export const CREDENTIAL_SOURCES = Object.freeze(['MANUAL', 'OPERATOR_INVENTORY']);

// 缺欄位（舊後端）＝不提供，請求完全不變；格式錯、重複 id、未知 authentication＝整體停用，不猜選。
export function credentialInventoryCapability(list) {
  const raw = list?.sshCredentials;
  if (raw === undefined) return { supported: false, options: [], reason: null };
  const invalid = { supported: false, options: [], reason: '後端回報的已配置密鑰清單格式無效，暫不能選擇；手動輸入不受影響。' };
  if (!Array.isArray(raw)) return invalid;
  const seen = new Set();
  const options = [];
  for (const o of raw) {
    if (!o || typeof o !== 'object' || Array.isArray(o) || typeof o.id !== 'string' || !CREDENTIAL_ID_PATTERN.test(o.id)
      || typeof o.label !== 'string' || !o.label.trim() || o.label.length > 200 || o.authentication !== 'PRIVATE_KEY' || seen.has(o.id)) return invalid;
    seen.add(o.id);
    options.push({ id: o.id, label: o.label.trim(), authentication: 'PRIVATE_KEY' });
  }
  return { supported: true, options, reason: options.length ? null : '中控尚未配置可用的專用密鑰。' };
}

export function credentialOption(cap, id) {
  return cap?.supported ? cap.options.find((o) => o.id === id) ?? null : null;
}

// 卡片與確認框的來源說明：只顯示 label／id，不顯示內容或路徑；未知來源如實呈現。
export function credentialSourceText(server, cap = null) {
  const src = server?.credentialSource;
  if (src === null || src === undefined) return null;
  if (src === 'MANUAL') return '手動輸入的憑據';
  if (src === 'OPERATOR_INVENTORY') {
    if (!server.credentialId) return '中控已配置的專用密鑰（未提供識別碼）';
    const opt = credentialOption(cap, server.credentialId);
    return opt ? `中控已配置的專用密鑰：${opt.label}（${server.credentialId}）` : `中控已配置的專用密鑰（${server.credentialId}）`;
  }
  return `憑據來源未知（${src}）`;
}

// form：{ name, location, host, port, username, authentication, credential, probe, keyIndex, confirmed, forceHostKey, privilege, privilegeCap, credentialSource, credentialId, inventoryCap }
// probe：{ signature, keys }；editing 時 server 為目前登記。
export function serverFormCheck(form, server = null) {
  const errors = [];
  const push = (p) => { if (p) errors.push(p); };
  push(nameProblem(form.name));
  if (!LOCATIONS.includes(form.location)) errors.push('請選擇位置。');
  push(hostProblem(effectiveHost(form)));
  push(portProblem(form.port));
  push(usernameProblem(form.username));
  if (!AUTHENTICATIONS.includes(form.authentication)) errors.push('請選擇認證方式。');
  const privilege = form.privilege ?? 'NONE';
  if (!SSH_PRIVILEGES.includes(privilege)) errors.push('請選擇執行權限。');
  else if (privilege === 'SUDO' && !form.privilegeCap?.sudo) errors.push('後端未宣告支援免密 sudo，只能直接執行。');
  const credential = form.credential ?? '';
  const authChanged = server && server.authentication !== form.authentication;
  const source = form.credentialSource ?? 'MANUAL';
  if (!CREDENTIAL_SOURCES.includes(source)) errors.push('請選擇憑據來源。');
  else if (source === 'OPERATOR_INVENTORY') {
    // 引用模式只看 credentialId；手動欄位內容不檢查也不送出。
    if (!form.inventoryCap?.supported) errors.push(form.inventoryCap?.reason ?? '這個中控後端不支援已配置的專用密鑰。');
    else if (!form.credentialId) errors.push(form.inventoryCap.options.length ? '請選擇要使用的專用密鑰。' : form.inventoryCap.reason);
    else if (!credentialOption(form.inventoryCap, form.credentialId)) errors.push('選擇的專用密鑰已不在中控清單中，請重新選擇。');
    if (form.authentication !== 'PRIVATE_KEY') errors.push('已配置的專用密鑰只能使用私鑰認證。');
  } else if (credential === '') {
    if (!server || !server.hasCredential) errors.push(form.authentication === 'PRIVATE_KEY' ? '請貼上私鑰。' : '請輸入 SSH 密碼。');
    else if (authChanged) errors.push('改變認證方式時必須輸入新的憑據。');
  } else if (AUTHENTICATIONS.includes(form.authentication)) push(credentialProblem(form.authentication, credential));
  if (server?.installation && (form.location !== server.location || effectiveHost(form) !== server.host || parsePort(form.port) !== server.port)) {
    errors.push('已安裝的伺服器不能修改位置、主機或埠。');
  }
  const hostKey = selectedHostKey(form, server);
  if (!hostKey) errors.push(needsHostKeyCheck(form, server) || form.forceHostKey ? '請取得主機指紋並選擇要信任的金鑰。' : '缺少已核對的主機指紋。');
  else if (hostKey.fresh && !form.confirmed) errors.push('請確認你已在伺服器上獨立核對主機指紋。');
  return { errors, hostKey };
}

export function selectedHostKey(form, server) {
  const probeValid = form.probe && form.probe.signature === addressSignature(form);
  if (probeValid) {
    const key = form.probe.keys[form.keyIndex];
    return key ? { ...key, fresh: true } : null;
  }
  if (server && !form.forceHostKey && !needsHostKeyCheck(form, server) && server.hostKey) return { ...server.hostKey, fresh: false };
  return null;
}

export function serverRequestBody(form, { server = null, requestId }) {
  const { errors, hostKey } = serverFormCheck(form, server);
  if (errors.length) return null;
  const body = {
    requestId,
    name: form.name.trim(),
    location: form.location,
    host: effectiveHost(form),
    port: parsePort(form.port),
    username: form.username,
    authentication: form.authentication,
    hostKey: hostKey.publicKey,
    confirmedFingerprint: hostKey.fingerprint,
  };
  if (server) body.expectedRevision = server.revision;
  // 引用中控密鑰時只送 credentialId，絕不同時送 credential；手動模式與舊後端維持原契約。
  if ((form.credentialSource ?? 'MANUAL') === 'OPERATOR_INVENTORY') body.credentialId = form.credentialId;
  else if (form.credential) body.credential = form.credential;
  // 後端宣告支援時一律明確送出（預設 NONE，不擴權）；舊後端不送此欄位。
  if (form.privilegeCap?.supported) body.privilege = form.privilege ?? 'NONE';
  return body;
}

// 只比對非機密欄位：結果未知後若內容相同，沿用同一 requestId，避免重複登記。
export function serverIntentSignature(body) {
  if (!body) return null;
  const { credential, requestId, ...rest } = body;
  return JSON.stringify({ ...rest, hasCredential: Boolean(credential) });
}

export function normalizeHostKeys(data) {
  const keys = Array.isArray(data?.keys) ? data.keys : [];
  return keys
    .filter((k) => k && typeof k.publicKey === 'string' && k.publicKey && typeof k.fingerprint === 'string' && k.fingerprint)
    .map((k) => ({ publicKey: k.publicKey, type: str(k.type) ?? '未提供類型', fingerprint: k.fingerprint }));
}

// ---------- 安裝／更新輸入 ----------

export function httpPortProblem(value) {
  const port = parsePort(value);
  if (port === null || port < 1024 || port > 65535) return '業務 HTTP 埠需為 1024–65535 的整數。';
  if (RESERVED_HTTP_PORTS.includes(port)) return `業務 HTTP 埠不能使用 ${RESERVED_HTTP_PORTS.join('、')}。`;
  return null;
}

export function appBaseUrlProblem(value) {
  if (typeof value !== 'string' || value.trim() === '') return '請輸入業務站對外網址。';
  if (/[?#]/.test(value)) return '對外網址不能包含查詢參數或錨點。';
  let url;
  try { url = new URL(value); } catch { return '對外網址格式無效，需以 http 或 https 開頭。'; }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return '對外網址只能使用 http 或 https。';
  if (url.username || url.password) return '對外網址不能包含帳號或密碼。';
  return null;
}

// ---------- 安裝身份（docs/architecture/control-installation-roles.md） ----------

export const SITE_MODES = Object.freeze(['CAMPUS', 'EXTERNAL']);
export const SITE_MODE_LABEL = Object.freeze({ CAMPUS: '校內權威站', EXTERNAL: '校外業務站' });
export const EXTERNAL_SLOT_MIN = 1;
export const EXTERNAL_SLOT_MAX = 1023;

// 只有清單同時回報 installationModes（陣列）與布林 installationIdentityRequired 才使用新欄位；
// 兩者都缺是舊後端（沿用舊契約）；只缺一個或格式不對是「能力未知」，身份欄位停用並說明。
export function identityCapability(list) {
  const modes = list?.installationModes;
  const required = list?.installationIdentityRequired;
  if (modes === undefined && required === undefined) return { supported: false, unknown: false, required: false, modes: [], reason: null };
  if (!Array.isArray(modes) || typeof required !== 'boolean') {
    return { supported: false, unknown: true, required: false, modes: [], reason: '身份能力未知：後端未完整回報 installationModes／installationIdentityRequired，暫停建立安裝計畫。' };
  }
  const known = SITE_MODES.filter((m) => modes.includes(m));
  if (!known.length) return { supported: false, unknown: true, required, modes: [], reason: '身份能力未知：後端回報的安裝角色都無法識別，暫停建立安裝計畫。' };
  return { supported: true, unknown: false, required, modes: known, reason: null };
}

export function identityProblems(form, cap) {
  // 能力未知（只缺一欄或格式錯）不能退回舊契約送出；只有兩欄都不存在的舊後端才沿用舊請求。
  if (cap.unknown) return [cap.reason];
  if (!cap.supported) return [];
  const mode = form.siteMode ?? '';
  if (mode === '') return cap.required ? ['請選擇校內權威站或校外業務站。'] : [];
  if (!cap.modes.includes(mode)) return [`後端不支援「${SITE_MODE_LABEL[mode] ?? mode}」。`];
  if (mode === 'EXTERNAL') {
    const slot = parsePort(form.allocationSlot);
    if (slot === null || slot < EXTERNAL_SLOT_MIN || slot > EXTERNAL_SLOT_MAX) return [`校外業務站的分配編號需為 ${EXTERNAL_SLOT_MIN}–${EXTERNAL_SLOT_MAX} 的整數。`];
  }
  return [];
}

export function installFormCheck(form, { kind, releases, identity = null }) {
  const errors = [];
  if (!releases.some((r) => r.id === form.releaseId)) errors.push('請選擇版本。');
  if (kind === 'INSTALL') {
    const p = httpPortProblem(form.httpPort);
    if (p) errors.push(p);
    const u = appBaseUrlProblem(form.appBaseUrl);
    if (u) errors.push(u);
    if (identity) errors.push(...identityProblems(form, identity));
  }
  return errors;
}

// UPDATE 沿用 installation 的 httpPort／appBaseUrl。
export function installRequestBody(form, { kind, server, releases, identity = null }) {
  if (installFormCheck(form, { kind, releases, identity }).length) return null;
  if (kind === 'UPDATE') {
    const inst = server.installation;
    return { expectedRevision: server.revision, releaseId: form.releaseId, httpPort: inst.httpPort, appBaseUrl: inst.appBaseUrl, installDocker: false };
  }
  const body = { expectedRevision: server.revision, releaseId: form.releaseId, httpPort: parsePort(form.httpPort), appBaseUrl: form.appBaseUrl.trim(), installDocker: form.installDocker === true };
  // 新欄位只在後端證明支援時送出；CAMPUS 固定分配 0，EXTERNAL 使用明確選擇的 1–1023。
  if (identity?.supported && SITE_MODES.includes(form.siteMode)) {
    body.siteMode = form.siteMode;
    body.allocationSlot = form.siteMode === 'CAMPUS' ? 0 : parsePort(form.allocationSlot);
  }
  return body;
}

// ---------- 回應正規化 ----------

function normalizeConnection(c) {
  const o = c && typeof c === 'object' ? c : {};
  return {
    state: ['NOT_TESTED', 'CONNECTED', 'FAILED'].includes(o.state) ? o.state : 'NOT_TESTED',
    checkedAt: str(o.checkedAt), code: str(o.code),
    linux: o.linux ?? null, python: o.python ?? null, docker: o.docker ?? null, compose: o.compose ?? null,
    dockerAccess: typeof o.dockerAccess === 'boolean' ? o.dockerAccess : null,
    root: typeof o.root === 'boolean' ? o.root : null,
    memoryMiB: Number.isFinite(o.memoryMiB) ? o.memoryMiB : null,
    diskFreeMiB: Number.isFinite(o.diskFreeMiB) ? o.diskFreeMiB : null,
  };
}

// NOT_INSTALLED（含資料庫啟動前失敗並已清理）視為未安裝；FAILED 等其他狀態保留，不能當作沒安裝過。
function normalizeInstallation(i) {
  if (!i || typeof i !== 'object' || typeof i.state !== 'string' || i.state === 'NOT_INSTALLED') return null;
  return {
    state: i.state, siteId: str(i.siteId), instanceId: str(i.instanceId), httpPort: int(i.httpPort), root: str(i.root),
    restoreEvidenceId: str(i.restoreEvidenceId), currentSha256: str(i.currentSha256), appBaseUrl: str(i.appBaseUrl),
    // 缺少 clusterMembership 視為「未確定」，不補成 NOT_JOINED。
    jobId: str(i.jobId), clusterMembership: str(i.clusterMembership),
    // 安裝身份：舊安裝沒有 mode（沿用 CAMPUS 獨立站）；未知值保留原字串，畫面停用相關操作。
    mode: str(i.mode), clusterId: str(i.clusterId), allocationSlot: Number.isInteger(i.allocationSlot) ? i.allocationSlot : null,
  };
}

export function normalizeServer(s) {
  const base = normalizeServerBase(s);
  if (base) base.clusterJobId = isServerUuid(s.clusterJobId) ? s.clusterJobId : null;
  return base;
}

function normalizeServerBase(s) {
  if (!s || typeof s !== 'object' || !isServerUuid(s.id) || typeof s.name !== 'string' || int(s.revision) === null) return null;
  const hk = s.hostKey && typeof s.hostKey === 'object' && typeof s.hostKey.publicKey === 'string' && typeof s.hostKey.fingerprint === 'string'
    ? { publicKey: s.hostKey.publicKey, type: str(s.hostKey.type) ?? '未提供類型', fingerprint: s.hostKey.fingerprint } : null;
  return {
    id: s.id, name: s.name,
    location: LOCATIONS.includes(s.location) ? s.location : 'REMOTE',
    host: str(s.host) ?? '', port: int(s.port), username: str(s.username) ?? '',
    authentication: AUTHENTICATIONS.includes(s.authentication) ? s.authentication : null,
    hasCredential: s.hasCredential === true,
    // 憑據來源：缺欄位（舊後端）為 null 不顯示；已知值照用；其他字串如實保留。credentialId 只接受合法格式。
    credentialSource: typeof s.credentialSource === 'string' && s.credentialSource ? s.credentialSource : null,
    credentialId: typeof s.credentialId === 'string' && CREDENTIAL_ID_PATTERN.test(s.credentialId) ? s.credentialId : null,
    // 省略視為 NONE（舊記錄）；未知值保留為 UNKNOWN，不視為具權限。
    privilege: s.privilege === undefined || s.privilege === null ? 'NONE' : SSH_PRIVILEGES.includes(s.privilege) ? s.privilege : 'UNKNOWN',
    hostKey: hk, revision: s.revision,
    connection: normalizeConnection(s.connection),
    installation: normalizeInstallation(s.installation),
    createdAt: str(s.createdAt), updatedAt: str(s.updatedAt),
  };
}

export function normalizeServerList(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.servers)) return null;
  const servers = data.servers.map(normalizeServer).filter(Boolean);
  return {
    servers,
    dropped: data.servers.length - servers.length,
    canManage: data.canManage === true,
    reason: str(data.reason),
    installationEnabled: data.installationEnabled === true,
    clusterEnrollmentEnabled: data.clusterEnrollmentEnabled === true,
    // 只有嚴格 true 才開放校外業務站接入；缺欄位是舊後端。
    remoteMemberEnrollmentSupported: data.remoteMemberEnrollmentSupported === true,
    sshPrivileges: data.sshPrivileges,
    sshCredentials: data.sshCredentials,
    installationModes: data.installationModes,
    installationIdentityRequired: data.installationIdentityRequired,
    // 清單可能在頂層或個別伺服器提供最近的接入任務 UUID，用於重新登入後查回。
    clusterJobId: isServerUuid(data.clusterJobId) ? data.clusterJobId : servers.find((s) => s.clusterJobId)?.clusterJobId ?? null,
    releases: (Array.isArray(data.releases) ? data.releases : [])
      .filter((r) => r && typeof r.id === 'string' && r.id)
      .map((r) => ({ id: r.id, sha256: str(r.sha256), acceptanceEvidenceId: str(r.acceptanceEvidenceId) })),
  };
}

export function normalizePlan(p) {
  if (!p || typeof p !== 'object' || !isServerUuid(p.id) || !['INSTALL', 'UPDATE', 'RECOVER'].includes(p.kind)) return null;
  return {
    id: p.id, kind: p.kind, serverId: str(p.serverId), serverRevision: p.serverRevision ?? null,
    releaseId: str(p.releaseId), artifactSha256: str(p.artifactSha256), fromSha256: str(p.fromSha256),
    httpPort: int(p.httpPort), appBaseUrl: str(p.appBaseUrl), root: str(p.root),
    services: Array.isArray(p.services) ? p.services.filter((x) => typeof x === 'string') : [],
    sameMachine: p.sameMachine === true, clusterMembership: str(p.clusterMembership) ?? 'NOT_JOINED',
    expiresAt: str(p.expiresAt), installDocker: p.installDocker === true,
    state: p.state === 'READY' ? 'READY' : 'BLOCKED',
    blockers: Array.isArray(p.blockers) ? p.blockers.map((b) => (typeof b === 'string' ? b : String(b?.code ?? JSON.stringify(b)))) : [],
    storage: p.storage && typeof p.storage === 'object' ? { endpoint: str(p.storage.endpoint), bucket: str(p.storage.bucket) } : null,
    administratorUsername: str(p.administratorUsername),
    environment: p.environment && typeof p.environment === 'object' ? p.environment : null,
    mode: str(p.mode), clusterId: str(p.clusterId), allocationSlot: Number.isInteger(p.allocationSlot) ? p.allocationSlot : null,
    // RECOVER 專用欄位
    sourceJobId: isServerUuid(p.sourceJobId) ? p.sourceJobId : null,
    originalInstallJobId: isServerUuid(p.originalInstallJobId) ? p.originalInstallJobId : null,
    siteId: str(p.siteId), instanceId: str(p.instanceId), sourceStage: str(p.sourceStage),
    dataPreserved: p.dataPreserved === true, passwordsPreserved: p.passwordsPreserved === true,
    administratorAction: str(p.administratorAction),
  };
}

// ---------- 版本更新與恢復安裝的可用性 ----------

// 舊單機 UPDATE 只適用於明確 NOT_JOINED 的已安裝伺服器；已加入或未確定都需要集群部署。
export function updateAvailability(server) {
  const inst = server?.installation;
  if (!inst || inst.state !== 'SUCCEEDED') return { show: false, reasons: [] };
  if (inst.mode && !SITE_MODES.includes(inst.mode)) return { show: true, reasons: [`安裝身份未知（${inst.mode}），停用版本更新`] };
  if (inst.clusterId) return { show: true, reasons: ['已分配站點須完成集群接入與發布'], code: 'ALLOCATED_SITE_MEMBERSHIP_REQUIRED' };
  if (inst.clusterMembership !== 'NOT_JOINED') return { show: true, reasons: ['需使用集群部署'], code: 'JOINED_SERVER_CLUSTER_DEPLOYMENT_REQUIRED' };
  return { show: true, reasons: [] };
}

// 恢復安裝：只有安裝狀態 FAILED、有來源任務，且讀回的來源任務是已核實 FAILED 的 INSTALL／RECOVER。
// source：{ loading, error, job }。OUTCOME_UNKNOWN 只提供核對。
export function recoveryAvailability(server, source) {
  const inst = server?.installation;
  if (!inst || inst.state !== 'FAILED') return { mode: 'none', reasons: [] };
  if (!isServerUuid(inst.jobId)) return { mode: 'blocked', reasons: ['缺少來源安裝任務 UUID，無法恢復。'] };
  if (inst.mode && !SITE_MODES.includes(inst.mode)) return { mode: 'blocked', reasons: [`安裝身份未知（${inst.mode}），不能恢復。`] };
  if (!source || source.loading) return { mode: 'pending', reasons: ['正在讀取來源任務…'] };
  if (source.error) return { mode: 'blocked', reasons: ['無法讀取來源任務；未核實前不能恢復。'] };
  const job = source.job;
  if (!job || job.id !== inst.jobId) return { mode: 'blocked', reasons: ['來源任務與登記不符。'] };
  if (job.state === 'OUTCOME_UNKNOWN') return { mode: 'reconcile', reasons: ['來源任務結果未知：先核對任務，不能當成失敗直接重試。'] };
  if (job.state !== 'FAILED' || !['INSTALL', 'RECOVER'].includes(job.kind)) return { mode: 'blocked', reasons: [`來源任務不是已核實失敗的安裝（${job.kind ?? '?'} · ${job.state}）。`] };
  return { mode: 'recover', reasons: [] };
}

export function recoveryRequestBody(server) {
  const inst = server?.installation;
  if (!inst || inst.state !== 'FAILED' || !isServerUuid(inst.jobId) || !Number.isInteger(server.revision)) return null;
  return { expectedRevision: server.revision, sourceJobId: inst.jobId };
}

// 對齊 ControlInstallExecutor 的 administratorAction。AUTHORITY_ACCOUNTS_AFTER_JOIN 是加入後的帳號流程要求，不是已同步帳號。
export const ADMINISTRATOR_ACTION_TEXT = Object.freeze({
  INITIALIZE_OPERATOR_ADMINISTRATOR: '複製目前管理員作為初始管理員，之後兩邊各自獨立變更',
  PRESERVE_EXISTING_OR_INITIALIZE_ONLY_IF_EMPTY: '保留既有管理員與密碼；只有沒有任何使用者時才以凍結的目前管理員初始化',
  AUTHORITY_ACCOUNTS_AFTER_JOIN: '不複製管理員；加入集群後才由權威站帳號流程處理（目前沒有同步帳號）',
});

export function planExpired(plan, now = Date.now()) {
  const t = plan?.expiresAt ? Date.parse(plan.expiresAt) : NaN;
  return Number.isFinite(t) && t <= now;
}

export const JOB_STATES = Object.freeze(['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN']);

export function normalizeJob(j) {
  if (!j || typeof j !== 'object' || !isServerUuid(j.id) || typeof j.state !== 'string') return null;
  return {
    id: j.id, serverId: str(j.serverId), kind: str(j.kind), state: j.state,
    createdAt: str(j.createdAt), artifactSha256: str(j.artifactSha256), code: str(j.code),
    result: j.result && typeof j.result === 'object' && !Array.isArray(j.result) ? j.result : null,
  };
}

// Job.result.stage：固定列舉，只在結果明細顯示，不換算成進度。
export const JOB_STAGE_TEXT = Object.freeze({
  PREFLIGHT: '前置檢查', ARTIFACT_VERIFY: '驗證版本檔案', SYSTEM_DEPENDENCIES: '系統相依套件', IMAGES: '映像',
  DATABASE_REDIS: '資料庫與 Redis', APPLICATION_START: '啟動業務後端', RESTORE_REHEARSAL: '恢復演練',
  ADMIN_INITIALIZATION: '初始化管理員', COMPLETE: '完成',
});
export const jobStageText = (stage) => (typeof stage === 'string' ? JOB_STAGE_TEXT[stage] ?? null : null);

// ---------- 接入集群（首個權威站） ----------

// CONFIGURED／WAITING_FOR_JOIN 只是遠端設定完成或等待加入，不是已加入。
export const CLUSTER_STAGE_TEXT = Object.freeze({
  AUTHORITY_SETUP: '設定權威資料庫', MEMBER_CONFIGURATION: '成員設定', MEMBERSHIP_JOIN: '成員加入', VERIFIED: '已核對',
  CONFIGURED: '已設定（尚未加入）', WAITING_FOR_JOIN: '等待加入（尚未加入）',
  MEMBER_CONFIGURATION_RECOVERY: '恢復原設定（仍未加入）',
});
export const MEMBER_RECOVERY_KIND = 'REMOTE_MEMBER_CONFIGURATION_RECOVERY';
export const CLUSTER_PLAN_KINDS = Object.freeze(['INITIAL_AUTHORITY', 'REMOTE_MEMBER', MEMBER_RECOVERY_KIND]);
export const CLUSTER_KIND_LABEL = Object.freeze({ INITIAL_AUTHORITY: '首個權威站接入', REMOTE_MEMBER: '校外業務站接入', [MEMBER_RECOVERY_KIND]: '恢復成員原設定' });
export const clusterStageText = (stage) => (typeof stage === 'string' ? CLUSTER_STAGE_TEXT[stage] ?? null : null);

// 已安裝且尚未 ACTIVE 才顯示入口；reasons 非空時入口停用並顯示原因。
// kind：INITIAL_AUTHORITY（校內權威站，原規則）或 REMOTE_MEMBER（已安裝的校外業務站）。
// 遠端成員只有清單 remoteMemberEnrollmentSupported 嚴格為 true 才開放；私有設定與網路就緒交給後端預檢。
export function enrollmentAvailability(server, list) {
  const st = serverStatus(server);
  if (!st.installed || st.joined) return { show: false, reasons: [] };
  const inst = server.installation;
  const reasons = [];
  if (st.unknownIdentity) return { show: true, reasons: ['安裝身份未知，停用接入。'], kind: null };
  if (!list?.clusterEnrollmentEnabled) reasons.push(SERVER_CODE_TEXT.CLUSTER_ENROLLMENT_DISABLED);
  if (inst?.mode === 'EXTERNAL') {
    if (list?.remoteMemberEnrollmentSupported !== true) return { show: true, reasons: ['這個中控後端不支援校外業務站接入。'], kind: null };
    if (inst.clusterMembership !== 'NOT_JOINED') reasons.push('成員狀態未確定，不能接入。');
    if (!(Number.isInteger(inst.allocationSlot) && inst.allocationSlot >= 1 && inst.allocationSlot <= 1023) || !inst.clusterId || !inst.instanceId) {
      reasons.push('角色或分配資料不完整（需 EXTERNAL、分配編號 1–1023 與原集群身份）。');
    }
    if (!rootCapable(server, list)) reasons.push(SERVER_CODE_TEXT.MEMBER_CONFIGURATION_ROOT_REQUIRED);
    return { show: true, reasons, kind: 'REMOTE_MEMBER' };
  }
  if (server.location !== 'CONTROL_HOST') reasons.push(SERVER_CODE_TEXT.INITIAL_AUTHORITY_CONTROL_HOST_REQUIRED);
  return { show: true, reasons, kind: 'INITIAL_AUTHORITY' };
}

export function normalizeClusterPlan(p) {
  if (!p || typeof p !== 'object' || !isServerUuid(p.id) || !CLUSTER_PLAN_KINDS.includes(p.kind)) return null;
  return {
    id: p.id, kind: p.kind, serverId: str(p.serverId), serverRevision: p.serverRevision ?? null,
    state: p.state === 'READY' ? 'READY' : 'BLOCKED',
    blockers: Array.isArray(p.blockers) ? p.blockers.map((b) => (typeof b === 'string' ? b : String(b?.code ?? JSON.stringify(b)))) : [],
    expiresAt: str(p.expiresAt), clusterId: str(p.clusterId), siteId: str(p.siteId), instanceId: str(p.instanceId),
    allocationSlot: int(p.allocationSlot), httpPort: int(p.httpPort), databasePort: int(p.databasePort),
    // 這三項若未明確回報 true，介面一律以保守方式說明（單站、會重啟、原資料庫保留由後端負責）。
    singleSiteOnly: p.singleSiteOnly !== false,
    controllerRestartExpected: p.controllerRestartExpected !== false,
    originalDatabasePreserved: p.originalDatabasePreserved === true,
    administratorUsername: str(p.administratorUsername),
    // REMOTE_MEMBER 公開欄位
    mode: str(p.mode), artifactSha256: str(p.artifactSha256), profileId: str(p.profileId),
    baseEpoch: Number.isInteger(p.baseEpoch) ? p.baseEpoch : null,
    // 恢復原設定計畫：來源失敗任務 UUID 與「恢復後仍需新的接入計畫」（只認嚴格 true）。
    failedId: isServerUuid(p.failedId) ? p.failedId : null,
    controllerRestartDeclaredFalse: p.controllerRestartExpected === false,
    newEnrollmentPlanRequired: p.newEnrollmentPlanRequired === true,
  };
}

// 接入計畫可執行的前端條件：READY、有效期可解析且未過期、保留原資料庫；遠端成員還需完整身份與 baseEpoch。
export function clusterPlanProblems(p, now = Date.now()) {
  const problems = [];
  if (p.state !== 'READY') problems.push('接入計畫有阻擋條件。');
  const t = Date.parse(p.expiresAt ?? '');
  if (!Number.isFinite(t)) problems.push('計畫有效期未知，不能執行；請重新建立。');
  else if (t <= now) problems.push('接入計畫已過期，請重新建立。');
  if (!p.originalDatabasePreserved) problems.push('計畫沒有明確回報保留原資料庫，不能執行。');
  if (p.kind === 'REMOTE_MEMBER') {
    if (p.mode !== 'EXTERNAL' || !(Number.isInteger(p.allocationSlot) && p.allocationSlot >= 1 && p.allocationSlot <= 1023) || !p.siteId || !p.instanceId || !p.clusterId) problems.push('計畫的角色或分配資料不完整。');
    if (p.baseEpoch === null) problems.push('計畫缺少基準 epoch。');
  }
  if (p.kind === MEMBER_RECOVERY_KIND) {
    if (!p.failedId) problems.push('計畫缺少來源失敗任務 UUID。');
    if (!(Number.isInteger(p.allocationSlot) && p.allocationSlot >= 1 && p.allocationSlot <= 1023) || !p.siteId || !p.instanceId || !p.clusterId || !p.serverId) problems.push('計畫的原身份或分配資料不完整。');
    if (!/^[0-9a-f]{64}$/.test(p.artifactSha256 ?? '')) problems.push('計畫缺少有效的原版本 SHA-256。');
    if (!p.controllerRestartDeclaredFalse) problems.push('計畫沒有明確回報中控不重啟，不能執行。');
    if (!p.newEnrollmentPlanRequired) problems.push('計畫沒有明確回報恢復後仍需新的接入計畫，不能執行。');
  }
  return problems;
}

// ---------- 成員設定失敗後恢復原設定 ----------
// 只有 REMOTE_MEMBER＋FAILED＋MEMBER_CONFIGURATION_FAILED_PRESERVED 的原任務可建立恢復；
// 結果未知、執行中、已 CONFIGURED／成功或其他失敗碼都不可（後端仍是最終判斷）。
export function memberRecoverySourceProblems(job) {
  if (!job) return ['沒有來源任務。'];
  if (job.kind !== 'REMOTE_MEMBER') return ['只有校外業務站接入任務可以恢復原設定。'];
  if (job.state !== 'FAILED') return [job.state === 'OUTCOME_UNKNOWN' ? '原任務結果未知：請先核對，不能建立恢復。' : jobActive(job) ? '原任務仍在執行，不能建立恢復。' : '原任務不是失敗狀態，不能建立恢復。'];
  if (job.code !== 'MEMBER_CONFIGURATION_FAILED_PRESERVED') return ['只有「遠端設定失敗且已保留原設定」（MEMBER_CONFIGURATION_FAILED_PRESERVED）的任務可以恢復。'];
  return [];
}

// 恢復任務成功：只認 NOT_JOINED＋newEnrollmentPlanRequired＝true；絕不視為已加入。
export function memberRecoveryVerified(job) {
  return job?.kind === MEMBER_RECOVERY_KIND && job.state === 'SUCCEEDED' && job.result?.clusterMembership === 'NOT_JOINED' && job.result?.newEnrollmentPlanRequired === true;
}

export function normalizeClusterJob(j) {
  if (!j || typeof j !== 'object' || !isServerUuid(j.id) || typeof j.state !== 'string') return null;
  return {
    id: j.id, kind: str(j.kind), serverId: str(j.serverId), state: j.state, stage: str(j.stage),
    // 三態：嚴格 true／false 照回報；缺失或其他值為 null（不承諾重啟行為）。
    controllerRestartExpected: typeof j.controllerRestartExpected === 'boolean' ? j.controllerRestartExpected : null,
    failedId: isServerUuid(j.failedId) ? j.failedId : null,
    singleSiteOnly: j.singleSiteOnly !== false,
    createdAt: str(j.createdAt), code: str(j.code),
    result: j.result && typeof j.result === 'object' && !Array.isArray(j.result) ? j.result : null,
  };
}

// 接入任務被接受後的提示：只依正式回應的 controllerRestartExpected 說明重啟；任何情況都只以原 UUID 唯讀查詢，不重送。
export function clusterAcceptedText(job) {
  const id = job?.id ?? '—';
  if (job?.kind === MEMBER_RECOVERY_KIND) {
    return `已送出恢復原設定任務 ${id}，後端已接受${job.controllerRestartExpected === false ? '；中控不重啟' : '；回應未說明中控是否重啟'}。此頁持續以原任務 UUID 唯讀查詢進度；不需要、也不要重新送出。完成後仍未加入集群。`;
  }
  if (job?.controllerRestartExpected === true) return `已送出接入任務 ${id}。中控預期會短暫重啟，此頁會自動重新連線並以原任務 UUID 讀取；不需要、也不要重新送出。`;
  if (job?.controllerRestartExpected === false) return `已送出接入任務 ${id}，後端已接受；中控不重啟。此頁持續以原任務 UUID 唯讀查詢進度；不需要、也不要重新送出。`;
  return `已送出接入任務 ${id}，後端已接受。回應未說明中控是否重啟；此頁持續以原任務 UUID 唯讀查詢進度（連線中斷時自動重試）；不需要、也不要重新送出。`;
}

// 送出結果未確認：計畫明確回報不重啟時不提重啟；否則保守說明可能正在重啟。兩者都只以原計畫 UUID 唯讀讀取。
export function clusterUnknownSubmitText(plan) {
  return plan?.controllerRestartExpected === false
    ? '送出結果未確認。不會重新送出；正以原計畫 UUID 唯讀讀取任務。'
    : '送出結果未確認（中控可能正在重新啟動）。不會重新送出；正以原計畫 UUID 讀取任務。';
}

// 任務結果明確為 APPLIED／ACTIVE 才算已加入；其他情況（含 SUCCEEDED 但欄位缺漏）都不宣稱加入。
export function clusterJobVerified(job) {
  return job?.kind !== MEMBER_RECOVERY_KIND && job?.state === 'SUCCEEDED' && job.result?.clusterMembership === 'ACTIVE' && job.result?.changeState === 'APPLIED';
}

// 讀取任務時的錯誤：AUTH 走正常登出；RECONNECT 為中控重啟期間可預期的失敗（網路、逾時、無法解析、5xx），繼續唯讀輪詢。
export function clusterPollFailure(err) {
  if (err?.status === 401 || err?.status === 403) return 'AUTH';
  if (err?.kind === 'aborted') return 'ABORTED';
  if (err?.kind === 'network' || err?.kind === 'timeout' || err?.kind === 'parse' || err?.status >= 500) return 'RECONNECT';
  if (err?.status === 404) return 'NOT_FOUND';
  return 'OTHER';
}

export const CLUSTER_POLL_MS = 4000;
export const CLUSTER_POLL_MAX_MS = 15000;
// 連續失敗 n 次後的等待：4、4、8、8、15… 秒，有上限。
export function clusterPollDelay(failures) {
  if (failures <= 0) return CLUSTER_POLL_MS;
  return Math.min(CLUSTER_POLL_MS * 2 ** Math.floor((failures - 1) / 2), CLUSTER_POLL_MAX_MS);
}
export const CLUSTER_RESTART_EXPECTED_MS = 120000;

export const jobActive = (job) => job && (job.state === 'QUEUED' || job.state === 'RUNNING');

export const JOB_COPY = Object.freeze({
  QUEUED: ['amber', '排隊中'], RUNNING: ['amber', '執行中'], SUCCEEDED: ['teal', '已完成'],
  FAILED: ['red', '失敗'], OUTCOME_UNKNOWN: ['red', '結果未知'],
});
export const jobCopy = (state) => JOB_COPY[state] ?? ['amber', String(state)];

// ---------- 環境檢測 ----------

export function pythonOk(version) {
  const m = typeof version === 'string' ? version.match(/(\d+)\.(\d+)/) : null;
  if (!m) return null;
  const [maj, min] = [Number(m[1]), Number(m[2])];
  return maj > PYTHON_MIN[0] || (maj === PYTHON_MIN[0] && min >= PYTHON_MIN[1]);
}

export function composeOk(version) {
  if (typeof version !== 'string' || !version) return null;
  const m = version.match(/v?(\d+)\.\d+/);
  return m ? Number(m[1]) >= 2 : null;
}

const show = (v) => (v === null || v === undefined || v === '' ? null : typeof v === 'string' ? v : JSON.stringify(v));

// 回傳逐項 { label, tone: ok|bad|warn|wait, detail }。CONNECTED 只代表 SSH 與探測成功，各項仍需個別判斷。
export function connectionChecks(conn) {
  if (!conn || conn.state === 'NOT_TESTED') {
    return ['Linux', 'Python', 'Docker Compose', 'root 與 Docker 權限'].map((label) => ({ label, tone: 'wait', detail: '未檢測' }));
  }
  if (conn.state === 'FAILED') {
    return [{ label: 'SSH 連線', tone: 'bad', detail: serverCodeText(conn.code) ?? '連線或探測失敗' }];
  }
  const items = [];
  items.push(show(conn.linux) ? { label: 'Linux', tone: 'ok', detail: show(conn.linux) } : { label: 'Linux', tone: 'warn', detail: '未回報系統資訊' });
  const py = pythonOk(conn.python);
  items.push({ label: 'Python', tone: py === true ? 'ok' : py === false ? 'bad' : 'warn', detail: show(conn.python) ? `${show(conn.python)}${py === false ? '（需要 3.10 以上）' : py === null ? '（無法判定版本）' : ''}` : '找不到 Python（需要 3.10 以上）' });
  const cp = composeOk(conn.compose);
  const docker = show(conn.docker);
  items.push({
    label: 'Docker Compose',
    tone: cp === true && docker ? 'ok' : 'bad',
    detail: [docker ? `Docker ${docker}` : '找不到 Docker', show(conn.compose) ? `Compose ${show(conn.compose)}${cp === false ? '（需要 v2）' : ''}` : '找不到 Compose v2'].join(' · '),
  });
  const rootOk = conn.root === true;
  const accessOk = conn.dockerAccess === true;
  items.push({
    label: 'root 與 Docker 權限',
    tone: rootOk && accessOk ? 'ok' : conn.root === null && conn.dockerAccess === null ? 'warn' : 'bad',
    detail: [conn.root === null ? 'root：未回報' : rootOk ? '具 root 權限' : '沒有 root 權限（首次安裝需要 root 或已配置免密 sudo）',
      conn.dockerAccess === null ? 'Docker 權限：未回報' : accessOk ? '可存取 Docker' : '無法存取 Docker'].join(' · '),
  });
  return items;
}

export function resourceLine(conn) {
  if (!conn || conn.state !== 'CONNECTED') return null;
  const parts = [];
  if (conn.memoryMiB !== null) parts.push(`記憶體 ${conn.memoryMiB} MiB`);
  if (conn.diskFreeMiB !== null) parts.push(`可用磁碟 ${conn.diskFreeMiB} MiB`);
  return parts.length ? parts.join(' · ') : null;
}

// 卡片狀態：只由後端回報推出，不推測成「已就緒」以外的狀態。
export function serverStatus(server) {
  const inst = server.installation;
  // 只有後端核對真實 APPLIED／ACTIVE 後才會把 clusterMembership 改為 ACTIVE；安裝成功本身不代表加入。
  if (inst?.state === 'SUCCEEDED' && inst.clusterMembership === 'ACTIVE') return { tone: 'teal', label: '已加入集群', installed: true, joined: true };
  if (inst?.state === 'SUCCEEDED' && inst.mode && !SITE_MODES.includes(inst.mode)) return { tone: 'amber', label: `安裝身份未知（${inst.mode}）`, installed: true, unknownIdentity: true };
  // 已分配身份（校外業務站或保留分配的校內站）：安裝成功仍是等待接入，不是集群成員、也沒有同步帳號。
  if (inst?.state === 'SUCCEEDED' && (inst.mode === 'EXTERNAL' || inst.clusterId)) return { tone: 'teal', label: '已安裝 · 等待接入', installed: true, allocated: true };
  if (inst?.state === 'SUCCEEDED') return { tone: 'teal', label: '已安裝 · 尚未加入多站集群', installed: true };
  if (inst?.state === 'FAILED') return { tone: 'red', label: '安裝失敗 · 資料已保留', installed: false, unresolved: true };
  if (inst) return { tone: 'amber', label: `安裝狀態 ${inst.state}`, installed: false, unresolved: true };
  const c = server.connection;
  if (c.state === 'FAILED') return { tone: 'red', label: '連線失敗', installed: false };
  if (c.state === 'NOT_TESTED') return { tone: 'neutral', label: '未檢測', installed: false };
  const ready = connectionChecks(c).every((i) => i.tone === 'ok');
  return ready ? { tone: 'teal', label: '環境就緒', installed: false } : { tone: 'amber', label: '環境未全部就緒', installed: false };
}

// ---------- 文字 ----------

export const SERVER_CODE_TEXT = Object.freeze({
  INVALID_REQUEST: '請求內容無效。',
  INVALID_SSH_HOST: '主機格式無效。',
  INVALID_SSH_PORT: 'SSH 埠無效。',
  INVALID_SSH_USER: 'SSH 使用者格式無效。',
  INVALID_SERVER_NAME: '名稱無效。',
  INVALID_SERVER_INPUT: '伺服器資料無效。',
  INVALID_SSH_CREDENTIAL: 'SSH 憑據無效（目前只支援沒有保護密碼的私鑰）。',
  SSH_CREDENTIAL_REQUIRED: '需要輸入 SSH 憑據。',
  INVALID_SSH_HOST_KEY: '主機金鑰無效，請重新取得指紋。',
  SSH_HOST_KEY_CONFIRMATION_REQUIRED: '需要確認主機指紋。',
  CONTROL_HOST_LOOPBACK_REQUIRED: '中控本機只能使用回環位址 127.0.0.1。',
  INVALID_BUSINESS_PORT: '業務 HTTP 埠無效。',
  INVALID_APPLICATION_URL: '業務站對外網址無效。',
  SERVER_NOT_FOUND: '找不到這台伺服器的登記，可能已被移除。',
  SERVER_JOB_NOT_FOUND: '找不到這個任務。',
  RELEASE_NOT_CONFIGURED: '這個版本未在中控登記。',
  SERVER_REVISION_CHANGED: '伺服器登記已被修改，請重新讀取後再操作。',
  SSH_SERVER_ALREADY_REGISTERED: '這個位址與使用者已登記過。',
  SERVER_LIMIT_REACHED: '已達伺服器登記數量上限。',
  IDEMPOTENCY_CONFLICT: '同一個請求 ID 已用於不同內容；請重新開啟表單後再送出。',
  SERVER_AUTHORITY_REQUIRED: '這個入口不能管理伺服器，請改用權威站的主中控入口。',
  CONTROL_HANDOFF_PENDING: '主中控交接進行中，暫時不能管理伺服器。',
  INSTALLED_SERVER_ADDRESS_IMMUTABLE: '已安裝的伺服器不能修改位置、主機或埠。',
  SERVER_REGISTRY_BUSY: '伺服器登記正在被其他操作使用，請稍後再試。',
  DEPLOYMENT_RUNNING: '有部署正在執行，暫時不能進行這項操作。',
  DEPLOYMENT_RECONCILIATION_REQUIRED: '有部署結果尚待核對，請先在部署頁核對。',
  SERVER_PLAN_BLOCKED: '計畫有阻擋條件，不能執行。',
  SERVER_PLAN_EXPIRED: '計畫已過期（15 分鐘），請重新建立。',
  SERVER_PLAN_CHANGED: '伺服器登記或條件已改變，計畫失效，請重新建立。',
  SERVER_TASK_RECONCILIATION_REQUIRED: '這台伺服器有結果未知的任務，請先核對遠端記錄。',
  SERVER_INSTALLATION_DISABLED: '安裝功能未啟用。',
  SSH_HOST_KEY_UNAVAILABLE: '無法取得主機金鑰：主機或埠無法連線。',
  SSH_OPERATION_BUSY: '這台伺服器正在進行其他 SSH 操作，請稍後再試。',
  SERVER_PLAN_NOT_FOUND: '找不到這個計畫，可能已過期或被取代，請重新建立。',
  SERVER_PRIVATE_STATE_UNAVAILABLE: '中控的伺服器憑據儲存暫時不可用。',
  SERVER_AGENT_UNAVAILABLE: 'SSH 執行元件暫時不可用。',
  SERVER_INSTALLATION_UNAVAILABLE: '安裝服務暫時不可用。',
  CONTROL_LEASE_NOT_HELD: '這個入口目前不是主中控，伺服器管理已停用。',
  // 計畫阻擋條件
  RELEASE_ACCEPTANCE_REQUIRED: '這個版本尚未完成驗收登記。',
  ARTIFACT_SHA256_MISMATCH: '版本檔案的 SHA-256 與登記不符。',
  ARTIFACT_UNAVAILABLE: '找不到版本檔案。',
  SERVER_ALREADY_INSTALLED_OR_UNRESOLVED: '這台伺服器已安裝，或有尚未釐清的安裝結果。',
  INSTALLATION_DIRECTORY_EXISTS: '安裝目錄已存在（即使是空目錄也不會覆寫）；中控不會刪除既有目錄。',
  BUSINESS_PORT_IN_USE: '業務 HTTP 埠已被占用。',
  FIRST_INSTALL_ROOT_REQUIRED: '首次安裝需要 root，或已配置免密 sudo 的 SSH 帳號。',
  DOCKER_COMPOSE_REQUIRED: '需要 Docker 與 Compose v2（Debian 13+／Ubuntu 24.04+ 可勾選由中控安裝）。',
  INSUFFICIENT_DISK_SPACE: '磁碟可用空間不足。',
  SSH_INSPECTION_FAILED: 'SSH 檢查失敗，請先重新連線檢測。',
  SERVER_NOT_INSTALLED: '這台伺服器尚未安裝。',
  // 版本更新與恢復安裝
  JOINED_SERVER_CLUSTER_DEPLOYMENT_REQUIRED: '需使用集群部署：已加入集群或成員狀態未確定的伺服器不能用單機版本更新。',
  INSTALLED_SERVER_PORT_IMMUTABLE: '版本更新不會改動業務 HTTP 埠，不能修改。',
  INSTALLED_SERVER_URL_IMMUTABLE: '版本更新不會改動業務對外網址，不能修改。',
  // 安裝身份
  SITE_ROLE_REQUIRED: '請選擇校內權威站或校外業務站。',
  INVALID_SITE_ROLE: '站點角色無效。',
  INVALID_SITE_ALLOCATION: '分配編號無效。',
  CLUSTER_ROLE_SLOT_MISMATCH: '角色與分配編號不符（校內權威站只能是 0，校外業務站為 1–1023）。',
  SITE_ALLOCATION_REQUIRED: '校外業務站需要分配編號。',
  INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED: '中控缺少可用的集群設定。',
  COORDINATOR_MUST_JOIN_FIRST: '請先完成首個校內權威站的接入。',
  INSTALLATION_ALLOCATION_RETAINED: '這個身份或分配編號已被保留，不能重新分配。',
  MEMBER_IDENTITY_OR_SLOT_RETAINED: '這個身份或分配編號已被保留，不能重新分配。',
  INSTALLED_SITE_IDENTITY_IMMUTABLE: '已安裝站點的身份不可修改。',
  ALLOCATED_SITE_MEMBERSHIP_REQUIRED: '已分配站點暫不能使用獨立更新流程，須完成受支援的集群接入和發布。',
  RECOVERY_RESTORE_CLEANUP_REQUIRED: '有遺留的恢復演練實例或臨時恢復庫尚未清理（或證據鏈不符），需另行人工審閱處理；此入口不會刪除任何資料。',
  // 接入集群
  CLUSTER_ENROLLMENT_DISABLED: '接入集群功能未啟用。',
  INITIAL_AUTHORITY_CONTROL_HOST_REQUIRED: '首個權威站必須是「中控本機」的校內業務站；目前不支援遠端伺服器作為首站。',
  CLUSTER_ALREADY_INITIALIZED: '集群已有成員；目前不支援已有成員時切換權威站或加入其他站。',
  AUTHORITY_PREFLIGHT_FAILED: '權威站前置檢查失敗。',
  AUTHORITY_PLAN_CHANGED: '伺服器或集群條件已改變，接入計畫失效，請重新建立。',
  AUTHORITY_PLAN_EXPIRED: '接入計畫已過期，請重新建立。',
  AUTHORITY_PLAN_BLOCKED: '接入計畫有阻擋條件，不能執行。',
  AUTHORITY_JOB_NOT_FOUND: '找不到這個接入任務。',
  AUTHORITY_RECONCILIATION_REQUIRED: '有結果未知的接入任務，請先核對實際成員狀態。',
  // 遠端校外業務站接入
  MULTISITE_AUTHORITY_SETUP_REQUIRED: '目前權威站仍是單站集群，需先完成多站配置；這不是一鍵部署第二中控。',
  ACTIVE_AUTHORITY_REQUIRED: '需要已加入（epoch ≥ 1）的校內權威站。',
  MEMBER_CONFIGURATION_ROOT_REQUIRED: '遠端成員設定需要 root，或已配置免密 sudo 的 SSH 帳號。',
  UNJOINED_EXTERNAL_INSTALLATION_REQUIRED: '只限已安裝且尚未接入的校外業務站。',
  INSTALLED_MEMBER_IDENTITY_REQUIRED: '安裝記錄缺少完整的站點身份。',
  MEMBER_CLUSTER_BINDING_MISMATCH: '站點的集群綁定與目前集群不符。',
  MEMBER_ALLOCATION_RESERVATION_REQUIRED: '找不到與安裝一致的分配保留記錄。',
  PRIVATE_MEMBER_CONFIGURATION_REQUIRED: '中控缺少這台伺服器的私有成員設定（由維運提供）。',
  MEMBER_ALREADY_RETAINED: '這個成員身份已被保留，不能重新接入。',
  MEMBERSHIP_CHANGE_PENDING: '有進行中的成員變更，請等它完成。',
  MEMBER_CONFIGURATION_PREFLIGHT_FAILED: '遠端設定預檢失敗。',
  MEMBER_PLAN_BLOCKED: '接入計畫有阻擋條件，不能執行。',
  MEMBER_PLAN_CHANGED: '條件已改變，接入計畫失效，請重新建立。',
  MEMBER_PLAN_EXPIRED_OR_CHANGED: '接入計畫已過期或條件已改變，請重新建立。',
  MEMBER_RECEIPT_MISMATCH: '遠端回執與計畫不符，已停止。',
  MEMBER_CONFIGURATION_FAILED_PRESERVED: '遠端設定失敗；原資料與私有設定備份已保留，不會自動重裝或重置資料庫。',
  MEMBER_RECONCILIATION_REQUIRED: '接入結果未知，請以原任務 UUID 核對；不會重送。',
  FAILED_MEMBER_CONFIGURATION_REQUIRED: '只有「遠端設定失敗且已保留原設定」的校外業務站接入任務可以恢復原設定。',
  MEMBER_RECOVERY_PREFLIGHT_FAILED: '恢復原設定的遠端預檢失敗（原設定備份、資料庫指紋或成員狀態不符）；未做任何變更。',
  MEMBER_RECOVERY_FAILED_PRESERVED: '恢復原設定失敗；原資料與備份已保留，不會自動重試或重置資料庫。',
  MEMBER_RECOVERY_RECONCILIATION_REQUIRED: '恢復結果未知，請以原恢復任務 UUID 核對；不會重送。',
  STALE_MEMBERSHIP_EPOCH: '成員 epoch 已改變，請重新建立計畫。',
  UPGRADE_COMPATIBILITY_NOT_REVIEWED: '候選版本尚未登記相容性審查。',
  RESTORE_REHEARSAL_REQUIRED: '需要先完成恢復演練。',
});

export const serverCodeText = (code) => (code ? SERVER_CODE_TEXT[code] ?? null : null);

export function gateReasonText(reason) {
  return serverCodeText(reason) ?? '這個入口目前不能管理伺服器。';
}

const KNOWN_REJECTION_503 = new Set(['SERVER_PRIVATE_STATE_UNAVAILABLE', 'SERVER_AGENT_UNAVAILABLE', 'SERVER_INSTALLATION_UNAVAILABLE', 'CONTROL_LEASE_NOT_HELD']);

// LOGOUT：401／403。UNKNOWN：網路、逾時、解析失敗或未定義的 5xx——寫入可能已生效，不自動重送。REJECTED：其他明確拒絕。
export function serverFailure(err) {
  const code = typeof err?.code === 'string' ? err.code : null;
  if (err?.kind === 'validation') return { outcome: 'REJECTED', message: '輸入未通過檢查，未送出。', code };
  if (err?.status === 401 || err?.status === 403) return { outcome: 'LOGOUT', message: '登入已失效或不具管理員權限，請重新登入。', code };
  if (err?.outcomeUnknown || (err?.status >= 500 && !(KNOWN_REJECTION_503.has(code) || code === 'SSH_HOST_KEY_UNAVAILABLE'))) {
    return { outcome: 'UNKNOWN', message: '結果未知（網路中斷、逾時或伺服器未回應）。中控不會自動重送。', code };
  }
  const text = serverCodeText(code) ?? `伺服器拒絕本次操作（HTTP ${err?.status ?? '?'}${code ? ` · ${code}` : ''}）。`;
  return { outcome: 'REJECTED', message: text, code, leaseLost: code === 'CONTROL_LEASE_NOT_HELD' };
}
