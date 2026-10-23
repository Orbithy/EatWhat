// 顯示用的文字與格式化。所有輸出都是純字串，由呼叫端以 textContent 放入 DOM。

const pad = (n) => String(n).padStart(2, '0');

export const SITE_LABELS = Object.freeze({
  campus: '校內站 campus',
  external: '校外站 external',
});

// 依部署時固定的 CONTROL_MODE 決定頂列與登入畫面字樣（不是執行期切換）。
const MODE_BRANDING = Object.freeze({
  cluster: Object.freeze({ sub: '多站叢集', lead: 'EatWhat 多站叢集中控台，需具備 admin 角色。' }),
  legacy: Object.freeze({ sub: '雙站驗收', lead: 'EatWhat 雙站驗收中控台，需具備 admin 角色。' }),
});

export function modeBranding(mode) {
  return MODE_BRANDING[mode] ?? MODE_BRANDING.cluster;
}

export function siteLabel(id) {
  return SITE_LABELS[id] ?? String(id);
}

const LABELS = {
  observationStatus: {
    OBSERVED: '已觀測（僅身份核對）',
    UNKNOWN: '未知',
  },
  pairStatus: {
    UNVERIFIED: '未驗證',
    VERIFIED_DISTINCT: '已核對為不同資料庫',
    SAME_DATABASE_REJECTED: '兩站為同一資料庫（已拒絕）',
  },
  check: {
    NOT_CHECKED: '未檢查',
  },
  reason: {
    NODE_NOT_CONFIGURED: '節點未設定',
  },
  channelState: {
    FORWARDING: '代理放行',
    BLOCKED: '已阻斷',
  },
  runState: {
    QUEUED: '排隊中',
    RUNNING: '執行中',
    COMMAND_SUCCEEDED: '指令退出碼 0（非遠端驗收）',
    FAILED: '失敗',
    CANCELLED: '已取消',
    INTERRUPTED: '已中斷（重啟後不再執行）',
  },
  remoteAcceptance: {
    NOT_RUN: '未執行',
  },
  reportStatus: {
    REVIEW_REQUIRED: '需人工審查報告',
  },
  planState: {
    PREVIEW_ONLY: '僅預覽',
  },
  counterGroup: {
    delivery: '投遞佇列 delivery',
    inbox: '收件匣 inbox',
    transfers: '傳輸 transfers',
    replicas: '副本 replicas',
    publication: '發佈 publication',
    cdn: 'CDN',
    gc: 'GC 狀態 gc',
  },
  counterScalar: {
    activeReferences: '有效引用 activeReferences',
    unpublishedReceipts: '未發佈回執 unpublishedReceipts',
    multipartJobs: 'Multipart 任務 multipartJobs',
  },
};

export function isMissing(value) {
  return value === undefined || value === null || value === '';
}

// 回傳「中文說明 · 原始代碼」，未知代碼原樣顯示，避免把未定義的值美化成已知狀態。
export function label(group, value) {
  if (isMissing(value)) return '（未提供）';
  const raw = String(value);
  const text = LABELS[group]?.[raw];
  return text ? `${text} · ${raw}` : raw;
}

export function shortLabel(group, value) {
  if (isMissing(value)) return '（未提供）';
  const raw = String(value);
  return LABELS[group]?.[raw] ?? raw;
}

export function parseTime(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  const t = Date.parse(value);
  return Number.isFinite(t) ? t : null;
}

export function formatTime(value) {
  const t = typeof value === 'number' ? value : parseTime(value);
  if (t === null || !Number.isFinite(t)) {
    return isMissing(value) ? '（未提供）' : `無法解析：${String(value)}`;
  }
  const d = new Date(t);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function formatAge(ms) {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '時間未知';
  if (ms < -2000) return '晚於本機時鐘';
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s} 秒前`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} 分鐘前`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h} 小時前`;
  return `${Math.floor(h / 24)} 天前`;
}

export function formatTimeWithAge(value, now) {
  const t = typeof value === 'number' ? value : parseTime(value);
  if (t === null) return formatTime(value);
  return `${formatTime(t)}（${formatAge(now - t)}）`;
}

export function formatCount(value) {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '—';
}

export function formatBytes(value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return '—';
  if (value < 1024) return `${value} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let v = value / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v.toFixed(1)} ${units[i]}（${value} B）`;
}

export function formatBool(value) {
  if (value === true) return '是';
  if (value === false) return '否';
  return '（未提供）';
}

export function shortId(value, length = 8) {
  if (isMissing(value)) return '（無）';
  const s = String(value);
  return s.length > length ? `${s.slice(0, length)}…` : s;
}

export function formatDuration(ms) {
  if (!Number.isFinite(ms) || ms < 0) return '—';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} 秒`;
  return `${Math.floor(s / 60)} 分 ${s % 60} 秒`;
}

const ERROR_CODE_TEXT = {
  ADMIN_REQUIRED: '需要 admin 角色',
  DEPLOYMENT_LOCKED: '部署執行已鎖定',
  NODE_REQUEST_FAILED: '節點請求失敗，結果未知（OUTCOME_UNKNOWN）',
  OUTCOME_UNKNOWN: '結果未知',
  NOT_LOGGED_IN: '尚未登入',
  CLIENT_VALIDATION: '用戶端檢查未通過，未送出請求',
  STALE_MEMBERSHIP_EPOCH: '成員 epoch 已變更',
  INITIAL_SYNC_PENDING: '新站仍在追平',
  LOCAL_ACCOUNT_ARCHIVE_PENDING: '仍有未歸檔帳戶',
  RETIREMENT_REFERENCES_PENDING: '仍有活引用待排空',
  RETIREMENT_OBJECTS_PENDING: '仍有本地物件待排空',
  RETIREMENT_UPLOADS_PENDING: '仍有未完成上傳',
  GC_DECISIONS_PENDING: '仍有未完成的 GC 決策',
  SITE_ID_RETAINED: '站點 ID 已保留，不可重用',
  MEMBER_IDENTITY_OR_SLOT_RETAINED: '成員身份或 slot 已保留，不可重用',
  CONTROL_LEASE_NOT_HELD: '此程序未持有控制租約（備機或租約失效）',
};

const STATUS_TEXT = {
  400: '參數錯誤',
  401: '需要登入或登入已失效',
  403: '此帳號不是管理員',
  404: '未設定或找不到',
  409: '目前狀態不允許此操作',
  423: '部署已鎖定',
  503: '節點或依賴服務不可用',
};

export function describeError(err) {
  if (!err) return '';
  const kind = err.kind;
  if (kind === 'network') return '無法連線至伺服器（網路錯誤或代理中斷）';
  if (kind === 'timeout') return '請求逾時，未收到伺服器回應';
  if (kind === 'aborted') return '請求已中止（登出或重新整理）';
  if (kind === 'parse') return `伺服器回應不是有效 JSON（HTTP ${err.status}）`;
  if (kind === 'validation') {
    const details = Array.isArray(err.details) && err.details.length ? `：${err.details.join(' ')}` : '';
    return `${ERROR_CODE_TEXT.CLIENT_VALIDATION}${details}`;
  }
  if (kind === 'auth') return ERROR_CODE_TEXT.NOT_LOGGED_IN;
  if (kind === 'business') {
    const msg = typeof err.serverMessage === 'string' && err.serverMessage ? err.serverMessage : '';
    const code = isMissing(err.code) ? '' : `（code ${err.code}）`;
    return `登入失敗${msg ? `：${msg}` : ''}${code}`;
  }
  if (kind === 'protocol') return `伺服器回應不符合契約：${err.serverMessage ?? '欄位缺失'}`;
  const parts = [];
  const codeText = err.code ? ERROR_CODE_TEXT[err.code] : null;
  parts.push(codeText ?? STATUS_TEXT[err.status] ?? '請求失敗');
  const meta = [`HTTP ${err.status}`];
  if (err.code) meta.push(String(err.code));
  return `${parts.join('')}（${meta.join(' · ')}）`;
}
