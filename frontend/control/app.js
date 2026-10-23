import { mountDeployment } from './deployment-ui.js';
import { mountLocale } from './locale.js';
// 中控台主程式：登入、輪詢、使用者手動觸發的操作。
// 不會自動啟動測試、阻斷通道或部署；所有寫入都需要使用者點擊並在對話框確認。

import {
  AUDIT_LIMIT_DEFAULT,
  CONTROL_MODE,
  FAULT_SECONDS_MAX,
  POLL_INTERVAL_MS,
  RESTORE_SECONDS,
  SITE_IDS,
  SUITE_SCOPES,
} from './constants.js';
import { createClusterClient, createControlClient, login } from './api.js';
import { createSession } from './session.js';
import { describeError, formatTime, modeBranding, shortId, siteLabel } from './format.js';
import { isUuid, isValidAuditLimit, validateFaultRequest, validatePlanRequest } from './validate.js';
import * as model from './state.js';
import * as view from './render.js';
import { replace } from './dom.js';
import { createClusterController } from './cluster-app.js';
import { mountRouter } from './router.js';
import { mountAccount } from './account-ui.js';
import { mountServers } from './servers-ui.js';
import { mountTasks } from './tasks-ui.js';
import { mountBootstrap } from './bootstrap-ui.js';

const CLUSTER_MODE = CONTROL_MODE === 'cluster';
mountLocale(document);

const $ = (id) => {
  const el = document.getElementById(id);
  if (!el) throw new Error(`缺少元素 #${id}`);
  return el;
};

const els = {
  connection: $('connection'),
  refresh: $('refresh-button'),
  logout: $('logout-button'),
  locale: document.getElementById('locale-control'),
  loginView: $('login-view'),
  loginForm: $('login-form'),
  loginUsername: $('login-username'),
  loginPassword: $('login-password'),
  loginSubmit: $('login-submit'),
  loginError: $('login-error'),
  consoleView: $('console-view'),
  staleBanner: $('stale-banner'),
  notice: $('notice'),
  capabilities: $('capabilities'),
  capSummary: $('cap-summary'),
  envChip: $('env-chip'),
  overview: $('overview'),
  pairBar: $('pair-bar'),
  siteGrid: $('site-grid'),
  gc: $('gc'),
  faultForm: $('fault-form'),
  faultSite: $('fault-site'),
  faultChannel: $('fault-channel'),
  faultSeconds: $('fault-seconds'),
  faultLimit: $('fault-limit'),
  faultImpact: $('fault-impact'),
  faultBlocking: $('fault-blocking'),
  faultWarnings: $('fault-warnings'),
  faultSubmit: $('fault-submit'),
  faultResult: $('fault-result'),
  suiteList: $('suite-list'),
  suiteStatus: $('suite-status'),
  activeRun: $('active-run'),
  runCancel: $('run-cancel'),
  runLookupForm: $('run-lookup-form'),
  runLookupId: $('run-lookup-id'),
  runLookupSubmit: $('run-lookup-submit'),
  runHistory: $('run-history'),
  planForm: $('plan-form'),
  releaseSelect: $('release-select'),
  releaseDetails: $('release-details'),
  planCreate: $('plan-create'),
  planReload: $('plan-reload'),
  deployExecute: $('deploy-execute'),
  lockReasons: $('lock-reasons'),
  planView: $('plan-view'),
  auditLimit: $('audit-limit'),
  audit: $('audit'),
  dialog: $('confirm-dialog'),
  dialogTitle: $('dialog-title'),
  dialogLines: $('dialog-lines'),
  dialogCancel: $('dialog-cancel'),
  dialogConfirm: $('dialog-confirm'),
  shell: $('shell'),
  sidebar: $('sidebar'),
  appbar: $('appbar'),
  navToggle: $('nav-toggle'),
  navClose: $('nav-close'),
  navScrim: $('nav-scrim'),
  navIssues: $('nav-issues'),
  accountView: $('account-view'),
  serversView: $('servers-view'),
  tasksView: $('tasks-view'),
  deployGuide: $('cl-deploy-guide'),
  meName: $('me-name'),
  main: $('main'),
};

const session = createSession();
const client = createControlClient({ session });
// 多站模式控制器：只在 CONTROL_MODE = 'cluster' 時啟用；與既有雙站畫面互不共用輪詢。
const cluster = CLUSTER_MODE
  ? createClusterController({ session, confirmDialog, onAuthFailure: (err) => handleAuthFailure(err), onSummary: updateNavSummary })
  : null;

// ---------- 外框：分頁路由、側邊導覽、帳號頁 ----------

const router = mountRouter(document, { onChange: onRouteChange });
const account = mountAccount({
  client,
  session,
  confirmDialog,
  onAuthFailure: (err) => handleAuthFailure(err),
  // 修改成功、401／403 或結果未知：清除權杖與全部密碼並回到登入頁；成功時可預填新的使用者名稱。
  onReauthenticate: ({ message, username }) => logout(message, { username }),
});
// 伺服器頁：兩種模式共用；多站模式另受主中控租約限制。
const servers = mountServers({
  client,
  session,
  confirmDialog,
  onAuthFailure: (err) => handleAuthFailure(err),
  getLeadership: () => (cluster ? cluster.leadership() : null),
  onLeaseLost: () => cluster?.refreshNow(),
  // 接入集群完成或結束後，多站頁面需要重新讀取成員與 epoch。
  onClusterChanged: () => cluster?.refreshNow(),
});
// 任務歷史：兩種模式共用；權威發布約束只在多站模式讀取。
const tasks = mountTasks({
  client,
  clusterClient: CLUSTER_MODE ? createClusterClient({ session }) : null,
  session,
  confirmDialog,
  onAuthFailure: (err) => handleAuthFailure(err),
  // 詳情目標已由「類型＋UUID」白名單決定；交給既有面板讀取，核對在原面板確認後進行。
  openDetail: (target) => {
    if (target.target === 'server-job') servers.openJob(target.id);
    else if (target.target === 'cluster-job') servers.openClusterJob(target.id);
    else if (target.target === 'deployment') (cluster ? cluster.openDeployment(target.id) : deploymentUi.lookup(target.id));
  },
});
// 在新伺服器安裝中控與業務：伺服器頁內的獨立面板；排隊後轉到任務歷史的只讀詳情。
const bootstrap = mountBootstrap({
  client,
  session,
  confirmDialog,
  onAuthFailure: (err) => handleAuthFailure(err),
  getLeadership: () => (cluster ? cluster.leadership() : null),
  openControllerJob: (id) => tasks.openControllerJob(id),
});

function setNavOpen(open) {
  els.shell.classList.toggle('nav-open', open);
  els.navScrim.hidden = !open;
  els.navToggle.setAttribute('aria-expanded', String(open));
  if (open) els.navClose.focus();
}

function setSignedIn(on) {
  els.shell.dataset.auth = on ? 'in' : 'out';
  els.sidebar.hidden = !on;
  els.appbar.hidden = !on;
  els.accountView.hidden = !on;
  els.serversView.hidden = !on;
  els.tasksView.hidden = !on;
  setNavOpen(false);
  if (!on) updateNavSummary(null);
}

function onRouteChange(route) {
  setNavOpen(false);
  if (route === 'account' && session.isActive()) account.load();
  if (route === 'servers') servers.enter();
  else servers.leave();
  if (route === 'servers') bootstrap.enter();
  else bootstrap.leave();
  if (route === 'tasks') tasks.enter();
  else tasks.leave();
  cluster?.setSitesVisible(route === 'sites');
  window.scrollTo(0, 0);
}

// 總覽待處理問題數：只有多站模式提供；沒有資料時不顯示徽章。
function updateNavSummary(model) {
  const count = model ? model.issues.length : 0;
  els.navIssues.hidden = count === 0;
  els.navIssues.textContent = count ? String(count) : '';
  els.navIssues.className = `nav-badge${model ? ` nav-badge-${model.worstTone}` : ''}`;
  // 部署頁：已知沒有任何集群成員時，導引到伺服器頁添加 SSH 伺服器。
  els.deployGuide.hidden = !(model && model.sites.known && model.sites.rows.length === 0);
  // 主中控狀態可能改變伺服器頁的寫入閘門。
  if (router.current() === 'servers') servers.render();
}

function initialStore() {
  return {
    capabilities: model.emptyResource(),
    sites: model.emptyResource(),
    runs: model.emptyResource(),
    suites: model.emptyResource(),
    releases: model.emptyResource(),
    audit: model.emptyResource(),
    lookups: new Map(),
    knownChannels: Object.fromEntries(SITE_IDS.map((id) => [id, []])),
    plan: null,
    planReceivedAt: null,
    planError: null,
    auditLimit: AUDIT_LIMIT_DEFAULT,
  };
}

let store = initialStore();
let generation = 0;
let pollTimer = null;
let tickTimer = null;
let inflight = null;
let lastFreshness = '';
const busy = new Set();

// ---------- 小工具 ----------

function selectedAction() {
  const checked = els.faultForm.querySelector('input[name="fault-action"]:checked');
  return checked ? checked.value : 'BLOCK';
}

function notify(tone, title, lines = []) {
  view.renderMessage(els.notice, { tone, title, lines });
}

function isAuthFailure(err) {
  return err && (err.status === 401 || err.status === 403) && err.kind !== 'validation';
}

function handleAuthFailure(err) {
  if (!isAuthFailure(err)) return false;
  logout(err.status === 403 ? '此帳號不具 admin 角色或權限已變更，已登出。' : '登入已失效，請重新登入。');
  return true;
}

function confirmDialog({ title, lines, confirmLabel }) {
  if (els.dialog.open) return Promise.resolve(false);
  return new Promise((resolve) => {
    els.dialogTitle.textContent = title;
    replace(els.dialogLines, lines.map((line) => {
      const li = document.createElement('li');
      li.textContent = line;
      return li;
    }));
    els.dialogConfirm.textContent = confirmLabel;
    els.dialog.returnValue = '';
    const onClose = () => {
      els.dialog.removeEventListener('close', onClose);
      resolve(els.dialog.returnValue === 'confirm');
    };
    els.dialog.addEventListener('close', onClose);
    els.dialog.showModal();
    els.dialogCancel.focus();
  });
}

function clearPasswords() {
  deploymentUi.reset();
  for (const input of document.querySelectorAll('input[type="password"]')) input.value = '';
}

// ---------- 登入 / 登出 ----------

function showLoginView(message, { username } = {}) {
  setSignedIn(false);
  if (typeof username === 'string' && username) els.loginUsername.value = username;
  els.consoleView.hidden = true;
  els.loginView.hidden = false;
  els.refresh.hidden = true;
  els.logout.hidden = true;
  if (els.locale) els.locale.hidden = true;
  $('brand-sub').hidden = true;
  if (message) {
    els.loginError.textContent = message;
    els.loginError.hidden = false;
  } else {
    els.loginError.textContent = '';
    els.loginError.hidden = true;
  }
  renderStatus(Date.now());
  els.loginUsername.focus();
}

function showConsoleView() {
  setSignedIn(true);
  router.apply();
  els.loginView.hidden = true;
  els.consoleView.hidden = false;
  els.refresh.hidden = false;
  els.logout.hidden = false;
  if (els.locale) els.locale.hidden = false;
  $('brand-sub').hidden = false;
  els.loginError.hidden = true;
}

function clearRenderedData() {
  for (const el of [
    els.capabilities, els.capSummary, els.overview,
    els.pairBar, els.siteGrid, els.gc, els.activeRun, els.runHistory, els.suiteStatus,
    els.releaseDetails, els.planView, els.audit, els.lockReasons, els.dialogLines,
  ]) el.replaceChildren();
  view.resetSuites(els.suiteList);
  for (const select of [els.faultChannel, els.releaseSelect]) {
    select.replaceChildren();
    delete select.dataset.signature;
  }
  view.renderMessage(els.notice, null);
  view.renderMessage(els.faultResult, null);
  view.renderList(els.faultBlocking, []);
  view.renderList(els.faultWarnings, []);
  els.faultImpact.textContent = '';
  els.faultLimit.textContent = '';
  els.staleBanner.hidden = true;
  view.renderEnvChip(els.envChip, null);
  els.runLookupId.value = '';
  els.auditLimit.value = String(AUDIT_LIMIT_DEFAULT);
}

function logout(message, { username } = {}) {
  generation += 1;
  account.reset();
  servers.reset();
  tasks.reset();
  bootstrap.reset();
  clearTimeout(pollTimer);
  pollTimer = null;
  clearInterval(tickTimer);
  tickTimer = null;
  inflight = null;
  session.clear();
  store = initialStore();
  busy.clear();
  lastFreshness = '';
  clearPasswords();
  if (els.dialog.open) els.dialog.close('cancel');
  // 停止多站輪詢並中止進行中的請求（含長時間觀測）。
  if (cluster) cluster.stop();
  clearRenderedData();
  showLoginView(message, { username });
}

async function onLoginSubmit(event) {
  event.preventDefault();
  if (busy.has('login')) return;
  const username = els.loginUsername.value.trim();
  const password = els.loginPassword.value;
  clearPasswords();
  if (!username || !password) {
    showLoginView('請輸入帳號與密碼。');
    return;
  }
  busy.add('login');
  els.loginSubmit.disabled = true;
  els.loginSubmit.textContent = '登入中…';
  els.loginError.hidden = true;
  const gen = ++generation;
  try {
    const { tokenName, tokenValue } = await login({ username, password });
    if (gen !== generation) return;
    session.set(tokenName, tokenValue);
    els.meName.textContent = username;
    if (cluster) {
      // 多站模式：只驗證多站中控入口；404 不自動降級為雙站畫面。
      const capsRes = await cluster.verifyEntry();
      if (gen !== generation) return;
      els.loginView.hidden = true;
      els.refresh.hidden = false;
      els.logout.hidden = false;
      if (els.locale) els.locale.hidden = false;
      $('brand-sub').hidden = false;
      els.loginError.hidden = true;
      setSignedIn(true);
      cluster.start(capsRes);
      router.apply();
      account.load();
      return;
    }
    // 以 /capabilities 驗證 admin 角色與中控模組是否啟用。
    const caps = await client.getCapabilities();
    if (gen !== generation) return;
    store.capabilities = model.succeed(store.capabilities, caps, Date.now());
    showConsoleView();
    account.load();
    renderAll();
    startTick();
    await refreshNow({ includeStatic: true });
  } catch (err) {
    if (gen !== generation) return;
    session.clear();
    let text = describeError(err);
    if (err?.status === 403) text = '此帳號不具 admin 角色，無法使用中控台。';
    else if (err?.status === 404 && cluster) {
      text = '此入口不是多站中控入口，或多站功能未開啟（cluster 模式不會自動降級為雙站畫面）。';
    } else if (err?.status === 404) text = '中控 API 不存在或未啟用（僅限 isolated-acceptance 環境）。';
    showLoginView(text);
  } finally {
    busy.delete('login');
    els.loginSubmit.disabled = false;
    els.loginSubmit.textContent = '登入';
  }
}

// ---------- 讀取與輪詢 ----------

function applyResult(key, result, now) {
  store[key] = result.status === 'fulfilled'
    ? model.succeed(store[key], result.value, now)
    : model.fail(store[key], result.reason, now);
}

async function refreshStatic(gen) {
  const [caps, suites, releases] = await Promise.allSettled([
    client.getCapabilities(),
    client.getTestSuites(),
    client.listReleases(),
  ]);
  if (gen !== generation) return false;
  const now = Date.now();
  applyResult('capabilities', caps, now);
  applyResult('suites', suites, now);
  applyResult('releases', releases, now);
  return ![caps, suites, releases].some((r) => r.status === 'rejected' && handleAuthFailure(r.reason));
}

async function refreshActiveLookups(gen, listed) {
  const listedIds = new Set((Array.isArray(listed) ? listed : []).map((r) => r?.id));
  const targets = [...store.lookups.entries()].filter(([id, e]) => !listedIds.has(id) && model.isRunActive(e.run));
  for (const [id] of targets) {
    try {
      const run = await client.getTestRun(id);
      if (gen !== generation) return;
      store.lookups.set(id, { run, fetchedAt: Date.now(), error: null });
    } catch (err) {
      if (gen !== generation) return;
      if (handleAuthFailure(err)) return;
      const prev = store.lookups.get(id);
      store.lookups.set(id, { ...prev, error: err });
    }
  }
}

async function refreshDynamic(gen) {
  const [sites, runs, audit] = await Promise.allSettled([
    client.getSites(),
    client.listTestRuns(),
    client.getAudit(store.auditLimit),
  ]);
  if (gen !== generation) return false;
  const now = Date.now();
  applyResult('sites', sites, now);
  applyResult('runs', runs, now);
  applyResult('audit', audit, now);
  if (sites.status === 'fulfilled') store.knownChannels = model.mergeKnownChannels(store.knownChannels, sites.value);
  if ([sites, runs, audit].some((r) => r.status === 'rejected' && handleAuthFailure(r.reason))) return false;
  await refreshActiveLookups(gen, runs.status === 'fulfilled' ? runs.value : null);
  return gen === generation;
}

async function refreshNow({ includeStatic = false } = {}) {
  const gen = generation;
  if (!session.isActive()) return;
  clearTimeout(pollTimer);
  pollTimer = null;
  // 同一時間只跑一輪讀取；排隊者依序等待。
  while (inflight) {
    try { await inflight; } catch { /* 錯誤已記錄在各資源 */ }
  }
  if (gen !== generation || !session.isActive()) return;
  const job = (async () => {
    if (includeStatic && !(await refreshStatic(gen))) return;
    if (gen !== generation) return;
    await refreshDynamic(gen);
  })();
  inflight = job;
  try {
    await job;
  } finally {
    if (inflight === job) inflight = null;
    if (gen === generation && session.isActive()) {
      renderAll();
      clearTimeout(pollTimer);
      pollTimer = setTimeout(() => refreshNow(), POLL_INTERVAL_MS);
    }
  }
}

function startTick() {
  clearInterval(tickTimer);
  tickTimer = setInterval(() => {
    if (!session.isActive()) return;
    const now = Date.now();
    const signature = [store.sites, store.runs, store.audit].map((r) => model.freshnessOf(r, now)).join('|');
    if (signature !== lastFreshness) renderAll();
    else renderStatus(now);
  }, 1000);
}

// ---------- 渲染 ----------

function renderStatus(now) {
  const loggedIn = session.isActive();
  const summary = model.connectionSummary([store.sites, store.runs, store.audit], now);
  view.renderConnection(els.connection, summary, now, loggedIn);
  if (!loggedIn || summary.level === 'FRESH' || summary.level === 'NEVER') {
    els.staleBanner.hidden = true;
    els.staleBanner.textContent = '';
  } else {
    els.staleBanner.hidden = false;
    els.staleBanner.textContent = summary.level === 'ERROR'
      ? `最後一次輪詢失敗（${describeError(summary.error)}）。畫面上的站點與任務資料已失效，不能視為目前在線狀態。`
      : '資料已超過 15 秒未更新，畫面內容已過期，不能視為目前在線狀態。';
  }
}

// 讀取表單目前的值；RESTORE 固定送 RESTORE_SECONDS（後端不以秒數控制恢復時間）。
function readFaultForm(sitesView) {
  const site = els.faultSite.value;
  const action = selectedAction();
  const configured = model.normalizeConfiguredChannels(store.capabilities.data);
  const options = model.channelOptions(sitesView, store.knownChannels, site, action, configured);
  return {
    site,
    action,
    configured,
    options,
    channel: els.faultChannel.value,
    secondsRaw: action === 'RESTORE' ? String(RESTORE_SECONDS) : els.faultSeconds.value,
  };
}

function faultChecks(sitesView, form) {
  const validation = validateFaultRequest(
    { site: form.site, channel: form.channel, action: form.action, seconds: form.secondsRaw },
    { knownChannels: form.options.map((o) => o.value) },
  );
  const check = model.faultPrecheck({
    sitesView,
    knownChannels: store.knownChannels,
    configured: form.configured,
    site: form.site,
    channel: form.channel,
    action: form.action,
    seconds: validation.ok ? validation.value.seconds : null,
  });
  const limit = form.action === 'BLOCK' ? model.blockLimit(sitesView, form.site, form.channel) : null;
  return { validation, check, limit };
}

function syncFaultForm(sitesView) {
  const initial = readFaultForm(sitesView);
  const emptyLabel = initial.action === 'BLOCK'
    ? '（目前觀測中沒有可用通道）'
    : initial.configured.configured ? '（觀測與設定清單皆無通道）' : '（尚無已知通道；伺服器未提供設定清單）';
  view.syncSelect(els.faultChannel, initial.options, emptyLabel);
  const form = readFaultForm(sitesView);
  const { validation, check, limit } = faultChecks(sitesView, form);
  const isBlock = form.action === 'BLOCK';

  els.faultSeconds.disabled = !isBlock;
  els.faultSeconds.max = String(isBlock && limit !== null ? limit : FAULT_SECONDS_MAX);
  if (!isBlock) els.faultLimit.textContent = `RESTORE 不使用秒數控制恢復時間；依契約固定送出 seconds=${RESTORE_SECONDS}。`;
  else if (!form.channel) els.faultLimit.textContent = '本通道上限：未選擇通道。';
  else if (limit === null) els.faultLimit.textContent = '本通道上限：缺值（最新有效觀測未提供 maximumBlockSeconds），不能 BLOCK。';
  else els.faultLimit.textContent = `本通道上限：${limit} 秒（來自最新有效觀測的 maximumBlockSeconds）。`;
  els.faultLimit.className = `note ${isBlock && (limit === null || !form.channel) ? 'note-amber' : 'note-neutral'}`;

  const blocking = [...check.blocking, ...(validation.ok ? [] : validation.errors.filter((e) => !e.includes('通道')))];
  view.renderList(els.faultBlocking, [...new Set(blocking)], 'red');
  view.renderList(els.faultWarnings, check.warnings, 'amber');
  const s = validation.ok ? validation.value.seconds : '?';
  els.faultImpact.textContent = isBlock
    ? `影響：${siteLabel(form.site)} 的 ${form.channel || '（未選）'} 通道將關閉既有測試連線並拒絕新連線，${s} 秒後自動放行；期間跨站同步與協調可能延遲。不改變 GC 凍結狀態。`
    : `影響：立即恢復 ${siteLabel(form.site)} 的 ${form.channel || '（未選）'} 通道代理放行。恢復只代表代理放行，不代表節點在線或 worker 流量已確認接入。`;
  els.faultSubmit.disabled = busy.has('fault') || blocking.length > 0;
  els.faultSubmit.textContent = busy.has('fault') ? '送出中…' : '檢查並確認…';
}

function renderRuns(now) {
  const freshness = model.freshnessOf(store.runs, now);
  const entries = model.mergeRuns(store.runs.data, store.lookups);
  const active = model.findActiveRun(entries);
  const suites = model.normalizeSuites(store.suites.data);
  view.syncSuites(
    els.suiteList,
    suites,
    (suite) => {
      const reasons = model.startBlockers(suite, { runsFreshness: freshness, activeEntry: active });
      if (busy.has('start')) reasons.push('正在送出啟動請求…');
      return reasons;
    },
    onStartSuite,
  );
  const suitesFreshness = model.freshnessOf(store.suites, now, Infinity);
  if (suitesFreshness === 'FRESH') replace(els.suiteStatus);
  else replace(els.suiteStatus, view.freshnessLine(suitesFreshness, store.suites, now));
  view.renderActiveRun(els.activeRun, active, freshness, now);
  els.runCancel.disabled = !active || busy.has('cancel');
  els.runCancel.dataset.runId = active ? active.run.id : '';
  view.renderRunHistory(els.runHistory, entries, freshness, store.runs, now);
}

const deploymentUi = mountDeployment({
  button: els.deployExecute, reasonsEl: els.lockReasons, session, confirmDialog,
  execute: (id) => client.executeDeployment(id), getPlan: (id) => client.getDeploymentPlan(id),
  getCapabilities: () => client.getCapabilities(), getJob: (id) => client.getDeployment(id), reconcile: (id) => client.reconcileDeployment(id),
  // 雙站模式沒有租約所有權資料：恢復入口會停用並說明，不猜測主控。
  recovery: {
    preview: (jobId, body) => client.createDeploymentRecoveryPlan(jobId, body),
    get: (id) => client.getDeploymentRecoveryPlan(id),
    execute: (id, body) => client.executeDeploymentRecoveryPlan(id, body),
    reconcile: (id) => client.reconcileDeploymentRecoveryPlan(id),
  },
  recheck: () => model.freshnessOf(store.sites, Date.now()) === 'FRESH' && store.sites.data?.pairStatus === 'VERIFIED_DISTINCT' ? [] : ['站點觀測不是最新或身份未核對。'],
  onAuthFailure: handleAuthFailure,
});

function renderDeploy(now) {
  const releases = model.normalizeReleases(store.releases.data);
  const relFreshness = model.freshnessOf(store.releases, now);
  view.syncSelect(
    els.releaseSelect,
    releases.map((r) => ({ value: r.id, label: r.id })),
    relFreshness === 'ERROR' ? '（制品清單讀取失敗）' : '（伺服器未登記任何制品）',
  );
  const selected = releases.find((r) => r.id === els.releaseSelect.value) ?? null;
  view.renderReleaseDetails(els.releaseDetails, selected);
  if (relFreshness === 'ERROR') {
    els.releaseDetails.append(view.freshnessLine(relFreshness, store.releases, now));
  }
  els.planCreate.disabled = busy.has('plan') || releases.length === 0;
  els.planReload.disabled = busy.has('plan') || !store.plan || !isUuid(store.plan.id);
  deploymentUi.render({ plan: store.plan, capabilities: store.capabilities.data, extraReasons: [ ...(busy.has('plan') || store.capabilities.error ? ['計畫或能力資料正在更新／讀取失敗。'] : []), ...(model.freshnessOf(store.sites, now) !== 'FRESH' || store.sites.data?.pairStatus !== 'VERIFIED_DISTINCT' ? ['站點觀測不是最新或身份未核對。'] : []) ] });
  view.renderPlan(els.planView, store.plan, { releases, now, error: store.planError, receivedAt: store.planReceivedAt });
}

function renderAll() {
  if (!session.isActive()) return;
  const now = Date.now();
  const sitesView = model.deriveSites(store.sites, now);
  lastFreshness = [store.sites, store.runs, store.audit].map((r) => model.freshnessOf(r, now)).join('|');
  view.renderEnvChip(els.envChip, store.capabilities.data);
  view.renderCapSummary(els.capSummary, store.capabilities);
  view.renderCapabilities(els.capabilities, store.capabilities, model.freshnessOf(store.capabilities, now, Infinity), now);
  view.renderOverview(els.overview, {
    summary: model.connectionSummary([store.sites, store.runs, store.audit], now),
    sitesView,
    runsFreshness: model.freshnessOf(store.runs, now),
    activeEntry: model.findActiveRun(model.mergeRuns(store.runs.data, store.lookups)),
    now,
  });
  view.renderPairBar(els.pairBar, sitesView, now);
  view.renderSites(els.siteGrid, sitesView, now);
  view.renderGc(els.gc, model.compareGc(sitesView), now);
  syncFaultForm(sitesView);
  renderRuns(now);
  renderDeploy(now);
  view.renderAudit(els.audit, model.auditEntries(store.audit.data), model.freshnessOf(store.audit, now), store.audit, now);
  renderStatus(now);
}

// ---------- 通道故障 ----------

async function onFaultSubmit(event) {
  event.preventDefault();
  if (busy.has('fault') || !session.isActive()) return;
  const now = Date.now();
  const sitesView = model.deriveSites(store.sites, now);
  const form = readFaultForm(sitesView);
  const { action } = form;
  const { validation, check, limit } = faultChecks(sitesView, form);
  if (!validation.ok) {
    view.renderMessage(els.faultResult, { tone: 'red', title: '未送出：輸入不符合契約', lines: validation.errors });
    return;
  }
  if (check.blocking.length) {
    view.renderMessage(els.faultResult, { tone: 'red', title: '未送出：前置檢查未通過', lines: check.blocking });
    return;
  }
  const { site, channel, seconds } = validation.value;
  if (action === 'BLOCK' && (limit === null || seconds > limit)) {
    view.renderMessage(els.faultResult, { tone: 'red', title: '未送出：秒數超過節點上限或上限缺值', lines: [] });
    return;
  }
  const lines = action === 'BLOCK'
    ? [
        `目標站點：${siteLabel(site)}`,
        `通道：${channel}`,
        `動作：BLOCK，持續 ${seconds} 秒（到期自動放行）`,
        `此通道節點上限：${limit} 秒（最新有效觀測）`,
        '影響：關閉此通道既有測試連線並拒絕新連線；跨站同步與協調在期間可能延遲。',
        '不會改變 GC 凍結狀態；可隨時以 RESTORE 提前恢復。',
      ]
    : [
        `目標站點：${siteLabel(site)}`,
        `通道：${channel}`,
        `動作：RESTORE（立即恢復代理放行；依契約送出 seconds=${seconds}，後端不以此控制時長）`,
        '恢復只代表代理放行；routingVerified=false，仍需另行確認 worker 流量已接入代理。',
      ];
  lines.push(...check.warnings);
  const ok = await confirmDialog({
    title: action === 'BLOCK' ? '確認阻斷通道' : '確認恢復通道',
    lines,
    confirmLabel: action === 'BLOCK' ? `確認 BLOCK ${channel} ${seconds} 秒` : `確認 RESTORE ${channel}`,
  });
  if (!ok || !session.isActive()) return;
  const gen = generation;
  busy.add('fault');
  syncFaultForm(model.deriveSites(store.sites, Date.now()));
  try {
    const res = await client.postFault(site, { channel, action, seconds });
    if (gen !== generation) return;
    const ch = model.isPlainObject(res?.channel) ? res.channel : {};
    view.renderMessage(els.faultResult, {
      tone: 'teal',
      title: `伺服器已接受 ${action} 請求（requestId ${res?.requestId ?? '未提供'}）`,
      lines: [
        `回報通道狀態：${ch.state ?? '（未提供）'}${ch.expiresAt ? `，到期 ${formatTime(ch.expiresAt)}` : ''}`,
        `routingVerified=${String(ch.routingVerified)}：不代表 worker 流量已接入代理。`,
        '正在重新讀取站點狀態以確認實際結果。',
      ],
    });
  } catch (err) {
    if (gen !== generation || handleAuthFailure(err)) return;
    view.renderMessage(els.faultResult, err?.outcomeUnknown
      ? {
          tone: 'amber',
          title: '結果未知（OUTCOME_UNKNOWN）',
          lines: [describeError(err), '請求可能已在節點執行。請重新讀取站點狀態，勿假定未執行。'],
        }
      : { tone: 'red', title: `${action} 未被接受`, lines: [describeError(err)] });
  } finally {
    if (gen === generation) {
      busy.delete('fault');
      refreshNow();
    }
  }
}

// ---------- 驗收任務 ----------

async function onStartSuite(suiteId) {
  if (busy.has('start') || !session.isActive()) return;
  const now = Date.now();
  const suite = model.normalizeSuites(store.suites.data).find((s) => s.id === suiteId);
  const entries = model.mergeRuns(store.runs.data, store.lookups);
  const reasons = model.startBlockers(suite, {
    runsFreshness: model.freshnessOf(store.runs, now),
    activeEntry: model.findActiveRun(entries),
  });
  if (reasons.length) {
    notify('amber', '無法啟動任務', reasons);
    return;
  }
  const ok = await confirmDialog({
    title: '確認執行固定驗收任務',
    lines: [
      `套件：${suite.id}`,
      `範圍：${SUITE_SCOPES[suite.id]}`,
      '只執行伺服器預先登記的固定任務，不接受任何命令、參數、路徑或 URL。',
      '一次只能執行一個任務；取消不會撤銷已完成的測試操作。',
      'COMMAND_SUCCEEDED 只代表 Maven 退出碼 0，不代表遠端驗收通過；不會進行部署。',
    ],
    confirmLabel: `確認執行 ${suite.id}`,
  });
  if (!ok || !session.isActive()) return;
  const gen = generation;
  busy.add('start');
  renderAll();
  try {
    const run = await client.startTestRun(suite.id);
    if (gen !== generation) return;
    if (model.normalizeRun(run)) store.lookups.set(run.id, { run, fetchedAt: Date.now(), error: null });
    notify('teal', `已送出任務 ${shortId(run?.id)}，狀態 ${run?.state ?? '未提供'}`, ['請以任務紀錄追蹤後續狀態。']);
  } catch (err) {
    if (gen !== generation || handleAuthFailure(err)) return;
    notify(err?.outcomeUnknown ? 'amber' : 'red', err?.outcomeUnknown ? '啟動結果未知，請重新讀取任務清單' : '任務未啟動', [
      err?.status === 409 ? '伺服器回報目前狀態不允許（可能已有任務在執行或套件未啟用）。' : describeError(err),
    ]);
  } finally {
    if (gen === generation) {
      busy.delete('start');
      refreshNow();
    }
  }
}

async function onCancelRun() {
  const id = els.runCancel.dataset.runId;
  if (busy.has('cancel') || !isUuid(id) || !session.isActive()) return;
  const ok = await confirmDialog({
    title: '確認取消任務',
    lines: [
      `任務：${id}`,
      '取消不會撤銷已完成的測試操作，需另行檢查資源清理。',
    ],
    confirmLabel: '確認取消任務',
  });
  if (!ok || !session.isActive()) return;
  const gen = generation;
  busy.add('cancel');
  els.runCancel.disabled = true;
  try {
    const run = await client.cancelTestRun(id);
    if (gen !== generation) return;
    if (model.normalizeRun(run)) store.lookups.set(run.id, { run, fetchedAt: Date.now(), error: null });
    notify('neutral', `取消請求已回應，任務狀態 ${run?.state ?? '未提供'}`, ['取消不會撤銷已完成的測試操作。']);
  } catch (err) {
    if (gen !== generation || handleAuthFailure(err)) return;
    notify(err?.outcomeUnknown ? 'amber' : 'red', err?.outcomeUnknown ? '取消結果未知' : '取消未被接受', [describeError(err)]);
  } finally {
    if (gen === generation) {
      busy.delete('cancel');
      refreshNow();
    }
  }
}

async function onRunLookup(event) {
  event.preventDefault();
  const id = els.runLookupId.value.trim().toLowerCase();
  if (!isUuid(id)) {
    notify('red', '任務 ID 必須是 UUID 格式。');
    return;
  }
  if (busy.has('lookup') || !session.isActive()) return;
  const gen = generation;
  busy.add('lookup');
  els.runLookupSubmit.disabled = true;
  try {
    const run = await client.getTestRun(id);
    if (gen !== generation) return;
    if (!model.normalizeRun(run)) throw Object.assign(new Error('protocol'), { kind: 'protocol', serverMessage: '任務資料缺少有效 id' });
    store.lookups.set(run.id, { run, fetchedAt: Date.now(), error: null });
    notify('neutral', `已讀取任務 ${shortId(run.id)}：${run.state ?? '未提供'}`);
    renderAll();
  } catch (err) {
    if (gen !== generation || handleAuthFailure(err)) return;
    notify('red', err?.status === 404 ? '找不到此任務。' : '查詢失敗', err?.status === 404 ? [] : [describeError(err)]);
  } finally {
    if (gen === generation) {
      busy.delete('lookup');
      els.runLookupSubmit.disabled = false;
    }
  }
}

// ---------- 部署預覽 ----------

async function onPlanCreate(event) {
  event.preventDefault();
  if (busy.has('plan') || !session.isActive()) return;
  const releases = model.normalizeReleases(store.releases.data);
  const sites = [...els.planForm.querySelectorAll('input[name="plan-site"]:checked')].map((i) => i.value);
  const check = validatePlanRequest({ releaseId: els.releaseSelect.value, sites }, { knownReleaseIds: releases.map((r) => r.id) });
  if (!check.ok) {
    store.planError = { kind: 'validation', details: check.errors };
    renderAll();
    return;
  }
  const gen = generation;
  busy.add('plan');
  renderAll();
  try {
    const plan = await client.createDeploymentPlan(check.value);
    if (gen !== generation) return;
    store.plan = model.isPlainObject(plan) ? plan : null;
    store.planReceivedAt = Date.now();
    store.planError = store.plan ? null : { kind: 'protocol', serverMessage: '計畫回應不是物件' };
  } catch (err) {
    if (gen !== generation || handleAuthFailure(err)) return;
    store.planError = err;
  } finally {
    if (gen === generation) {
      busy.delete('plan');
      renderAll();
    }
  }
}

async function onPlanReload() {
  const id = store.plan?.id;
  if (busy.has('plan') || !isUuid(id) || !session.isActive()) return;
  const gen = generation;
  busy.add('plan');
  renderAll();
  try {
    const plan = await client.getDeploymentPlan(id);
    if (gen !== generation) return;
    if (model.isPlainObject(plan)) {
      store.plan = plan;
      store.planReceivedAt = Date.now();
      store.planError = null;
    }
  } catch (err) {
    if (gen !== generation || handleAuthFailure(err)) return;
    store.planError = err;
  } finally {
    if (gen === generation) {
      busy.delete('plan');
      renderAll();
    }
  }
}

// ---------- 事件綁定 ----------

els.dialogCancel.addEventListener('click', () => els.dialog.close('cancel'));
els.dialogConfirm.addEventListener('click', () => els.dialog.close('confirm'));
els.loginForm.addEventListener('submit', onLoginSubmit);
els.logout.addEventListener('click', () => logout('已登出，權杖與密碼欄位已清除。'));
els.navToggle.addEventListener('click', () => setNavOpen(true));
els.navClose.addEventListener('click', () => { setNavOpen(false); els.navToggle.focus(); });
els.navScrim.addEventListener('click', () => setNavOpen(false));
// 點目前所在頁的連結不會觸發 hashchange，仍需收起手機抽屜。
els.sidebar.addEventListener('click', (event) => { if (event.target.closest?.('a[data-route]')) setNavOpen(false); });
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && els.shell.classList.contains('nav-open')) { setNavOpen(false); els.navToggle.focus(); }
});
els.refresh.addEventListener('click', () => {
  if (cluster && cluster.isActive()) cluster.refreshNow();
  else refreshNow({ includeStatic: true });
  if (router.current() === 'servers') servers.load();
  if (router.current() === 'tasks') tasks.reload();
});

const resyncFault = () => {
  if (session.isActive()) syncFaultForm(model.deriveSites(store.sites, Date.now()));
};
els.faultSite.addEventListener('change', resyncFault);
els.faultChannel.addEventListener('change', resyncFault);
els.faultSeconds.addEventListener('input', resyncFault);
for (const radio of els.faultForm.querySelectorAll('input[name="fault-action"]')) radio.addEventListener('change', resyncFault);
els.faultForm.addEventListener('submit', onFaultSubmit);

els.runCancel.addEventListener('click', onCancelRun);
els.runLookupForm.addEventListener('submit', onRunLookup);

els.planForm.addEventListener('submit', onPlanCreate);
els.planReload.addEventListener('click', onPlanReload);
els.releaseSelect.addEventListener('change', () => {
  if (session.isActive()) renderDeploy(Date.now());
});

els.auditLimit.addEventListener('change', () => {
  const n = Number(els.auditLimit.value);
  store.auditLimit = isValidAuditLimit(n) ? n : AUDIT_LIMIT_DEFAULT;
  refreshNow();
});

// 依部署時固定的 CONTROL_MODE 顯示頂列與登入字樣（HTML 靜態為中性文字，不做執行期切換）。
function applyModeBranding() {
  const branding = modeBranding(CONTROL_MODE);
  $('brand-sub').textContent = branding.sub;
}

applyModeBranding();
showLoginView();
