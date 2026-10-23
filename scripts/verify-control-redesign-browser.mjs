// 中控改版瀏覽器驗收（本機模擬 API；不連線正式環境、不部署）。
// 用法：EATWHAT_PLAYWRIGHT_MODULE=<existing-playwright-package> node scripts/verify-control-redesign-browser.mjs
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, join } from 'node:path';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';

const root = resolve('frontend/control');
const output = resolve('output/control-redesign-verification');
const { chromium } = createRequire(join(process.env.EATWHAT_PLAYWRIGHT_MODULE, 'package.json'))('playwright');

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const I1 = '11111111-1111-4111-8111-111111111111', I2 = '22222222-2222-4222-8222-222222222222', I3 = '33333333-3333-4333-8333-333333333333';
const PLAN = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const sha = 'a'.repeat(64);

// ---------- 可控的模擬狀態 ----------
const S = {
  mode: 'cluster', localLeader: true, generation: 3, handoff: { state: 'NONE' },
  account: { username: 'ops_admin', canEdit: true, reason: null },
  credentialReply: null, credentialBodies: [], credentialPosts: 0,
  executions: 0, reconciliations: 0, unknown: false, handoffRequests: 0, writes: [],
};
const caps = () => ({ topology: 'SINGLE_CLUSTER', maximumActiveSites: 16, allocationSlots: 1024, coordinatorTransferEnabled: false, leadershipHandoffEnabled: true, businessSessionShared: true, deploymentExecutionEnabled: true, remoteFailureMatrixAccepted: false });
const leadership = () => ({ state: 'HELD', localLeader: S.localLeader, generation: S.generation, expiresAt: '2026-10-17T08:00:15Z', localControllerId: A, leaderControllerId: S.localLeader ? A : B, handoffEnabled: true, handoff: S.handoff, controllers: [{ id: A, name: 'campus-control-a', sharedState: true }, { id: B, name: 'campus-control-b', sharedState: true }] });
const reg = (site, inst, slot, state, mode = 'EXTERNAL') => ({ site_id: site, instance_id: inst, profile_id: `p-${site}`, mode, allocation_slot: slot, state, database_fingerprint: `fp${slot}`.padEnd(32, '0'), joined_epoch: state === 'REGISTERED' ? null : 1, retired_epoch: null });
const status = () => ({ clusterId: A, epoch: 4, members: [reg('campus', I1, 0, 'ACTIVE', 'CAMPUS'), reg('external', I2, 1, 'ACTIVE'), reg('site_3', I3, 2, 'REGISTERED')], pendingChanges: [], worker: { running: true, lastCode: 'OK' }, leadership: leadership() });
const obs = (site, inst, slot, mode) => ({ member: { siteId: site, instanceId: inst, mode, allocationSlot: slot }, health: 'REACHABLE', node: { siteId: site, instanceId: inst, clusterId: A, allocationSlot: slot, mode, databaseFingerprint: `fp${slot}`.padEnd(32, '0'), protocolVersion: 1, dataPlaneReady: true, membership: { epoch: 4, state: 'ACTIVE', pending_change: null }, drain: { liveReferences: 0, localObjects: 3, pendingUploads: 0, unarchivedAccounts: 0 }, control: { siteId: site, instanceId: inst, mode, observedAt: '2026-10-17T08:00:00Z', deploymentExecutionEnabled: false, observationStatus: 'OBSERVED', readiness: 'NOT_CHECKED', storageReachability: 'NOT_CHECKED', channels: { events: { state: 'FORWARDING', expiresAt: '', acceptedConnections: 2, forwardedBytes: 4096, routingVerified: false, maximumBlockSeconds: 60 } } } }, sync: { pendingDeliveries: 0, pendingInbox: 0, fileTransfers: 0 } });
const observations = () => [obs('campus', I1, 0, 'CAMPUS'), { member: { siteId: 'external', instanceId: I2, mode: 'EXTERNAL', allocationSlot: 1 }, health: 'UNKNOWN', code: 'NODE_TIMEOUT' }];
const plan = () => ({ id: PLAN, requestHash: 'h', createdAt: '2026-10-17T08:00:00Z', epoch: 4, releaseId: 'release_2026_10_17', artifactSha256: sha, members: [{ siteId: 'campus', instanceId: I1, mode: 'CAMPUS', allocationSlot: 0 }], state: 'PREVIEW_ONLY', executionEnabled: true, blockers: [], steps: ['備份資料庫', '更新 campus'] });
const job = () => ({ id: PLAN, releaseId: 'release_2026_10_17', state: S.unknown ? 'OUTCOME_UNKNOWN' : 'SUCCEEDED', targets: [{ site: 'campus', state: S.unknown ? 'DEPLOYING' : 'SUCCEEDED' }] });

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;
    let text = '';
    for await (const part of req) text += part;
    const send = (code, body) => { res.statusCode = code; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(body)); };
    if (p === '/auth/login') return send(200, { code: 0, data: { tokenName: 'satoken', tokenValue: 'synthetic-redesign-token' } });
    if (p.startsWith('/admin/control/v1')) {
      if (req.method === 'POST') S.writes.push(p);
      if (p === '/admin/control/v1/account') return send(200, S.account);
      if (p === '/admin/control/v1/account/credentials') {
        S.credentialPosts++;
        S.credentialBodies.push(JSON.parse(text));
        const reply = S.credentialReply ?? { status: 200 };
        S.credentialReply = null;
        if (reply.delay) await new Promise((r) => setTimeout(r, reply.delay));
        // 回應中途斷線：已送出部分回應，瀏覽器不會自行重試，前端拿到網路錯誤（結果未知）。
        if (reply.drop) { res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': '200' }); res.write('{"username":'); setTimeout(() => req.socket.destroy(), 50); return; }
        if (reply.status === 200 && reply.body !== undefined) return send(200, reply.body);
        if (reply.status === 200) {
          const body = JSON.parse(text);
          if (body.newUsername) S.account = { ...S.account, username: body.newUsername };
          return send(200, { username: S.account.username, reauthenticationRequired: true });
        }
        return send(reply.status, { code: reply.code });
      }
      if (p.endsWith('/cluster/capabilities')) return send(200, caps());
      if (p.endsWith('/capabilities')) return send(200, { apiVersion: 'v1', environmentKind: 'isolated-acceptance', deploymentExecutionEnabled: true, remoteFaultMatrixAutomated: false, testSuites: [], configuredChannels: { campus: [], external: [] } });
      if (p.endsWith('/leadership')) return send(200, leadership());
      if (p.endsWith('/leadership/handoffs') && req.method === 'POST') { S.handoffRequests++; const b = JSON.parse(text); S.handoff = { id: b.requestId, state: 'WAITING', sourceControllerId: A, targetControllerId: b.targetControllerId, expectedGeneration: b.expectedGeneration, reason: 'WAITING_FOR_QUIESCENCE' }; return send(202, S.handoff); }
      if (p.includes('/leadership/handoffs/') && p.endsWith('/cancel')) { S.handoff = { ...S.handoff, state: 'CANCELLED', reason: 'ADMIN_CANCELLED' }; return send(200, S.handoff); }
      if (p === '/admin/control/v1/cluster') return send(200, status());
      if (p.endsWith('/observations')) return send(200, observations());
      if (p.endsWith('/channels')) return send(200, [{ siteId: 'external', instanceId: I2, channels: { events: 60 }, routingVerified: false }]);
      if (p.endsWith('/profiles')) return send(200, [{ id: 'p-site_4', siteId: 'site_4', instanceId: '44444444-4444-4444-8444-444444444444', mode: 'EXTERNAL', allocationSlot: 3 }]);
      if (p.endsWith('/releases')) return send(200, [{ id: 'release_2026_10_17', sha256: sha, acceptanceEvidenceId: 'reviewed' }]);
      if (p.endsWith('/test-suites')) return send(200, [{ id: 'business', scope: 'LOCAL_BUSINESS_RUSTFS', enabled: true }]);
      if (p.endsWith('/sites') && req.method === 'GET') return send(200, { observedAt: new Date().toISOString(), pairStatus: 'VERIFIED_DISTINCT', sites: {} });
      if (p.endsWith('/execute')) { S.executions++; return send(202, { ...job(), state: 'QUEUED' }); }
      if (p.endsWith('/reconcile')) { S.reconciliations++; S.unknown = false; return send(200, job()); }
      if (p.includes('/deployments/')) return send(200, job());
      if (p.includes('/deployment-plans/')) return send(200, plan());
      if (p.endsWith('/deployment-plans')) return send(201, plan());
      if (p.endsWith('/cluster/audit')) return send(200, [{ id: 1, request_id: A, actor: '資料庫管理員', action: 'JOIN', site_id: 'external', epoch: 4, created_at: '2026-10-17T07:00:00Z' }]);
      return send(200, []);
    }
    const file = resolve(root, '.' + (p === '/' ? '/index.html' : p));
    assert.ok(file.startsWith(root + '/'));
    let content = await readFile(file);
    if (file.endsWith('constants.js') && S.mode === 'legacy') content = Buffer.from(content.toString().replace("CONTROL_MODE = 'cluster'", "CONTROL_MODE = 'legacy'"));
    res.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[extname(file)] ?? 'application/octet-stream');
    res.end(content);
  } catch (error) { res.statusCode = 500; res.end('{}'); }
});

await new Promise((done) => server.listen(0, '127.0.0.1', done));
const origin = `http://127.0.0.1:${server.address().port}`;
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
const steps = [], errors = [], external = [];
const record = (name) => { steps.push(name); console.log(name); };
let page;

const ROUTES = ['overview', 'deploy', 'sites', 'control', 'advanced/gc', 'advanced/fault', 'advanced/tasks', 'audit', 'account'];
const noOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1);
const visiblePages = () => page.evaluate(() => [...document.querySelectorAll('[data-page]')].filter((e) => e.offsetParent !== null).map((e) => e.dataset.page));
async function nav(route) {
  const narrow = await page.evaluate(() => innerWidth <= 900);
  if (narrow) { await page.locator('#nav-toggle').click(); await page.locator('#sidebar').waitFor({ state: 'visible' }); }
  const group = route.startsWith('advanced/') && !(await page.locator(`#app-nav .nav-sub a[data-route="${route}"]`).isVisible());
  if (group) await page.locator('#app-nav a[data-route-group="true"]').click();
  if (narrow && group) { await page.locator('#sidebar').waitFor({ state: 'hidden' }); await page.locator('#nav-toggle').click(); await page.locator('#sidebar').waitFor({ state: 'visible' }); }
  await page.locator(route.startsWith('advanced/') ? `#app-nav .nav-sub a[data-route="${route}"]` : `#app-nav > a[data-route="${route}"]`).click();
  await page.waitForFunction((r) => location.hash === `#/${r}` && [...document.querySelectorAll(`[data-page="${r}"]`)].some((e) => e.offsetParent !== null), route);
  assert.deepEqual(await visiblePages(), [route]);
}
async function login(username = 'ops_admin', password = 'Synthetic-Password-1') {
  await page.locator('#login-username').fill(username);
  await page.locator('#login-password').fill(password);
  await page.locator('#login-submit').click();
  await page.locator('#appbar').waitFor({ state: 'visible' });
}
async function newPage(width = 1440, height = 960, locale = null) {
  page = await browser.newPage({ viewport: { width, height } });
  page.setDefaultTimeout(15000);
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('request', (r) => { if (!r.url().startsWith(origin + '/')) external.push(r.url()); });
  if (locale) await page.addInitScript((l) => localStorage.setItem('eatwhat.control.locale', l), locale);
  await page.goto(origin);
}
async function fillAccount({ current = 'Synthetic-Password-1', username = '', password = '', confirm = password }) {
  await page.locator('#account-current-password').fill(current);
  await page.locator('#account-new-username').fill(username);
  await page.locator('#account-new-password').fill(password);
  await page.locator('#account-confirm-password').fill(confirm);
}
async function submitAccount() {
  await page.waitForFunction(() => !document.getElementById('account-submit').disabled);
  await page.locator('#account-submit').click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  const dialog = await page.locator('#dialog-lines').textContent();
  await page.locator('#dialog-confirm').click();
  return dialog;
}
const shot = (name, full = true) => page.screenshot({ path: join(output, name), fullPage: full });

try {
  // ---------- 登入、簡繁、分頁 ----------
  await newPage();
  assert.equal(await page.locator('html').getAttribute('lang'), 'zh-Hans');
  assert.equal(await page.locator('#login-title').textContent(), '管理员登录');
  assert.equal(await page.locator('#sidebar').isVisible(), false);
  await shot('login-1440.png', false); record('minimal_login_simplified_default_without_sidebar');

  await login();
  await page.waitForFunction(() => location.hash === '' || location.hash === '#/overview');
  assert.deepEqual(await visiblePages(), ['overview']);
  await page.waitForFunction(() => document.querySelector('#cl-overview .issues, #cl-overview .empty'));
  await page.waitForFunction(() => document.querySelector('#cl-overview').textContent.includes('external'));
  const home = await page.locator('#cl-overview').textContent();
  assert.ok(home.includes('待处理问题'), home);
  assert.ok(/external 观测未知/.test(home) && home.includes('NODE_TIMEOUT'));
  assert.ok(home.includes('site_3') && home.includes('本次登录尚未读取部署任务'));
  assert.ok(!home.includes('先看状态与下一步'));
  assert.equal(await page.locator('#app-nav .nav-sub').isVisible(), false);
  assert.equal(await page.locator('#nav-issues').isVisible(), true);
  record('overview_shows_real_issues_and_empty_deployment_state');
  assert.ok(home.includes('管理操作可用') && !home.includes('重新核对租约'), '主中控卡片只顯示簡短說明');
  await shot('overview-1440.png');

  for (const route of ROUTES) {
    await nav(route);
    assert.equal(await page.locator('.page-head h1:visible').count(), 1);
    assert.ok(await noOverflow(), route);
    assert.equal(await page.locator('#app-nav .nav-sub').isVisible(), route.startsWith('advanced/'));
    await shot(`${route.replace('/', '-')}-1440.png`);
  }
  record('all_nine_routes_reachable_single_page_visible_no_overflow_advanced_submenu_only_in_section');

  await page.locator('#locale-select').selectOption('zh-Hant');
  assert.equal(await page.locator('#app-nav > a[data-route="overview"] span').first().textContent(), '總覽');
  assert.equal(await page.locator('#logout-button').textContent(), '登出');
  await page.locator('#locale-select').selectOption('zh-Hans');
  assert.equal(await page.locator('#logout-button').textContent(), '退出登录');
  await nav('audit');
  assert.ok((await page.locator('#cl-audit').textContent()).includes('資料庫管理員'), '原始操作者不轉換');
  assert.deepEqual(await page.evaluate(() => Object.fromEntries(Object.entries(localStorage))), { 'eatwhat.control.locale': 'zh-Hans' });
  record('locale_switch_overrides_and_raw_actor_preserved');

  // ---------- 備機：寫入停用 ----------
  S.localLeader = false; S.account = { ...S.account, canEdit: false, reason: 'CONTROL_LEASE_NOT_HELD' };
  await page.locator('#refresh-button').click();
  await nav('sites');
  await page.waitForFunction(() => document.getElementById('cl-member-blocking').textContent.includes('备机'));
  for (const id of ['cl-member-submit', 'cl-register-submit', 'cl-profiles-reload']) assert.equal(await page.locator(`#${id}`).isDisabled(), true, id);
  await nav('deploy'); assert.equal(await page.locator('#cl-plan-create').isDisabled(), true);
  await nav('advanced/fault'); assert.equal(await page.locator('#cl-fault-submit').isDisabled(), true);
  await nav('advanced/gc'); assert.equal(await page.locator('#cl-gc-begin-submit').isDisabled(), true);
  await nav('control'); await shot('control-standby-1440.png');
  await nav('account');
  await page.waitForFunction(() => document.getElementById('account-status').textContent.includes('不是主中控'));
  assert.ok(!(await page.locator('#account-status').textContent()).includes('CONTROL_LEASE_NOT_HELD'), '唯讀原因不直接顯示代碼');
  await fillAccount({ username: 'ops_admin_9' });
  assert.equal(await page.locator('#account-submit').isDisabled(), true);
  await nav('overview');
  assert.ok((await page.locator('#cl-overview').textContent()).includes('本入口是备机'));
  await shot('overview-standby-1440.png');
  assert.equal(S.writes.length, 0);
  record('standby_disables_member_registry_deploy_fault_gc_and_account_writes');
  S.localLeader = true; S.account = { ...S.account, canEdit: true, reason: null };
  await page.locator('#refresh-button').click();

  // ---------- 手動切主 ----------
  await nav('control');
  await page.waitForFunction(() => !document.getElementById('cl-handoff-submit').disabled);
  await page.locator('#cl-handoff-target').selectOption(B);
  await page.locator('#cl-handoff-submit').click(); await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  await page.locator('#dialog-cancel').click(); assert.equal(S.handoffRequests, 0);
  await page.locator('#cl-handoff-submit').click(); await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  await page.locator('#dialog-confirm').click();
  await page.waitForFunction(() => document.getElementById('cl-handoff-result').textContent.includes('WAITING'));
  assert.equal(S.handoffRequests, 1);
  await page.waitForFunction(() => !document.getElementById('cl-handoff-cancel').disabled);
  await page.locator('#cl-handoff-cancel').click(); await page.locator('#confirm-dialog').waitFor({ state: 'visible' }); await page.locator('#dialog-confirm').click();
  await page.waitForFunction(() => document.getElementById('cl-handoff-result').textContent.includes('CANCELLED'));
  S.handoff = { state: 'NONE' };
  record('manual_handoff_confirm_cancel_and_request_once');

  // ---------- 部署：確認、未知結果核對 ----------
  await nav('deploy');
  await page.locator('#cl-release-select').selectOption('release_2026_10_17');
  await page.locator('#cl-plan-sites input').first().check();
  await page.waitForFunction(() => !document.getElementById('cl-plan-create').disabled);
  await page.locator('#cl-plan-create').click(); await page.locator('#confirm-dialog').waitFor({ state: 'visible' }); await page.locator('#dialog-confirm').click();
  await page.waitForFunction(() => !document.getElementById('cl-deploy-execute').disabled);
  await page.locator('#cl-deploy-execute').click(); await page.locator('#confirm-dialog').waitFor({ state: 'visible' }); await page.locator('#dialog-cancel').click();
  assert.equal(S.executions, 0);
  await page.locator('#cl-deploy-execute').click(); await page.locator('#confirm-dialog').waitFor({ state: 'visible' }); await page.locator('#dialog-confirm').click();
  await page.waitForFunction((id) => document.body.textContent.includes(`任务 ${id} · SUCCEEDED`), PLAN);
  assert.equal(S.executions, 1);
  S.unknown = true;
  const lookup = page.locator('#cl-deploy-execute').locator('..').locator('form');
  await lookup.locator('input').fill(PLAN); await lookup.locator('button').click();
  await page.waitForFunction((id) => document.body.textContent.includes(`任务 ${id} · OUTCOME_UNKNOWN`), PLAN);
  await nav('overview');
  await page.waitForFunction(() => document.getElementById('cl-overview').textContent.includes('部署结果未知'));
  await shot('overview-deploy-unknown-1440.png');
  await nav('deploy');
  await page.getByRole('button', { name: /^核对远.部署记录$/ }).filter({ visible: true }).click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' }); await page.locator('#dialog-confirm').click();
  await page.waitForFunction((id) => document.body.textContent.includes(`任务 ${id} · SUCCEEDED`), PLAN);
  assert.equal(S.executions, 1); assert.equal(S.reconciliations, 1);
  await shot('deploy-after-reconcile-1440.png');
  record('deploy_confirm_once_unknown_shown_on_overview_and_reconciled_without_redeploy');

  // ---------- 帳號：前端驗證 ----------
  await nav('account');
  await page.waitForFunction(() => document.getElementById('account-current-username').textContent === 'ops_admin');
  for (const [values, needle] of [
    [{ username: '13800138000' }, '11'], [{ username: 'ab' }, '3–32'], [{ username: 'bad name' }, '3–32'], [{ username: 'ops_admin' }, '与目前相同'],
    [{ password: 'short-pass1' }, '12–64'], [{ password: '            ' }, '空白'], [{ password: 'Valid-Password-12', confirm: 'Valid-Password-13' }, '不一致'],
    [{ password: '密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼密碼' }, '72'],
    [{ current: 'Same-Password-123', password: 'Same-Password-123' }, '不能与目前密码相同'], [{ current: '' , username: 'ops_admin_2' }, '目前密码'], [{}, '至少修改'],
  ]) {
    await fillAccount(values);
    await page.waitForFunction((n) => document.getElementById('account-problems').textContent.includes(n), needle);
    assert.equal(await page.locator('#account-submit').isDisabled(), true, JSON.stringify(values));
  }
  assert.equal(S.credentialPosts, 0);
  await fillAccount({});
  await shot('account-validation-1440.png');
  record('account_client_validation_blocks_invalid_or_unchanged_input');

  // ---------- 帳號：錯誤回應不登出 ----------
  for (const [status, code, needle] of [[400, 'CURRENT_PASSWORD_INVALID', '目前密码不正确'], [409, 'USERNAME_UNAVAILABLE', '无法使用'], [429, 'CREDENTIAL_RATE_LIMITED', '5 分钟'], [409, 'CONTROL_HANDOFF_PENDING', '交接'], [503, 'CONTROL_LEASE_NOT_HELD', '不是主中控']]) {
    S.credentialReply = { status, code };
    await fillAccount({ username: 'ops_admin_x' });
    await submitAccount();
    await page.waitForFunction((n) => document.getElementById('account-result').textContent.includes(n), needle);
    assert.equal(await page.locator('#sidebar').isVisible(), true, code);
    assert.equal(await page.locator('#account-current-password').inputValue(), '');
  }
  record('account_400_409_429_503_keep_session_clear_passwords_and_explain');

  // ---------- 帳號：只改名稱 ----------
  await fillAccount({ username: 'ops_admin_2' });
  let dialog = await submitAccount();
  assert.ok(!dialog.includes('Synthetic-Password-1'), '確認彈窗不得顯示密碼');
  assert.ok(dialog.includes('ops_admin_2') && dialog.includes('失效'));
  await page.locator('#login-view').waitFor({ state: 'visible' });
  assert.deepEqual(Object.keys(S.credentialBodies.at(-1)).sort(), ['currentPassword', 'newUsername']);
  assert.equal(await page.locator('#login-username').inputValue(), 'ops_admin_2');
  assert.ok((await page.locator('#login-error').textContent()).includes('帐号已更新'));
  assert.equal(await page.evaluate(() => [...document.querySelectorAll('input[type=password]')].every((i) => i.value === '')), true);
  assert.equal(await page.locator('#cluster-view').isVisible(), false);
  await shot('account-relogin-1440.png', false);
  record('account_username_only_success_logs_out_and_prefills_new_name');

  // ---------- 只改密碼、兩者一起 ----------
  await login('ops_admin_2');
  await nav('account');
  await page.waitForFunction(() => document.getElementById('account-current-username').textContent === 'ops_admin_2');
  await fillAccount({ password: 'New Password With Space 1' });
  dialog = await submitAccount();
  assert.ok(!dialog.includes('New Password With Space 1'));
  await page.locator('#login-view').waitFor({ state: 'visible' });
  assert.deepEqual(Object.keys(S.credentialBodies.at(-1)).sort(), ['currentPassword', 'newPassword']);
  assert.equal(S.credentialBodies.at(-1).newPassword, 'New Password With Space 1', '密碼不 trim');
  await login('ops_admin_2', 'New Password With Space 1');
  await nav('account');
  await page.waitForFunction(() => document.getElementById('account-current-username').textContent === 'ops_admin_2');
  await fillAccount({ current: 'New Password With Space 1', username: 'ops_admin_3', password: 'Another-Password-77' });
  await submitAccount();
  await page.locator('#login-view').waitFor({ state: 'visible' });
  assert.deepEqual(Object.keys(S.credentialBodies.at(-1)).sort(), ['currentPassword', 'newPassword', 'newUsername']);
  assert.ok(S.credentialBodies.every((b) => !('confirmPassword' in b) && !('userId' in b) && !('role' in b)));
  record('account_password_only_and_combined_changes_send_only_changed_fields');

  // ---------- 401：登出 ----------
  await login('ops_admin_3', 'Another-Password-77');
  await nav('account');
  await page.waitForFunction(() => document.getElementById('account-current-username').textContent === 'ops_admin_3');
  S.credentialReply = { status: 401, code: 'ACCOUNT_CREDENTIALS_CHANGED' };
  await fillAccount({ current: 'Another-Password-77', username: 'ops_admin_4' });
  await submitAccount();
  await page.locator('#login-view').waitFor({ state: 'visible' });
  assert.ok((await page.locator('#login-error').textContent()).includes('凭证已变更'));
  record('account_401_logs_out_with_message');

  // ---------- 200 但內容無法確認、503 ACCOUNT_UPDATE_OUTCOME_UNKNOWN：結果未知 ----------
  for (const reply of [{ status: 200, body: {} }, { status: 200, body: { username: 'ops_admin_4' } }, { status: 200, body: { username: 'someone_else', reauthenticationRequired: true } }, { status: 503, code: 'ACCOUNT_UPDATE_OUTCOME_UNKNOWN' }]) {
    await login('ops_admin_3', 'Another-Password-77');
    await nav('account');
    await page.waitForFunction(() => document.getElementById('account-current-username').textContent === 'ops_admin_3');
    const sent = S.credentialPosts;
    S.credentialReply = reply;
    await fillAccount({ current: 'Another-Password-77', username: 'ops_admin_4' });
    await submitAccount();
    await page.locator('#login-view').waitFor({ state: 'visible' });
    const text = await page.locator('#login-error').textContent();
    assert.ok(!text.includes('已更新'), JSON.stringify(reply));
    assert.ok(reply.status === 503 ? text.includes('可能已写入') : text.includes('无法确认'), text);
    assert.notEqual(await page.locator('#login-username').inputValue(), 'ops_admin_4', '結果未知不預填新名稱');
    assert.equal(await page.evaluate(() => [...document.querySelectorAll('input[type=password]')].every((i) => i.value === '')), true);
    await page.waitForTimeout(300);
    assert.equal(S.credentialPosts, sent + 1, '不自動重送');
  }
  await shot('account-outcome-unknown-1440.png', false);
  record('account_unconfirmed_200_and_outcome_unknown_503_return_to_login_without_success_or_retry');

  // ---------- 結果未知：不重送 ----------
  await login('ops_admin_3', 'Another-Password-77');
  await nav('account');
  await page.waitForFunction(() => document.getElementById('account-current-username').textContent === 'ops_admin_3');
  const before = S.credentialPosts;
  S.credentialReply = { drop: true };
  await fillAccount({ current: 'Another-Password-77', username: 'ops_admin_5' });
  await submitAccount();
  await page.locator('#login-view').waitFor({ state: 'visible' });
  { const t = await page.locator('#login-error').textContent(); assert.ok(t.includes('结果未知'), t); }
  await page.waitForTimeout(1500);
  assert.equal(S.credentialPosts, before + 1, '不自動重送');
  assert.equal(await page.evaluate(() => [...document.querySelectorAll('input[type=password]')].every((i) => i.value === '')), true);
  record('account_unknown_result_logs_out_without_automatic_retry');

  // ---------- 登出後的過期回應 ----------
  await login('ops_admin_3', 'Another-Password-77');
  await nav('account');
  await page.waitForFunction(() => document.getElementById('account-current-username').textContent === 'ops_admin_3');
  S.credentialReply = { status: 409, code: 'USERNAME_UNAVAILABLE', delay: 1200 };
  await fillAccount({ current: 'Another-Password-77', username: 'ops_admin_6' });
  await submitAccount();
  await page.locator('#logout-button').click();
  await page.locator('#login-view').waitFor({ state: 'visible' });
  const loggedOut = await page.locator('#login-error').textContent();
  await page.waitForTimeout(1800);
  assert.equal(await page.locator('#login-error').textContent(), loggedOut);
  assert.equal(await page.locator('#account-result').textContent(), '');
  assert.equal(await page.locator('#account-current-username').textContent(), '—');
  assert.equal(await page.locator('#sidebar').isVisible(), false);
  record('late_account_response_after_logout_does_not_pollute_state');

  // ---------- 390px ----------
  await page.close();
  await newPage(390, 844);
  await login('ops_admin_3', 'Another-Password-77');
  await page.waitForFunction(() => document.querySelector('#cl-overview').textContent.includes('external'));
  assert.equal(await page.locator('#sidebar').isVisible(), false);
  assert.ok(await noOverflow());
  await shot('overview-390.png');
  await page.locator('#nav-toggle').click(); await page.locator('#sidebar').waitFor({ state: 'visible' });
  await shot('drawer-390.png', false);
  await page.keyboard.press('Escape'); await page.locator('#sidebar').waitFor({ state: 'hidden' });
  for (const route of ROUTES) {
    await nav(route);
    await page.locator('#sidebar').waitFor({ state: 'hidden', timeout: 2000 });
    await page.locator('[data-page]:visible details').evaluateAll((list) => list.forEach((d) => { d.open = true; }));
    assert.ok(await noOverflow(), `390 ${route}`);
    await shot(`${route.replace('/', '-')}-390.png`);
  }
  record('mobile_390_drawer_navigation_all_routes_expanded_without_overflow');
  await page.locator('#nav-toggle').click(); await page.locator('#logout-button').click();
  await page.locator('#login-view').waitFor({ state: 'visible' });
  assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length <= 1 && !JSON.stringify(localStorage).includes('token')), true);
  record('mobile_logout_and_no_token_persistence');
  await page.close();

  // ---------- legacy 相容 ----------
  S.mode = 'legacy';
  await newPage(1440, 960, 'zh-Hant');
  await login('ops_admin_3', 'Another-Password-77');
  await page.locator('#console-view').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#cluster-view').isVisible(), false);
  for (const route of ['overview', 'deploy', 'sites', 'control', 'advanced/tasks', 'audit', 'account']) await nav(route);
  await page.waitForFunction(() => document.getElementById('account-current-username').textContent === 'ops_admin_3');
  await nav('overview');
  await shot('legacy-overview-1440.png');
  record('legacy_mode_keeps_two_site_ui_inside_new_shell');
  await page.close();

  assert.deepEqual(errors, []); assert.deepEqual(external, []);
  await writeFile(join(output, 'verification.json'), JSON.stringify({ status: 'PASS', scope: 'LOCAL_BROWSER_WITH_SIMULATED_CONTROL_API', productionDeployment: false, browser: browser.version(), steps, pageErrors: errors, externalRequests: external }, null, 2));
  console.log(JSON.stringify({ status: 'PASS', steps: steps.length }));
} catch (error) {
  if (page) try { await page.screenshot({ path: join(output, 'failure.png'), fullPage: true }); } catch {}
  await writeFile(join(output, 'verification.json'), JSON.stringify({ status: 'FAIL', error: String(error?.stack ?? error), steps, pageErrors: errors }, null, 2));
  throw error;
} finally {
  await browser.close();
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
