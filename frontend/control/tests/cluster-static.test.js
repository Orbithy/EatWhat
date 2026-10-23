// 多站模式的靜態結構與安全檢查：只讀取本目錄。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { CONTROL_MODE, CONTROL_MODES, OBSERVATION_TIMEOUT_MS, CLUSTER_BLOCK_FRESHNESS_MS, CLUSTER_POLL_INTERVAL_MS } from '../constants.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const jsFiles = readdirSync(root).filter((f) => f.endsWith('.js'));
const html = read('index.html');
const clusterApp = read('cluster-app.js');
const clusterRender = read('cluster-render.js');

test('部署時固定模式：預設 cluster，只有 cluster／legacy 兩種', () => {
  assert.equal(CONTROL_MODE, 'cluster');
  assert.deepEqual([...CONTROL_MODES], ['cluster', 'legacy']);
  assert.equal(OBSERVATION_TIMEOUT_MS, 360000);
  assert.equal(CLUSTER_BLOCK_FRESHNESS_MS, 30000);
  assert.equal(CLUSTER_POLL_INTERVAL_MS, 5000);
});

test('HTML：多站區塊的穩定 id 各出現一次', () => {
  const ids = [
    'cluster-view', 'cl-banner', 'cl-overview', 'cl-members', 'cl-member-form', 'cl-member-site', 'cl-member-submit', 'cl-member-result',
    'cl-register-form', 'cl-profile-select', 'cl-register-submit', 'cl-profiles-reload', 'cl-changes-pending', 'cl-changes-recent',
    'cl-resume-form', 'cl-resume-change', 'cl-resume-submit', 'cl-fault-form', 'cl-fault-site', 'cl-fault-channel', 'cl-fault-seconds',
    'cl-fault-limit', 'cl-fault-submit', 'cl-fault-result', 'cl-gc-candidates', 'cl-gc-begin-form', 'cl-gc-candidate', 'cl-gc-begin-submit',
    'cl-gc-pending-only', 'cl-gc-list', 'cl-gc-resume-form', 'cl-gc-resume-select', 'cl-plan-form', 'cl-release-select', 'cl-plan-sites',
    'cl-plan-create', 'cl-plan-reload', 'cl-deploy-execute', 'cl-lock-reasons', 'cl-plan-view', 'cl-audit-limit', 'cl-audit', 'cl-file-audit',
    'cl-cap-summary', 'cl-capabilities',
    'cl-sec-runs', 'cl-suite-status', 'cl-suite-list', 'cl-active-run', 'cl-run-cancel', 'cl-run-result',
    'cl-run-lookup-form', 'cl-run-lookup-id', 'cl-run-lookup-submit', 'cl-run-history',
  ];
  for (const id of ids) {
    assert.equal((html.match(new RegExp(`id="${id}"`, 'g')) ?? []).length, 1, `id="${id}"`);
  }
  assert.match(html, /<div id="cluster-view"[^>]*\shidden>/, '多站區塊預設隱藏');
});

test('登入與頂列字樣依固定 CONTROL_MODE 顯示，HTML 靜態文字不寫死雙站或多站', async () => {
  const { modeBranding } = await import('../format.js');
  assert.equal(modeBranding('cluster').sub, '多站叢集');
  assert.ok(modeBranding('cluster').lead.includes('多站'));
  assert.ok(!modeBranding('cluster').lead.includes('雙站'));
  assert.equal(modeBranding('legacy').sub, '雙站驗收');
  assert.ok(modeBranding('legacy').lead.includes('雙站'));
  assert.equal(modeBranding('other').sub, '多站叢集', '未知值依預設 cluster');
  const brandSub = html.match(/<span id="brand-sub"[^>]*>([^<]*)<\/span>/);
  assert.ok(brandSub);
  assert.ok(!/雙站|多站/.test(brandSub[1]));
  assert.ok(!html.includes('id="login-lead"'));
  assert.ok(!html.includes('class="login-foot"'));
  const app = read('app.js');
  assert.match(app, /modeBranding\(CONTROL_MODE\)/);
  assert.match(app, /applyModeBranding\(\);\s*showLoginView\(\);/, '載入時即套用，不等登入');
});

test('HTML：多站部署執行按鈕停用且無事件處理', () => {
  const btn = html.match(/<button[^>]*id="cl-deploy-execute"[^>]*>/);
  assert.ok(btn);
  assert.match(btn[0], /\sdisabled[\s>]/);
  assert.match(btn[0], /aria-disabled="true"/);
  assert.ok(!/deployExecute\.addEventListener/.test(clusterApp));
  assert.ok(!/deployExecute\.disabled\s*=\s*false/.test(clusterApp));
});

test('中控不呼叫業務 consent API，也沒有部署執行、強制或豁免選項', () => {
  for (const f of jsFiles) {
    const text = read(f);
    assert.ok(!/\/auth\/consent/.test(text), `${f} 不可呼叫 consent API`);
    if (f !== 'api.js') assert.ok(!/\/execute/.test(text), `${f} 的 HTTP 執行路徑應集中在 api.js`);
  }
  for (const f of ['cluster-app.js', 'cluster-render.js', 'cluster-state.js']) {
    assert.ok(!/\b(force|waive|override|bypass|skipCheck)\w*/i.test(read(f)), `${f} 不可有強制或豁免選項`);
  }
  assert.ok(!/\bkey\s*:|bucket\s*:/.test(read('cluster-app.js').replace(/key:\s*(`|null|memberKey)/g, '')), 'GC 不可傳 key／bucket');
});

test('寫入 API 只在單一使用者事件處理器中呼叫', () => {
  const writes = ['joinSite', 'retireSite', 'cancelSite', 'registerSite', 'reloadProfiles', 'resumeChange', 'postFault', 'beginGc', 'resumeGc', 'createPreview'];
  for (const name of writes) {
    const count = (clusterApp.match(new RegExp(`client\\.${name}\\(`, 'g')) ?? []).length;
    assert.equal(count, 1, `${name} 應只呼叫一次`);
  }
  for (const name of ['startTestRun', 'cancelTestRun']) {
    const count = (clusterApp.match(new RegExp(`legacy\\.${name}\\(`, 'g')) ?? []).length;
    assert.equal(count, 1, `${name} 應只在單一使用者事件處理器中呼叫`);
  }
  const polling = clusterApp.slice(clusterApp.indexOf('async function refreshShort'), clusterApp.indexOf('// ---------- 渲染 ----------'));
  assert.ok(polling.length > 100);
  for (const name of [...writes, 'startTestRun', 'cancelTestRun']) assert.ok(!polling.includes(name), `輪詢不可呼叫 ${name}`);
  assert.ok(polling.includes('legacy.listTestRuns(') && polling.includes('legacy.getTestSuites('), '輪詢只讀取任務清單與套件');
  assert.ok(clusterApp.includes('confirmDialog({ title, lines: dialogLines, confirmLabel })'), '寫入前必須確認');
});

test('確認後、送出前重新檢查：在 confirmDialog 之後、取得 requestId 之前', () => {
  const body = clusterApp.slice(clusterApp.indexOf('async function runWrite'), clusterApp.indexOf('function onMemberSubmit'));
  const confirmAt = body.indexOf('await confirmDialog(');
  const recheckAt = body.indexOf('recheck({ replay })');
  const acquireAt = body.indexOf('intents.acquire(');
  const sendAt = body.indexOf('await send(');
  assert.ok(confirmAt > 0 && recheckAt > confirmAt && acquireAt > recheckAt && sendAt > acquireAt, '順序：確認 → 重新檢查 → 取得 requestId → 送出');
  assert.ok(/if \(stale\.length\)[\s\S]*refreshShort\(\);\s*return;/.test(body), '條件改變即不送出並重新讀取');
  const recheckCount = (clusterApp.match(/recheck: /g) ?? []).length;
  assert.equal(recheckCount, 10, '每個寫入（成員、登記、續行變更、故障、GC 發起、GC 續行、預覽、啟動任務、取消任務）都有重新檢查；inventory 重讀除外');
  assert.ok(clusterApp.includes('cm.recheckFault('), 'BLOCK 重新檢查觀測新鮮度');
});

test('多站畫面保留固定 allowlist 驗收任務，不提供命令或 URL 輸入', () => {
  // 改版後驗收任務位於「進階運維 → 驗收任務」分頁；取該 section 到下一個分頁為止。
  const runsAt = html.indexOf('id="cl-sec-runs"');
  const section = html.slice(runsAt, html.indexOf('data-page="audit"', runsAt));
  assert.match(html.slice(html.lastIndexOf('data-page=', runsAt), runsAt), /data-page="advanced\/tasks"/, '驗收任務在進階運維分頁');
  assert.ok(section.length > 100, '多站畫面需有驗收任務區塊');
  const inputs = section.match(/<input\b[^>]*>/g) ?? [];
  assert.equal(inputs.length, 1, '只有任務 ID 查詢欄位');
  assert.match(inputs[0], /id="cl-run-lookup-id"/);
  assert.ok(!/<textarea|<select/.test(section), '沒有命令、參數或 URL 選擇／輸入');
  // 啟動與取消都重用 state.js 的條件，並在確認後重新檢查。
  assert.ok((clusterApp.match(/model\.startBlockers\(/g) ?? []).length >= 2, '同步與確認後都檢查 startBlockers');
  assert.match(clusterApp, /view\.syncSuites\(els\.suiteList,[^\n]*onStartSuite, 'cl-suite'\)/);
  assert.match(clusterApp, /els\.runCancel\.addEventListener\('click', onCancelRun\)/);
  // 啟動只由套件按鈕觸發：onStartSuite 只出現在定義與 syncSuites 的點擊回呼。
  assert.equal((clusterApp.match(/onStartSuite\b/g) ?? []).length, 2);
  // 依 ID 查詢的執行中任務每輪重讀，避免舊 RUNNING 快照。
  assert.match(clusterApp, /refreshActiveLookups\(gen, opts\)/);
});

test('觀測：單一請求、完成後才排下一輪、登出中止', () => {
  assert.ok(clusterApp.includes('if (store.observations.inFlightSince !== null) return;'), '不重疊');
  const obsBody = clusterApp.slice(clusterApp.indexOf('async function refreshObservations'), clusterApp.indexOf('// ---------- 渲染 ----------'));
  assert.ok(/finally[\s\S]*setTimeout\(\(\) => refreshObservations\(\), CLUSTER_POLL_INTERVAL_MS\)/.test(obsBody), '完成後隔 5 秒');
  assert.ok(!/setInterval\([^)]*refreshObservations/.test(clusterApp), '不用固定間隔重疊輪詢');
  assert.ok(/function stop\(\)[\s\S]*controller\.abort\(\)/.test(clusterApp), 'stop 中止請求');
  assert.ok(/if \(cluster\) cluster\.stop\(\);/.test(read('app.js')), '登出呼叫 cluster.stop');
});

test('Session 保留：只有 401／403 交給登出，503／逾時不登出', () => {
  assert.match(clusterApp, /function isAuthFailure\(err\)[\s\S]*err\.status === 401 \|\| err\.status === 403/);
  assert.ok(!/status === 503[^\n]*onAuthFailure/.test(clusterApp));
});

test('cluster 入口不自動降級：cluster 分支在舊 capabilities 之前結束', () => {
  const app = read('app.js');
  const login = app.slice(app.indexOf('async function onLoginSubmit'), app.indexOf('// ---------- 讀取與輪詢 ----------'));
  const branch = login.indexOf('if (cluster) {');
  const verify = login.indexOf('cluster.verifyEntry()');
  const legacyCaps = login.indexOf('client.getCapabilities()');
  assert.ok(branch > 0 && verify > branch && legacyCaps > verify, 'cluster 分支先於舊 capabilities');
  const between = login.slice(verify, legacyCaps);
  assert.match(between, /cluster\.start\(capsRes\);(?:\s*(?:router\.apply|account\.load)\(\);)*\s*return;/, 'cluster 分支結束後不進入舊流程');
  assert.ok(app.includes('cluster 模式不會自動降級為雙站畫面'));
  assert.ok(clusterApp.includes('cm.isEntryRejection(err)'), 'verifyEntry 使用已測試的入口判定');
});

test('cluster-render.js 中每個表格都包在 .table-wrap 內', () => {
  const tables = (clusterRender.match(/\{ class: 'table' \}/g) ?? []).length;
  const wraps = (clusterRender.match(/class: 'table-wrap'/g) ?? []).length;
  assert.ok(tables > 0);
  assert.equal(wraps, tables);
});

test('多站樣式：成員格與進度條不撐寬頁面', () => {
  const css = read('styles.css');
  assert.match(css, /\.member-grid\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/);
  assert.match(css, /\.member-grid > \*/);
  assert.match(css, /\.stepper\s*\{[^}]*flex-wrap:\s*wrap/);
});
