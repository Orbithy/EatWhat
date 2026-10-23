// 成員設定失敗後恢復原設定（REMOTE_MEMBER_CONFIGURATION_RECOVERY）：來源任務條件、計畫守衛、成功只代表 NOT_JOINED、API 與頁面約束。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as ss from '../servers-state.js';
import { createControlClient } from '../api.js';
import { fakeFetch, fakeSession, jsonResponse } from './helpers.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const SRV = '0f3b7c2a-9d41-4e6b-8a52-1c7e9f3d2b10';
const FAILED = '11111111-1111-4111-8111-111111111111';
const PLAN = '22222222-2222-4222-8222-222222222222';
const R = ss.MEMBER_RECOVERY_KIND;

const failedJob = (over = {}) => ss.normalizeClusterJob({ id: FAILED, kind: 'REMOTE_MEMBER', serverId: SRV, state: 'FAILED', stage: 'MEMBER_CONFIGURATION', code: 'MEMBER_CONFIGURATION_FAILED_PRESERVED', ...over });
const plan = (over = {}) => ss.normalizeClusterPlan({
  id: PLAN, kind: R, failedId: FAILED, serverId: SRV, serverRevision: 3, siteId: 'edge_4', instanceId: '33333333-3333-4333-8333-333333333333', clusterId: '44444444-4444-4444-8444-444444444444',
  allocationSlot: 4, artifactSha256: 'b'.repeat(64), state: 'READY', blockers: [], expiresAt: new Date(Date.now() + 600000).toISOString(),
  originalDatabasePreserved: true, controllerRestartExpected: false, newEnrollmentPlanRequired: true, singleSiteOnly: false, _payload: 'secret', ...over,
});

test('來源任務：只有 REMOTE_MEMBER＋FAILED＋MEMBER_CONFIGURATION_FAILED_PRESERVED 可建立恢復', () => {
  assert.deepEqual(ss.memberRecoverySourceProblems(failedJob()), []);
  const cases = [
    [{ state: 'OUTCOME_UNKNOWN', code: 'MEMBER_RECONCILIATION_REQUIRED' }, /結果未知/],
    [{ state: 'RUNNING', code: undefined }, /仍在執行/],
    [{ state: 'QUEUED', code: undefined }, /仍在執行/],
    [{ state: 'SUCCEEDED', code: undefined, result: { clusterMembership: 'ACTIVE' } }, /不是失敗/],
    [{ state: 'RUNNING', stage: 'MEMBERSHIP_JOIN', result: { remoteState: 'CONFIGURED' } }, /仍在執行/],
    [{ code: 'MEMBER_PLAN_CHANGED' }, /MEMBER_CONFIGURATION_FAILED_PRESERVED/],
    [{ code: undefined }, /MEMBER_CONFIGURATION_FAILED_PRESERVED/],
    [{ kind: 'INITIAL_AUTHORITY' }, /校外業務站/],
    [{ kind: R, code: 'MEMBER_RECOVERY_FAILED_PRESERVED' }, /校外業務站/],
  ];
  for (const [over, re] of cases) {
    const p = ss.memberRecoverySourceProblems(failedJob(over));
    assert.equal(p.length, 1, JSON.stringify(over));
    assert.match(p[0], re, JSON.stringify(over));
  }
  assert.deepEqual(ss.memberRecoverySourceProblems(null), ['沒有來源任務。']);
});

test('計畫正規化：保留公開欄位與 failedId；不帶私有欄位；未知 kind 不接受', () => {
  const p = plan();
  assert.deepEqual([p.kind, p.failedId, p.serverRevision, p.allocationSlot, p.controllerRestartDeclaredFalse, p.newEnrollmentPlanRequired, p.originalDatabasePreserved], [R, FAILED, 3, 4, true, true, true]);
  assert.ok(!('_payload' in p));
  assert.deepEqual(ss.clusterPlanProblems(p), []);
  assert.equal(ss.normalizeClusterPlan({ id: PLAN, kind: 'REMOTE_MEMBER_RESET', state: 'READY' }), null);
  assert.equal(ss.CLUSTER_KIND_LABEL[R], '恢復成員原設定');
});

test('計畫守衛：期限缺失／非法／過期、阻擋、保留資料、不重啟、仍需新接入計畫、身份與 SHA 都必須明確', () => {
  for (const expiresAt of [undefined, '', 'not-a-date']) assert.ok(ss.clusterPlanProblems(plan({ expiresAt })).some((x) => /有效期未知/.test(x)), String(expiresAt));
  assert.ok(ss.clusterPlanProblems(plan({ expiresAt: new Date(Date.now() - 1000).toISOString() })).some((x) => /過期/.test(x)));
  assert.ok(ss.clusterPlanProblems(plan({ state: 'BLOCKED', blockers: ['MEMBER_RECOVERY_PREFLIGHT_FAILED'] })).some((x) => /阻擋/.test(x)));
  assert.ok(ss.clusterPlanProblems(plan({ originalDatabasePreserved: undefined })).some((x) => /保留原資料庫/.test(x)));
  for (const v of [undefined, true, 'false']) assert.ok(ss.clusterPlanProblems(plan({ controllerRestartExpected: v })).some((x) => /中控不重啟/.test(x)), String(v));
  for (const v of [undefined, false, 'true']) assert.ok(ss.clusterPlanProblems(plan({ newEnrollmentPlanRequired: v })).some((x) => /新的接入計畫/.test(x)), String(v));
  assert.ok(ss.clusterPlanProblems(plan({ failedId: 'nope' })).some((x) => /來源失敗任務/.test(x)));
  for (const over of [{ allocationSlot: 0 }, { allocationSlot: 1024 }, { siteId: undefined }, { instanceId: undefined }, { clusterId: undefined }, { serverId: undefined }]) {
    assert.ok(ss.clusterPlanProblems(plan(over)).some((x) => /原身份/.test(x)), JSON.stringify(over));
  }
  assert.ok(ss.clusterPlanProblems(plan({ artifactSha256: 'abc' })).some((x) => /SHA-256/.test(x)));
});

test('恢復任務：成功只認 NOT_JOINED＋newEnrollmentPlanRequired；絕不算已加入', () => {
  const job = (o) => ss.normalizeClusterJob({ id: PLAN, kind: R, serverId: SRV, failedId: FAILED, state: 'SUCCEEDED', stage: 'VERIFIED', controllerRestartExpected: false, ...o });
  const ok = job({ result: { clusterMembership: 'NOT_JOINED', originalDatabasePreserved: true, newEnrollmentPlanRequired: true, failedId: FAILED } });
  assert.equal(ok.failedId, FAILED);
  assert.equal(ss.memberRecoveryVerified(ok), true);
  assert.equal(ss.clusterJobVerified(ok), false);
  // 即使回報 ACTIVE／APPLIED 也不顯示為已加入，也不是有效的恢復結果
  const weird = job({ result: { clusterMembership: 'ACTIVE', changeState: 'APPLIED', newEnrollmentPlanRequired: true } });
  assert.equal(ss.clusterJobVerified(weird), false);
  assert.equal(ss.memberRecoveryVerified(weird), false);
  assert.equal(ss.memberRecoveryVerified(job({ result: { clusterMembership: 'NOT_JOINED' } })), false);
  assert.equal(ss.memberRecoveryVerified(job({ state: 'RUNNING', result: null })), false);
  assert.equal(ss.clusterStageText('MEMBER_CONFIGURATION_RECOVERY'), '恢復原設定（仍未加入）');
  const accepted = ss.clusterAcceptedText(job({ state: 'QUEUED' }));
  assert.match(accepted, /已送出恢復原設定任務[\s\S]*中控不重啟[\s\S]*原任務 UUID[\s\S]*仍未加入集群/);
  assert.ok(!/已加入。|ACTIVE/.test(accepted));
  assert.equal(ss.clusterUnknownSubmitText(plan()), '送出結果未確認。不會重新送出；正以原計畫 UUID 唯讀讀取任務。');
});

test('錯誤碼：四個新碼有說明；未知碼仍為 null（畫面保留原碼）', () => {
  for (const code of ['FAILED_MEMBER_CONFIGURATION_REQUIRED', 'MEMBER_RECOVERY_PREFLIGHT_FAILED', 'MEMBER_RECOVERY_FAILED_PRESERVED', 'MEMBER_RECOVERY_RECONCILIATION_REQUIRED']) assert.ok(ss.serverCodeText(code), code);
  assert.match(ss.serverCodeText('MEMBER_RECOVERY_FAILED_PRESERVED'), /不會自動重試或重置資料庫/);
  assert.match(ss.serverCodeText('MEMBER_RECOVERY_RECONCILIATION_REQUIRED'), /原恢復任務 UUID[\s\S]*不會重送/);
  assert.equal(ss.serverCodeText('MEMBER_RECOVERY_FUTURE_CODE'), null);
});

test('API：恢復計畫只以失敗任務 UUID 建立、無本文；非 UUID 不送出', async () => {
  const fetchImpl = fakeFetch(() => jsonResponse(201, {}));
  const client = createControlClient({ session: fakeSession(), fetchImpl });
  await client.createMemberRecoveryPlan(FAILED);
  assert.ok(fetchImpl.calls[0].url.endsWith(`/server-cluster-jobs/${FAILED}/recovery-plans`));
  assert.equal(fetchImpl.calls[0].init.method, 'POST');
  assert.equal(fetchImpl.calls[0].body, undefined);
  for (const bad of ['../x', '', null, 'not-a-uuid']) await assert.rejects(client.createMemberRecoveryPlan(bad), (e) => e.kind === 'validation', String(bad));
  assert.equal(fetchImpl.calls.length, 1);
});

test('頁面靜態：入口只在任務面板；建立前重讀來源任務；確認後重讀計畫並重查租約；未知只讀原 UUID 不重送', () => {
  const ui = read('servers-ui.js');
  assert.match(read('index.html'), /id="server-cluster-job-recover" class="btn btn-warn" hidden disabled>預覽恢復原設定…</);
  assert.equal((ui.match(/client\.createMemberRecoveryPlan\(/g) ?? []).length, 1);
  const create = ui.slice(ui.indexOf('async function createClusterPlan'), ui.indexOf('function clusterPlanProblems'));
  assert.ok(create.indexOf('readClusterJob(failedId)') > 0 && create.indexOf('readClusterJob(failedId)') < create.indexOf('client.createMemberRecoveryPlan('), '建立前重讀來源任務');
  assert.match(create, /ss\.memberRecoverySourceProblems\(source\)/);
  const exec = ui.slice(ui.indexOf('async function executeCluster'), ui.indexOf('function stopClusterPoll'));
  const order = ['confirmDialog(', 'client.getClusterPlan(captured.id)', 'await load()', 'clusterReasons(current)', 'clusterPlanProblems(fresh)', 'client.executeClusterPlan('].map((k) => exec.indexOf(k));
  assert.ok(order.every((i, n) => i > 0 && (n === 0 || i > order[n - 1])), `確認後重查順序 ${order}`);
  assert.ok(!exec.slice(exec.indexOf('} catch (err) {')).includes('executeClusterPlan('));
  assert.match(ui, /function recoveryReasons\(server\) \{\n\s*const reasons = gateReasons\(\);/);
  assert.match(ui, /if \(isRecovery\(\) && \(p\.failedId !== clusterCtx\.failedId \|\| p\.serverId !== clusterCtx\.serverId\)\)/);
});

test('頁面靜態：遲到的輪詢回應（成功或失敗）在改看其他任務後丟棄；自動查回不受影響', () => {
  const ui = read('servers-ui.js');
  const fetch = ui.slice(ui.indexOf('async function fetchClusterJob'), ui.indexOf('function setClusterJob'));
  assert.match(fetch, /const watched = poll && clusterWatchId\(\) === id;/);
  assert.match(fetch, /const superseded = \(\) => watched && clusterWatchId\(\) !== id;/);
  assert.ok(fetch.indexOf('if (superseded()) return;') < fetch.indexOf('setClusterJob(j'), '成功回應先檢查');
  assert.match(fetch, /if \(g !== gen \|\| superseded\(\)\) return;/);
});

test('頁面靜態：恢復計畫送出後 404 不建議重送、不重新開放送出；未知結果用「核對恢復記錄」', () => {
  const ui = read('servers-ui.js');
  const fetch = ui.slice(ui.indexOf('async function fetchClusterJob'), ui.indexOf('function setClusterJob'));
  const nf = fetch.slice(fetch.indexOf("kind === 'NOT_FOUND'"));
  const rec = nf.slice(nf.indexOf('if (sentRecoveryPlans.has(id))'), nf.indexOf('return;', nf.indexOf('if (sentRecoveryPlans.has(id))')));
  assert.match(rec, /不會重送；請稍後在下方以這個原 UUID 再查詢核對/);
  assert.ok(!/確認接入|重送只會|再按/.test(rec), '恢復 404 不建議重送');
  assert.ok(nf.indexOf('if (sentRecoveryPlans.has(id))') < nf.indexOf('再按「確認接入集群…」'), '恢復分支先於一般接入提示');
  assert.match(ui, /if \(recovery\) sentRecoveryPlans\.add\(captured\.id\);/);
  assert.match(ui, /const used = Boolean\(clusterPlan && \(clusterJob\?\.id === clusterPlan\.id \|\| clusterPending === clusterPlan\.id \|\| sentRecoveryPlans\.has\(clusterPlan\.id\)\)\);/);
  assert.match(ui, /reset\(\) \{[\s\S]*sentRecoveryPlans\.clear\(\);/);
  assert.match(ui, /clusterJob\?\.kind === ss\.MEMBER_RECOVERY_KIND \? '核對恢復記錄…' : '核對實際成員狀態…'/);
  assert.match(ui, /title: '核對恢復記錄', lines: \['只讀取遠端恢復回執與目前狀態，不重新恢復、不加入集群。'/);
  assert.match(ui, /恢復結果未知。不會重送；請按「核對恢復記錄…」。/);
});
