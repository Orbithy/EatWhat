// 安裝身份（CAMPUS／EXTERNAL）、部署階段與手動恢復舊版、權威診斷邊界。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as ss from '../servers-state.js';
import * as dr from '../deployment-recovery-state.js';
import * as ts from '../tasks-state.js';
import { createControlClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const SID = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const JOB = '1e4c8d3b-0a52-4f7c-9b63-2d8f0a4e3c21';
const REC = '7c1e2b9a-41d3-4f6e-9a20-5d8b3e6f1c04';
const OLD = 'a'.repeat(64), CUR = 'b'.repeat(64);

// ---------- 安裝身份 ----------

test('能力：兩欄位都缺是舊後端；只缺一個、格式錯或角色都未知是能力未知並停用', () => {
  assert.deepEqual(ss.identityCapability({}), { supported: false, unknown: false, required: false, modes: [], reason: null });
  for (const list of [{ installationModes: ['CAMPUS', 'EXTERNAL'] }, { installationIdentityRequired: true }, { installationModes: 'CAMPUS', installationIdentityRequired: true },
    { installationModes: ['CAMPUS'], installationIdentityRequired: 'yes' }, { installationModes: ['FOO'], installationIdentityRequired: true }]) {
    const c = ss.identityCapability(list);
    assert.equal(c.supported, false, JSON.stringify(list));
    assert.equal(c.unknown, true);
    assert.ok(c.reason);
  }
  const full = ss.identityCapability({ installationModes: ['CAMPUS', 'EXTERNAL', 'FUTURE'], installationIdentityRequired: true });
  assert.deepEqual([full.supported, full.required, full.modes], [true, true, ['CAMPUS', 'EXTERNAL']]);
  assert.deepEqual(ss.identityCapability({ installationModes: ['CAMPUS'], installationIdentityRequired: false }).modes, ['CAMPUS']);
});

test('身份驗證：必須選擇時要明確角色；EXTERNAL 分配編號 1–1023；未支援角色擋下', () => {
  const cap = ss.identityCapability({ installationModes: ['CAMPUS', 'EXTERNAL'], installationIdentityRequired: true });
  assert.match(ss.identityProblems({ siteMode: '' }, cap)[0], /校內權威站或校外業務站/);
  assert.deepEqual(ss.identityProblems({ siteMode: 'CAMPUS' }, cap), []);
  for (const slot of ['1', '1023']) assert.deepEqual(ss.identityProblems({ siteMode: 'EXTERNAL', allocationSlot: slot }, cap), [], slot);
  for (const slot of ['0', '1024', '', 'x', '2.5']) assert.ok(ss.identityProblems({ siteMode: 'EXTERNAL', allocationSlot: slot }, cap).length, slot);
  const campusOnly = ss.identityCapability({ installationModes: ['CAMPUS'], installationIdentityRequired: false });
  assert.deepEqual(ss.identityProblems({ siteMode: '' }, campusOnly), [], '非必須時可不分配');
  assert.ok(ss.identityProblems({ siteMode: 'EXTERNAL', allocationSlot: '3' }, campusOnly).length);
  assert.deepEqual(ss.identityProblems({ siteMode: '' }, ss.identityCapability({})), [], '舊後端不檢查身份');
});

const server = (inst = null, over = {}) => ss.normalizeServer({ id: SID, name: 'x', revision: 3, location: 'REMOTE', host: '203.0.113.9', port: 22, username: 'root', authentication: 'PASSWORD', hasCredential: true, connection: { state: 'CONNECTED' }, installation: inst, ...over });
const form = { releaseId: 'r1', httpPort: '18090', appBaseUrl: 'https://b.example.org', installDocker: false };
const releases = [{ id: 'r1' }];

test('安裝本文：只有能力證明支援時才送 siteMode／allocationSlot；CAMPUS 固定 0；UPDATE 不送', () => {
  const cap = ss.identityCapability({ installationModes: ['CAMPUS', 'EXTERNAL'], installationIdentityRequired: true });
  const ext = ss.installRequestBody({ ...form, siteMode: 'EXTERNAL', allocationSlot: '7' }, { kind: 'INSTALL', server: server(), releases, identity: cap });
  assert.deepEqual([ext.siteMode, ext.allocationSlot], ['EXTERNAL', 7]);
  const campus = ss.installRequestBody({ ...form, siteMode: 'CAMPUS', allocationSlot: '9' }, { kind: 'INSTALL', server: server(), releases, identity: cap });
  assert.deepEqual([campus.siteMode, campus.allocationSlot], ['CAMPUS', 0]);
  assert.equal(ss.installRequestBody({ ...form, siteMode: '' }, { kind: 'INSTALL', server: server(), releases, identity: cap }), null, '必須選擇角色');
  const old = ss.installRequestBody({ ...form, siteMode: 'EXTERNAL', allocationSlot: '7' }, { kind: 'INSTALL', server: server(), releases, identity: ss.identityCapability({}) });
  assert.ok(!('siteMode' in old) && !('allocationSlot' in old), '舊後端保持舊契約');
  // 只缺一欄或格式錯：身份能力未知，停用建立計畫，不以舊契約送出。
  for (const list of [{ installationModes: ['EXTERNAL'] }, { installationIdentityRequired: true }, { installationModes: 'CAMPUS', installationIdentityRequired: false }, { installationModes: ['MOON'], installationIdentityRequired: true }]) {
    const cap = ss.identityCapability(list);
    for (const f of [{ ...form, siteMode: '' }, { ...form, siteMode: 'EXTERNAL', allocationSlot: '7' }]) {
      assert.equal(ss.installRequestBody(f, { kind: 'INSTALL', server: server(), releases, identity: cap }), null, JSON.stringify(list));
      assert.ok(ss.installFormCheck(f, { kind: 'INSTALL', releases, identity: cap }).some((e) => /身份能力未知/.test(e)));
    }
  }
  assert.deepEqual(ss.installFormCheck({ ...form, siteMode: '' }, { kind: 'INSTALL', releases, identity: ss.identityCapability({}) }), [], '兩欄都不存在的舊後端沿用舊請求');
  const upd = ss.installRequestBody({ ...form, siteMode: 'EXTERNAL', allocationSlot: '7' }, { kind: 'UPDATE', server: server({ state: 'SUCCEEDED', httpPort: 18090, appBaseUrl: 'https://b.example.org', clusterMembership: 'NOT_JOINED' }), releases, identity: cap });
  assert.ok(!('siteMode' in upd));
});

test('API：身份欄位必須成對且角色與編號相符；不接受 clusterId／siteId', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(201, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  const base = { expectedRevision: 3, releaseId: 'r1', httpPort: 18090, appBaseUrl: 'https://b.example.org', installDocker: false };
  await client.createServerPlan(SID, 'INSTALL', base);
  await client.createServerPlan(SID, 'INSTALL', { ...base, siteMode: 'CAMPUS', allocationSlot: 0 });
  await client.createServerPlan(SID, 'INSTALL', { ...base, siteMode: 'EXTERNAL', allocationSlot: 1023 });
  assert.deepEqual(fetchImpl.calls[2].body.allocationSlot, 1023);
  for (const bad of [{ siteMode: 'CAMPUS' }, { allocationSlot: 3 }, { siteMode: 'CAMPUS', allocationSlot: 1 }, { siteMode: 'EXTERNAL', allocationSlot: 0 }, { siteMode: 'EXTERNAL', allocationSlot: 1024 },
    { siteMode: 'OTHER', allocationSlot: 1 }, { siteMode: 'EXTERNAL', allocationSlot: 2, clusterId: SID }, { siteId: 'x' }, { instanceId: SID }]) {
    await assert.rejects(client.createServerPlan(SID, 'INSTALL', { ...base, ...bad }), (e) => e.kind === 'validation', JSON.stringify(bad));
  }
  assert.equal(fetchImpl.calls.length, 3);
});

test('卡片狀態：校外業務站安裝成功是等待接入，不是 ACTIVE；未知身份停用操作', () => {
  const ext = server({ state: 'SUCCEEDED', mode: 'EXTERNAL', clusterId: SID, allocationSlot: 4, clusterMembership: 'NOT_JOINED' });
  const st = ss.serverStatus(ext);
  assert.equal(st.label, '已安裝 · 等待接入');
  assert.ok(!st.joined && !/ACTIVE|同步/.test(st.label));
  assert.equal(ext.installation.allocationSlot, 4);
  assert.deepEqual(ss.updateAvailability(ext).reasons, ['已分配站點須完成集群接入與發布']);
  assert.ok(ss.enrollmentAvailability(ext, { clusterEnrollmentEnabled: true }).reasons.some((r) => /校外業務站/.test(r)));
  const allocatedCampus = server({ state: 'SUCCEEDED', mode: 'CAMPUS', clusterId: SID, allocationSlot: 0, clusterMembership: 'NOT_JOINED' });
  assert.equal(ss.updateAvailability(allocatedCampus).code, 'ALLOCATED_SITE_MEMBERSHIP_REQUIRED');
  const weird = server({ state: 'SUCCEEDED', mode: 'MOON', clusterMembership: 'NOT_JOINED' });
  assert.equal(ss.serverStatus(weird).unknownIdentity, true);
  assert.match(ss.updateAvailability(weird).reasons[0], /安裝身份未知/);
  const legacy = server({ state: 'SUCCEEDED', clusterMembership: 'NOT_JOINED' });
  assert.deepEqual(ss.updateAvailability(legacy).reasons, [], '舊安裝（無 mode）仍可用單機更新');
});

test('恢復安裝保留原角色；未知身份不能恢復；管理員處理文字對齊後端', () => {
  const failedExt = server({ state: 'FAILED', jobId: JOB, mode: 'EXTERNAL', clusterId: SID, allocationSlot: 5 });
  const src = { loading: false, error: null, job: { id: JOB, kind: 'INSTALL', state: 'FAILED' } };
  assert.equal(ss.recoveryAvailability(failedExt, src).mode, 'recover');
  assert.deepEqual(ss.recoveryRequestBody(failedExt), { expectedRevision: 3, sourceJobId: JOB }, '恢復不送角色，由後端沿用原值');
  assert.equal(ss.recoveryAvailability(server({ state: 'FAILED', jobId: JOB, mode: 'MOON' }), src).mode, 'blocked');
  const plan = ss.normalizePlan({ id: REC, kind: 'RECOVER', mode: 'EXTERNAL', clusterId: SID, allocationSlot: 5, administratorAction: 'AUTHORITY_ACCOUNTS_AFTER_JOIN' });
  assert.deepEqual([plan.mode, plan.allocationSlot, plan.clusterId], ['EXTERNAL', 5, SID]);
  assert.match(ss.ADMINISTRATOR_ACTION_TEXT.AUTHORITY_ACCOUNTS_AFTER_JOIN, /不複製管理員[\s\S]*沒有同步帳號/);
  for (const k of ['INITIALIZE_OPERATOR_ADMINISTRATOR', 'PRESERVE_EXISTING_OR_INITIALIZE_ONLY_IF_EMPTY']) assert.ok(ss.ADMINISTRATOR_ACTION_TEXT[k], k);
  for (const code of ['SITE_ROLE_REQUIRED', 'INVALID_SITE_ROLE', 'INVALID_SITE_ALLOCATION', 'CLUSTER_ROLE_SLOT_MISMATCH', 'SITE_ALLOCATION_REQUIRED', 'INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED',
    'COORDINATOR_MUST_JOIN_FIRST', 'INSTALLATION_ALLOCATION_RETAINED', 'MEMBER_IDENTITY_OR_SLOT_RETAINED', 'INSTALLED_SITE_IDENTITY_IMMUTABLE', 'ALLOCATED_SITE_MEMBERSHIP_REQUIRED']) {
    assert.ok(ss.serverCodeText(code), code);
  }
  assert.ok(!/刪除再添加|移除後重新/.test(ss.serverCodeText('INSTALLATION_ALLOCATION_RETAINED')));
});

// ---------- 部署階段 ----------

test('部署階段：簡短階段與失敗階段文字；刷新鍵包含 stage／failureStage', () => {
  assert.equal(dr.targetStageLine({ stage: 'INSTALLING' }), '階段 安裝（INSTALLING）');
  assert.equal(dr.targetStageLine({ stage: 'ROLLING_BACK', failureStage: 'STARTING' }), '階段 回退中（ROLLING_BACK） · 失敗於 啟動（STARTING）');
  assert.equal(dr.targetStageLine({ stage: 'NEW_STAGE' }), '階段 未定義（NEW_STAGE）');
  assert.equal(dr.targetStageLine({ stage: 'bad stage<script>' }), '');
  for (const s of ['UPLOAD', 'UPLOADED', 'BACKUP', 'INSTALLING', 'STARTING', 'ROLLING_BACK', 'COMPLETE']) assert.ok(dr.deployStageText(s), s);
  const job = { id: JOB, state: 'RUNNING', targets: [{ site: 'campus', state: 'DEPLOYING', stage: 'UPLOAD' }] };
  const k1 = dr.jobRenderKey(job);
  assert.notEqual(dr.jobRenderKey({ ...job, targets: [{ ...job.targets[0], stage: 'BACKUP' }] }), k1, '只改階段也要重繪');
  assert.notEqual(dr.jobRenderKey({ ...job, targets: [{ ...job.targets[0], failureStage: 'STARTING' }] }), k1);
});

// ---------- 手動恢復舊版 ----------

test('恢復記錄只保留公開欄位；不顯示 _remoteProof、憑據、capability', () => {
  const r = dr.normalizeRecovery({ id: REC, jobId: JOB, siteId: 'campus', state: 'PREVIEW_ONLY', restoreSha256: OLD, currentSha256: CUR, backupId: 'b1', executionEnabled: true,
    parentState: 'FAILED', parentReconciliationRequired: true, _remoteProof: { x: 1 }, credential: 's', capability: 'c', databaseSystemIdentifier: '123', stage: 'ACCEPTED' });
  for (const k of ['_remoteProof', 'credential', 'capability', 'databaseSystemIdentifier']) assert.ok(!(k in r), k);
  assert.deepEqual([r.restoreSha256, r.parentState, r.parentReconciliationRequired, r.stage], [OLD, 'FAILED', true, 'ACCEPTED']);
  assert.equal(dr.normalizeRecovery({ id: REC, state: 'DONE' }), null);
  assert.equal(dr.normalizeRecovery({ id: REC, state: 'SUCCEEDED', restoreSha256: 'short' }).restoreSha256, null);
  assert.equal(dr.normalizeRecovery({ id: REC, state: 'SUCCEEDED' }).parentReconciliationRequired, null, '缺值為未知');
  const attempts = dr.normalizeAttempts({ recoveryAttempts: [{ id: REC, siteId: 'campus', state: 'OUTCOME_UNKNOWN', stage: 'DISPATCH', code: 'DEPLOYMENT_RECOVERY_DISPATCH_NOT_CONFIRMED' }, { id: 'x' }] });
  assert.equal(attempts.length, 1);
  assert.ok(dr.recoveryCodeText('DEPLOYMENT_RECOVERY_DISPATCH_NOT_CONFIRMED'));
});

test('恢復權限：看目前租約所有權（localLeader 且 HELD）與恢復能力，不是新部署開關；缺資料停用', () => {
  assert.match(dr.leaseReasons(undefined)[0], /缺少目前租約所有權資料/);
  assert.match(dr.leaseReasons({ known: false })[0], /缺少/);
  assert.ok(dr.leaseReasons({ known: true, owned: false }).length);
  assert.deepEqual(dr.leaseReasons({ known: true, owned: true }), []);
  assert.ok(dr.capabilityReasons({}).length, '舊後端缺欄位不當支援');
  assert.ok(dr.capabilityReasons({ deploymentRecoveryEnabled: 'true' }).length);
  assert.deepEqual(dr.capabilityReasons({ deploymentRecoveryEnabled: true, deploymentExecutionEnabled: false }), [], '新部署停用不等於恢復停用');
  assert.equal(dr.targetRecoverable('OUTCOME_UNKNOWN', { state: 'DEPLOYING' }), true);
  for (const st of ['SUCCEEDED', 'ROLLED_BACK']) assert.equal(dr.targetRecoverable('OUTCOME_UNKNOWN', { state: st }), false, st);
  assert.equal(dr.targetRecoverable('FAILED', { state: 'DEPLOYING' }), false);
  const caps = { deploymentRecoveryEnabled: true };
  const own = { known: true, owned: true };
  const plan = { state: 'PREVIEW_ONLY', executionEnabled: true, restoreSha256: OLD, expiresAt: new Date(Date.now() + 60000).toISOString() };
  assert.deepEqual(dr.executeReasons({ caps, ownership: own, plan }), []);
  assert.ok(dr.executeReasons({ caps, ownership: own, plan: { ...plan, expiresAt: new Date(Date.now() - 1000).toISOString() } }).some((r) => /過期/.test(r)));
  assert.ok(dr.executeReasons({ caps, ownership: own, plan: { ...plan, state: 'RUNNING' } }).some((r) => /不會再次執行/.test(r)));
  assert.ok(dr.executeReasons({ caps, ownership: own, plan: { ...plan, restoreSha256: null } }).length);
  // 有效期缺失或無法解析：停用並說明有效期未知，不視為有效預覽。
  for (const expiresAt of [undefined, null, '', 'not-a-date', '2026-13-45T99:99:99Z']) {
    const r = dr.executeReasons({ caps, ownership: own, plan: { ...plan, expiresAt } });
    assert.ok(r.some((x) => /有效期未知/.test(x)), String(expiresAt));
  }
  assert.ok(dr.executeReasons({ caps, ownership: own, plan, busy: true }).length);
  assert.ok(dr.previewReasons({ caps, ownership: own, active: true }).some((r) => /進行中/.test(r)));
});

test('恢復 API：預覽只帶 requestId 與 siteId；執行只帶完整舊 SHA', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(200, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.createDeploymentRecoveryPlan(JOB, { requestId: REC, siteId: 'campus' });
  await client.getDeploymentRecoveryPlan(REC);
  await client.executeDeploymentRecoveryPlan(REC, { restoreSha256: OLD });
  await client.reconcileDeploymentRecoveryPlan(REC);
  assert.deepEqual(fetchImpl.calls.map((c) => `${c.init.method} ${c.url.replace('/admin/control/v1', '')}`), [
    `POST /deployments/${JOB}/recovery-plans`, `GET /deployment-recovery-plans/${REC}`, `POST /deployment-recovery-plans/${REC}/execute`, `POST /deployment-recovery-plans/${REC}/reconcile`]);
  assert.deepEqual(fetchImpl.calls[2].body, { restoreSha256: OLD });
  assert.equal(fetchImpl.calls[3].body, undefined);
  for (const bad of [() => client.createDeploymentRecoveryPlan(JOB, { requestId: REC }), () => client.createDeploymentRecoveryPlan(JOB, { requestId: REC, siteId: 'campus', backupId: 'x' }),
    () => client.executeDeploymentRecoveryPlan(REC, { restoreSha256: 'abc' }), () => client.executeDeploymentRecoveryPlan(REC, { restoreSha256: OLD, force: true }), () => client.reconcileDeploymentRecoveryPlan('x')]) {
    await assert.rejects(bad(), (e) => e.kind === 'validation');
  }
  assert.equal(fetchImpl.calls.length, 4);
});

test('靜態：恢復使用租約所有權、確認後重查、回執遺失只核對不重送；部署頁鍵含階段', () => {
  const ui = read('deployment-ui.js');
  const exec = ui.slice(ui.indexOf('async function executeRecovery'), ui.indexOf('async function reconcileRecovery'));
  assert.equal((ui.match(/recovery\.execute\(/g) ?? []).length, 1, 'execute 只在一處');
  assert.ok(exec.indexOf('confirmDialog(') < exec.indexOf('recovery.get(captured.id)') && exec.indexOf('recovery.get(captured.id)') < exec.indexOf('recovery.execute('), '確認後重查再送出');
  const unknownBranch = exec.slice(exec.indexOf('if (err?.outcomeUnknown'));
  assert.ok(!unknownBranch.includes('recovery.execute(') && !unknownBranch.includes('randomUUID'), '回執遺失不重送、不換 UUID');
  assert.match(unknownBranch, /rec\.submitUnknown = true/);
  assert.ok(!/extraReasons|lead\.writable|acceptingWrites/.test(ui.slice(ui.indexOf('// ---------- 手動恢復舊版'), ui.indexOf('function auth('))), '不沿用新部署寫入閘門');
  assert.match(read('cluster-app.js'), /leaseOwnership: d\.lead\.lead \? \{ known: true, owned: d\.lead\.lead\.localLeader === true && d\.lead\.lead\.state === 'HELD' \}/);
  assert.match(ui, /const key = JSON\.stringify\(\[dr\.jobRenderKey\(job\)/);
  assert.ok(!/_remoteProof/.test(ui));
});

// ---------- 權威診斷邊界 ----------

test('權威診斷：dispatch_authorized 缺失或非布林為未知；重讀中保留上次結果需標示', () => {
  const f = ts.normalizeFences({ available: true, active: [{ job_id: JOB }, { job_id: REC, dispatch_authorized: 'yes' }, { job_id: SID, dispatch_authorized: false }] });
  assert.deepEqual(f.active.map((x) => x.dispatchAuthorized), [null, null, false]);
  const ui = read('tasks-ui.js');
  assert.match(ui, /f\.dispatchAuthorized === true \? '已授權' : f\.dispatchAuthorized === false \? '未授權' : '未知'/);
  assert.match(ui, /正在重新讀取，以下為上次結果/);
  assert.match(ui, /上次結果沒有未結束的約束；目前狀態尚未確認/);
});
