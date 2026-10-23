// 在新伺服器安裝中控與業務（controller-plans）：純函式。
// 契約：docs/architecture/control-controller-bootstrap-api.md；後端 ControlBootstrapPlanController／ControlBootstrapPlans.Request／ControlBootstrapStorage.Selection。
// - 能力只以 GET /controller-plans 的回應判斷；404 表示未啟用，不改走單獨業務安裝。
// - 表單只收契約欄位；不收管理員身份、epoch、私有配置／制品路徑或配置雜湊。
// - 預覽必須與送出的輸入一致；只批准預覽回傳的原 requestHash 與使用者逐項確認的完整權限。

import { isTaskUuid, normalizeControllerJob, safeStorageUrl } from './tasks-state.js';

export const STORAGE_PROVIDERS = Object.freeze(['GENERIC', 'RUSTFS', 'R2']);
export const STORAGE_MODES = Object.freeze(['SOURCE', 'CUSTOM']);
export const RESERVED_HTTP_PORTS = Object.freeze([18080, 18081, 25432, 16379, 28090]);

export const PERMISSION_TEXT = Object.freeze({
  INSTALL_RUNTIME_PACKAGES: '在來源與目標安裝執行環境所需的系統套件。',
  ADD_EXACT_PRIVATE_PEER: '在兩端新增精確指定的私網對等連線（WireGuard）。',
  ALLOW_TARGET_UDP_LISTENER: '允許目標開放 WireGuard UDP 監聽埠。',
  SHARE_EXISTING_CONTROLLER_STATE: '讓目標中控共享現有中控的部署狀態。',
  CREATE_FORWARDING_ONLY_IDENTITY: '建立只用於轉發的系統身分。',
  ALLOW_PRIVATE_DEPENDENCY_LISTENER: '允許依賴服務在私網位址監聽。',
  FORWARD_PINNED_SOURCE_ADMIN_SSH: '經私網轉發已固定的來源管理 SSH。',
  FORWARD_CAPTURED_MEMBER_ENDPOINTS: '經私網轉發已擷取的成員端點。',
  INSTALL_BUSINESS_AND_JOIN: '在目標安裝業務並加入集群。',
  ALLOW_BUSINESS_DOCKER_NETWORKING: '允許業務容器使用 Docker 網路。',
  ALLOW_DOCKER_FORWARD_POLICY_DROP: '允許把 Docker 轉發政策設為 DROP。',
  CREATE_BUSINESS_TLS_RELAY: '建立業務 TLS 轉送。',
  CREATE_LIMITED_BUSINESS_AUTH_ROLE: '建立權限受限的業務認證資料庫角色。',
  CREATE_PRIVATE_MEMBER_HTTPS: '建立私網成員 HTTPS 入口。',
});
export const permissionText = (code) => PERMISSION_TEXT[code] ?? '後端回報的權限（前端沒有說明，請依代碼確認）。';

export const BOOTSTRAP_CODE_TEXT = Object.freeze({
  BOOTSTRAP_SOURCE_CONTROLLER_UPGRADE_REQUIRED: '來源中控需要先更新到已審查的中控版本，才能建立組合安裝。',
  BOOTSTRAP_TARGET_STORAGE_CAPABILITY_REQUIRED: '已審查的中控候選版本不支援目標站儲存設定。',
  BOOTSTRAP_WORKFLOW_CAPABILITY_REQUIRED: '已審查的中控候選版本不支援背景組合安裝流程。',
  BOOTSTRAP_STORAGE_CONFIGURATION_REQUIRED: '目標物件儲存設定不完整或格式無效。',
  BOOTSTRAP_INVALID_INPUT: '輸入格式無效。',
  BOOTSTRAP_INVALID_IDENTITY: '來源與目標必須是不同且已登記的伺服器。',
  BOOTSTRAP_INVALID_BINDING: 'revision 或分配槽位無效。',
  BOOTSTRAP_REVIEW_REQUIRED: '網路與業務網址欄位不完整。',
  BOOTSTRAP_INVALID_PORT: '端口無效（需 1024–65535，且不能使用保留端口）。',
  BOOTSTRAP_PRIVATE_ADDRESS_REQUIRED: '私網地址必須是 10.x、172.16–31.x 或 192.168.x 的 IPv4。',
  BOOTSTRAP_DISTINCT_ADDRESSES_REQUIRED: '來源與目標私網地址必須不同。',
  INVALID_APPLICATION_URL: '業務網址無效。',
  INVALID_IDENTIFIER: '業務版本識別碼無效。',
  INVALID_SSH_HOST: 'WireGuard 入口主機格式無效。',
  IDEMPOTENCY_CONFLICT: '這個 UUID 已用於不同的輸入；請重新建立預覽（會使用新的 UUID）。',
  BOOTSTRAP_PLAN_CHANGED: '預覽已改變或 requestHash 不符；請重新建立預覽。',
  BOOTSTRAP_PLAN_EXPIRED: '預覽已過期；請重新建立預覽。',
  BOOTSTRAP_PERMISSION_APPROVAL_REQUIRED: '必須逐項確認全部權限。',
  BOOTSTRAP_ORIGINAL_ADMIN_REQUIRED: '只有建立預覽的原管理員可以批准。',
  BOOTSTRAP_REAPPROVAL_REQUIRED: '需要重新批准。',
  BOOTSTRAP_JOB_NOT_FOUND: '中控沒有這個任務。',
  BOOTSTRAP_PLAN_NOT_FOUND: '中控沒有這個預覽。',
  ADMIN_REQUIRED: '需要管理員身份。',
  DEPLOYMENT_RUNNING: '目前有部署或交接正在進行。',
  CONTROL_LEASE_NOT_HELD: '目前入口不是主中控。',
});
export const bootstrapCodeText = (code) => (typeof code === 'string' && code ? BOOTSTRAP_CODE_TEXT[code] ?? null : null);

// ---------- 能力 ----------
// 回傳 { available, canPlan, reason, permissionsRequired, storageProviders, storageModes, planTtlSeconds, backgroundExecution, problem }。
export function normalizeCapabilities(data) {
  const off = (problem) => ({ available: false, canPlan: false, reason: null, permissionsRequired: [], storageProviders: [], storageModes: [], planTtlSeconds: null, backgroundExecution: false, problem });
  if (!data || typeof data !== 'object' || Array.isArray(data)) return off('能力回應格式無效。');
  if (data.supported !== true) return off('後端回報不支援組合安裝。');
  const codes = (v) => (Array.isArray(v) && v.every((x) => typeof x === 'string' && /^[A-Z][A-Z0-9_]{0,95}$/.test(x)) ? v : null);
  const providers = codes(data.storageProviders)?.filter((p) => STORAGE_PROVIDERS.includes(p));
  const modes = codes(data.storageModes)?.filter((m) => STORAGE_MODES.includes(m));
  const perms = codes(data.permissionsRequired);
  if (!providers || !modes || !perms || typeof data.canPlan !== 'boolean') return off('能力回應格式無效。');
  return {
    available: true, canPlan: data.canPlan === true, reason: typeof data.reason === 'string' && data.reason ? data.reason : null,
    permissionsRequired: perms, storageProviders: providers, storageModes: modes,
    planTtlSeconds: Number.isSafeInteger(data.planTtlSeconds) ? data.planTtlSeconds : null,
    backgroundExecution: data.backgroundExecution === true, problem: null,
  };
}

// ---------- 候選伺服器 ----------
// 來源：所有已登記的伺服器。後端以實際運行的服務與權威資料庫核對來源身份（ControlBootstrapSources.discover），
// 不要求安裝器記錄；最初手動建立、後來才登記的現有權威中控在清單中是「未安裝」（installation＝null），仍可作為來源。
// 已知 ACTIVE、校內（CAMPUS）的排在前面，只是排序，不代表前端已確認身份；也不把來源改寫成已安裝或 ACTIVE。
// 目標：仍要求尚未安裝的已登記伺服器。來源與目標不能是同一台（由 formProblems 檢查）。
export function sourceRank(s) {
  const inst = s?.installation;
  if (inst?.clusterMembership === 'ACTIVE') return 0;
  if (inst?.mode === 'CAMPUS') return 1;
  if (!inst) return 2;
  return 3;
}

export function sourceNote(s) {
  const inst = s?.installation;
  if (inst?.clusterMembership === 'ACTIVE') return '已知已加入集群';
  if (inst?.mode === 'CAMPUS') return '已知校內安裝';
  if (!inst) return '沒有安裝器記錄（不代表沒有運行中的中控）';
  return `安裝記錄：${inst.state ?? '未知'}`;
}

export function serverCandidates(list) {
  const servers = (Array.isArray(list?.servers) ? list.servers : []).filter((s) => s && isTaskUuid(s.id));
  return {
    sources: servers.map((s, i) => [s, i]).sort((a, b) => sourceRank(a[0]) - sourceRank(b[0]) || a[1] - b[1]).map(([s]) => s),
    targets: servers.filter((s) => !s.installation),
  };
}

// ---------- 表單驗證 ----------
const CONTROL = /[\u0000-\u001f\u007f]/;
const cleanText = (v, max) => typeof v === 'string' && v.length >= 1 && v.length <= max && v === v.trim() && !v.includes('${') && !CONTROL.test(v);

export function privateIpv4(v) {
  if (typeof v !== 'string') return false;
  const parts = v.split('.');
  if (parts.length !== 4 || !parts.every((p) => /^(0|[1-9][0-9]{0,2})$/.test(p) && Number(p) <= 255)) return false;
  const [a, b] = parts.map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}
export const endpointHost = (v) => typeof v === 'string' && v.length <= 253 && !v.startsWith('-') && !v.includes('..') && (/^[A-Za-z0-9][A-Za-z0-9.-]*$/.test(v) || (/^[0-9A-Fa-f:]+$/.test(v) && v.includes(':')));
const port = (v) => (/^[0-9]{4,5}$/.test(String(v ?? '')) ? Number(v) : null);

// 業務網址：http(s)、無帳密／query／fragment，路徑含 /api 段。
export function appBaseUrlProblem(v) {
  if (typeof v !== 'string' || !v) return '請輸入業務網址。';
  if (v.length > 512 || !safeStorageUrl(v)) return '業務網址需要 http／https，且不得含帳號密碼、查詢字串或片段。';
  if (!/\/api(\/|$)/.test(new URL(v).pathname)) return '業務網址需要包含實際的 /api 路徑前綴（例如 網域後接 /api）。';
  return null;
}

// form：{ sourceId, targetId, releaseId, allocationSlot, sourceAddress, targetAddress, targetEndpoint, wireguardPort, httpPort, appBaseUrl,
//         storageMode, provider, endpoint, region, bucket, accessKey, secretKey, pathStyle, publicBaseUrl, cdnEndpoint }
export function formProblems(form, { list, capabilities } = {}) {
  const errors = [];
  const { sources, targets } = serverCandidates(list);
  const source = sources.find((s) => s.id === form.sourceId);
  const target = targets.find((s) => s.id === form.targetId);
  if (!source) errors.push('請選擇現有來源中控（後端會實際核對權威身份）。');
  if (!target) errors.push('請選擇空的目標伺服器（尚未安裝）。');
  if (source && target && source.id === target.id) errors.push('來源與目標必須不同。');
  const releases = Array.isArray(list?.releases) ? list.releases : [];
  if (!releases.some((r) => r.id === form.releaseId) || !/^[A-Za-z0-9_-]{1,64}$/.test(form.releaseId ?? '')) errors.push('請選擇業務版本。');
  const slot = /^[0-9]{1,4}$/.test(String(form.allocationSlot ?? '')) ? Number(form.allocationSlot) : null;
  if (!(slot >= 1 && slot <= 1023)) errors.push('分配槽位需為 1–1023。');
  if (!privateIpv4(form.sourceAddress)) errors.push('來源私網地址需為 10.x、172.16–31.x 或 192.168.x 的 IPv4。');
  if (!privateIpv4(form.targetAddress)) errors.push('目標私網地址需為 10.x、172.16–31.x 或 192.168.x 的 IPv4。');
  if (privateIpv4(form.sourceAddress) && form.sourceAddress === form.targetAddress) errors.push('來源與目標私網地址必須不同。');
  if (!endpointHost(form.targetEndpoint)) errors.push('請輸入目標 WireGuard 入口主機（網域或 IP，不含端口）。');
  const wg = port(form.wireguardPort), http = port(form.httpPort);
  if (!(wg >= 1024 && wg <= 65535)) errors.push('WireGuard 端口需為 1024–65535。');
  if (!(http >= 1024 && http <= 65535)) errors.push('業務端口需為 1024–65535。');
  else if (RESERVED_HTTP_PORTS.includes(http)) errors.push(`業務端口不能使用保留端口（${RESERVED_HTTP_PORTS.join('、')}）。`);
  const app = appBaseUrlProblem(form.appBaseUrl);
  if (app) errors.push(app);
  if (!STORAGE_MODES.includes(form.storageMode)) errors.push('請選擇目標物件儲存方式。');
  else if (capabilities && !capabilities.storageModes.includes(form.storageMode)) errors.push('後端未宣告支援這種儲存方式。');
  if (form.storageMode === 'CUSTOM') {
    if (!STORAGE_PROVIDERS.includes(form.provider)) errors.push('請選擇儲存提供者。');
    else if (capabilities && !capabilities.storageProviders.includes(form.provider)) errors.push('後端未宣告支援這個儲存提供者。');
    if (!safeStorageUrl(form.endpoint)) errors.push('S3 API 地址需要 http／https，且不得含帳號密碼、查詢字串或片段。');
    if (form.provider !== 'R2' && !cleanText(form.region, 128)) errors.push('請輸入區域。');
    if (!cleanText(form.bucket, 128)) errors.push('請輸入桶名稱。');
    if (!cleanText(form.accessKey, 1024)) errors.push('請輸入 Access Key（只在建立預覽時送出一次）。');
    if (!cleanText(form.secretKey, 4096)) errors.push('請輸入 Secret Key（只在建立預覽時送出一次）。');
    for (const [k, label] of [['publicBaseUrl', '公開網址'], ['cdnEndpoint', 'CDN 地址']]) if (form[k] && !safeStorageUrl(form[k])) errors.push(`${label}需要 http／https，且不得含帳號密碼、查詢字串或片段。`);
  }
  return { errors, source, target };
}

// 建立預覽的請求本文（含一次性的儲存密鑰）。呼叫者送出後必須清除表單中的密鑰。
export function planRequestBody(form, { id, list, capabilities }) {
  const { errors, source, target } = formProblems(form, { list, capabilities });
  if (errors.length || !isTaskUuid(id)) return null;
  const body = {
    id, sourceServerId: source.id, sourceRevision: source.revision, targetServerId: target.id, targetRevision: target.revision,
    releaseId: form.releaseId, allocationSlot: Number(form.allocationSlot),
    sourceAddress: form.sourceAddress, targetAddress: form.targetAddress, targetEndpoint: form.targetEndpoint,
    wireguardPort: Number(form.wireguardPort), httpPort: Number(form.httpPort), appBaseUrl: form.appBaseUrl,
  };
  if (form.storageMode === 'CUSTOM') {
    body.storage = {
      provider: form.provider, endpoint: form.endpoint, region: form.provider === 'R2' ? 'auto' : form.region, bucket: form.bucket,
      accessKey: form.accessKey, secretKey: form.secretKey, pathStyle: form.pathStyle === true,
    };
    if (form.publicBaseUrl) body.storage.publicBaseUrl = form.publicBaseUrl;
    if (form.cdnEndpoint) body.storage.cdnEndpoint = form.cdnEndpoint;
  }
  return body;
}

// 自訂儲存的非秘密設定，依後端正規化規則（R2 區域固定 auto；選填網址空白視為未設定）。SOURCE 模式為 null。
export function storageSummary(form) {
  if (form.storageMode !== 'CUSTOM') return null;
  return {
    provider: form.provider, endpoint: form.endpoint, region: form.provider === 'R2' ? 'auto' : form.region, bucket: form.bucket,
    pathStyle: form.pathStyle === true, publicBaseUrl: form.publicBaseUrl || null, cdnEndpoint: form.cdnEndpoint || null,
  };
}

// 全部非秘密部署選項的意圖簽章（不含 Access／Secret Key）：預覽與目前表單不同時不能批准。
export function intentSignature(form, list) {
  const { source, target } = formProblems(form, { list });
  return JSON.stringify({
    source: form.sourceId, sourceRevision: source?.revision ?? null, target: form.targetId, targetRevision: target?.revision ?? null,
    releaseId: form.releaseId, allocationSlot: String(form.allocationSlot), sourceAddress: form.sourceAddress, targetAddress: form.targetAddress,
    targetEndpoint: form.targetEndpoint, wireguardPort: String(form.wireguardPort), httpPort: String(form.httpPort), appBaseUrl: form.appBaseUrl,
    storageMode: form.storageMode, storage: storageSummary(form),
  });
}

// ---------- 預覽 ----------
export function normalizePreview(data, expectedId) {
  return normalizeControllerJob(data, expectedId);
}

// sent：送出時保存的非秘密摘要 { id, sourceServerId, sourceRevision, targetServerId, targetRevision, releaseId, allocationSlot, review, storageMode, storage }
// （storage 為 storageSummary 的結果；不含密鑰）
export function previewProblems(p, sent, now = Date.now()) {
  const problems = [];
  if (!p) return ['預覽回應格式無效。'];
  if (!sent || p.id !== sent.id) problems.push('預覽 UUID 與送出的請求不同。');
  if (p.state !== 'PREVIEW_ONLY') problems.push(`預覽狀態是 ${p.state ?? '缺失'}，不是可批准的 PREVIEW_ONLY。`);
  if (!p.requestHash) problems.push('預覽缺少有效的 requestHash。');
  if (!Array.isArray(p.permissionsRequired) || p.permissionsRequired.length === 0) problems.push('預覽沒有提供權限清單。');
  else if (new Set(p.permissionsRequired).size !== p.permissionsRequired.length) problems.push('預覽的權限清單有重複項目。');
  const t = Date.parse(p.expiresAt ?? '');
  if (!Number.isFinite(t)) problems.push('預覽有效期未知，不能批准。');
  else if (t <= now) problems.push('預覽已過期，請重新建立。');
  if (p.requiresReapproval === true) problems.push('預覽要求重新批准。');
  if (sent) {
    const same = (a, b) => a === b;
    if (!same(p.sourceServerId, sent.sourceServerId) || !same(p.sourceRevision, sent.sourceRevision)) problems.push('預覽的來源伺服器或 revision 與送出的不同。');
    if (!same(p.serverId, sent.targetServerId) || !same(p.serverRevision, sent.targetRevision)) problems.push('預覽的目標伺服器或 revision 與送出的不同。');
    if (!same(p.releaseId, sent.releaseId) || !same(p.allocationSlot, sent.allocationSlot)) problems.push('預覽的業務版本或分配槽位與送出的不同。');
    const r = p.review ?? {};
    for (const k of ['sourceAddress', 'targetAddress', 'targetEndpoint', 'wireguardPort', 'httpPort', 'appBaseUrl']) {
      if (String(r[k] ?? '') !== String(sent.review[k])) { problems.push('預覽的私網或業務網址設定與送出的不同。'); break; }
    }
    const st = p.storage;
    if (!st?.provided || !st.valid) problems.push('預覽沒有提供目標物件儲存摘要。');
    else {
      if (st.mode !== sent.storageMode) problems.push('預覽的儲存方式與送出的不同。');
      if (sent.storageMode === 'CUSTOM') {
        const want = sent.storage ?? {};
        if (st.provider !== want.provider) problems.push('預覽的儲存提供者與送出的不同。');
        // 依後端公開摘要逐項比對；任何一項不同（包括網址被過濾而未顯示）都不能批准。
        const pairs = [['endpoint', 'S3 API 地址'], ['region', '區域'], ['bucket', '桶'], ['pathStyle', 'path style'], ['publicBaseUrl', '公開網址'], ['cdnEndpoint', 'CDN 地址']];
        for (const [k, label] of pairs) if ((st[k] ?? null) !== (want[k] ?? null)) problems.push(`預覽的${label}與送出的不同。`);
      }
      if (st.credentialsConfigured !== true) problems.push('預覽沒有確認儲存憑據已封存。');
    }
  }
  if (!p.controllerSha256 || !p.artifactSha256) problems.push('預覽缺少中控或業務版本的 SHA-256。');
  return problems;
}

// 只有預覽的全部權限都被逐項確認、且沒有多出其他項時，才產生批准本文。
export function approvalBody(p, confirmed) {
  if (!p?.requestHash || !Array.isArray(p.permissionsRequired) || !p.permissionsRequired.length) return null;
  const set = confirmed instanceof Set ? confirmed : new Set(confirmed ?? []);
  if (set.size !== p.permissionsRequired.length || !p.permissionsRequired.every((x) => set.has(x))) return null;
  return { requestHash: p.requestHash, permissions: [...p.permissionsRequired] };
}
