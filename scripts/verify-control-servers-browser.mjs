// 伺服器（SSH 登記、首次安裝、版本更新）瀏覽器驗收：本機模擬 API，不連線真實主機、不部署。
// 用法：EATWHAT_PLAYWRIGHT_MODULE=<existing-playwright-package> node scripts/verify-control-servers-browser.mjs
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, join } from 'node:path';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { gotoRoute, withDrawer } from './control-nav.mjs';

const root = resolve('frontend/control');
const output = resolve('output/control-servers-verification');
const { chromium } = createRequire(join(process.env.EATWHAT_PLAYWRIGHT_MODULE, 'package.json'))('playwright');

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const SHA = 'a'.repeat(64), SHA2 = 'b'.repeat(64);
const KEYS = [
  { publicKey: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAISyntheticEd25519', type: 'ssh-ed25519', fingerprint: 'SHA256:q7Lm2x0VfR9eKc3TzW8uN1bYhJ4sPa6dG5oE2iQkXwM' },
  { publicKey: 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABSyntheticRsa', type: 'ssh-rsa', fingerprint: 'SHA256:Zt4cH8yQm1Lw0VnB7rE3xK9pS2aJ6fD5uG1oN8iWkTe' },
];
const CONNECTED = { state: 'CONNECTED', checkedAt: '2026-10-17T10:05:00Z', linux: 'Debian GNU/Linux 13 · x86_64', python: '3.11.2', docker: '27.3.1', compose: 'v2.29.1', dockerAccess: true, root: true, memoryMiB: 7936, diskFreeMiB: 51200 };

// ---------- 模擬狀態 ----------
const S = {
  localLeader: true, canManage: true, installationEnabled: true,
  servers: new Map(), plans: new Map(), jobs: new Map(),
  reply: {}, // 依操作覆寫下一個回應：{ status, code } 或 { drop: true }
  delayList: 0, listStatus: null,
  calls: [], bodies: {}, executions: 0, reconciliations: 0,
};
const leadership = () => ({ state: 'HELD', localLeader: S.localLeader, generation: 1, expiresAt: '2026-10-17T12:00:15Z', localControllerId: A, leaderControllerId: S.localLeader ? A : B, handoffEnabled: false, handoff: { state: 'NONE' }, controllers: [{ id: A, name: 'campus-control-a', sharedState: true }] });
const caps = () => ({ topology: 'SINGLE_CLUSTER', maximumActiveSites: 16, allocationSlots: 1024, coordinatorTransferEnabled: false, leadershipHandoffEnabled: false, businessSessionShared: true, deploymentExecutionEnabled: true, remoteFailureMatrixAccepted: false });
const listBody = () => ({
  servers: [...S.servers.values()], canManage: S.canManage && S.localLeader,
  reason: !S.localLeader ? 'CONTROL_LEASE_NOT_HELD' : S.canManage ? null : 'SERVER_AUTHORITY_REQUIRED',
  installationEnabled: S.installationEnabled,
  releases: [{ id: 'release_2026_10_17', sha256: SHA, acceptanceEvidenceId: 'reviewed-1' }, { id: 'release_2026_10_24', sha256: SHA2, acceptanceEvidenceId: 'reviewed-2' }],
});
const take = (op) => { const r = S.reply[op]; delete S.reply[op]; return r; };

function makePlan(server, kind, body) {
  const id = randomUUID();
  const inst = server.installation;
  const release = listBody().releases.find((r) => r.id === body.releaseId);
  const plan = {
    id, kind, serverId: server.id, serverRevision: server.revision, releaseId: body.releaseId, artifactSha256: release.sha256,
    httpPort: kind === 'UPDATE' ? inst.httpPort : body.httpPort, appBaseUrl: kind === 'UPDATE' ? inst.appBaseUrl : body.appBaseUrl,
    root: `/opt/eatwhat-sites/${server.id}`, services: ['app', 'postgres', 'redis'], sameMachine: server.location === 'CONTROL_HOST',
    clusterMembership: 'NOT_JOINED', expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(), installDocker: body.installDocker,
    state: 'READY', blockers: [], storage: { endpoint: 'http://10.0.0.12:9000', bucket: 'eatwhat' },
    ...(kind === 'INSTALL' ? { administratorUsername: 'ops_admin' } : { fromSha256: inst.currentSha256 }),
  };
  if (kind === 'INSTALL' && server.connection.root === false) { plan.state = 'BLOCKED'; plan.blockers = ['FIRST_INSTALL_ROOT_REQUIRED']; }
  S.plans.set(id, plan);
  return plan;
}

// outcome：retryable＝資料庫啟動前失敗並清理；afterDatabase＝資料庫啟動後失敗、保留資料。
function failJob(job) {
  const server = S.servers.get(job.serverId);
  job.state = 'FAILED';
  job.code = 'SSH_INSPECTION_FAILED';
  if (job.outcome === 'retryable') {
    job.result = { stage: 'IMAGES', retryable: true };
    server.installation = { state: 'NOT_INSTALLED' };
  } else {
    job.result = { stage: 'APPLICATION_START', retryable: false };
    server.installation = { state: 'FAILED', root: `/opt/eatwhat-sites/${server.id}`, jobId: job.id, clusterMembership: 'NOT_JOINED' };
  }
  server.revision += 1;
}

function finishJob(job) {
  job.state = 'SUCCEEDED';
  const server = S.servers.get(job.serverId);
  const plan = S.plans.get(job.id);
  job.result = { restoreEvidenceId: 'restore-evidence-1', siteId: 'site_new', instanceId: B };
  server.installation = { state: 'SUCCEEDED', siteId: 'site_new', instanceId: B, httpPort: plan.httpPort, root: plan.root, restoreEvidenceId: 'restore-evidence-1', currentSha256: plan.artifactSha256, appBaseUrl: plan.appBaseUrl, jobId: job.id, clusterMembership: 'NOT_JOINED' };
  server.revision += 1;
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;
    let text = '';
    for await (const part of req) text += part;
    const body = text ? JSON.parse(text) : undefined;
    const send = (code, data) => { res.statusCode = code; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data)); };
    // 回應中途斷線：瀏覽器不會自行重試，前端得到網路錯誤（結果未知）。
    const drop = () => { res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': '200' }); res.write('{"id":'); setTimeout(() => req.socket.destroy(), 50); };
    if (p === '/auth/login') return send(200, { code: 0, data: { tokenName: 'satoken', tokenValue: 'synthetic-servers-token' } });
    if (!p.startsWith('/admin/control/v1')) {
      const file = resolve(root, '.' + (p === '/' ? '/index.html' : p));
      assert.ok(file.startsWith(root + '/'));
      res.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[extname(file)] ?? 'application/octet-stream');
      return res.end(await readFile(file));
    }
    const api = p.slice('/admin/control/v1'.length);
    S.calls.push(`${req.method} ${api}${url.search}`);
    if (body !== undefined) (S.bodies[`${req.method} ${api.replace(/[0-9a-f-]{36}/g, ':id')}`] ??= []).push(body);
    if (api === '/cluster/capabilities') return send(200, caps());
    if (api === '/cluster/leadership') return send(200, leadership());
    if (api === '/cluster') return send(200, { clusterId: A, epoch: 1, members: [], pendingChanges: [], worker: { running: true, lastCode: 'AWAITING_FIRST_JOIN' }, leadership: leadership() });
    if (api === '/account') return send(200, { username: 'ops_admin', canEdit: S.localLeader, reason: S.localLeader ? null : 'CONTROL_LEASE_NOT_HELD' });
    if (api === '/servers' && req.method === 'GET') {
      if (S.delayList) await new Promise((r) => setTimeout(r, S.delayList));
      if (S.listStatus) { const st = S.listStatus; S.listStatus = null; return send(st, { code: st === 401 ? 'AUTHENTICATION_REQUIRED' : 'SERVER_PRIVATE_STATE_UNAVAILABLE' }); }
      return send(200, listBody());
    }
    if (api === '/servers/host-key') return send(200, { keys: KEYS });
    if (api === '/servers' && req.method === 'POST') {
      const r = take('create');
      if (r?.status) return send(r.status, { code: r.code });
      const created = { id: body.requestId, name: body.name, location: body.location, host: body.host, port: body.port, username: body.username, authentication: body.authentication, hasCredential: true, hostKey: KEYS.find((k) => k.publicKey === body.hostKey), revision: 1, connection: { state: 'NOT_TESTED' }, installation: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
      if (!S.servers.has(body.requestId)) S.servers.set(body.requestId, created);
      if (r?.drop) return drop();
      return send(201, S.servers.get(body.requestId));
    }
    let m;
    if ((m = api.match(/^\/servers\/([0-9a-f-]{36})$/))) {
      const s = S.servers.get(m[1]);
      if (!s) return send(404, { code: 'SERVER_NOT_FOUND' });
      if (req.method === 'PUT') {
        if (body.expectedRevision !== s.revision) return send(409, { code: 'SERVER_REVISION_CHANGED' });
        Object.assign(s, { name: body.name, username: body.username, authentication: body.authentication, hostKey: KEYS.find((k) => k.publicKey === body.hostKey) ?? s.hostKey, revision: s.revision + 1 });
        return send(200, s);
      }
      if (req.method === 'DELETE') {
        if (Number(url.searchParams.get('expectedRevision')) !== s.revision) return send(409, { code: 'SERVER_REVISION_CHANGED' });
        S.servers.delete(s.id);
        return send(200, { removed: true, remoteDataDeleted: false });
      }
    }
    if ((m = api.match(/^\/servers\/([0-9a-f-]{36})\/test$/))) {
      const s = S.servers.get(m[1]);
      s.connection = { ...CONNECTED, root: s.username === 'root' };
      return send(200, s);
    }
    if ((m = api.match(/^\/servers\/([0-9a-f-]{36})\/(installation|update)-plans$/))) {
      const s = S.servers.get(m[1]);
      if (body.expectedRevision !== s.revision) return send(409, { code: 'SERVER_REVISION_CHANGED' });
      return send(201, makePlan(s, m[2] === 'installation' ? 'INSTALL' : 'UPDATE', body));
    }
    if ((m = api.match(/^\/server-plans\/([0-9a-f-]{36})$/))) return send(200, S.plans.get(m[1]));
    if ((m = api.match(/^\/server-plans\/([0-9a-f-]{36})\/execute$/))) {
      S.executions += 1;
      const plan = S.plans.get(m[1]);
      const r = take('execute');
      const job = S.jobs.get(plan.id) ?? { id: plan.id, serverId: plan.serverId, kind: plan.kind, state: r?.drop ? 'OUTCOME_UNKNOWN' : 'RUNNING', createdAt: new Date().toISOString(), artifactSha256: plan.artifactSha256, polls: 0, outcome: r?.outcome };
      S.jobs.set(job.id, job);
      if (r?.drop) return drop();
      return send(202, job);
    }
    if ((m = api.match(/^\/server-jobs\/([0-9a-f-]{36})$/))) {
      const job = S.jobs.get(m[1]);
      if (!job) return send(404, { code: 'SERVER_JOB_NOT_FOUND' });
      if (job.state === 'RUNNING' && ++job.polls >= 1) (job.outcome ? failJob : finishJob)(job);
      return send(200, job);
    }
    if ((m = api.match(/^\/server-jobs\/([0-9a-f-]{36})\/reconcile$/))) {
      S.reconciliations += 1;
      const job = S.jobs.get(m[1]);
      finishJob(job);
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
const passwordsEmpty = () => page.evaluate(() => document.getElementById('server-password').value === '' && document.getElementById('server-private-key').value === '');
const card = (name) => page.locator('#servers-list .server-card', { has: page.locator('.server-name', { hasText: new RegExp(`^${name}$`) }) });
async function login() {
  await page.locator('#login-username').fill('ops_admin');
  await page.locator('#login-password').fill('Synthetic-Password-1');
  await page.locator('#login-submit').click();
  await page.locator('#appbar').waitFor({ state: 'visible' });
}
async function newPage(width = 1440, height = 960) {
  page = await browser.newPage({ viewport: { width, height } });
  page.setDefaultTimeout(15000);
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('request', (r) => { if (!r.url().startsWith(origin + '/')) external.push(r.url()); });
  await page.addInitScript(() => localStorage.setItem('eatwhat.control.locale', 'zh-Hant'));
  await page.goto(origin);
}
async function confirm({ expectInLines = [], notInLines = [] } = {}) {
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  const lines = await text('#dialog-lines');
  for (const n of expectInLines) assert.ok(lines.includes(n), `確認框缺少：${n}\n${lines}`);
  for (const n of notInLines) assert.ok(!lines.includes(n), `確認框不應包含：${n}`);
  await page.locator('#dialog-confirm').click();
  return lines;
}
async function fillServer({ name, host, port = '22', user = 'root', password = 'Synthetic SSH pass 1' }) {
  await page.locator('#server-name').fill(name);
  if (host !== undefined) await page.locator('#server-host').fill(host);
  await page.locator('#server-port').fill(port);
  await page.locator('#server-username').fill(user);
  if (password !== null) await page.locator('#server-password').fill(password);
}
async function confirmHostKey(index = 0) {
  await page.locator('#server-hostkey-fetch').click();
  await page.locator('#server-hostkeys input[name="server-hostkey"]').first().waitFor();
  await page.locator('#server-hostkeys input[name="server-hostkey"]').nth(index).check();
  assert.equal(await page.locator('#server-save').isDisabled(), true, '未勾選確認前不能儲存');
  await page.locator('#server-hostkey-confirm').check();
}

try {
  await newPage();
  await login();

  // ---------- 部署頁導引與空狀態 ----------
  await gotoRoute(page, 'deploy');
  await page.locator('#cl-deploy-guide').waitFor({ state: 'visible' });
  assert.match(await text('#cl-deploy-guide'), /添加 SSH 伺服器/);
  await shot('deploy-guide-1440.png');
  await page.locator('#cl-deploy-guide a.btn').click();
  await page.waitForFunction(() => location.hash === '#/servers');
  await page.locator('#servers-list .guide').waitFor();
  assert.match(await text('#servers-list'), /尚未登記任何伺服器/);
  assert.ok(S.calls.includes('GET /servers'), '登入後進入頁面才讀取');
  await shot('servers-empty-1440.png');
  record('deploy_page_guides_to_servers_and_explicit_empty_state');

  // ---------- 同機提示與離開清空憑據 ----------
  await page.locator('#server-add').click();
  await page.locator('#server-form-panel').waitFor({ state: 'visible' });
  await page.locator('input[name="server-location"][value="CONTROL_HOST"]').check();
  assert.equal(await page.locator('#server-host').inputValue(), '127.0.0.1');
  assert.equal(await page.locator('#server-host').getAttribute('readonly'), '');
  assert.match(await text('#server-local-note'), /並非你正在開啟網頁的這台電腦/);
  assert.match(await text('#server-local-note'), /仍需要執行 SSH 服務/);
  await page.locator('#server-password').fill('typed-but-leaving');
  await shot('server-add-local-1440.png');
  await gotoRoute(page, 'overview');
  await gotoRoute(page, 'servers');
  assert.equal(await passwordsEmpty(), true, '離開頁面清空憑據');
  await page.locator('input[name="server-auth"][value="PRIVATE_KEY"]').check();
  await page.locator('#server-private-key').fill('-----BEGIN OPENSSH PRIVATE KEY-----\nsynthetic\n-----END OPENSSH PRIVATE KEY-----');
  await page.locator('#server-form-cancel').click();
  assert.equal(await passwordsEmpty(), true, '取消清空憑據');
  record('control_host_prefills_loopback_with_explanation_and_leaving_or_cancel_clears_credentials');

  // ---------- 新增：指紋確認、錯誤、結果未知 ----------
  await page.locator('#server-add').click();
  await fillServer({ name: 'edge-hk', host: '203.0.113.24', port: '2222' });
  assert.equal(await page.locator('#server-save').isDisabled(), true, '未取得指紋不能儲存');
  await confirmHostKey(0);
  await page.locator('#server-port').fill('2223');
  assert.equal(await page.locator('#server-hostkey-confirm-field').isVisible(), false, '改埠後指紋失效');
  assert.equal(await page.locator('#server-save').isDisabled(), true);
  await page.locator('#server-port').fill('2222');
  await confirmHostKey(0);
  await shot('server-add-hostkey-1440.png');
  S.reply.create = { status: 409, code: 'SSH_SERVER_ALREADY_REGISTERED' };
  await page.locator('#server-save').click();
  await confirm({ expectInLines: ['root@203.0.113.24:2222', KEYS[0].fingerprint, '內容不顯示'], notInLines: ['Synthetic SSH pass 1'] });
  await page.waitForFunction(() => document.getElementById('servers-result').textContent.includes('已登記過'));
  assert.equal(await passwordsEmpty(), true, '送出後清空憑據');
  assert.equal(await page.locator('#sidebar').isVisible(), true, '409 不登出');
  // 結果未知：伺服器實際已建立；重新讀取後以 requestId 找回，不重送。
  await page.locator('#server-password').fill('Synthetic SSH pass 1');
  S.reply.create = { drop: true };
  const posts = S.calls.filter((c) => c === 'POST /servers').length;
  await page.locator('#server-save').click();
  await confirm();
  await page.waitForFunction(() => document.getElementById('servers-result').textContent.includes('確認登記已存在'));
  assert.equal(S.calls.filter((c) => c === 'POST /servers').length, posts + 1, '只送一次');
  const created = S.bodies['POST /servers'];
  assert.equal(created.at(-1).requestId, created.at(-2).requestId, '相同內容沿用同一 requestId');
  assert.deepEqual(Object.keys(created.at(-1)).sort(), ['authentication', 'confirmedFingerprint', 'credential', 'host', 'hostKey', 'location', 'name', 'port', 'requestId', 'username']);
  assert.equal(created.at(-1).confirmedFingerprint, KEYS[0].fingerprint);
  await card('edge-hk').waitFor();
  record('add_requires_confirmed_host_key_rejections_keep_session_and_unknown_result_recovered_by_request_id');

  // ---------- 連線檢測 ----------
  await card('edge-hk').locator('button[data-action="test"]').click();
  await page.waitForFunction(() => document.getElementById('servers-result').textContent.includes('檢測完成'));
  const readiness = await card('edge-hk').locator('.readiness').textContent();
  for (const n of ['Debian GNU/Linux 13', '3.11.2', 'v2.29.1', '具 root 權限', '可存取 Docker']) assert.ok(readiness.includes(n), n);
  assert.deepEqual(S.bodies['POST /servers/:id/test'].at(-1), { expectedRevision: 1 });
  record('connection_test_shows_linux_python_compose_root_and_docker_access');

  // ---------- 首次安裝計畫、確認、任務 ----------
  await card('edge-hk').locator('button[data-action="install"]').click();
  await page.locator('#server-plan-panel').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#server-plan-port').inputValue(), '18090');
  assert.equal(await page.locator('#server-plan-docker').isChecked(), false);
  await page.locator('#server-plan-release').selectOption('release_2026_10_17');
  await page.locator('#server-plan-url').fill('https://shop.example.org');
  await page.locator('#server-plan-port').fill('18080');
  await page.waitForFunction(() => document.getElementById('server-plan-problems').textContent.includes('18080'));
  assert.equal(await page.locator('#server-plan-create').isDisabled(), true);
  await page.locator('#server-plan-port').fill('18090');
  await page.locator('#server-plan-create').click();
  await page.locator('#server-plan-view .kv').waitFor();
  const planText = await text('#server-plan-view');
  const serverId = [...S.servers.keys()][0];
  for (const n of [`/opt/eatwhat-sites/${serverId}`, '127.0.0.1:18090', 'Nginx', 'ops_admin', '各自獨立變更', '既有 RustFS', '不公開主機埠', '尚未加入多站集群', '空目錄也不覆寫']) assert.ok(planText.includes(n), n);
  assert.deepEqual(S.bodies['POST /servers/:id/installation-plans'].at(-1), { expectedRevision: 1, releaseId: 'release_2026_10_17', httpPort: 18090, appBaseUrl: 'https://shop.example.org', installDocker: false });
  await shot('server-install-plan-1440.png');
  await page.locator('#server-plan-execute').click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  await page.locator('#dialog-cancel').click();
  assert.equal(S.executions, 0, '取消不送出');
  await page.locator('#server-plan-execute').click();
  await confirm({ expectInLines: ['首次安裝', '必須不存在', '127.0.0.1:18090', 'PostgreSQL 與 Redis 不公開主機埠', '既有 RustFS', 'ops_admin', '不自動重送'] });
  await page.waitForFunction(() => document.getElementById('server-job-view').textContent.includes('SUCCEEDED'), null, { timeout: 20000 });
  assert.equal(S.executions, 1);
  const jobText = await text('#server-job-view');
  assert.ok(jobText.includes('尚未加入多站集群') && jobText.includes('restore-evidence-1'));
  assert.ok(!/步驟|%|百分比/.test(jobText), '任務只有總體狀態');
  await shot('server-install-job-1440.png');
  await page.locator('#server-plan-cancel').click();
  await card('edge-hk').locator('.install-block').waitFor();
  assert.match(await card('edge-hk').textContent(), /尚未加入多站集群/);
  assert.ok(!/ACTIVE/.test(await card('edge-hk').locator('.status').textContent()));
  record('install_plan_confirm_execute_and_overall_job_status_until_installed_not_joined');

  // ---------- 真實更新入口；執行結果未知 → 以計畫 UUID 查回 → 核對 ----------
  const updateBtn = card('edge-hk').locator('button[data-action="update"]');
  assert.equal(await updateBtn.isDisabled(), false, '更新入口可用');
  await updateBtn.click();
  // 版本更新沿用安裝時的埠與網址：欄位顯示為唯讀，不能只改畫面上的登記值。
  assert.equal(await page.locator('#server-plan-port').inputValue(), '18090');
  assert.equal(await page.locator('#server-plan-port').getAttribute('readonly'), '');
  assert.equal(await page.locator('#server-plan-url').getAttribute('readonly'), '');
  assert.equal(await page.locator('#server-plan-docker').isVisible(), false);
  assert.match(await text('#server-plan-update-note'), /不會改動業務 HTTP 埠與對外網址/);
  await page.locator('#server-plan-release').selectOption('release_2026_10_24');
  await page.locator('#server-plan-create').click();
  await page.locator('#server-plan-view .kv').waitFor();
  assert.deepEqual(S.bodies['POST /servers/:id/update-plans'].at(-1), { expectedRevision: 2, releaseId: 'release_2026_10_24', httpPort: 18090, appBaseUrl: 'https://shop.example.org', installDocker: false });
  S.reply.execute = { drop: true };
  await page.locator('#server-plan-execute').click();
  await confirm({ expectInLines: ['版本更新'] });
  await page.waitForFunction(() => document.getElementById('server-job-view').textContent.includes('OUTCOME_UNKNOWN'));
  assert.equal(S.executions, 2, '結果未知不重送');
  assert.ok(S.calls.some((c) => /^GET \/server-jobs\//.test(c)), '以原計畫 UUID 查詢任務');
  await page.locator('#server-job-reconcile').waitFor({ state: 'visible' });
  await shot('server-update-unknown-1440.png');
  await page.locator('#server-job-reconcile').click();
  await confirm({ expectInLines: ['不重新安裝或更新'] });
  await page.waitForFunction(() => document.getElementById('server-job-view').textContent.includes('SUCCEEDED'));
  assert.equal(S.reconciliations, 1);
  assert.equal(S.executions, 2);
  await page.locator('#server-plan-cancel').click();
  record('real_update_entry_unknown_execute_looked_up_by_plan_uuid_and_reconciled_without_resend');

  // ---------- 編輯：留空保留憑據；已安裝不可改位址；重新核對指紋 ----------
  await card('edge-hk').locator('button[data-action="edit"]').click();
  assert.equal(await page.locator('#server-password').inputValue(), '');
  assert.equal(await page.locator('#server-password').getAttribute('placeholder'), '留空則保留原密碼');
  assert.equal(await page.locator('#server-host').isDisabled(), true, '已安裝不能改主機');
  assert.equal(await page.locator('#server-port').isDisabled(), true);
  await page.locator('#server-name').fill('edge-hk-main');
  await page.locator('#server-hostkey-fetch').click();
  await page.locator('#server-hostkeys input').first().waitFor();
  assert.equal(await page.locator('#server-save').isDisabled(), true, '重新核對時需要確認');
  await page.locator('#server-hostkeys input').nth(0).check();
  await page.locator('#server-hostkey-confirm').check();
  await shot('server-edit-1440.png');
  await page.locator('#server-save').click();
  await confirm({ expectInLines: ['保留原憑據'] });
  await card('edge-hk-main').waitFor();
  const put = S.bodies['PUT /servers/:id'].at(-1);
  assert.ok(!('credential' in put), '留空不送憑據');
  assert.equal(put.expectedRevision, 3);
  assert.equal(put.host, '203.0.113.24');
  record('edit_keeps_credentials_when_blank_installed_address_locked_and_host_key_reconfirmed');

  // ---------- 同機登記、移除只刪登記 ----------
  await page.locator('#server-add').click();
  await page.locator('input[name="server-location"][value="CONTROL_HOST"]').check();
  await fillServer({ name: 'campus-local' });
  await confirmHostKey(1);
  await page.locator('#server-save').click();
  await confirm({ expectInLines: ['中控本機（運行中控的伺服器，不是開啟網頁的電腦）', 'root@127.0.0.1:22'] });
  await card('campus-local').waitFor();
  assert.equal(S.bodies['POST /servers'].at(-1).host, '127.0.0.1');
  // 首次安裝失敗：資料庫啟動前（清理後可重新計畫）與資料庫啟動後（保留 FAILED）。
  async function installOnce(outcome) {
    await card('campus-local').locator('button[data-action="install"]').click();
    await page.locator('#server-plan-release').selectOption('release_2026_10_17');
    await page.locator('#server-plan-url').fill('https://campus.example.org');
    await page.locator('#server-plan-port').fill('18091');
    await page.locator('#server-plan-create').click();
    await page.locator('#server-plan-view .kv').waitFor();
    assert.match(await text('#server-plan-view'), /與中控在同一台伺服器/);
    S.reply.execute = { outcome };
    await page.locator('#server-plan-execute').click();
    await confirm();
    const stage = outcome === 'retryable' ? 'IMAGES' : 'APPLICATION_START';
    await page.waitForFunction((st) => { const t = document.getElementById('server-job-view').textContent; return t.includes('FAILED') && t.includes(st); }, stage, { timeout: 20000 });
    return text('#server-job-view');
  }
  await card('campus-local').locator('button[data-action="test"]').click();
  await page.waitForFunction(() => document.getElementById('servers-result').textContent.includes('檢測完成'));
  let failed = await installOnce('retryable');
  assert.ok(failed.includes('映像') && failed.includes('IMAGES'), '顯示階段明細');
  assert.ok(failed.includes('已清理本任務新建的檔案'));
  assert.match(await text('#servers-result'), /已結束：失敗/, '失敗後不再顯示送出成功');
  assert.equal(await page.locator('#server-plan-execute').isVisible(), false, '已執行的計畫不再提供執行');
  assert.ok(!/%|百分比/.test(failed));
  await shot('server-install-failed-retryable-1440.png');
  await page.locator('#server-plan-cancel').click();
  await page.waitForFunction(() => [...document.querySelectorAll('#servers-list .server-card')].some((c) => c.textContent.includes('campus-local') && c.querySelector('button[data-action="install"]')));
  assert.equal(await card('campus-local').locator('button[data-action="install"]').isDisabled(), false, '清理後可重新建立計畫');
  failed = await installOnce('afterDatabase');
  assert.ok(failed.includes('啟動業務後端'));
  await page.locator('#server-plan-cancel').click();
  await page.waitForFunction(() => [...document.querySelectorAll('#servers-list .server-card')].some((c) => c.textContent.includes('campus-local') && c.textContent.includes('資料已保留')));
  assert.equal(await card('campus-local').locator('button[data-action="install"]').count(), 0, 'FAILED 不能當作未安裝重裝');
  assert.equal(await card('campus-local').locator('button[data-action="update"]').count(), 0);
  assert.match(await card('campus-local').textContent(), /不是「未安裝」/);
  await shot('server-install-failed-kept-1440.png');
  record('install_failure_before_database_returns_to_not_installed_after_database_keeps_failed');
  await card('campus-local').locator('button[data-action="remove"]').click();
  await confirm({ expectInLines: ['不刪除伺服器上的任何目錄、容器或資料'] });
  await page.waitForFunction(() => document.getElementById('servers-result').textContent.includes('都沒有刪除'));
  assert.ok(S.calls.some((c) => /^DELETE \/servers\/[0-9a-f-]{36}\?expectedRevision=\d+$/.test(c)));
  await card('campus-local').waitFor({ state: 'detached' });
  record('control_host_registration_and_remove_only_deletes_registration');

  // ---------- 失租：只讀 ----------
  S.localLeader = false;
  await page.locator('#refresh-button').click();
  await page.waitForFunction(() => !document.getElementById('servers-status').hidden && document.getElementById('servers-status').textContent.includes('不是主中控'));
  assert.equal(await page.locator('#server-add').isDisabled(), true);
  for (const action of ['test', 'update', 'edit', 'remove']) assert.equal(await card('edge-hk-main').locator(`button[data-action="${action}"]`).isDisabled(), true, action);
  assert.equal(await page.locator('#server-job-lookup-submit').isDisabled(), false, '唯讀時仍可查詢任務');
  await shot('servers-standby-1440.png');
  const writesBefore = S.calls.filter((c) => !c.startsWith('GET')).length;
  S.localLeader = true;
  await page.locator('#refresh-button').click();
  await page.waitForFunction(() => document.getElementById('servers-status').hidden);
  assert.equal(S.calls.filter((c) => !c.startsWith('GET')).length, writesBefore);
  record('lost_lease_makes_servers_read_only_but_job_lookup_available');

  // ---------- 1440 清單截圖 ----------
  await shot('servers-list-1440.png');
  assert.ok(await noOverflow());

  // ---------- 登出清空、過期回應不污染 ----------
  await page.locator('#server-add').click();
  await page.locator('#server-password').fill('typed-before-logout');
  S.delayList = 1200;
  await page.locator('#refresh-button').click();
  await page.locator('#logout-button').click();
  await page.locator('#login-view').waitFor({ state: 'visible' });
  await page.waitForTimeout(1600);
  S.delayList = 0;
  assert.equal(await passwordsEmpty(), true, '登出清空憑據');
  assert.equal(await page.locator('#servers-list .server-card').count(), 0, '登出後到達的清單不顯示');
  assert.equal(await page.locator('#servers-view').isVisible(), false);
  assert.equal(await page.evaluate(() => JSON.stringify(localStorage)), JSON.stringify({ 'eatwhat.control.locale': 'zh-Hant' }));
  record('logout_clears_credentials_and_late_list_response_does_not_pollute');

  // ---------- 401 登出；其他讀取失敗保持未知 ----------
  S.listStatus = 503;
  await login();
  await gotoRoute(page, 'servers');
  await page.waitForFunction(() => document.getElementById('servers-list').textContent.includes('狀態未知'));
  assert.equal(await page.locator('#server-add').isDisabled(), true);
  S.listStatus = 401;
  await page.locator('#refresh-button').click();
  await page.locator('#login-view').waitFor({ state: 'visible' });
  record('list_401_logs_out_other_failures_keep_state_unknown_and_writes_disabled');
  await page.close();

  // ---------- 390px ----------
  await newPage(390, 844);
  await login();
  await gotoRoute(page, 'servers');
  await card('edge-hk-main').waitFor();
  assert.ok(await noOverflow());
  await shot('servers-list-390.png');
  await page.locator('#server-add').click();
  await page.locator('input[name="server-location"][value="CONTROL_HOST"]').check();
  await fillServer({ name: 'mobile-check', password: null });
  await page.locator('input[name="server-auth"][value="PRIVATE_KEY"]').check();
  await page.locator('#server-hostkey-fetch').click();
  await page.locator('#server-hostkeys input').first().waitFor();
  assert.ok(await noOverflow());
  await shot('server-add-390.png');
  await page.locator('#server-form-cancel').click();
  await card('edge-hk-main').locator('button[data-action="update"]').click();
  await page.locator('#server-plan-release').selectOption('release_2026_10_17');
  await page.locator('#server-plan-create').click();
  await page.locator('#server-plan-view .kv').waitFor();
  await page.locator('#server-plan-view details').evaluateAll((list) => list.forEach((d) => { d.open = true; }));
  assert.ok(await noOverflow());
  await shot('server-plan-390.png');
  await page.locator('#server-plan-cancel').click();
  await gotoRoute(page, 'deploy');
  assert.ok(await noOverflow());
  await shot('deploy-guide-390.png');
  await withDrawer(page, () => page.locator('#logout-button').click());
  await page.locator('#login-view').waitFor({ state: 'visible' });
  record('mobile_390_list_form_plan_and_deploy_guide_without_overflow');
  await page.close();

  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  await writeFile(join(output, 'verification.json'), JSON.stringify({ status: 'PASS', scope: 'LOCAL_BROWSER_WITH_SIMULATED_SERVERS_API', productionDeployment: false, realSsh: false, browser: browser.version(), steps, pageErrors: errors, externalRequests: external }, null, 2));
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
