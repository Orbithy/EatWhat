// 接入任務被接受後的重啟說明：只依正式回應 controllerRestartExpected 的嚴格 true／false；缺失不承諾；結果未知只以原 UUID 查詢、不重送。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as ss from '../servers-state.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const SID = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const job = (over = {}) => ss.normalizeClusterJob({ id: SID, kind: 'REMOTE_MEMBER', state: 'QUEUED', stage: 'MEMBER_CONFIGURATION', ...over });

test('任務正規化：controllerRestartExpected 保留 true／false，缺失或非布林為 null', () => {
  assert.equal(job({ controllerRestartExpected: true }).controllerRestartExpected, true);
  assert.equal(job({ controllerRestartExpected: false }).controllerRestartExpected, false);
  for (const v of [undefined, null, 'true', 'false', 1, 0]) assert.equal(job({ controllerRestartExpected: v }).controllerRestartExpected, null, String(v));
});

test('true：才說中控預期重啟；仍不重送', () => {
  const t = ss.clusterAcceptedText(job({ controllerRestartExpected: true, kind: 'INITIAL_AUTHORITY' }));
  assert.match(t, /中控預期會短暫重啟/);
  assert.match(t, new RegExp(SID));
  assert.match(t, /原任務 UUID/);
  assert.match(t, /不要重新送出/);
});

test('false：任務已接受、持續查詢原 UUID、中控不重啟', () => {
  const t = ss.clusterAcceptedText(job({ controllerRestartExpected: false }));
  assert.match(t, /後端已接受；中控不重啟。/);
  assert.match(t, /持續以原任務 UUID 唯讀查詢進度/);
  assert.match(t, new RegExp(SID));
  assert.ok(!/預期會短暫重啟|即將短暫重啟|重新連線/.test(t));
});

test('缺失或非布林：不承諾重啟行為', () => {
  for (const v of [undefined, 'true', 1]) {
    const t = ss.clusterAcceptedText(job({ controllerRestartExpected: v }));
    assert.match(t, /回應未說明中控是否重啟/, String(v));
    assert.match(t, /原任務 UUID/);
    assert.ok(!/中控預期會短暫重啟|中控不重啟|即將短暫重啟/.test(t), String(v));
  }
});

test('結果未知：計畫明確不重啟時不提重啟；其他保守說明；都只用原計畫 UUID', () => {
  const remote = ss.normalizeClusterPlan({ id: SID, kind: 'REMOTE_MEMBER', serverId: SID, serverRevision: 1, state: 'READY', blockers: [], controllerRestartExpected: false });
  assert.equal(ss.clusterUnknownSubmitText(remote), '送出結果未確認。不會重新送出；正以原計畫 UUID 唯讀讀取任務。');
  const sparse = ss.normalizeClusterPlan({ id: SID, kind: 'REMOTE_MEMBER', serverId: SID, serverRevision: 1, state: 'READY', blockers: [] });
  assert.match(ss.clusterUnknownSubmitText(sparse), /中控可能正在重新啟動[\s\S]*不會重新送出；正以原計畫 UUID/);
});

test('靜態：接受訊息與未知訊息改用共用函式；未知分支不重送 execute；舊固定文案移除', () => {
  const ui = readFileSync(join(root, 'servers-ui.js'), 'utf8');
  assert.ok(!ui.includes('中控即將短暫重啟'));
  const exec = ui.slice(ui.indexOf('async function executeCluster'), ui.indexOf('function stopClusterPoll'));
  assert.match(exec, /setClusterJob\(j, \{ announce: false \}\);\n\s*message\('teal', ss\.clusterAcceptedText\(j\)\);/);
  const unknown = exec.slice(exec.indexOf("f.outcome === 'UNKNOWN'"));
  assert.match(unknown, /message\('amber', ss\.clusterUnknownSubmitText\(captured\)\);\n\s*startClusterWatch\(captured\.id\);/);
  assert.ok(!exec.slice(exec.indexOf('} catch (err) {')).includes('executeClusterPlan('), '未知結果不重送');
  assert.match(ui, /ss\.jobActive\(j\) \? h\('p', \{ class: 'hint' \}, j\.controllerRestartExpected === true/);
});
