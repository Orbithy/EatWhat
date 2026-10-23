// SSH 伺服器登記、首次安裝與版本更新：驗證、正規化、錯誤分類、API 白名單與靜態結構。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as ss from '../servers-state.js';
import { ApiError, createControlClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');

const SID = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const KEY = { publicKey: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample', type: 'ssh-ed25519', fingerprint: 'SHA256:abcDEF123' };
const PRIVATE_KEY = '-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ==\n-----END OPENSSH PRIVATE KEY-----';
// openssh-key-v1\0 + cipher "aes256-ctr"
const ENCRYPTED_OPENSSH = `-----BEGIN OPENSSH PRIVATE KEY-----\n${Buffer.from('openssh-key-v1\0\0\0\0\naes256-ctr\0\0\0\x06bcrypt').toString('base64')}\n-----END OPENSSH PRIVATE KEY-----`;

function form(over = {}) {
  const base = { name: 'edge-hk', location: 'REMOTE', host: '203.0.113.24', port: '2222', username: 'root', authentication: 'PASSWORD', credential: 'ssh pass word', keyIndex: 0, confirmed: true, forceHostKey: false };
  const f = { ...base, ...over };
  if (!('probe' in over)) f.probe = { signature: ss.addressSignature(f), keys: [KEY] };
  return f;
}

const server = (over = {}) => ss.normalizeServer({
  id: SID, name: 'edge-hk', location: 'REMOTE', host: '203.0.113.24', port: 2222, username: 'root', authentication: 'PASSWORD',
  hasCredential: true, hostKey: KEY, revision: 3, connection: { state: 'NOT_TESTED' }, installation: null, ...over,
});

// ---------- 連線資料驗證 ----------

test('主機：接受主機名稱、IPv4、IPv6；拒絕協定、使用者、路徑、參數與空白', () => {
  for (const ok of ['example.org', 'edge-1', '203.0.113.24', '::1', '2001:db8::10', 'localhost']) assert.equal(ss.hostProblem(ok), null, ok);
  for (const bad of ['', ' host', 'ssh://host', 'root@host', 'host/path', 'host?x=1', 'host#a', '256.1.1.1', '1.2.3', '[::1]', 'a b', '-bad.example']) assert.ok(ss.hostProblem(bad), bad);
});

test('埠、使用者、名稱規則', () => {
  assert.equal(ss.portProblem('22'), null);
  assert.equal(ss.portProblem('65535'), null);
  for (const bad of ['0', '65536', '2.5', '', 'x']) assert.ok(ss.portProblem(bad), bad);
  for (const ok of ['root', '_svc', 'deploy.user@corp', 'a-b']) assert.equal(ss.usernameProblem(ok), null, ok);
  for (const bad of ['1root', '', 'a b', 'a'.repeat(129), 'a:b']) assert.ok(ss.usernameProblem(bad), bad);
  assert.equal(ss.nameProblem('x'), null);
  assert.equal(ss.nameProblem('名'.repeat(64)), null);
  assert.ok(ss.nameProblem('名'.repeat(65)));
  assert.ok(ss.nameProblem('   '));
  assert.ok(ss.nameProblem('a\u0007b'));
});

test('憑據：密碼不含換行／NUL、≤256；私鑰需完整且沒有保護密碼、≤16 KiB', () => {
  assert.equal(ss.credentialProblem('PASSWORD', ' 前後空白 也保留 '), null);
  assert.ok(ss.credentialProblem('PASSWORD', 'a\nb'));
  assert.ok(ss.credentialProblem('PASSWORD', 'a\0b'));
  assert.ok(ss.credentialProblem('PASSWORD', 'x'.repeat(257)));
  assert.equal(ss.credentialProblem('PRIVATE_KEY', PRIVATE_KEY), null);
  assert.equal(ss.privateKeyEncryption(PRIVATE_KEY), 'none');
  assert.equal(ss.privateKeyEncryption(ENCRYPTED_OPENSSH), 'encrypted');
  assert.match(ss.credentialProblem('PRIVATE_KEY', ENCRYPTED_OPENSSH), /保護密碼/);
  assert.match(ss.credentialProblem('PRIVATE_KEY', '-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n-----END RSA PRIVATE KEY-----'), /保護密碼/);
  assert.ok(ss.credentialProblem('PRIVATE_KEY', 'not a key'));
  assert.ok(ss.credentialProblem('PRIVATE_KEY', `-----BEGIN OPENSSH PRIVATE KEY-----\n${'A'.repeat(16 * 1024)}\n-----END OPENSSH PRIVATE KEY-----`));
});

test('中控本機一律使用 127.0.0.1', () => {
  const f = form({ location: 'CONTROL_HOST', host: '198.51.100.9' });
  assert.equal(ss.effectiveHost(f), '127.0.0.1');
  const body = ss.serverRequestBody(f, { requestId: SID });
  assert.equal(body.host, '127.0.0.1');
  assert.equal(body.location, 'CONTROL_HOST');
});

// ---------- 主機指紋 ----------

test('新增：必須取得指紋、選擇金鑰並明確確認', () => {
  assert.deepEqual(ss.serverFormCheck(form()).errors, []);
  assert.ok(ss.serverFormCheck(form({ confirmed: false })).errors.some((e) => /獨立核對/.test(e)));
  assert.ok(ss.serverFormCheck(form({ probe: null })).errors.some((e) => /取得主機指紋/.test(e)));
  assert.ok(ss.serverFormCheck(form({ keyIndex: -1 })).errors.length > 0, '未選擇金鑰');
});

test('取得指紋後改了主機、埠、使用者或位置，先前的指紋失效', () => {
  const f = form();
  for (const change of [{ host: '203.0.113.25' }, { port: '22' }, { username: 'admin' }, { location: 'CONTROL_HOST' }]) {
    const changed = { ...f, ...change };
    assert.equal(ss.selectedHostKey(changed, null), null, JSON.stringify(change));
    assert.ok(ss.serverFormCheck(changed).errors.length > 0);
  }
});

test('編輯：位址未變沿用已核對的指紋；憑據留空保留；改位址需重新核對', () => {
  const s = server();
  const keep = form({ port: '2222', credential: '', probe: null, confirmed: false });
  assert.deepEqual(ss.serverFormCheck(keep, s).errors, []);
  const body = ss.serverRequestBody(keep, { server: s, requestId: SID });
  assert.equal(body.hostKey, KEY.publicKey);
  assert.equal(body.confirmedFingerprint, KEY.fingerprint);
  assert.equal(body.expectedRevision, 3);
  assert.ok(!('credential' in body), '留空不送 credential');
  assert.ok(ss.serverFormCheck({ ...keep, host: '203.0.113.99' }, s).errors.some((e) => /主機指紋/.test(e)));
  assert.ok(ss.serverFormCheck({ ...keep, forceHostKey: true }, s).errors.length > 0, '要求重新核對時不能沿用');
});

test('編輯：改變認證方式必須輸入新憑據；已安裝不能改位置／主機／埠', () => {
  const s = server();
  assert.ok(ss.serverFormCheck(form({ authentication: 'PRIVATE_KEY', credential: '', probe: null }), s).errors.some((e) => /認證方式/.test(e)));
  const installed = server({ installation: { state: 'SUCCEEDED', httpPort: 18090 } });
  const moved = form({ host: '203.0.113.30' });
  assert.ok(ss.serverFormCheck(moved, installed).errors.some((e) => /已安裝/.test(e)));
  assert.deepEqual(ss.serverFormCheck(form({ name: '新名稱', credential: '', probe: null }), installed).errors, [], '名稱與憑據可改');
});

test('請求本文只含契約欄位；requestId 與憑據不影響意圖簽章', () => {
  const body = ss.serverRequestBody(form(), { requestId: SID });
  assert.deepEqual(Object.keys(body).sort(), ['authentication', 'confirmedFingerprint', 'credential', 'host', 'hostKey', 'location', 'name', 'port', 'requestId', 'username'].sort());
  assert.equal(body.port, 2222);
  assert.equal(body.credential, 'ssh pass word', '密碼不 trim');
  const other = { ...body, requestId: '11111111-1111-4111-8111-111111111111', credential: '另一個密碼' };
  assert.equal(ss.serverIntentSignature(body), ss.serverIntentSignature(other));
  assert.notEqual(ss.serverIntentSignature(body), ss.serverIntentSignature({ ...body, name: 'x' }));
  assert.equal(ss.serverRequestBody(form({ confirmed: false }), { requestId: SID }), null);
});

test('主機金鑰回應：只保留有 publicKey 與 fingerprint 的項目', () => {
  assert.deepEqual(ss.normalizeHostKeys({ keys: [KEY, { type: 'x' }, null, { publicKey: 'p', fingerprint: 'f' }] }).length, 2);
  assert.deepEqual(ss.normalizeHostKeys(null), []);
});

// ---------- 安裝／更新 ----------

test('業務 HTTP 埠：1024–65535，禁止 18080／18081，預設 18090', () => {
  assert.equal(ss.DEFAULT_HTTP_PORT, 18090);
  assert.equal(ss.httpPortProblem('18090'), null);
  for (const bad of ['1023', '18080', '18081', '65536', 'x']) assert.ok(ss.httpPortProblem(bad), bad);
});

test('對外網址：http／https、無帳號、查詢或錨點', () => {
  for (const ok of ['https://shop.example.org', 'http://203.0.113.24:8080/app']) assert.equal(ss.appBaseUrlProblem(ok), null, ok);
  for (const bad of ['', 'ftp://example.org', 'https://u:p@example.org', 'https://example.org/?a=1', 'https://example.org/#x', 'example.org']) assert.ok(ss.appBaseUrlProblem(bad), bad);
});

test('安裝本文固定五個欄位；更新沿用安裝時的埠與網址且不安裝 Docker', () => {
  const releases = [{ id: 'release_1' }];
  const s = server();
  const install = ss.installRequestBody({ releaseId: 'release_1', httpPort: '18090', appBaseUrl: 'https://shop.example.org', installDocker: true }, { kind: 'INSTALL', server: s, releases });
  assert.deepEqual(install, { expectedRevision: 3, releaseId: 'release_1', httpPort: 18090, appBaseUrl: 'https://shop.example.org', installDocker: true });
  assert.equal(ss.installRequestBody({ releaseId: 'nope', httpPort: '18090', appBaseUrl: 'https://a.example' }, { kind: 'INSTALL', server: s, releases }), null);
  const installed = server({ installation: { state: 'SUCCEEDED', httpPort: 18100, appBaseUrl: 'https://old.example.org' } });
  const update = ss.installRequestBody({ releaseId: 'release_1', httpPort: '1', appBaseUrl: '', installDocker: true }, { kind: 'UPDATE', server: installed, releases });
  assert.deepEqual(update, { expectedRevision: 3, releaseId: 'release_1', httpPort: 18100, appBaseUrl: 'https://old.example.org', installDocker: false });
});

test('計畫正規化與過期判定', () => {
  const plan = ss.normalizePlan({ id: SID, kind: 'INSTALL', state: 'READY', blockers: [], services: ['app', 'postgres', 'redis'], root: `/opt/eatwhat-sites/${SID}`, httpPort: 18090, clusterMembership: 'NOT_JOINED', administratorUsername: 'ops_admin', expiresAt: '2026-10-17T10:15:00Z' });
  assert.equal(plan.root, `/opt/eatwhat-sites/${SID}`);
  assert.equal(plan.administratorUsername, 'ops_admin');
  assert.equal(ss.planExpired(plan, Date.parse('2026-10-17T10:14:59Z')), false);
  assert.equal(ss.planExpired(plan, Date.parse('2026-10-17T10:15:00Z')), true);
  assert.equal(ss.normalizePlan({ id: SID, kind: 'DELETE' }), null);
  assert.equal(ss.normalizePlan({ id: SID, kind: 'INSTALL', state: 'WEIRD' }).state, 'BLOCKED', '未知狀態不當成可執行');
});

test('任務只有總體狀態；QUEUED／RUNNING 才輪詢', () => {
  const job = ss.normalizeJob({ id: SID, state: 'RUNNING', kind: 'INSTALL', steps: [1, 2], progress: 50 });
  assert.equal(ss.jobActive(job), true);
  assert.ok(!('steps' in job) && !('progress' in job));
  assert.equal(ss.jobActive(ss.normalizeJob({ id: SID, state: 'OUTCOME_UNKNOWN' })), false);
  assert.deepEqual(ss.jobCopy('OUTCOME_UNKNOWN'), ['red', '結果未知']);
});

// ---------- 環境檢測與狀態 ----------

test('環境檢測：Python ≥3.10、Compose v2、root 與 Docker 權限分別判斷', () => {
  const ok = { state: 'CONNECTED', linux: 'Debian 13', python: '3.11.2', docker: '27.3.1', compose: 'v2.29.1', dockerAccess: true, root: true };
  assert.ok(ss.connectionChecks(ok).every((c) => c.tone === 'ok'));
  assert.equal(ss.connectionChecks({ ...ok, python: '3.9.18' }).find((c) => c.label === 'Python').tone, 'bad');
  assert.equal(ss.connectionChecks({ ...ok, compose: '1.29.2' }).find((c) => c.label === 'Docker Compose').tone, 'bad');
  assert.match(ss.connectionChecks({ ...ok, root: false }).at(-1).detail, /需要 root/);
  assert.equal(ss.connectionChecks({ state: 'NOT_TESTED' }).every((c) => c.tone === 'wait'), true);
  assert.equal(ss.connectionChecks({ state: 'FAILED', code: 'SSH_HOST_KEY_UNAVAILABLE' })[0].tone, 'bad');
  assert.equal(ss.serverStatus(server({ connection: ok })).label, '環境就緒');
  assert.equal(ss.serverStatus(server({ connection: { ...ok, root: false } })).tone, 'amber');
});

test('已安裝：顯示尚未加入多站集群，不寫成集群成員或停止流量', () => {
  const st = ss.serverStatus(server({ installation: { state: 'SUCCEEDED', clusterMembership: 'NOT_JOINED' } }));
  assert.equal(st.installed, true);
  assert.match(st.label, /尚未加入多站集群/);
  assert.ok(!/ACTIVE|不提供業務流量/.test(st.label));
  assert.equal(ss.serverStatus(server({ installation: { state: 'RUNNING' } })).unresolved, true);
});

test('清單正規化：格式無效回 null；無效項目略過；canManage 只認 true', () => {
  assert.equal(ss.normalizeServerList(null), null);
  assert.equal(ss.normalizeServerList({ servers: 'x' }), null);
  const list = ss.normalizeServerList({ servers: [{ id: SID, name: 'a', revision: 0 }, { id: 'bad' }], canManage: 'yes', releases: [{ id: 'r' }, {}] });
  assert.equal(list.servers.length, 1);
  assert.equal(list.dropped, 1);
  assert.equal(list.canManage, false);
  assert.deepEqual(list.releases.map((r) => r.id), ['r']);
  assert.equal(ss.gateReasonText('CONTROL_LEASE_NOT_HELD'), ss.SERVER_CODE_TEXT.CONTROL_LEASE_NOT_HELD);
  assert.match(ss.gateReasonText('FUTURE_CODE'), /不能管理/);
});

test('安裝狀態：NOT_INSTALLED 視為未安裝可重新計畫；FAILED 保留且不能當作未安裝', () => {
  const ok = { state: 'CONNECTED', linux: 'Debian 13', python: '3.11.2', docker: '27', compose: 'v2.29.1', dockerAccess: true, root: true };
  const retry = server({ connection: ok, installation: { state: 'NOT_INSTALLED' } });
  assert.equal(retry.installation, null);
  assert.equal(ss.serverStatus(retry).label, '環境就緒');
  assert.deepEqual(ss.serverFormCheck(form({ host: '203.0.113.30' }), retry).errors, [], '未安裝可改位址');
  const failed = server({ connection: ok, installation: { state: 'FAILED', root: `/opt/eatwhat-sites/${SID}`, jobId: SID } });
  const st = ss.serverStatus(failed);
  assert.equal(st.tone, 'red');
  assert.equal(st.unresolved, true);
  assert.equal(st.installed, false);
  assert.ok(ss.serverFormCheck(form({ host: '203.0.113.30' }), failed).errors.some((e) => /已安裝/.test(e)), 'FAILED 保留資料，位址不可改');
});

test('任務階段只做文字明細；未知階段不翻譯', () => {
  for (const stage of ['PREFLIGHT', 'ARTIFACT_VERIFY', 'SYSTEM_DEPENDENCIES', 'IMAGES', 'DATABASE_REDIS', 'APPLICATION_START', 'RESTORE_REHEARSAL', 'ADMIN_INITIALIZATION', 'COMPLETE']) {
    assert.ok(ss.jobStageText(stage), stage);
  }
  assert.equal(ss.jobStageText('SOMETHING'), null);
  assert.equal(ss.jobStageText(3), null);
});

// ---------- 錯誤分類 ----------

const httpErr = (status, code) => new ApiError({ kind: 'http', status, code });

test('serverFailure：401／403 登出；網路、逾時、解析失敗與未定義 5xx 為結果未知', () => {
  assert.equal(ss.serverFailure(httpErr(401, null)).outcome, 'LOGOUT');
  assert.equal(ss.serverFailure(httpErr(403, 'ADMIN_REQUIRED')).outcome, 'LOGOUT');
  for (const e of [new ApiError({ kind: 'network' }), new ApiError({ kind: 'timeout' }), new ApiError({ kind: 'parse', status: 201 }), httpErr(500, null), httpErr(504, null), httpErr(503, 'OTHER')]) {
    assert.equal(ss.serverFailure(e).outcome, 'UNKNOWN', e.message);
  }
});

test('serverFailure：契約錯誤碼為明確拒絕並轉成說明；失租需重讀主中控狀態', () => {
  for (const [s, c] of [[400, 'INVALID_SSH_HOST'], [404, 'SERVER_NOT_FOUND'], [409, 'SERVER_REVISION_CHANGED'], [409, 'DEPLOYMENT_RUNNING'], [423, 'SERVER_INSTALLATION_DISABLED'], [429, 'SSH_OPERATION_BUSY'], [404, 'SERVER_PLAN_NOT_FOUND'], [502, 'SSH_HOST_KEY_UNAVAILABLE'], [503, 'SERVER_AGENT_UNAVAILABLE']]) {
    const f = ss.serverFailure(httpErr(s, c));
    assert.equal(f.outcome, 'REJECTED', c);
    assert.equal(f.message, ss.SERVER_CODE_TEXT[c]);
  }
  const lease = ss.serverFailure(httpErr(503, 'CONTROL_LEASE_NOT_HELD'));
  assert.equal(lease.outcome, 'REJECTED');
  assert.equal(lease.leaseLost, true);
  for (const code of ['INSTALLATION_DIRECTORY_EXISTS', 'FIRST_INSTALL_ROOT_REQUIRED', 'DOCKER_COMPOSE_REQUIRED', 'UPGRADE_COMPATIBILITY_NOT_REVIEWED', 'RESTORE_REHEARSAL_REQUIRED']) {
    assert.ok(ss.serverCodeText(code), code);
  }
  assert.match(ss.serverCodeText('INSTALLATION_DIRECTORY_EXISTS'), /空目錄也不會覆寫/);
});

// ---------- API ----------

test('伺服器 API：路徑、方法與本文白名單', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  const body = ss.serverRequestBody(form(), { requestId: SID });
  await client.listServers();
  await client.probeHostKey({ host: '203.0.113.24', port: 2222 });
  await client.createServer(body);
  await client.updateServer(SID, { ...body, expectedRevision: 3 });
  await client.removeServer(SID, 3);
  await client.testServer(SID, 3);
  const install = { expectedRevision: 3, releaseId: 'r', httpPort: 18090, appBaseUrl: 'https://a.example', installDocker: false };
  await client.createServerPlan(SID, 'INSTALL', install);
  await client.createServerPlan(SID, 'UPDATE', install);
  await client.getServerPlan(SID);
  await client.executeServerPlan(SID);
  await client.getServerJob(SID);
  await client.reconcileServerJob(SID);
  assert.deepEqual(fetchImpl.calls.map((c) => `${c.init.method} ${c.url.replace('/admin/control/v1', '')}`), [
    'GET /servers', 'POST /servers/host-key', 'POST /servers', `PUT /servers/${SID}`, `DELETE /servers/${SID}?expectedRevision=3`,
    `POST /servers/${SID}/test`, `POST /servers/${SID}/installation-plans`, `POST /servers/${SID}/update-plans`,
    `GET /server-plans/${SID}`, `POST /server-plans/${SID}/execute`, `GET /server-jobs/${SID}`, `POST /server-jobs/${SID}/reconcile`,
  ]);
  assert.deepEqual(fetchImpl.calls[1].body, { host: '203.0.113.24', port: 2222 });
  assert.deepEqual(fetchImpl.calls[5].body, { expectedRevision: 3 });
  assert.equal(fetchImpl.calls[9].body, undefined, 'execute 無本文');
});

test('伺服器 API：拒絕契約外欄位（命令、目錄、映像、私鑰密碼）且不送出', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  const body = ss.serverRequestBody(form(), { requestId: SID });
  const install = { expectedRevision: 3, releaseId: 'r', httpPort: 18090, appBaseUrl: 'https://a.example', installDocker: false };
  const bad = [
    () => client.createServer({ ...body, passphrase: 'x' }),
    () => client.createServer({ ...body, command: 'rm -rf /' }),
    () => client.createServer({ ...body, requestId: 'not-uuid' }),
    () => client.createServer({ ...body, credential: '' }),
    () => client.updateServer(SID, body),
    () => client.probeHostKey({ host: 'h', port: 22, user: 'root' }),
    () => client.createServerPlan(SID, 'INSTALL', { ...install, root: '/tmp' }),
    () => client.createServerPlan(SID, 'INSTALL', { ...install, image: 'x' }),
    () => client.createServerPlan(SID, 'DELETE', install),
    () => client.removeServer('x', 1),
    () => client.removeServer(SID),
    () => client.executeServerPlan('../x'),
  ];
  for (const call of bad) await assert.rejects(call(), (e) => e.kind === 'validation');
  assert.equal(fetchImpl.calls.length, 0);
});

// ---------- 靜態結構 ----------

test('伺服器頁：路由、側欄入口、表單 ID；沒有私鑰密碼欄位與「不建議 root」文案', () => {
  const html = read('index.html');
  assert.match(html, /<a href="#\/servers" data-route="servers">/);
  assert.match(html, /<section id="page-servers" class="page" data-page="servers"[^>]*hidden>/);
  for (const id of ['server-add', 'servers-list', 'server-form', 'server-name', 'server-host', 'server-port', 'server-username', 'server-password', 'server-private-key',
    'server-hostkey-fetch', 'server-hostkey-confirm', 'server-save', 'server-plan-form', 'server-plan-release', 'server-plan-url', 'server-plan-port', 'server-plan-docker',
    'server-plan-create', 'server-plan-execute', 'server-job-view', 'server-job-reconcile', 'server-job-lookup-form', 'cl-deploy-guide']) {
    assert.ok(html.includes(`id="${id}"`), id);
  }
  assert.ok(!/passphrase|保護密碼（選填）|私鑰保護密碼/.test(html));
  assert.ok(!/不建議.{0,4}root/.test(html + read('servers-ui.js')));
  assert.match(html, /首次安裝需要 root/);
  assert.match(html, /並非你正在開啟網頁的這台電腦/);
  assert.match(html, /<input id="server-plan-port"[^>]*value="18090"/);
  assert.match(html, /<input id="server-plan-docker" type="checkbox">/, 'Docker 安裝預設不勾選');
  assert.match(html, /<input id="server-password" type="password" autocomplete="new-password"/);
});

test('伺服器頁：憑據在送出、離開、登出時清空；寫入只在使用者事件中；不顯示逐步進度', () => {
  const ui = read('servers-ui.js');
  const app = read('app.js');
  const between = (a, b) => ui.slice(ui.indexOf(a), ui.indexOf(b, ui.indexOf(a)));
  assert.match(between('async function submitForm', 'const res = server'), /clearSecrets\(\);\s*$/m, '送出前清空');
  assert.match(between('leave()', 'load,'), /clearSecrets\(\)/);
  assert.match(between('reset()', 'render,'), /clearSecrets\(\)/);
  assert.match(between('function closeForm', 'function renderForm'), /clearSecrets\(\)/);
  assert.match(app, /servers\.reset\(\);/);
  assert.match(app, /if \(route === 'servers'\) servers\.enter\(\);\s*else servers\.leave\(\);/);
  for (const name of ['createServer', 'updateServer', 'removeServer', 'testServer', 'executeServerPlan', 'reconcileServerJob', 'createServerPlan', 'probeHostKey']) {
    assert.equal((ui.match(new RegExp(`client\\.${name}\\(`, 'g')) ?? []).length, 1, `${name} 只在一個處理器中呼叫`);
    assert.ok(!app.includes(`${name}(`), `app.js 不直接呼叫 ${name}`);
  }
  assert.ok(!/progress|percent|百分比/.test(ui.replace(/不顯示步驟或百分比/, '')), '不編造進度');
  assert.ok(!/localStorage|sessionStorage/.test(ui));
  // 結果未知不自動重送：execute 的未知分支只查詢任務。
  const unknown = between('onUnknown: async () => {\n          els.jobLookupId.value = captured.id;', 'finally');
  assert.ok(!unknown.includes('executeServerPlan'), '未知結果只以原計畫 UUID 查詢');
});

test('多站模式：伺服器寫入受主中控租約限制', () => {
  assert.match(read('cluster-app.js'), /function leadership\(\)[\s\S]*?return \{ writable: lead\.writable, reasons: lead\.reasons \};/);
  assert.match(read('app.js'), /getLeadership: \(\) => \(cluster \? cluster\.leadership\(\) : null\)/);
  assert.match(read('servers-ui.js'), /if \(lead && !lead\.writable\)/);
});

// ---------- 接入集群（空集群首個中控本機權威站） ----------

const OK_CONN = { state: 'CONNECTED', linux: 'Debian 13', python: '3.11.2', docker: '27', compose: 'v2.29.1', dockerAccess: true, root: true };
const installedServer = (over = {}, inst = {}) => server({ location: 'CONTROL_HOST', host: '127.0.0.1', connection: OK_CONN, installation: { state: 'SUCCEEDED', httpPort: 18090, clusterMembership: 'NOT_JOINED', ...inst }, ...over });
const enrollList = (over = {}) => ({ clusterEnrollmentEnabled: true, ...over });

test('接入入口：只對已安裝且未加入的伺服器顯示；遠端或功能未啟用時停用並說明', () => {
  assert.deepEqual(ss.enrollmentAvailability(installedServer(), enrollList()), { show: true, reasons: [], kind: 'INITIAL_AUTHORITY' });
  assert.equal(ss.enrollmentAvailability(server({ connection: OK_CONN }), enrollList()).show, false, '未安裝不顯示');
  assert.equal(ss.enrollmentAvailability(installedServer({}, { clusterMembership: 'ACTIVE' }), enrollList()).show, false, '已加入不顯示');
  const remote = ss.enrollmentAvailability(installedServer({ location: 'REMOTE', host: '203.0.113.24' }), enrollList());
  assert.equal(remote.show, true);
  assert.ok(remote.reasons.some((r) => /中控本機/.test(r)));
  assert.ok(ss.enrollmentAvailability(installedServer(), enrollList({ clusterEnrollmentEnabled: false })).reasons.some((r) => /未啟用/.test(r)));
  assert.ok(ss.enrollmentAvailability(installedServer(), null).reasons.length > 0, '清單未知時不開放');
});

test('已加入：只有 clusterMembership=ACTIVE 才顯示「已加入集群」；安裝成功不算加入、卡片不寫單站', () => {
  assert.equal(ss.serverStatus(installedServer()).joined, undefined);
  assert.match(ss.serverStatus(installedServer()).label, /尚未加入多站集群/);
  for (const m of ['JOINING', 'APPLIED', 'active', null]) assert.notEqual(ss.serverStatus(installedServer({}, { clusterMembership: m })).joined, true, String(m));
  const joined = ss.serverStatus(installedServer({}, { clusterMembership: 'ACTIVE' }));
  assert.equal(joined.joined, true);
  assert.equal(joined.label, '已加入集群');
  assert.ok(!/單站/.test(joined.label), '不僅憑 ACTIVE 寫單站運行');
});

test('清單：clusterEnrollmentEnabled 只認 true；clusterJobId 可在頂層或個別伺服器', () => {
  const base = { servers: [{ id: SID, name: 'a', revision: 1, clusterJobId: '11111111-1111-4111-8111-111111111111' }] };
  const list = ss.normalizeServerList({ ...base, clusterEnrollmentEnabled: 'true' });
  assert.equal(list.clusterEnrollmentEnabled, false);
  assert.equal(list.clusterJobId, '11111111-1111-4111-8111-111111111111');
  assert.equal(ss.normalizeServerList({ ...base, clusterJobId: SID }).clusterJobId, SID, '頂層優先');
  assert.equal(ss.normalizeServerList({ servers: [], clusterJobId: 'bad' }).clusterJobId, null);
});

test('接入計畫：只接受 INITIAL_AUTHORITY；單站與重啟預設保守；原資料庫保留需明確 true', () => {
  const plan = ss.normalizeClusterPlan({ id: SID, kind: 'INITIAL_AUTHORITY', serverId: SID, serverRevision: 2, state: 'READY', blockers: [], databasePort: 25432, httpPort: 18090, singleSiteOnly: true, controllerRestartExpected: true, originalDatabasePreserved: true, administratorUsername: 'rpc' });
  assert.equal(plan.databasePort, 25432);
  assert.equal(plan.originalDatabasePreserved, true);
  assert.equal(ss.normalizeClusterPlan({ id: SID, kind: 'INSTALL', state: 'READY' }), null);
  const sparse = ss.normalizeClusterPlan({ id: SID, kind: 'INITIAL_AUTHORITY', state: 'READY' });
  assert.equal(sparse.singleSiteOnly, true);
  assert.equal(sparse.controllerRestartExpected, true);
  assert.equal(sparse.originalDatabasePreserved, false);
  assert.equal(ss.normalizeClusterPlan({ id: SID, kind: 'INITIAL_AUTHORITY', state: 'NEW' }).state, 'BLOCKED');
  assert.deepEqual(ss.normalizeClusterPlan({ id: SID, kind: 'INITIAL_AUTHORITY', state: 'BLOCKED', blockers: ['CLUSTER_ALREADY_INITIALIZED', 'FUTURE_BLOCKER'] }).blockers, ['CLUSTER_ALREADY_INITIALIZED', 'FUTURE_BLOCKER']);
  for (const code of ['CLUSTER_ENROLLMENT_DISABLED', 'INITIAL_AUTHORITY_CONTROL_HOST_REQUIRED', 'CLUSTER_ALREADY_INITIALIZED', 'AUTHORITY_PREFLIGHT_FAILED', 'AUTHORITY_PLAN_CHANGED', 'AUTHORITY_PLAN_EXPIRED', 'AUTHORITY_PLAN_BLOCKED', 'AUTHORITY_JOB_NOT_FOUND', 'AUTHORITY_RECONCILIATION_REQUIRED']) {
    assert.ok(ss.serverCodeText(code), code);
  }
});

test('接入任務：只有 SUCCEEDED 且結果為 APPLIED／ACTIVE 才算已核對加入', () => {
  const ok = { id: SID, state: 'SUCCEEDED', stage: 'VERIFIED', result: { clusterMembership: 'ACTIVE', changeState: 'APPLIED', epoch: 1, singleSiteOnly: true } };
  assert.equal(ss.clusterJobVerified(ss.normalizeClusterJob(ok)), true);
  assert.equal(ss.clusterJobVerified(ss.normalizeClusterJob({ ...ok, result: { clusterMembership: 'ACTIVE' } })), false);
  assert.equal(ss.clusterJobVerified(ss.normalizeClusterJob({ ...ok, result: null })), false);
  assert.equal(ss.clusterJobVerified(ss.normalizeClusterJob({ ...ok, state: 'RUNNING' })), false);
  assert.equal(ss.clusterStageText('MEMBERSHIP_JOIN'), '成員加入');
  assert.equal(ss.clusterStageText('OTHER'), null);
});

test('重新連線：網路、逾時、解析失敗與 5xx 只重試；401／403 走登出；404 停止；退避有上限', () => {
  for (const e of [new ApiError({ kind: 'network' }), new ApiError({ kind: 'timeout' }), new ApiError({ kind: 'parse', status: 200 }), httpErr(502, null), httpErr(503, 'CONTROL_LEASE_NOT_HELD'), httpErr(504, null)]) {
    assert.equal(ss.clusterPollFailure(e), 'RECONNECT', e.message);
  }
  assert.equal(ss.clusterPollFailure(httpErr(401, null)), 'AUTH');
  assert.equal(ss.clusterPollFailure(httpErr(403, 'ADMIN_REQUIRED')), 'AUTH');
  assert.equal(ss.clusterPollFailure(httpErr(404, 'AUTHORITY_JOB_NOT_FOUND')), 'NOT_FOUND');
  assert.equal(ss.clusterPollFailure(httpErr(409, 'X')), 'OTHER');
  assert.equal(ss.clusterPollFailure(new ApiError({ kind: 'aborted' })), 'ABORTED');
  assert.equal(ss.clusterPollDelay(0), 4000);
  const delays = Array.from({ length: 30 }, (_, i) => ss.clusterPollDelay(i));
  assert.ok(delays.every((d, i) => d >= 4000 && d <= 15000 && (i === 0 || d >= delays[i - 1])));
  assert.equal(delays.at(-1), 15000);
});

test('接入 API：五個端點的路徑、方法與本文', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.createClusterPlan(SID, 2);
  await client.getClusterPlan(SID);
  await client.executeClusterPlan(SID);
  await client.getClusterJob(SID);
  await client.reconcileClusterJob(SID);
  assert.deepEqual(fetchImpl.calls.map((c) => `${c.init.method} ${c.url.replace('/admin/control/v1', '')}`), [
    `POST /servers/${SID}/cluster-plans`, `GET /server-cluster-plans/${SID}`, `POST /server-cluster-plans/${SID}/execute`,
    `GET /server-cluster-jobs/${SID}`, `POST /server-cluster-jobs/${SID}/reconcile`,
  ]);
  assert.deepEqual(fetchImpl.calls[0].body, { expectedRevision: 2 });
  assert.equal(fetchImpl.calls[2].body, undefined, 'execute 無本文');
  assert.equal(fetchImpl.calls[4].body, undefined, 'reconcile 無本文');
  for (const bad of [() => client.createClusterPlan('x', 1), () => client.createClusterPlan(SID), () => client.executeClusterPlan('../x'), () => client.getClusterJob(''), () => client.reconcileClusterJob('x')]) {
    await assert.rejects(bad(), (e) => e.kind === 'validation');
  }
  assert.equal(fetchImpl.calls.length, 5);
});

test('接入頁：execute 只在確認流程中呼叫一次；未知結果與輪詢只唯讀 GET；任務 UUID 只存記憶體', () => {
  const ui = read('servers-ui.js');
  const html = read('index.html');
  for (const id of ['server-cluster-panel', 'server-cluster-plan-create', 'server-cluster-execute', 'server-cluster-plan-view', 'server-cluster-job-panel', 'server-cluster-reconnect', 'server-cluster-job-view', 'server-cluster-job-reconcile', 'server-cluster-lookup-form', 'server-cluster-lookup-id']) {
    assert.ok(html.includes(`id="${id}"`), id);
  }
  assert.match(html, /<p id="server-cluster-reconnect" class="message message-amber" role="status" aria-live="polite" hidden>/);
  assert.match(html, /單站集群/);
  assert.match(html, /跨站同步尚未啟用/);
  for (const name of ['createClusterPlan', 'getClusterPlan', 'executeClusterPlan', 'getClusterJob', 'reconcileClusterJob']) {
    assert.equal((ui.match(new RegExp(`client\\.${name}\\(`, 'g')) ?? []).length, 1, `${name} 只在一處呼叫`);
  }
  const exec = ui.slice(ui.indexOf('async function executeCluster'), ui.indexOf('function stopClusterPoll'));
  const unknownBranch = exec.slice(exec.indexOf('} catch (err) {'));
  assert.ok(!unknownBranch.includes('executeClusterPlan'), '未知結果不重送');
  assert.match(unknownBranch, /startClusterWatch\(captured\.id\)/);
  assert.ok(exec.indexOf('client.getClusterPlan(') < exec.indexOf('client.executeClusterPlan('), '執行前重新讀取計畫');
  assert.ok(exec.indexOf('await load();') < exec.indexOf('client.executeClusterPlan('), '執行前重新讀取伺服器 revision');
  const poll = ui.slice(ui.indexOf('async function fetchClusterJob'), ui.indexOf('function setClusterJob'));
  assert.ok(!/execute|createClusterPlan|reconcileClusterJob/.test(poll), '輪詢只讀取任務');
  assert.match(poll, /kind === 'RECONNECT'[\s\S]*?scheduleClusterPoll\(\)/);
  assert.ok(!/sessionStorage|localStorage/.test(ui));
  const between = (a, b) => ui.slice(ui.indexOf(a), ui.indexOf(b, ui.indexOf(a)));
  assert.match(between('leave()', 'load,'), /stopClusterPoll\(\)/);
  assert.match(between('reset()', 'render,'), /stopClusterPoll\(\)[\s\S]*clusterJob = null; clusterPending = null;/);
});
