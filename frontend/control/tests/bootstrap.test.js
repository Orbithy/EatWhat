// 在新伺服器安裝中控與業務（controller-plans）：能力、表單、請求本文、預覽核對、權限批准、API 白名單與頁面約束。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as bs from '../bootstrap-state.js';
import * as ts from '../tasks-state.js';
import { createControlClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const SRC = '00000901-0000-4000-8000-000000000901', DST = '00000902-0000-4000-8000-000000000902', OTHER = '00000903-0000-4000-8000-000000000903';
const ID = '7c1e2b9a-41d3-4f6e-9a20-5d8b3e6f1c04';
const HASH = 'a'.repeat(64);
const PERMS = ['ADD_EXACT_PRIVATE_PEER', 'INSTALL_BUSINESS_AND_JOIN', 'INSTALL_RUNTIME_PACKAGES'];
const CAPS = bs.normalizeCapabilities({ supported: true, canPlan: true, reason: '', permissionsRequired: PERMS, storageProviders: ['GENERIC', 'RUSTFS', 'R2'], storageModes: ['SOURCE', 'CUSTOM'], planTtlSeconds: 900, backgroundExecution: true });
const LIST = {
  canManage: true,
  servers: [
    { id: SRC, name: 'campus', revision: 4, installation: { state: 'SUCCEEDED', clusterMembership: 'ACTIVE' } },
    { id: DST, name: 'sg-new', revision: 7, installation: null },
    { id: OTHER, name: 'edge-installed', revision: 2, installation: { state: 'SUCCEEDED', clusterMembership: 'NOT_JOINED' } },
  ],
  releases: [{ id: 'release_2026_10_19', sha256: 'b'.repeat(64) }],
};
const form = (over = {}) => ({
  sourceId: SRC, targetId: DST, releaseId: 'release_2026_10_19', allocationSlot: '5', sourceAddress: '10.253.74.1', targetAddress: '10.253.74.3',
  targetEndpoint: 'sg.example.org', wireguardPort: '51820', httpPort: '18090', appBaseUrl: 'https://sg.example.org/api',
  storageMode: 'SOURCE', provider: 'GENERIC', endpoint: '', region: '', bucket: '', accessKey: '', secretKey: '', pathStyle: true, publicBaseUrl: '', cdnEndpoint: '', ...over,
});
const CUSTOM = { storageMode: 'CUSTOM', provider: 'RUSTFS', endpoint: 'http://10.0.0.9:9000', region: 'us-east-1', bucket: 'eatwhat', accessKey: 'AKIASYNTH', secretKey: 'SyntheticSecret', pathStyle: true };

test('能力：只有 supported＝true 且欄位完整才可用；canPlan＝false 保留原因碼', () => {
  assert.deepEqual([CAPS.available, CAPS.canPlan, CAPS.planTtlSeconds, CAPS.backgroundExecution], [true, true, 900, true]);
  const blocked = bs.normalizeCapabilities({ supported: true, canPlan: false, reason: 'CONTROL_LEASE_NOT_HELD', permissionsRequired: PERMS, storageProviders: ['R2'], storageModes: ['SOURCE'] });
  assert.deepEqual([blocked.available, blocked.canPlan, blocked.reason, blocked.storageProviders], [true, false, 'CONTROL_LEASE_NOT_HELD', ['R2']]);
  for (const bad of [null, [], { supported: false }, { supported: 'true', canPlan: true }, { supported: true, canPlan: 'yes', permissionsRequired: [], storageProviders: [], storageModes: [] }, { supported: true, canPlan: true, permissionsRequired: 'X', storageProviders: [], storageModes: [] }]) {
    const c = bs.normalizeCapabilities(bad);
    assert.equal(c.available, false, JSON.stringify(bad));
    assert.equal(c.canPlan, false);
  }
  assert.deepEqual(bs.normalizeCapabilities({ ...{ supported: true, canPlan: true, permissionsRequired: PERMS, storageModes: ['SOURCE'] }, storageProviders: ['MINIO', 'R2'] }).storageProviders, ['R2'], '未知提供者不採用');
});

test('候選伺服器：來源可為任何已登記伺服器（ACTIVE、CAMPUS 排前；無安裝記錄不排除）；目標需尚未安裝', () => {
  const c = bs.serverCandidates(LIST);
  assert.deepEqual(c.sources.map((s) => s.id), [SRC, DST, OTHER], 'ACTIVE 在前，未安裝其次，其他安裝記錄最後');
  assert.deepEqual(c.targets.map((s) => s.id), [DST]);
  const MANUAL = '00000904-0000-4000-8000-000000000904';
  const raw = { canManage: true, releases: LIST.releases, servers: [{ id: MANUAL, name: 'legacy-authority', revision: 1, installation: null }, ...LIST.servers.slice(1)] };
  const c2 = bs.serverCandidates(raw);
  assert.ok(c2.sources.some((s) => s.id === MANUAL), '原已運行、剛登記（NOT_INSTALLED→null）的中控可作為來源');
  assert.match(bs.sourceNote(c2.sources.find((s) => s.id === MANUAL)), /不代表沒有運行中的中控/);
  assert.equal(c2.sources.find((s) => s.id === MANUAL).installation, null, '不把來源改寫成已安裝');
  assert.deepEqual(bs.formProblems(form({ sourceId: MANUAL }), { list: raw, capabilities: CAPS }).errors, []);
  assert.ok(bs.formProblems(form({ sourceId: DST, targetId: DST }), { list: raw, capabilities: CAPS }).errors.some((e) => /來源與目標必須不同/.test(e)), '同 UUID 禁止');
  assert.ok(bs.formProblems(form({ targetId: SRC }), { list: raw, capabilities: CAPS }).errors.some((e) => /空的目標伺服器/.test(e)), '目標仍需未安裝');
  assert.equal(bs.sourceRank({ installation: { mode: 'CAMPUS', clusterMembership: 'NOT_JOINED' } }), 1);
  assert.deepEqual(bs.serverCandidates({ servers: [{ id: 'bad' }, null] }).sources, []);
});

test('表單：私網 IPv4、入口主機、端口與保留端口、含 /api 的業務網址、來源≠目標', () => {
  assert.deepEqual(bs.formProblems(form(), { list: LIST, capabilities: CAPS }).errors, []);
  const has = (over, re) => assert.ok(bs.formProblems(form(over), { list: LIST, capabilities: CAPS }).errors.some((e) => re.test(e)), `${JSON.stringify(over)} ${re}`);
  has({ sourceId: '' }, /現有來源中控/);
  has({ sourceId: 'not-registered' }, /現有來源中控/);
  assert.deepEqual(bs.formProblems(form({ sourceId: OTHER }), { list: LIST, capabilities: CAPS }).errors, [], '有其他安裝記錄的已登記伺服器也可作為來源');
  has({ targetId: SRC }, /空的目標伺服器/);
  has({ releaseId: 'nope' }, /業務版本/);
  for (const slot of ['0', '1024', 'x', '']) has({ allocationSlot: slot }, /分配槽位/);
  for (const a of ['8.8.8.8', '172.32.0.1', '10.0.0', '10.0.0.01', '192.169.1.1', '']) has({ sourceAddress: a }, /來源私網地址/);
  has({ targetAddress: '10.253.74.1' }, /必須不同/);
  for (const e of ['sg.example.org:51820', '-bad', 'a..b', 'https://x', '']) has({ targetEndpoint: e }, /WireGuard 入口主機/);
  has({ wireguardPort: '80' }, /WireGuard 端口/);
  for (const p of ['18080', '25432', '16379', '28090', '18081']) has({ httpPort: p }, /保留端口/);
  has({ appBaseUrl: 'https://sg.example.org' }, /\/api/);
  has({ appBaseUrl: 'https://sg.example.org/apix' }, /\/api/);
  for (const u of ['https://u:p@sg.example.org/api', 'https://sg.example.org/api?x=1', 'https://sg.example.org/api#a', 'ftp://sg.example.org/api']) has({ appBaseUrl: u }, /http／https/);
  assert.deepEqual(bs.formProblems(form({ appBaseUrl: 'https://sg.example.org/eat/api/' }), { list: LIST, capabilities: CAPS }).errors, []);
});

test('自訂儲存：完整欄位必填；R2 不需區域；網址過濾；後端未宣告的方式／提供者不可用', () => {
  assert.deepEqual(bs.formProblems(form(CUSTOM), { list: LIST, capabilities: CAPS }).errors, []);
  const errs = (over, caps = CAPS) => bs.formProblems(form({ ...CUSTOM, ...over }), { list: LIST, capabilities: caps }).errors;
  for (const [over, re] of [[{ endpoint: 'https://k:s@s3.example.org' }, /S3 API 地址/], [{ endpoint: 'https://s3.example.org/?sig=1' }, /S3 API 地址/], [{ region: '' }, /區域/],
    [{ bucket: '' }, /桶名稱/], [{ accessKey: '' }, /Access Key/], [{ secretKey: '' }, /Secret Key/], [{ secretKey: ' padded ' }, /Secret Key/], [{ bucket: '${env}' }, /桶名稱/],
    [{ publicBaseUrl: 'https://x.org/?t=1' }, /公開網址/], [{ cdnEndpoint: 'javascript:alert(1)' }, /CDN 地址/], [{ provider: 'MINIO' }, /提供者/]]) {
    assert.ok(errs(over).some((e) => re.test(e)), JSON.stringify(over));
  }
  assert.deepEqual(errs({ provider: 'R2', region: '', endpoint: 'https://acc.r2.cloudflarestorage.com', pathStyle: false }), []);
  const noCustom = bs.normalizeCapabilities({ supported: true, canPlan: true, permissionsRequired: PERMS, storageProviders: ['RUSTFS'], storageModes: ['SOURCE'] });
  assert.ok(errs({}, noCustom).some((e) => /儲存方式/.test(e)));
  assert.ok(errs({ provider: 'R2' }, noCustom).some((e) => /提供者/.test(e)));
});

test('請求本文：只含契約欄位；沿用來源不送 storage；R2 區域固定 auto；不含 epoch／路徑／雜湊', () => {
  const src = bs.planRequestBody(form(), { id: ID, list: LIST, capabilities: CAPS });
  assert.deepEqual(Object.keys(src).sort(), ['allocationSlot', 'appBaseUrl', 'httpPort', 'id', 'releaseId', 'sourceAddress', 'sourceRevision', 'sourceServerId', 'targetAddress', 'targetEndpoint', 'targetRevision', 'targetServerId', 'wireguardPort']);
  assert.deepEqual([src.sourceRevision, src.targetRevision, src.allocationSlot, src.wireguardPort, src.httpPort], [4, 7, 5, 51820, 18090]);
  const r2 = bs.planRequestBody(form({ ...CUSTOM, provider: 'R2', region: 'eu-west-1', endpoint: 'https://acc.r2.cloudflarestorage.com', pathStyle: false, cdnEndpoint: 'https://cdn.example.org' }), { id: ID, list: LIST, capabilities: CAPS });
  assert.deepEqual(r2.storage, { provider: 'R2', endpoint: 'https://acc.r2.cloudflarestorage.com', region: 'auto', bucket: 'eatwhat', accessKey: 'AKIASYNTH', secretKey: 'SyntheticSecret', pathStyle: false, cdnEndpoint: 'https://cdn.example.org' });
  assert.equal(bs.planRequestBody(form({ sourceAddress: '8.8.8.8' }), { id: ID, list: LIST, capabilities: CAPS }), null);
  assert.equal(bs.planRequestBody(form(), { id: 'nope', list: LIST, capabilities: CAPS }), null);
  assert.ok(!/epoch|Path|Hash|actor|admin/i.test(JSON.stringify(Object.keys(r2))));
});

test('意圖簽章：不含密鑰；換伺服器、地址或儲存設定就不同', () => {
  const a = bs.intentSignature(form(CUSTOM), LIST);
  assert.ok(!a.includes('AKIASYNTH') && !a.includes('SyntheticSecret'));
  assert.equal(a, bs.intentSignature(form({ ...CUSTOM, accessKey: '', secretKey: '' }), LIST), '密鑰清除後簽章不變');
  for (const over of [{ targetId: OTHER }, { targetAddress: '10.253.74.4' }, { storageMode: 'SOURCE' }, { ...CUSTOM, bucket: 'other' }, { ...CUSTOM, provider: 'GENERIC' }]) {
    assert.notEqual(bs.intentSignature(form({ ...CUSTOM, ...over }), LIST), a, JSON.stringify(over));
  }
});

const uuid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const SENT = { id: ID, sourceServerId: SRC, sourceRevision: 4, targetServerId: DST, targetRevision: 7, releaseId: 'release_2026_10_19', allocationSlot: 5,
  review: { sourceAddress: '10.253.74.1', targetAddress: '10.253.74.3', targetEndpoint: 'sg.example.org', wireguardPort: 51820, httpPort: 18090, appBaseUrl: 'https://sg.example.org/api' }, storageMode: 'SOURCE', provider: null };
const rawPreview = (over = {}) => ({
  workflowVersion: 2, id: ID, kind: 'CONTROLLER_INSTALL', sourceServerId: SRC, serverId: DST, sourceRevision: 4, serverRevision: 7, clusterId: uuid(99), epoch: 3,
  controllerSha256: 'c'.repeat(64), releaseId: 'release_2026_10_19', artifactSha256: 'b'.repeat(64), allocationSlot: 5, review: { ...SENT.review },
  storage: { mode: 'SOURCE', provider: 'RUSTFS', endpoint: 'http://127.0.0.1:9000', region: 'us-east-1', bucket: 'eatwhat', pathStyle: true, credentialsConfigured: true },
  permissionsRequired: PERMS, requestHash: HASH, state: 'PREVIEW_ONLY', expiresAt: new Date(Date.now() + 600000).toISOString(), requiresReapproval: false, requiresReconciliation: false,
  steps: ts.CONTROLLER_PHASES.map((phase, i) => ({ phase, id: uuid(i + 1), state: 'PENDING' })), ...over,
});
const preview = (over) => bs.normalizePreview(rawPreview(over), ID);

test('預覽核對：必須與送出的非秘密輸入一致、PREVIEW_ONLY、有 hash／權限／有效期、儲存方式相符且已封存', () => {
  assert.deepEqual(bs.previewProblems(preview(), SENT), []);
  const has = (over, re, sent = SENT) => assert.ok(bs.previewProblems(preview(over), sent).some((p) => re.test(p)), `${JSON.stringify(over).slice(0, 60)} ${re}`);
  has({ serverId: OTHER }, /目標伺服器/);
  has({ serverRevision: 8 }, /目標伺服器/);
  has({ sourceServerId: OTHER }, /來源伺服器/);
  has({ releaseId: 'other' }, /業務版本/);
  has({ review: { ...SENT.review, targetAddress: '10.253.74.9' } }, /私網或業務網址/);
  has({ state: 'QUEUED' }, /PREVIEW_ONLY/);
  has({ requestHash: undefined }, /requestHash/);
  has({ requestHash: 'XYZ' }, /requestHash/);
  has({ permissionsRequired: [] }, /權限清單/);
  has({ permissionsRequired: undefined }, /權限清單/);
  has({ expiresAt: 'bad' }, /有效期未知/);
  has({ expiresAt: new Date(Date.now() - 1000).toISOString() }, /過期/);
  has({ storage: undefined }, /儲存摘要/);
  has({ storage: { ...rawPreview().storage, mode: 'CUSTOM' } }, /儲存方式/);
  has({ storage: { ...rawPreview().storage, credentialsConfigured: undefined } }, /憑據已封存/);
  has({}, /儲存提供者/, { ...SENT, storageMode: 'CUSTOM', provider: 'R2' });
  has({ requiresReapproval: true }, /重新批准/);
  has({ controllerSha256: 'x' }, /SHA-256/);
  assert.ok(bs.previewProblems(preview(), { ...SENT, id: uuid(5) }).some((p) => /UUID/.test(p)), '另一個請求的預覽不能用');
  assert.equal(bs.normalizePreview(rawPreview(), uuid(5)), null, '回應 UUID 與請求不同');
  assert.deepEqual(bs.previewProblems(null, SENT), ['預覽回應格式無效。']);
});

test('批准本文：只有逐項確認全部權限（不多不少）才產生，內容為原 hash 與預覽權限', () => {
  const p = preview();
  assert.equal(bs.approvalBody(p, new Set()), null);
  assert.equal(bs.approvalBody(p, new Set(PERMS.slice(0, 2))), null, '少一項');
  assert.equal(bs.approvalBody(p, new Set([...PERMS, 'EXTRA_PERMISSION'])), null, '多一項');
  assert.equal(bs.approvalBody(p, new Set([PERMS[0], PERMS[1], 'OTHER'])), null, '數量相同但內容不同');
  assert.deepEqual(bs.approvalBody(p, new Set([...PERMS].reverse())), { requestHash: HASH, permissions: PERMS });
  assert.equal(bs.approvalBody({ ...p, requestHash: null }, new Set(PERMS)), null);
  for (const code of PERMS) assert.ok(bs.permissionText(code).length > 4);
  assert.match(bs.permissionText('FUTURE_PERMISSION'), /前端沒有說明/);
  assert.match(bs.bootstrapCodeText('BOOTSTRAP_SOURCE_CONTROLLER_UPGRADE_REQUIRED'), /更新/);
});

test('API：四個固定路徑；本文白名單（拒絕 epoch／路徑／雜湊等額外欄位）；批准本文只含 hash 與權限', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.getControllerCapabilities();
  const body = bs.planRequestBody(form(CUSTOM), { id: ID, list: LIST, capabilities: CAPS });
  await client.createControllerPlan(body);
  await client.getControllerPlan(ID);
  await client.executeControllerPlan(ID, { requestHash: HASH, permissions: PERMS });
  assert.deepEqual(fetchImpl.calls.map((c) => `${c.init.method} ${c.url.replace(/^.*\/admin\/control\/v1/, '')}`),
    ['GET /controller-plans', 'POST /controller-plans', `GET /controller-plans/${ID}`, `POST /controller-plans/${ID}/execute`]);
  assert.deepEqual(fetchImpl.calls[3].body, { requestHash: HASH, permissions: PERMS });
  const bad = [{ ...body, epoch: 3 }, { ...body, configPath: '/etc/x' }, { ...body, sourceServerId: DST }, { ...body, allocationSlot: 0 }, { ...body, httpPort: 80 },
    { ...body, storage: { ...body.storage, extra: 1 } }, { ...body, storage: { ...body.storage, provider: 'R2', region: 'us-east-1' } }, { ...body, storage: { ...body.storage, secretKey: '' } }];
  for (const b of bad) await assert.rejects(client.createControllerPlan(b), (e) => e.kind === 'validation', JSON.stringify(Object.keys(b)));
  for (const a of [{ requestHash: HASH }, { requestHash: 'A'.repeat(64), permissions: PERMS }, { requestHash: HASH, permissions: [] }, { requestHash: HASH, permissions: [PERMS[0], PERMS[0]] }, { requestHash: HASH, permissions: PERMS, extra: 1 }]) {
    await assert.rejects(client.executeControllerPlan(ID, a), (e) => e.kind === 'validation', JSON.stringify(a));
  }
  await assert.rejects(client.getControllerPlan('../x'), (e) => e.kind === 'validation');
  assert.equal(fetchImpl.calls.length, 4);
});

test('頁面靜態：送出前清除密鑰；未知結果不自動重送；權限不預先勾選；確認後重讀原預覽；不呼叫單獨業務安裝；不寫瀏覽器儲存', () => {
  const ui = read('bootstrap-ui.js');
  const code = ui.replace(/\/\/.*$/gm, '');
  const create = code.slice(code.indexOf('async function createPreview'), code.indexOf('function acceptPreview'));
  assert.ok(create.indexOf('clearSecrets();') > 0 && create.indexOf('clearSecrets();') < create.indexOf('client.createControllerPlan('), '送出前清除密鑰');
  assert.equal((create.match(/client\.createControllerPlan\(/g) ?? []).length, 1);
  assert.ok(!create.slice(create.indexOf('} catch (err) {')).includes('createControllerPlan'), '未知結果不重送');
  assert.match(create, /pendingId = id;/);
  assert.match(create, /if \(busy \|\| pendingId \|\| !caps\?\.canPlan/);
  assert.equal((code.match(/client\.createControllerPlan\(/g) ?? []).length, 1);
  const lookup = code.slice(code.indexOf('async function lookupPreview'), code.indexOf('function approveProblems'));
  assert.ok(lookup.includes('client.getControllerPlan(id)') && !/createControllerPlan|executeControllerPlan/.test(lookup));
  const approve = code.slice(code.indexOf('async function approve()'), code.indexOf('function queueAccepted'));
  const order = ['confirmDialog(', 'client.getControllerPlan(captured.id)', 'previewProblems(now, sent)', 'now.requestHash !== captured.requestHash', 'client.executeControllerPlan('].map((k) => approve.indexOf(k));
  assert.ok(order.every((i, n) => i > 0 && (n === 0 || i > order[n - 1])), `批准順序 ${order}`);
  assert.ok(!approve.slice(approve.indexOf('} catch (err) {')).includes('executeControllerPlan'), '批准未知不重送');
  assert.equal((code.match(/client\.executeControllerPlan\(/g) ?? []).length, 1);
  assert.match(code, /h\('input', \{ type: 'checkbox', 'data-permission': code \}\)/, '權限不預先勾選');
  assert.ok(!/checked: true/.test(code));
  assert.ok(!/localStorage|sessionStorage|console\.|createServerPlan|executeServerPlan/.test(code));
  assert.match(code, /leave\(\) \{[\s\S]*?abandonInflight\(\);\s*gen \+= 1;[\s\S]*?clearSecrets\(\);/);
  assert.match(code, /reset\(\) \{[\s\S]*?clearSecrets\(\);/);
  const html = read('index.html');
  assert.match(html, /id="bootstrap-access-key" type="password"/);
  assert.match(html, /id="bootstrap-secret-key" type="password"/);
  assert.match(html, /id="server-add" class="btn btn-primary"/, '保留原有入口');
  for (const id of ['bootstrap-source-address', 'bootstrap-target-address', 'bootstrap-endpoint', 'bootstrap-app-url', 'bootstrap-slot', 'bootstrap-wg-port', 'bootstrap-http-port']) {
    assert.match(html, new RegExp(`id="${id}"(?![^>]*value=)[^>]*>`), `${id} 不預填`);
  }
  assert.ok(!/epoch|配置雜湊|制品路徑/.test(html.slice(html.indexOf('id="bootstrap-form"'), html.indexOf('</form>', html.indexOf('id="bootstrap-form"')))), '表單不要求 epoch／路徑／雜湊');
  const app = read('app.js');
  assert.match(app, /bootstrap\.reset\(\);/);
  assert.match(app, /if \(route === 'servers'\) bootstrap\.enter\(\);\s*else bootstrap\.leave\(\);/);
  assert.match(read('tasks-ui.js'), /openControllerJob\(id\) \{[\s\S]*?else pendingOpen = id;/);
});

test('任務詳情：執行診斷碼只顯示固定代碼與時間，不改變完成判定', () => {
  const j = ts.normalizeControllerJob({ ...rawPreview({ state: 'RUNNING', executionCode: 'BOOTSTRAP_PHASE_RECONCILIATION_REQUIRED', executionObservedAt: '2026-10-19T01:00:00Z' }) }, ID);
  assert.deepEqual([j.executionCode, j.executionObservedAt], ['BOOTSTRAP_PHASE_RECONCILIATION_REQUIRED', '2026-10-19T01:00:00Z']);
  assert.equal(ts.normalizeControllerJob(rawPreview({ executionCode: 'lower<script>' }), ID).executionCode, null);
  assert.match(read('tasks-ui.js'), /固定診斷碼，不代表原生成功或失敗/);
  assert.equal(ts.controllerJobDisplay(j).complete, false);
});

// ---------- 審閱補充：全部非秘密選項納入簽章；預覽儲存摘要逐項比對；重新輸入密鑰作廢預覽 ----------
const R2_FORM = { ...CUSTOM, provider: 'R2', endpoint: 'https://acc.r2.cloudflarestorage.com', region: '', pathStyle: false, publicBaseUrl: 'https://files.example.org', cdnEndpoint: 'https://cdn.example.org' };

test('簽章：pathStyle、公開網址、CDN、端點、區域、桶、提供者都會改變簽章；密鑰不會', () => {
  const base = bs.intentSignature(form(R2_FORM), LIST);
  for (const over of [{ pathStyle: true }, { publicBaseUrl: 'https://other.example.org' }, { publicBaseUrl: '' }, { cdnEndpoint: '' }, { cdnEndpoint: 'https://cdn2.example.org' },
    { endpoint: 'https://acc2.r2.cloudflarestorage.com' }, { bucket: 'other' }, { provider: 'GENERIC', region: 'auto' }]) {
    assert.notEqual(bs.intentSignature(form({ ...R2_FORM, ...over }), LIST), base, JSON.stringify(over));
  }
  assert.equal(bs.intentSignature(form({ ...R2_FORM, accessKey: 'NEW', secretKey: 'NEW' }), LIST), base);
  assert.notEqual(bs.intentSignature(form({ ...CUSTOM, region: 'eu-west-1' }), LIST), bs.intentSignature(form(CUSTOM), LIST), 'RustFS 區域');
  assert.ok(!base.includes('AKIASYNTH') && !base.includes('SyntheticSecret'));
  // R2 的區域依後端規則固定為 auto：輸入其他值不改變送出內容
  assert.equal(bs.storageSummary(form({ ...R2_FORM, region: 'eu' })).region, 'auto');
  assert.equal(bs.storageSummary(form()), null, '沿用來源沒有自訂摘要');
  assert.deepEqual(Object.keys(bs.storageSummary(form(R2_FORM))).sort(), ['bucket', 'cdnEndpoint', 'endpoint', 'pathStyle', 'provider', 'publicBaseUrl', 'region']);
});

test('預覽儲存摘要逐項比對：錯桶／錯 endpoint／錯區域／錯 path style／錯公開網址／錯 CDN 都不能批准', () => {
  const sentCustom = { ...SENT, storageMode: 'CUSTOM', storage: bs.storageSummary(form(R2_FORM)) };
  const okStorage = { mode: 'CUSTOM', provider: 'R2', endpoint: 'https://acc.r2.cloudflarestorage.com', region: 'auto', bucket: 'eatwhat', pathStyle: false, 'public-base-url': 'https://files.example.org', 'cdn-endpoint': 'https://cdn.example.org', credentialsConfigured: true };
  assert.deepEqual(bs.previewProblems(preview({ storage: okStorage }), sentCustom), []);
  for (const [over, re] of [[{ bucket: 'wrong' }, /桶/], [{ endpoint: 'https://evil.r2.cloudflarestorage.com' }, /S3 API 地址/], [{ region: 'us-east-1' }, /區域/], [{ pathStyle: true }, /path style/],
    [{ 'public-base-url': '' }, /公開網址/], [{ 'cdn-endpoint': 'https://other.example.org' }, /CDN 地址/], [{ endpoint: 'https://u:p@acc.r2.cloudflarestorage.com' }, /S3 API 地址/], [{ provider: 'GENERIC' }, /提供者/]]) {
    const problems = bs.previewProblems(preview({ storage: { ...okStorage, ...over } }), sentCustom);
    assert.ok(problems.some((p) => re.test(p)), `${JSON.stringify(over)} ${re}`);
  }
  // 公開網址都未設定：後端回空字串，與送出的未設定一致
  const none = { ...SENT, storageMode: 'CUSTOM', storage: bs.storageSummary(form({ ...R2_FORM, publicBaseUrl: '', cdnEndpoint: '' })) };
  assert.deepEqual(bs.previewProblems(preview({ storage: { ...okStorage, 'public-base-url': '', 'cdn-endpoint': '' } }), none), []);
});

test('頁面靜態：重新輸入密鑰直接作廢預覽；送出摘要不含密鑰；權限勾選不在表單內', () => {
  const ui = read('bootstrap-ui.js');
  assert.match(ui, /const secretEdit = event\.target === els\.accessKey \|\| event\.target === els\.secretKey;/);
  assert.match(ui, /if \(preview && \(secretEdit \|\| bs\.intentSignature\(readForm\(\), list\) !== previewSig\)\) discardPreview/);
  const sentBlock = ui.slice(ui.indexOf('sent = {'), ui.indexOf('};', ui.indexOf('sent = {')));
  assert.ok(!/accessKey|secretKey/.test(sentBlock), 'sent 不含密鑰');
  assert.match(sentBlock, /storage: bs\.storageSummary\(form\)/);
  const html = read('index.html');
  const formHtml = html.slice(html.indexOf('id="bootstrap-form"'), html.indexOf('</form>', html.indexOf('id="bootstrap-form"')));
  assert.ok(!formHtml.includes('bootstrap-permission-list'), '權限清單不在部署表單內，勾選不算修改輸入');
});
