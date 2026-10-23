import test from 'node:test';
import assert from 'node:assert/strict';

import { isUuid, parseSeconds, validateFaultRequest, validatePlanRequest } from '../validate.js';
import { createSession } from '../session.js';
import { describeError, formatAge, formatBytes, label } from '../format.js';
import { ApiError } from '../api.js';

test('parseSeconds：只接受十進位整數', () => {
  assert.equal(parseSeconds('30'), 30);
  assert.equal(parseSeconds(' 30 '), 30);
  assert.equal(parseSeconds(30), 30);
  for (const bad of ['', '1.5', '1e2', '-1', '0x10', 'abc', null, undefined, 1.5, '12345']) {
    assert.ok(Number.isNaN(parseSeconds(bad)), `應拒絕 ${String(bad)}`);
  }
});

test('validateFaultRequest：邊界 1 與 300', () => {
  assert.equal(validateFaultRequest({ site: 'campus', channel: 'events', action: 'BLOCK', seconds: '1' }).ok, true);
  assert.equal(validateFaultRequest({ site: 'campus', channel: 'events', action: 'BLOCK', seconds: '300' }).ok, true);
  assert.equal(validateFaultRequest({ site: 'campus', channel: 'events', action: 'BLOCK', seconds: '0' }).ok, false);
  assert.equal(validateFaultRequest({ site: 'campus', channel: 'events', action: 'BLOCK', seconds: '301' }).ok, false);
  const r = validateFaultRequest({ site: 'campus', channel: 'files', action: 'BLOCK', seconds: 5 }, { knownChannels: ['events'] });
  assert.equal(r.ok, false);
});

test('validatePlanRequest：只接受登記的 releaseId 與固定站點', () => {
  assert.equal(validatePlanRequest({ releaseId: 'candidate-1', sites: ['campus'] }, { knownReleaseIds: ['candidate-1'] }).ok, true);
  assert.equal(validatePlanRequest({ releaseId: 'candidate-2', sites: ['campus'] }, { knownReleaseIds: ['candidate-1'] }).ok, false);
  assert.equal(validatePlanRequest({ releaseId: 'candidate-1', sites: ['mars'] }).ok, false);
});

test('isUuid', () => {
  assert.equal(isUuid('44444444-4444-4444-8444-444444444444'), true);
  assert.equal(isUuid('44444444-4444-4444-8444-44444444444'), false);
  assert.equal(isUuid('../44444444-4444-4444-8444-444444444444'), false);
});

test('session：權杖只在記憶體，序列化不洩漏，clear 後失效', () => {
  const s = createSession();
  assert.equal(s.isActive(), false);
  assert.equal(s.authHeader(), null);
  s.set('satoken', 'secret-token-value');
  assert.deepEqual(s.authHeader(), { name: 'satoken', value: 'Bearer secret-token-value' });
  assert.ok(!JSON.stringify(s).includes('secret-token-value'));
  assert.ok(!JSON.stringify({ s }).includes('secret'));
  assert.ok(Object.isFrozen(s));
  s.clear();
  assert.equal(s.isActive(), false);
  assert.equal(s.authHeader(), null);
  assert.throws(() => s.set('bad name', 'x'));
  assert.throws(() => s.set('satoken', ''));
});

test('session：已帶 Bearer 前綴的 tokenValue 不重複', () => {
  const s = createSession();
  s.set('Authorization', 'Bearer abc');
  assert.equal(s.authHeader().value, 'Bearer abc');
});

test('label：未知代碼原樣顯示，缺值明示', () => {
  assert.equal(label('runState', 'COMMAND_SUCCEEDED'), '指令退出碼 0（非遠端驗收） · COMMAND_SUCCEEDED');
  assert.equal(label('runState', 'NEW_STATE'), 'NEW_STATE');
  assert.equal(label('check', undefined), '（未提供）');
  assert.ok(!label('runState', 'COMMAND_SUCCEEDED').includes('通過'));
});

test('formatAge / formatBytes', () => {
  assert.equal(formatAge(4000), '4 秒前');
  assert.equal(formatAge(125000), '2 分鐘前');
  assert.equal(formatAge(-10000), '晚於本機時鐘');
  assert.equal(formatAge(NaN), '時間未知');
  assert.equal(formatBytes(512), '512 B');
  assert.equal(formatBytes(2048), '2.0 KiB（2048 B）');
  assert.equal(formatBytes(null), '—');
});

test('describeError：依類型與狀態碼產生中文說明', () => {
  assert.match(describeError(new ApiError({ kind: 'network' })), /無法連線/);
  assert.match(describeError(new ApiError({ kind: 'timeout' })), /逾時/);
  assert.match(describeError(new ApiError({ kind: 'http', status: 423, code: 'DEPLOYMENT_LOCKED' })), /部署執行已鎖定.*HTTP 423/);
  assert.match(describeError(new ApiError({ kind: 'http', status: 503, code: 'NODE_REQUEST_FAILED' })), /結果未知/);
  assert.match(describeError(new ApiError({ kind: 'http', status: 409 })), /目前狀態不允許/);
  assert.match(describeError(new ApiError({ kind: 'business', code: '1', serverMessage: '密碼錯誤' })), /登入失敗：密碼錯誤/);
});
