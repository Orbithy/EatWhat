import { executionReasons } from './deployment-state.js';
// 純資料模型：不碰 DOM，可在 Node 測試。
// 原則：讀取失敗或過期的資料一律降級為「失效」，絕不沿用為目前狀態；
// 未知、未檢查、未回報都保留原狀，不推導健康、收斂或已刪除。

import {
  ACTIVE_RUN_STATES,
  BLOCK_FRESHNESS_MS,
  FAULT_SECONDS_MAX,
  FAULT_SECONDS_MIN,
  SITE_IDS,
  STALE_AFTER_MS,
  SUITE_IDS,
  SUITE_SCOPES,
} from './constants.js';
import { parseTime, shortId } from './format.js';
import { isChannelName, isUuid } from './validate.js';

export function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

// ---------- 資源新鮮度 ----------

export function emptyResource() {
  return Object.freeze({ data: null, receivedAt: null, error: null, attemptedAt: null });
}

export function succeed(_resource, data, now) {
  return Object.freeze({ data, receivedAt: now, error: null, attemptedAt: now });
}

export function fail(resource, error, now) {
  const base = resource ?? emptyResource();
  return Object.freeze({ data: base.data, receivedAt: base.receivedAt, error, attemptedAt: now });
}

// NEVER：尚未成功讀取；FRESH：最近一次成功且未過期；STALE：太久沒成功；ERROR：最近一次失敗。
export function freshnessOf(resource, now, staleAfterMs = STALE_AFTER_MS) {
  if (!resource) return 'NEVER';
  if (resource.error) return 'ERROR';
  if (resource.receivedAt === null) return 'NEVER';
  if (now - resource.receivedAt > staleAfterMs) return 'STALE';
  return 'FRESH';
}

const FRESHNESS_RANK = { FRESH: 0, NEVER: 1, STALE: 2, ERROR: 3 };

export function connectionSummary(resources, now) {
  let level = 'FRESH';
  let error = null;
  let oldestSuccess = null;
  for (const res of resources) {
    const f = freshnessOf(res, now);
    if (FRESHNESS_RANK[f] > FRESHNESS_RANK[level]) level = f;
    if (!error && res?.error) error = res.error;
    if (res?.receivedAt !== null && res?.receivedAt !== undefined) {
      oldestSuccess = oldestSuccess === null ? res.receivedAt : Math.min(oldestSuccess, res.receivedAt);
    }
  }
  return { level, error, oldestSuccess };
}

// ---------- 站點觀測 ----------

// 只接受契約範圍內的整數上限；其他值一律視為缺值，不猜測。
export function parseBlockLimit(value) {
  return Number.isInteger(value) && value >= FAULT_SECONDS_MIN && value <= FAULT_SECONDS_MAX ? value : null;
}

export function normalizeChannels(channels) {
  if (!isPlainObject(channels)) return [];
  return Object.entries(channels)
    .filter(([name, value]) => isChannelName(name) && isPlainObject(value))
    .map(([name, value]) => ({
      name,
      maximumBlockSeconds: parseBlockLimit(value.maximumBlockSeconds),
      state: typeof value.state === 'string' ? value.state : null,
      expiresAt: typeof value.expiresAt === 'string' && value.expiresAt ? value.expiresAt : null,
      acceptedConnections: typeof value.acceptedConnections === 'number' ? value.acceptedConnections : null,
      forwardedBytes: typeof value.forwardedBytes === 'number' ? value.forwardedBytes : null,
      // 只有明確 true 才算 true；缺失視為未確認。
      routingVerified: value.routingVerified === true,
      routingVerifiedRaw: value.routingVerified,
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

function str(value) {
  return typeof value === 'string' && value !== '' ? value : null;
}

function deriveSite(node, id, freshness, payloadObservedAt) {
  const trusted = freshness === 'FRESH';
  const untrustedStatus = freshness === 'NEVER' ? 'LOADING' : 'STALE';
  if (!isPlainObject(node)) {
    return {
      id,
      present: false,
      reported: null,
      effectiveStatus: trusted ? 'MISSING' : untrustedStatus,
      reason: null,
      observedAt: null,
      lagMs: null,
      lagExceeded: null,
      channels: [],
      database: null,
      databaseMissing: false,
    };
  }
  const reported = str(node.observationStatus) ?? 'UNKNOWN';
  const nodeObservedAt = parseTime(node.observedAt);
  // 兩個時間都來自伺服器，用來比較可避開本機時鐘偏差。
  const lagMs = payloadObservedAt !== null && nodeObservedAt !== null ? payloadObservedAt - nodeObservedAt : null;
  let effectiveStatus;
  if (!trusted) effectiveStatus = untrustedStatus;
  else if (reported === 'OBSERVED') effectiveStatus = 'OBSERVED';
  else effectiveStatus = 'UNKNOWN';
  const database = isPlainObject(node.database) ? node.database : null;
  return {
    id,
    present: true,
    reported,
    effectiveStatus,
    reason: str(node.reason),
    observedAt: str(node.observedAt),
    lagMs,
    lagExceeded: lagMs === null ? null : lagMs > BLOCK_FRESHNESS_MS,
    siteId: str(node.siteId),
    instanceId: str(node.instanceId),
    mode: str(node.mode),
    readiness: str(node.readiness),
    storageReachability: str(node.storageReachability),
    deploymentExecutionEnabled: node.deploymentExecutionEnabled,
    channels: normalizeChannels(node.channels),
    database,
    databaseMissing: reported === 'OBSERVED' && database === null,
  };
}

export function deriveSites(resource, now) {
  const freshness = freshnessOf(resource, now);
  const trusted = freshness === 'FRESH';
  const payload = isPlainObject(resource?.data) ? resource.data : null;
  const payloadObservedAt = parseTime(payload?.observedAt);
  const reportedPair = str(payload?.pairStatus);
  const sites = {};
  for (const id of SITE_IDS) {
    sites[id] = deriveSite(isPlainObject(payload?.sites) ? payload.sites[id] : null, id, freshness, payloadObservedAt);
  }
  return {
    freshness,
    trusted,
    hasData: payload !== null,
    // 失效時不提供目前的配對狀態，只保留最後已知值供參考。
    pairStatus: trusted ? reportedPair : null,
    lastKnownPairStatus: reportedPair,
    observedAt: str(payload?.observedAt),
    receivedAt: resource?.receivedAt ?? null,
    error: resource?.error ?? null,
    sites,
  };
}

export function mergeKnownChannels(previous, payload) {
  const next = {};
  for (const id of SITE_IDS) {
    const set = new Set(Array.isArray(previous?.[id]) ? previous[id] : []);
    const node = isPlainObject(payload?.sites) ? payload.sites[id] : null;
    for (const ch of normalizeChannels(node?.channels)) set.add(ch.name);
    next[id] = [...set].sort();
  }
  return next;
}

const GROUP_ORDER = ['delivery', 'inbox', 'transfers', 'replicas', 'publication', 'cdn', 'gc'];

// 狀態分組鍵隨資料變化：任何「值皆為數字」的物件欄位都視為計數分組。
export function counterGroups(database) {
  if (!isPlainObject(database)) return [];
  const groups = [];
  for (const [key, value] of Object.entries(database)) {
    if (!isPlainObject(value)) continue;
    const entries = Object.entries(value);
    if (!entries.every(([, n]) => typeof n === 'number' && Number.isFinite(n))) continue;
    entries.sort(([a], [b]) => a.localeCompare(b));
    groups.push({ key, entries, total: entries.reduce((sum, [, n]) => sum + n, 0) });
  }
  const rank = (k) => {
    const i = GROUP_ORDER.indexOf(k);
    return i === -1 ? GROUP_ORDER.length : i;
  };
  return groups.sort((a, b) => rank(a.key) - rank(b.key) || a.key.localeCompare(b.key));
}

export function scalarCounters(database) {
  if (!isPlainObject(database)) return [];
  return Object.entries(database)
    .filter(([, v]) => typeof v === 'number' && Number.isFinite(v))
    .sort(([a], [b]) => a.localeCompare(b));
}

// ---------- 站點摘要與告警 ----------

export function channelSummary(channels) {
  const list = Array.isArray(channels) ? channels : [];
  const forwarding = list.filter((c) => c.state === 'FORWARDING').length;
  const blocked = list.filter((c) => c.state === 'BLOCKED').length;
  return { total: list.length, forwarding, blocked, other: list.length - forwarding - blocked };
}

const IDENTITY_CHECKS = [
  ['v35', 'V35'],
  ['restrictedRole', 'restrictedRole'],
  ['coordinationGuard', 'coordinationGuard'],
];

// 卡片頂部的重要告警。只描述 API 回報的事實與未知；沒有告警也不代表健康。
export function siteAlerts(sv) {
  if (!sv) return [];
  if (sv.effectiveStatus === 'LOADING') return [];
  const alerts = [];
  if (sv.effectiveStatus === 'STALE') {
    alerts.push({ tone: 'red', text: '資料已失效：以下為最後已知回報，不代表目前狀態。' });
  }
  if (!sv.present) {
    if (sv.effectiveStatus === 'MISSING') alerts.push({ tone: 'amber', text: 'API 未回報此站。' });
    return alerts;
  }
  if (sv.reported !== 'OBSERVED') {
    alerts.push({ tone: 'amber', text: `觀測狀態為 ${sv.reported}${sv.reason ? `（${sv.reason}）` : ''}，節點資料未知。` });
  }
  if (sv.lagExceeded === true) alerts.push({ tone: 'amber', text: '節點觀測時間落後超過 30 秒，BLOCK 會被拒絕。' });
  if (sv.databaseMissing) alerts.push({ tone: 'amber', text: '缺少 database 欄位（資料庫查詢失敗）：佇列與 GC 摘要不可用。' });
  if (sv.database) {
    for (const [key, name] of IDENTITY_CHECKS) {
      if (sv.database[key] !== true) alerts.push({ tone: 'red', text: `${name} 未通過核對（回報值：${String(sv.database[key])}）。` });
    }
  }
  if (sv.reported === 'OBSERVED') {
    if (sv.readiness === 'NOT_CHECKED' || sv.readiness === null) alerts.push({ tone: 'amber', text: '就緒檢查 readiness 未檢查。' });
    if (sv.storageReachability === 'NOT_CHECKED' || sv.storageReachability === null) {
      alerts.push({ tone: 'amber', text: '儲存可達性 storageReachability 未檢查。' });
    }
  }
  if (sv.deploymentExecutionEnabled !== undefined && sv.deploymentExecutionEnabled !== false) {
    alerts.push({ tone: 'red', text: '節點回報 deploymentExecutionEnabled 不是 false（異常值）。' });
  }
  for (const ch of sv.channels ?? []) {
    if (ch.state === 'BLOCKED') alerts.push({ tone: 'amber', text: `通道 ${ch.name} 目前已阻斷。` });
    if (ch.maximumBlockSeconds === null) alerts.push({ tone: 'amber', text: `通道 ${ch.name} 未回報 BLOCK 上限。` });
  }
  return alerts;
}

// ---------- GC 對照 ----------

function gcKey(entry) {
  if (str(entry.operation_id)) return `op:${entry.operation_id}`;
  return `file:${String(entry.file_id)}#${String(entry.revision)}`;
}

// 只並列 API 回傳的識別碼與狀態；不推導物件是否已實體刪除。
export function compareGc(sitesView) {
  const availability = {};
  const rows = new Map();
  for (const id of SITE_IDS) {
    const db = sitesView?.sites?.[id]?.database;
    const list = Array.isArray(db?.recentGc) ? db.recentGc : null;
    availability[id] = list !== null;
    if (!list) continue;
    for (const entry of list) {
      if (!isPlainObject(entry)) continue;
      const key = gcKey(entry);
      const row = rows.get(key) ?? { key, operationId: str(entry.operation_id), sides: {} };
      const prev = row.sides[id];
      if (!prev || (parseTime(entry.updated_at) ?? 0) > (parseTime(prev.updated_at) ?? 0)) row.sides[id] = entry;
      rows.set(key, row);
    }
  }
  const result = [...rows.values()].map((row) => {
    const present = SITE_IDS.filter((id) => row.sides[id]);
    const [a, b] = SITE_IDS.map((id) => row.sides[id] ?? null);
    const both = Boolean(a && b);
    const latest = Math.max(...present.map((id) => parseTime(row.sides[id].updated_at) ?? 0));
    return {
      key: row.key,
      operationId: row.operationId,
      sides: { [SITE_IDS[0]]: a, [SITE_IDS[1]]: b },
      presence: both ? 'BOTH' : present[0] === SITE_IDS[0] ? 'ONLY_CAMPUS' : 'ONLY_EXTERNAL',
      stateDiffers: both ? a.state !== b.state : null,
      sameRole: both ? Boolean(a.role) && a.role === b.role : null,
      identityMismatch: both ? String(a.file_id) !== String(b.file_id) || String(a.revision) !== String(b.revision) : null,
      latest,
    };
  });
  result.sort((x, y) => y.latest - x.latest || x.key.localeCompare(y.key));
  return { availability, rows: result, stale: !sitesView?.trusted };
}

export function gcSummary(gc) {
  const rows = Array.isArray(gc?.rows) ? gc.rows : [];
  return {
    total: rows.length,
    both: rows.filter((r) => r.presence === 'BOTH').length,
    oneSided: rows.filter((r) => r.presence !== 'BOTH').length,
    stateDiffers: rows.filter((r) => r.stateDiffers === true).length,
  };
}

// ---------- 通道故障 ----------

// capabilities.configuredChannels：操作員預先設定的離線恢復清單。
// 只用於 RESTORE 的通道選擇；不代表節點在線、代理狀態或 worker 路由。
export function normalizeConfiguredChannels(capabilities) {
  const raw = isPlainObject(capabilities) && isPlainObject(capabilities.configuredChannels) ? capabilities.configuredChannels : null;
  const result = {};
  for (const id of SITE_IDS) {
    const list = Array.isArray(raw?.[id]) ? raw[id] : [];
    const seen = new Set();
    result[id] = list
      .filter((c) => isPlainObject(c) && isChannelName(c.name) && !seen.has(c.name) && seen.add(c.name))
      .map((c) => ({
        name: c.name,
        maximumBlockSeconds: parseBlockLimit(c.maximumBlockSeconds),
        source: str(c.source),
        state: str(c.state),
        routingVerifiedRaw: c.routingVerified,
      }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }
  return { configured: raw !== null, sites: result };
}

function configuredNames(configured, site) {
  const list = configured?.sites?.[site];
  return Array.isArray(list) ? list.map((c) => c.name) : [];
}

function observedChannel(sitesView, site, channel) {
  if (!sitesView.trusted) return null;
  return (sitesView.sites[site]?.channels ?? []).find((c) => c.name === channel) ?? null;
}

// BLOCK 上限只取有效（新鮮）觀測中的值；缺值回傳 null，不猜測、不使用設定清單。
export function blockLimit(sitesView, site, channel) {
  return observedChannel(sitesView, site, channel)?.maximumBlockSeconds ?? null;
}

export function channelOptions(sitesView, knownChannels, site, action, configured) {
  const currentList = sitesView.trusted ? sitesView.sites[site]?.channels ?? [] : [];
  const current = currentList.map((c) => c.name);
  if (action === 'BLOCK') {
    return currentList.map((c) => ({
      value: c.name,
      label: c.maximumBlockSeconds === null ? `${c.name}（上限缺值）` : `${c.name}（上限 ${c.maximumBlockSeconds} 秒）`,
    }));
  }
  const known = Array.isArray(knownChannels?.[site]) ? knownChannels[site] : [];
  const fromConfig = configuredNames(configured, site);
  const names = [...new Set([...current, ...known, ...fromConfig])].sort();
  return names.map((name) => {
    let suffix = '';
    if (!current.includes(name)) suffix = known.includes(name) ? '（最後已知）' : '（設定清單，未觀測）';
    return { value: name, label: `${name}${suffix}` };
  });
}

export function faultPrecheck({ sitesView, knownChannels, configured, site, channel, action, seconds }) {
  const blocking = [];
  const warnings = [];
  const sv = sitesView.sites[site];
  if (action === 'BLOCK') {
    if (!sitesView.trusted) blocking.push('站點資料已失效或過期，無法確認目前狀態，不能送出 BLOCK。');
    if (sitesView.pairStatus !== 'VERIFIED_DISTINCT') {
      blocking.push(`BLOCK 需要兩站配對狀態為 VERIFIED_DISTINCT，目前為 ${sitesView.pairStatus ?? '未知'}。`);
    }
    if (!sv || sv.effectiveStatus !== 'OBSERVED') blocking.push('目標站點目前不是 OBSERVED。');
    if (sv?.lagExceeded === true) blocking.push('目標節點的觀測時間已超過 30 秒。');
    if (sv?.lagExceeded === null && sv?.effectiveStatus === 'OBSERVED') {
      warnings.push('無法判斷節點觀測時間差，由伺服器做最終判定。');
    }
    if (channel) {
      const observed = (sv?.channels ?? []).find((c) => c.name === channel);
      if (!observed) {
        blocking.push('目前觀測中沒有這個通道。');
      } else if (observed.maximumBlockSeconds === null) {
        blocking.push('節點未回報此通道的 maximumBlockSeconds（缺值），不能 BLOCK。');
      } else if (Number.isInteger(seconds) && seconds > observed.maximumBlockSeconds) {
        blocking.push(`秒數 ${seconds} 超過此通道的節點上限 ${observed.maximumBlockSeconds} 秒。`);
      }
    }
  } else if (action === 'RESTORE') {
    const current = sitesView.trusted ? (sv?.channels ?? []).map((c) => c.name) : [];
    const known = Array.isArray(knownChannels?.[site]) ? knownChannels[site] : [];
    const fromConfig = configuredNames(configured, site);
    if (!sitesView.trusted || sv?.effectiveStatus !== 'OBSERVED') {
      warnings.push('目標站點目前沒有有效觀測；RESTORE 仍可送出，但無法確認節點是否在線或通道目前狀態。');
    }
    if (channel && !current.includes(channel)) {
      if (known.includes(channel)) warnings.push('此通道來自本頁的最後已知觀測，不是目前觀測。');
      else if (fromConfig.includes(channel)) warnings.push('此通道來自操作員設定清單（configuredChannels），未經節點觀測。');
      else blocking.push('此通道不在目前觀測、最後已知觀測或設定清單中。');
    }
  } else {
    blocking.push('請選擇 BLOCK 或 RESTORE。');
  }
  if (!channel) blocking.push('沒有可選擇的通道。');
  return { blocking, warnings };
}

// ---------- 驗收任務 ----------

export function isRunActive(run) {
  return Boolean(run) && ACTIVE_RUN_STATES.includes(run.state);
}

export function normalizeRun(run) {
  if (!isPlainObject(run) || !isUuid(run.id)) return null;
  return run;
}

// lookups：Map<id, { run, fetchedAt, error }>，來自手動查詢或本頁啟動/取消的回應。
// 清單（本程序記錄）較權威，同 ID 時優先使用清單。
export function mergeRuns(listed, lookups) {
  const map = new Map();
  if (lookups) {
    for (const [id, entry] of lookups) {
      const run = normalizeRun(entry?.run);
      if (run && run.id === id) map.set(id, { run, source: 'lookup', fetchedAt: entry.fetchedAt ?? null, error: entry.error ?? null });
    }
  }
  for (const raw of Array.isArray(listed) ? listed : []) {
    const run = normalizeRun(raw);
    if (run) map.set(run.id, { run, source: 'list', fetchedAt: null, error: null });
  }
  return [...map.values()].sort(
    (a, b) => (parseTime(b.run.createdAt) ?? 0) - (parseTime(a.run.createdAt) ?? 0) || a.run.id.localeCompare(b.run.id),
  );
}

export function findActiveRun(entries) {
  return entries.find((e) => isRunActive(e.run)) ?? null;
}

export function runNotes(run) {
  const notes = [];
  if (!run) return notes;
  switch (run.state) {
    case 'COMMAND_SUCCEEDED':
      notes.push('只表示 Maven 指令退出碼為 0，不代表遠端驗收通過。');
      break;
    case 'INTERRUPTED':
      notes.push('伺服器重啟後無法再擁有此任務；它不在執行中，結果未知。');
      break;
    case 'CANCELLED':
      notes.push('取消不會撤銷已完成的測試操作，請另行確認資源清理。');
      break;
    case 'FAILED':
      notes.push('任務失敗；日誌留在伺服器私有狀態目錄，API 不提供。');
      break;
    default:
      break;
  }
  if (run.reportStatus === 'REVIEW_REQUIRED') notes.push('報告需要人工審查。');
  if (run.remoteAcceptance === undefined || run.remoteAcceptance === null) {
    notes.push('API 未提供 remoteAcceptance；遠端驗收狀態未知。');
  } else if (run.remoteAcceptance === 'NOT_RUN') {
    notes.push('遠端驗收尚未執行（NOT_RUN）。');
  } else {
    notes.push(`remoteAcceptance=${String(run.remoteAcceptance)}（API 原值，本頁不據此判定驗收結果）。`);
  }
  if (run.deploymentPerformed !== false) notes.push('deploymentPerformed 不是 false，回應異常，請人工確認。');
  if (typeof run.reason === 'string' && run.reason) notes.push(`原因：${run.reason}`);
  return notes;
}

export function startBlockers(suite, { runsFreshness, activeEntry }) {
  const reasons = [];
  if (!suite || !SUITE_IDS.includes(suite.id)) reasons.push('不是契約定義的固定套件。');
  if (suite?.enabled !== true) reasons.push('伺服器未啟用此套件（需設定 repository 與 java-home）。');
  if (suite && SUITE_IDS.includes(suite.id) && suite.scope !== undefined && suite.scope !== SUITE_SCOPES[suite.id]) {
    reasons.push(`伺服器回報的 scope ${String(suite.scope)} 與契約 ${SUITE_SCOPES[suite.id]} 不一致。`);
  }
  if (runsFreshness !== 'FRESH') reasons.push('任務清單未即時更新，無法確認是否已有任務在執行。');
  if (activeEntry) {
    reasons.push(`已有任務 ${shortId(activeEntry.run.id)} 處於 ${activeEntry.run.state}，一次只能執行一個。`);
  }
  return reasons;
}

export function normalizeSuites(data) {
  if (!Array.isArray(data)) return [];
  return data
    .filter((s) => isPlainObject(s) && typeof s.id === 'string' && s.id)
    .map((s) => ({ id: s.id, scope: s.scope, enabled: s.enabled === true }));
}

// ---------- 部署預覽 ----------

export function normalizeReleases(data) {
  if (!Array.isArray(data)) return [];
  return data.filter((r) => isPlainObject(r) && typeof r.id === 'string' && r.id);
}

export function deploymentLockReasons(capabilities, plan) {
  const reasons = executionReasons(capabilities, plan);
  return { locked: reasons.length > 0, reasons };
}

export function planShaStatus(plan, releases) {
  if (!isPlainObject(plan) || typeof plan.artifactSha256 !== 'string') return 'UNKNOWN';
  const release = releases.find((r) => r.id === plan.releaseId);
  if (!release || typeof release.sha256 !== 'string') return 'UNKNOWN';
  return release.sha256.toLowerCase() === plan.artifactSha256.toLowerCase() ? 'MATCH' : 'MISMATCH';
}

// 把未定型的 blockers / steps 轉成「標題 + 細項」，全部為字串。
export function toDisplayItems(value) {
  if (value === undefined || value === null) return [];
  const list = Array.isArray(value) ? value : [value];
  return list.map((item) => {
    if (typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean') {
      return { title: String(item), details: [] };
    }
    if (!isPlainObject(item)) return { title: JSON.stringify(item) ?? '', details: [] };
    const titleKey = ['title', 'name', 'description', 'code', 'id', 'step', 'action'].find((k) => typeof item[k] === 'string' && item[k]);
    const details = Object.entries(item)
      .filter(([k]) => k !== titleKey)
      .map(([k, v]) => [k, typeof v === 'string' ? v : JSON.stringify(v) ?? String(v)]);
    return { title: titleKey ? item[titleKey] : '（未命名項目）', details };
  });
}

// ---------- 稽核 ----------

// API 為時間升序；介面改為新到舊。
export function auditEntries(data) {
  if (!Array.isArray(data)) return [];
  return data.filter(isPlainObject).slice().reverse();
}

// details 結構未完全定型：seconds 為故障請求時限，其餘鍵值以文字原樣列出。
export function auditDetails(entry) {
  const details = isPlainObject(entry?.details) ? entry.details : null;
  if (!details) return { seconds: null, extra: [] };
  const seconds = Number.isInteger(details.seconds) ? details.seconds : null;
  const extra = Object.entries(details)
    .filter(([k]) => k !== 'seconds' || seconds === null)
    .map(([k, v]) => [k, typeof v === 'string' ? v : JSON.stringify(v) ?? String(v)]);
  return { seconds, extra };
}

export function auditOutcomeTone(outcome) {
  const text = String(outcome ?? '').toUpperCase();
  if (!text) return 'neutral';
  if (text.includes('UNKNOWN')) return 'amber';
  if (/(FAIL|REJECT|DENIED|LOCKED|ERROR|FORBIDDEN)/.test(text)) return 'red';
  return 'neutral';
}
