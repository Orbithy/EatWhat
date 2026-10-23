import test from 'node:test';
import assert from 'node:assert/strict';

import * as model from '../state.js';
import { UUID_A, UUID_B } from './helpers.js';

const T0 = Date.parse('2026-10-16T00:00:00Z');

function observedNode(overrides = {}) {
  const { database: dbOverrides, ...rest } = overrides;
  return {
    siteId: 'campus-acceptance',
    instanceId: '11111111-1111-4111-8111-111111111111',
    mode: 'CAMPUS',
    observedAt: '2026-10-16T00:00:00Z',
    observationStatus: 'OBSERVED',
    readiness: 'NOT_CHECKED',
    storageReachability: 'NOT_CHECKED',
    deploymentExecutionEnabled: false,
    channels: { events: { state: 'FORWARDING', expiresAt: '', acceptedConnections: 0, forwardedBytes: 0, routingVerified: false, maximumBlockSeconds: 60 } },
    database: {
      databaseFingerprint: 'a'.repeat(32),
      v35: true,
      restrictedRole: true,
      coordinationGuard: true,
      delivery: { READY: 2, ACKED: 4 },
      inbox: { APPLIED: 4 },
      cdn: { READY: 1 },
      gc: { PREPARING: 1 },
      zeta: { X: 1 },
      activeReferences: 1,
      unpublishedReceipts: 0,
      multipartJobs: 0,
      recentGc: [],
      ...dbOverrides,
    },
    ...rest,
  };
}

function sitesPayload({ pair = 'VERIFIED_DISTINCT', campus = observedNode(), external = observedNode({ siteId: 'external-acceptance' }) } = {}) {
  return { observedAt: '2026-10-16T00:00:00Z', pairStatus: pair, sites: { campus, external } };
}

test('freshnessOf：NEVER / FRESH / STALE / ERROR', () => {
  let r = model.emptyResource();
  assert.equal(model.freshnessOf(r, T0), 'NEVER');
  r = model.succeed(r, {}, T0);
  assert.equal(model.freshnessOf(r, T0 + 5000), 'FRESH');
  assert.equal(model.freshnessOf(r, T0 + 15001), 'STALE');
  r = model.fail(r, { kind: 'network' }, T0 + 6000);
  assert.equal(model.freshnessOf(r, T0 + 6000), 'ERROR', '一次失敗即標記失效');
  assert.deepEqual(r.data, {}, '保留舊資料但標為失效');
  r = model.succeed(r, { x: 1 }, T0 + 7000);
  assert.equal(model.freshnessOf(r, T0 + 7000), 'FRESH');
});

test('connectionSummary：取最差狀態', () => {
  const ok = model.succeed(model.emptyResource(), {}, T0);
  const bad = model.fail(ok, { kind: 'timeout' }, T0 + 1000);
  assert.equal(model.connectionSummary([ok, ok], T0 + 1000).level, 'FRESH');
  const s = model.connectionSummary([ok, bad], T0 + 1000);
  assert.equal(s.level, 'ERROR');
  assert.equal(s.error.kind, 'timeout');
});

test('deriveSites：讀取失敗時 OBSERVED 降級為 STALE，配對狀態不提供', () => {
  let r = model.succeed(model.emptyResource(), sitesPayload(), T0);
  let v = model.deriveSites(r, T0 + 1000);
  assert.equal(v.trusted, true);
  assert.equal(v.pairStatus, 'VERIFIED_DISTINCT');
  assert.equal(v.sites.campus.effectiveStatus, 'OBSERVED');

  r = model.fail(r, { kind: 'network' }, T0 + 5000);
  v = model.deriveSites(r, T0 + 5000);
  assert.equal(v.trusted, false);
  assert.equal(v.pairStatus, null);
  assert.equal(v.lastKnownPairStatus, 'VERIFIED_DISTINCT');
  assert.equal(v.sites.campus.effectiveStatus, 'STALE');
  assert.equal(v.sites.external.effectiveStatus, 'STALE');
});

test('deriveSites：尚未讀取為 LOADING，首次即失敗為 STALE，皆非 OBSERVED', () => {
  const v = model.deriveSites(model.emptyResource(), T0);
  assert.equal(v.sites.campus.effectiveStatus, 'LOADING');
  const failed = model.deriveSites(model.fail(model.emptyResource(), { kind: 'network' }, T0), T0);
  assert.equal(failed.freshness, 'ERROR');
  assert.equal(failed.sites.campus.effectiveStatus, 'STALE');
  assert.equal(failed.pairStatus, null);
});

test('deriveSites：過期（無新成功讀取）也降級', () => {
  const r = model.succeed(model.emptyResource(), sitesPayload(), T0);
  const v = model.deriveSites(r, T0 + 20000);
  assert.equal(v.freshness, 'STALE');
  assert.equal(v.sites.campus.effectiveStatus, 'STALE');
});

test('deriveSites：UNKNOWN、缺站、缺 database 都明確標示', () => {
  const payload = {
    observedAt: '2026-10-16T00:00:00Z',
    pairStatus: 'UNVERIFIED',
    sites: {
      campus: { observationStatus: 'UNKNOWN', reason: 'NODE_NOT_CONFIGURED', observedAt: '2026-10-16T00:00:00Z' },
    },
  };
  const v = model.deriveSites(model.succeed(model.emptyResource(), payload, T0), T0);
  assert.equal(v.sites.campus.effectiveStatus, 'UNKNOWN');
  assert.equal(v.sites.campus.reason, 'NODE_NOT_CONFIGURED');
  assert.equal(v.sites.external.effectiveStatus, 'MISSING');

  const noDb = observedNode();
  delete noDb.database;
  const v2 = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ campus: noDb }), T0), T0);
  assert.equal(v2.sites.campus.effectiveStatus, 'OBSERVED');
  assert.equal(v2.sites.campus.databaseMissing, true);
  assert.equal(v2.sites.campus.database, null);
});

test('deriveSites：節點觀測時間差以伺服器時間計算', () => {
  const late = observedNode({ observedAt: '2026-10-15T23:59:20Z' });
  const v = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ campus: late }), T0), T0);
  assert.equal(v.sites.campus.lagMs, 40000);
  assert.equal(v.sites.campus.lagExceeded, true);
  assert.equal(v.sites.external.lagExceeded, false);
});

test('normalizeChannels：routingVerified 只有明確 true 才為 true，非法通道名稱略過', () => {
  const list = model.normalizeChannels({
    events: { state: 'FORWARDING' },
    files: { state: 'BLOCKED', routingVerified: 'true', expiresAt: '2026-10-16T00:00:30Z' },
    'bad name': { state: 'FORWARDING' },
  });
  assert.deepEqual(list.map((c) => c.name), ['events', 'files']);
  assert.equal(list[0].routingVerified, false);
  assert.equal(list[1].routingVerified, false);
  assert.equal(list[0].expiresAt, null);
});

test('counterGroups：分組鍵動態、排序固定、非計數物件略過', () => {
  const groups = model.counterGroups(observedNode().database);
  assert.deepEqual(groups.map((g) => g.key), ['delivery', 'inbox', 'cdn', 'gc', 'zeta']);
  assert.equal(groups[0].total, 6);
  assert.deepEqual(groups[0].entries, [['ACKED', 4], ['READY', 2]]);
  const scalars = Object.fromEntries(model.scalarCounters(observedNode().database));
  assert.deepEqual(scalars, { activeReferences: 1, multipartJobs: 0, unpublishedReceipts: 0 });
  assert.deepEqual(model.counterGroups(null), []);
});

test('compareGc：兩站並列、單邊回報、狀態差異；不產生刪除推論欄位', () => {
  const op1 = '33333333-3333-4333-8333-333333333333';
  const op2 = '66666666-6666-4666-8666-666666666666';
  const fileId = '22222222-2222-4222-8222-222222222222';
  const campus = observedNode({
    database: {
      recentGc: [
        { file_id: fileId, revision: 1, operation_id: op1, role: 'COORDINATOR', state: 'PREPARING', peer_prepared: false, peer_deleted: false, updated_at: '2026-10-16T00:00:00Z' },
        { file_id: fileId, revision: 2, operation_id: op2, role: 'COORDINATOR', state: 'PREPARING', peer_prepared: false, peer_deleted: true, updated_at: '2026-10-16T00:00:05Z' },
      ],
    },
  });
  const external = observedNode({
    database: {
      recentGc: [{ file_id: fileId, revision: 1, operation_id: op1, role: 'PARTICIPANT', state: 'PREPARED', peer_prepared: true, peer_deleted: false, updated_at: '2026-10-16T00:00:01Z' }],
    },
  });
  const v = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ campus, external }), T0), T0);
  const gc = model.compareGc(v);
  assert.equal(gc.rows.length, 2);
  assert.equal(gc.rows[0].operationId, op2, '最新的排前面');
  assert.equal(gc.rows[0].presence, 'ONLY_CAMPUS');
  assert.equal(gc.rows[1].presence, 'BOTH');
  assert.equal(gc.rows[1].stateDiffers, true);
  assert.equal(gc.rows[1].sameRole, false);
  assert.equal(gc.rows[1].identityMismatch, false);
  for (const row of gc.rows) {
    assert.ok(!Object.keys(row).some((k) => /delet|purg|remov/i.test(k)), '不推導已實體刪除');
  }
  // 原值照傳，不改寫 peer_deleted。
  assert.equal(gc.rows[0].sides.campus.peer_deleted, true);
});

test('compareGc：無資料庫時 availability=false', () => {
  const noDb = observedNode();
  delete noDb.database;
  const v = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ campus: noDb }), T0), T0);
  const gc = model.compareGc(v);
  assert.equal(gc.availability.campus, false);
  assert.equal(gc.availability.external, true);
});

test('faultPrecheck：BLOCK 需要新鮮資料、VERIFIED_DISTINCT、OBSERVED、30 秒內', () => {
  const known = { campus: ['events'], external: ['events'] };
  const fresh = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload(), T0), T0);
  assert.deepEqual(model.faultPrecheck({ sitesView: fresh, knownChannels: known, site: 'campus', channel: 'events', action: 'BLOCK' }).blocking, []);

  const unverified = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ pair: 'UNVERIFIED' }), T0), T0);
  assert.ok(model.faultPrecheck({ sitesView: unverified, knownChannels: known, site: 'campus', channel: 'events', action: 'BLOCK' }).blocking.length > 0);

  const stale = model.deriveSites(model.fail(model.succeed(model.emptyResource(), sitesPayload(), T0), { kind: 'network' }, T0 + 5000), T0 + 5000);
  assert.ok(model.faultPrecheck({ sitesView: stale, knownChannels: known, site: 'campus', channel: 'events', action: 'BLOCK' }).blocking.length > 0);

  const late = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ campus: observedNode({ observedAt: '2026-10-15T23:59:00Z' }) }), T0), T0);
  assert.ok(model.faultPrecheck({ sitesView: late, knownChannels: known, site: 'campus', channel: 'events', action: 'BLOCK' }).blocking.some((t) => t.includes('30')));

  assert.ok(model.faultPrecheck({ sitesView: fresh, knownChannels: known, site: 'campus', channel: 'nope', action: 'BLOCK' }).blocking.length > 0);
});

test('faultPrecheck：RESTORE 在資料失效時仍可用最後已知通道', () => {
  const stale = model.deriveSites(model.fail(model.succeed(model.emptyResource(), sitesPayload(), T0), { kind: 'network' }, T0 + 5000), T0 + 5000);
  const known = { campus: ['events'], external: [] };
  const r = model.faultPrecheck({ sitesView: stale, knownChannels: known, site: 'campus', channel: 'events', action: 'RESTORE' });
  assert.deepEqual(r.blocking, []);
  assert.ok(r.warnings.length > 0);
  const options = model.channelOptions(stale, known, 'campus', 'RESTORE');
  assert.deepEqual(options, [{ value: 'events', label: 'events（最後已知）' }]);
  assert.deepEqual(model.channelOptions(stale, known, 'campus', 'BLOCK'), [], 'BLOCK 不使用失效通道清單');
});

test('mergeKnownChannels：累積各站通道名稱', () => {
  let known = model.mergeKnownChannels({}, sitesPayload());
  assert.deepEqual(known, { campus: ['events'], external: ['events'] });
  known = model.mergeKnownChannels(known, { sites: { campus: { channels: { files: {} } } } });
  assert.deepEqual(known.campus, ['events', 'files']);
});

test('任務：INTERRUPTED 不是執行中；COMMAND_SUCCEEDED 不是遠端驗收', () => {
  assert.equal(model.isRunActive({ state: 'RUNNING' }), true);
  assert.equal(model.isRunActive({ state: 'QUEUED' }), true);
  for (const s of ['INTERRUPTED', 'COMMAND_SUCCEEDED', 'FAILED', 'CANCELLED']) assert.equal(model.isRunActive({ state: s }), false);
  const notes = model.runNotes({ state: 'COMMAND_SUCCEEDED', remoteAcceptance: 'NOT_RUN', deploymentPerformed: false, reportStatus: 'REVIEW_REQUIRED' });
  assert.ok(notes.some((n) => n.includes('不代表遠端驗收通過')));
  assert.ok(notes.some((n) => n.includes('NOT_RUN')));
  assert.ok(notes.some((n) => n.includes('人工審查')));
  assert.ok(model.runNotes({ state: 'INTERRUPTED', deploymentPerformed: false }).some((n) => n.includes('不在執行中')));
  assert.ok(model.runNotes({ state: 'QUEUED' }).some((n) => n.includes('deploymentPerformed')), 'deploymentPerformed 缺失需警示');
});

test('mergeRuns：清單優先、依建立時間新到舊、忽略無效 ID', () => {
  const lookups = new Map([
    [UUID_A, { run: { id: UUID_A, state: 'RUNNING', createdAt: '2026-10-16T00:00:00Z' }, fetchedAt: T0 }],
    [UUID_B, { run: { id: UUID_B, state: 'INTERRUPTED', createdAt: '2026-10-15T00:00:00Z' }, fetchedAt: T0 }],
  ]);
  const listed = [{ id: UUID_A, state: 'COMMAND_SUCCEEDED', createdAt: '2026-10-16T00:00:00Z' }, { id: 'bad', state: 'RUNNING' }];
  const merged = model.mergeRuns(listed, lookups);
  assert.equal(merged.length, 2);
  assert.equal(merged[0].run.id, UUID_A);
  assert.equal(merged[0].run.state, 'COMMAND_SUCCEEDED');
  assert.equal(merged[0].source, 'list');
  assert.equal(model.findActiveRun(merged), null, 'INTERRUPTED 不會被當成執行中');
});

test('startBlockers：未啟用、資料失效、已有任務都阻擋', () => {
  const suite = { id: 'business', scope: 'LOCAL_BUSINESS_RUSTFS', enabled: true };
  assert.deepEqual(model.startBlockers(suite, { runsFreshness: 'FRESH', activeEntry: null }), []);
  assert.ok(model.startBlockers({ ...suite, enabled: false }, { runsFreshness: 'FRESH', activeEntry: null }).length);
  assert.ok(model.startBlockers(suite, { runsFreshness: 'ERROR', activeEntry: null }).length);
  assert.ok(model.startBlockers(suite, { runsFreshness: 'FRESH', activeEntry: { run: { id: UUID_A, state: 'RUNNING' } } }).length);
  assert.ok(model.startBlockers({ id: 'shell', enabled: true }, { runsFreshness: 'FRESH', activeEntry: null }).length);
  assert.ok(model.startBlockers({ ...suite, scope: 'OTHER' }, { runsFreshness: 'FRESH', activeEntry: null }).length);
});

test('deploymentLockReasons：有阻擋項時維持停用', () => {
  const lock = model.deploymentLockReasons({ deploymentExecutionEnabled: true }, { executionEnabled: true, blockers: ['部署執行器鎖定', { code: 'BACKUP_UNVERIFIED' }] });
  assert.equal(lock.locked, true);
  assert.ok(lock.reasons.length > 0);
  assert.ok(lock.reasons.some((r) => r.includes('部署執行器鎖定')));
  assert.ok(lock.reasons.some((r) => r.includes('BACKUP_UNVERIFIED')));
  assert.equal(model.deploymentLockReasons(null, null).locked, true);
});

test('planShaStatus 與 toDisplayItems', () => {
  const releases = [{ id: 'candidate-1', sha256: 'ABC' }];
  assert.equal(model.planShaStatus({ releaseId: 'candidate-1', artifactSha256: 'abc' }, releases), 'MATCH');
  assert.equal(model.planShaStatus({ releaseId: 'candidate-1', artifactSha256: 'def' }, releases), 'MISMATCH');
  assert.equal(model.planShaStatus({ releaseId: 'x', artifactSha256: 'abc' }, releases), 'UNKNOWN');
  const items = model.toDisplayItems([{ name: 'render', target: 'campus', n: 1 }, 'plain', 3]);
  assert.deepEqual(items[0], { title: 'render', details: [['target', 'campus'], ['n', '1']] });
  assert.deepEqual(items[1], { title: 'plain', details: [] });
  assert.deepEqual(items[2], { title: '3', details: [] });
  assert.deepEqual(model.toDisplayItems(undefined), []);
});

test('auditEntries：升序轉為新到舊，過濾非物件', () => {
  const list = [{ at: '1' }, null, { at: '2' }];
  assert.deepEqual(model.auditEntries(list).map((e) => e.at), ['2', '1']);
  assert.equal(model.auditOutcomeTone('OUTCOME_UNKNOWN'), 'amber');
  assert.equal(model.auditOutcomeTone('REJECTED'), 'red');
  assert.equal(model.auditOutcomeTone('ACCEPTED'), 'neutral');
});

// ---------- 站點告警與摘要 ----------

test('siteAlerts：OBSERVED 仍提示未檢查項目，不產生「健康」語義', () => {
  const v = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload(), T0), T0);
  const alerts = model.siteAlerts(v.sites.campus);
  assert.ok(alerts.some((a) => a.text.includes('readiness') && a.tone === 'amber'));
  assert.ok(alerts.some((a) => a.text.includes('storageReachability') && a.tone === 'amber'));
  assert.ok(!alerts.some((a) => /健康|正常|通過/.test(a.text) && !/未通過/.test(a.text)));
});

test('siteAlerts：失效、UNKNOWN、缺 database、身份核對失敗、阻斷與缺上限', () => {
  const stale = model.deriveSites(model.fail(model.succeed(model.emptyResource(), sitesPayload(), T0), { kind: 'network' }, T0 + 5000), T0 + 5000);
  assert.equal(model.siteAlerts(stale.sites.campus)[0].tone, 'red');

  const unknown = unknownSitesView();
  assert.ok(model.siteAlerts(unknown.sites.campus).some((a) => a.text.includes('UNKNOWN') && a.text.includes('NODE_NOT_CONFIGURED')));

  const noDb = observedNode();
  delete noDb.database;
  const badDb = observedNode({ database: { v35: false } });
  const blocked = observedNode({ channels: { events: { state: 'BLOCKED' } } });
  const v = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ campus: noDb, external: badDb }), T0), T0);
  assert.ok(model.siteAlerts(v.sites.campus).some((a) => a.text.includes('database')));
  assert.ok(model.siteAlerts(v.sites.external).some((a) => a.text.includes('V35') && a.tone === 'red'));
  const vb = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ campus: blocked }), T0), T0);
  const ba = model.siteAlerts(vb.sites.campus);
  assert.ok(ba.some((a) => a.text.includes('已阻斷')));
  assert.ok(ba.some((a) => a.text.includes('BLOCK 上限')));

  assert.deepEqual(model.siteAlerts(model.deriveSites(model.emptyResource(), T0).sites.campus), [], '讀取中不產生告警');
});

test('channelSummary 與 gcSummary', () => {
  assert.deepEqual(model.channelSummary([{ state: 'FORWARDING' }, { state: 'BLOCKED' }, { state: 'X' }]), { total: 3, forwarding: 1, blocked: 1, other: 1 });
  assert.deepEqual(model.channelSummary(undefined), { total: 0, forwarding: 0, blocked: 0, other: 0 });
  const gc = { rows: [{ presence: 'BOTH', stateDiffers: true }, { presence: 'ONLY_CAMPUS', stateDiffers: null }] };
  assert.deepEqual(model.gcSummary(gc), { total: 2, both: 1, oneSided: 1, stateDiffers: 1 });
  assert.deepEqual(model.gcSummary(null), { total: 0, both: 0, oneSided: 0, stateDiffers: 0 });
});

// ---------- configuredChannels 與 maximumBlockSeconds ----------

const CAPS = {
  configuredChannels: {
    campus: [
      { name: 'events', maximumBlockSeconds: 60, source: 'HUB_CONFIG', state: 'NOT_OBSERVED', routingVerified: false },
      { name: 'files', maximumBlockSeconds: 999, source: 'HUB_CONFIG', state: 'NOT_OBSERVED', routingVerified: false },
      { name: 'bad name' },
      { name: 'events' },
    ],
    external: [],
  },
};

function unknownSitesView() {
  const payload = {
    observedAt: '2026-10-16T00:00:00Z',
    pairStatus: 'UNVERIFIED',
    sites: {
      campus: { observationStatus: 'UNKNOWN', reason: 'NODE_NOT_CONFIGURED', observedAt: '2026-10-16T00:00:00Z' },
      external: { observationStatus: 'UNKNOWN', reason: 'NODE_NOT_CONFIGURED', observedAt: '2026-10-16T00:00:00Z' },
    },
  };
  return model.deriveSites(model.succeed(model.emptyResource(), payload, T0), T0);
}

test('parseBlockLimit：只接受 1–300 整數', () => {
  assert.equal(model.parseBlockLimit(60), 60);
  assert.equal(model.parseBlockLimit(1), 1);
  assert.equal(model.parseBlockLimit(300), 300);
  for (const bad of [0, 301, 1.5, '60', null, undefined]) assert.equal(model.parseBlockLimit(bad), null);
  const [ch] = model.normalizeChannels({ events: { state: 'FORWARDING', maximumBlockSeconds: '60' } });
  assert.equal(ch.maximumBlockSeconds, null, '字串上限視為缺值，不猜測');
});

test('normalizeConfiguredChannels：過濾非法與重複名稱，未提供時標示未設定', () => {
  const cfg = model.normalizeConfiguredChannels(CAPS);
  assert.equal(cfg.configured, true);
  assert.deepEqual(cfg.sites.campus.map((c) => c.name), ['events', 'files']);
  assert.equal(cfg.sites.campus[1].maximumBlockSeconds, null);
  assert.deepEqual(cfg.sites.external, []);
  const none = model.normalizeConfiguredChannels({ apiVersion: 'v1' });
  assert.equal(none.configured, false);
  assert.deepEqual(none.sites, { campus: [], external: [] });
});

test('首次登入即遇節點 UNKNOWN：RESTORE 可從設定清單選擇，BLOCK 不可', () => {
  const view = unknownSitesView();
  const cfg = model.normalizeConfiguredChannels(CAPS);
  const empty = { campus: [], external: [] };
  const restore = model.channelOptions(view, empty, 'campus', 'RESTORE', cfg);
  assert.deepEqual(restore, [
    { value: 'events', label: 'events（設定清單，未觀測）' },
    { value: 'files', label: 'files（設定清單，未觀測）' },
  ]);
  const check = model.faultPrecheck({ sitesView: view, knownChannels: empty, configured: cfg, site: 'campus', channel: 'events', action: 'RESTORE', seconds: 1 });
  assert.deepEqual(check.blocking, []);
  assert.ok(check.warnings.some((w) => w.includes('configuredChannels')));
  assert.ok(check.warnings.some((w) => w.includes('無法確認節點是否在線')));

  assert.deepEqual(model.channelOptions(view, empty, 'campus', 'BLOCK', cfg), [], 'BLOCK 不使用設定清單');
  const block = model.faultPrecheck({ sitesView: view, knownChannels: empty, configured: cfg, site: 'campus', channel: 'events', action: 'BLOCK', seconds: 10 });
  assert.ok(block.blocking.length > 0);
  assert.equal(model.blockLimit(view, 'campus', 'events'), null, '設定清單上限不得當成觀測上限');
});

test('設定清單為空時不猜測通道名稱', () => {
  const view = unknownSitesView();
  const cfg = model.normalizeConfiguredChannels(CAPS);
  const empty = { campus: [], external: [] };
  assert.deepEqual(model.channelOptions(view, empty, 'external', 'RESTORE', cfg), []);
  const none = model.normalizeConfiguredChannels({});
  assert.deepEqual(model.channelOptions(view, empty, 'campus', 'RESTORE', none), []);
  const check = model.faultPrecheck({ sitesView: view, knownChannels: empty, configured: cfg, site: 'external', channel: 'events', action: 'RESTORE', seconds: 1 });
  assert.ok(check.blocking.some((b) => b.includes('不在')));
});

test('RESTORE 選項合併目前觀測、最後已知與設定清單，目前觀測不加註記', () => {
  const view = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload(), T0), T0);
  const cfg = model.normalizeConfiguredChannels(CAPS);
  const known = { campus: ['events', 'legacy'], external: [] };
  assert.deepEqual(model.channelOptions(view, known, 'campus', 'RESTORE', cfg), [
    { value: 'events', label: 'events' },
    { value: 'files', label: 'files（設定清單，未觀測）' },
    { value: 'legacy', label: 'legacy（最後已知）' },
  ]);
});

test('BLOCK：使用最新有效觀測的 maximumBlockSeconds 限制秒數', () => {
  const view = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload(), T0), T0);
  const known = { campus: ['events'], external: ['events'] };
  assert.equal(model.blockLimit(view, 'campus', 'events'), 60);
  assert.deepEqual(model.channelOptions(view, known, 'campus', 'BLOCK'), [{ value: 'events', label: 'events（上限 60 秒）' }]);
  const ok = model.faultPrecheck({ sitesView: view, knownChannels: known, site: 'campus', channel: 'events', action: 'BLOCK', seconds: 60 });
  assert.deepEqual(ok.blocking, []);
  const over = model.faultPrecheck({ sitesView: view, knownChannels: known, site: 'campus', channel: 'events', action: 'BLOCK', seconds: 61 });
  assert.ok(over.blocking.some((b) => b.includes('超過') && b.includes('60')));
});

test('BLOCK：上限缺值即阻擋並顯示缺值', () => {
  const node = observedNode({ channels: { events: { state: 'FORWARDING', routingVerified: false } } });
  const view = model.deriveSites(model.succeed(model.emptyResource(), sitesPayload({ campus: node }), T0), T0);
  assert.equal(model.blockLimit(view, 'campus', 'events'), null);
  assert.deepEqual(model.channelOptions(view, {}, 'campus', 'BLOCK'), [{ value: 'events', label: 'events（上限缺值）' }]);
  const check = model.faultPrecheck({ sitesView: view, knownChannels: {}, site: 'campus', channel: 'events', action: 'BLOCK', seconds: 5 });
  assert.ok(check.blocking.some((b) => b.includes('缺值')));
});

test('BLOCK：資料失效時不提供上限（不沿用舊觀測）', () => {
  const r = model.fail(model.succeed(model.emptyResource(), sitesPayload(), T0), { kind: 'network' }, T0 + 5000);
  const view = model.deriveSites(r, T0 + 5000);
  assert.equal(model.blockLimit(view, 'campus', 'events'), null);
});

test('auditDetails：取出 details.seconds，其餘鍵值轉文字', () => {
  assert.deepEqual(model.auditDetails({ details: { seconds: 30, channel: 'events', nested: { a: 1 } } }), {
    seconds: 30,
    extra: [['channel', 'events'], ['nested', '{"a":1}']],
  });
  assert.deepEqual(model.auditDetails({}), { seconds: null, extra: [] });
  assert.deepEqual(model.auditDetails({ details: { seconds: '30' } }), { seconds: null, extra: [['seconds', '30']] }, '非整數 seconds 不當成時限');
});
