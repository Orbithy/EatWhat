// 節點健康檢測瀏覽器驗收：本機模擬 API（時間驅動），不連線真實伺服器、不部署。
// 用法：EATWHAT_PLAYWRIGHT_MODULE=<existing-playwright-package> node scripts/verify-control-health-browser.mjs
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, join } from 'node:path';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { gotoRoute, withDrawer } from './control-nav.mjs';

const root = resolve('frontend/control');
const output = resolve('output/control-health-verification');
const { chromium } = createRequire(join(process.env.EATWHAT_PLAYWRIGHT_MODULE, 'package.json'))('playwright');

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const I1 = '11111111-1111-4111-8111-111111111111', I2 = '22222222-2222-4222-8222-222222222222';
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

let S;
function fresh() {
  S = {
    localLeader: true,
    // 每站健康模式：healthy／expired／dbTimeout／s3Denied／old（舊後端無欄位）
    mode: { campus: 'healthy', external: 'dbTimeout' },
    runningUntil: { campus: 0, external: 0 },
    write: { campus: { state: 'NOT_CHECKED' }, external: { state: 'NOT_CHECKED' } },
    writeDoneAt: { campus: 0, external: 0 },
    nextWriteOutcome: 'cleanupPending',
    reply: {}, // { health: {delay, status, code}, storage: {drop|status,code|delay} }
    calls: [], bodies: { health: [], storage: [] }, cleanupCalls: 0, observationTimes: [],
  };
}
fresh();

const iso = (ms) => new Date(ms).toISOString();
function check(now, over = {}) {
  return { status: 'PASS', code: null, checkKind: 'HTTP', startedAt: iso(now - 2000), checkedAt: iso(now - 1500), validUntil: iso(now + 58000), durationMs: 23, running: false, ...over };
}
function controlFor(site, now) {
  const mode = S.mode[site];
  if (mode === 'old') return { readiness: 'NOT_CHECKED', storageReachability: 'NOT_CHECKED', channels: {} };
  const running = now < S.runningUntil[site];
  const checks = {
    application: check(now, { checkKind: 'APPLICATION' }),
    database: check(now, { checkKind: 'DATABASE' }),
    redis: check(now, { checkKind: 'REDIS' }),
    storage: check(now, { checkKind: 'S3_HEAD_BUCKET_AND_EMPTY_LIST' }),
  };
  let readiness = 'READY', storage = 'REACHABLE';
  if (mode === 'expired') for (const k of Object.keys(checks)) Object.assign(checks[k], { checkedAt: iso(now - 120000), validUntil: iso(now - 60000) });
  let extra = {};
  // 單項逾時：UNKNOWN + CHECK_TIMEOUT + running=true（底層連線尚未返回）；資料庫故障時只回報依賴檢測、身份只來自設定。
  if (mode === 'dbTimeout') { Object.assign(checks.database, { status: 'UNKNOWN', code: 'CHECK_TIMEOUT', durationMs: 5000, running: true, checkedAt: iso(now - 1000) }); readiness = 'UNKNOWN'; extra = { observationStatus: 'DEPENDENCY_CHECKS_ONLY', identityProof: 'CONFIGURATION_ONLY' }; }
  // S3 回 401／403 時後端回報 STORAGE_ACCESS_DENIED。
  if (mode === 's3Denied') { Object.assign(checks.storage, { status: 'FAIL', code: 'STORAGE_ACCESS_DENIED' }); storage = 'UNREACHABLE'; }
  // 協議校驗：域名回 Edge HTML 404／403，或 HTTP 200 卻沒有合法桶回應。
  if (mode === 's3NotS3') { Object.assign(checks.storage, { status: 'FAIL', code: 'STORAGE_ENDPOINT_NOT_S3' }); storage = 'UNREACHABLE'; }
  if (mode === 's3Invalid') { Object.assign(checks.storage, { status: 'FAIL', code: 'STORAGE_INVALID_S3_RESPONSE' }); storage = 'UNREACHABLE'; }
  if (running) { for (const k of Object.keys(checks)) Object.assign(checks[k], { status: 'CHECKING', running: true, startedAt: iso(now - 500), checkedAt: undefined }); readiness = 'CHECKING'; storage = 'CHECKING'; }
  // 寫入探針依時間推進。
  const w = S.write[site];
  if (w.state === 'RUNNING' && now >= S.writeDoneAt[site]) {
    if (S.nextWriteOutcome === 'cleanupPending') Object.assign(w, { state: 'CLEANUP_PENDING', phase: 'CLEANUP', code: 'STORAGE_PROBE_CLEANUP_FAILED', updatedAt: iso(now) });
    else if (S.nextWriteOutcome === 'readMismatch') Object.assign(w, { state: 'FAILED', phase: 'READ', code: 'STORAGE_READ_MISMATCH', updatedAt: iso(now) });
    else if (S.nextWriteOutcome === 'failed') Object.assign(w, { state: 'FAILED', phase: 'WRITE', code: 'STORAGE_REQUEST_FAILED', updatedAt: iso(now) });
    else Object.assign(w, { state: 'PASSED', phase: 'COMPLETE', code: null, updatedAt: iso(now) });
  }
  return { readiness, storageReachability: storage, healthChecks: checks, storageWriteCheck: { ...w }, channels: {}, ...extra };
}
const obs = (site, inst, slot, mode, now) => ({
  member: { siteId: site, instanceId: inst, mode, allocationSlot: slot }, health: 'REACHABLE',
  node: { siteId: site, instanceId: inst, clusterId: A, allocationSlot: slot, mode, databaseFingerprint: `fp${slot}`.padEnd(32, '0'), protocolVersion: 1, dataPlaneReady: true, membership: { epoch: 2, state: 'ACTIVE', pending_change: null }, drain: { liveReferences: 0, localObjects: 0, pendingUploads: 0, unarchivedAccounts: 0 }, control: { siteId: site, instanceId: inst, mode, observedAt: iso(now), deploymentExecutionEnabled: false, observationStatus: 'OBSERVED', ...controlFor(site, now) } },
  sync: { pendingDeliveries: 0, pendingInbox: 0, fileTransfers: 0 },
});
const leadership = () => ({ state: 'HELD', localLeader: S.localLeader, generation: 1, expiresAt: '2026-10-17T12:00:15Z', localControllerId: A, leaderControllerId: S.localLeader ? A : B, handoffEnabled: false, handoff: { state: 'NONE' }, controllers: [{ id: A, name: 'control-a', sharedState: true }, { id: B, name: 'control-b', sharedState: true }] });
const reg = (site, inst, slot, mode) => ({ site_id: site, instance_id: inst, profile_id: `p-${site}`, mode, allocation_slot: slot, state: 'ACTIVE', database_fingerprint: `fp${slot}`.padEnd(32, '0'), joined_epoch: 1, retired_epoch: null });

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;
    let text = '';
    for await (const part of req) text += part;
    const body = text ? JSON.parse(text) : undefined;
    const send = (code, data) => { res.statusCode = code; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data)); };
    const drop = () => { res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': '200' }); res.write('{"state":'); setTimeout(() => req.socket.destroy(), 50); };
    if (p === '/auth/login') return send(200, { code: 0, data: { tokenName: 'satoken', tokenValue: 'synthetic-health-token' } });
    if (!p.startsWith('/admin/control/v1')) {
      const file = resolve(root, '.' + (p === '/' ? '/index.html' : p));
      assert.ok(file.startsWith(root + '/'));
      res.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[extname(file)] ?? 'application/octet-stream');
      return res.end(await readFile(file));
    }
    const api = p.slice('/admin/control/v1'.length);
    S.calls.push(`${req.method} ${api}`);
    const now = Date.now();
    if (api === '/cluster/capabilities') return send(200, { topology: 'SINGLE_CLUSTER', maximumActiveSites: 16, allocationSlots: 1024, coordinatorTransferEnabled: false, leadershipHandoffEnabled: false, businessSessionShared: true, deploymentExecutionEnabled: true, remoteFailureMatrixAccepted: false });
    if (api === '/cluster/leadership') return send(200, leadership());
    if (api === '/cluster') return send(200, { clusterId: A, epoch: 2, members: [reg('campus', I1, 0, 'CAMPUS'), reg('external', I2, 1, 'EXTERNAL')], pendingChanges: [], worker: { running: true, lastCode: 'OK' }, leadership: leadership() });
    if (api === '/cluster/observations') { S.observationTimes.push(now); return send(200, [obs('campus', I1, 0, 'CAMPUS', now), obs('external', I2, 1, 'EXTERNAL', now)]); }
    if (api === '/account') return send(200, { username: 'rpc', canEdit: true, reason: null });
    let m;
    if ((m = api.match(/^\/cluster\/sites\/([a-z_]+)\/health-checks$/))) {
      S.bodies.health.push(body);
      const r = S.reply.health; delete S.reply.health;
      if (r?.delay) await new Promise((x) => setTimeout(x, r.delay));
      if (r?.status) return send(r.status, { code: r.code });
      S.runningUntil[m[1]] = Date.now() + 3000;
      return send(202, { requestId: body.requestId, state: 'RUNNING' });
    }
    if ((m = api.match(/^\/cluster\/sites\/([a-z_]+)\/storage-checks$/))) {
      const site = m[1];
      S.bodies.storage.push(body);
      const r = S.reply.storage; delete S.reply.storage;
      if (r?.status) return send(r.status, { code: r.code });
      if (r?.dropBefore) return drop(); // 請求在後端處理前中斷：沒有建立任何探針
      if (!S.localLeader) return send(503, { code: 'CONTROL_LEASE_NOT_HELD' });
      const w = S.write[site];
      if (w.requestId === body.requestId) {
        if (w.state === 'CLEANUP_PENDING') { S.cleanupCalls += 1; Object.assign(w, { state: 'PASSED', phase: 'COMPLETE', code: null, updatedAt: iso(Date.now()) }); }
        return send(202, { ...w });
      }
      if (w.state === 'RUNNING') return send(409, { code: 'CHECK_IN_PROGRESS' });
      if (w.state === 'CLEANUP_PENDING') return send(409, { code: 'STORAGE_CHECK_CLEANUP_REQUIRED' });
      S.write[site] = { requestId: body.requestId, state: 'RUNNING', phase: 'WRITE', code: null, startedAt: iso(Date.now()), updatedAt: iso(Date.now()) };
      S.writeDoneAt[site] = Date.now() + 3000;
      if (r?.drop) return drop();
      return send(202, { ...S.write[site] });
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
const card = (site) => page.locator(`#cl-members .member-card[aria-labelledby="cl-member-${site}"]`);
const health = (site) => card(site).locator('.health');
const healthText = (site) => health(site).textContent();
const waitHealth = (site, re, timeout = 20000) => page.waitForFunction(([s, src]) => new RegExp(src).test(document.querySelector(`#cl-members .member-card[aria-labelledby="cl-member-${s}"] .health`)?.textContent ?? ''), [site, re.source], { timeout });
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
async function refresh() { await page.evaluate(() => document.getElementById('refresh-button').click()); }

try {
  await newPage();
  await login();

  // ---------- 總覽與站點卡片：就緒與 S3 分開、逾時錯誤碼 ----------
  await page.waitForFunction(() => document.getElementById('cl-overview').textContent.includes('external 就緒狀態未知'));
  const home = await page.locator('#cl-overview').textContent();
  assert.ok(!/campus 未就緒|campus 就緒狀態/.test(home), '健康站點不列問題');
  await shot('overview-health-1440.png');
  await gotoRoute(page, 'sites');
  await waitHealth('campus', /就緒/);
  const campus = await healthText('campus');
  for (const n of ['就緒（應用／資料庫／Redis）', 'S3 桶存取', '桶可存取', '通過', '有效至', '耗時', 'HEAD＋空列表（maxKeys=0）', '不讀取業務物件內容', '不等於讀寫健康']) assert.ok(campus.includes(n), n);
  assert.ok(!campus.includes('HEAD Bucket'));
  assert.equal(await health('campus').locator('.health-summary .status-teal').count(), 2);
  const ext = await healthText('external');
  assert.ok(ext.includes('檢測逾時') && ext.includes('CHECK_TIMEOUT') && ext.includes('耗時 5000 ms'));
  assert.ok(ext.includes('底層檢測仍在結束'), 'running=true 只另外標示');
  assert.equal(await health('external').locator('.health-check', { hasText: '資料庫' }).locator('.status-amber', { hasText: '未知' }).count(), 1, 'UNKNOWN 不被覆蓋成檢測中');
  assert.ok(!(await health('external').locator('.health-check', { hasText: '資料庫' }).textContent()).includes('檢測中'));
  assert.equal(await health('external').locator('.health-summary .health-tile').first().locator('.status-amber').count(), 1, '就緒未知為琥珀色');
  assert.equal(await health('external').locator('.health-summary .health-tile').nth(1).locator('.status-teal').count(), 1, 'S3 另計');
  assert.match(ext, /DEPENDENCY_CHECKS_ONLY[\s\S]*CONFIGURATION_ONLY[\s\S]*不代表資料庫身份已核實/);
  await shot('sites-health-1440.png');
  record('readiness_and_s3_bucket_access_shown_separately_with_timeout_code_and_times');

  // ---------- 過期 PASS 不顯示健康；403 存取拒絕 ----------
  // Edge HTML 404：端點不是 S3；HTTP 200 但回應不合法。兩者都提示檢查入口與反向代理，不宣稱桶不存在。
  S.mode.external = 's3NotS3';
  await refresh();
  await waitHealth('external', /STORAGE_ENDPOINT_NOT_S3/);
  const notS3 = await healthText('external');
  assert.ok(notS3.includes('S3 API 入口') && notS3.includes('反向代理') && notS3.includes('不代表桶不存在'));
  assert.ok(!notS3.includes('找不到設定的存儲桶'));
  assert.match(notS3, /桶無法存取/);
  await shot('sites-endpoint-not-s3-1440.png');
  S.mode.external = 's3Invalid';
  await refresh();
  await waitHealth('external', /STORAGE_INVALID_S3_RESPONSE/);
  assert.match(await healthText('external'), /HTTP 200[\s\S]*不代表桶不存在/);
  record('non_s3_endpoint_and_invalid_s3_response_point_to_api_entry_and_proxy_not_missing_bucket');

  S.mode.campus = 'expired';
  S.mode.external = 's3Denied';
  await refresh();
  await waitHealth('campus', /已過期/);
  assert.equal(await health('campus').locator('.health-summary .status-teal').count(), 0, '過期不沿用綠色');
  assert.match(await healthText('campus'), /（已過期）/);
  await waitHealth('external', /桶無法存取/);
  const denied = await healthText('external');
  assert.ok(denied.includes('401／403') && denied.includes('STORAGE_ACCESS_DENIED'));
  assert.equal(await health('external').locator('.health-tile').first().locator('.status-teal').count(), 1, '就緒仍可為就緒');
  await gotoRoute(page, 'overview');
  await page.waitForFunction(() => { const t = document.getElementById('cl-overview').textContent; return t.includes('campus 就緒狀態已過期') && t.includes('external S3 桶無法存取'); });
  await gotoRoute(page, 'sites');
  await shot('sites-expired-403-1440.png');
  record('expired_pass_is_not_healthy_and_storage_403_is_separate_from_readiness');

  // ---------- 立即檢測：重複點擊只送一次、檢測中、完成 ----------
  S.mode.campus = 'healthy';
  S.mode.external = 'healthy';
  S.reply.health = { delay: 800 };
  await card('campus').locator('button[data-health-action="check"]').dblclick();
  await waitHealth('campus', /已要求立即檢測/);
  assert.equal(S.bodies.health.length, 1, '重複點擊只送一次');
  assert.match(S.bodies.health[0].requestId, UUID_RE);
  await waitHealth('campus', /檢測中/);
  await shot('sites-checking-1440.png');
  await waitHealth('campus', /就緒[\s\S]*桶可存取/, 20000);
  await page.waitForFunction(() => document.querySelectorAll('#cl-members .member-card[aria-labelledby="cl-member-campus"] .health-summary .status-teal').length === 2, null, { timeout: 20000 });
  record('manual_read_only_check_deduplicates_double_click_and_shows_running_then_result');

  // ---------- 存儲讀寫驗證：取消、確認、清理待處理、重試清理（同 requestId） ----------
  await card('campus').locator('button[data-health-action="storage"]').click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  const lines = await page.locator('#dialog-lines').textContent();
  for (const n of ['專用臨時小物件', '讀回比對後刪除', '只代表這一次讀寫驗證', '持有控制租約']) assert.ok(lines.includes(n), n);
  await page.locator('#dialog-cancel').click();
  assert.equal(S.bodies.storage.length, 0, '取消不送出');
  await card('campus').locator('button[data-health-action="storage"]').click();
  await page.locator('#dialog-confirm').click();
  await waitHealth('campus', /讀寫驗證進行中/);
  assert.equal(await card('campus').locator('button[data-health-action="storage"]').isDisabled(), true, '進行中不能開新任務');
  await waitHealth('campus', /清理待處理/, 20000);
  assert.match(await healthText('campus'), /STORAGE_PROBE_CLEANUP_FAILED/);
  assert.equal(await card('campus').locator('button[data-health-action="storage"]').count(), 0);
  assert.equal(await health('campus').locator('.health-write.health-amber').count(), 1);
  await gotoRoute(page, 'overview');
  await page.waitForFunction(() => document.getElementById('cl-overview').textContent.includes('campus 存儲探針清理待處理'));
  await gotoRoute(page, 'sites');
  await shot('sites-cleanup-pending-1440.png');
  const firstId = S.bodies.storage[0].requestId;
  await card('campus').locator('button[data-health-action="cleanup"]').click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  assert.match(await page.locator('#dialog-lines').textContent(), /只清理[\s\S]*不再寫入/);
  await page.locator('#dialog-confirm').click();
  await waitHealth('campus', /本次讀寫驗證通過/);
  assert.equal(S.bodies.storage.at(-1).requestId, firstId, '重試清理沿用原 requestId');
  assert.equal(S.cleanupCalls, 1);
  assert.match(await healthText('campus'), /不等於目前健康/);
  assert.match(await healthText('campus'), /更新 /);
  record('storage_write_probe_confirmed_cleanup_pending_blocks_new_write_and_retry_reuses_request_id');

  // ---------- 送出結果未知：保留 requestId，再次確認以相同 UUID 重送 ----------
  S.nextWriteOutcome = 'passed';
  S.write.external = { state: 'NOT_CHECKED' };
  S.reply.storage = { drop: true };
  await card('external').locator('button[data-health-action="storage"]').click();
  await page.locator('#dialog-confirm').click();
  await waitHealth('external', /結果未知/);
  const unknownId = S.bodies.storage.at(-1).requestId;
  assert.match(await healthText('external'), new RegExp(unknownId));
  await page.waitForFunction(() => !document.querySelector('#cl-members .member-card[aria-labelledby="cl-member-external"] button[data-health-action="storage"]')?.disabled || document.querySelector('#cl-members .member-card[aria-labelledby="cl-member-external"] .health').textContent.includes('進行中'));
  record('unknown_storage_submit_keeps_request_id_and_never_auto_resends');
  const sentBefore = S.bodies.storage.length;
  await page.waitForFunction(() => document.querySelector('#cl-members .member-card[aria-labelledby="cl-member-external"] .health').textContent.includes('本次讀寫驗證通過'), null, { timeout: 20000 });
  assert.equal(S.bodies.storage.length, sentBefore, '未自動重送');

  // 中斷發生在後端處理前：使用者再次確認時以相同 requestId 送出。
  S.write.external = { state: 'NOT_CHECKED' };
  S.reply.storage = { dropBefore: true };
  await refresh();
  await page.waitForFunction(() => !document.querySelector('#cl-members .member-card[aria-labelledby="cl-member-external"] button[data-health-action="storage"]')?.disabled);
  await card('external').locator('button[data-health-action="storage"]').click();
  await page.locator('#dialog-confirm').click();
  await waitHealth('external', /結果未知/);
  const lostId = S.bodies.storage.at(-1).requestId;
  await card('external').locator('button[data-health-action="storage"]').click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  assert.match(await page.locator('#dialog-lines').textContent(), new RegExp(`相同 requestId ${lostId}`));
  await page.locator('#dialog-confirm').click();
  await waitHealth('external', /已開始存儲讀寫驗證|讀寫驗證進行中/);
  assert.equal(S.bodies.storage.at(-1).requestId, lostId, '再次確認沿用原 requestId');
  await waitHealth('external', /本次讀寫驗證通過/, 20000);
  record('manual_retry_after_unknown_submit_reuses_the_same_request_id');

  // ---------- 讀回內容不符：讀寫驗證失敗，原碼保留 ----------
  S.write.campus = { state: 'NOT_CHECKED' };
  S.nextWriteOutcome = 'readMismatch';
  await refresh();
  await page.waitForFunction(() => !document.querySelector('#cl-members .member-card[aria-labelledby="cl-member-campus"] button[data-health-action="storage"]')?.disabled);
  await card('campus').locator('button[data-health-action="storage"]').click();
  await page.locator('#dialog-confirm').click();
  await waitHealth('campus', /STORAGE_READ_MISMATCH/, 20000);
  const mismatch = await healthText('campus');
  assert.ok(mismatch.includes('讀寫驗證失敗') && mismatch.includes('讀回的臨時物件內容與寫入不符') && mismatch.includes('階段：讀取'));
  assert.equal(await health('campus').locator('.health-write.health-red').count(), 1);
  S.nextWriteOutcome = 'passed';
  record('storage_read_mismatch_fails_write_probe_with_friendly_text_and_raw_code');

  // ---------- 確認期間失去領導：不送出；備機可只讀檢測、不能寫入 ----------
  S.write.campus = { state: 'NOT_CHECKED' };
  await refresh();
  await page.waitForFunction(() => !document.querySelector('#cl-members .member-card[aria-labelledby="cl-member-campus"] button[data-health-action="storage"]')?.disabled);
  const beforeLead = S.bodies.storage.length;
  await card('campus').locator('button[data-health-action="storage"]').click();
  await page.locator('#confirm-dialog').waitFor({ state: 'visible' });
  S.localLeader = false;
  await refresh();
  await page.waitForFunction(() => document.getElementById('cl-leadership').textContent.includes('備機') || document.getElementById('cl-leadership').textContent.includes('备机'));
  await page.locator('#dialog-confirm').click();
  await waitHealth('campus', /確認後條件已改變，未送出/);
  assert.equal(S.bodies.storage.length, beforeLead, '失去領導後不送出');
  assert.equal(await card('campus').locator('button[data-health-action="storage"]').isDisabled(), true);
  assert.match(await healthText('campus'), /存儲讀寫驗證停用/);
  const readBefore = S.bodies.health.length;
  await card('external').locator('button[data-health-action="check"]').click();
  await waitHealth('external', /已要求立即檢測/);
  assert.equal(S.bodies.health.length, readBefore + 1, '備機仍可只讀檢測');
  await shot('sites-standby-1440.png');
  S.localLeader = true;
  await refresh();
  await page.waitForFunction(() => !document.querySelector('#cl-members .member-card[aria-labelledby="cl-member-campus"] button[data-health-action="storage"]')?.disabled, null, { timeout: 20000 });
  record('leadership_lost_during_confirmation_sends_nothing_and_standby_can_still_read_check');

  // ---------- 409 拒絕保留原碼 ----------
  S.reply.storage = { status: 409, code: 'STORAGE_CHECK_CLEANUP_REQUIRED' };
  await card('campus').locator('button[data-health-action="storage"]').click();
  await page.locator('#dialog-confirm').click();
  await waitHealth('campus', /STORAGE_CHECK_CLEANUP_REQUIRED/);
  assert.match(await healthText('campus'), /尚未清理/);
  S.reply.health = { status: 409, code: 'FUTURE_HEALTH_CODE' };
  await card('external').locator('button[data-health-action="check"]').click();
  await waitHealth('external', /FUTURE_HEALTH_CODE/);
  assert.match(await healthText('external'), /未定義的錯誤碼/);
  record('rejections_keep_copyable_codes_and_unknown_codes_are_not_hidden');

  // ---------- 切頁：停止加快輪詢、丟棄遲到回應 ----------
  S.reply.health = { delay: 1500 };
  await card('campus').locator('button[data-health-action="check"]').click();
  await gotoRoute(page, 'overview');
  await page.waitForTimeout(2500);
  const t0 = Date.now();
  await page.waitForTimeout(6000);
  const onOverview = S.observationTimes.filter((t) => t >= t0).length;
  assert.ok(onOverview <= 2, `離開站點頁後回到一般輪詢（${onOverview} 次／6 秒）`);
  await gotoRoute(page, 'sites');
  assert.ok(!(await healthText('campus')).includes('已要求立即檢測'), '遲到回應不顯示');
  record('leaving_sites_stops_fast_polling_and_drops_late_responses');

  // ---------- 登出：取消並丟棄遲到回應 ----------
  S.reply.health = { delay: 1500 };
  await card('campus').locator('button[data-health-action="check"]').click();
  await withDrawer(page, () => page.locator('#logout-button').click());
  await page.locator('#login-view').waitFor({ state: 'visible' });
  await page.waitForTimeout(2000);
  assert.equal(await page.locator('#cl-members').textContent(), '');
  await login();
  await gotoRoute(page, 'sites');
  await waitHealth('campus', /就緒/);
  assert.ok(!(await healthText('campus')).includes('已要求立即檢測'));
  record('logout_drops_late_health_responses');
  await page.close();

  // ---------- 簡體、舊後端未檢查、390px ----------
  fresh();
  S.mode.external = 'old';
  await newPage({ width: 390, height: 844, locale: null });
  await login();
  await gotoRoute(page, 'sites');
  await waitHealth('campus', /就绪/);
  assert.match(await healthText('campus'), /立即检测/);
  assert.match(await healthText('external'), /后端未提供健康检测字段|未检查/);
  assert.equal(await health('external').locator('.status-teal').count(), 0, '舊後端不補成正常');
  await page.locator('#cl-members details').evaluateAll((l) => l.forEach((d) => { d.open = true; }));
  assert.ok(await noOverflow());
  await shot('sites-health-390.png');
  S.write.campus = { requestId: '33333333-3333-4333-8333-333333333333', state: 'CLEANUP_PENDING', phase: 'CLEANUP', code: 'STORAGE_PROBE_CLEANUP_FAILED', startedAt: iso(Date.now() - 60000), updatedAt: iso(Date.now() - 30000) };
  await refresh();
  await waitHealth('campus', /清理待处理/);
  assert.ok(await noOverflow());
  await shot('sites-cleanup-pending-390.png');
  await gotoRoute(page, 'overview');
  assert.ok(await noOverflow());
  await shot('overview-health-390.png');
  record('simplified_old_backend_not_checked_and_390_without_overflow');
  await page.close();

  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  await writeFile(join(output, 'verification.json'), JSON.stringify({ status: 'PASS', scope: 'LOCAL_BROWSER_WITH_SIMULATED_HEALTH_API', realServers: false, productionDeployment: false, browser: browser.version(), steps, pageErrors: errors, externalRequests: external }, null, 2));
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
