// 任務歷史、權威發布約束、保留資料的恢復安裝與單機版本更新限制。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as ts from '../tasks-state.js';
import * as ss from '../servers-state.js';
import { effectiveJobState } from '../deployment-state.js';
import { jobSummary } from '../home-state.js';
import { createClusterClient, createControlClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const ID = '7c1e2b9a-41d3-4f6e-9a20-5d8b3e6f1c04';
const SID = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const JOB = '1e4c8d3b-0a52-4f7c-9b63-2d8f0a4e3c21';

// ---------- 任務歷史 ----------

test('詳情目標只由類型與 UUID 白名單組合', () => {
  for (const t of ['INSTALL', 'UPDATE', 'RECOVER']) assert.deepEqual(ts.taskDetailTarget(t, ID), { route: 'servers', target: 'server-job', id: ID });
  assert.deepEqual(ts.taskDetailTarget('ENROLLMENT', ID), { route: 'servers', target: 'cluster-job', id: ID });
  assert.deepEqual(ts.taskDetailTarget('DEPLOYMENT', ID), { route: 'deploy', target: 'deployment', id: ID });
  for (const [t, id] of [['OTHER', ID], ['INSTALL', '../x'], ['INSTALL', `${ID}?x=1`], ['DEPLOYMENT', ''], [undefined, ID]]) assert.equal(ts.taskDetailTarget(t, id), null, `${t} ${id}`);
  const code = read('tasks-ui.js').replace(/\/\/.*$/gm, '');
  assert.ok(!/detailRoute/.test(code), '程式不使用回應中的 detailRoute');
});

test('任務正規化只保留契約欄位；格式無效回 null', () => {
  const t = ts.normalizeTask({ id: ID, type: 'INSTALL', state: 'FAILED', code: 'X', updatedAt: '2026-10-17T10:00:00Z', serverId: SID, _plan: { secret: 1 }, token: 't', detailRoute: 'https://evil.example/x', requiresReconciliation: 'yes' });
  assert.ok(!('_plan' in t) && !('token' in t) && !('detailRoute' in t));
  assert.equal(t.requiresReconciliation, false);
  assert.equal(ts.normalizeTask({ id: 'bad', state: 'X' }), null);
  assert.equal(ts.normalizeTaskPage({}), null);
  assert.equal(ts.normalizeTaskPage(null), null);
  const page = ts.normalizeTaskPage({ items: [{ id: ID, state: 'RUNNING' }, { id: 'x' }], nextCursor: 'c1', observedAt: '2026-10-17T10:00:00Z' });
  assert.deepEqual([page.items.length, page.dropped, page.nextCursor], [1, 1, 'c1']);
});

test('游標分頁合併：同一任務只保留較新的一筆，順序不變', () => {
  const a = { id: 'a', updatedAt: '2026-10-17T10:00:00Z', state: 'RUNNING' };
  const b = { id: 'b', updatedAt: '2026-10-17T10:00:00Z', state: 'RUNNING' };
  const merged = ts.mergeTasks([a, b], [{ ...a, updatedAt: '2026-10-17T10:05:00Z', state: 'SUCCEEDED' }, { id: 'c', updatedAt: '2026-10-17T09:00:00Z', state: 'FAILED' }, { ...b, updatedAt: '2026-10-17T09:00:00Z', state: 'QUEUED' }]);
  assert.deepEqual(merged.map((t) => `${t.id}:${t.state}`), ['a:SUCCEEDED', 'b:RUNNING', 'c:FAILED']);
});

test('查詢字串只含固定欄位；非法篩選值不送出', () => {
  assert.equal(ts.taskQuery({ type: 'RECOVER', state: 'FAILED', serverId: SID, cursor: 'x/y&z' }), `limit=20&type=RECOVER&state=FAILED&serverId=${SID}&cursor=x%2Fy%26z`);
  assert.equal(ts.taskQuery({ type: 'DROP', state: 'ALL', serverId: 'nope' }), 'limit=20');
});

test('需核對或約束未收斂顯示結果未知；已證明未派發是失敗', () => {
  assert.equal(ts.taskDisplay({ state: 'SUCCEEDED', requiresReconciliation: true }).unresolved, true);
  for (const code of ['DEPLOYMENT_FENCE_FINALIZATION_REQUIRED', 'DEPLOYMENT_ADMISSION_OUTCOME_UNKNOWN']) {
    const d = ts.taskDisplay({ state: 'SUCCEEDED', code });
    assert.equal(d.unresolved, true, code);
    assert.notEqual(d.tone, 'teal', code);
  }
  assert.equal(ts.taskDisplay({ state: 'OUTCOME_UNKNOWN' }).label, '結果未知 · 需核對');
  const nd = ts.taskDisplay({ state: 'FAILED', code: 'AUTHORITY_NO_DISPATCH_PROVEN' });
  assert.deepEqual([nd.tone, nd.unresolved], ['red', false]);
  assert.match(ts.taskCodeText('AUTHORITY_NO_DISPATCH_PROVEN'), /重新建立部署預覽/);
  assert.equal(ts.taskDisplay({ state: 'SUCCEEDED' }).tone, 'teal');
  for (const code of ['TASK_HISTORY_SCAN_LIMIT', 'TASK_HISTORY_INVALID_RECORD', 'TASK_HISTORY_UNAVAILABLE']) assert.ok(ts.taskCodeText(code), code);
  assert.match(ts.taskCodeText('TASK_HISTORY_UNAVAILABLE'), /不是「沒有任務」/);
  // 掃描上限先於篩選：不能建議縮小篩選或刪除任務；交由管理員處理，仍可依 UUID 開啟。
  const scan = ts.taskCodeText('TASK_HISTORY_SCAN_LIMIT');
  assert.match(scan, /管理員/);
  assert.match(scan, /UUID/);
  assert.ok(!/縮小|刪除/.test(scan), scan);
});

test('部署任務：約束未收斂碼在部署頁與總覽都是結果未知', () => {
  assert.equal(effectiveJobState({ state: 'SUCCEEDED', code: 'DEPLOYMENT_FENCE_FINALIZATION_REQUIRED' }), 'OUTCOME_UNKNOWN');
  assert.equal(effectiveJobState({ state: 'RUNNING', reason: 'DEPLOYMENT_ADMISSION_OUTCOME_UNKNOWN' }), 'OUTCOME_UNKNOWN');
  assert.equal(effectiveJobState({ state: 'SUCCEEDED' }), 'SUCCEEDED');
  assert.equal(jobSummary({ id: ID, state: 'SUCCEEDED', code: 'DEPLOYMENT_FENCE_FINALIZATION_REQUIRED' }).state, 'OUTCOME_UNKNOWN');
  assert.match(read('deployment-ui.js'), /reconcileButton\.disabled = busy \|\| !job \|\| state !== 'OUTCOME_UNKNOWN'/);
});

test('權威發布約束：不可用或格式無效都不偽造空列表；只保留有效 job_id', () => {
  assert.deepEqual(ts.normalizeFences({ available: false, reason: 'DEPLOYMENT_FENCE_UNAVAILABLE' }), { available: false, reason: 'DEPLOYMENT_FENCE_UNAVAILABLE', active: [], dropped: 0 });
  assert.equal(ts.normalizeFences(null).available, false);
  // available=true 但 active 缺失／不是陣列／全部無效：格式無效、狀態未知。
  for (const bad of [{ available: true }, { available: true, active: {} }, { available: true, active: 'x' }, { available: true, active: [{ job_id: 'x' }, null] }]) {
    const f = ts.normalizeFences(bad);
    assert.deepEqual([f.available, f.reason], [false, 'DEPLOYMENT_FENCE_INVALID_RESPONSE'], JSON.stringify(bad));
  }
  assert.match(ts.taskCodeText('DEPLOYMENT_FENCE_INVALID_RESPONSE'), /狀態未知/);
  assert.deepEqual(ts.normalizeFences({ available: true, active: [] }), { available: true, reason: null, active: [], dropped: 0 }, '明確空陣列才是沒有約束');
  const partial = ts.normalizeFences({ available: true, active: [{ job_id: ID }, { job_id: 'bad' }] });
  assert.deepEqual([partial.available, partial.active.length, partial.dropped], [true, 1, 1]);
  assert.equal(ts.normalizeFences({ available: 'true', active: [] }).available, false);
  const f = ts.normalizeFences({ available: true, active: [{ job_id: ID, state: 'ACTIVE', dispatch_authorized: true, epoch: 3 }, { job_id: 'x' }] });
  assert.equal(f.active.length, 1);
  assert.deepEqual([f.active[0].jobId, f.active[0].dispatchAuthorized, f.active[0].epoch], [ID, true, 3]);
  assert.match(ts.taskCodeText('DEPLOYMENT_FENCE_UNAVAILABLE'), /不代表沒有未結束的發布/);
});

test('API：任務列表、恢復計畫、權威發布約束', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  const cluster = createClusterClient({ session: fakeSession(), fetchImpl });
  await client.listTasks(ts.taskQuery({ type: 'INSTALL', cursor: 'abc' }));
  await client.createRecoveryPlan(SID, { expectedRevision: 4, sourceJobId: JOB });
  await cluster.getDeploymentFences();
  assert.deepEqual(fetchImpl.calls.map((c) => `${c.init.method} ${c.url}`), [
    'GET /admin/control/v1/tasks?limit=20&type=INSTALL&cursor=abc',
    `POST /admin/control/v1/servers/${SID}/recovery-plans`,
    'GET /admin/control/v1/cluster/deployment-fences',
  ]);
  assert.deepEqual(fetchImpl.calls[1].body, { expectedRevision: 4, sourceJobId: JOB });
  for (const bad of [() => client.listTasks('limit=20&url=https://x'), () => client.listTasks('x'), () => client.createRecoveryPlan(SID, { expectedRevision: 4, sourceJobId: JOB, command: 'rm' }),
    () => client.createRecoveryPlan(SID, { expectedRevision: 4 }), () => client.createRecoveryPlan('x', { expectedRevision: 4, sourceJobId: JOB })]) {
    await assert.rejects(bad(), (e) => e.kind === 'validation');
  }
  assert.equal(fetchImpl.calls.length, 3);
});

// ---------- 恢復安裝與版本更新 ----------

const srv = (inst, over = {}) => ss.normalizeServer({ id: SID, name: 'campus', revision: 4, location: 'CONTROL_HOST', host: '127.0.0.1', port: 22, username: 'root', authentication: 'PASSWORD', hasCredential: true, connection: { state: 'CONNECTED' }, installation: inst, ...over });

test('缺少 clusterMembership 視為未確定；只有 NOT_JOINED 才能用單機版本更新', () => {
  assert.equal(srv({ state: 'SUCCEEDED' }).installation.clusterMembership, null);
  assert.deepEqual(ss.updateAvailability(srv({ state: 'SUCCEEDED', clusterMembership: 'NOT_JOINED' })).reasons, []);
  for (const m of [undefined, 'ACTIVE', 'JOINING', 'not_joined']) {
    const u = ss.updateAvailability(srv({ state: 'SUCCEEDED', clusterMembership: m }));
    assert.equal(u.show, true);
    assert.deepEqual(u.reasons, ['需使用集群部署'], String(m));
  }
  assert.equal(ss.updateAvailability(srv({ state: 'FAILED' })).show, false);
  for (const code of ['JOINED_SERVER_CLUSTER_DEPLOYMENT_REQUIRED', 'INSTALLED_SERVER_PORT_IMMUTABLE', 'INSTALLED_SERVER_URL_IMMUTABLE']) assert.ok(ss.serverCodeText(code), code);
  assert.match(ss.serverCodeText('JOINED_SERVER_CLUSTER_DEPLOYMENT_REQUIRED'), /需使用集群部署/);
});

test('恢復安裝：只有已核實 FAILED 的 INSTALL／RECOVER 來源任務；結果未知只提供核對', () => {
  const failed = srv({ state: 'FAILED', jobId: JOB });
  const src = (job) => ({ loading: false, error: null, job: { id: JOB, kind: 'INSTALL', state: 'FAILED', ...job } });
  assert.equal(ss.recoveryAvailability(failed, src({})).mode, 'recover');
  assert.equal(ss.recoveryAvailability(failed, src({ kind: 'RECOVER' })).mode, 'recover');
  assert.equal(ss.recoveryAvailability(failed, src({ state: 'OUTCOME_UNKNOWN' })).mode, 'reconcile');
  assert.equal(ss.recoveryAvailability(failed, src({ kind: 'UPDATE' })).mode, 'blocked');
  assert.equal(ss.recoveryAvailability(failed, src({ state: 'RUNNING' })).mode, 'blocked');
  assert.equal(ss.recoveryAvailability(failed, src({ id: ID })).mode, 'blocked', '來源任務與登記不符');
  assert.equal(ss.recoveryAvailability(failed, { loading: true }).mode, 'pending');
  assert.equal(ss.recoveryAvailability(failed, { loading: false, error: new Error('x') }).mode, 'blocked');
  assert.equal(ss.recoveryAvailability(failed, undefined).mode, 'pending');
  assert.equal(ss.recoveryAvailability(srv({ state: 'FAILED' }), src({})).mode, 'blocked', '缺少來源任務');
  assert.equal(ss.recoveryAvailability(srv({ state: 'SUCCEEDED', clusterMembership: 'NOT_JOINED' }), src({})).mode, 'none');
  assert.deepEqual(ss.recoveryRequestBody(failed), { expectedRevision: 4, sourceJobId: JOB });
  assert.equal(ss.recoveryRequestBody(srv({ state: 'SUCCEEDED' })), null);
});

test('恢復計畫：RECOVER 欄位與清理待處理的說明', () => {
  const p = ss.normalizePlan({ id: ID, kind: 'RECOVER', state: 'BLOCKED', blockers: ['RECOVERY_RESTORE_CLEANUP_REQUIRED'], sourceJobId: JOB, originalInstallJobId: JOB, siteId: 'campus', instanceId: SID, artifactSha256: 'a'.repeat(64), sourceStage: 'DATABASE_REDIS', dataPreserved: true, passwordsPreserved: true, administratorAction: 'PRESERVE_EXISTING', serverRevision: 4 });
  assert.deepEqual([p.kind, p.sourceJobId, p.sourceStage, p.dataPreserved, p.passwordsPreserved, p.administratorAction], ['RECOVER', JOB, 'DATABASE_REDIS', true, true, 'PRESERVE_EXISTING']);
  assert.equal(ss.normalizePlan({ id: ID, kind: 'RECOVER', dataPreserved: 'yes' }).dataPreserved, false);
  const text = ss.serverCodeText('RECOVERY_RESTORE_CLEANUP_REQUIRED');
  assert.match(text, /人工審閱/);
  assert.match(text, /不會刪除任何資料/);
});

test('靜態：恢復執行前重查來源任務；版本更新欄位唯讀；任務頁只讀', () => {
  const ui = read('servers-ui.js');
  const exec = ui.slice(ui.indexOf('async function executePlan'), ui.indexOf('// ---------- 任務 ----------'));
  assert.ok(exec.indexOf('fetchRecoverySource(current, { force: true })') < exec.indexOf('client.executeServerPlan('), '確認後重查來源任務');
  assert.match(exec, /captured\.kind === 'UPDATE'\) reasons\.push\(\.\.\.ss\.updateAvailability/);
  assert.match(ui, /for \(const input of \[els\.planPort, els\.planUrl\]\) input\.readOnly = kind !== 'INSTALL';/);
  assert.match(ui, /rec\.mode === 'reconcile' \? h\('button'[^)]*'data-action': 'source-job'/);
  assert.ok(!/恢復.*刪除|強制|force: true\) reasons/.test(ui.slice(ui.indexOf("'恢復安裝…'") - 200, ui.indexOf("'恢復安裝…'"))));
  const tasks = read('tasks-ui.js');
  assert.ok(!/reconcile|execute|POST/.test(tasks.replace(/核對/g, '')), '任務頁只讀，不核對或重送');
  assert.equal((tasks.match(/client\.listTasks\(/g) ?? []).length, 1);
  assert.ok(!/強制成功|清空任務|清除約束\W*按鈕/.test(tasks));
  assert.match(tasks, /const stale = \(\) => g !== gen \|\| lg !== listGen;/);
  assert.match(tasks, /if \(inflight && \(inflight\.query === query \|\| more\)\) return inflight\.promise;/, '分頁請求合併');
  assert.match(tasks, /const fg = \+\+fenceGen;/, '權威診斷有獨立請求世代');
  assert.match(tasks, /const current = \(\) => g === gen && fg === fenceGen && owner === fences;/, '舊詳情查詢不寫入新物件');
  assert.match(tasks, /d\.dropped \? h\('p'/, '部分記錄無效時提示診斷不完整');
  const app = read('app.js');
  assert.match(app, /if \(route === 'tasks'\) tasks\.enter\(\);\s*else tasks\.leave\(\);/);
  // 已有任務歷史：伺服器頁的任務區不再說沒有清單 API，改指向任務歷史並保留 UUID 查詢。
  const html = read('index.html');
  assert.ok(!/清單 API/.test(html));
  for (const id of ['server-job-panel', 'server-cluster-job-panel']) {
    const section = html.slice(html.indexOf(`id="${id}"`), html.indexOf('</section>', html.indexOf(`id="${id}"`)));
    assert.match(section, /任務歷史/, id);
    assert.match(section, /UUID/, id);
  }
  assert.match(app, /tasks\.reset\(\);/);
});
