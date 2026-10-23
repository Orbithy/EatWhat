// 已安裝伺服器接入集群（空集群首個中控本機權威站）瀏覽器驗收：本機模擬 API。
// 模擬回應不是真實成員證明；不連線真實伺服器、不部署。
// 用法：EATWHAT_PLAYWRIGHT_MODULE=<existing-playwright-package> node scripts/verify-control-server-cluster-browser.mjs
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, join } from 'node:path';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { gotoRoute, withDrawer } from './control-nav.mjs';

const root = resolve('frontend/control');
const output = resolve('output/control-server-cluster-verification');
const { chromium } = createRequire(join(process.env.EATWHAT_PLAYWRIGHT_MODULE, 'package.json'))('playwright');

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const LOCAL = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10', REMOTE = '1e4c8d3b-0a52-4f7c-9b63-2d8f0a4e3c21';
const SITE_INSTANCE = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', CLUSTER = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const KEY = { publicKey: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAISynthetic', type: 'ssh-ed25519', fingerprint: 'SHA256:q7Lm2x0VfR9eKc3TzW8uN1bYhJ4sPa6dG5oE2iQkXwM' };
const CONN = { state: 'CONNECTED', checkedAt: '2026-10-17T10:05:00Z', linux: 'Debian GNU/Linux 13 · x86_64', python: '3.11.2', docker: '27.3.1', compose: 'v2.29.1', dockerAccess: true, root: true };

let S;
function freshState() {
  const installed = (id, name, location, host) => ({
    id, name, location, host, port: 22, username: 'root', authentication: 'PRIVATE_KEY', hasCredential: true, hostKey: KEY, revision: 2, connection: CONN,
    installation: { state: 'SUCCEEDED', siteId: `server-${name}`, instanceId: SITE_INSTANCE, httpPort: 18090, root: `/opt/eatwhat-sites/${id}`, restoreEvidenceId: 'restore-1', currentSha256: 'a'.repeat(64), appBaseUrl: 'https://campus.example.org', jobId: randomUUID(), clusterMembership: 'NOT_JOINED' },
    createdAt: '2026-10-17T09:00:00Z', updatedAt: '2026-10-17T09:40:00Z',
  });
  S = {
    enrollment: true, members: [], epoch: 0, workerCode: null,
    servers: new Map([[LOCAL, installed(LOCAL, 'campus-local', 'CONTROL_HOST', '127.0.0.1')], [REMOTE, installed(REMOTE, 'edge-remote', 'REMOTE', '203.0.113.24')]]),
    plans: new Map(), jobs: new Map(), clusterJobId: null,
    blockNext: null, dropExecute: false, restartAfterExecute: 0, restartUntil: 0, restartFlip: 0, unauthorizedJobGets: 0,
    jobScript: [], calls: [], executions: 0, reconciliations: 0, planBodies: [],
  };
}
freshState();

const leadership = () => ({ state: 'HELD', localLeader: true, generation: 1, expiresAt: '2026-10-17T12:00:15Z', localControllerId: A, leaderControllerId: A, handoffEnabled: false, handoff: { state: 'NONE' }, controllers: [{ id: A, name: 'campus-control', sharedState: true }] });
const listBody = () => ({ servers: [...S.servers.values()], canManage: true, reason: null, installationEnabled: true, clusterEnrollmentEnabled: S.enrollment, clusterJobId: S.clusterJobId, releases: [{ id: 'release_2026_10_17', sha256: 'a'.repeat(64) }] });

function joinSucceeded(job, { verified = true } = {}) {
  const server = S.servers.get(job.serverId);
  job.state = 'SUCCEEDED';
  job.stage = 'VERIFIED';
  job.result = verified
    ? { clusterMembership: 'ACTIVE', changeState: 'APPLIED', epoch: 1, siteId: server.installation.siteId, instanceId: SITE_INSTANCE, singleSiteOnly: true }
    : { clusterMembership: 'ACTIVE' };
  if (verified) {
    server.installation.clusterMembership = 'ACTIVE';
    server.revision += 1;
    S.epoch = 1;
    S.members = [{ site_id: server.installation.siteId, instance_id: SITE_INSTANCE, profile_id: 'p', mode: 'CAMPUS', allocation_slot: 0, state: 'ACTIVE', database_fingerprint: 'f'.repeat(32), joined_epoch: 1, retired_epoch: null }];
  }
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;
    let text = '';
    for await (const part of req) text += part;
    const body = text ? JSON.parse(text) : undefined;
    const send = (code, data) => { res.statusCode = code; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data)); };
    const drop = () => { res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': '200' }); res.write('{"id":'); setTimeout(() => req.socket.destroy(), 50); };
    if (p === '/auth/login') return send(200, { code: 0, data: { tokenName: 'satoken', tokenValue: `synthetic-${randomUUID()}` } });
    if (!p.startsWith('/admin/control/v1')) {
      const file = resolve(root, '.' + (p === '/' ? '/index.html' : p));
      assert.ok(file.startsWith(root + '/'));
      res.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[extname(file)] ?? 'application/octet-stream');
      return res.end(await readFile(file));
    }
    const api = p.slice('/admin/control/v1'.length);
    S.calls.push(`${req.method} ${api}`);
    // 中控重啟窗口：交替回 503、斷線、502。
    if (Date.now() < S.restartUntil) {
      const k = S.restartFlip++ % 3;
      if (k === 1) return drop();
      return send(k === 0 ? 503 : 502, { code: 'CONTROL_RESTARTING' });
    }
    if (api === '/cluster/capabilities') return send(200, { topology: 'SINGLE_CLUSTER', maximumActiveSites: 16, allocationSlots: 1024, coordinatorTransferEnabled: false, leadershipHandoffEnabled: false, businessSessionShared: true, deploymentExecutionEnabled: true, remoteFailureMatrixAccepted: false });
    if (api === '/cluster/leadership') return send(200, leadership());
    if (api === '/cluster') return send(200, { clusterId: CLUSTER, epoch: S.epoch, members: S.members, pendingChanges: [], worker: { running: true, lastCode: S.workerCode ?? (S.members.length ? 'SINGLE_SITE_ACTIVE' : 'AWAITING_FIRST_JOIN') }, leadership: leadership() });
    if (api === '/cluster/observations') return send(200, S.members.map((mb) => ({ member: { siteId: mb.site_id, instanceId: mb.instance_id, mode: 'CAMPUS', allocationSlot: 0 }, health: 'REACHABLE', node: { siteId: mb.site_id, instanceId: mb.instance_id, clusterId: CLUSTER, allocationSlot: 0, mode: 'CAMPUS', databaseFingerprint: mb.database_fingerprint, protocolVersion: 1, dataPlaneReady: true, membership: { epoch: S.epoch, state: 'ACTIVE', pending_change: null }, drain: { liveReferences: 0, localObjects: 0, pendingUploads: 0, unarchivedAccounts: 0 }, control: { siteId: mb.site_id, instanceId: mb.instance_id, mode: 'CAMPUS', observedAt: new Date().toISOString(), deploymentExecutionEnabled: false, observationStatus: 'OBSERVED', readiness: 'NOT_CHECKED', storageReachability: 'NOT_CHECKED', channels: {} } }, sync: { pendingDeliveries: 0, pendingInbox: 0, fileTransfers: 0 } })));
    if (api === '/account') return send(200, { username: 'rpc', canEdit: true, reason: null });
    if (api === '/servers' && req.method === 'GET') return send(200, listBody());
    let m;
    if ((m = api.match(/^\/servers\/([0-9a-f-]{36})\/cluster-plans$/))) {
      S.planBodies.push(body);
      const s = S.servers.get(m[1]);
      if (body.expectedRevision !== s.revision) return send(409, { code: 'SERVER_REVISION_CHANGED' });
      const blockers = S.blockNext ?? [];
      S.blockNext = null;
      const plan = { id: randomUUID(), kind: 'INITIAL_AUTHORITY', serverId: s.id, serverRevision: s.revision, state: blockers.length ? 'BLOCKED' : 'READY', blockers, expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(), clusterId: CLUSTER, siteId: s.installation.siteId, instanceId: SITE_INSTANCE, allocationSlot: 0, httpPort: 18090, databasePort: 25432, singleSiteOnly: true, controllerRestartExpected: true, originalDatabasePreserved: true, administratorUsername: 'rpc' };
      S.plans.set(plan.id, plan);
      return send(201, plan);
    }
    if ((m = api.match(/^\/server-cluster-plans\/([0-9a-f-]{36})$/))) return S.plans.has(m[1]) ? send(200, S.plans.get(m[1])) : send(404, { code: 'SERVER_PLAN_NOT_FOUND' });
    if ((m = api.match(/^\/server-cluster-plans\/([0-9a-f-]{36})\/execute$/))) {
      S.executions += 1;
      const plan = S.plans.get(m[1]);
      const job = S.jobs.get(plan.id) ?? { id: plan.id, kind: 'INITIAL_AUTHORITY', serverId: plan.serverId, state: 'RUNNING', stage: 'AUTHORITY_SETUP', controllerRestartExpected: true, singleSiteOnly: true, createdAt: new Date().toISOString() };
      S.jobs.set(job.id, job);
      S.clusterJobId = job.id;
      if (S.restartAfterExecute) { S.restartUntil = Date.now() + S.restartAfterExecute; S.restartAfterExecute = 0; }
      if (S.dropExecute) { S.dropExecute = false; return drop(); }
      return send(202, job);
    }
    if ((m = api.match(/^\/server-cluster-jobs\/([0-9a-f-]{36})$/))) {
      if (S.unauthorizedJobGets > 0) { S.unauthorizedJobGets -= 1; return send(401, { code: 'AUTHENTICATION_REQUIRED' }); }
      const job = S.jobs.get(m[1]);
      if (!job) return send(404, { code: 'AUTHORITY_JOB_NOT_FOUND' });
      const step = S.jobScript.shift();
      if (step === 'join') job.stage = 'MEMBERSHIP_JOIN';
      else if (step === 'succeed') joinSucceeded(job);
      else if (step === 'unknown') { job.state = 'OUTCOME_UNKNOWN'; job.stage = 'MEMBERSHIP_JOIN'; job.code = 'AUTHORITY_RECONCILIATION_REQUIRED'; }
      return send(200, job);
    }
    if ((m = api.match(/^\/server-cluster-jobs\/([0-9a-f-]{36})\/reconcile$/))) {
      S.reconciliations += 1;
      const job = S.jobs.get(m[1]);
      joinSucceeded(job);
      return send(200, job);
    }
    return send(200, []);
  } catch (error) { res.statusCode = 500; res.end('{}'); }
});

await new Promise((done) => server.listen(0, '127.0.0.1', done));
const origin = `http://127.0.0.1:${server.address().port}`;
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
const steps = [], errors = [], external = [];
const record = (name) => { steps.push(name); console.log(name); };
let page;

const noOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1);
const shot = async (name, full = true) => { await page.evaluate(() => window.scrollTo(0, 0)); return page.screenshot({ path: join(output, name), fullPage: full }); };
const text = (sel) => page.locator(sel).textContent();
const card = (name) => page.locator('#servers-list .server-card', { has: page.locator('.server-name', { hasText: new RegExp(`^${name}$`) }) });
const jobGets = () => S.calls.filter((c) => c.startsWith('GET /server-cluster-jobs/')).length;
async function login() {
  await page.locator('#login-username').fill('rpc');
  await page.locator('#login-password').fill('Synthetic-Password-1');
  await page.locator('#login-submit').click();
  await page.locator('#appbar').waitFor({ state: 'visible' });
}
async function newPage({ width = 1440, height = 960, locale = 'zh-Hant' } = {}) {
  page = await browser.newPage({ viewport: { width, height } });
  page.setDefaultTimeout(20000);
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('request', (r) => { if (!r.url().startsWith(origin + '/')) external.push(r.url()); });
  if (locale) await page.addInitScript((l) => localStorage.setItem('eatwhat.control.locale', l), locale);
  await page.goto(origin);
}
async function openJoin() {
  await card('campus-local').locator('button[data-action="cluster"]').click();
  await page.locator('#server-cluster-panel').waitFor({ state: 'visible' });
}
async function createPlan() {
  await page.locator('#server-cluster-plan-create').click();
  await page.locator('#server-cluster-plan-view .kv').waitFor();
}

try {
  await newPage();
  await login();
  await gotoRoute(page, 'servers');
  await card('campus-local').waitFor();

  // ---------- 入口與停用原因 ----------
  assert.equal(await card('campus-local').locator('button[data-action="cluster"]').isDisabled(), false);
  assert.match(await card('campus-local').locator('.status').textContent(), /尚未加入多站集群/);
  assert.equal(await card('edge-remote').locator('button[data-action="cluster"]').isDisabled(), true);
  assert.match(await card('edge-remote').locator('.enroll-reason').textContent(), /中控本機/);
  S.enrollment = false;
  await page.locator('#refresh-button').click();
  await page.waitForFunction(() => document.querySelector('#servers-list').textContent.includes('接入集群功能未啟用'));
  assert.equal(await card('campus-local').locator('button[data-action="cluster"]').isDisabled(), true);
  S.enrollment = true;
  await page.locator('#refresh-button').click();
  await page.waitForFunction(() => !document.querySelector('#servers-list').textContent.includes('接入集群功能未啟用'));
  await shot('servers-join-entry-1440.png');
  record('join_entry_only_for_installed_control_host_with_clear_disabled_reasons');

  // ---------- 阻擋條件 ----------
  await openJoin();
  S.blockNext = ['CLUSTER_ALREADY_INITIALIZED', 'FUTURE_BLOCKER_X'];
  await createPlan();
  const blocked = await text('#server-cluster-plan-view');
  assert.ok(blocked.includes('集群已有成員') && blocked.includes('FUTURE_BLOCKER_X'), '已知阻擋翻譯、未知阻擋保留原文');
  assert.equal(await page.locator('#server-cluster-execute').isDisabled(), true);
  assert.deepEqual(S.planBodies.at(-1), { expectedRevision: 2 });
  await shot('join-plan-blocked-1440.png');
  record('blocked_plan_shows_translated_and_unknown_blockers_and_cannot_execute');

  // ---------- 確認期間 revision 改變：不送出 ----------
  await createPlan();
  const plan = await text('#server-cluster-plan-view');
  for (const n of ['127.0.0.1:25432', '回環位址', '中控原本的資料庫也保留', 'rpc', '30–120 秒', '單站集群', '跨站同步尚未啟用']) assert.ok(plan.includes(n), n);
  assert.equal(await page.locator('#server-cluster-panel input[type="password"]').count(), 0, '不需要輸入密碼');
  await shot('join-plan-ready-1440.png');
  S.servers.get(LOCAL).revision += 1;
  await page.locator('#server-cluster-execute').click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  await page.locator('#dialog-confirm').click();
  await page.waitForFunction(() => document.getElementById('servers-result').textContent.includes('確認期間狀態已改變'));
  assert.match(await text('#servers-result'), /計畫失效/);
  assert.equal(S.executions, 0);
  record('stale_revision_during_confirmation_sends_nothing');

  // ---------- 雙擊、中斷的 POST、中控重啟與重新連線 ----------
  await createPlan();
  assert.equal(S.planBodies.at(-1).expectedRevision, 3);
  S.dropExecute = true;
  await page.locator('#server-cluster-execute').dblclick();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  const lines = await text('#dialog-lines');
  for (const n of ['首個權威站', '保留業務站與中控兩邊的原有資料與帳號', '127.0.0.1:25432', '短暫重啟', '單站集群', '不會自動重送']) assert.ok(lines.includes(n), n);
  // 執行回應斷線，之後模擬中控重啟約 9 秒。
  S.restartAfterExecute = 9000;
  await page.locator('#dialog-confirm').click();
  await page.locator('#server-cluster-reconnect').waitFor({ state: 'visible' });
  assert.match(await text('#server-cluster-reconnect'), /正在重新連線/);
  assert.match(await text('#server-cluster-reconnect'), /不會重新送出/);
  assert.equal(await page.locator('#sidebar').isVisible(), true, '重連不清除登入');
  assert.ok(!/FAILED|失敗/.test(await text('#server-cluster-job-view')), '重連時不假裝失敗');
  await shot('join-reconnecting-1440.png');
  assert.equal(S.executions, 1, '雙擊只送一次，斷線不重送');
  S.jobScript = ['join', 'join'];
  await page.waitForFunction(() => document.getElementById('server-cluster-job-view').textContent.includes('MEMBERSHIP_JOIN'), null, { timeout: 40000 });
  await page.locator('#server-cluster-reconnect').waitFor({ state: 'hidden' });
  assert.match(await text('#server-cluster-job-view'), /RUNNING/);
  record('double_click_and_interrupted_execute_send_once_then_reconnect_polling_recovers_without_failure');

  // ---------- 離開頁面停止輪詢，回來恢復 ----------
  await gotoRoute(page, 'overview');
  const before = jobGets();
  await page.waitForTimeout(9000);
  assert.equal(jobGets(), before, '離開頁面停止輪詢');
  S.jobScript = ['succeed'];
  await gotoRoute(page, 'servers');
  await page.waitForFunction(() => document.getElementById('server-cluster-job-view').textContent.includes('SUCCEEDED'), null, { timeout: 20000 });
  await card('campus-local').locator('.status', { hasText: '已加入集群' }).waitFor();
  assert.equal(await card('campus-local').locator('button[data-action="cluster"]').count(), 0);
  assert.equal(await card('campus-local').locator('a[href="#/sites"]').count() >= 1, true);
  assert.match(await text('#server-cluster-job-view'), /APPLIED／ACTIVE/);
  assert.match(await text('#servers-result'), /已加入集群（後端已核對 APPLIED／ACTIVE；本次為單站集群/);
  assert.ok(!/單站/.test(await card('campus-local').locator('.status').textContent()), '卡片只寫已加入集群');
  assert.equal(S.executions, 1);
  await gotoRoute(page, 'deploy');
  await page.waitForFunction(() => document.getElementById('cl-deploy-guide').hidden);
  await gotoRoute(page, 'servers');
  await shot('servers-joined-1440.png');
  record('leaving_page_pauses_polling_and_only_verified_applied_active_shows_joined');

  // ---------- 單站集群 SINGLE_SITE_ACTIVE：總覽零誤報；真正錯誤碼仍告警 ----------
  await gotoRoute(page, 'overview');
  await page.locator('#refresh-button').click();
  await page.waitForFunction(() => { const t = document.getElementById('cl-overview').textContent; return t.includes('目前沒有待處理問題') && t.includes('單站集群運行中'); }, null, { timeout: 30000 });
  const home = await text('#cl-overview');
  assert.ok(!home.includes('worker 回報錯誤碼'), '單站運行不列為待處理問題');
  assert.match(home, /待處理問題（0）/);
  assert.equal(await page.locator('#nav-issues').isVisible(), false, '側欄沒有問題徽章');
  assert.equal(await page.locator('#cl-overview .home-worker .status-teal').count(), 1);
  await shot('overview-single-site-active-1440.png');
  S.workerCode = 'CLUSTER_CYCLE_RETRY';
  await page.locator('#refresh-button').click();
  await page.waitForFunction(() => document.getElementById('cl-overview').textContent.includes('worker 回報錯誤碼'), null, { timeout: 30000 });
  assert.match(await text('#cl-overview'), /CLUSTER_CYCLE_RETRY/);
  assert.equal(await page.locator('#nav-issues').isVisible(), true, '真正錯誤仍有徽章');
  assert.equal(await page.locator('#cl-overview .home-worker .status-amber').count(), 1);
  await shot('overview-worker-error-1440.png');
  S.workerCode = null;
  await page.locator('#refresh-button').click();
  await page.waitForFunction(() => document.getElementById('cl-overview').textContent.includes('目前沒有待處理問題'), null, { timeout: 30000 });
  await gotoRoute(page, 'servers');
  record('single_site_active_worker_is_normal_on_overview_while_real_error_codes_still_warn');
  await page.close();

  // ---------- 401 → 重新登入 → 以清單 clusterJobId 查回 → 結果未知核對 ----------
  freshState();
  await newPage({ locale: null });
  await login();
  await gotoRoute(page, 'servers');
  await openJoin();
  await createPlan();
  await page.locator('#server-cluster-execute').click();
  await page.locator('#dialog-confirm').click();
  await page.waitForFunction(() => document.getElementById('server-cluster-job-view').textContent.includes('RUNNING'));
  S.unauthorizedJobGets = 1;
  S.jobScript = ['unknown'];
  await page.locator('#login-view').waitFor({ state: 'visible', timeout: 20000 });
  assert.equal(await page.locator('#server-cluster-job-view').isVisible(), false);
  await login();
  await gotoRoute(page, 'servers');
  await page.waitForFunction(() => document.getElementById('server-cluster-job-view').textContent.includes('OUTCOME_UNKNOWN'));
  assert.equal(await page.locator('#server-cluster-lookup-id').inputValue(), S.clusterJobId, '重新登入後以清單 UUID 查回');
  assert.equal(S.executions, 1);
  await page.locator('#server-cluster-job-reconcile').waitFor({ state: 'visible' });
  await shot('join-unknown-zh-Hans-1440.png');
  await page.locator('#server-cluster-job-reconcile').click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  assert.match(await text('#dialog-lines'), /不重新安装、不重新切换数据库/);
  await page.locator('#dialog-confirm').click();
  await card('campus-local').locator('.status', { hasText: '已加入集群' }).waitFor();
  assert.equal(S.reconciliations, 1);
  assert.equal(S.executions, 1);
  record('unauthorized_poll_relogs_in_recovers_job_by_list_uuid_and_reconciles_unknown_without_resend');

  // ---------- 已完成但未核對、找不到任務 ----------
  const unverified = randomUUID();
  S.jobs.set(unverified, { id: unverified, kind: 'INITIAL_AUTHORITY', serverId: REMOTE, state: 'SUCCEEDED', stage: 'VERIFIED', result: { clusterMembership: 'ACTIVE' }, createdAt: new Date().toISOString() });
  await page.locator('#server-cluster-lookup-id').fill(unverified);
  await page.locator('#server-cluster-lookup-submit').click();
  await page.waitForFunction(() => document.getElementById('server-cluster-job-view').textContent.includes('没有明确回报'));
  assert.match(await card('edge-remote').locator('.status').textContent(), /尚未加入多站集群/);
  await page.locator('#server-cluster-lookup-id').fill(randomUUID());
  await page.locator('#server-cluster-lookup-submit').click();
  await page.waitForFunction(() => document.getElementById('servers-result').textContent.includes('找不到这个接入任务'));
  record('unverified_success_is_not_joined_and_unknown_uuid_lookup_reports_not_found');

  // ---------- 登出取消輪詢、舊回應不污染 ----------
  await withDrawer(page, () => page.locator('#logout-button').click());
  await page.locator('#login-view').waitFor({ state: 'visible' });
  const afterLogout = jobGets();
  await page.waitForTimeout(5000);
  assert.equal(jobGets(), afterLogout, '登出後不再輪詢');
  assert.equal(await page.locator('#server-cluster-job-view').textContent(), '');
  assert.equal(await page.evaluate(() => sessionStorage.length), 0);
  assert.equal(await page.evaluate(() => Object.keys(localStorage).join(',')), '', '沒有寫入任務或權杖');
  record('logout_cancels_polling_and_clears_job_state_without_storage');
  await page.close();

  // ---------- 390px ----------
  freshState();
  await newPage({ width: 390, height: 844 });
  await login();
  await gotoRoute(page, 'servers');
  await card('campus-local').waitFor();
  assert.ok(await noOverflow());
  await shot('servers-join-entry-390.png');
  await openJoin();
  await createPlan();
  assert.ok(await noOverflow());
  await shot('join-plan-390.png');
  S.restartAfterExecute = 6000;
  await page.locator('#server-cluster-execute').click();
  await page.locator('#dialog-confirm').click();
  await page.locator('#server-cluster-reconnect').waitFor({ state: 'visible' });
  assert.ok(await noOverflow());
  await shot('join-reconnecting-390.png');
  S.jobScript = ['succeed'];
  await card('campus-local').locator('.status', { hasText: '已加入集群' }).waitFor({ timeout: 40000 });
  assert.ok(await noOverflow());
  await shot('servers-joined-390.png');
  record('mobile_390_entry_plan_reconnect_and_joined_without_overflow');
  await page.close();

  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  await writeFile(join(output, 'verification.json'), JSON.stringify({ status: 'PASS', scope: 'LOCAL_BROWSER_WITH_SIMULATED_SERVER_CLUSTER_API', membershipProof: 'NONE_SIMULATED_RESPONSES', realServers: false, productionDeployment: false, browser: browser.version(), steps, pageErrors: errors, externalRequests: external }, null, 2));
  console.log(JSON.stringify({ status: 'PASS', steps: steps.length }));
} catch (error) {
  if (page && !page.isClosed()) try { await page.screenshot({ path: join(output, 'failure.png'), fullPage: true }); } catch {}
  await writeFile(join(output, 'verification.json'), JSON.stringify({ status: 'FAIL', error: String(error?.stack ?? error), steps, pageErrors: errors }, null, 2));
  throw error;
} finally {
  await browser.close();
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
