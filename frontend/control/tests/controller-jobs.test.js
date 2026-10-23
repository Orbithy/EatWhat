// 中控與業務安裝任務（CONTROLLER_INSTALL）只讀詳情：固定路由、不跟隨 detailRoute、公開欄位正規化、
// 結果未知／需要重新批准／缺欄位不顯示完成、只讀 GET 與頁面約束。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as ts from '../tasks-state.js';
import { createControlClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const ID = '7c1e2b9a-41d3-4f6e-9a20-5d8b3e6f1c04';
const SRC = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const DST = '1e4c8d3b-0a52-4f7c-9b63-2d8f0a4e3c21';
const CL = '44444444-4444-4444-8444-444444444444';
const uuid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

const steps = (state = 'SUCCEEDED', over = {}) => ts.CONTROLLER_PHASES.map((phase, i) => ({
  phase, id: uuid(i + 1), state: over[phase] ?? state, observedAt: '2026-10-18T01:00:00Z',
  ...(phase === 'DEPENDENCY_LINK' ? { operations: ['KEY', 'SOURCE', 'CONNECT'].map((operation, k) => ({ operation, nativeId: uuid(i + 1), role: operation === 'SOURCE' ? 'SOURCE' : 'TARGET', state: over[`op:${operation}`] ?? state, remoteState: 'X' })) } : {}),
}));
const view = (over = {}) => ({
  workflowVersion: 2, id: ID, kind: 'CONTROLLER_INSTALL', sourceServerId: SRC, serverId: DST, sourceRevision: 4, serverRevision: 7, clusterId: CL, epoch: 3,
  controllerSha256: 'c'.repeat(64), releaseId: 'release_2026_10_18', artifactSha256: 'b'.repeat(64), allocationSlot: 5,
  review: { targetEndpoint: '203.0.113.9:51820', httpPort: 18090, appBaseUrl: 'https://edge.example.org', secret: 'x' },
  permissionsRequired: ['ADD_EXACT_PRIVATE_PEER', 'INSTALL_RUNTIME_PACKAGES'], requestHash: 'h'.repeat(64),
  state: 'SUCCEEDED', stage: 'VERIFY', createdAt: '2026-10-18T00:00:00Z', acceptedAt: '2026-10-18T00:01:00Z', finishedAt: '2026-10-18T01:00:00Z',
  requiresReapproval: false, requiresReconciliation: false, steps: steps(), ...over,
});
const norm = (over) => ts.normalizeControllerJob(view(over), ID);

test('固定路由：CONTROLLER_INSTALL 只對到任務頁的只讀詳情；非法 UUID 或類型不給目標', () => {
  assert.deepEqual(ts.taskDetailTarget('CONTROLLER_INSTALL', ID), { route: 'tasks', target: 'controller-job', id: ID });
  for (const id of ['../x', `${ID}/execute`, `${ID}?x=1`, '', 'https://evil.example/x']) assert.equal(ts.taskDetailTarget('CONTROLLER_INSTALL', id), null, id);
  assert.equal(ts.taskDetailTarget('controller_install', ID), null);
  assert.ok(ts.TASK_TYPES.includes('CONTROLLER_INSTALL'));
  assert.equal(ts.TASK_TYPE_LABEL.CONTROLLER_INSTALL, '中控與業務安裝');
  assert.match(ts.taskQuery({ type: 'CONTROLLER_INSTALL' }), /type=CONTROLLER_INSTALL/);
});

test('列表不跟隨 detailRoute：正規化丟棄 detailRoute；頁面程式不讀它', () => {
  const t = ts.normalizeTask({ id: ID, type: 'CONTROLLER_INSTALL', state: 'RUNNING', updatedAt: '2026-10-18T00:00:00Z', detailRoute: 'https://evil.example/admin/control/v1/controller-jobs/x' });
  assert.ok(!('detailRoute' in t));
  assert.equal(t.type, 'CONTROLLER_INSTALL');
  const code = read('tasks-ui.js').replace(/\/\/.*$/gm, '');
  assert.ok(!/detailRoute/.test(code));
});

test('API：任務路徑只有固定 GET 詳情與撤銷未派發；非 UUID 不送出；沒有其他寫入方法', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, view()));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.getControllerJob(ID);
  assert.ok(fetchImpl.calls[0].url.endsWith(`/admin/control/v1/controller-jobs/${ID}`));
  assert.equal(fetchImpl.calls[0].init.method, 'GET');
  for (const bad of ['../x', `${ID}/accept`, '', null]) await assert.rejects(client.getControllerJob(bad), (e) => e.kind === 'validation', String(bad));
  assert.equal(fetchImpl.calls.length, 1);
  const api = read('api.js');
  // 任務路徑只有只讀詳情與「撤銷未派發」（2026-10-19 契約）；沒有接受、執行、派發、核對或重新批准。
  assert.deepEqual(api.match(/`\/controller-jobs[^`]*`/g), ['`/controller-jobs/${encodeURIComponent(id)}`', '`/controller-jobs/${encodeURIComponent(id)}/cancel`']);
  assert.ok(!/controller-jobs[^`]*\/(accept|execute|dispatch|reconcile|reapprove)/.test(api));
});

test('正規化：只保留公開欄位；UUID 不符或 kind 不符回 null；review 只留白名單鍵', () => {
  const j = norm();
  assert.equal(j.serverId, DST);
  assert.equal(j.sourceServerId, SRC);
  assert.equal(j.controllerSha256, 'c'.repeat(64));
  // requestHash 是公開欄位（批准時原樣送回）；私有封存欄位不帶出。
  assert.equal(j.requestHash, null, '非十六進位的 requestHash 不採用');
  assert.equal(norm({ requestHash: 'a1'.repeat(32) }).requestHash, 'a1'.repeat(32));
  assert.ok(!('_body' in j));
  assert.deepEqual(Object.keys(j.review).sort(), ['appBaseUrl', 'httpPort', 'targetEndpoint']);
  assert.equal(j.steps.length, 13);
  assert.equal(j.steps[6].operations.length, 3);
  assert.equal(ts.normalizeControllerJob(view(), '11111111-1111-4111-8111-111111111111'), null, '回應 UUID 與請求不符');
  assert.equal(ts.normalizeControllerJob(view({ kind: 'INSTALL' }), ID), null);
  assert.equal(ts.normalizeControllerJob(null, ID), null);
  assert.equal(ts.normalizeControllerJob([], ID), null);
  // 私有欄位即使出現在回應中也不帶出
  const withPrivate = ts.normalizeControllerJob({ ...view(), _body: 'enc', _actor: 'rpc', steps: steps().map((r) => ({ ...r, _receipt: 'enc', claimToken: 't' })) }, ID);
  assert.ok(!JSON.stringify(withPrivate).includes('enc') && !JSON.stringify(withPrivate).includes('claimToken'));
});

test('完成只在 SUCCEEDED、兩個旗標明確 false、13 階段與子操作全部完成時成立', () => {
  const ok = ts.controllerJobDisplay(norm());
  assert.deepEqual([ok.label, ok.complete, ok.unresolved], ['已完成（歷史記錄）', true, false]);
});

test('結果未知／核對／重新批准不能顯示為完成', () => {
  const cases = [
    [{ state: 'OUTCOME_UNKNOWN', code: 'BOOTSTRAP_REMOTE_OBSERVATION_REQUIRED', requiresReconciliation: true }, '結果未知 · 需核對'],
    [{ requiresReconciliation: true }, '結果未知 · 需核對'],
    [{ steps: steps('SUCCEEDED', { VERIFY: 'DISPATCHED' }) }, '結果未知 · 需核對'],
    [{ steps: steps('SUCCEEDED', { 'op:CONNECT': 'OUTCOME_UNKNOWN' }) }, '結果未知 · 需核對'],
    [{ requiresReapproval: true, state: 'QUEUED' }, '需要重新批准'],
    [{ requiresReapproval: true }, '需要重新批准'],
  ];
  for (const [over, label] of cases) {
    const d = ts.controllerJobDisplay(norm(over));
    assert.equal(d.label, label, JSON.stringify(over));
    assert.equal(d.complete, false);
    assert.equal(d.unresolved, true);
  }
});

test('缺欄位：旗標缺失、非布林、階段缺失／不全／順序錯、子操作未完成都不能確認完成', () => {
  for (const over of [{ requiresReapproval: undefined }, { requiresReconciliation: 'false' }, { steps: undefined }, { steps: steps().slice(0, 12) },
    { steps: [...steps()].reverse() }, { steps: steps('SUCCEEDED', { 'op:SOURCE': 'PENDING' }) }, { steps: steps('SUCCEEDED', { MEMBER_JOIN: 'PENDING' }) }]) {
    const j = norm(over);
    const d = ts.controllerJobDisplay(j);
    assert.equal(d.complete, false, JSON.stringify(Object.keys(over)));
    assert.equal(d.label, '回報完成 · 未能確認', JSON.stringify(over).slice(0, 80));
    assert.ok(d.notes.length > 0);
  }
  assert.equal(norm({ requiresReapproval: 'yes' }).requiresReapproval, null, '非布林為未知，不補成 false');
  assert.equal(norm({ controllerSha256: 'abc' }).controllerSha256, null);
  assert.equal(norm({ permissionsRequired: ['ok_lower'] }).permissionsRequired, null);
  const missingState = ts.controllerJobDisplay(norm({ state: undefined }));
  assert.equal(missingState.label, '狀態缺失');
  assert.equal(ts.controllerJobDisplay(norm({ state: 'FUTURE' })).label, '未知狀態 FUTURE');
  assert.equal(ts.controllerJobDisplay(null).complete, false);
});

test('其他狀態與階段文字；未知碼保留為 null（畫面顯示原碼）', () => {
  assert.equal(ts.controllerJobDisplay(norm({ state: 'FAILED', code: 'BOOTSTRAP_PHASE_FAILED_PRESERVED' })).label, '失敗');
  assert.equal(ts.controllerJobDisplay(norm({ state: 'RUNNING', steps: steps('PENDING', { SOURCE_RUNTIME: 'RUNNING' }) })).label, '執行中');
  assert.equal(ts.controllerJobDisplay(norm({ state: 'PREVIEW_ONLY', steps: steps('PENDING') })).label, '僅預覽 · 尚未接受');
  assert.deepEqual(ts.controllerStepDisplay('DISPATCHED'), { tone: 'red', label: '已派發 · 結果未確認' });
  assert.equal(ts.controllerStepDisplay('WEIRD').label, '未知狀態 WEIRD');
  assert.equal(ts.controllerStepDisplay(null).label, '狀態缺失');
  assert.match(ts.controllerCodeText('BOOTSTRAP_REAPPROVAL_REQUIRED'), /重新批准/);
  assert.equal(ts.controllerCodeText('BOOTSTRAP_FUTURE_CODE'), null);
  assert.equal(Object.keys(ts.CONTROLLER_PHASE_LABEL).length, 13);
});

test('頁面靜態：只讀面板只有重新讀取／關閉；遲到回應以世代丟棄；401／403 交給登出處理；404 有說明', () => {
  const html = read('index.html');
  assert.match(html, /<option value="CONTROLLER_INSTALL">中控與業務安裝<\/option><\/select><\/label>\s*<label class="field"><span class="field-label">狀態/);
  assert.match(html, /id="tasks-open-type">[\s\S]*?<option value="CONTROLLER_INSTALL">中控與業務安裝<\/option><\/select>/);
  const panel = html.slice(html.indexOf('id="tasks-controller-panel"'), html.indexOf('</section>', html.indexOf('id="tasks-controller-panel"')));
  const buttons = panel.match(/<button[^>]*>[^<]*<\/button>/g);
  assert.deepEqual(buttons.map((b) => b.replace(/<[^>]+>/g, '')), ['重新讀取', '關閉']);
  assert.match(panel, /不重試派發、不重新批准，也不核對/);
  const ui = read('tasks-ui.js');
  const open = ui.slice(ui.indexOf('async function openControllerJob'), ui.indexOf('function ctlErrorText'));
  assert.match(open, /const cg = \+\+ctlGen;/);
  assert.match(open, /const stale = \(\) => g !== gen \|\| cg !== ctlGen;/);
  assert.ok((open.match(/if \(stale\(\)\) return;/g) ?? []).length >= 2, '成功與失敗都先檢查世代');
  assert.match(open, /if \(isAuth\(err\)\) \{ onAuthFailure\(err\); return; \}/);
  assert.match(open, /normalizeControllerJob\(await client\.getControllerJob\(id\), id\)/);
  assert.match(ui, /err\?\.status === 404\) return \['找不到這個安裝任務/);
  assert.match(ui, /leave\(\) \{[\s\S]*?ctlGen \+= 1;/);
  assert.match(ui, /reset\(\) \{[\s\S]*?ctl = null;\s*ctlGen \+= 1;/);
  assert.match(ui, /if \(target\.target === 'controller-job'\) \{ event\.preventDefault\(\); showController\(target\.id\); return; \}/);
  assert.ok(!/client\.(execute|accept|reconcile|dispatch)\w*Controller/i.test(ui));
  assert.match(ui, /成功只是歷史記錄，不代表目前健康/);
});

// DEPENDENCY_LINK 父階段 id（第 7 階段）：uuid(7)
const DEP = uuid(7);
const withOps = (ops) => steps().map((r) => (r.phase === 'DEPENDENCY_LINK' ? { ...r, operations: ops } : r));
const op = (operation, role, over = {}) => ({ operation, nativeId: DEP, role, state: 'SUCCEEDED', ...over });
const GOOD_OPS = [op('KEY', 'TARGET'), op('SOURCE', 'SOURCE'), op('CONNECT', 'TARGET')];

test('依賴子操作：精確 KEY／SOURCE／CONNECT、角色 TARGET／SOURCE／TARGET、nativeId＝父階段 UUID、全部完成才算完成', () => {
  assert.equal(ts.controllerJobDisplay(norm({ steps: withOps(GOOD_OPS) })).complete, true);
  assert.equal(ts.dependencyOperationsComplete(norm({ steps: withOps(GOOD_OPS) }).steps[6]), true);
  const bad = {
    '缺 operations 欄位': steps().map((r) => { const { operations, ...rest } = r; return rest; }),
    'operations 為 null': withOps(null),
    'operations 空陣列': withOps([]),
    '缺 SOURCE': withOps([GOOD_OPS[0], GOOD_OPS[2]]),
    '多一項': withOps([...GOOD_OPS, op('CONNECT', 'TARGET')]),
    '重複 KEY': withOps([GOOD_OPS[0], GOOD_OPS[0], GOOD_OPS[2]]),
    '順序錯': withOps([GOOD_OPS[1], GOOD_OPS[0], GOOD_OPS[2]]),
    'KEY 角色錯': withOps([op('KEY', 'SOURCE'), GOOD_OPS[1], GOOD_OPS[2]]),
    'SOURCE 角色錯': withOps([GOOD_OPS[0], op('SOURCE', 'TARGET'), GOOD_OPS[2]]),
    '角色缺失': withOps([op('KEY', undefined), GOOD_OPS[1], GOOD_OPS[2]]),
    'nativeId 為其他階段': withOps([GOOD_OPS[0], op('SOURCE', 'SOURCE', { nativeId: uuid(8) }), GOOD_OPS[2]]),
    'nativeId 非 UUID': withOps([GOOD_OPS[0], GOOD_OPS[1], op('CONNECT', 'TARGET', { nativeId: 'x' })]),
    'nativeId 缺失': withOps([op('KEY', 'TARGET', { nativeId: undefined }), GOOD_OPS[1], GOOD_OPS[2]]),
    '操作名缺失': withOps([op(undefined, 'TARGET'), GOOD_OPS[1], GOOD_OPS[2]]),
    '未知操作名': withOps([op('KEY2', 'TARGET'), GOOD_OPS[1], GOOD_OPS[2]]),
    '空物件': withOps([{}, GOOD_OPS[1], GOOD_OPS[2]]),
  };
  for (const [name, s] of Object.entries(bad)) {
    const d = ts.controllerJobDisplay(norm({ steps: s }));
    assert.equal(d.complete, false, name);
    assert.equal(d.label, '回報完成 · 未能確認', name);
    assert.ok(d.notes.some((n) => /依賴連線的子操作/.test(n)), name);
  }
  // 父階段 id 本身無效時也不成立
  const badParent = steps().map((r) => (r.phase === 'DEPENDENCY_LINK' ? { ...r, id: 'nope' } : r));
  assert.equal(ts.controllerJobDisplay(norm({ steps: badParent })).complete, false);
  assert.equal(ts.dependencyOperationsComplete(null), false);
});

test('詳情錨點：固定白名單 route→href；CONTROLLER_INSTALL 指向 #/tasks；未知 route 沒有連結', () => {
  assert.deepEqual(ts.TASK_ROUTE_HREF, { servers: '#/servers', deploy: '#/deploy', tasks: '#/tasks' });
  assert.equal(ts.taskDetailHref(ts.taskDetailTarget('CONTROLLER_INSTALL', ID)), '#/tasks');
  for (const t of ['INSTALL', 'UPDATE', 'RECOVER', 'ENROLLMENT']) assert.equal(ts.taskDetailHref(ts.taskDetailTarget(t, ID)), '#/servers', t);
  assert.equal(ts.taskDetailHref(ts.taskDetailTarget('DEPLOYMENT', ID)), '#/deploy');
  for (const route of ['account', 'constructor', '__proto__', 'https://evil.example', undefined]) assert.equal(ts.taskDetailHref({ route, target: 'x', id: ID }), null, String(route));
  assert.equal(ts.taskDetailHref(null), null);
  const ui = read('tasks-ui.js');
  assert.equal((ui.match(/a\.href = href;/g) ?? []).length, 2, '列表與依 UUID 開啟都用白名單 href');
  assert.ok(!/a\.href = target\.route|'#\/servers'|'#\/deploy'/.test(ui), '頁面程式不再自行組 href');
  assert.match(ui, /const href = ts\.taskDetailHref\(target\);\n\s*if \(!target \|\| !href\) return h\('span', \{ class: 'muted small' \}, '無對應詳情'\);/);
});
