// 健康結果的成功碼 OK：只在有效狀態本身成功時說成功；不能蓋過 FAIL／UNKNOWN／過期；未知碼仍如實呈現。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { healthCodeText, healthModel, resultCodeText } from '../health-state.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const NOW = Date.parse('2026-10-17T12:00:00Z');
const check = (status, code, validUntil = '2026-10-17T12:00:40Z') => ({ status, code, checkedAt: '2026-10-17T11:59:40Z', validUntil });
const model = (application, opts = {}) => healthModel({ healthChecks: { application } }, { now: NOW, current: true, ...opts }).checks.find((c) => c.key === 'application');

test('有效 PASS＋OK 顯示成功說明，不再是未定義的錯誤碼', () => {
  const c = model(check('PASS', 'OK'));
  assert.equal(c.status, 'PASS');
  assert.equal(resultCodeText(c.code, c.status), '檢測成功。');
});

test('OK 不能蓋過狀態：過期／觀測失效的 PASS 為舊結果；FAIL／UNKNOWN／CHECKING 帶 OK 標示不一致', () => {
  const expired = model(check('PASS', 'OK', '2026-10-17T11:59:59Z'));
  assert.equal(expired.status, 'STALE');
  assert.match(resultCodeText(expired.code, expired.status), /已過期.*不代表目前健康/);
  const notCurrent = model(check('PASS', 'OK'), { current: false });
  assert.equal(notCurrent.status, 'STALE');
  assert.doesNotMatch(resultCodeText(notCurrent.code, notCurrent.status), /^檢測成功/);
  const noUntil = model(check('PASS', 'OK', null));
  assert.equal(noUntil.status, 'STALE');
  for (const status of ['FAIL', 'UNKNOWN', 'CHECKING']) {
    const c = model(check(status, 'OK'));
    assert.equal(c.status, status, '狀態不被 OK 改寫');
    assert.equal(resultCodeText(c.code, c.status), `回報代碼 OK 與狀態 ${status} 不一致；以狀態為準。`);
  }
  const weird = model(check('BOGUS', 'OK'));
  assert.equal(weird.status, 'UNKNOWN');
  assert.match(resultCodeText(weird.code, weird.status), /不一致/);
});

test('讀寫驗證：PASSED＋OK 為成功；其他狀態帶 OK 為不一致', () => {
  const w = (state) => healthModel({ storageWriteCheck: { state, code: 'OK' } }, { now: NOW }).write;
  assert.equal(resultCodeText(w('PASSED').code, w('PASSED').state), '讀寫驗證成功。');
  assert.match(resultCodeText(w('FAILED').code, w('FAILED').state), /不一致/);
  assert.match(resultCodeText(w('SOMETHING').code, w('SOMETHING').state), /UNKNOWN 不一致/);
});

test('非 OK 代碼照原表；未知碼仍顯示「未定義的錯誤碼」；OK 不加入通用錯誤碼表', () => {
  assert.equal(resultCodeText('CHECK_TIMEOUT', 'UNKNOWN'), healthCodeText('CHECK_TIMEOUT'));
  assert.equal(resultCodeText('NEW_FUTURE_CODE', 'PASS'), '未定義的錯誤碼');
  assert.equal(resultCodeText('ok', 'PASS'), '未定義的錯誤碼', '大小寫不同不視為 OK');
  assert.equal(resultCodeText(null, 'PASS'), null);
  assert.equal(healthCodeText('OK'), null);
});

test('靜態：結果列以有效狀態傳入代碼說明', () => {
  const r = readFileSync(join(root, 'cluster-render.js'), 'utf8');
  assert.match(r, /codeLine\(c\.code, c\.status\)/);
  assert.match(r, /codeLine\(w\.code, w\.state\)/);
  assert.ok(!r.includes("healthCodeText(code) ?? '未定義的錯誤碼'"));
});
