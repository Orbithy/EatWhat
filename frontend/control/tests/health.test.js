// 節點健康檢測：有效狀態、就緒與 S3 分開、存儲讀寫探針、阻擋條件、總覽問題、API 與靜態結構。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as hs from '../health-state.js';
import { homeModel } from '../home-state.js';
import { createClusterClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');

const NOW = Date.parse('2026-10-17T10:00:30Z');
const RID = '5d2c8e1f-3a4b-4c6d-8e9f-0a1b2c3d4e5f';
const pass = (over = {}) => ({ status: 'PASS', code: null, checkKind: 'X', startedAt: '2026-10-17T10:00:00Z', checkedAt: '2026-10-17T10:00:01Z', validUntil: '2026-10-17T10:01:01Z', durationMs: 12, running: false, ...over });
const control = (over = {}) => ({
  readiness: 'READY', storageReachability: 'REACHABLE',
  healthChecks: { application: pass(), database: pass(), redis: pass(), storage: pass({ checkKind: 'S3_HEAD_BUCKET_AND_EMPTY_LIST' }) },
  storageWriteCheck: { state: 'NOT_CHECKED' },
  ...over,
});

test('單項檢測：有效 PASS 才是通過；過期、缺有效期限、觀測失效都改為已過期', () => {
  assert.equal(hs.effectiveCheck(pass(), { now: NOW, current: true }).status, 'PASS');
  const expired = hs.effectiveCheck(pass({ validUntil: '2026-10-17T10:00:00Z' }), { now: NOW, current: true });
  assert.equal(expired.status, 'STALE');
  assert.equal(expired.expired, true);
  assert.equal(hs.effectiveCheck(pass({ validUntil: undefined }), { now: NOW, current: true }).status, 'STALE');
  assert.equal(hs.effectiveCheck(pass(), { now: NOW, current: false }).status, 'STALE', '舊觀測的成功不是新健康');
  assert.equal(hs.effectiveCheck({ status: 'CHECKING', running: true }, { now: NOW, current: true }).status, 'CHECKING', '只有回報 CHECKING 才是檢測中');
  const fail = hs.effectiveCheck({ status: 'FAIL', code: 'DATABASE_NOT_READY', running: false }, { now: NOW, current: true });
  assert.deepEqual([fail.status, fail.code], ['FAIL', 'DATABASE_NOT_READY']);
  // 單項逾時：UNKNOWN + CHECK_TIMEOUT + running=true（底層連線尚未返回）仍是未知，另標示仍在結束。
  const timeout = hs.effectiveCheck({ status: 'UNKNOWN', code: 'CHECK_TIMEOUT', running: true }, { now: NOW, current: true });
  assert.deepEqual([timeout.status, timeout.finishing], ['UNKNOWN', true]);
  for (const st of ['FAIL', 'STALE']) assert.equal(hs.effectiveCheck({ status: st, running: true }, { now: NOW, current: true }).status, st, `${st} 不被 running 覆蓋`);
  assert.equal(hs.effectiveCheck(pass({ running: true }), { now: NOW, current: true }).finishing, true);
  assert.equal(hs.effectiveCheck({ status: 'CHECKING', running: true }, { now: NOW, current: true }).finishing, false);
  assert.equal(hs.effectiveCheck({ status: 'GREEN' }, { now: NOW, current: true }).status, 'UNKNOWN', '未知狀態不當成通過');
  assert.equal(hs.effectiveCheck(undefined, { now: NOW, current: true }).status, 'NOT_CHECKED');
});

test('就緒只看應用／資料庫／Redis；S3 桶存取分開判斷', () => {
  const ok = hs.healthModel(control(), { now: NOW });
  assert.deepEqual([ok.readiness.value, ok.readiness.tone, ok.storage.value, ok.storage.tone], ['READY', 'teal', 'REACHABLE', 'teal']);
  const s3Down = hs.healthModel(control({ storageReachability: 'UNREACHABLE', healthChecks: { ...control().healthChecks, storage: { status: 'FAIL', code: 'STORAGE_ACCESS_DENIED' } } }), { now: NOW });
  assert.equal(s3Down.readiness.value, 'READY', '桶不可存取不影響就緒');
  assert.equal(s3Down.storage.value, 'UNREACHABLE');
  assert.match(hs.healthCodeText('STORAGE_ACCESS_DENIED'), /401／403/);
  assert.ok(!/403/.test(hs.healthCodeText('STORAGE_REQUEST_FAILED')), '403 屬於 STORAGE_ACCESS_DENIED');
  const dbExpired = hs.healthModel(control({ healthChecks: { ...control().healthChecks, database: pass({ validUntil: '2026-10-17T10:00:00Z' }) } }), { now: NOW });
  assert.equal(dbExpired.readiness.value, 'STALE', '後端回報 READY 但資料庫結果已過期');
  assert.equal(dbExpired.readiness.tone, 'amber');
  const stale = hs.healthModel(control(), { now: NOW, current: false });
  assert.notEqual(stale.readiness.tone, 'teal');
  assert.notEqual(stale.storage.tone, 'teal');
  const s3Expired = hs.healthModel(control({ healthChecks: { ...control().healthChecks, storage: pass({ validUntil: '2026-10-17T09:00:00Z' }) } }), { now: NOW });
  assert.equal(s3Expired.storage.value, 'STALE');
});

// 後端 ControlHealthChecks.java 與 ControlStorageCheck.java 回傳的安全碼（不含狀態、階段與 checkKind）。
const BACKEND_HEALTH_CODES = ['CHECK_IN_PROGRESS', 'CHECK_TIMEOUT', 'CHECK_RESULT_EXPIRED', 'CHECK_CAPACITY_EXCEEDED', 'DEPENDENCY_CHECK_FAILED',
  'APPLICATION_NOT_ACCEPTING_TRAFFIC', 'DATABASE_NOT_READY', 'DATABASE_UNAVAILABLE', 'REDIS_NOT_READY', 'REDIS_UNAVAILABLE',
  'STORAGE_NOT_CONFIGURED', 'STORAGE_ACCESS_DENIED', 'STORAGE_BUCKET_NOT_FOUND', 'STORAGE_UNREACHABLE', 'STORAGE_TIMEOUT',
  'STORAGE_TEMPORARILY_UNAVAILABLE', 'STORAGE_REQUEST_FAILED', 'STORAGE_ENDPOINT_NOT_S3', 'STORAGE_INVALID_S3_RESPONSE'];
const BACKEND_STORAGE_CHECK_CODES = ['REQUEST_ID_REQUIRED', 'STORAGE_CHECK_CLEANUP_REQUIRED', 'STORAGE_CHECK_CONFIGURATION_CHANGED', 'STORAGE_CHECK_HISTORY_FULL',
  'STORAGE_CHECK_INTERRUPTED_CLEANED', 'STORAGE_CHECK_LOCKED', 'STORAGE_CHECK_STATE_UNAVAILABLE', 'STORAGE_CHECK_STOPPING',
  'STORAGE_CLEANUP_IN_PROGRESS', 'STORAGE_READ_MISMATCH', 'STORAGE_PROBE_CLEANUP_FAILED', 'STORAGE_PROBE_OWNERSHIP_MISMATCH', 'STORAGE_NOT_CONFIGURED'];

test('錯誤碼對齊後端全部安全碼；未知碼不翻譯', () => {
  for (const code of [...BACKEND_HEALTH_CODES, ...BACKEND_STORAGE_CHECK_CODES]) assert.ok(hs.healthCodeText(code), code);
  for (const code of ['DATABASE_UNAVAILABLE', 'REDIS_UNAVAILABLE', 'STORAGE_ACCESS_DENIED', 'STORAGE_BUCKET_NOT_FOUND', 'STORAGE_UNREACHABLE']) assert.ok(hs.healthCodeText(code), `保留 ${code}`);
  for (const unknown of ['HEALTH_CHECK_TIMEOUT', 'STORAGE_CLEANUP_FAILED', 'STORAGE_CHECK_BUSY', 'FUTURE_CODE', 'POSTGRES_SELECT_1', 'S3_HEAD_BUCKET_AND_EMPTY_LIST']) assert.equal(hs.healthCodeText(unknown), null, unknown);
});

test('S3 協議校驗：端點不是 S3 或回應不合法時提示檢查入口與反向代理，不宣稱桶不存在', () => {
  for (const code of ['STORAGE_ENDPOINT_NOT_S3', 'STORAGE_INVALID_S3_RESPONSE']) {
    const text = hs.healthCodeText(code);
    assert.match(text, /S3 API 入口/, code);
    assert.match(text, /反向代理/, code);
    assert.match(text, /不代表桶不存在/, code);
    assert.ok(!/找不到|桶不存在。$/.test(text.replace('不代表桶不存在', '')), code);
  }
  assert.match(hs.healthCodeText('STORAGE_ENDPOINT_NOT_S3'), /HTML 404／403/);
  assert.match(hs.healthCodeText('STORAGE_INVALID_S3_RESPONSE'), /HTTP 200/);
  assert.match(hs.healthCodeText('STORAGE_READ_MISMATCH'), /讀回.*不符/);
  const render = read('cluster-render.js');
  assert.match(render, /'S3 桶存取'\)/, '卡片標題為「S3 桶存取」');
  assert.match(render, /HEAD＋空列表（maxKeys=0）[^']*不讀取業務物件內容[^']*不等於讀寫健康/);
  assert.ok(!render.includes('HEAD Bucket'));
});

test('依賴檢測快照：CONFIGURATION_ONLY／DEPENDENCY_CHECKS_ONLY 不視為資料庫身份核實', () => {
  assert.equal(hs.healthModel(control({ observationStatus: 'DEPENDENCY_CHECKS_ONLY', identityProof: 'CONFIGURATION_ONLY' }), { now: NOW }).dependencyOnly, true);
  assert.equal(hs.healthModel(control({ identityProof: 'CONFIGURATION_ONLY' }), { now: NOW }).dependencyOnly, true);
  assert.equal(hs.healthModel(control({ observationStatus: 'OBSERVED' }), { now: NOW }).dependencyOnly, false);
  const render = read('cluster-render.js');
  assert.match(render, /health\.dependencyOnly \? h\('p', \{ class: 'alert alert-amber' \}/);
  assert.match(render, /不代表資料庫身份已核實/);
});

test('舊後端或缺欄位：一律未檢查，不補成正常；未知值顯示未知', () => {
  const old = hs.healthModel({ readiness: undefined }, { now: NOW });
  assert.equal(old.provided, false);
  assert.deepEqual([old.readiness.value, old.storage.value, old.write.state], ['NOT_CHECKED', 'NOT_CHECKED', 'NOT_CHECKED']);
  assert.ok(old.checks.every((c) => c.status === 'NOT_CHECKED' && c.tone === 'neutral'));
  assert.equal(hs.healthModel(null, { now: NOW }).provided, false);
  const weird = hs.healthModel({ readiness: 'GREEN', storageReachability: 'OK' }, { now: NOW });
  assert.deepEqual([weird.readiness.value, weird.storage.value], ['UNKNOWN', 'UNKNOWN']);
  assert.equal(hs.healthModel({ readiness: 'NOT_CHECKED' }, { now: NOW }).readiness.value, 'NOT_CHECKED');
});

test('存儲讀寫驗證：只有 PASSED 標示本次通過；清理待處理為琥珀色；未知狀態不當成功', () => {
  const w = (state, extra = {}) => hs.healthModel(control({ storageWriteCheck: { state, requestId: RID, phase: 'CLEANUP', code: null, startedAt: '2026-10-17T09:59:00Z', updatedAt: '2026-10-17T09:59:03Z', ...extra } }), { now: NOW }).write;
  assert.deepEqual([w('PASSED').tone, w('PASSED').label], ['teal', '本次讀寫驗證通過']);
  assert.equal(w('CLEANUP_PENDING').tone, 'amber');
  assert.equal(w('CLEANUP_PENDING').phaseText, '清理');
  assert.equal(w('FAILED', { code: 'STORAGE_CLEANUP_FAILED' }).tone, 'red');
  assert.equal(w('RUNNING').tone, 'amber');
  assert.equal(w('DONE').state, 'UNKNOWN');
  assert.equal(w('DONE').tone, 'amber');
  assert.equal(hs.healthModel(control({ storageWriteCheck: { state: 'RUNNING', phase: 'WRITE' } }), { now: NOW }).anyRunning, true);
});

test('阻擋條件：只讀檢測備機也可；寫入驗證只限主控且不能在進行中或清理待處理時開新任務', () => {
  assert.deepEqual(hs.readCheckBlockers({ observable: true, sessionActive: true }), []);
  const healthy = hs.healthModel(control(), { now: NOW });
  assert.deepEqual(hs.storageWriteBlockers(healthy, { leadWritable: true, current: true }), []);
  assert.ok(hs.storageWriteBlockers(healthy, { leadWritable: false, leadReasons: ['此進程是備機'], current: true }).includes('此進程是備機'));
  assert.ok(hs.storageWriteBlockers(healthy, { leadWritable: false, current: true })[0].includes('主中控'));
  assert.ok(hs.storageWriteBlockers(healthy, { leadWritable: true, current: false }).length > 0, '觀測失效不能開新驗證');
  const running = hs.healthModel(control({ storageWriteCheck: { state: 'RUNNING' } }), { now: NOW });
  assert.ok(hs.storageWriteBlockers(running, { leadWritable: true, current: true }).length > 0);
  const pending = hs.healthModel(control({ storageWriteCheck: { state: 'CLEANUP_PENDING', requestId: RID } }), { now: NOW });
  assert.ok(hs.storageWriteBlockers(pending, { leadWritable: true, current: true }).some((r) => /重試清理/.test(r)));
  assert.deepEqual(hs.cleanupRetryBlockers(pending, { leadWritable: true }), []);
  assert.ok(hs.cleanupRetryBlockers(pending, { leadWritable: false }).length > 0, '備機不能重試清理');
  assert.ok(hs.cleanupRetryBlockers(hs.healthModel(control({ storageWriteCheck: { state: 'CLEANUP_PENDING', requestId: 'x' } }), { now: NOW }), { leadWritable: true }).length > 0, '缺有效 requestId 不重試');
  assert.ok(hs.cleanupRetryBlockers(healthy, { leadWritable: true }).length > 0);
});

test('總覽問題：未就緒與桶不可存取為紅色、清理待處理為琥珀；未檢查不列入；SINGLE_SITE_ACTIVE 與健康站點零問題', () => {
  const model = (ctl, lastCode = 'SINGLE_SITE_ACTIVE') => homeModel({
    statusFreshness: 'FRESH', obsFreshness: 'FRESH', now: NOW,
    status: { epoch: 1, pendingChanges: [], worker: { running: true, lastCode } },
    views: [{ siteId: 'campus', state: 'ACTIVE', mode: 'CAMPUS', observable: true, observed: 'REACHABLE', observation: { reachable: true, control: ctl } }],
    lead: { role: 'LEADER', writable: true, reasons: [] },
  });
  assert.deepEqual(model(control()).issues, []);
  assert.deepEqual(model({ readiness: 'NOT_CHECKED', storageReachability: 'NOT_CHECKED' }).issues, [], '舊後端未檢查不算問題');
  const bad = model(control({ readiness: 'NOT_READY', storageReachability: 'UNREACHABLE', storageWriteCheck: { state: 'CLEANUP_PENDING', requestId: RID } })).issues;
  assert.ok(bad.some((i) => i.tone === 'red' && /未就緒/.test(i.title)));
  assert.ok(bad.some((i) => i.tone === 'red' && /S3 桶無法存取/.test(i.title)));
  assert.ok(bad.some((i) => i.tone === 'amber' && /清理待處理/.test(i.title)));
  const expired = model(control({ healthChecks: { ...control().healthChecks, redis: pass({ validUntil: '2026-10-17T09:00:00Z' }) } })).issues;
  assert.ok(expired.some((i) => /就緒狀態已過期/.test(i.title)), '舊成功不當新健康');
  assert.ok(model(control(), 'CLUSTER_CYCLE_RETRY').issues.some((i) => i.title === 'worker 回報錯誤碼'), 'worker 錯誤仍告警');
});

test('健康 API：只讀檢測與存儲探針的路徑與本文', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createClusterClient({ session: fakeSession(), fetchImpl });
  await client.startHealthCheck('campus', RID);
  await client.startStorageCheck('campus', RID);
  assert.deepEqual(fetchImpl.calls.map((c) => `${c.init.method} ${c.url}`), [
    'POST /admin/control/v1/cluster/sites/campus/health-checks', 'POST /admin/control/v1/cluster/sites/campus/storage-checks',
  ]);
  assert.deepEqual(fetchImpl.calls[1].body, { requestId: RID });
  for (const bad of [() => client.startHealthCheck('../x', RID), () => client.startStorageCheck('campus', 'nope'), () => client.startStorageCheck('campus')]) {
    await assert.rejects(bad(), (e) => e.kind === 'validation');
  }
  assert.equal(fetchImpl.calls.length, 2);
});

test('靜態：寫入驗證確認後重查領導、結果未知保留 requestId；只讀檢測不需租約；遲到回應丟棄；切頁停止加快輪詢', () => {
  const app = read('cluster-app.js');
  const render = read('cluster-render.js');
  const section = (a, b) => app.slice(app.indexOf(a), app.indexOf(b, app.indexOf(a)));
  const read1 = section('async function onHealthCheck', 'async function onStorageWrite');
  assert.ok(!/lead|leadership|confirmDialog/.test(read1), '只讀檢測不看租約、不需確認');
  assert.match(read1, /hg !== healthGen/);
  const write = section('async function onStorageWrite', 'function onMembersClick');
  assert.ok(write.indexOf('confirmDialog(') < write.indexOf('cm.recheckLeadership('), '確認後重查領導');
  assert.ok(write.indexOf('cm.recheckLeadership(') < write.indexOf('client.startStorageCheck('));
  assert.match(write, /intents\.acquire\(intentKey/);
  assert.match(write, /outcome !== 'UNKNOWN'/);
  assert.match(write, /hg !== healthGen/);
  assert.equal((app.match(/client\.startStorageCheck\(/g) ?? []).length, 1);
  assert.equal((app.match(/client\.startHealthCheck\(/g) ?? []).length, 1);
  assert.match(section('function setSitesVisible', '// 供其他頁'), /healthGen \+= 1;[\s\S]*stopHealthWatch\(\)/);
  assert.match(section('function stop()', 'function refreshNow'), /healthGen \+= 1;\s*stopHealthWatch\(\);\s*healthMessages\.clear\(\);/);
  assert.match(read('app.js'), /cluster\?\.setSitesVisible\(route === 'sites'\);/);
  for (const act of ['check', 'storage', 'cleanup']) assert.ok(render.includes(`'data-focus-key': \`health:${act}:`), `${act} 保留焦點`);
  assert.ok(!/localStorage|sessionStorage/.test(read('health-state.js') + read1 + write));
  assert.ok(!/objectKey|probePath|secret|accessKey/i.test(render.slice(render.indexOf('function healthSection'), render.indexOf('function memberCard'))), '不顯示探針路徑或憑據');
});
