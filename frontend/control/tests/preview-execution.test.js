// 建立部署預覽的確認文案：這次操作不部署；執行能力只依正式 capabilities／預覽回應，未知或未啟用不宣稱可執行。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { executionCapabilityView, executionReasons, planExecutionStat, previewCreateExecutionLine, previewResultExecutionLine } from '../deployment-state.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const ID = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const plan = (over = {}) => ({ id: ID, state: 'PREVIEW_ONLY', executionEnabled: true, artifactSha256: 'a'.repeat(64), blockers: [], ...over });

test('確認框：enabled 說可另行確認執行，但這一步不部署', () => {
  const t = previewCreateExecutionLine({ deploymentExecutionEnabled: true });
  assert.match(t, /^這一步只建立 PREVIEW_ONLY 預覽，不會部署。/);
  assert.match(t, /已啟用部署執行器/);
  assert.match(t, /下一個確認框另行確認才會部署/);
  assert.ok(!/423|不能執行|未知/.test(t));
});

test('確認框：disabled 說不能執行；不再提固定 423', () => {
  const t = previewCreateExecutionLine({ deploymentExecutionEnabled: false });
  assert.match(t, /不會部署。服務端未啟用部署執行器，這份預覽不能執行部署。/);
  assert.ok(!/423|已啟用|下一個確認框/.test(t));
});

test('確認框：缺少、異常值或讀取失敗皆為未知，不宣稱可執行', () => {
  const unknown = [undefined, null, {}, { deploymentExecutionEnabled: 'true' }, { deploymentExecutionEnabled: 1 }, { deploymentExecutionEnabled: null }];
  for (const caps of unknown) {
    const t = previewCreateExecutionLine(caps);
    assert.match(t, /不會部署。部署執行能力未知/, JSON.stringify(caps));
    assert.ok(!/已啟用|另行確認/.test(t));
  }
  const failed = previewCreateExecutionLine({ deploymentExecutionEnabled: true }, { error: { status: 500 } });
  assert.match(failed, /未知/, '讀取失敗時不沿用舊的 true');
});

test('預覽回應：capabilities 啟用且回應可執行、無阻擋才說可執行；仍未部署', () => {
  assert.equal(previewResultExecutionLine({ deploymentExecutionEnabled: true }, plan()), '服務端回報這份預覽可執行；尚未部署，需在下一個確認框另行確認。');
  assert.match(previewResultExecutionLine({ deploymentExecutionEnabled: false }, plan()), /^目前不能執行：服務端尚未啟用部署執行器。/);
  assert.match(previewResultExecutionLine({}, plan()), /^目前不能執行：服務端尚未啟用部署執行器。/, '未知能力失敗即關閉');
  assert.match(previewResultExecutionLine({ deploymentExecutionEnabled: true }, plan(), { error: new Error('x') }), /^目前不能執行/);
  assert.match(previewResultExecutionLine({ deploymentExecutionEnabled: true }, plan({ executionEnabled: false })), /尚未滿足部署條件/);
  assert.match(previewResultExecutionLine({ deploymentExecutionEnabled: true }, plan({ executionEnabled: 'true' })), /尚未滿足部署條件/);
  assert.match(previewResultExecutionLine({ deploymentExecutionEnabled: true }, plan({ blockers: ['SITE_OFFLINE'] })), /阻擋項：SITE_OFFLINE/);
  assert.match(previewResultExecutionLine({ deploymentExecutionEnabled: true }, plan({ blockers: undefined })), /未提供阻擋項/);
  assert.match(previewResultExecutionLine({ deploymentExecutionEnabled: false }, plan({ executionEnabled: false })), /（另有 1 項）/);
  assert.match(previewResultExecutionLine({ deploymentExecutionEnabled: true }, null), /請先建立部署計畫/);
});

test('靜態：建立預覽確認框與成功訊息改用上述函式，舊的固定文案移除', () => {
  const app = readFileSync(join(root, 'cluster-app.js'), 'utf8');
  assert.ok(!app.includes('執行介面固定回 423'));
  assert.match(app, /ds\.previewCreateExecutionLine\(store\.capabilities\.data, \{ error: store\.capabilities\.error \}\)/);
  assert.match(app, /ds\.previewResultExecutionLine\(store\.capabilities\.data, store\.preview, \{ error: store\.capabilities\.error \}\)/);
});

test('能力區塊：true 已啟用（仍需預覽與確認）、false 未啟用、缺失／非布林為未知；不再有永久鎖定說法', () => {
  const on = executionCapabilityView(true);
  assert.deepEqual([on.state, on.tone, on.badge], ['ENABLED', 'teal', '部署執行器已啟用']);
  assert.match(on.detail, /^true · 已啟用；每次部署仍需預覽無阻擋並在確認框另行確認$/);
  const off = executionCapabilityView(false);
  assert.deepEqual([off.state, off.tone, off.badge], ['DISABLED', 'outline', '部署執行器未啟用']);
  assert.match(off.detail, /預覽不能執行部署/);
  for (const [v, shown] of [[undefined, '未提供'], [null, 'null'], ['true', 'true'], [1, '1'], [{}, '{}']]) {
    const u = executionCapabilityView(v);
    assert.deepEqual([u.state, u.tone, u.badge], ['UNKNOWN', 'amber', '部署執行能力未知'], String(v));
    assert.equal(u.detail, `${shown} · 未知或異常，不視為可執行`);
  }
  for (const v of [true, false, undefined, 'true']) assert.ok(!/永久鎖定|前端仍鎖定|423/.test(executionCapabilityView(v).detail));
  // 顯示與實際閘門一致：只有嚴格 true 不會產生「未啟用」原因
  assert.ok(!executionReasons({ deploymentExecutionEnabled: true }, null).some((r) => /執行器/.test(r)));
  for (const v of [false, undefined, 'true']) assert.ok(executionReasons({ deploymentExecutionEnabled: v }, null).some((r) => /執行器/.test(r)));
});

test('預覽 executionEnabled：true 不再標紅為異常；false 中性；其他為未知', () => {
  assert.deepEqual(planExecutionStat(true), { text: 'true', tone: 'teal' });
  assert.deepEqual(planExecutionStat(false), { text: 'false', tone: undefined });
  assert.deepEqual(planExecutionStat(undefined), { text: '未提供', tone: 'amber' });
  assert.deepEqual(planExecutionStat('true'), { text: 'true', tone: 'amber' });
});

test('靜態：叢集與舊版能力面板、預覽統計改用共用顯示；移除過時鎖定文案', () => {
  const cr = readFileSync(join(root, 'cluster-render.js'), 'utf8');
  const r = readFileSync(join(root, 'render.js'), 'utf8');
  for (const src of [cr, r]) {
    assert.ok(!src.includes('前端永久鎖定') && !src.includes('前端仍鎖定'));
    assert.match(src, /executionCapabilityView\((caps|data)\.deploymentExecutionEnabled\)\.detail/);
    assert.match(src, /executionCapabilityView\((caps|data)\.deploymentExecutionEnabled\)\.badge/);
    assert.match(src, /planExecutionStat\((pv|plan)\.executionEnabled\)\.text/);
  }
  // 節點觀測欄位由後端固定回報 false（節點本身不執行部署），維持原顯示。
  assert.match(r, /節點部署執行/);
});
