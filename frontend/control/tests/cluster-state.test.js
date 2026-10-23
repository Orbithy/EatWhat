import test from 'node:test';
import assert from 'node:assert/strict';

import * as cm from '../cluster-state.js';
import { UUID_A, UUID_B } from './helpers.js';

const I1 = '11111111-1111-4111-8111-111111111111';
const I2 = '22222222-2222-4222-8222-222222222222';
const I3 = '33333333-3333-4333-8333-333333333333';
const I4 = '44444444-4444-4444-8444-444444444444';

function reg(site, instance, slot, state, mode = 'EXTERNAL', extra = {}) {
  return {
    site_id: site,
    instance_id: instance,
    profile_id: `p-${site}`,
    mode,
    allocation_slot: slot,
    state,
    database_fingerprint: `fp-${site}`,
    joined_epoch: state === 'REGISTERED' ? null : 1,
    retired_epoch: state === 'RETIRED' ? 4 : null,
    ...extra,
  };
}

const STATUS = {
  clusterId: UUID_A,
  epoch: 5,
  members: [
    reg('site-3', I3, 2, 'REGISTERED'),
    reg('campus', I1, 0, 'ACTIVE', 'CAMPUS'),
    reg('external', I2, 1, 'ACTIVE'),
    reg('old', I4, 3, 'RETIRED'),
  ],
  pendingChanges: [],
  worker: { running: true, lastCode: 'OK' },
};

function obs(site, instance, slot, extra = {}) {
  return {
    member: { siteId: site, instanceId: instance, mode: site === 'campus' ? 'CAMPUS' : 'EXTERNAL', allocationSlot: slot },
    health: 'REACHABLE',
    node: {
      siteId: site,
      instanceId: instance,
      clusterId: UUID_A,
      allocationSlot: slot,
      mode: 'EXTERNAL',
      databaseFingerprint: `fp-${site}`,
      protocolVersion: 1,
      dataPlaneReady: true,
      membership: { epoch: 5, state: 'ACTIVE', pending_change: null },
      drain: { liveReferences: 0, localObjects: 0, pendingUploads: 0, unarchivedAccounts: 0 },
      control: {
        siteId: site,
        instanceId: instance,
        mode: 'EXTERNAL',
        observedAt: '2026-10-16T00:00:00Z',
        deploymentExecutionEnabled: false,
        observationStatus: 'OBSERVED',
        channels: { events: { state: 'FORWARDING', expiresAt: '', acceptedConnections: 0, forwardedBytes: 0, routingVerified: false, maximumBlockSeconds: 60 } },
        readiness: 'NOT_CHECKED',
        storageReachability: 'NOT_CHECKED',
      },
    },
    sync: { pendingDeliveries: 3, pendingInbox: 0, fileTransfers: 1 },
    ...extra,
  };
}

function viewsWith(observations, freshness = 'FRESH', status = STATUS) {
  return cm.buildMemberViews({ status: cm.normalizeStatus(status), observations, observationFreshness: freshness });
}

// ---------- 單調時間 ----------

test('mono 新鮮度：年齡自請求開始起算，進行中的輪詢不延長舊資料', () => {
  let r = cm.emptyMono();
  assert.equal(cm.monoFreshness(r, 1000, 30000), 'NEVER');
  r = cm.monoSucceed(r, [], 1000, 25000, 0);
  assert.equal(cm.monoAge(r, 26000), 25000, '以開始時間計算年齡');
  assert.equal(cm.monoFreshness(r, 30000, 30000), 'FRESH');
  r = cm.monoStart(r, 30000);
  assert.equal(cm.inFlightFor(r, 40000), 10000);
  assert.equal(cm.monoFreshness(r, 31001, 30000), 'STALE', '進行中不延長');
  r = cm.monoFail(r, { kind: 'timeout' });
  assert.equal(cm.monoFreshness(r, 31001, 30000), 'ERROR');
  assert.equal(r.inFlightSince, null);
  assert.deepEqual(r.data, [], '保留舊資料但標為失效');
});

test('blockFreshness：成功請求開始 30 秒內才可 BLOCK', () => {
  const r = cm.monoSucceed(cm.emptyMono(), [], 0, 20000, 0);
  assert.equal(cm.blockFreshness(r, 30000).ok, true);
  assert.equal(cm.blockFreshness(r, 30001).ok, false);
  assert.equal(cm.blockFreshness(cm.monoFail(r, { kind: 'timeout' }), 1000).ok, false);
  assert.equal(cm.blockFreshness(cm.emptyMono(), 0).ageMs, null);
});

// ---------- 正規化與成員檢視 ----------

test('normalizeStatus：snake_case 登記轉內部欄位並依 slot 排序', () => {
  const s = cm.normalizeStatus(STATUS);
  assert.equal(s.epoch, 5);
  assert.deepEqual(s.members.map((m) => m.siteId), ['campus', 'external', 'site-3', 'old']);
  assert.equal(s.members[0].slot, 0);
  assert.equal(s.members[0].fingerprint, 'fp-campus');
  assert.equal(s.members[3].retiredEpoch, 4);
  assert.equal(s.worker.lastCode, 'OK');
  assert.equal(cm.normalizeStatus(null), null);
});

test('buildMemberViews：完整名單來自 status；observations 只對 ACTIVE／DRAINING', () => {
  const v = viewsWith([obs('campus', I1, 0), obs('external', I2, 1)]);
  const by = Object.fromEntries(v.map((x) => [x.siteId, x]));
  assert.equal(by.campus.observed, 'REACHABLE');
  assert.equal(by['site-3'].observed, 'NOT_OBSERVED');
  assert.equal(by['site-3'].observable, false);
  assert.equal(by.old.observed, 'NOT_OBSERVED');
});

test('buildMemberViews：缺觀測不補成在線；UNKNOWN 不提供零積壓', () => {
  const unknown = { member: { siteId: 'external', instanceId: I2, mode: 'EXTERNAL', allocationSlot: 1 }, health: 'UNKNOWN', code: 'NODE_TIMEOUT', sync: { pendingDeliveries: 0, pendingInbox: 0, fileTransfers: 0 } };
  const v = viewsWith([obs('campus', I1, 0), unknown]);
  const ext = v.find((x) => x.siteId === 'external');
  assert.equal(ext.observed, 'UNKNOWN');
  assert.equal(ext.observation.sync, null, 'UNKNOWN 時不採用 sync');
  assert.equal(ext.observation.drain, null);
  const missing = viewsWith([obs('campus', I1, 0)]);
  assert.equal(missing.find((x) => x.siteId === 'external').observed, 'NOT_OBSERVED');
});

test('buildMemberViews：instanceId 必須精確相符', () => {
  const v = viewsWith([obs('external', I3, 1)]);
  assert.equal(v.find((x) => x.siteId === 'external').observed, 'NOT_OBSERVED');
});

test('buildMemberViews：觀測失效／尚未讀取', () => {
  assert.equal(viewsWith([obs('campus', I1, 0)], 'ERROR').find((x) => x.siteId === 'campus').observed, 'STALE');
  assert.equal(viewsWith(null, 'NEVER').find((x) => x.siteId === 'campus').observed, 'LOADING');
});

test('memberAlerts：UNKNOWN、epoch 不符、指紋不符、未檢查、阻斷、身份核對', () => {
  const unknown = { member: { siteId: 'external', instanceId: I2, mode: 'EXTERNAL', allocationSlot: 1 }, health: 'UNKNOWN', code: 'X' };
  const a1 = cm.memberAlerts(viewsWith([unknown]).find((x) => x.siteId === 'external'), 5);
  assert.ok(a1.some((a) => a.text.includes('UNKNOWN') && a.text.includes('未知')));

  const o = obs('external', I2, 1);
  o.node.membership.epoch = 4;
  o.node.databaseFingerprint = 'other';
  o.node.control.channels.events.state = 'BLOCKED';
  o.node.control.database = { v35: false, restrictedRole: true, coordinationGuard: true };
  const a2 = cm.memberAlerts(viewsWith([o]).find((x) => x.siteId === 'external'), 5);
  assert.ok(a2.some((a) => a.text.includes('epoch 4')));
  assert.ok(a2.some((a) => a.text.includes('指紋') && a.tone === 'red'));
  assert.ok(a2.some((a) => a.text.includes('readiness') && a.text.includes('未檢查')));
  assert.ok(a2.some((a) => a.text.includes('已阻斷')));
  assert.ok(a2.some((a) => a.text.includes('v35') && a.tone === 'red'));
  assert.ok(!a2.some((a) => /健康|正常/.test(a.text)), '不產生健康判定');
});

test('drainPending：未知與非零都列出', () => {
  assert.deepEqual(cm.drainPending({ liveReferences: 0, localObjects: 2, pendingUploads: null, unarchivedAccounts: 0 }), ['本地物件 2', '未完成上傳（未知）']);
  assert.equal(cm.drainPending(null).length, 4);
});

// ---------- 成員操作 ----------

function ctxFor(status, { fresh = true, caps } = {}) {
  return cm.actionContext({ status: cm.normalizeStatus(status), statusFreshness: fresh ? 'FRESH' : 'STALE', capabilities: caps ?? { maximumActiveSites: 16, coordinatorTransferEnabled: false } });
}

test('memberActionCheck：依狀態只允許合法動作', () => {
  const s = cm.normalizeStatus(STATUS);
  const by = Object.fromEntries(s.members.map((m) => [m.siteId, m]));
  const ctx = ctxFor(STATUS);
  assert.deepEqual(cm.memberActionCheck('JOIN', by['site-3'], ctx).blocking, []);
  assert.deepEqual(cm.memberActionCheck('CANCEL', by['site-3'], ctx).blocking, []);
  assert.ok(cm.memberActionCheck('JOIN', by.external, ctx).blocking.length);
  assert.ok(cm.memberActionCheck('CANCEL', by.external, ctx).blocking.length, '已開始加入不提供取消');
  assert.deepEqual(cm.memberActionCheck('RETIRE', by.external, ctx).blocking, []);
  assert.ok(cm.memberActionCheck('RETIRE', by.campus, ctx).blocking.some((b) => b.includes('權威站')));
  assert.ok(cm.memberActionCheck('RETIRE', by.old, ctx).blocking.length);
  assert.ok(cm.memberActionCheck('RETIRE', by.external, ctx).warnings.some((w) => w.includes('不提供強制移除')));
});

test('memberActionCheck：狀態過期、未 APPLIED 變更、參與上限都阻擋', () => {
  const s = cm.normalizeStatus(STATUS);
  const site3 = s.members.find((m) => m.siteId === 'site-3');
  assert.ok(cm.memberActionCheck('JOIN', site3, ctxFor(STATUS, { fresh: false })).blocking.some((b) => b.includes('expectedEpoch')));
  const withChange = { ...STATUS, pendingChanges: [{ change_id: UUID_A, base_epoch: 5, next_epoch: 6, kind: 'JOIN', site_id: 'x', state: 'INSTALLED', participants: [], votes: [] }] };
  assert.ok(cm.memberActionCheck('JOIN', site3, ctxFor(withChange)).blocking.some((b) => b.includes('APPLIED')));
  const full = cm.memberActionCheck('JOIN', site3, ctxFor(STATUS, { caps: { maximumActiveSites: 2 } }));
  assert.ok(full.blocking.some((b) => b.includes('上限')));
});

test('profileRegistration：已保留的 siteId／UUID／slot 不可重用', () => {
  const members = cm.normalizeStatus(STATUS).members;
  assert.equal(cm.profileRegistration({ id: 'p', siteId: 'new', instanceId: UUID_B, slot: 9 }, members).allowed, true);
  assert.equal(cm.profileRegistration({ id: 'p', siteId: 'old', instanceId: UUID_B, slot: 9 }, members).allowed, false, '退役 siteId 不可重用');
  assert.equal(cm.profileRegistration({ id: 'p', siteId: 'new', instanceId: I4, slot: 9 }, members).allowed, false);
  assert.equal(cm.profileRegistration({ id: 'p', siteId: 'new', instanceId: UUID_B, slot: 3 }, members).allowed, false);
});

// ---------- 變更與 votes ----------

test('changeView：逐站 votes 依 instance_id 對應，保留階段時間', () => {
  const change = cm.normalizeChange({
    change_id: UUID_A,
    base_epoch: 5,
    next_epoch: 6,
    kind: 'JOIN',
    site_id: 'site-3',
    state: 'COMMITTED',
    participants: [
      { siteId: 'campus', instanceId: I1, mode: 'CAMPUS', allocationSlot: 0 },
      { siteId: 'site-3', instanceId: I3, mode: 'EXTERNAL', allocationSlot: 2 },
    ],
    votes: [
      { site_id: 'campus', instance_id: I1, phase: 'PREPARED', created_at: '2026-10-16T00:00:01Z' },
      { site_id: 'site-3', instance_id: I3, phase: 'PREPARED', created_at: '2026-10-16T00:00:03Z' },
      { site_id: 'campus', instance_id: I1, phase: 'APPLIED', created_at: '2026-10-16T00:00:05Z' },
      { site_id: 'ghost', instance_id: I4, phase: 'PREPARED', created_at: '2026-10-16T00:00:09Z' },
    ],
  });
  const v = cm.changeView(change);
  assert.equal(v.open, true);
  assert.deepEqual(v.steps.map((s) => s.status), ['done', 'current', 'todo', 'todo']);
  assert.equal(v.matrix.rows[0].phases.PREPARED, '2026-10-16T00:00:01Z');
  assert.equal(v.matrix.rows[0].phases.APPLIED, '2026-10-16T00:00:05Z');
  assert.equal(v.matrix.rows[1].phases.APPLIED, null);
  assert.equal(v.matrix.unmatched.length, 1);
  assert.equal(v.lastConfirmedAt, '2026-10-16T00:00:09Z', '最後確認時間＝最大 vote 時間');
  const applied = cm.changeView({ ...change, state: 'APPLIED' });
  assert.equal(applied.open, false);
  assert.deepEqual(applied.steps.map((s) => s.status), ['done', 'done', 'done', 'done']);
  assert.equal(cm.changeView({ ...change, votes: [] }).lastConfirmedAt, null);
  assert.equal(cm.changeView({ ...change, state: 'FAILED' }).unknownState, true);
});

test('normalizeChanges：保留後端列表順序', () => {
  const list = cm.normalizeChanges([
    { change_id: UUID_B, state: 'APPLIED', participants: [], votes: [] },
    { change_id: UUID_A, state: 'PREPARING', participants: [], votes: [] },
    { change_id: 'bad' },
  ]);
  assert.deepEqual(list.map((c) => c.id), [UUID_B, UUID_A]);
});

// ---------- GC ----------

test('gcView：PREPARED／DELETED 逐站對應，不推導實體刪除', () => {
  const snap = cm.normalizeGcSnapshot({
    decision: { operationId: UUID_A, fileId: UUID_B, revision: 2, epoch: 5, sha256: 'a'.repeat(64), sizeBytes: 10, participants: [{ siteId: 'campus', instanceId: I1, mode: 'CAMPUS', allocationSlot: 0 }] },
    state: 'COMMITTED',
    votes: [{ site_id: 'campus', instance_id: I1, phase: 'PREPARED', created_at: '2026-10-16T00:00:01Z' }],
  });
  const g = cm.gcView(snap);
  assert.equal(g.open, true);
  assert.equal(g.matrix.rows[0].phases.PREPARED, '2026-10-16T00:00:01Z');
  assert.equal(g.matrix.rows[0].phases.DELETED, null);
  assert.ok(!Object.keys(g).some((k) => /physical|purged|removed/i.test(k)));
  assert.equal(cm.gcView({ ...snap, state: 'DELETED' }).open, false);
  assert.equal(cm.normalizeGcSnapshot({ decision: { operationId: 'x' } }), null);
});

test('candidateCheck：只有 ELIGIBLE 可發起，其餘顯示原因', () => {
  const ctx = { statusFresh: true, epoch: 5, openGc: [] };
  const base = { fileId: UUID_B, revision: 2, updatedAt: null, blockedReason: null };
  assert.deepEqual(cm.candidateCheck({ ...base, state: 'ELIGIBLE' }, ctx), []);
  assert.ok(cm.candidateCheck({ ...base, state: 'DETECTED' }, ctx)[0].includes('DETECTED'));
  assert.ok(cm.candidateCheck({ ...base, state: 'BLOCKED', blockedReason: 'RETENTION' }, ctx)[0].includes('RETENTION'));
  assert.ok(cm.candidateCheck({ ...base, state: 'ELIGIBLE' }, { ...ctx, statusFresh: false }).length);
  assert.ok(cm.candidateCheck({ ...base, state: 'ELIGIBLE' }, { ...ctx, epoch: 0 }).length);
  const openGc = [{ decision: { fileId: UUID_B, revision: 2 } }];
  assert.ok(cm.candidateCheck({ ...base, state: 'ELIGIBLE' }, { ...ctx, openGc }).some((r) => r.includes('續行')));
});

// ---------- 故障 ----------

const CATALOG = cm.normalizeOfflineChannels([
  { siteId: 'old', instanceId: I4, channels: { events: 60 }, routingVerified: false },
  { siteId: 'external', instanceId: I2, channels: { events: 60, files: 30 }, routingVerified: false },
  { siteId: 'site-3', instanceId: I1, channels: { events: 60 }, routingVerified: false },
]);

test('faultSiteOptions：BLOCK 僅 ACTIVE；RESTORE 含 RETIRED 等所有保留身份', () => {
  const v = viewsWith([obs('campus', I1, 0), obs('external', I2, 1)]);
  assert.deepEqual(cm.faultSiteOptions(v, 'BLOCK').map((o) => o.value), ['campus', 'external']);
  assert.deepEqual(cm.faultSiteOptions(v, 'RESTORE').map((o) => o.value), ['campus', 'external', 'site-3', 'old']);
});

test('faultChannelOptions：RESTORE 以 siteId＋instanceId 匹配離線目錄；不匹配不猜測', () => {
  const v = viewsWith([obs('campus', I1, 0), obs('external', I2, 1)]);
  const by = Object.fromEntries(v.map((x) => [x.siteId, x]));
  assert.deepEqual(cm.faultChannelOptions(by.old, CATALOG, 'RESTORE'), [{ value: 'events', label: 'events（離線目錄，未驗證）' }]);
  assert.deepEqual(cm.faultChannelOptions(by['site-3'], CATALOG, 'RESTORE'), [], 'instanceId 不符不採用');
  assert.deepEqual(cm.faultChannelOptions(by.external, CATALOG, 'RESTORE').map((o) => o.label), ['events', 'files（離線目錄，未驗證）']);
  assert.deepEqual(cm.faultChannelOptions(by.external, CATALOG, 'BLOCK'), [{ value: 'events', label: 'events（上限 60 秒）' }], 'BLOCK 不用離線目錄');
});

test('clusterFaultPrecheck：BLOCK 需 ACTIVE、30 秒內觀測、REACHABLE、上限', () => {
  const fresh = { ok: true, ageMs: 1000 };
  const v = viewsWith([obs('campus', I1, 0), obs('external', I2, 1)]);
  const ext = v.find((x) => x.siteId === 'external');
  assert.deepEqual(cm.clusterFaultPrecheck({ view: ext, catalog: CATALOG, action: 'BLOCK', channel: 'events', seconds: 60, freshness: fresh }).blocking, []);
  assert.ok(cm.clusterFaultPrecheck({ view: ext, catalog: CATALOG, action: 'BLOCK', channel: 'events', seconds: 61, freshness: fresh }).blocking.some((b) => b.includes('60')));
  assert.ok(cm.clusterFaultPrecheck({ view: ext, catalog: CATALOG, action: 'BLOCK', channel: 'events', seconds: 10, freshness: { ok: false, ageMs: 45000 } }).blocking.some((b) => b.includes('45 秒')));
  const old = v.find((x) => x.siteId === 'old');
  assert.ok(cm.clusterFaultPrecheck({ view: old, catalog: CATALOG, action: 'BLOCK', channel: 'events', seconds: 10, freshness: fresh }).blocking.some((b) => b.includes('ACTIVE')));
  const noLimit = obs('external', I2, 1);
  delete noLimit.node.control.channels.events.maximumBlockSeconds;
  const v2 = viewsWith([noLimit]).find((x) => x.siteId === 'external');
  assert.ok(cm.clusterFaultPrecheck({ view: v2, catalog: CATALOG, action: 'BLOCK', channel: 'events', seconds: 10, freshness: fresh }).blocking.some((b) => b.includes('缺值')));
});

test('clusterFaultPrecheck：RESTORE 對 RETIRED 可送出但註明不恢復成員資格', () => {
  const v = viewsWith([obs('campus', I1, 0)], 'ERROR');
  const old = v.find((x) => x.siteId === 'old');
  const r = cm.clusterFaultPrecheck({ view: old, catalog: CATALOG, action: 'RESTORE', channel: 'events', seconds: 1, freshness: { ok: false, ageMs: null } });
  assert.deepEqual(r.blocking, []);
  assert.ok(r.warnings.some((w) => w.includes('不恢復成員資格')));
  assert.ok(r.warnings.some((w) => w.includes('routingVerified=false')));
  const site3 = v.find((x) => x.siteId === 'site-3');
  assert.ok(cm.clusterFaultPrecheck({ view: site3, catalog: CATALOG, action: 'RESTORE', channel: '', seconds: 1, freshness: {} }).blocking.length);
});

// ---------- 確認後、送出前重新檢查 ----------

test('recheckEpochWrite：確認期間 epoch 變更或狀態過期即不送出，不改寫為新 epoch', () => {
  const ctxNow = ctxFor({ ...STATUS, epoch: 6 });
  const r = cm.recheckEpochWrite({ captured: { epoch: 5 }, ctx: ctxNow, replay: false });
  assert.ok(r.some((x) => x.includes('由 5 變為 6')));
  assert.deepEqual(cm.recheckEpochWrite({ captured: { epoch: 5 }, ctx: ctxFor(STATUS), replay: false }), []);
  assert.ok(cm.recheckEpochWrite({ captured: { epoch: 5 }, ctx: ctxFor(STATUS, { fresh: false }), replay: false }).some((x) => x.includes('過期')));
  assert.deepEqual(cm.recheckEpochWrite({ captured: { epoch: 5 }, ctx: ctxNow, replay: true }), [], '結果未知的重送沿用原 body，不做 epoch 比對');
});

test('recheckMember：目標身份或狀態改變即不送出', () => {
  const members = cm.normalizeStatus(STATUS).members;
  const site3 = members.find((m) => m.siteId === 'site-3');
  const captured = { siteId: 'site-3', instanceId: I3, state: 'REGISTERED' };
  assert.deepEqual(cm.recheckMember({ captured, member: site3 }), []);
  assert.ok(cm.recheckMember({ captured, member: { ...site3, state: 'JOINING' } })[0].includes('JOINING'));
  assert.ok(cm.recheckMember({ captured, member: { ...site3, instanceId: I4 } })[0].includes('身份'));
  assert.ok(cm.recheckMember({ captured, member: null })[0].includes('身份'));
});

test('recheckFault：對話框開超過 30 秒後 BLOCK 不送出', () => {
  const v = viewsWith([obs('campus', I1, 0), obs('external', I2, 1)]).find((x) => x.siteId === 'external');
  const captured = { siteId: 'external', instanceId: I2, state: 'ACTIVE', action: 'BLOCK', channel: 'events', seconds: 30, limit: 60 };
  const res = cm.monoSucceed(cm.emptyMono(), [], 0, 5000, 0);
  assert.deepEqual(cm.recheckFault({ captured, view: v, catalog: CATALOG, freshness: cm.blockFreshness(res, 29000) }), [], '確認時仍在 30 秒內');
  const late = cm.recheckFault({ captured, view: v, catalog: CATALOG, freshness: cm.blockFreshness(res, 45000) });
  assert.ok(late.some((x) => x.includes('45 秒')), '開啟 modal 超過 30 秒');
  assert.ok(cm.recheckFault({ captured: { ...captured, limit: 90 }, view: v, catalog: CATALOG, freshness: cm.blockFreshness(res, 1000) }).some((x) => x.includes('上限')));
  assert.ok(cm.recheckFault({ captured: { ...captured, instanceId: I3 }, view: v, catalog: CATALOG, freshness: cm.blockFreshness(res, 1000) })[0].includes('身份'));
  const draining = { ...v, state: 'DRAINING' };
  assert.ok(cm.recheckFault({ captured, view: draining, catalog: CATALOG, freshness: cm.blockFreshness(res, 1000) }).some((x) => x.includes('DRAINING')));
});

test('recheckFault：RESTORE 不依賴線上觀測新鮮度', () => {
  const old = viewsWith(null, 'ERROR').find((x) => x.siteId === 'old');
  const captured = { siteId: 'old', instanceId: I4, state: 'RETIRED', action: 'RESTORE', channel: 'events', seconds: 1, limit: null };
  assert.deepEqual(cm.recheckFault({ captured, view: old, catalog: CATALOG, freshness: { ok: false, ageMs: null } }), []);
  assert.ok(cm.recheckFault({ captured: { ...captured, channel: 'files' }, view: old, catalog: CATALOG, freshness: {} }).length, '通道已不在目錄則不送出');
});

test('recheckPreviewSites：站點已非相同身份的 ACTIVE 即不送出', () => {
  const views = viewsWith([obs('campus', I1, 0), obs('external', I2, 1)]);
  assert.deepEqual(cm.recheckPreviewSites({ captured: { sites: [{ siteId: 'campus', instanceId: I1 }] }, views }), []);
  assert.ok(cm.recheckPreviewSites({ captured: { sites: [{ siteId: 'external', instanceId: I3 }] }, views })[0].includes('external'));
  assert.ok(cm.recheckPreviewSites({ captured: { sites: [{ siteId: 'old', instanceId: I4 }] }, views }).length);
});

// ---------- 預覽 ----------

test('previewView：epoch 變更即過期，SHA 對照，成員已非 ACTIVE', () => {
  const p = cm.normalizePreview({
    id: UUID_A,
    requestHash: 'h',
    createdAt: '2026-10-16T00:00:00Z',
    epoch: 4,
    releaseId: 'candidate-1',
    artifactSha256: 'a'.repeat(64),
    members: [{ siteId: 'campus', instanceId: I1, mode: 'CAMPUS', allocationSlot: 0 }, { siteId: 'old', instanceId: I4, mode: 'EXTERNAL', allocationSlot: 3 }],
    state: 'PREVIEW_ONLY',
    executionEnabled: false,
    blockers: ['部署執行器鎖定'],
    steps: ['a', 'b'],
  });
  const v = cm.previewView(p, cm.normalizeStatus(STATUS), [{ id: 'candidate-1', sha256: 'A'.repeat(64) }]);
  assert.equal(v.epochStale, true);
  assert.equal(v.sha, 'MATCH');
  assert.deepEqual(v.memberChanges.map((m) => m.siteId), ['old']);
  const lock = cm.clusterLockReasons({ deploymentExecutionEnabled: true }, v);
  assert.equal(lock.locked, true);
  assert.ok(lock.reasons.length > 0);
  assert.ok(lock.reasons.some((r) => r.includes('已過期')));
  assert.ok(lock.reasons.some((r) => r.includes('部署執行器鎖定')));
  assert.equal(cm.clusterLockReasons(null, null).locked, true);
});

test('releaseOptions：不符合格式的 ID 標示為不可選，不改寫', () => {
  const opts = cm.releaseOptions([{ id: 'candidate-1' }, { id: 'v1.2' }]);
  assert.equal(opts[0].valid, true);
  assert.equal(opts[1].valid, false);
  assert.equal(opts[1].value, 'v1.2');
});

// ---------- 冪等寫入 ----------

test('createIntentStore：結果不明時重送相同 requestId 與原請求內容；參數不同則新 ID', () => {
  let n = 0;
  const store = cm.createIntentStore(() => `00000000-0000-4000-8000-00000000000${++n}`);
  const a = store.acquire('JOIN:site-3', (id) => ({ requestId: id, expectedEpoch: 5 }));
  assert.equal(a.replay, false);
  const again = store.acquire('JOIN:site-3', (id) => ({ requestId: id, expectedEpoch: 9 }));
  assert.equal(again.replay, true);
  assert.equal(again.id, a.id);
  assert.deepEqual(again.body, { requestId: a.id, expectedEpoch: 5 }, '沿用原 expectedEpoch');
  const other = store.acquire('RETIRE:site-3', (id) => ({ requestId: id, expectedEpoch: 5 }));
  assert.notEqual(other.id, a.id);
  store.release('JOIN:site-3');
  assert.equal(store.peek('JOIN:site-3'), null);
  const fresh = store.acquire('JOIN:site-3', (id) => ({ requestId: id, expectedEpoch: 6 }));
  assert.notEqual(fresh.id, a.id);
  store.clear();
  assert.equal(store.size(), 0);
});

test('writeOutcome：網路／逾時／中止／503 為結果未知；STALE epoch 與其他 409 分開', () => {
  assert.equal(cm.writeOutcome({ kind: 'network', outcomeUnknown: true }), 'UNKNOWN');
  assert.equal(cm.writeOutcome({ kind: 'http', status: 503, code: 'X', outcomeUnknown: false }), 'UNKNOWN');
  assert.equal(cm.writeOutcome({ kind: 'http', status: 409, code: 'STALE_MEMBERSHIP_EPOCH' }), 'STALE_EPOCH');
  assert.equal(cm.writeOutcome({ kind: 'http', status: 409, code: 'RETIREMENT_REFERENCES_PENDING' }), 'REJECTED');
  assert.equal(cm.writeOutcome({ kind: 'validation' }), 'REJECTED');
});

test('clusterErrorAdvice：可操作說明，且 PENDING 條件明示不可豁免', () => {
  for (const code of ['INITIAL_SYNC_PENDING', 'LOCAL_ACCOUNT_ARCHIVE_PENDING', 'RETIREMENT_REFERENCES_PENDING', 'RETIREMENT_OBJECTS_PENDING', 'RETIREMENT_UPLOADS_PENDING', 'GC_DECISIONS_PENDING']) {
    assert.ok(cm.clusterErrorAdvice(code).includes('不可由中控豁免'), code);
  }
  assert.ok(cm.clusterErrorAdvice('STALE_MEMBERSHIP_EPOCH').includes('重新讀取'));
  assert.ok(cm.clusterErrorAdvice('MEMBER_IDENTITY_OR_SLOT_RETAINED').includes('不可重用'));
  assert.equal(cm.clusterErrorAdvice('UNKNOWN_CODE'), null);
  const msg = cm.writeFailureMessage({ kind: 'network', outcomeUnknown: true }, 'UNKNOWN', { requestId: UUID_A });
  assert.ok(msg.lines.some((l) => l.includes(UUID_A)));
});

test('randomUuid：產生 v4 UUID，可退回 getRandomValues', () => {
  const fake = { getRandomValues: (b) => b.fill(0xab) };
  const id = cm.randomUuid(fake);
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.throws(() => cm.randomUuid({}));
});

// ---------- 能力與 worker ----------

test('capabilityAnomalies 與 workerSummary', () => {
  const ok = cm.normalizeCapabilities({ topology: 'SINGLE_CLUSTER', maximumActiveSites: 16, allocationSlots: 1024, coordinatorTransferEnabled: false, businessSessionShared: true, deploymentExecutionEnabled: false, remoteFailureMatrixAccepted: false });
  assert.deepEqual(cm.capabilityAnomalies(ok), []);
  assert.equal(cm.capabilityAnomalies({ ...ok, deploymentExecutionEnabled: true }).length, 0);
  const w = cm.workerSummary({ running: true, lastCode: 'OK' });
  assert.ok(w.label.includes('不代表各站健康'));
  assert.ok(cm.workerSummary({ running: true, lastCode: 'SOME_PROTOCOL_ERROR' }).label.includes('原樣'));
  assert.equal(cm.workerSummary(null).tone, 'amber');
});

test('normalizeClusterAudit：snake_case 轉換並依 id 新到舊', () => {
  const list = cm.normalizeClusterAudit([
    { id: 1, request_id: UUID_A, actor: 'admin', action: 'JOIN', site_id: 's', epoch: 1, created_at: '2026-10-16T00:00:00Z' },
    { id: 3, request_id: UUID_B, actor: 'admin', action: 'GC_BEGIN', site_id: null, epoch: 2, created_at: '2026-10-16T00:00:01Z' },
  ]);
  assert.deepEqual(list.map((e) => e.id), [3, 1]);
  assert.equal(list[0].siteId, null);
});

test('worker SINGLE_SITE_ACTIVE 是明確的正常碼；其他相似或未知字串仍告警', () => {
  const single = cm.workerSummary({ running: true, lastCode: 'SINGLE_SITE_ACTIVE' });
  assert.equal(single.tone, 'teal');
  assert.equal(single.label, '單站集群運行中');
  assert.equal(cm.workerCodeNormal('SINGLE_SITE_ACTIVE'), true);
  for (const code of ['OK', 'NOT_STARTED', 'AWAITING_FIRST_JOIN']) assert.equal(cm.workerCodeNormal(code), true, code);
  for (const code of ['ACTIVE', 'single_site_active', 'SINGLE_SITE_ACTIVE ', 'MULTI_SITE_ACTIVE', 'SINGLE_SITE_ACTIVE_DEGRADED', 'CLUSTER_CYCLE_RETRY', 'SOME_PROTOCOL_ERROR', '', null, undefined, 1]) {
    assert.equal(cm.workerCodeNormal(code), false, String(code));
  }
  for (const code of ['ACTIVE', 'MULTI_SITE_ACTIVE', 'CLUSTER_CYCLE_RETRY', 'SOME_PROTOCOL_ERROR']) {
    assert.equal(cm.workerSummary({ running: true, lastCode: code }).tone, 'amber', code);
  }
});
