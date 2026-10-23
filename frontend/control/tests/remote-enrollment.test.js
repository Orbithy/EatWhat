// 已安裝校外業務站的遠端接入（REMOTE_MEMBER）：入口條件、計畫、執行條件、阻擋碼與「不是已加入」的界線。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as ss from '../servers-state.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const SID = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const CID = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const IID = '11111111-1111-4111-8111-111111111111';

const ext = (inst = {}, over = {}) => ss.normalizeServer({
  id: SID, name: 'edge', revision: 3, location: 'REMOTE', host: '203.0.113.9', port: 22, username: 'root', authentication: 'PRIVATE_KEY', hasCredential: true,
  connection: { state: 'CONNECTED' },
  installation: { state: 'SUCCEEDED', mode: 'EXTERNAL', clusterId: CID, allocationSlot: 4, siteId: 'edge_1', instanceId: IID, clusterMembership: 'NOT_JOINED', ...inst }, ...over,
});
const list = (over = {}) => ({ clusterEnrollmentEnabled: true, remoteMemberEnrollmentSupported: true, ...over });

test('入口：只有 remoteMemberEnrollmentSupported 嚴格 true 才開放；不要求中控本機', () => {
  assert.deepEqual(ss.enrollmentAvailability(ext(), list()), { show: true, reasons: [], kind: 'REMOTE_MEMBER' });
  for (const supported of [undefined, 'true', 1, false]) {
    const r = ss.enrollmentAvailability(ext(), list({ remoteMemberEnrollmentSupported: supported }));
    assert.equal(r.kind, null, String(supported));
    assert.ok(r.reasons.length > 0);
  }
  assert.equal(ss.normalizeServerList({ servers: [], remoteMemberEnrollmentSupported: 'true' }).remoteMemberEnrollmentSupported, false);
  assert.equal(ss.normalizeServerList({ servers: [] }).remoteMemberEnrollmentSupported, false, '舊後端缺欄位');
  assert.ok(!ss.enrollmentAvailability(ext(), list()).reasons.some((r) => /中控本機/.test(r)));
});

test('入口：接入開關、root SSH、NOT_JOINED、分配 1–1023 與原身份都要完整', () => {
  const reasons = (inst, over, l = list()) => ss.enrollmentAvailability(ext(inst, over), l).reasons;
  assert.ok(reasons({}, {}, list({ clusterEnrollmentEnabled: false })).length);
  assert.ok(reasons({}, { username: 'deploy' }).some((r) => /root/.test(r)));
  for (const m of [undefined, 'JOINING', 'not_joined']) assert.ok(reasons({ clusterMembership: m }).length, String(m));
  for (const slot of [0, 1024, undefined, 2.5]) assert.ok(reasons({ allocationSlot: slot }).some((r) => /分配/.test(r)), String(slot));
  assert.ok(reasons({ clusterId: undefined }).length);
  assert.ok(reasons({ instanceId: undefined }).length);
  assert.deepEqual(reasons({ allocationSlot: 1 }), []);
  assert.deepEqual(reasons({ allocationSlot: 1023 }), []);
  assert.equal(ss.enrollmentAvailability(ext({ clusterMembership: 'ACTIVE' }), list()).show, false, '已加入不顯示');
  assert.equal(ss.enrollmentAvailability(ext({ mode: 'MOON' }), list()).kind, null, '未知角色停用');
});

test('校內權威站維持原 INITIAL_AUTHORITY 規則', () => {
  const campus = ss.normalizeServer({ id: SID, name: 'c', revision: 1, location: 'CONTROL_HOST', host: '127.0.0.1', port: 22, username: 'root', authentication: 'PASSWORD', hasCredential: true, connection: { state: 'CONNECTED' }, installation: { state: 'SUCCEEDED', mode: 'CAMPUS', clusterMembership: 'NOT_JOINED' } });
  assert.deepEqual(ss.enrollmentAvailability(campus, list()), { show: true, reasons: [], kind: 'INITIAL_AUTHORITY' });
  const remoteCampus = ss.normalizeServer({ ...campus, location: 'REMOTE', host: '203.0.113.5', installation: { state: 'SUCCEEDED', mode: 'CAMPUS', clusterMembership: 'NOT_JOINED' } });
  assert.ok(ss.enrollmentAvailability(remoteCampus, list()).reasons.some((r) => /中控本機/.test(r)));
});

const plan = (over = {}) => ss.normalizeClusterPlan({
  id: SID, kind: 'REMOTE_MEMBER', serverId: SID, serverRevision: 3, state: 'READY', blockers: [], expiresAt: new Date(Date.now() + 600000).toISOString(),
  siteId: 'edge_1', instanceId: IID, clusterId: CID, allocationSlot: 4, mode: 'EXTERNAL', artifactSha256: 'a'.repeat(64), profileId: 'external-1', baseEpoch: 1,
  singleSiteOnly: false, controllerRestartExpected: false, originalDatabasePreserved: true, _private: 'x', ...over,
});

test('REMOTE_MEMBER 計畫：公開欄位、不帶私有欄位；未知 kind 不接受', () => {
  const p = plan();
  assert.deepEqual([p.kind, p.mode, p.allocationSlot, p.baseEpoch, p.profileId, p.singleSiteOnly, p.controllerRestartExpected, p.originalDatabasePreserved], ['REMOTE_MEMBER', 'EXTERNAL', 4, 1, 'external-1', false, false, true]);
  assert.ok(!('_private' in p));
  assert.equal(ss.normalizeClusterPlan({ id: SID, kind: 'SECOND_CONTROLLER', state: 'READY' }), null);
  assert.deepEqual(ss.clusterPlanProblems(p), []);
});

test('執行條件：READY、有效期可解析且未過期、保留原資料、身份完整與 baseEpoch', () => {
  for (const expiresAt of [undefined, '', 'nope', '2026-99-99T00:00:00Z']) assert.ok(ss.clusterPlanProblems(plan({ expiresAt })).some((x) => /有效期未知/.test(x)), String(expiresAt));
  assert.ok(ss.clusterPlanProblems(plan({ expiresAt: new Date(Date.now() - 1000).toISOString() })).some((x) => /過期/.test(x)));
  assert.ok(ss.clusterPlanProblems(plan({ state: 'BLOCKED', blockers: ['MULTISITE_AUTHORITY_SETUP_REQUIRED'] })).length);
  assert.ok(ss.clusterPlanProblems(plan({ originalDatabasePreserved: undefined })).length);
  for (const over of [{ mode: 'CAMPUS' }, { allocationSlot: 0 }, { allocationSlot: undefined }, { siteId: undefined }, { clusterId: undefined }]) {
    assert.ok(ss.clusterPlanProblems(plan(over)).some((x) => /角色或分配/.test(x)), JSON.stringify(over));
  }
  assert.ok(ss.clusterPlanProblems(plan({ baseEpoch: undefined })).some((x) => /epoch/.test(x)));
});

test('阻擋與錯誤碼：簡短說明；單站權威不能說成一鍵部署第二中控', () => {
  for (const code of ['MULTISITE_AUTHORITY_SETUP_REQUIRED', 'ACTIVE_AUTHORITY_REQUIRED', 'MEMBER_CONFIGURATION_ROOT_REQUIRED', 'UNJOINED_EXTERNAL_INSTALLATION_REQUIRED',
    'MEMBER_CLUSTER_BINDING_MISMATCH', 'MEMBER_ALLOCATION_RESERVATION_REQUIRED', 'PRIVATE_MEMBER_CONFIGURATION_REQUIRED', 'MEMBER_ALREADY_RETAINED',
    'MEMBERSHIP_CHANGE_PENDING', 'MEMBER_CONFIGURATION_PREFLIGHT_FAILED', 'MEMBER_PLAN_BLOCKED', 'MEMBER_PLAN_CHANGED', 'MEMBER_PLAN_EXPIRED_OR_CHANGED',
    'MEMBER_RECEIPT_MISMATCH', 'MEMBER_CONFIGURATION_FAILED_PRESERVED', 'MEMBER_RECONCILIATION_REQUIRED', 'INSTALLED_MEMBER_IDENTITY_REQUIRED', 'STALE_MEMBERSHIP_EPOCH']) {
    assert.ok(ss.serverCodeText(code), code);
  }
  const multi = ss.serverCodeText('MULTISITE_AUTHORITY_SETUP_REQUIRED');
  assert.match(multi, /需先完成多站配置/);
  assert.match(multi, /不是一鍵部署第二中控/);
  assert.match(ss.serverCodeText('MEMBER_CONFIGURATION_FAILED_PRESERVED'), /不會自動重裝或重置資料庫/);
});

test('CONFIGURED／WAITING_FOR_JOIN 不是已加入；只有 APPLIED／ACTIVE 的成功結果才算', () => {
  assert.match(ss.clusterStageText('CONFIGURED'), /尚未加入/);
  assert.match(ss.clusterStageText('WAITING_FOR_JOIN'), /尚未加入/);
  assert.equal(ss.clusterStageText('MEMBER_CONFIGURATION'), '成員設定');
  const job = (o) => ss.normalizeClusterJob({ id: SID, kind: 'REMOTE_MEMBER', state: 'RUNNING', stage: 'MEMBER_CONFIGURATION', ...o });
  assert.equal(ss.clusterJobVerified(job({ state: 'RUNNING', stage: 'WAITING_FOR_JOIN' })), false);
  assert.equal(ss.clusterJobVerified(job({ state: 'SUCCEEDED', result: { clusterMembership: 'CONFIGURED' } })), false);
  assert.equal(ss.clusterJobVerified(job({ state: 'SUCCEEDED', stage: 'VERIFIED', result: { clusterMembership: 'ACTIVE', changeState: 'APPLIED', singleSiteOnly: false } })), true);
  assert.equal(ss.serverStatus(ext({ clusterMembership: 'CONFIGURED' })).joined, undefined);
  assert.equal(ss.CLUSTER_KIND_LABEL.REMOTE_MEMBER, '校外業務站接入');
});

test('靜態：接入面板依類型切換，執行前用共用條件重查，回執遺失只查詢不重送', () => {
  const ui = read('servers-ui.js');
  assert.match(ui, /clusterCtx = \{ serverId: server\.id, kind: ss\.enrollmentAvailability\(server, data\)\.kind \?\? null \};/);
  assert.match(ui, /const problems = ss\.clusterPlanProblems\(p\);/);
  assert.match(ui, /if \(clusterCtx\?\.kind && p\.kind !== clusterCtx\.kind\) problems\.push/);
  const exec = ui.slice(ui.indexOf('async function executeCluster'), ui.indexOf('function stopClusterPoll'));
  assert.ok(exec.indexOf('clusterPlanProblems(fresh)') > 0 && exec.indexOf('clusterPlanProblems(fresh)') < exec.indexOf('client.executeClusterPlan('));
  assert.ok(!exec.slice(exec.indexOf('} catch (err) {')).includes('executeClusterPlan('));
  assert.match(read('index.html'), /id="server-cluster-intro"/);
});
