import test from 'node:test';
import assert from 'node:assert/strict';
import { LOCALE_KEY, readLocale, saveLocale, localizedValue } from '../locale.js';

test('locale preference accepts only supported values and handles blocked storage', () => {
  const saved = new Map(), storage = { getItem: (key) => saved.get(key), setItem: (key, value) => saved.set(key, value) };
  assert.equal(readLocale(storage), 'zh-Hans');
  assert.equal(saveLocale(storage, 'zh-Hant'), true);
  assert.deepEqual([...saved], [[LOCALE_KEY, 'zh-Hant']]);
  assert.equal(readLocale(storage), 'zh-Hant');
  assert.equal(saveLocale(storage, 'arbitrary'), false);
  saved.set(LOCALE_KEY, 'arbitrary'); assert.equal(readLocale(storage), 'zh-Hans');
  assert.equal(readLocale(undefined), 'zh-Hans'); assert.equal(saveLocale(undefined, 'zh-Hant'), false);
});

test('UI conversion uses phrases and Traditional restores the exact original source', () => {
  const source = '切換主中控 · 等待目前部署完成 · 資料庫 · 操作稽核';
  assert.equal(localizedValue(source, 'zh-Hans'), '切换主中控 · 等待目前部署完成 · 数据库 · 操作审计');
  assert.equal(localizedValue(source, 'zh-Hant'), source);
  assert.equal(localizedValue('generation=12 · OUTCOME_UNKNOWN · 0123456789abcdef', 'zh-Hans'), 'generation=12 · OUTCOME_UNKNOWN · 0123456789abcdef');
});

test('Simplified overrides fix context-specific phrases but leave identifiers alone', () => {
  assert.equal(localizedValue('登出', 'zh-Hans'), '退出登录');
  assert.equal(localizedValue('執行部署 · 執行中', 'zh-Hans'), '执行部署 · 执行中');
  assert.equal(localizedValue('已加入集群 · 單站運行', 'zh-Hans'), '已加入集群 · 单站运行', '運行仍轉成运行');
  assert.equal(localizedValue('單站運行，執行完畢', 'zh-Hans'), '单站运行，执行完毕');
  assert.equal(localizedValue('任務紀錄 · 審計記錄 · 操作稽核', 'zh-Hans'), '任务记录 · 审计记录 · 操作审计');
  assert.equal(localizedValue('請求逾時', 'zh-Hans'), '请求超时');
  assert.equal(localizedValue('登出', 'zh-Hant'), '登出');
  assert.equal(localizedValue('ops_admin_01 · CONTROL_LEASE_NOT_HELD', 'zh-Hans'), 'ops_admin_01 · CONTROL_LEASE_NOT_HELD');
});
