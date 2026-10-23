// 控制領導（權威站主備租約）的純邏輯、API 與結構測試。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as cm from '../cluster-state.js';
import { createClusterClient } from '../api.js';
import { CLUSTER_BASE } from '../constants.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');

const fresh = (data, startedAt = 1000) => cm.monoSucceed(cm.emptyMono(), data, startedAt, startedAt + 10, 0);

function viewOf(leadership, { status = null, invalidatedAt = null, leadershipFreshness = 'FRESH', statusFreshness = 'FRESH' } = {}) {
  return cm.leadershipView({
    leadershipRes: leadership === undefined ? cm.emptyMono() : fresh(leadership),
    leadershipFreshness,
    statusRes: status ? fresh(status) : cm.emptyMono(),
    statusFreshness,
    invalidatedAt,
  });
}

test('normalizeLeadership：只採信布林 localLeader 與 ≥1 的 generation', () => {
  assert.deepEqual(cm.normalizeLeadership({ state: 'HELD', localLeader: true, generation: 3, expiresAt: '2026-10-16T00:00:15Z' }), {
    state: 'HELD',
    localLeader: true,
    localLeaderRaw: true,
    generation: 3,
    expiresAt: '2026-10-16T00:00:15Z',
  });
  const odd = cm.normalizeLeadership({ state: 'HELD', localLeader: 'true', generation: 0 });
  assert.equal(odd.localLeader, false, '字串 true 不算 leader');
  assert.equal(odd.generation, null);
  assert.equal(cm.normalizeLeadership({ localLeader: true }), null);
  assert.equal(cm.normalizeLeadership(null), null);
});

test('寫入只在此程序持有租約（HELD＋localLeader）或單程序 leader 時開放', () => {
  assert.equal(viewOf({ state: 'HELD', localLeader: true, generation: 2 }).writable, true);
  assert.equal(viewOf({ state: 'SINGLE_PROCESS', localLeader: true }).writable, true);
  const standby = viewOf({ state: 'HELD', localLeader: false, generation: 2 });
  assert.equal(standby.writable, false);
  assert.equal(standby.role, 'STANDBY');
  assert.ok(standby.reasons[0].includes('備機'));
  assert.equal(viewOf({ state: 'EXPIRED', localLeader: false }).role, 'EXPIRED');
  assert.equal(viewOf({ state: 'EXPIRED', localLeader: true }).writable, false, '過期即使曾是 leader 也停用');
  assert.equal(viewOf({ state: 'UNCLAIMED', localLeader: false }).writable, false);
  assert.equal(viewOf({ state: 'SINGLE_PROCESS', localLeader: false }).writable, false, '單程序回報非 leader 視為異常');
  assert.equal(viewOf({ state: 'NEW_STATE', localLeader: true }).writable, false, '未知狀態停用');
});

test('領導讀取失敗或過期：未知且停用；不沿用最後已知值作為授權', () => {
  const failed = cm.leadershipView({
    leadershipRes: cm.monoFail(fresh({ state: 'HELD', localLeader: true, generation: 1 }), { kind: 'http', status: 503 }),
    leadershipFreshness: 'ERROR',
    statusRes: cm.emptyMono(),
    statusFreshness: 'NEVER',
  });
  assert.equal(failed.writable, false);
  assert.equal(failed.role, 'UNKNOWN');
  assert.equal(failed.lastKnown.state, 'HELD', '只供顯示');
  assert.ok(failed.reasons[0].includes('未知'));
  assert.equal(viewOf({ state: 'HELD', localLeader: true }, { leadershipFreshness: 'STALE' }).writable, false);
});

test('/leadership 不可用時才採用同樣新鮮的 status.leadership', () => {
  const v = viewOf(undefined, { status: { epoch: 1, leadership: { state: 'HELD', localLeader: true, generation: 4 } }, leadershipFreshness: 'NEVER' });
  assert.equal(v.writable, true);
  assert.equal(v.source, 'status');
  const both = viewOf({ state: 'HELD', localLeader: false, generation: 5 }, { status: { leadership: { state: 'HELD', localLeader: true, generation: 4 } } });
  assert.equal(both.source, 'leadership', '專用接口優先');
  assert.equal(both.writable, false);
  const staleStatus = viewOf(undefined, { status: { leadership: { state: 'HELD', localLeader: true } }, leadershipFreshness: 'NEVER', statusFreshness: 'STALE' });
  assert.equal(staleStatus.writable, false);
});

test('收到 CONTROL_LEASE_NOT_HELD 後，之前開始的讀取不再採信', () => {
  const v = viewOf({ state: 'HELD', localLeader: true, generation: 2 }, { invalidatedAt: 5000 });
  assert.equal(v.writable, false);
  assert.equal(v.invalidated, true);
  const after = cm.leadershipView({
    leadershipRes: fresh({ state: 'HELD', localLeader: true, generation: 2 }, 6000),
    leadershipFreshness: 'FRESH',
    statusRes: cm.emptyMono(),
    statusFreshness: 'NEVER',
    invalidatedAt: 5000,
  });
  assert.equal(after.writable, true, '之後開始的新讀取可恢復');
});

test('recheckLeadership：確認期間失租、轉為備機或 generation 改變即不送出', () => {
  const leader = viewOf({ state: 'HELD', localLeader: true, generation: 2 });
  assert.deepEqual(cm.recheckLeadership({ captured: { generation: 2 }, view: leader }), []);
  const standby = viewOf({ state: 'HELD', localLeader: false, generation: 3 });
  assert.ok(cm.recheckLeadership({ captured: { generation: 2 }, view: standby })[0].includes('備機'));
  const newGen = viewOf({ state: 'HELD', localLeader: true, generation: 3 });
  assert.ok(cm.recheckLeadership({ captured: { generation: 2 }, view: newGen })[0].includes('由 2 變為 3'));
  const expired = viewOf({ state: 'EXPIRED', localLeader: false });
  assert.ok(cm.recheckLeadership({ captured: { generation: 2 }, view: expired }).length);
  const single = viewOf({ state: 'SINGLE_PROCESS', localLeader: true });
  assert.deepEqual(cm.recheckLeadership({ captured: { generation: null }, view: single }), []);
});

test('writeOutcome：CONTROL_LEASE_NOT_HELD 是失租結果，訊息保留 requestId 與登入態', () => {
  const err = { kind: 'http', status: 503, code: 'CONTROL_LEASE_NOT_HELD', outcomeUnknown: false };
  assert.equal(cm.writeOutcome(err), 'LEASE_NOT_HELD');
  assert.equal(cm.writeOutcome({ kind: 'http', status: 503, code: 'OTHER' }), 'UNKNOWN');
  const msg = cm.writeFailureMessage(err, 'LEASE_NOT_HELD', { requestId: '00000000-0000-4000-8000-000000000001' });
  assert.equal(msg.tone, 'amber');
  assert.ok(msg.lines.some((l) => l.includes('登入態保留')));
  assert.ok(msg.lines.some((l) => l.includes('00000000-0000-4000-8000-000000000001')));
});

test('API：GET /cluster/leadership；503 保持 http 錯誤（不視為登入失效）', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, { state: 'HELD', localLeader: false, generation: 7 }));
  const api = createClusterClient({ session: fakeSession(), fetchImpl });
  const data = await api.getLeadership();
  assert.equal(data.generation, 7);
  assert.equal(fetchImpl.calls[0].url, `${CLUSTER_BASE}/leadership`);
  assert.equal(fetchImpl.calls[0].init.method, 'GET');
  const api503 = createClusterClient({ session: fakeSession(), fetchImpl: fakeFetch(() => jsonResponse(503, { code: 'CONTROL_LEASE_NOT_HELD' })) });
  await assert.rejects(api503.getLeadership(), (e) => e.status === 503 && e.code === 'CONTROL_LEASE_NOT_HELD');
});

test('結構：8 個控制寫入都受領導權閘門保護，任務啟動／取消不受影響', () => {
  const app = read('cluster-app.js');
  const writes = app.slice(app.indexOf('function onMemberSubmit'), app.indexOf('// ---------- 事件 ----------'));
  assert.equal((writes.match(/runWrite\(\{\n\s*leaderGated: true,/g) ?? []).length, 8);
  const runs = app.slice(app.indexOf('function onStartSuite'), app.indexOf('async function onRunLookup'));
  assert.ok(!runs.includes('leaderGated'), '驗收任務沿用既有程序端 API，不受控制租約閘門影響');
  const body = app.slice(app.indexOf('async function runWrite'), app.indexOf('function onMemberSubmit'));
  const confirmAt = body.indexOf('await confirmDialog(');
  const leadRecheckAt = body.indexOf('cm.recheckLeadership(');
  const acquireAt = body.indexOf('intents.acquire(');
  assert.ok(confirmAt > 0 && leadRecheckAt > confirmAt && acquireAt > leadRecheckAt, '確認 → 重查領導權 → 取得 requestId');
  assert.ok(body.indexOf('if (!leadNow.writable)') < confirmAt, '開啟對話框前即擋下備機');
  assert.match(body, /outcome === 'LEASE_NOT_HELD'\) store\.leadershipInvalidatedAt = monoNow\(\)/);
  assert.match(app, /leadership: \(\) => client\.getLeadership\(opts\)/, '每輪輪詢讀取領導狀態');
});

test('結構：看板有控制領導區塊，且各表單停用條件含領導權', () => {
  const html = read('index.html');
  for (const id of ['cl-leadership', 'cl-resume-hint', 'cl-gc-resume-hint']) {
    assert.equal((html.match(new RegExp(`id="${id}"`, 'g')) ?? []).length, 1, id);
  }
  const app = read('cluster-app.js');
  for (const btn of ['registerSubmit', 'profilesReload', 'resumeSubmit', 'gcResumeSubmit']) {
    assert.match(app, new RegExp(`els\\.${btn}\\.disabled = [^\\n]*!d\\.lead\\.writable`), btn);
  }
  for (const list of ['memberBlocking = [...leaderBlocking(d)', 'reasons = [...leaderBlocking(d), ...reg.reasons]', 'reasons = [...leaderBlocking(d), ...cm.candidateCheck', 'reasons = [...leaderBlocking(d)];', 'blocking = [...leaderBlocking(d), ...check.blocking']) {
    assert.ok(app.includes(list), list);
  }
  const css = read('styles.css');
  assert.match(css, /\.leader-facts\s*\{[^}]*grid-template-columns:\s*repeat\(auto-fit, minmax\(150px, 1fr\)\)/);
});
