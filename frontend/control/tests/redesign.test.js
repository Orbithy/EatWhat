// 改版後新增模組：路由、帳號修改（驗證、錯誤分類、成功判定、UI 流程）、多站總覽模型與靜態結構。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { DEFAULT_ROUTE, ROUTES, parseRoute, routeSection } from '../router.js';
import {
  ACCOUNT_ERROR_TEXT, ACCOUNT_READONLY_TEXT, accountFailure, readonlyReasonText, confirmedCredentialResult, credentialChange, normalizeAccount, passwordProblem, usernameProblem,
} from '../account-state.js';
import { ApiError, createControlClient } from '../api.js';
import { homeModel, jobSummary } from '../home-state.js';
import { mountAccount } from '../account-ui.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');

// ---------- 路由 ----------

test('parseRoute：只認 #/ 開頭；未知路由回總覽；advanced 預設 GC', () => {
  assert.equal(parseRoute(''), null);
  assert.equal(parseRoute('#main'), null);
  assert.equal(parseRoute('#/'), DEFAULT_ROUTE);
  assert.equal(parseRoute('#/nope'), DEFAULT_ROUTE);
  assert.equal(parseRoute('#/advanced'), 'advanced/gc');
  assert.equal(parseRoute('#/advanced/tasks/'), 'advanced/tasks');
  for (const r of ROUTES) assert.equal(parseRoute(`#/${r}`), r);
  assert.equal(routeSection('advanced/fault'), 'advanced');
  assert.equal(routeSection('deploy'), 'deploy');
});

test('index.html：每個路由在兩種模式都有對應頁；側欄連結只指向固定路由；子選單預設收合', () => {
  const html = read('index.html');
  const pages = [...html.matchAll(/data-page="([^"]+)"/g)].map((m) => m[1]);
  // 帳號與伺服器頁兩種模式共用一頁；其餘每個路由在多站與雙站模式各有一頁。
  const shared = ['account', 'servers', 'tasks'];
  for (const r of ROUTES.filter((r) => !shared.includes(r))) assert.equal(pages.filter((p) => p === r).length, 2, r);
  for (const r of shared) assert.equal(pages.filter((p) => p === r).length, 1, r);
  for (const [, href] of html.matchAll(/<a[^>]*\shref="([^"]+)"/g)) {
    if (href === '#main') continue;
    assert.ok(ROUTES.includes(href.replace(/^#\//, '')), href);
  }
  for (const m of html.matchAll(/<[^>]*data-route-sub="advanced"[^>]*>/g)) assert.match(m[0], /\shidden[\s>]/);
  for (const m of html.matchAll(/<section[^>]*data-page="[^"]+"[^>]*>/g)) assert.match(m[0], /\shidden[\s>]/, m[0]);
  const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]);
  assert.equal(new Set(ids).size, ids.length, '不可有重複 id');
  for (const id of ['sidebar', 'appbar', 'nav-toggle', 'nav-close', 'nav-scrim', 'page-title', 'nav-issues', 'me-name', 'logout-button', 'locale-select',
    'account-view', 'account-form', 'account-current-password', 'account-new-username', 'account-new-password', 'account-confirm-password',
    'account-submit', 'account-result', 'account-problems', 'account-status', 'account-current-username', 'cl-epoch', 'cl-cluster-id']) {
    assert.ok(ids.includes(id), id);
  }
});

test('正式頁面不載入原型示範資料，也不含設計說明與 API 除錯文案', () => {
  const html = read('index.html');
  for (const f of ['index.html', 'app.js', 'cluster-app.js', 'home-render.js', 'home-state.js', 'account-ui.js']) {
    assert.ok(!/demo-data/.test(read(f)), f);
  }
  assert.ok(!/先看狀態與下一步/.test(html + read('home-render.js')));
  assert.ok(!/將送出欄位/.test(html + read('account-ui.js')));
  assert.match(html, /重新登入/, '保留成功後需重新登入的提示');
});

test('帳號表單：密碼欄位不自動填入舊值，autocomplete 標示正確', () => {
  const html = read('index.html');
  const tag = (id) => html.match(new RegExp(`<input[^>]*id="${id}"[^>]*>`))?.[0] ?? '';
  assert.match(tag('account-current-password'), /type="password"[^>]*|autocomplete="current-password"/);
  assert.match(tag('account-current-password'), /autocomplete="current-password"/);
  assert.match(tag('account-new-password'), /autocomplete="new-password"/);
  assert.match(tag('account-confirm-password'), /autocomplete="new-password"/);
  assert.match(tag('account-new-username'), /autocomplete="off"|autocomplete="username"/);
});

// ---------- 帳號驗證 ----------

test('使用者名稱：3–32 個 ASCII 英數底線，不可為 11 位純數字', () => {
  assert.equal(usernameProblem('ops_admin'), null);
  assert.equal(usernameProblem('abc'), null);
  assert.equal(usernameProblem('a'.repeat(32)), null);
  assert.equal(usernameProblem('1380013800'), null);
  assert.ok(usernameProblem('ab'));
  assert.ok(usernameProblem('a'.repeat(33)));
  assert.ok(usernameProblem('bad name'));
  assert.ok(usernameProblem('名稱abc'));
  assert.ok(usernameProblem('ops-admin'));
  assert.match(usernameProblem('13800138000'), /11/);
});

test('密碼：12–64 個 Unicode code point、UTF-8 ≤72 bytes、不可全空白、不 trim', () => {
  assert.equal(passwordProblem('a'.repeat(12)), null);
  assert.equal(passwordProblem('a'.repeat(64)), null);
  assert.ok(passwordProblem('a'.repeat(11)));
  assert.ok(passwordProblem('a'.repeat(65)));
  assert.equal(passwordProblem('  spaced pass  '), null, '前後空白保留且允許');
  assert.match(passwordProblem(' '.repeat(12)), /空白/);
  assert.match(passwordProblem('　'.repeat(12)), /空白|72/);
  // 以 code point 計數：12 個 emoji（UTF-16 長度 24，UTF-8 48 bytes）合法。
  const emoji = '😀'.repeat(12);
  assert.equal(emoji.length, 24);
  assert.equal(passwordProblem(emoji), null);
  // 11 個 emoji 的 UTF-16 長度為 22，但 code point 只有 11：不足。
  assert.ok(passwordProblem('😀'.repeat(11)));
  // 24 個中文 = 72 bytes 合法；25 個 = 75 bytes 超出。
  assert.equal(passwordProblem('密'.repeat(24)), null);
  assert.match(passwordProblem('密'.repeat(25)), /72/);
  // 18 個 emoji = 72 bytes 合法；19 個 = 76 bytes 超出（雖然 code point 只有 19）。
  assert.equal(passwordProblem('😀'.repeat(18)), null);
  assert.match(passwordProblem('😀'.repeat(19)), /72/);
});

test('credentialChange：只送出有變更的欄位，不送確認密碼', () => {
  const base = { currentUsername: 'ops_admin', currentPassword: 'Old-Password-1' };
  assert.deepEqual(credentialChange({ ...base, newUsername: 'ops_admin_2' }).body, { currentPassword: 'Old-Password-1', newUsername: 'ops_admin_2' });
  assert.deepEqual(credentialChange({ ...base, newPassword: ' New Password 2 ', confirmPassword: ' New Password 2 ' }).body,
    { currentPassword: 'Old-Password-1', newPassword: ' New Password 2 ' });
  const both = credentialChange({ ...base, newUsername: 'ops_admin_3', newPassword: 'New-Password-3', confirmPassword: 'New-Password-3' });
  assert.deepEqual(Object.keys(both.body).sort(), ['currentPassword', 'newPassword', 'newUsername']);
  assert.deepEqual(both.changes, { username: true, password: true });
});

test('credentialChange：缺目前密碼、無變更、名稱不變、確認不符、新舊密碼相同都擋下', () => {
  const base = { currentUsername: 'ops_admin', currentPassword: 'Old-Password-1' };
  const errs = (v) => credentialChange({ ...base, ...v });
  assert.ok(errs({ currentPassword: '', newUsername: 'ops_admin_2' }).errors.some((e) => /目前密碼/.test(e)));
  assert.ok(errs({}).errors.some((e) => /至少修改/.test(e)));
  assert.ok(errs({ newUsername: 'ops_admin' }).errors.some((e) => /與目前相同/.test(e)));
  assert.ok(errs({ newPassword: 'New-Password-3', confirmPassword: 'New-Password-4' }).errors.some((e) => /不一致/.test(e)));
  assert.ok(errs({ confirmPassword: 'New-Password-4' }).errors.length > 0, '只填確認密碼也視為要改密碼');
  assert.ok(errs({ newPassword: 'Old-Password-1', confirmPassword: 'Old-Password-1' }).errors.some((e) => /不能與目前密碼相同/.test(e)));
  for (const v of [{ currentPassword: '' }, {}, { newUsername: 'ops_admin' }]) assert.equal(errs(v).body, null);
});

// ---------- 錯誤分類與成功判定 ----------

const apiErr = (status, code) => new ApiError({ kind: 'http', status, code });

test('accountFailure：400／409／429 與開始寫入前的 503 不登出', () => {
  for (const [s, c] of [[400, 'CURRENT_PASSWORD_INVALID'], [400, 'INVALID_USERNAME'], [400, 'INVALID_NEW_PASSWORD'], [400, 'NO_CREDENTIAL_CHANGES'], [400, 'PASSWORD_UNCHANGED'],
    [409, 'USERNAME_UNAVAILABLE'], [409, 'ACCOUNT_AUTHORITY_REQUIRED'], [409, 'CONTROL_HANDOFF_PENDING'], [429, 'CREDENTIAL_RATE_LIMITED'],
    [503, 'ACCOUNT_SECURITY_UNAVAILABLE'], [503, 'ACCOUNT_UPDATE_UNAVAILABLE'], [503, 'CONTROL_LEASE_NOT_HELD']]) {
    const f = accountFailure(apiErr(s, c));
    assert.equal(f.outcome, 'REJECTED', c);
    assert.equal(f.message, ACCOUNT_ERROR_TEXT[c]);
  }
  assert.match(ACCOUNT_ERROR_TEXT.ACCOUNT_UPDATE_UNAVAILABLE, /尚未開始寫入/);
  assert.equal(accountFailure(apiErr(409, 'SOMETHING_NEW')).outcome, 'REJECTED');
});

test('accountFailure：401／403 登出', () => {
  assert.equal(accountFailure(apiErr(401, 'ACCOUNT_CREDENTIALS_CHANGED')).outcome, 'LOGOUT');
  assert.equal(accountFailure(apiErr(401, 'AUTHENTICATION_REQUIRED')).outcome, 'LOGOUT');
  assert.equal(accountFailure(apiErr(401, null)).message, ACCOUNT_ERROR_TEXT.AUTHENTICATION_REQUIRED);
  assert.equal(accountFailure(apiErr(403, 'ADMIN_REQUIRED')).outcome, 'LOGOUT');
});

test('accountFailure：503 ACCOUNT_UPDATE_OUTCOME_UNKNOWN、網路／逾時／解析失敗、未定義 5xx 都是結果未知', () => {
  const f = accountFailure(apiErr(503, 'ACCOUNT_UPDATE_OUTCOME_UNKNOWN'));
  assert.equal(f.outcome, 'UNKNOWN');
  assert.match(f.message, /可能已寫入/);
  assert.match(f.message, /不會自動重送/);
  for (const e of [new ApiError({ kind: 'network' }), new ApiError({ kind: 'timeout' }), new ApiError({ kind: 'parse', status: 200 }),
    apiErr(500, null), apiErr(502, null), apiErr(503, 'SOMETHING_ELSE'), apiErr(504, 'ACCOUNT_UPDATE_UNAVAILABLE')]) {
    assert.equal(accountFailure(e).outcome, 'UNKNOWN', e.message);
  }
});

test('confirmedCredentialResult：200 必須明確 reauthenticationRequired:true 且名稱符合預期', () => {
  assert.equal(confirmedCredentialResult({ username: 'ops_admin_2', reauthenticationRequired: true }, 'ops_admin_2'), true);
  for (const r of [null, undefined, '', 'ok', [], {}, { username: 'ops_admin_2' }, { reauthenticationRequired: true },
    { username: 'ops_admin_2', reauthenticationRequired: 'true' }, { username: 'ops_admin_2', reauthenticationRequired: false },
    { username: 'ops_admin', reauthenticationRequired: true }, { username: 'OPS_ADMIN_2', reauthenticationRequired: true }]) {
    assert.equal(confirmedCredentialResult(r, 'ops_admin_2'), false, JSON.stringify(r));
  }
});

test('normalizeAccount：缺 username 視為無效；canEdit 只認 true', () => {
  assert.equal(normalizeAccount(null), null);
  assert.equal(normalizeAccount({ canEdit: true }), null);
  assert.deepEqual(normalizeAccount({ username: 'a_b', canEdit: 'yes', reason: '' }), { username: 'a_b', canEdit: false, reason: null });
  assert.deepEqual(normalizeAccount({ username: 'a_b', canEdit: true }), { username: 'a_b', canEdit: true, reason: null });
});

test('readonlyReasonText：已知代碼轉成說明；未知、空值或非字串顯示通用唯讀說明', () => {
  for (const code of ['ACCOUNT_AUTHORITY_REQUIRED', 'CONTROL_HANDOFF_PENDING', 'CONTROL_LEASE_NOT_HELD']) assert.equal(readonlyReasonText(code), ACCOUNT_ERROR_TEXT[code]);
  for (const r of ['SOMETHING_NEW', null, undefined, '', 42, 'toString', '__proto__']) assert.equal(readonlyReasonText(r), ACCOUNT_READONLY_TEXT, String(r));
});

test('帳號頁：未知唯讀代碼不直接顯示，改用通用說明', async () => {
  const h = accountHarness({ account: { username: 'ops_admin', canEdit: false, reason: 'FUTURE_REASON_CODE' }, post: () => { throw new Error('不應送出'); } });
  await h.ui.load();
  assert.equal(h.$('account-status').textContent, ACCOUNT_READONLY_TEXT);
  assert.equal(h.$('account-submit').disabled, true);
});

// ---------- API ----------

test('帳號 API：GET /account 與 POST /account/credentials；本文只允許三個欄位', async () => {
  const fetchImpl = fakeFetch((url) => jsonResponse(200, url.endsWith('/account') ? { username: 'ops_admin', canEdit: true, reason: null } : { username: 'x_y', reauthenticationRequired: true }));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.getAccount();
  assert.equal(fetchImpl.calls[0].init.method, 'GET');
  assert.match(fetchImpl.calls[0].url, /\/admin\/control\/v1\/account$/);
  await client.changeCredentials({ currentPassword: 'p', newUsername: 'x_y' });
  assert.equal(fetchImpl.calls[1].init.method, 'POST');
  assert.match(fetchImpl.calls[1].url, /\/admin\/control\/v1\/account\/credentials$/);
  assert.deepEqual(fetchImpl.calls[1].body, { currentPassword: 'p', newUsername: 'x_y' });
  for (const bad of [{ currentPassword: 'p' }, { newUsername: 'x_y' }, { currentPassword: 'p', newUsername: 'x', confirmPassword: 'c' },
    { currentPassword: 'p', newUsername: 'x', userId: 1 }, { currentPassword: 'p', newUsername: 'x', role: 'ADMIN' }, { currentPassword: 'p', newPassword: '' }]) {
    await assert.rejects(client.changeCredentials(bad), (e) => e.kind === 'validation');
  }
  assert.equal(fetchImpl.calls.length, 2, '無效本文不送出');
});

// ---------- 帳號頁 UI 流程（最小假 DOM） ----------

function fakeDom() {
  const elements = new Map();
  const make = (id) => {
    const listeners = {};
    const el = {
      id, value: '', textContent: '', hidden: false, disabled: false, className: '', children: [],
      replaceChildren(...c) { this.children = c; },
      addEventListener(type, fn) { (listeners[type] ??= []).push(fn); },
      dispatch(type, event = {}) { return Promise.all((listeners[type] ?? []).map((fn) => fn({ preventDefault() {}, ...event }))); },
    };
    elements.set(id, el);
    return el;
  };
  globalThis.document = {
    getElementById: (id) => elements.get(id) ?? make(id),
    createElement: () => ({ textContent: '' }),
  };
  return (id) => globalThis.document.getElementById(id);
}

function accountHarness({ account = { username: 'ops_admin', canEdit: true, reason: null }, post }) {
  const $ = fakeDom();
  const calls = { get: 0, post: [], dialogs: [], reauth: [], authFailures: 0 };
  let active = true;
  const client = {
    async getAccount() { calls.get++; return typeof account === 'function' ? account() : account; },
    async changeCredentials(body) { calls.post.push(body); return post(body); },
  };
  const ui = mountAccount({
    client,
    session: { isActive: () => active },
    confirmDialog: async (opts) => { calls.dialogs.push(opts); return true; },
    onReauthenticate: (r) => { calls.reauth.push(r); active = false; },
    onAuthFailure: () => { calls.authFailures++; },
  });
  const fill = (v) => {
    $('account-current-password').value = v.current ?? 'Old-Password-1';
    $('account-new-username').value = v.username ?? '';
    $('account-new-password').value = v.password ?? '';
    $('account-confirm-password').value = v.confirm ?? v.password ?? '';
  };
  const submit = () => $('account-form').dispatch('submit');
  const passwordsCleared = () => ['account-current-password', 'account-new-password', 'account-confirm-password'].every((id) => $(id).value === '');
  return { $, ui, calls, fill, submit, passwordsCleared, setActive: (v) => { active = v; } };
}

test('帳號頁：只有明確成功回應才顯示已更新，並預填新名稱', async () => {
  const h = accountHarness({ post: (b) => ({ username: b.newUsername, reauthenticationRequired: true }) });
  await h.ui.load();
  h.fill({ username: 'ops_admin_2' });
  await h.submit();
  assert.equal(h.calls.post.length, 1);
  assert.equal(h.calls.get, 2, '確認後重新讀取帳號狀態');
  assert.match(h.calls.reauth[0].message, /已更新/);
  assert.equal(h.calls.reauth[0].username, 'ops_admin_2');
  assert.ok(h.passwordsCleared());
  const lines = h.calls.dialogs[0].lines.join('\n');
  assert.ok(!lines.includes('Old-Password-1'), '確認框不顯示密碼');
});

test('帳號頁：只改密碼時成功名稱必須仍是目前名稱', async () => {
  const ok = accountHarness({ post: () => ({ username: 'ops_admin', reauthenticationRequired: true }) });
  await ok.ui.load();
  ok.fill({ password: 'New Password 22' });
  await ok.submit();
  assert.match(ok.calls.reauth[0].message, /已更新/);
  assert.equal(ok.calls.reauth[0].username, 'ops_admin');
  assert.ok(!ok.calls.dialogs[0].lines.join('\n').includes('New Password 22'));
});

test('帳號頁：HTTP 200 但缺欄位／格式錯誤／名稱不符都視為結果未知，不宣稱成功', async () => {
  for (const reply of [{}, null, 'ok', { username: 'ops_admin_2' }, { reauthenticationRequired: true }, { username: 'other_name', reauthenticationRequired: true },
    { username: 'ops_admin_2', reauthenticationRequired: 'yes' }]) {
    const h = accountHarness({ post: () => reply });
    await h.ui.load();
    h.fill({ username: 'ops_admin_2' });
    await h.submit();
    assert.equal(h.calls.post.length, 1, '不自動重送');
    assert.equal(h.calls.reauth.length, 1);
    assert.ok(!/已更新/.test(h.calls.reauth[0].message), JSON.stringify(reply));
    assert.match(h.calls.reauth[0].message, /無法確認/);
    assert.equal(h.calls.reauth[0].username, null, '結果未知時不預填新名稱');
    assert.ok(h.passwordsCleared());
  }
});

test('帳號頁：ACCOUNT_UPDATE_OUTCOME_UNKNOWN 與網路中斷回登入確認，只送一次', async () => {
  for (const err of [apiErr(503, 'ACCOUNT_UPDATE_OUTCOME_UNKNOWN'), new ApiError({ kind: 'network' }), new ApiError({ kind: 'timeout' })]) {
    const h = accountHarness({ post: () => { throw err; } });
    await h.ui.load();
    h.fill({ username: 'ops_admin_2', password: 'New-Password-33' });
    await h.submit();
    assert.equal(h.calls.post.length, 1);
    assert.equal(h.calls.reauth.length, 1);
    assert.equal(h.calls.reauth[0].username, null);
    assert.ok(!/已更新/.test(h.calls.reauth[0].message));
    assert.ok(h.passwordsCleared());
  }
});

test('帳號頁：400／409／429／ACCOUNT_UPDATE_UNAVAILABLE 保留登入、清空密碼並顯示原因', async () => {
  for (const [s, c] of [[400, 'CURRENT_PASSWORD_INVALID'], [409, 'USERNAME_UNAVAILABLE'], [429, 'CREDENTIAL_RATE_LIMITED'], [503, 'ACCOUNT_UPDATE_UNAVAILABLE']]) {
    const h = accountHarness({ post: () => { throw apiErr(s, c); } });
    await h.ui.load();
    h.fill({ username: 'ops_admin_2' });
    await h.submit();
    assert.equal(h.calls.reauth.length, 0, c);
    assert.equal(h.$('account-result').textContent, ACCOUNT_ERROR_TEXT[c]);
    assert.ok(h.passwordsCleared());
    assert.equal(h.$('account-new-username').value, 'ops_admin_2', '保留非機密輸入');
  }
});

test('帳號頁：備機（canEdit=false）停用儲存且不送出', async () => {
  const h = accountHarness({ account: { username: 'ops_admin', canEdit: false, reason: 'CONTROL_LEASE_NOT_HELD' }, post: () => { throw new Error('不應送出'); } });
  await h.ui.load();
  h.fill({ username: 'ops_admin_2' });
  h.ui.render();
  assert.equal(h.$('account-submit').disabled, true);
  assert.equal(h.$('account-status').textContent, ACCOUNT_ERROR_TEXT.CONTROL_LEASE_NOT_HELD);
  assert.ok(!h.$('account-status').textContent.includes('CONTROL_LEASE_NOT_HELD'), '不直接顯示代碼');
  await h.submit();
  assert.equal(h.calls.post.length, 0);
});

test('帳號頁：確認期間變成不可修改或名稱被改動就不送出', async () => {
  let n = 0;
  const h = accountHarness({ account: () => (++n === 1 ? { username: 'ops_admin', canEdit: true } : { username: 'ops_admin', canEdit: false, reason: 'CONTROL_HANDOFF_PENDING' }), post: () => { throw new Error('不應送出'); } });
  await h.ui.load();
  h.fill({ username: 'ops_admin_2' });
  await h.submit();
  assert.equal(h.calls.post.length, 0);
  assert.ok(h.$('account-result').textContent.includes(ACCOUNT_ERROR_TEXT.CONTROL_HANDOFF_PENDING));
});

test('帳號頁：登出（reset）後才到達的回應不影響畫面', async () => {
  let release;
  const h = accountHarness({ post: () => new Promise((_, reject) => { release = () => reject(apiErr(409, 'USERNAME_UNAVAILABLE')); }) });
  await h.ui.load();
  h.fill({ username: 'ops_admin_2' });
  const pending = h.submit();
  await new Promise((r) => setTimeout(r, 0));
  h.ui.reset();
  h.setActive(false);
  release();
  await pending;
  assert.equal(h.$('account-result').textContent, '');
  assert.equal(h.$('account-current-username').textContent, '—');
  assert.equal(h.calls.reauth.length, 0);
  assert.ok(h.passwordsCleared());
});

// ---------- 多站總覽模型 ----------

test('homeModel：沒有資料時是空狀態而不是正常', () => {
  const m = homeModel({ statusFreshness: 'LOADING', obsFreshness: 'LOADING', status: null, lead: null });
  assert.equal(m.deployment, null);
  assert.equal(m.sites.known, false);
  assert.deepEqual(m.sites.rows, []);
  assert.equal(m.leader.writable, false);
  assert.ok(m.issues.some((i) => i.route === 'control'));
});

test('homeModel：備機、未知觀測、部署結果未知依嚴重度排序', () => {
  const m = homeModel({
    statusFreshness: 'FRESH', obsFreshness: 'FRESH', status: { pendingChanges: [], worker: { lastCode: 'OK' } },
    views: [{ siteId: 'campus', state: 'ACTIVE', mode: 'CAMPUS', observable: true, observed: 'REACHABLE' },
      { siteId: 'external', state: 'ACTIVE', mode: 'EXTERNAL', observable: true, observed: 'UNKNOWN', observation: { code: 'NODE_TIMEOUT' } },
      { siteId: 'site_3', state: 'REGISTERED', mode: 'EXTERNAL', observable: false, observed: 'NOT_OBSERVED' }],
    lead: { role: 'STANDBY', writable: false, reasons: ['本入口是備機'] },
    job: { id: 'j', state: 'OUTCOME_UNKNOWN', releaseId: 'r1', targets: [{ site: 'campus', state: 'DEPLOYING' }] },
  });
  assert.equal(m.issues[0].tone, 'red');
  assert.equal(m.issues[0].title, '部署結果未知');
  assert.ok(m.issues.some((i) => i.title === 'external 觀測未知' && /NODE_TIMEOUT/.test(i.detail)));
  assert.ok(m.issues.some((i) => /site_3/.test(i.title) && i.tone === 'neutral'));
  assert.equal(m.issues.at(-1).tone, 'neutral');
  assert.equal(m.leader.title, '本入口是備機（唯讀）');
  const writable = homeModel({ statusFreshness: 'FRESH', obsFreshness: 'FRESH', status: { pendingChanges: [] }, lead: { role: 'LEADER', writable: true, reasons: [] } });
  assert.equal(writable.leader.detail, '管理操作可用');
  assert.ok(!/租約|epoch/.test(writable.leader.detail));
  assert.deepEqual([m.sites.reachable, m.sites.observable], [1, 2]);
  assert.equal(m.worstTone, 'red');
});

test('homeModel：真實單站集群（SINGLE_SITE_ACTIVE、epoch 1）沒有待處理問題；真正錯誤碼仍列出', () => {
  const healthy = (lastCode) => homeModel({
    statusFreshness: 'FRESH', obsFreshness: 'FRESH',
    status: { epoch: 1, pendingChanges: [{ state: 'APPLIED' }], worker: { running: true, lastCode } },
    views: [{ siteId: 'server-campus', state: 'ACTIVE', mode: 'CAMPUS', observable: true, observed: 'REACHABLE' }],
    lead: { role: 'LEADER', writable: true, reasons: [] },
  });
  const ok = healthy('SINGLE_SITE_ACTIVE');
  assert.deepEqual(ok.issues, [], '單站運行不是錯誤');
  assert.equal(ok.worstTone, 'teal');
  assert.equal(ok.worker.label, '單站集群運行中');
  assert.equal(ok.worker.tone, 'teal');
  for (const code of ['CLUSTER_CYCLE_RETRY', 'SINGLE_SITE_DEGRADED', 'ACTIVE', 'single_site_active']) {
    const m = healthy(code);
    assert.ok(m.issues.some((i) => i.title === 'worker 回報錯誤碼' && i.detail === code), code);
    assert.equal(m.worker.tone, 'amber', code);
  }
  assert.equal(healthy(null).worker, null);
});

test('jobSummary：無效輸入回 null；未知狀態保留原字串', () => {
  assert.equal(jobSummary(null), null);
  assert.equal(jobSummary({}), null);
  const s = jobSummary({ state: 'NEW_STATE', targets: [{ site: 'a', state: 'X' }, { bad: true }] });
  assert.equal(s.label, 'NEW_STATE');
  assert.deepEqual(s.targets.map((t) => t.site), ['a']);
});
