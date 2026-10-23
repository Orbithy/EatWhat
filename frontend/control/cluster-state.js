// 多站叢集的純資料模型：不碰 DOM，可在 Node 測試。
// 原則：
// - 新鮮度以單調時間（performance.now）自「成功請求的開始時間」起算；輪詢進行中不延長舊資料。
// - 缺少觀測一律顯示「未觀測／未知」，不補成在線或零積壓。
// - 不推導健康、收斂或實體刪除；後端錯誤條件只說明，不提供豁免。

import { executionReasons } from './deployment-state.js';

import {
  CHANGE_STATES,
  CHANGE_VOTE_PHASES,
  CLUSTER_BLOCK_FRESHNESS_MS,
  CLUSTER_ID_RE,
  GC_STATES,
  GC_VOTE_PHASES,
  OBSERVED_MEMBER_STATES,
  PARTICIPATING_MEMBER_STATES,
} from './constants.js';
import { describeError, parseTime } from './format.js';
import { isPlainObject, normalizeChannels, parseBlockLimit, toDisplayItems } from './state.js';
import { isUuid } from './validate.js';

const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
const int = (v, min = 0) => (Number.isSafeInteger(v) && v >= min ? v : null);
const arr = (v) => (Array.isArray(v) ? v : []);
const isClusterId = (v) => typeof v === 'string' && CLUSTER_ID_RE.test(v);

// ---------- 單調時間資源 ----------

export function emptyMono() {
  return Object.freeze({ data: null, startedAt: null, receivedAt: null, receivedWall: null, error: null, inFlightSince: null });
}

export function monoStart(res, now) {
  return Object.freeze({ ...(res ?? emptyMono()), inFlightSince: now });
}

export function monoSucceed(_res, data, startedAt, receivedAt, receivedWall) {
  return Object.freeze({ data, startedAt, receivedAt, receivedWall, error: null, inFlightSince: null });
}

export function monoFail(res, error) {
  return Object.freeze({ ...(res ?? emptyMono()), error, inFlightSince: null });
}

// 年齡從成功請求的「開始」時間算起：資料反映的時點可能早於接收時間。
export function monoAge(res, now) {
  if (!res || res.startedAt === null || res.startedAt === undefined) return null;
  return Math.max(0, now - res.startedAt);
}

export function monoFreshness(res, now, thresholdMs) {
  if (!res) return 'NEVER';
  if (res.error) return 'ERROR';
  if (res.startedAt === null || res.startedAt === undefined) return 'NEVER';
  return now - res.startedAt > thresholdMs ? 'STALE' : 'FRESH';
}

export function inFlightFor(res, now) {
  return res && res.inFlightSince !== null && res.inFlightSince !== undefined ? Math.max(0, now - res.inFlightSince) : null;
}

// ---------- 控制領導（主備租約） ----------
// 主備接管只發生在同一邏輯 CAMPUS 權威站的程序之間（coordinatorTransferEnabled 仍為 false）。
// 只有「此程序持有租約」時才開放控制寫入；備機、租約過期、未取得或未知時一律停用。
// expiresAt 來自資料庫時鐘，原樣顯示，不與本機時鐘相減。

export const LEADERSHIP_STATES = Object.freeze(['SINGLE_PROCESS', 'UNCLAIMED', 'HELD', 'EXPIRED']);

export const TAKEOVER_MODES = Object.freeze(['MANUAL_HANDOFF_ONLY', 'AUTOMATIC']);
const TAKEOVER_TEXT = Object.freeze({ MANUAL_HANDOFF_ONLY: '僅手動交接', AUTOMATIC: '租約過期後可自動接管' });

export function takeoverModeText(lead) {
  return TAKEOVER_TEXT[lead?.takeoverMode] ?? '接管模式未聲明';
}

export function normalizeLeadership(data) {
  if (!isPlainObject(data) || typeof data.state !== 'string' || !data.state) return null;
  return {
    state: data.state,
    localLeader: data.localLeader === true,
    localLeaderRaw: data.localLeader,
    generation: Number.isSafeInteger(data.generation) && data.generation >= 1 ? data.generation : null,
    expiresAt: typeof data.expiresAt === 'string' && data.expiresAt ? data.expiresAt : null,
    // 唯讀：只保留後端聲明的已知值；缺少或未知時不帶此欄位，不推斷任何接管權限。
    ...(TAKEOVER_MODES.includes(data.takeoverMode) ? { takeoverMode: data.takeoverMode } : {}),
    ...(isPlainObject(data.handoff) ? { handoff: data.handoff } : {}),
  };
}

export function leadershipRole(lead) {
  if (!lead) return 'UNKNOWN';
  if (lead.state === 'HELD') return lead.localLeader ? 'LEADER' : 'STANDBY';
  if (lead.state === 'SINGLE_PROCESS') return lead.localLeader ? 'SINGLE' : 'UNKNOWN';
  if (lead.state === 'EXPIRED') return 'EXPIRED';
  if (lead.state === 'UNCLAIMED') return 'UNCLAIMED';
  return 'UNKNOWN';
}

export function leadershipWriteReasons(lead) {
  switch (leadershipRole(lead)) {
    case 'LEADER':
      if (['WAITING', 'TRANSFERRING'].includes(lead?.handoff?.state)) return ['主中控正在交接：目前部署繼續完成，新控制操作暫停；可追蹤或取消等待中的交接。'];
      return [];
    case 'SINGLE':
      return [];
    case 'STANDBY':
      return ['此程序是備機（standby）：控制租約由另一個權威程序持有，控制寫入已停用；讀取仍可用。'];
    case 'EXPIRED':
      return ['控制租約已過期，尚無程序接管；控制寫入已停用。'];
    case 'UNCLAIMED':
      return ['控制租約尚未被任何程序取得；控制寫入已停用。'];
    default:
      if (lead && lead.state === 'SINGLE_PROCESS') return ['單程序模式卻回報此程序不是 leader（異常值）；控制寫入已停用。'];
      if (lead) return [`未知的租約狀態 ${String(lead.state)}；控制寫入已停用。`];
      return ['控制領導狀態未知（讀取失敗、過期，或剛收到 CONTROL_LEASE_NOT_HELD），控制寫入已停用。'];
  }
}

function usable(res, freshness, invalidatedAt) {
  if (freshness !== 'FRESH' || !res) return false;
  return invalidatedAt === null || invalidatedAt === undefined || (res.startedAt ?? -Infinity) > invalidatedAt;
}

// 優先使用 /leadership；若它不可用，才採用同樣新鮮的 status.leadership。
// invalidatedAt：收到 CONTROL_LEASE_NOT_HELD 的時間；在此之前開始的讀取一律不採信。
export function leadershipView({ leadershipRes, leadershipFreshness, statusRes, statusFreshness, invalidatedAt = null }) {
  let lead = null;
  let source = null;
  if (usable(leadershipRes, leadershipFreshness, invalidatedAt)) {
    lead = normalizeLeadership(leadershipRes.data);
    source = lead ? 'leadership' : null;
  }
  if (!lead && usable(statusRes, statusFreshness, invalidatedAt)) {
    lead = normalizeLeadership(isPlainObject(statusRes.data) ? statusRes.data.leadership : null);
    source = lead ? 'status' : null;
  }
  // 最後已知值只供顯示，不作為寫入依據。
  const lastKnown = lead ?? normalizeLeadership(leadershipRes?.data) ?? normalizeLeadership(isPlainObject(statusRes?.data) ? statusRes.data.leadership : null);
  const reasons = leadershipWriteReasons(lead);
  return { lead, lastKnown, source, role: leadershipRole(lead), writable: reasons.length === 0, reasons, invalidated: invalidatedAt !== null && invalidatedAt !== undefined && !lead };
}

// 確認對話框開啟期間可能失租或換 leader：確認後以當下領導狀態重新檢查，generation 變了就要求重新確認。
export function recheckLeadership({ captured, view }) {
  if (!view.writable) return view.reasons.map((r) => `確認期間領導狀態改變：${r}`);
  const now = view.lead?.generation ?? null;
  if (captured && captured.generation !== now) {
    return [`確認期間控制租約 generation 已由 ${captured.generation ?? '無'} 變為 ${now ?? '無'}，未送出；請檢視最新狀態後重新確認。`];
  }
  return [];
}

// ---------- 入口判定 ----------

// 登入時 /cluster/capabilities 的錯誤處理：
// 401／403／404 拒絕進入（404 = 入口不是多站中控或功能未開啟；不自動降級、不改呼叫舊接口）；
// 503、逾時、網路錯誤仍進入畫面並標示未知。
export function isEntryRejection(err) {
  return Boolean(err) && err.kind === 'http' && [401, 403, 404].includes(err.status);
}

// ---------- 正規化 ----------

// camelCase 精確身份（participants、observations.member、preview.members）。
export function normalizeIdentity(m) {
  if (!isPlainObject(m) || !isClusterId(m.siteId)) return null;
  return { siteId: m.siteId, instanceId: str(m.instanceId), mode: str(m.mode), slot: int(m.allocationSlot) };
}

// snake_case 持久化登記（只出現在 status.members）。
export function normalizeRegistration(r) {
  if (!isPlainObject(r) || !isClusterId(r.site_id)) return null;
  return {
    siteId: r.site_id,
    instanceId: str(r.instance_id),
    profileId: str(r.profile_id),
    mode: str(r.mode),
    slot: int(r.allocation_slot),
    state: str(r.state),
    fingerprint: str(r.database_fingerprint),
    joinedEpoch: int(r.joined_epoch),
    retiredEpoch: int(r.retired_epoch),
  };
}

export function normalizeVote(v) {
  if (!isPlainObject(v) || !str(v.instance_id) || !str(v.phase)) return null;
  return { siteId: str(v.site_id), instanceId: v.instance_id, phase: v.phase, createdAt: str(v.created_at) };
}

export function normalizeChange(c) {
  if (!isPlainObject(c) || !isUuid(c.change_id)) return null;
  return {
    id: c.change_id,
    baseEpoch: int(c.base_epoch),
    nextEpoch: int(c.next_epoch),
    kind: str(c.kind),
    siteId: str(c.site_id),
    state: str(c.state),
    participants: arr(c.participants).map(normalizeIdentity).filter(Boolean),
    votes: arr(c.votes).map(normalizeVote).filter(Boolean),
  };
}

function bySlot(a, b) {
  const sa = a.slot ?? Number.MAX_SAFE_INTEGER;
  const sb = b.slot ?? Number.MAX_SAFE_INTEGER;
  return sa - sb || a.siteId.localeCompare(b.siteId);
}

export function normalizeStatus(data) {
  if (!isPlainObject(data)) return null;
  const worker = isPlainObject(data.worker) ? { running: data.worker.running === true, lastCode: str(data.worker.lastCode) } : null;
  return {
    clusterId: str(data.clusterId),
    epoch: int(data.epoch),
    members: arr(data.members).map(normalizeRegistration).filter(Boolean).sort(bySlot),
    pendingChanges: arr(data.pendingChanges).map(normalizeChange).filter(Boolean),
    worker,
  };
}

export function normalizeChanges(data) {
  return arr(data).map(normalizeChange).filter(Boolean);
}

export function normalizeObservation(o) {
  if (!isPlainObject(o)) return null;
  const member = normalizeIdentity(o.member);
  if (!member) return null;
  const health = str(o.health) ?? 'UNKNOWN';
  const reachable = health === 'REACHABLE';
  const node = reachable && isPlainObject(o.node) ? o.node : null;
  const control = isPlainObject(node?.control) ? node.control : null;
  const drainRaw = isPlainObject(node?.drain) ? node.drain : null;
  const syncRaw = reachable && isPlainObject(o.sync) ? o.sync : null;
  return {
    member,
    health,
    code: str(o.code),
    reachable,
    // UNKNOWN 時不採用任何計數，避免顯示為零積壓。
    sync: syncRaw
      ? { pendingDeliveries: int(syncRaw.pendingDeliveries), pendingInbox: int(syncRaw.pendingInbox), fileTransfers: int(syncRaw.fileTransfers) }
      : null,
    drain: drainRaw
      ? {
          liveReferences: int(drainRaw.liveReferences),
          localObjects: int(drainRaw.localObjects),
          pendingUploads: int(drainRaw.pendingUploads),
          unarchivedAccounts: int(drainRaw.unarchivedAccounts),
        }
      : null,
    dataPlaneReady: typeof node?.dataPlaneReady === 'boolean' ? node.dataPlaneReady : null,
    nodeFingerprint: str(node?.databaseFingerprint),
    nodeClusterId: str(node?.clusterId),
    protocolVersion: node?.protocolVersion ?? null,
    membership: isPlainObject(node?.membership)
      ? { epoch: int(node.membership.epoch), state: str(node.membership.state), pendingChange: str(node.membership.pending_change) }
      : null,
    control,
    channels: normalizeChannels(control?.channels),
  };
}

export function normalizeObservations(data) {
  return arr(data).map(normalizeObservation).filter(Boolean);
}

export function normalizeProfiles(data) {
  return arr(data)
    .filter((p) => isPlainObject(p) && isClusterId(p.id) && isClusterId(p.siteId))
    .map((p) => ({ id: p.id, siteId: p.siteId, instanceId: str(p.instanceId), mode: str(p.mode), slot: int(p.allocationSlot) }))
    .sort(bySlot);
}

export function normalizeOfflineChannels(data) {
  return arr(data)
    .filter((e) => isPlainObject(e) && isClusterId(e.siteId) && str(e.instanceId))
    .map((e) => ({
      siteId: e.siteId,
      instanceId: e.instanceId,
      channels: Object.entries(isPlainObject(e.channels) ? e.channels : {})
        .filter(([name]) => isClusterId(name))
        .map(([name, limit]) => ({ name, maximumBlockSeconds: parseBlockLimit(limit) }))
        .sort((a, b) => a.name.localeCompare(b.name)),
      routingVerifiedRaw: e.routingVerified,
    }));
}

export function normalizeCapabilities(data) {
  if (!isPlainObject(data)) return null;
  return {
    topology: str(data.topology),
    maximumActiveSites: int(data.maximumActiveSites, 1),
    allocationSlots: int(data.allocationSlots, 1),
    coordinatorTransferEnabled: data.coordinatorTransferEnabled,
    leadershipHandoffEnabled: data.leadershipHandoffEnabled,
    businessSessionShared: data.businessSessionShared,
    deploymentExecutionEnabled: data.deploymentExecutionEnabled,
    remoteFailureMatrixAccepted: data.remoteFailureMatrixAccepted,
    // 部署中斷後手動恢復：與新部署開關獨立；只有明確 true 才算支援。
    deploymentRecoveryEnabled: data.deploymentRecoveryEnabled === true,
  };
}

// 能力回報偏離契約常數時列為異常；前端仍維持鎖定與限制。
export function capabilityAnomalies(caps) {
  if (!caps) return [];
  const expected = {
    topology: 'SINGLE_CLUSTER',
    coordinatorTransferEnabled: false,
    businessSessionShared: true,
    remoteFailureMatrixAccepted: false,
  };
  const out = [];
  for (const [key, value] of Object.entries(expected)) {
    if (caps[key] !== value) out.push(`${key} 回報 ${String(caps[key])}，與契約 ${String(value)} 不同；前端仍依契約限制操作。`);
  }
  return out;
}

// ---------- 成員檢視 ----------

function sameIdentity(a, b) {
  return a && b && a.siteId === b.siteId && a.instanceId !== null && a.instanceId === b.instanceId;
}

// observed：REACHABLE／UNKNOWN／STALE／LOADING／NOT_OBSERVED
export function buildMemberViews({ status, observations, observationFreshness }) {
  const list = normalizeObservations(observations);
  return (status?.members ?? []).map((member) => {
    const observable = OBSERVED_MEMBER_STATES.includes(member.state);
    const observation = observable ? list.find((o) => sameIdentity(o.member, member)) ?? null : null;
    let observed;
    if (!observable) observed = 'NOT_OBSERVED';
    else if (observationFreshness === 'NEVER') observed = 'LOADING';
    else if (observationFreshness !== 'FRESH') observed = 'STALE';
    else if (!observation) observed = 'NOT_OBSERVED';
    else observed = observation.reachable ? 'REACHABLE' : 'UNKNOWN';
    return { ...member, observable, observation, observed, trusted: observed === 'REACHABLE' || observed === 'UNKNOWN' };
  });
}

const MEMBERSHIP_STATE_MAP = { REGISTERED: 'UNJOINED' };

export function memberAlerts(view, clusterEpoch) {
  const alerts = [];
  if (!view) return alerts;
  const obs = view.observation;
  if (view.observed === 'STALE') alerts.push({ tone: 'red', text: '觀測資料已失效：以下為最後已知回報，不代表目前狀態。' });
  if (view.observed === 'UNKNOWN') {
    alerts.push({ tone: 'amber', text: `節點觀測 UNKNOWN${obs?.code ? `（${obs.code}）` : ''}：同步積壓與排空數量未知。` });
  }
  if (view.observed === 'NOT_OBSERVED' && view.observable) alerts.push({ tone: 'amber', text: '觀測結果中沒有此站。' });
  if (view.state === 'DRAINING') alerts.push({ tone: 'amber', text: '退役意圖已保留（DRAINING）：需排空後才能完成退役。' });
  if (!obs || !obs.reachable) return alerts;
  if (view.fingerprint && obs.nodeFingerprint && view.fingerprint !== obs.nodeFingerprint) {
    alerts.push({ tone: 'red', text: '節點回報的資料庫指紋與登記不同。' });
  }
  if (obs.membership) {
    if (clusterEpoch !== null && clusterEpoch !== undefined && obs.membership.epoch !== null && obs.membership.epoch !== clusterEpoch) {
      alerts.push({ tone: 'amber', text: `節點回報 epoch ${obs.membership.epoch}，叢集 epoch 為 ${clusterEpoch}。` });
    }
    const expected = MEMBERSHIP_STATE_MAP[view.state] ?? view.state;
    if (obs.membership.state && expected && obs.membership.state !== expected) {
      alerts.push({ tone: 'amber', text: `節點回報成員狀態 ${obs.membership.state}，登記為 ${view.state}。` });
    }
  }
  if (obs.dataPlaneReady === false) alerts.push({ tone: 'amber', text: 'dataPlaneReady=false：節點軟體能力尚未就緒。' });
  const control = obs.control;
  if (!control) {
    alerts.push({ tone: 'amber', text: '節點未回報 control 快照：代理通道與就緒檢查未知。' });
  } else {
    if (control.readiness !== undefined && control.readiness !== 'READY') {
      alerts.push({ tone: 'amber', text: `就緒檢查 readiness：${control.readiness === 'NOT_CHECKED' ? '未檢查' : String(control.readiness)}。` });
    }
    if (control.storageReachability !== undefined && control.storageReachability !== 'REACHABLE') {
      alerts.push({
        tone: 'amber',
        text: `儲存可達性 storageReachability：${control.storageReachability === 'NOT_CHECKED' ? '未檢查' : String(control.storageReachability)}。`,
      });
    }
    const db = isPlainObject(control.database) ? control.database : null;
    if (db) {
      for (const key of ['v35', 'restrictedRole', 'coordinationGuard']) {
        if (db[key] !== true) alerts.push({ tone: 'red', text: `${key} 未通過核對（回報值：${String(db[key])}）。` });
      }
    }
  }
  for (const ch of obs.channels) {
    if (ch.state === 'BLOCKED') alerts.push({ tone: 'amber', text: `通道 ${ch.name} 目前已阻斷。` });
    if (ch.maximumBlockSeconds === null) alerts.push({ tone: 'amber', text: `通道 ${ch.name} 未回報 BLOCK 上限。` });
  }
  if (view.state === 'DRAINING') {
    if (!obs.drain) alerts.push({ tone: 'amber', text: '排空數量未知。' });
    else {
      const pending = drainPending(obs.drain);
      if (pending.length) alerts.push({ tone: 'amber', text: `仍需排空：${pending.join('、')}。` });
    }
  }
  return alerts;
}

const DRAIN_LABELS = {
  liveReferences: '活引用',
  localObjects: '本地物件',
  pendingUploads: '未完成上傳',
  unarchivedAccounts: '未歸檔帳戶',
};

export function drainItems(drain) {
  return Object.keys(DRAIN_LABELS).map((key) => ({ key, label: DRAIN_LABELS[key], value: drain ? drain[key] ?? null : null }));
}

export function drainPending(drain) {
  return drainItems(drain)
    .filter((i) => i.value === null || i.value > 0)
    .map((i) => (i.value === null ? `${i.label}（未知）` : `${i.label} ${i.value}`));
}

export function memberCounts(members) {
  const counts = Object.fromEntries(['REGISTERED', 'JOINING', 'ACTIVE', 'DRAINING', 'RETIRED'].map((s) => [s, 0]));
  for (const m of members ?? []) if (counts[m.state] !== undefined) counts[m.state] += 1;
  const participating = (members ?? []).filter((m) => PARTICIPATING_MEMBER_STATES.includes(m.state)).length;
  return { ...counts, participating };
}

// ---------- 成員變更 ----------

export function openChanges(status) {
  return (status?.pendingChanges ?? []).filter((c) => c.state !== 'APPLIED');
}

export function actionContext({ status, statusFreshness, capabilities }) {
  const caps = capabilities ?? null;
  return {
    statusFresh: statusFreshness === 'FRESH' && status !== null && status.epoch !== null,
    epoch: status?.epoch ?? null,
    open: openChanges(status),
    counts: memberCounts(status?.members),
    maxActive: caps?.maximumActiveSites ?? 16,
    coordinatorTransferEnabled: caps?.coordinatorTransferEnabled === true,
  };
}

export const MEMBER_ACTIONS = Object.freeze(['CANCEL', 'JOIN', 'RETIRE']);

export function memberActionCheck(action, member, ctx) {
  const blocking = [];
  const warnings = [];
  if (!member) return { blocking: ['請選擇站點。'], warnings };
  if (!ctx.statusFresh) blocking.push('叢集狀態不是最新（讀取失敗或過期），無法取得可信的 expectedEpoch。');
  if (action === 'CANCEL') {
    if (member.state !== 'REGISTERED') blocking.push('只有尚未加入的 REGISTERED 登記可以取消；已開始加入不提供取消。');
    warnings.push('取消後身份與 slot 仍會保留為墓碑，不可重用。');
  } else if (action === 'JOIN') {
    if (member.state !== 'REGISTERED') blocking.push('只有 REGISTERED 的站可以開始加入。');
    if (ctx.open.length) blocking.push(`仍有未 APPLIED 的成員變更（${ctx.open.map((c) => `${c.kind ?? '?'} ${c.siteId ?? ''}`).join('、')}），只有 APPLIED 後才能開下一個。`);
    if (ctx.counts.participating >= ctx.maxActive) blocking.push(`同時參與站點已達上限 ${ctx.maxActive}。`);
    warnings.push('加入開始後不提供取消；來源站自行追平，需等待變更完成。');
  } else if (action === 'RETIRE') {
    if (!['ACTIVE', 'DRAINING'].includes(member.state)) blocking.push('只有 ACTIVE 或 DRAINING 的站可以退役。');
    if (member.mode === 'CAMPUS' && !ctx.coordinatorTransferEnabled) blocking.push('CAMPUS 權威站不可退役（尚未提供權威站轉移）。');
    if (ctx.open.length) blocking.push('仍有未 APPLIED 的成員變更，請先完成或續行。');
    warnings.push('必須先排空活引用、本地物件、未完成上傳與帳戶責任；不提供強制移除。');
  } else {
    blocking.push('請選擇動作。');
  }
  return { blocking, warnings };
}

export function profileRegistration(profile, members) {
  if (!profile) return { allowed: false, reasons: ['請選擇 profile。'] };
  const retained = (members ?? []).find(
    (m) => m.siteId === profile.siteId || (profile.instanceId && m.instanceId === profile.instanceId) || (profile.slot !== null && m.slot === profile.slot),
  );
  if (retained) return { allowed: false, reasons: [`身份已保留（${retained.siteId} · ${retained.state ?? '未知'}），站點 ID、UUID 與 slot 不可重用。`] };
  return { allowed: true, reasons: [] };
}

// ---------- 逐站 votes ----------

export function voteMatrix(participants, votes, phases) {
  const matched = new Set();
  const rows = (participants ?? []).map((p) => {
    const own = (votes ?? []).filter((v) => p.instanceId && v.instanceId === p.instanceId);
    own.forEach((v) => matched.add(v));
    const times = Object.fromEntries(phases.map((ph) => [ph, own.find((v) => v.phase === ph)?.createdAt ?? null]));
    const siteMismatch = own.some((v) => v.siteId && v.siteId !== p.siteId);
    return { identity: p, phases: times, siteMismatch };
  });
  const unmatched = (votes ?? []).filter((v) => !matched.has(v));
  return { rows, unmatched };
}

export function lastConfirmedAt(votes) {
  let best = null;
  for (const v of votes ?? []) {
    const t = parseTime(v.createdAt);
    if (t !== null && (best === null || t > best.t)) best = { t, iso: v.createdAt };
  }
  return best ? best.iso : null;
}

function steps(states, state, finalState) {
  const idx = states.indexOf(state);
  return states.map((s, i) => ({
    state: s,
    status: idx === -1 ? 'todo' : i < idx || (i === idx && s === finalState) ? 'done' : i === idx ? 'current' : 'todo',
  }));
}

export function changeView(change) {
  const matrix = voteMatrix(change.participants, change.votes, CHANGE_VOTE_PHASES);
  return {
    ...change,
    open: change.state !== 'APPLIED',
    unknownState: !CHANGE_STATES.includes(change.state),
    steps: steps(CHANGE_STATES, change.state, 'APPLIED'),
    matrix,
    lastConfirmedAt: lastConfirmedAt(change.votes),
  };
}

// ---------- 多方 GC ----------

export function normalizeGcSnapshot(s) {
  if (!isPlainObject(s) || !isPlainObject(s.decision) || !isUuid(s.decision.operationId)) return null;
  const d = s.decision;
  return {
    decision: {
      operationId: d.operationId,
      fileId: str(d.fileId),
      revision: int(d.revision, 1),
      epoch: int(d.epoch),
      sha256: str(d.sha256),
      sizeBytes: int(d.sizeBytes, 1),
      participants: arr(d.participants).map(normalizeIdentity).filter(Boolean),
    },
    state: str(s.state),
    votes: arr(s.votes).map(normalizeVote).filter(Boolean),
  };
}

export function normalizeGcList(data) {
  return arr(data).map(normalizeGcSnapshot).filter(Boolean);
}

export function gcView(snapshot) {
  return {
    ...snapshot,
    open: snapshot.state !== 'DELETED',
    unknownState: !GC_STATES.includes(snapshot.state),
    steps: steps(GC_STATES, snapshot.state, 'DELETED'),
    matrix: voteMatrix(snapshot.decision.participants, snapshot.votes, GC_VOTE_PHASES),
    lastConfirmedAt: lastConfirmedAt(snapshot.votes),
  };
}

export function normalizeCandidates(data) {
  return arr(data)
    .filter((c) => isPlainObject(c) && isUuid(c.fileId) && int(c.revision, 1) !== null)
    .map((c) => ({ fileId: c.fileId, revision: c.revision, state: str(c.state), blockedReason: str(c.blockedReason), updatedAt: str(c.updatedAt) }));
}

export function candidateKey(c) {
  return `${c.fileId}#${c.revision}`;
}

export function candidateCheck(candidate, { statusFresh, epoch, openGc }) {
  const reasons = [];
  if (!candidate) return ['請選擇候選。'];
  if (candidate.state === 'DETECTED') reasons.push('尚未判定為可刪除（DETECTED）。');
  else if (candidate.state === 'BLOCKED') reasons.push(`已封鎖：${candidate.blockedReason ?? '未提供原因'}。`);
  else if (candidate.state !== 'ELIGIBLE') reasons.push(`候選狀態 ${String(candidate.state)} 不可發起。`);
  if (!statusFresh) reasons.push('叢集狀態不是最新，無法取得可信的 expectedEpoch。');
  if (epoch === null || epoch < 1) reasons.push('目前 epoch 小於 1，不能發起多方 GC。');
  if ((openGc ?? []).some((g) => g.decision.fileId === candidate.fileId && g.decision.revision === candidate.revision)) {
    reasons.push('此 revision 已有未完成的 GC 決策，請改用續行。');
  }
  return reasons;
}

// ---------- 故障控制 ----------

export function blockFreshness(observationsRes, now, thresholdMs = CLUSTER_BLOCK_FRESHNESS_MS) {
  const age = monoAge(observationsRes, now);
  const ok = Boolean(observationsRes) && !observationsRes.error && age !== null && age <= thresholdMs;
  return { ok, ageMs: age, error: observationsRes?.error ?? null };
}

export function faultSiteOptions(views, action) {
  const list = action === 'BLOCK' ? views.filter((v) => v.state === 'ACTIVE') : views;
  return list.map((v) => ({ value: v.siteId, label: `${v.siteId} · ${v.state ?? '未知'}${v.mode === 'CAMPUS' ? ' · 權威站' : ''}` }));
}

function catalogFor(view, catalog) {
  return (catalog ?? []).find((c) => c.siteId === view.siteId && view.instanceId && c.instanceId === view.instanceId) ?? null;
}

export function faultChannelOptions(view, catalog, action) {
  if (!view) return [];
  const live = view.trusted && view.observation ? view.observation.channels : [];
  if (action === 'BLOCK') {
    return live.map((c) => ({
      value: c.name,
      label: c.maximumBlockSeconds === null ? `${c.name}（上限缺值）` : `${c.name}（上限 ${c.maximumBlockSeconds} 秒）`,
    }));
  }
  const offline = catalogFor(view, catalog)?.channels ?? [];
  const names = [...new Set([...live.map((c) => c.name), ...offline.map((c) => c.name)])].sort();
  return names.map((name) => ({
    value: name,
    label: live.some((c) => c.name === name) ? name : `${name}（離線目錄，未驗證）`,
  }));
}

export function clusterBlockLimit(view, channel) {
  if (!view?.trusted || !view.observation) return null;
  return view.observation.channels.find((c) => c.name === channel)?.maximumBlockSeconds ?? null;
}

export function clusterFaultPrecheck({ view, catalog, action, channel, seconds, freshness }) {
  const blocking = [];
  const warnings = [];
  if (!view) return { blocking: ['請選擇站點。'], warnings };
  if (action === 'BLOCK') {
    if (view.state !== 'ACTIVE') blocking.push('BLOCK 僅限 ACTIVE 成員。');
    if (!freshness?.ok) {
      blocking.push(
        freshness?.ageMs === null || freshness?.ageMs === undefined
          ? '尚無成功的觀測，不能 BLOCK。'
          : `觀測資料已 ${Math.round(freshness.ageMs / 1000)} 秒（自請求開始），BLOCK 需 30 秒內的成功觀測。`,
      );
    }
    const obs = view.observation;
    if (!obs) blocking.push('沒有此站的觀測。');
    else if (!obs.reachable) blocking.push(`此站觀測為 ${obs.health}${obs.code ? `（${obs.code}）` : ''}。`);
    else if (!obs.control || obs.control.observationStatus !== 'OBSERVED') blocking.push('節點 control 快照不是 OBSERVED。');
    if (channel && obs?.reachable) {
      const live = obs.channels.find((c) => c.name === channel);
      if (!live) blocking.push('目前觀測中沒有這個通道。');
      else if (live.maximumBlockSeconds === null) blocking.push('節點未回報此通道的 maximumBlockSeconds（缺值），不能 BLOCK。');
      else if (Number.isInteger(seconds) && seconds > live.maximumBlockSeconds) {
        blocking.push(`秒數 ${seconds} 超過此通道的節點上限 ${live.maximumBlockSeconds} 秒。`);
      }
    }
    warnings.push('後端會重新核對精確節點與資料庫指紋；前端時間不是授權依據。');
  } else if (action === 'RESTORE') {
    const options = faultChannelOptions(view, catalog, 'RESTORE').map((o) => o.value);
    if (channel && !options.includes(channel)) blocking.push('此通道不在目前觀測或離線目錄中。');
    if (!catalogFor(view, catalog) && !view.observation) blocking.push('此站沒有離線目錄項目（profile 不匹配或未設定），也沒有觀測。');
    if (view.state === 'RETIRED') warnings.push('RETIRED 站的 RESTORE 只恢復固定代理通道，不恢復成員資格。');
    const live = view.trusted && view.observation ? view.observation.channels.map((c) => c.name) : [];
    if (channel && !live.includes(channel)) warnings.push('此通道來自離線目錄（routingVerified=false），不代表線上 wiring 或節點在線。');
    if (!view.trusted) warnings.push('此站目前沒有可信觀測；RESTORE 仍可送出，但無法確認通道目前狀態。');
  } else {
    blocking.push('請選擇 BLOCK 或 RESTORE。');
  }
  if (!channel) blocking.push('沒有可選擇的通道。');
  return { blocking, warnings };
}

// ---------- 確認後、送出前的重新檢查 ----------
// 確認對話框可能開很久；確認不是永久有效的授權。按下確認後以「當下」狀態重新檢查，
// 條件改變就不送出，要求重新確認；絕不靜默把已確認的 epoch 換成新值。
// 結果未知的冪等重送（replay）沿用原 UUID 與原 body，不做 epoch 比對（原請求可能已讓 epoch 前進）。

export function recheckEpochWrite({ captured, ctx, replay }) {
  if (replay) return [];
  const out = [];
  if (!ctx.statusFresh) out.push('確認期間叢集狀態已過期或讀取失敗，未送出；請待狀態更新後重新確認。');
  if (captured.epoch !== ctx.epoch) {
    out.push(`確認期間 epoch 已由 ${captured.epoch ?? '未知'} 變為 ${ctx.epoch ?? '未知'}，未送出；請檢視最新狀態後重新確認。`);
  }
  return out;
}

export function recheckMember({ captured, member }) {
  if (!member || member.instanceId !== captured.instanceId) return ['確認期間目標站身份已改變或已不在名單中，未送出。'];
  if (member.state !== captured.state) return [`確認期間目標狀態已由 ${captured.state} 變為 ${member.state ?? '未知'}，未送出。`];
  return [];
}

export function recheckFault({ captured, view, catalog, freshness }) {
  if (!view || view.instanceId !== captured.instanceId) return ['確認期間目標站身份已改變或已不在名單中，未送出。'];
  const out = [];
  if (captured.action === 'BLOCK') {
    if (view.state !== captured.state) out.push(`確認期間目標狀態已由 ${captured.state} 變為 ${view.state ?? '未知'}，未送出。`);
    const limit = clusterBlockLimit(view, captured.channel);
    if (limit !== captured.limit) out.push(`確認期間通道上限已由 ${captured.limit ?? '缺值'} 變為 ${limit ?? '缺值'}，未送出。`);
  }
  const pre = clusterFaultPrecheck({ view, catalog, action: captured.action, channel: captured.channel, seconds: captured.seconds, freshness });
  out.push(...pre.blocking);
  return [...new Set(out)];
}

export function recheckPreviewSites({ captured, views }) {
  const active = new Set(views.filter((v) => v.state === 'ACTIVE').map((v) => `${v.siteId}|${v.instanceId}`));
  const missing = captured.sites.filter((s) => !active.has(`${s.siteId}|${s.instanceId}`));
  return missing.length ? [`確認期間站點 ${missing.map((s) => s.siteId).join('、')} 已不是相同身份的 ACTIVE 成員，未送出。`] : [];
}

// ---------- 部署預覽 ----------

export function normalizePreview(p) {
  if (!isPlainObject(p) || !isUuid(p.id)) return null;
  return {
    id: p.id,
    requestHash: str(p.requestHash),
    createdAt: str(p.createdAt),
    epoch: int(p.epoch),
    releaseId: str(p.releaseId),
    artifactSha256: str(p.artifactSha256),
    members: arr(p.members).map(normalizeIdentity).filter(Boolean),
    state: p.state,
    executionEnabled: p.executionEnabled,
    blockers: toDisplayItems(p.blockers),
    steps: toDisplayItems(p.steps),
  };
}

export function previewView(preview, status, releases) {
  if (!preview) return null;
  const release = (releases ?? []).find((r) => r.id === preview.releaseId);
  const sha =
    !release || typeof release.sha256 !== 'string' || !preview.artifactSha256
      ? 'UNKNOWN'
      : release.sha256.toLowerCase() === preview.artifactSha256.toLowerCase()
        ? 'MATCH'
        : 'MISMATCH';
  const currentEpoch = status?.epoch ?? null;
  const epochStale = currentEpoch !== null && preview.epoch !== null && currentEpoch !== preview.epoch;
  const memberChanges = preview.members.filter(
    (m) => !(status?.members ?? []).some((s) => s.siteId === m.siteId && s.instanceId === m.instanceId && s.state === 'ACTIVE'),
  );
  return { ...preview, sha, currentEpoch, epochStale, memberChanges };
}

export function previewSiteOptions(views) {
  return views.filter((v) => v.state === 'ACTIVE').map((v) => ({ value: v.siteId, label: `${v.siteId}${v.mode === 'CAMPUS' ? '（權威站）' : ''}` }));
}

export function releaseOptions(releases) {
  return (releases ?? []).map((r) => ({
    value: r.id,
    label: isClusterId(r.id) ? r.id : `${String(r.id)}（格式不符，不可選）`,
    valid: isClusterId(r.id),
  }));
}

export function clusterLockReasons(caps, preview) {
  const reasons = executionReasons(caps, preview);
  return { locked: reasons.length > 0, reasons };
}

// ---------- 稽核 ----------

export function normalizeClusterAudit(data) {
  return arr(data)
    .filter(isPlainObject)
    .map((e) => ({
      id: int(e.id),
      requestId: str(e.request_id),
      actor: str(e.actor),
      action: str(e.action),
      siteId: str(e.site_id),
      epoch: int(e.epoch),
      createdAt: str(e.created_at),
    }))
    .sort((a, b) => (b.id ?? -1) - (a.id ?? -1));
}

// ---------- 冪等寫入 ----------

export function randomUuid(cryptoObj = globalThis.crypto) {
  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') return cryptoObj.randomUUID();
  if (!cryptoObj || typeof cryptoObj.getRandomValues !== 'function') throw new Error('瀏覽器不支援安全亂數，無法產生 requestId');
  const b = cryptoObj.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

// 同一操作（key 含參數）結果不明時保留原 requestId 與原請求內容，重送時完全相同。
// 參數改變即為不同 key，產生新的 requestId。成功或明確被拒後釋放。
export function createIntentStore(uuid = randomUuid) {
  const intents = new Map();
  return Object.freeze({
    peek: (key) => intents.get(key) ?? null,
    acquire(key, buildBody) {
      const existing = intents.get(key);
      if (existing) return { ...existing, replay: true };
      const id = uuid();
      const entry = Object.freeze({ id, body: Object.freeze(buildBody(id)) });
      intents.set(key, entry);
      return { ...entry, replay: false };
    },
    release: (key) => {
      intents.delete(key);
    },
    clear: () => intents.clear(),
    size: () => intents.size,
  });
}

// UNKNOWN：可能已執行，保留 requestId；STALE_EPOCH：重新讀取並重新確認；REJECTED：明確拒絕。
export function writeOutcome(err) {
  if (!err) return 'REJECTED';
  if (err.kind === 'validation') return 'REJECTED';
  // 失租：此程序未執行寫入；保留 requestId（冪等），待領導狀態更新後再確認。
  if (err.code === 'CONTROL_LEASE_NOT_HELD') return 'LEASE_NOT_HELD';
  if (err.outcomeUnknown || err.status === 503) return 'UNKNOWN';
  if (err.code === 'STALE_MEMBERSHIP_EPOCH') return 'STALE_EPOCH';
  return 'REJECTED';
}

const ADVICE = {
  INITIAL_SYNC_PENDING: '新站仍在追平歷史資料；稍後以「續行」或重新送出。',
  LOCAL_ACCOUNT_ARCHIVE_PENDING: '目標站仍有未歸檔帳戶；請先完成帳戶歸檔後重試。',
  RETIREMENT_REFERENCES_PENDING: '目標站仍有活引用；請先排空引用後重試。',
  RETIREMENT_OBJECTS_PENDING: '目標站仍有未刪除的本地物件；請先排空物件後重試。',
  RETIREMENT_UPLOADS_PENDING: '目標站仍有未完成上傳；請等待或處理上傳後重試。',
  GC_DECISIONS_PENDING: '仍有未完成的 GC 決策；請先完成既有 GC。',
  STALE_MEMBERSHIP_EPOCH: '成員 epoch 已變更；已重新讀取，請確認最新狀態後重新送出（會使用新的 requestId）。',
  SITE_ID_RETAINED: '站點 ID 已保留為墓碑，不可重用。',
  MEMBER_IDENTITY_OR_SLOT_RETAINED: '成員 UUID 或 slot 已保留，不可重用；新增 slot 必須從空安裝開始。',
  DEPLOYMENT_LOCKED: '部署執行固定鎖定。',
  CLUSTER_CYCLE_RETRY: 'worker 本輪需要重試，會自動接續。',
  CONTROL_LEASE_NOT_HELD: '此程序已不是控制 leader（備機或租約失效）；寫入未在此程序執行，登入態保留。請待領導狀態更新、穩定入口轉向新 leader 後再確認。',
};

export function clusterErrorAdvice(code) {
  const text = code ? ADVICE[code] : null;
  if (!text) return null;
  return code.endsWith('_PENDING') ? `${text}此條件不可由中控豁免。` : text;
}

export function writeFailureMessage(err, outcome, { requestId } = {}) {
  const advice = clusterErrorAdvice(err?.code);
  if (outcome === 'UNKNOWN') {
    return {
      tone: 'amber',
      title: '結果未知：請求可能已在後端執行',
      lines: [
        describeError(err),
        requestId ? `已保留 requestId ${requestId}；重新送出同一操作時會沿用相同 requestId 與原請求內容。` : '請重新讀取狀態，勿假定未執行。',
      ],
    };
  }
  if (outcome === 'STALE_EPOCH') {
    return { tone: 'amber', title: 'epoch 已變更，未執行', lines: [advice ?? describeError(err)] };
  }
  if (outcome === 'LEASE_NOT_HELD') {
    return {
      tone: 'amber',
      title: '此程序未持有控制租約（CONTROL_LEASE_NOT_HELD），寫入已停用',
      lines: [
        advice,
        requestId ? `已保留 requestId ${requestId}；待領導狀態恢復後再次確認時沿用相同 requestId 與原請求內容。` : '待領導狀態恢復後再重新確認。',
      ].filter(Boolean),
    };
  }
  return { tone: 'red', title: '未被接受', lines: [describeError(err), advice].filter(Boolean) };
}

const WORKER_LABELS = {
  OK: '上一輪沒有拋錯（不代表各站健康）',
  NOT_STARTED: '尚未啟動',
  AWAITING_FIRST_JOIN: '等待第一次加入',
  CLUSTER_CYCLE_RETRY: '本輪需重試',
  SINGLE_SITE_ACTIVE: '單站集群運行中',
};

// 明確列舉的正常 worker 碼（大小寫完全相符）。其他任何字串（含未知碼、僅含 ACTIVE 字樣的碼）一律告警。
export const WORKER_NORMAL_CODES = Object.freeze(['OK', 'NOT_STARTED', 'AWAITING_FIRST_JOIN', 'SINGLE_SITE_ACTIVE']);
export const workerCodeNormal = (code) => typeof code === 'string' && WORKER_NORMAL_CODES.includes(code);

export function workerSummary(worker) {
  if (!worker) return { text: '未提供', tone: 'amber', code: null };
  const code = worker.lastCode;
  const label = code ? WORKER_LABELS[code] ?? '協議錯誤碼（原樣顯示）' : '未提供 lastCode';
  const tone = code === 'SINGLE_SITE_ACTIVE' ? 'teal' : workerCodeNormal(code) ? 'neutral' : 'amber';
  return { text: `${worker.running ? 'worker 已啟動' : 'worker 未啟動'} · ${code ?? '—'}`, label, tone, code };
}
