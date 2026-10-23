// 撤銷尚未派發的排隊任務（controller-jobs/{id}/cancel）：能力只認明確布林、已撤銷事實需全部一致、API 本文、頁面約束。

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
const HASH = 'd'.repeat(64);
const uuid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const job = (over = {}) => ts.normalizeControllerJob({ id: ID, kind: 'CONTROLLER_INSTALL', state: 'QUEUED', requestHash: HASH, requiresReapproval: false, requiresReconciliation: false,
  steps: ts.CONTROLLER_PHASES.map((phase, i) => ({ phase, id: uuid(i + 1), state: 'PENDING' })), canCancelBeforeDispatch: true, cancelledBeforeDispatch: false, ...over }, ID);

test('可撤銷：只在 canCancelBeforeDispatch 嚴格 true 且有原 requestHash', () => {
  assert.equal(ts.controllerJobCancellable(job()), true);
  for (const over of [{ canCancelBeforeDispatch: undefined }, { canCancelBeforeDispatch: false }, { canCancelBeforeDispatch: 'true' }, { canCancelBeforeDispatch: 1 }, { requestHash: undefined }, { requestHash: 'XYZ' }]) {
    assert.equal(ts.controllerJobCancellable(job(over)), false, JSON.stringify(over));
  }
  assert.equal(job({ canCancelBeforeDispatch: 'yes' }).canCancelBeforeDispatch, null, '非布林為未知');
});

test('已撤銷（未派發）：FAILED＋代碼＋cancelledBeforeDispatch＝true＋effectsStarted＝false 全部一致才成立', () => {
  const cancelled = job({ state: 'FAILED', code: 'BOOTSTRAP_CANCELLED_BEFORE_DISPATCH', cancelledBeforeDispatch: true, effectsStarted: false, canCancelBeforeDispatch: false });
  assert.equal(ts.controllerJobCancelled(cancelled), true);
  const d = ts.controllerJobDisplay(cancelled);
  assert.deepEqual([d.label, d.complete, d.cancelled], ['已撤銷（未派發）', false, true]);
  assert.equal(ts.controllerJobCancellable(cancelled), false);
  for (const over of [{ cancelledBeforeDispatch: undefined }, { cancelledBeforeDispatch: false }, { effectsStarted: true }, { effectsStarted: undefined }, { state: 'SUCCEEDED' }, { code: 'BOOTSTRAP_PHASE_FAILED_PRESERVED' }]) {
    const j = job({ state: 'FAILED', code: 'BOOTSTRAP_CANCELLED_BEFORE_DISPATCH', cancelledBeforeDispatch: true, effectsStarted: false, ...over });
    assert.equal(ts.controllerJobCancelled(j), false, JSON.stringify(over));
    assert.notEqual(ts.controllerJobDisplay(j).label, '已撤銷（未派發）', JSON.stringify(over));
  }
  const inconsistent = ts.controllerJobDisplay(job({ state: 'FAILED', code: 'BOOTSTRAP_CANCELLED_BEFORE_DISPATCH', cancelledBeforeDispatch: true, effectsStarted: true }));
  assert.ok(inconsistent.notes.some((n) => /不一致/.test(n)));
  // 結果未知仍優先：不因撤銷代碼被說成已撤銷
  assert.equal(ts.controllerJobDisplay(job({ state: 'OUTCOME_UNKNOWN', requiresReconciliation: true, code: 'BOOTSTRAP_CANCELLED_BEFORE_DISPATCH', cancelledBeforeDispatch: true, effectsStarted: false })).label, '結果未知 · 需核對');
  assert.match(ts.controllerCodeText('BOOTSTRAP_DISPATCH_ALREADY_CLAIMED'), /不能再撤銷/);
  assert.match(ts.controllerCodeText('BOOTSTRAP_CANCELLED_BEFORE_DISPATCH'), /不是回滾/);
});

test('API：POST /controller-jobs/{id}/cancel 只送 requestHash；其他本文拒絕', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.cancelControllerJob(ID, { requestHash: HASH });
  assert.ok(fetchImpl.calls[0].url.endsWith(`/controller-jobs/${ID}/cancel`));
  assert.equal(fetchImpl.calls[0].init.method, 'POST');
  assert.deepEqual(fetchImpl.calls[0].body, { requestHash: HASH });
  for (const [id, body] of [[ID, {}], [ID, { requestHash: 'A'.repeat(64) }], [ID, { requestHash: HASH, force: true }], ['../x', { requestHash: HASH }], [ID, null]]) {
    await assert.rejects(client.cancelControllerJob(id, body), (e) => e.kind === 'validation', JSON.stringify(body));
  }
  assert.equal(fetchImpl.calls.length, 1);
});

test('頁面靜態：只依能力顯示；確認後重讀並比對 hash；競態拒絕與結果未知只重新讀取不重送；不受 canManage 單獨停用', () => {
  const ui = read('tasks-ui.js');
  const code = ui.replace(/\/\/.*$/gm, '');
  const fn = code.slice(code.indexOf('async function cancelControllerJob'), code.indexOf('function ctlErrorText'));
  const order = ['confirmDialog(', 'client.getControllerJob(id)', 'controllerJobCancellable(fresh)', 'fresh.requestHash !== original.requestHash', 'client.cancelControllerJob(id, { requestHash: fresh.requestHash })'].map((k) => fn.indexOf(k));
  assert.ok(order.every((i, n) => i > 0 && (n === 0 || i > order[n - 1])), `撤銷順序 ${order}`);
  assert.equal((code.match(/client\.cancelControllerJob\(/g) ?? []).length, 1);
  const catchPart = fn.slice(fn.indexOf('} catch (err) {'));
  assert.ok(!catchPart.includes('cancelControllerJob('), '失敗不重送');
  assert.match(catchPart, /BOOTSTRAP_DISPATCH_ALREADY_CLAIMED'\) \{\s*openControllerJob\(id/);
  assert.match(catchPart, /if \(sent && \(err\?\.outcomeUnknown \|\| err\?\.status >= 500\)\) \{\s*openControllerJob\(id/);
  assert.ok(!/canManage|gateReasons|leadership/i.test(fn), '不因 canManage／交接單獨停用');
  assert.match(code, /ts\.controllerJobCancellable\(j\) && !ctl\.loading \? h\('div'/);
  assert.match(ui, /只撤銷尚未派發的排隊；不刪除伺服器、資料或任何設定，也不是回滾。/);
  assert.match(ui, /leave\(\) \{[\s\S]*?if \(ctl\?\.cancelling\) ctl = \{ \.\.\.ctl, cancelling: false, notice:/);
  assert.match(read('app.js'), /const tasks = mountTasks\(\{[\s\S]*?confirmDialog,/);
  assert.ok(!/reapprove|renew|重新批准…|續期/.test(code.slice(code.indexOf('function renderController'))), '沒有重新批准或續期按鈕');
});
