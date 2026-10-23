// 交接提交回應與正式 leadership.handoff 的合併：同一 UUID 的較新終態才同步；不同 UUID、過期、失敗、無法取得不假報完成。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { handoffResultView } from '../handoff-state.js';
import { UUID_A, UUID_B } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const waiting = { id: UUID_A, state: 'WAITING' };
const lead = (handoff) => ({ state: 'HELD', localLeader: false, leaderControllerId: UUID_B, generation: 8, handoffEnabled: true, handoff });

test('同一 UUID、最新觀測為 COMPLETED：下方回應同步為完成，不再說 WAITING 或持續更新', () => {
  const v = handoffResultView(waiting, lead({ id: UUID_A, state: 'COMPLETED', reason: 'TARGET_ACKNOWLEDGED' }), true);
  assert.deepEqual([v.state, v.synced, v.terminal], ['COMPLETED', true, true]);
  assert.equal(v.text, `交接 ${UUID_A} · COMPLETED（已依最新領導狀態更新：指定中控已接管）。`);
  assert.ok(!/WAITING|持續更新/.test(v.text));
});

test('同一 UUID 的 TRANSFERRING 前進但仍在進行；FAILED／CANCELLED 照實，不說成完成', () => {
  const t = handoffResultView(waiting, lead({ id: UUID_A, state: 'TRANSFERRING', reason: 'WAITING_FOR_TARGET_ACK' }), true);
  assert.equal(t.state, 'TRANSFERRING');
  assert.match(t.text, /持續更新/);
  const f = handoffResultView(waiting, lead({ id: UUID_A, state: 'FAILED', reason: 'TARGET_TAKEOVER_NOT_CONFIRMED' }), true);
  assert.equal(f.state, 'FAILED');
  assert.match(f.text, /FAILED（已依最新領導狀態更新：指定中控未完成接管）/);
  assert.ok(!/COMPLETED|已接管。/.test(f.text));
  assert.equal(handoffResultView(waiting, lead({ id: UUID_A, state: 'CANCELLED', reason: 'ADMIN_CANCELLED' }), true).state, 'CANCELLED');
});

test('不同 UUID、觀測過期、無法取得、未知或倒退狀態：保留原回應，不假報完成', () => {
  const keep = (raw, fresh) => {
    const v = handoffResultView(waiting, raw, fresh);
    assert.deepEqual([v.state, v.synced], ['WAITING', false], JSON.stringify({ raw, fresh }));
    assert.equal(v.text, `交接 ${UUID_A} · WAITING。狀態會持續更新。`);
  };
  keep(lead({ id: UUID_B, state: 'COMPLETED', reason: 'TARGET_ACKNOWLEDGED' }), true);
  keep(lead({ id: UUID_A, state: 'COMPLETED' }), false);
  keep(lead({ id: UUID_A, state: 'COMPLETED' }), undefined);
  keep(null, true);
  keep(undefined, false);
  keep(lead(undefined), true);
  keep(lead({ state: 'COMPLETED' }), true);
  keep(lead({ id: UUID_A, state: 'FUTURE_DONE' }), true);
  keep(lead({ id: UUID_A, state: 'NONE' }), true);
  keep(lead({ id: UUID_A, state: 'WAITING' }), true);
  // 已是終態的提交（取消回應 CANCELLED）不會被較舊的 WAITING 倒退
  const c = handoffResultView({ id: UUID_A, state: 'CANCELLED' }, lead({ id: UUID_A, state: 'WAITING' }), true);
  assert.deepEqual([c.state, c.synced, c.terminal], ['CANCELLED', false, true]);
  // TRANSFERRING 回應不被同等級或較低觀測覆寫；也不會把 COMPLETED 換成 FAILED 以外的推測
  assert.equal(handoffResultView({ id: UUID_A, state: 'COMPLETED' }, lead({ id: UUID_A, state: 'FAILED' }), true).state, 'COMPLETED');
});

test('沒有有效 UUID 的回應不建立可同步的檢視', () => {
  assert.equal(handoffResultView(null, lead({ id: UUID_A, state: 'COMPLETED' }), true), null);
  assert.equal(handoffResultView({ id: 'bad', state: 'WAITING' }, lead({ id: 'bad', state: 'COMPLETED' }), true), null);
});

test('靜態：提交與查回都以結構保存並在每次重畫合併；登出清除', () => {
  const ui = readFileSync(join(root, 'handoff-ui.js'), 'utf8');
  assert.ok(!ui.includes('狀態會持續更新'), '文字只由 handoffResultView 產生');
  assert.match(ui, /intent = null;showSubmitted\(response\);/);
  assert.match(ui, /intent = null;showSubmitted\(saved\);return;/);
  const render = ui.slice(ui.indexOf('function render()'), ui.indexOf('async function fresh()'));
  assert.match(render, /syncSubmitted\(\);/);
  assert.match(ui, /function message\(text\) \{ submitted = null;/);
  assert.match(ui, /reset\(\) \{[^}]*message\(''\)/);
});
