import { readResources } from './read-resources.js';
import { mountDeployment } from './deployment-ui.js';
import * as ds from './deployment-state.js';
import { mountHandoff } from './handoff-ui.js';
// 多站叢集中控控制器（CONTROL_MODE = 'cluster'）。
// - 簡短接口：一輪完成後隔 5 秒再排下一輪（8 秒逾時）。
// - /observations：獨立的單一長請求（預設 360 秒逾時），完成後隔 5 秒再排；不重疊。
// - 新鮮度以 performance.now() 自成功請求開始時間起算。
// - 401／403 交給外層登出；503、逾時、網路錯誤只標示未知並保留 Session。
// - 所有寫入都由使用者點擊並確認；帶 requestId 的寫入在結果不明時以相同內容重送。
// - 部署需明確確認；不呼叫業務 consent API，不提供豁免或強制選項。

import {
  CLUSTER_AUDIT_LIMIT_CHOICES,
  CLUSTER_AUDIT_LIMIT_DEFAULT,
  CLUSTER_LIST_LIMIT,
  CLUSTER_MAX_PREVIEW_SITES,
  CLUSTER_POLL_INTERVAL_MS,
  CLUSTER_STATUS_STALE_MS,
  FAULT_SECONDS_MAX,
  OBSERVATION_STALE_MS,
  RESTORE_SECONDS,
  SUITE_SCOPES,
} from './constants.js';
import { createClusterClient, createControlClient } from './api.js';
import * as cm from './cluster-state.js';
import * as cv from './cluster-render.js';
import * as view from './render.js';
import { homeModel } from './home-state.js';
import * as hs from './health-state.js';
import { renderHome as renderHomeView } from './home-render.js';
import { describeError, formatTime, shortId } from './format.js';
import * as model from './state.js';
import { auditEntries, normalizeReleases } from './state.js';
import { isUuid, validateClusterFault } from './validate.js';

const STATIC_KEYS = ['capabilities', 'profiles', 'channels', 'releases', 'suites'];

function initialStore() {
  const store = {
    preview: null,
    previewError: null,
    gcPendingOnly: true,
    auditLimit: CLUSTER_AUDIT_LIMIT_DEFAULT,
    // 依 ID 查詢或本頁啟動／取消回應的任務快照：Map<id, { run, fetchedAt, error }>
    lookups: new Map(),
    // 收到 CONTROL_LEASE_NOT_HELD 的單調時間；在此之前開始的領導讀取不再採信。
    leadershipInvalidatedAt: null,
  };
  for (const key of ['status', 'leadership', 'changes', 'gc', 'candidates', 'audit', 'fileAudit', 'runs', 'observations', ...STATIC_KEYS]) {
    store[key] = cm.emptyMono();
  }
  return store;
}

function isAuthFailure(err) {
  return Boolean(err) && err.kind !== 'validation' && (err.status === 401 || err.status === 403);
}

export function createClusterController({
  session,
  confirmDialog,
  onAuthFailure,
  monoNow = () => performance.now(),
  wallNow = () => Date.now(),
  fetchImpl,
  onSummary,
}) {
  const client = createClusterClient({ session, fetchImpl });
  const legacy = createControlClient({ session, fetchImpl });

  const $ = (id) => {
    const el = document.getElementById(id);
    if (!el) throw new Error(`缺少元素 #${id}`);
    return el;
  };
  const els = {
    view: $('cluster-view'),
    connection: $('connection'),
    envChip: $('env-chip'),
    banner: $('cl-banner'),
    notice: $('cl-notice'),
    overview: $('cl-overview'),
    epoch: $('cl-epoch'),
    clusterId: $('cl-cluster-id'),
    members: $('cl-members'),
    memberForm: $('cl-member-form'),
    memberSite: $('cl-member-site'),
    memberImpact: $('cl-member-impact'),
    memberBlocking: $('cl-member-blocking'),
    memberWarnings: $('cl-member-warnings'),
    memberSubmit: $('cl-member-submit'),
    memberResult: $('cl-member-result'),
    registerForm: $('cl-register-form'),
    profileSelect: $('cl-profile-select'),
    profileDetails: $('cl-profile-details'),
    registerSubmit: $('cl-register-submit'),
    profilesReload: $('cl-profiles-reload'),
    registerResult: $('cl-register-result'),
    changesPending: $('cl-changes-pending'),
    changesRecent: $('cl-changes-recent'),
    resumeForm: $('cl-resume-form'),
    resumeChange: $('cl-resume-change'),
    resumeSubmit: $('cl-resume-submit'),
    resumeResult: $('cl-resume-result'),
    resumeHint: $('cl-resume-hint'),
    faultForm: $('cl-fault-form'),
    faultSite: $('cl-fault-site'),
    faultChannel: $('cl-fault-channel'),
    faultSeconds: $('cl-fault-seconds'),
    faultLimit: $('cl-fault-limit'),
    faultImpact: $('cl-fault-impact'),
    faultBlocking: $('cl-fault-blocking'),
    faultWarnings: $('cl-fault-warnings'),
    faultSubmit: $('cl-fault-submit'),
    faultResult: $('cl-fault-result'),
    gcCandidates: $('cl-gc-candidates'),
    gcBeginForm: $('cl-gc-begin-form'),
    gcCandidate: $('cl-gc-candidate'),
    gcBeginReasons: $('cl-gc-begin-reasons'),
    gcBeginSubmit: $('cl-gc-begin-submit'),
    gcPendingOnly: $('cl-gc-pending-only'),
    gcResumeForm: $('cl-gc-resume-form'),
    gcResumeSelect: $('cl-gc-resume-select'),
    gcResumeSubmit: $('cl-gc-resume-submit'),
    gcResult: $('cl-gc-result'),
    gcResumeHint: $('cl-gc-resume-hint'),
    leadership: $('cl-leadership'),
    gcList: $('cl-gc-list'),
    planForm: $('cl-plan-form'),
    releaseSelect: $('cl-release-select'),
    releaseDetails: $('cl-release-details'),
    planSites: $('cl-plan-sites'),
    planReasons: $('cl-plan-reasons'),
    planCreate: $('cl-plan-create'),
    planReload: $('cl-plan-reload'),
    planResult: $('cl-plan-result'),
    deployExecute: $('cl-deploy-execute'),
    lockReasons: $('cl-lock-reasons'),
    planView: $('cl-plan-view'),
    auditLimit: $('cl-audit-limit'),
    audit: $('cl-audit'),
    fileAudit: $('cl-file-audit'),
    capSummary: $('cl-cap-summary'),
    capabilities: $('cl-capabilities'),
    suiteStatus: $('cl-suite-status'),
    suiteList: $('cl-suite-list'),
    activeRun: $('cl-active-run'),
    runCancel: $('cl-run-cancel'),
    runResult: $('cl-run-result'),
    runLookupForm: $('cl-run-lookup-form'),
    runLookupId: $('cl-run-lookup-id'),
    runLookupSubmit: $('cl-run-lookup-submit'),
    runHistory: $('cl-run-history'),
  };

  let store = initialStore();
  let generation = 0;
  let active = false;
  let shortTimer = null;
  let obsTimer = null;
  let tickTimer = null;
  let shortInflight = null;
  let controller = null;
  let lastSignature = '';
  const busy = new Set();
  const intents = cm.createIntentStore();
  // 健康檢測：每站一則操作結果；healthGen 在登出或離開站點頁時遞增，遲到的回應一律丟棄。
  const healthMessages = new Map();
  let healthGen = 0;
  let sitesVisible = false;
  let healthWatchTimer = null;
  let healthWatchUntil = 0;

  const deploymentUi = mountDeployment({
    button: els.deployExecute, reasonsEl: els.lockReasons, session, confirmDialog,
    execute: (id) => client.executeDeployment(id, signalOpts()), getPlan: (id) => client.getPreview(id, signalOpts()),
    getCapabilities: () => client.getCapabilities(signalOpts()), getJob: (id) => legacy.getDeployment(id, signalOpts()), reconcile: (id) => legacy.reconcileDeployment(id, signalOpts()),
    recovery: {
      preview: (jobId, body) => legacy.createDeploymentRecoveryPlan(jobId, body, signalOpts()),
      get: (id) => legacy.getDeploymentRecoveryPlan(id, signalOpts()),
      execute: (id, body) => legacy.executeDeploymentRecoveryPlan(id, body, signalOpts()),
      reconcile: (id) => legacy.reconcileDeploymentRecoveryPlan(id, signalOpts()),
    },
    recheck: (plan) => {
      const d = derive();
      return [...leaderBlocking(d), ...(!d.ctx.statusFresh || d.ctx.epoch !== plan.epoch ? ['成員狀態已過期或 epoch 改變。'] : [])];
    }, onAuthFailure,
    onJobChange: () => { if (active) renderHome(); },
  });
  const handoffUi = mountHandoff({ client, session, confirmDialog, onAuthFailure, refresh: () => refreshShort(),
    snapshot: () => ({ raw: store.leadership.data, fresh: freshness('leadership', monoNow()) === 'FRESH', enabled: store.capabilities.data?.leadershipHandoffEnabled === true }),
    readLeadership: async (options) => {
      const gen = generation, started = monoNow();
      const raw = await client.getLeadership(options);
      if (active && gen === generation && (store.leadership.startedAt ?? -Infinity) <= started) store.leadership = cm.monoSucceed(store.leadership, raw, started, monoNow(), wallNow());
      return raw;
    },
  });

  // ---------- 衍生資料 ----------

  function derive(now = monoNow()) {
    const status = cm.normalizeStatus(store.status.data);
    const statusFreshness = cm.monoFreshness(store.status, now, CLUSTER_STATUS_STALE_MS);
    const obsFreshness = cm.monoFreshness(store.observations, now, OBSERVATION_STALE_MS);
    const caps = cm.normalizeCapabilities(store.capabilities.data);
    const views = cm.buildMemberViews({ status, observations: store.observations.data, observationFreshness: obsFreshness });
    const ctx = cm.actionContext({ status, statusFreshness, capabilities: caps });
    const gcList = cm.normalizeGcList(store.gc.data).map(cm.gcView);
    return {
      now,
      status,
      statusFreshness,
      obsFreshness,
      caps,
      anomalies: cm.capabilityAnomalies(caps),
      views,
      ctx,
      catalog: cm.normalizeOfflineChannels(store.channels.data),
      gcList,
      openGc: gcList.filter((g) => g.open),
      gcFreshness: cm.monoFreshness(store.gc, now, CLUSTER_STATUS_STALE_MS),
      candidates: cm.normalizeCandidates(store.candidates.data),
      releases: normalizeReleases(store.releases.data),
      lead: cm.leadershipView({
        leadershipRes: store.leadership,
        leadershipFreshness: cm.monoFreshness(store.leadership, now, CLUSTER_STATUS_STALE_MS),
        statusRes: store.status,
        statusFreshness,
        invalidatedAt: store.leadershipInvalidatedAt,
      }),
      leadershipFreshness: cm.monoFreshness(store.leadership, now, CLUSTER_STATUS_STALE_MS),
    };
  }

  // 依領導狀態停用控制寫入的共用原因（寫在每個表單的阻擋清單最前面）。
  function leaderBlocking(d) {
    return d.lead.writable ? [] : d.lead.reasons;
  }

  function freshness(key, now, threshold = CLUSTER_STATUS_STALE_MS) {
    return cm.monoFreshness(store[key], now, threshold);
  }

  function radioValue(form, name, fallback) {
    const checked = form.querySelector(`input[name="${name}"]:checked`);
    return checked ? checked.value : fallback;
  }

  function notify(tone, title, lines = []) {
    view.renderMessage(els.notice, { tone, title, lines });
  }

  // ---------- 讀取 ----------

  function signalOpts() {
    return { signal: controller ? controller.signal : undefined };
  }

  async function refreshShort({ includeStatic = false } = {}) {
    const gen = generation;
    if (!active || !session.isActive()) return;
    clearTimeout(shortTimer);
    shortTimer = null;
    while (shortInflight) {
      try {
        await shortInflight;
      } catch {
        /* 錯誤已記錄在各資源 */
      }
    }
    if (gen !== generation || !active) return;
    const opts = signalOpts();
    const tasks = {
      status: () => client.getStatus(opts),
      leadership: () => client.getLeadership(opts),
      changes: () => client.listChanges(CLUSTER_LIST_LIMIT, opts),
      gc: () => client.listGc({ limit: CLUSTER_LIST_LIMIT, pendingOnly: store.gcPendingOnly }, opts),
      candidates: () => client.listGcCandidates(CLUSTER_LIST_LIMIT, opts),
      audit: () => client.getAudit(store.auditLimit, opts),
      fileAudit: () => legacy.getAudit(store.auditLimit, opts),
      runs: () => legacy.listTestRuns(opts),
    };
    if (includeStatic) {
      Object.assign(tasks, {
        capabilities: () => client.getCapabilities(opts),
        profiles: () => client.getProfiles(opts),
        channels: () => client.getChannels(opts),
        releases: () => legacy.listReleases(opts),
        suites: () => legacy.getTestSuites(opts),
      });
    }
    const job = (async () => {
      await readResources(tasks, monoNow, (key, r, started, received) => {
        if (gen !== generation || !active) return;
        if (key === 'leadership' && (store[key].startedAt ?? -Infinity) > started) return;
        store[key] = r.status === 'fulfilled' ? cm.monoSucceed(store[key], r.value, started, received, wallNow()) : cm.monoFail(store[key], r.reason);
        if (r.status === 'rejected' && isAuthFailure(r.reason)) {
          onAuthFailure(r.reason);
          return;
        }
        renderAll();
      });
      if (gen !== generation || !active) return;
      await refreshActiveLookups(gen, opts);
    })();
    shortInflight = job;
    try {
      await job;
    } finally {
      if (shortInflight === job) shortInflight = null;
      if (gen === generation && active && session.isActive()) {
        renderAll();
        clearTimeout(shortTimer);
        shortTimer = setTimeout(() => refreshShort(), CLUSTER_POLL_INTERVAL_MS);
      }
    }
  }

  // 依 ID 查詢到、但不在伺服器清單中的執行中任務：每輪重新讀取，避免把舊快照當成仍在執行。
  async function refreshActiveLookups(gen, opts) {
    const listed = Array.isArray(store.runs.data) ? store.runs.data : [];
    const listedIds = new Set(listed.map((r) => r?.id));
    for (const [id, entry] of [...store.lookups.entries()]) {
      if (listedIds.has(id) || !model.isRunActive(entry.run)) continue;
      try {
        const run = await legacy.getTestRun(id, opts);
        if (gen !== generation) return;
        store.lookups.set(id, { run, fetchedAt: wallNow(), error: null });
      } catch (err) {
        if (gen !== generation) return;
        if (isAuthFailure(err)) {
          onAuthFailure(err);
          return;
        }
        store.lookups.set(id, { ...entry, error: err });
      }
    }
  }

  // 單一觀測請求：進行中不再發第二個；完成後才排下一輪。
  async function refreshObservations() {
    const gen = generation;
    if (!active || !session.isActive()) return;
    clearTimeout(obsTimer);
    obsTimer = null;
    if (store.observations.inFlightSince !== null) return;
    const started = monoNow();
    store.observations = cm.monoStart(store.observations, started);
    renderLive();
    try {
      const data = await client.getObservations(signalOpts());
      if (gen !== generation) return;
      store.observations = cm.monoSucceed(store.observations, data, started, monoNow(), wallNow());
    } catch (err) {
      if (gen !== generation) return;
      store.observations = cm.monoFail(store.observations, err);
      if (isAuthFailure(err)) {
        onAuthFailure(err);
        return;
      }
    } finally {
      if (gen === generation && active && session.isActive()) {
        renderAll();
        clearTimeout(obsTimer);
        obsTimer = setTimeout(() => refreshObservations(), CLUSTER_POLL_INTERVAL_MS);
      }
    }
  }

  // ---------- 渲染 ----------

  function freshnessSignature(now) {
    return [
      freshness('status', now),
      freshness('observations', now, OBSERVATION_STALE_MS),
      freshness('gc', now),
      freshness('changes', now),
      freshness('audit', now),
      freshness('fileAudit', now),
      freshness('runs', now),
      freshness('leadership', now),
      String(store.leadershipInvalidatedAt),
      cm.blockFreshness(store.observations, now).ok,
    ].join('|');
  }

  function renderBanner(d) {
    const parts = [];
    if (d.statusFreshness === 'ERROR') parts.push(`叢集狀態讀取失敗（${describeError(store.status.error)}）`);
    else if (d.statusFreshness === 'STALE') parts.push('叢集狀態超過 15 秒未更新');
    if (d.obsFreshness === 'ERROR') parts.push(`逐站觀測失敗（${describeError(store.observations.error)}）`);
    if (d.leadershipFreshness === 'ERROR' && !d.lead.lead) parts.push(`控制領導狀態讀取失敗（${describeError(store.leadership.error)}）`);
    if (!parts.length) {
      els.banner.hidden = true;
      els.banner.textContent = '';
      return;
    }
    els.banner.hidden = false;
    els.banner.textContent = `${parts.join('；')}。畫面資料已失效，依賴最新狀態的寫入已停用；登入狀態保留，會繼續重試。`;
  }

  function renderLive(now = monoNow()) {
    if (!active) return;
    const d = derive(now);
    cv.renderClusterConnection(els.connection, {
      statusFreshness: d.statusFreshness,
      statusRes: store.status,
      obsFreshness: d.obsFreshness,
      obsRes: store.observations,
      now,
    });
    renderBanner(d);
    syncFaultForm(d);
  }

  // 總覽：只在資料或新鮮度改變時重繪，避免每秒重建連結而打斷鍵盤焦點。
  function renderHome(d = derive()) {
    if (!active) return;
    const model = homeModel({
      statusFreshness: d.statusFreshness,
      obsFreshness: d.obsFreshness,
      status: d.status,
      views: d.views,
      lead: d.lead,
      leadershipRaw: store.leadership.data,
      anomalies: d.anomalies,
      openGcCount: d.openGc.length,
      job: deploymentUi.current(),
    });
    const received = store.status.receivedWall;
    const line = document.createElement('span');
    line.className = 'card-sub';
    line.textContent = d.statusFreshness === 'FRESH' && received ? `更新於 ${formatTime(received)}` : d.statusFreshness === 'NEVER' ? '讀取中…' : '資料已失效';
    renderHomeView(els.overview, model, { freshnessLine: line });
    els.epoch.textContent = d.status?.epoch ?? '—';
    els.clusterId.textContent = d.status?.clusterId ?? '—';
    onSummary?.(model);
  }

  function renderAll() {
    if (!active) return;
    const now = monoNow();
    lastSignature = freshnessSignature(now);
    const d = derive(now);
    renderHome(d);
    els.envChip.hidden = false;
    els.envChip.className = `env-chip${d.caps && d.caps.topology !== 'SINGLE_CLUSTER' ? ' env-chip-warn' : ''}`;
    els.envChip.textContent = d.caps ? `多站叢集 · ${d.caps.topology ?? '未知'}` : '多站叢集';

    cv.renderLeadership(els.leadership, d.lead, {
      freshness: d.leadershipFreshness,
      res: store.leadership,
      now,
      transferEnabled: d.caps?.coordinatorTransferEnabled,
    });
    handoffUi.render();

    cv.renderMembers(els.members, d.views, {
      epoch: d.status?.epoch ?? null, statusFreshness: d.statusFreshness, statusRes: store.status, now,
      health: { now: wallNow(), busy, messages: healthMessages, leadWritable: d.lead.writable, leadReasons: d.lead.reasons, sessionActive: session.isActive() },
    });

    const pending = (d.status?.pendingChanges ?? []).map(cm.changeView);
    cv.renderChangeList(els.changesPending, pending, {
      emptyText: d.statusFreshness === 'FRESH' ? '目前沒有進行中的成員變更。' : '叢集狀態未即時，無法確認是否有進行中的變更。',
    });
    const recent = cm.normalizeChanges(store.changes.data).map(cm.changeView);
    cv.renderChangeList(els.changesRecent, recent, {
      emptyText: freshness('changes', now) === 'ERROR' ? `最近變更讀取失敗：${describeError(store.changes.error)}` : '沒有變更紀錄。',
    });

    cv.renderGcCandidates(els.gcCandidates, d.candidates, {
      freshness: freshness('candidates', now),
      res: store.candidates,
      now,
      checkFor: (c) => cm.candidateCheck(c, { statusFresh: d.ctx.statusFresh, epoch: d.ctx.epoch, openGc: d.openGc }),
    });
    cv.renderGcList(els.gcList, d.gcList, { freshness: d.gcFreshness, res: store.gc, now, pendingOnly: store.gcPendingOnly });

    const pv = cm.previewView(store.preview, d.status, d.releases);
    cv.renderClusterPreview(els.planView, pv, { error: store.previewError });
    deploymentUi.render({plan: pv, capabilities: d.caps,
      extraReasons: [...leaderBlocking(d), ...(!d.ctx.statusFresh || store.capabilities.error || busy.has('plan') ? ['狀態正在更新或讀取失敗。'] : [])],
      confirmationKey: d.lead.lead?.generation,
      // 恢復已接受任務看「目前實際持有租約」，不沿用接納新任務的寫入閘門；沒有有效的領導資料就是未知。
      leaseOwnership: d.lead.lead ? { known: true, owned: d.lead.lead.localLeader === true && d.lead.lead.state === 'HELD' } : { known: false, owned: false },
    });

    cv.renderClusterAudit(els.audit, cm.normalizeClusterAudit(store.audit.data), { freshness: freshness('audit', now), res: store.audit, now });
    cv.renderFileAudit(els.fileAudit, auditEntries(store.fileAudit.data), { freshness: freshness('fileAudit', now), res: store.fileAudit, now });

    const capFresh = cm.monoFreshness(store.capabilities, now, Infinity);
    cv.renderClusterCapSummary(els.capSummary, d.caps, d.anomalies, store.capabilities.error);
    cv.renderClusterCapabilities(els.capabilities, d.caps, d.anomalies, { freshness: capFresh, res: store.capabilities, now });

    syncMemberForm(d);
    syncRegisterForm(d);
    syncResumeForm(d);
    syncGcForms(d);
    syncPlanForm(d);
    renderRuns(now);
    renderLive(now);
  }

  // ---------- 驗收任務（重用既有 /test-suites、/test-runs 與 state.js 模型） ----------

  // 既有渲染函式以牆鐘 receivedAt 顯示時間；新鮮度判定仍用單調時間。
  function wallResource(res) {
    return { receivedAt: res.receivedWall, error: res.error };
  }

  function runContext(now) {
    const runsFreshness = freshness('runs', now);
    const entries = model.mergeRuns(store.runs.data, store.lookups);
    return { runsFreshness, entries, activeEntry: model.findActiveRun(entries) };
  }

  function suiteBlockers(suite, ctx) {
    const reasons = model.startBlockers(suite, { runsFreshness: ctx.runsFreshness, activeEntry: ctx.activeEntry });
    if (busy.has('start')) reasons.push('正在送出啟動請求…');
    return reasons;
  }

  function renderRuns(now) {
    const ctx = runContext(now);
    const wall = wallNow();
    view.syncSuites(els.suiteList, model.normalizeSuites(store.suites.data), (suite) => suiteBlockers(suite, ctx), onStartSuite, 'cl-suite');
    const suitesFreshness = cm.monoFreshness(store.suites, now, Infinity);
    if (suitesFreshness === 'FRESH') els.suiteStatus.replaceChildren();
    else els.suiteStatus.replaceChildren(view.freshnessLine(suitesFreshness, wallResource(store.suites), wall));
    view.renderActiveRun(els.activeRun, ctx.activeEntry, ctx.runsFreshness, wall);
    els.runCancel.disabled = !ctx.activeEntry || busy.has('cancel');
    els.runCancel.dataset.runId = ctx.activeEntry ? ctx.activeEntry.run.id : '';
    view.renderRunHistory(els.runHistory, ctx.entries, ctx.runsFreshness, wallResource(store.runs), wall);
    els.runLookupSubmit.disabled = busy.has('lookup');
  }

  function rememberRun(run) {
    if (model.normalizeRun(run)) store.lookups.set(run.id, { run, fetchedAt: wallNow(), error: null });
  }

  function onStartSuite(suiteId) {
    const now = monoNow();
    const suite = model.normalizeSuites(store.suites.data).find((s) => s.id === suiteId);
    const reasons = suiteBlockers(suite, runContext(now));
    if (reasons.length) {
      view.renderMessage(els.runResult, { tone: 'amber', title: '無法啟動任務', lines: reasons });
      return;
    }
    runWrite({
      // 確認期間若已有任務開始、清單過期或套件被停用，就不送出。
      recheck: () => {
        const current = model.normalizeSuites(store.suites.data).find((s) => s.id === suiteId);
        if (!current) return ['確認期間此套件已不在伺服器清單中，未送出。'];
        const ctx = runContext(monoNow());
        return model.startBlockers(current, { runsFreshness: ctx.runsFreshness, activeEntry: ctx.activeEntry });
      },
      key: null,
      busyKey: 'start',
      resultEl: els.runResult,
      title: '確認執行固定驗收任務',
      lines: [
        `套件：${suite.id}`,
        `範圍：${SUITE_SCOPES[suite.id]}`,
        '只執行伺服器預先登記的固定任務，不接受任何命令、參數、路徑或 URL。',
        '一次只能執行一個任務；取消不會撤銷已完成的測試操作。',
        'runner 只執行已配置的套件，不代表任意多站故障矩陣。',
        'COMMAND_SUCCEEDED 只代表 Maven 退出碼 0，不代表遠端驗收通過；不會進行部署。',
      ],
      confirmLabel: `確認執行 ${suite.id}`,
      buildBody: () => undefined,
      send: () => legacy.startTestRun(suite.id, signalOpts()),
      onSuccess: (run) => {
        rememberRun(run);
        return { tone: 'teal', title: `已送出任務 ${shortId(run?.id)}，狀態 ${run?.state ?? '未提供'}`, lines: ['請以目前任務與任務紀錄追蹤後續狀態。'] };
      },
    });
  }

  function onCancelRun() {
    const id = els.runCancel.dataset.runId;
    if (!isUuid(id)) return;
    runWrite({
      recheck: () => {
        const ctx = runContext(monoNow());
        return ctx.activeEntry && ctx.activeEntry.run.id === id ? [] : ['確認期間此任務已不是目前排隊或執行中的任務，未送出。'];
      },
      key: null,
      busyKey: 'cancel',
      resultEl: els.runResult,
      title: '確認取消任務',
      lines: [`任務：${id}`, '取消不會撤銷已完成的測試操作，需另行檢查資源清理。'],
      confirmLabel: '確認取消任務',
      buildBody: () => undefined,
      send: () => legacy.cancelTestRun(id, signalOpts()),
      onSuccess: (run) => {
        rememberRun(run);
        return { tone: 'neutral', title: `取消請求已回應，任務狀態 ${run?.state ?? '未提供'}`, lines: ['取消不會撤銷已完成的測試操作。'] };
      },
    });
  }

  async function onRunLookup(event) {
    event.preventDefault();
    const id = els.runLookupId.value.trim().toLowerCase();
    if (!isUuid(id)) {
      view.renderMessage(els.runResult, { tone: 'red', title: '任務 ID 必須是 UUID 格式。', lines: [] });
      return;
    }
    if (busy.has('lookup') || !active) return;
    const gen = generation;
    busy.add('lookup');
    renderRuns(monoNow());
    try {
      const run = await legacy.getTestRun(id, signalOpts());
      if (gen !== generation) return;
      if (!model.normalizeRun(run)) throw Object.assign(new Error('protocol'), { kind: 'protocol', serverMessage: '任務資料缺少有效 id' });
      rememberRun(run);
      view.renderMessage(els.runResult, { tone: 'neutral', title: `已讀取任務 ${shortId(run.id)}：${run.state ?? '未提供'}`, lines: [] });
    } catch (err) {
      if (gen !== generation) return;
      if (isAuthFailure(err)) {
        onAuthFailure(err);
        return;
      }
      view.renderMessage(els.runResult, { tone: 'red', title: err?.status === 404 ? '找不到此任務。' : '查詢失敗', lines: err?.status === 404 ? [] : [describeError(err)] });
    } finally {
      if (gen === generation) {
        busy.delete('lookup');
        renderAll();
      }
    }
  }

  // ---------- 表單同步 ----------

  function pendingNote(key) {
    const intent = key ? intents.peek(key) : null;
    return intent ? [`上次送出的結果未知：再次送出會以相同 requestId ${intent.id} 與原請求內容重送。`] : [];
  }

  function memberKey(action, member) {
    return member ? `member:${action}:${member.siteId}:${member.instanceId ?? ''}` : null;
  }

  function syncMemberForm(d) {
    const action = radioValue(els.memberForm, 'cl-member-action', 'JOIN');
    view.syncSelect(
      els.memberSite,
      d.views.map((v) => ({ value: v.siteId, label: `${v.siteId} · ${v.state ?? '未知'}${v.mode === 'CAMPUS' ? ' · 權威站' : ''}` })),
      '（尚無成員名單）',
    );
    const member = d.views.find((v) => v.siteId === els.memberSite.value) ?? null;
    const check = cm.memberActionCheck(action, member, d.ctx);
    const warnings = [...check.warnings, ...pendingNote(memberKey(action, member))];
    if (action === 'RETIRE' && member) {
      if (!member.observation?.drain) warnings.push('排空數量未知（沒有可信觀測）；後端會在最終凍結前重新檢查。');
      else {
        const pending = cm.drainPending(member.observation.drain);
        warnings.push(pending.length ? `目前仍需排空：${pending.join('、')}；後端會拒絕最終凍結。` : '目前觀測的排空數量皆為 0；後端仍會重新檢查。');
      }
    }
    const memberBlocking = [...leaderBlocking(d), ...check.blocking];
    view.renderList(els.memberBlocking, memberBlocking, 'red');
    view.renderList(els.memberWarnings, warnings, 'amber');
    const epochText = d.ctx.epoch === null ? '未知' : String(d.ctx.epoch);
    els.memberImpact.textContent = !member
      ? '請選擇站點。'
      : action === 'JOIN'
        ? `開始讓 ${member.siteId} 加入叢集（expectedEpoch ${epochText}）。會建立 durable 變更，所有參與者確認後才提交新 epoch。`
        : action === 'RETIRE'
          ? `讓 ${member.siteId} 進入退役（expectedEpoch ${epochText}）。先保留 DRAINING 意圖，排空後才凍結；退役保留全部歷史與身份。`
          : `取消 ${member.siteId} 的 REGISTERED 登記（expectedEpoch ${epochText}）。取消後為 RETIRED，身份仍保留。`;
    els.memberSubmit.disabled = busy.has('member') || memberBlocking.length > 0;
    els.memberSubmit.textContent = busy.has('member') ? '送出中…' : '檢查並確認…';
  }

  function syncRegisterForm(d) {
    const profiles = cm.normalizeProfiles(store.profiles.data);
    view.syncSelect(
      els.profileSelect,
      profiles.map((p) => ({ value: p.id, label: `${p.id} · ${p.siteId} · ${p.mode ?? '?'} · slot ${p.slot ?? '?'}` })),
      store.profiles.error ? '（inventory 讀取失敗）' : '（inventory 沒有 profile）',
    );
    const profile = profiles.find((p) => p.id === els.profileSelect.value) ?? null;
    const reg = cm.profileRegistration(profile, d.status?.members);
    const reasons = [...leaderBlocking(d), ...reg.reasons];
    if (!d.ctx.statusFresh) reasons.push('叢集狀態不是最新，無法取得可信的 expectedEpoch。');
    reasons.push(...pendingNote(profile ? `register:${profile.id}` : null));
    cv.renderProfileDetails(els.profileDetails, profile, reasons);
    els.registerSubmit.disabled = busy.has('register') || !profile || !reg.allowed || !d.ctx.statusFresh || !d.lead.writable;
    els.profilesReload.disabled = busy.has('register') || !d.lead.writable;
  }

  function syncResumeForm(d) {
    const open = cm.openChanges(d.status);
    view.syncSelect(
      els.resumeChange,
      open.map((c) => ({ value: c.id, label: `${c.kind ?? '?'} ${c.siteId ?? ''} · ${c.state ?? '?'} · ${shortId(c.id)}` })),
      '（沒有未完成的變更）',
    );
    els.resumeSubmit.disabled = busy.has('resume') || open.length === 0 || !d.lead.writable;
    view.renderList(els.resumeHint, leaderBlocking(d), 'amber');
  }

  function syncGcForms(d) {
    view.syncSelect(
      els.gcCandidate,
      d.candidates.map((c) => ({ value: cm.candidateKey(c), label: `${c.state ?? '?'} · ${shortId(c.fileId, 13)} · r${c.revision}` })),
      '（沒有已發現的候選）',
    );
    const candidate = d.candidates.find((c) => cm.candidateKey(c) === els.gcCandidate.value) ?? null;
    const reasons = [...leaderBlocking(d), ...cm.candidateCheck(candidate, { statusFresh: d.ctx.statusFresh, epoch: d.ctx.epoch, openGc: d.openGc })];
    const notes = [...reasons, ...pendingNote(candidate ? `gc:${cm.candidateKey(candidate)}` : null)];
    view.renderList(els.gcBeginReasons, notes, 'amber');
    els.gcBeginSubmit.disabled = busy.has('gc') || reasons.length > 0;
    view.syncSelect(
      els.gcResumeSelect,
      d.openGc.map((g) => ({ value: g.decision.operationId, label: `${g.state ?? '?'} · ${shortId(g.decision.fileId, 13)} · r${g.decision.revision ?? '?'}` })),
      '（沒有未完成的決策）',
    );
    els.gcResumeSubmit.disabled = busy.has('gc') || d.openGc.length === 0 || !d.lead.writable;
    view.renderList(els.gcResumeHint, d.openGc.length ? leaderBlocking(d) : [], 'amber');
    els.gcPendingOnly.checked = store.gcPendingOnly;
  }

  function selectedPlanSites() {
    return [...els.planSites.querySelectorAll('input[type="checkbox"]:checked')].map((i) => i.value);
  }

  function syncPlanForm(d) {
    const options = cm.releaseOptions(d.releases);
    const valid = options.filter((o) => o.valid);
    view.syncSelect(
      els.releaseSelect,
      valid.map((o) => ({ value: o.value, label: o.label })),
      store.releases.error ? '（制品清單讀取失敗）' : '（伺服器未登記可用制品）',
    );
    cv.renderReleaseInfo(els.releaseDetails, d.releases.find((r) => r.id === els.releaseSelect.value) ?? null);
    cv.syncCheckboxes(els.planSites, cm.previewSiteOptions(d.views), 'cl-plan-site', '沒有 ACTIVE 成員可預覽。');
    const sites = selectedPlanSites();
    const reasons = [...leaderBlocking(d)];
    if (!d.ctx.statusFresh) reasons.push('叢集狀態不是最新，無法取得可信的 expectedEpoch。');
    if (d.ctx.epoch !== null && d.ctx.epoch < 1) reasons.push('目前 epoch 小於 1，不能建立叢集預覽。');
    if (!valid.length) reasons.push('沒有可用的制品。');
    if (options.length > valid.length) reasons.push(`${options.length - valid.length} 個制品 ID 不符合格式，已排除（不改寫 ID）。`);
    if (!sites.length) reasons.push('請至少選擇一個 ACTIVE 站點。');
    if (sites.length > CLUSTER_MAX_PREVIEW_SITES) reasons.push(`最多 ${CLUSTER_MAX_PREVIEW_SITES} 個站點。`);
    const blocking = reasons.filter((r) => !r.includes('已排除'));
    view.renderList(els.planReasons, reasons, 'amber');
    els.planCreate.disabled = busy.has('plan') || blocking.length > 0;
    els.planReload.disabled = busy.has('plan') || !store.preview;
  }

  function syncFaultForm(d) {
    const action = radioValue(els.faultForm, 'cl-fault-action', 'BLOCK');
    view.syncSelect(els.faultSite, cm.faultSiteOptions(d.views, action), action === 'BLOCK' ? '（沒有 ACTIVE 成員）' : '（尚無成員名單）');
    const target = d.views.find((v) => v.siteId === els.faultSite.value) ?? null;
    view.syncSelect(
      els.faultChannel,
      cm.faultChannelOptions(target, d.catalog, action),
      action === 'BLOCK' ? '（沒有可信的線上通道）' : '（觀測與離線目錄皆無通道）',
    );
    const channel = els.faultChannel.value;
    const secondsRaw = action === 'RESTORE' ? String(RESTORE_SECONDS) : els.faultSeconds.value;
    const validation = validateClusterFault({ site: target?.siteId, channel, action, seconds: secondsRaw });
    const freshnessInfo = cm.blockFreshness(store.observations, d.now);
    const check = cm.clusterFaultPrecheck({
      view: target,
      catalog: d.catalog,
      action,
      channel,
      seconds: validation.ok ? validation.value.seconds : null,
      freshness: freshnessInfo,
    });
    const limit = cm.clusterBlockLimit(target, channel);
    const isBlock = action === 'BLOCK';
    els.faultSeconds.disabled = !isBlock;
    els.faultSeconds.max = String(isBlock && limit !== null ? limit : FAULT_SECONDS_MAX);
    if (!isBlock) els.faultLimit.textContent = `RESTORE 不使用秒數控制恢復時間；依契約固定送出 seconds=${RESTORE_SECONDS}。`;
    else if (!channel) els.faultLimit.textContent = '本通道上限：未選擇通道。';
    else if (limit === null) els.faultLimit.textContent = '本通道上限：缺值（可信觀測未提供 maximumBlockSeconds），不能 BLOCK。';
    else els.faultLimit.textContent = `本通道上限：${limit} 秒（來自 ${Math.round((freshnessInfo.ageMs ?? 0) / 1000)} 秒前開始的觀測）。`;
    els.faultLimit.className = `note ${isBlock && (limit === null || !channel) ? 'note-amber' : 'note-neutral'}`;
    const blocking = [...leaderBlocking(d), ...check.blocking, ...(validation.ok ? [] : validation.errors.filter((e) => !e.includes('通道') && !e.includes('站點')))];
    view.renderList(els.faultBlocking, [...new Set(blocking)], 'red');
    view.renderList(els.faultWarnings, check.warnings, 'amber');
    const s = validation.ok ? validation.value.seconds : '?';
    els.faultImpact.textContent = !target
      ? '請選擇站點。'
      : isBlock
        ? `影響：${target.siteId} 的 ${channel || '（未選）'} 通道將關閉既有測試連線並拒絕新連線，${s} 秒後自動放行；期間跨站同步與協調可能延遲。`
        : `影響：恢復 ${target.siteId} 的 ${channel || '（未選）'} 固定代理通道放行。不恢復成員資格，也不代表節點在線或 worker 已接入。`;
    els.faultSubmit.disabled = busy.has('fault') || blocking.length > 0;
    els.faultSubmit.textContent = busy.has('fault') ? '送出中…' : '檢查並確認…';
  }

  // ---------- 寫入 ----------

  // recheck({ replay }) 在使用者按下確認「之後」、送出「之前」以當下狀態重新檢查；
  // 回傳非空原因即不送出，重新讀取後要求重新確認。
  // leaderGated：控制寫入（成員、GC、故障注入、設定重載、部署預覽）只在此程序持有控制租約時送出；
  // 開啟對話框時記下 generation，確認後重新核對領導權與 generation，改變就不送出。
  async function runWrite({ key, busyKey, resultEl, title, lines, confirmLabel, buildBody, send, onSuccess, recheck, leaderGated = false }) {
    if (busy.has(busyKey) || !active || !session.isActive()) return;
    let capturedLead = null;
    if (leaderGated) {
      const leadNow = derive().lead;
      if (!leadNow.writable) {
        view.renderMessage(resultEl, { tone: 'amber', title: '控制寫入已停用', lines: leadNow.reasons });
        return;
      }
      capturedLead = { generation: leadNow.lead?.generation ?? null };
    }
    const pending = key ? intents.peek(key) : null;
    const dialogLines = [...lines];
    if (leaderGated) {
      dialogLines.push(`控制領導：此程序持有控制租約${capturedLead.generation !== null ? `（generation ${capturedLead.generation}）` : ''}；確認後會重新核對。`);
    }
    if (pending) {
      dialogLines.push(`上次送出的結果未知：將以相同 requestId ${pending.id} 與原請求內容重送（原 expectedEpoch ${pending.body.expectedEpoch ?? '—'}），避免重複執行。`);
    }
    const ok = await confirmDialog({ title, lines: dialogLines, confirmLabel });
    if (!ok || !active || !session.isActive()) return;
    const gen = generation;
    const replay = key ? intents.peek(key) !== null : false;
    // 先重查領導權（含結果未知的重送：備機不得送出），再重查各操作自身條件與 epoch。
    const stale = [
      ...(leaderGated ? cm.recheckLeadership({ captured: capturedLead, view: derive().lead }) : []),
      ...(recheck ? recheck({ replay }) : []),
    ];
    if (stale.length) {
      view.renderMessage(resultEl, {
        tone: 'amber',
        title: '確認後條件已改變，未送出',
        lines: [...stale, '已重新讀取狀態；請檢視最新資料後重新確認。確認不會沿用到新的 epoch 或新的目標狀態。'],
      });
      refreshShort();
      return;
    }
    const intent = key ? intents.acquire(key, buildBody) : { id: null, body: buildBody(null), replay: false };
    busy.add(busyKey);
    renderAll();
    try {
      const res = await send(intent.body);
      if (gen !== generation) return;
      if (key) intents.release(key);
      view.renderMessage(resultEl, onSuccess(res, intent));
    } catch (err) {
      if (gen !== generation) return;
      if (isAuthFailure(err)) {
        onAuthFailure(err);
        return;
      }
      const outcome = cm.writeOutcome(err);
      const keepIntent = outcome === 'UNKNOWN' || outcome === 'LEASE_NOT_HELD';
      if (outcome === 'LEASE_NOT_HELD') store.leadershipInvalidatedAt = monoNow();
      if (key && !keepIntent) intents.release(key);
      view.renderMessage(resultEl, cm.writeFailureMessage(err, outcome, { requestId: key && keepIntent ? intent.id : null }));
    } finally {
      if (gen === generation) {
        busy.delete(busyKey);
        refreshShort();
      }
    }
  }

  function onMemberSubmit(event) {
    event.preventDefault();
    const d = derive();
    const action = radioValue(els.memberForm, 'cl-member-action', 'JOIN');
    const member = d.views.find((v) => v.siteId === els.memberSite.value) ?? null;
    const check = cm.memberActionCheck(action, member, d.ctx);
    if (check.blocking.length) {
      view.renderMessage(els.memberResult, { tone: 'red', title: '未送出：前置檢查未通過', lines: check.blocking });
      return;
    }
    const names = { JOIN: '加入', RETIRE: '退役', CANCEL: '取消登記' };
    const epoch = d.ctx.epoch;
    const captured = { epoch, siteId: member.siteId, instanceId: member.instanceId, state: member.state };
    runWrite({
      leaderGated: true,
      recheck: ({ replay }) => {
        if (replay) return [];
        const now = derive();
        const current = now.views.find((v) => v.siteId === captured.siteId) ?? null;
        return [
          ...cm.recheckEpochWrite({ captured, ctx: now.ctx, replay }),
          ...cm.recheckMember({ captured, member: current }),
          ...cm.memberActionCheck(action, current, now.ctx).blocking,
        ];
      },
      key: memberKey(action, member),
      busyKey: 'member',
      resultEl: els.memberResult,
      title: `確認${names[action]}：${member.siteId}`,
      lines: [
        `站點：${member.siteId}（${member.mode ?? '?'} · slot ${member.slot ?? '?'} · ${member.state}）`,
        `instanceId：${member.instanceId ?? '未提供'}`,
        `expectedEpoch：${epoch}`,
        ...check.warnings,
        '後端條件（追平、排空、GC 決策等）不可由中控豁免。',
      ],
      confirmLabel: `確認${names[action]} ${member.siteId}`,
      buildBody: (requestId) => ({ requestId, expectedEpoch: epoch }),
      send: (body) => {
        const opts = signalOpts();
        if (action === 'JOIN') return client.joinSite(member.siteId, body, opts);
        if (action === 'RETIRE') return client.retireSite(member.siteId, body, opts);
        return client.cancelSite(member.siteId, body, opts);
      },
      onSuccess: (res) => {
        if (action === 'CANCEL') {
          return { tone: 'teal', title: `${res?.siteId ?? member.siteId} 已為 ${res?.state ?? '（未提供）'}`, lines: [`epoch ${res?.epoch ?? '—'}；身份與 slot 保留，不可重用。`] };
        }
        const change = cm.normalizeChange(res);
        return {
          tone: 'teal',
          title: `已建立${names[action]}變更 ${change ? shortId(change.id) : ''}（${change?.state ?? '狀態未提供'}）`,
          lines: ['這是 durable 變更；請在進度區追蹤逐站確認，暫時失敗會保留 phase，可用「續行」接續。'],
        };
      },
    });
  }

  function onRegisterSubmit(event) {
    event.preventDefault();
    const d = derive();
    const profile = cm.normalizeProfiles(store.profiles.data).find((p) => p.id === els.profileSelect.value) ?? null;
    const reg = cm.profileRegistration(profile, d.status?.members);
    if (!profile || !reg.allowed || !d.ctx.statusFresh) {
      view.renderMessage(els.registerResult, { tone: 'red', title: '未送出', lines: reg.reasons.length ? reg.reasons : ['叢集狀態不是最新。'] });
      return;
    }
    const epoch = d.ctx.epoch;
    runWrite({
      leaderGated: true,
      recheck: ({ replay }) => {
        if (replay) return [];
        const now = derive();
        const current = cm.normalizeProfiles(store.profiles.data).find((p) => p.id === profile.id) ?? null;
        const regNow = cm.profileRegistration(current, now.status?.members);
        return [...cm.recheckEpochWrite({ captured: { epoch }, ctx: now.ctx, replay }), ...(regNow.allowed ? [] : regNow.reasons)];
      },
      key: `register:${profile.id}`,
      busyKey: 'register',
      resultEl: els.registerResult,
      title: `確認登記 ${profile.siteId}`,
      lines: [
        `profile：${profile.id}`,
        `站點：${profile.siteId}（${profile.mode ?? '?'} · slot ${profile.slot ?? '?'}）`,
        `expectedEpoch：${epoch}`,
        '登記後狀態為 REGISTERED；站點 ID、UUID 與 slot 永久保留。尚未加入前可以取消。',
      ],
      confirmLabel: `確認登記 ${profile.siteId}`,
      buildBody: (requestId) => ({ requestId, expectedEpoch: epoch, profileId: profile.id }),
      send: (body) => client.registerSite(body, signalOpts()),
      onSuccess: (res) => ({ tone: 'teal', title: `${res?.siteId ?? profile.siteId} 已登記（${res?.state ?? '狀態未提供'}）`, lines: [`epoch ${res?.epoch ?? '—'}`] }),
    });
  }

  function onProfilesReload() {
    runWrite({
      leaderGated: true,
      key: null,
      busyKey: 'register',
      resultEl: els.registerResult,
      title: '確認重讀 inventory',
      lines: ['後端會重讀固定的私有 profile inventory（不傳任何檔案路徑）。', '會拒絕替換既有非退役身份。'],
      confirmLabel: '確認重讀',
      buildBody: () => undefined,
      send: () => client.reloadProfiles(signalOpts()),
      onSuccess: (res) => {
        store.profiles = cm.monoSucceed(store.profiles, res, monoNow(), monoNow(), wallNow());
        return { tone: 'teal', title: `inventory 已重讀：${cm.normalizeProfiles(res).length} 個 profile`, lines: [] };
      },
    });
  }

  function onResumeSubmit(event) {
    event.preventDefault();
    const d = derive();
    const change = cm.openChanges(d.status).find((c) => c.id === els.resumeChange.value);
    if (!change) return;
    runWrite({
      leaderGated: true,
      recheck: () => (cm.openChanges(derive().status).some((c) => c.id === change.id) ? [] : ['確認期間此變更已不在未完成清單中（可能已 APPLIED），未送出。']),
      key: null,
      busyKey: 'resume',
      resultEl: els.resumeResult,
      title: '確認續行成員變更',
      lines: [
        `變更：${change.kind ?? '?'} ${change.siteId ?? ''}（${change.id}）`,
        `目前階段：${change.state ?? '未知'}`,
        '後端會同步嘗試有界進度；409／503 會保留 durable phase，worker 也會自行接續。',
      ],
      confirmLabel: '確認續行',
      buildBody: () => undefined,
      send: () => client.resumeChange(change.id, signalOpts()),
      onSuccess: (res) => {
        const c = cm.normalizeChange(res);
        return { tone: 'teal', title: `續行已回應：${c?.state ?? '狀態未提供'}`, lines: [] };
      },
    });
  }

  function onFaultSubmit(event) {
    event.preventDefault();
    const d = derive();
    const action = radioValue(els.faultForm, 'cl-fault-action', 'BLOCK');
    const target = d.views.find((v) => v.siteId === els.faultSite.value) ?? null;
    const secondsRaw = action === 'RESTORE' ? String(RESTORE_SECONDS) : els.faultSeconds.value;
    const validation = validateClusterFault({ site: target?.siteId, channel: els.faultChannel.value, action, seconds: secondsRaw });
    if (!validation.ok) {
      view.renderMessage(els.faultResult, { tone: 'red', title: '未送出：輸入不符合契約', lines: validation.errors });
      return;
    }
    const { site, channel, seconds } = validation.value;
    const freshnessInfo = cm.blockFreshness(store.observations, d.now);
    const check = cm.clusterFaultPrecheck({ view: target, catalog: d.catalog, action, channel, seconds, freshness: freshnessInfo });
    if (check.blocking.length) {
      view.renderMessage(els.faultResult, { tone: 'red', title: '未送出：前置檢查未通過', lines: check.blocking });
      return;
    }
    const limit = cm.clusterBlockLimit(target, channel);
    const captured = { siteId: site, instanceId: target.instanceId, state: target.state, action, channel, seconds, limit };
    runWrite({
      leaderGated: true,
      // BLOCK：重新要求 30 秒內開始的成功觀測與相同身份／狀態／上限；RESTORE 不依賴線上觀測。
      recheck: () => {
        const now = derive();
        const current = now.views.find((v) => v.siteId === captured.siteId) ?? null;
        return cm.recheckFault({ captured, view: current, catalog: now.catalog, freshness: cm.blockFreshness(store.observations, now.now) });
      },
      key: null,
      busyKey: 'fault',
      resultEl: els.faultResult,
      title: action === 'BLOCK' ? '確認阻斷通道' : '確認恢復通道',
      lines:
        action === 'BLOCK'
          ? [
              `站點：${site}（${target.state}）`,
              `通道：${channel}`,
              `動作：BLOCK，持續 ${seconds} 秒（到期自動放行）`,
              `此通道節點上限：${limit} 秒；觀測自請求開始 ${Math.round((freshnessInfo.ageMs ?? 0) / 1000)} 秒`,
              '影響：關閉此通道既有測試連線並拒絕新連線；跨站同步與協調在期間可能延遲。',
              ...check.warnings,
            ]
          : [`站點：${site}（${target.state}）`, `通道：${channel}`, `動作：RESTORE（依契約送出 seconds=${seconds}，後端不以此控制時長）`, ...check.warnings],
      confirmLabel: action === 'BLOCK' ? `確認 BLOCK ${channel} ${seconds} 秒` : `確認 RESTORE ${channel}`,
      buildBody: () => ({ channel, action, seconds }),
      send: (body) => client.postFault(site, body, signalOpts()),
      onSuccess: (res) => {
        const fault = res && typeof res.fault === 'object' && res.fault ? res.fault : {};
        return {
          tone: 'teal',
          title: `伺服器已接受 ${action}（${res?.siteId ?? site}）`,
          lines: [
            `回報通道狀態：${fault.state ?? '（未提供）'}${fault.expiresAt ? `，到期 ${formatTime(fault.expiresAt)}` : ''}`,
            `routingVerified=${String(fault.routingVerified)}：不代表 worker 流量已接入代理。`,
            '正在重新讀取狀態；觀測會在下一輪更新。',
          ],
        };
      },
    });
  }

  function onGcBegin(event) {
    event.preventDefault();
    const d = derive();
    const candidate = d.candidates.find((c) => cm.candidateKey(c) === els.gcCandidate.value) ?? null;
    const reasons = cm.candidateCheck(candidate, { statusFresh: d.ctx.statusFresh, epoch: d.ctx.epoch, openGc: d.openGc });
    if (reasons.length) {
      view.renderMessage(els.gcResult, { tone: 'red', title: '未送出', lines: reasons });
      return;
    }
    const epoch = d.ctx.epoch;
    runWrite({
      leaderGated: true,
      recheck: ({ replay }) => {
        if (replay) return [];
        const now = derive();
        const current = now.candidates.find((c) => cm.candidateKey(c) === cm.candidateKey(candidate)) ?? null;
        return [
          ...cm.recheckEpochWrite({ captured: { epoch }, ctx: now.ctx, replay }),
          ...(current ? cm.candidateCheck(current, { statusFresh: now.ctx.statusFresh, epoch: now.ctx.epoch, openGc: now.openGc }) : ['確認期間此候選已不在清單中，未送出。']),
        ];
      },
      key: `gc:${cm.candidateKey(candidate)}`,
      busyKey: 'gc',
      resultEl: els.gcResult,
      title: '確認發起多方 GC',
      lines: [
        `fileId：${candidate.fileId}`,
        `revision：${candidate.revision}`,
        `expectedEpoch：${epoch}`,
        '後端會固定參與者、SHA-256 與大小，並在每站重新檢查保留期、引用與柵欄；候選狀態不保證仍可刪除。',
        '全部 PREPARED 才提交，全部 DELETED 才完成；缺席或斷線永遠不是確認。不可指定 key、bucket 或豁免條件。',
      ],
      confirmLabel: '確認發起 GC',
      buildBody: (operationId) => ({ operationId, fileId: candidate.fileId, revision: candidate.revision, expectedEpoch: epoch }),
      send: (body) => client.beginGc(body, signalOpts()),
      onSuccess: (res) => ({
        tone: 'teal',
        title: `已建立 GC 決策 ${shortId(res?.operationId)}`,
        lines: [`參與者 ${Array.isArray(res?.participants) ? res.participants.length : '—'} 站；請在決策進度追蹤逐站確認。`],
      }),
    });
  }

  function onGcResume(event) {
    event.preventDefault();
    const d = derive();
    const g = d.openGc.find((x) => x.decision.operationId === els.gcResumeSelect.value);
    if (!g) return;
    runWrite({
      leaderGated: true,
      recheck: () => (derive().openGc.some((x) => x.decision.operationId === g.decision.operationId) ? [] : ['確認期間此決策已不在未完成清單中，未送出。']),
      key: null,
      busyKey: 'gc',
      resultEl: els.gcResult,
      title: '確認續行 GC 決策',
      lines: [`操作：${g.decision.operationId}`, `目前狀態：${g.state ?? '未知'}`, '後端會收集實際準備／刪除證據；瀏覽器不提交 vote。'],
      confirmLabel: '確認續行',
      buildBody: () => undefined,
      send: () => client.resumeGc(g.decision.operationId, signalOpts()),
      onSuccess: (res) => {
        const s = cm.normalizeGcSnapshot(res);
        return { tone: 'teal', title: `續行已回應：${s?.state ?? '狀態未提供'}`, lines: [] };
      },
    });
  }

  function onPlanCreate(event) {
    event.preventDefault();
    const d = derive();
    const releaseId = els.releaseSelect.value;
    const sites = selectedPlanSites();
    if (!d.ctx.statusFresh || !releaseId || !sites.length) {
      view.renderMessage(els.planResult, { tone: 'red', title: '未送出', lines: ['請確認叢集狀態即時、已選擇制品與站點。'] });
      return;
    }
    const epoch = d.ctx.epoch;
    const capturedSites = sites.map((s) => ({ siteId: s, instanceId: d.views.find((v) => v.siteId === s)?.instanceId ?? null }));
    runWrite({
      leaderGated: true,
      recheck: ({ replay }) => {
        if (replay) return [];
        const now = derive();
        return [...cm.recheckEpochWrite({ captured: { epoch }, ctx: now.ctx, replay }), ...cm.recheckPreviewSites({ captured: { sites: capturedSites }, views: now.views })];
      },
      key: `preview:${releaseId}:${[...sites].sort().join(',')}`,
      busyKey: 'plan',
      resultEl: els.planResult,
      title: '確認建立部署預覽',
      lines: [`制品：${releaseId}`, `站點：${sites.join('、')}`, `expectedEpoch：${epoch}`, ds.previewCreateExecutionLine(store.capabilities.data, { error: store.capabilities.error })],
      confirmLabel: '確認建立預覽',
      buildBody: (requestId) => ({ requestId, expectedEpoch: epoch, releaseId, sites }),
      send: (body) => client.createPreview(body, signalOpts()),
      onSuccess: (res) => {
        store.preview = cm.normalizePreview(res);
        store.previewError = store.preview ? null : { kind: 'protocol', serverMessage: '預覽回應不符合契約' };
        return { tone: 'teal', title: `已建立預覽 ${shortId(store.preview?.id)}`, lines: ['預覽綁定目前 epoch；成員變更後即過期。',
          ds.previewResultExecutionLine(store.capabilities.data, store.preview, { error: store.capabilities.error })] };
      },
    });
  }

  async function onPlanReload() {
    const id = store.preview?.id;
    if (!id || busy.has('plan')) return;
    const gen = generation;
    busy.add('plan');
    renderAll();
    try {
      const res = await client.getPreview(id, signalOpts());
      if (gen !== generation) return;
      const pv = cm.normalizePreview(res);
      if (pv) {
        store.preview = pv;
        store.previewError = null;
      }
    } catch (err) {
      if (gen !== generation) return;
      if (isAuthFailure(err)) {
        onAuthFailure(err);
        return;
      }
      store.previewError = err;
    } finally {
      if (gen === generation) {
        busy.delete('plan');
        renderAll();
      }
    }
  }

  // ---------- 事件 ----------

  const resyncForms = () => {
    if (active) renderAll();
  };
  for (const el of [els.memberSite, els.profileSelect, els.resumeChange, els.gcCandidate, els.gcResumeSelect, els.releaseSelect]) {
    el.addEventListener('change', resyncForms);
  }
  els.planSites.addEventListener('change', resyncForms);
  for (const r of els.memberForm.querySelectorAll('input[name="cl-member-action"]')) r.addEventListener('change', resyncForms);
  const resyncFault = () => {
    if (active) syncFaultForm(derive());
  };
  els.faultSite.addEventListener('change', resyncFault);
  els.faultChannel.addEventListener('change', resyncFault);
  els.faultSeconds.addEventListener('input', resyncFault);
  for (const r of els.faultForm.querySelectorAll('input[name="cl-fault-action"]')) r.addEventListener('change', resyncFault);

  els.memberForm.addEventListener('submit', onMemberSubmit);
  els.registerForm.addEventListener('submit', onRegisterSubmit);
  els.profilesReload.addEventListener('click', onProfilesReload);
  els.members.addEventListener('click', onMembersClick);
  els.resumeForm.addEventListener('submit', onResumeSubmit);
  els.faultForm.addEventListener('submit', onFaultSubmit);
  els.gcBeginForm.addEventListener('submit', onGcBegin);
  els.gcResumeForm.addEventListener('submit', onGcResume);
  els.gcPendingOnly.addEventListener('change', () => {
    store.gcPendingOnly = els.gcPendingOnly.checked;
    refreshShort();
  });
  els.runCancel.addEventListener('click', onCancelRun);
  els.runLookupForm.addEventListener('submit', onRunLookup);
  els.planForm.addEventListener('submit', onPlanCreate);
  els.planReload.addEventListener('click', onPlanReload);
  els.auditLimit.addEventListener('change', () => {
    const n = Number(els.auditLimit.value);
    store.auditLimit = CLUSTER_AUDIT_LIMIT_CHOICES.includes(n) ? n : CLUSTER_AUDIT_LIMIT_DEFAULT;
    refreshShort();
  });

  // ---------- 生命週期 ----------

  function clearDom() {
    for (const el of [
      els.overview, els.members, els.changesPending, els.changesRecent, els.gcCandidates, els.gcList,
      els.planView, els.lockReasons, els.audit, els.fileAudit, els.capSummary, els.capabilities,
      els.profileDetails, els.releaseDetails, els.planSites,
      els.suiteStatus, els.activeRun, els.runHistory, els.leadership,
    ]) el.replaceChildren();
    els.leadership.className = 'leader-bar';
    els.epoch.textContent = '—';
    els.clusterId.textContent = '—';
    onSummary?.(null);
    view.resetSuites(els.suiteList);
    els.runLookupId.value = '';
    els.runCancel.disabled = true;
    els.runCancel.dataset.runId = '';
    for (const select of [els.memberSite, els.profileSelect, els.resumeChange, els.faultSite, els.faultChannel, els.gcCandidate, els.gcResumeSelect, els.releaseSelect]) {
      select.replaceChildren();
      delete select.dataset.signature;
    }
    delete els.planSites.dataset.signature;
    for (const el of [els.notice, els.memberResult, els.registerResult, els.resumeResult, els.faultResult, els.gcResult, els.planResult, els.runResult]) {
      view.renderMessage(el, null);
    }
    for (const el of [els.memberBlocking, els.memberWarnings, els.faultBlocking, els.faultWarnings, els.gcBeginReasons, els.planReasons, els.resumeHint, els.gcResumeHint]) {
      view.renderList(el, []);
    }
    for (const el of [els.memberImpact, els.faultImpact, els.faultLimit]) el.textContent = '';
    els.banner.hidden = true;
    els.banner.textContent = '';
    els.auditLimit.value = String(CLUSTER_AUDIT_LIMIT_DEFAULT);
    els.gcPendingOnly.checked = true;
  }

  // 登入時驗證中控入口：401／403／404 拋出（404 = 入口不是多站中控或功能未開啟，不自動降級）；
  // 503、逾時、網路錯誤仍進入畫面並標示未知。
  async function verifyEntry() {
    const started = monoNow();
    try {
      const caps = await client.getCapabilities();
      return cm.monoSucceed(cm.emptyMono(), caps, started, monoNow(), wallNow());
    } catch (err) {
      if (cm.isEntryRejection(err)) throw err;
      return cm.monoFail(cm.emptyMono(), err);
    }
  }

  function start(capabilitiesRes) {
    stop();
    generation += 1;
    active = true;
    controller = typeof AbortController === 'function' ? new AbortController() : null;
    store = initialStore();
    if (capabilitiesRes) store.capabilities = capabilitiesRes;
    els.view.hidden = false;
    renderAll();
    tickTimer = setInterval(() => {
      if (!active) return;
      const now = monoNow();
      if (freshnessSignature(now) !== lastSignature) renderAll();
      else renderLive(now);
    }, 1000);
    refreshShort({ includeStatic: true });
    refreshObservations();
  }

  function stop() {
    deploymentUi.reset();
    handoffUi.reset();
    generation += 1;
    active = false;
    clearTimeout(shortTimer);
    clearTimeout(obsTimer);
    clearInterval(tickTimer);
    shortTimer = null;
    obsTimer = null;
    tickTimer = null;
    // 登出時中止所有進行中的請求（含長時間的觀測請求）。
    if (controller) controller.abort();
    controller = null;
    shortInflight = null;
    store = initialStore();
    busy.clear();
    intents.clear();
    healthGen += 1;
    stopHealthWatch();
    healthMessages.clear();
    lastSignature = '';
    els.view.hidden = true;
    clearDom();
  }

  function refreshNow() {
    if (!active) return;
    refreshShort({ includeStatic: true });
    if (store.observations.inFlightSince === null) refreshObservations();
    else notify('neutral', '觀測請求仍在進行中，完成後會立即更新；不會重疊發出第二個請求。');
  }

  // ---------- 健康檢測 ----------

  function stopHealthWatch() {
    clearTimeout(healthWatchTimer);
    healthWatchTimer = null;
    healthWatchUntil = 0;
  }

  // 只在站點頁可見時加快觀測（每 2 秒，最多 2 分鐘）；沒有進行中的檢測或離開頁面就停止。
  function startHealthWatch() {
    if (!sitesVisible || !active) return;
    healthWatchUntil = wallNow() + hs.HEALTH_FAST_POLL_LIMIT_MS;
    scheduleHealthWatch(0);
  }

  function scheduleHealthWatch(delay = hs.HEALTH_FAST_POLL_MS) {
    clearTimeout(healthWatchTimer);
    const g = generation;
    const hg = healthGen;
    healthWatchTimer = setTimeout(() => {
      healthWatchTimer = null;
      if (g !== generation || hg !== healthGen || !active || !sitesVisible || !session.isActive()) return;
      if (wallNow() > healthWatchUntil) return;
      if (store.observations.inFlightSince === null) refreshObservations();
      const running = derive().views.some((v) => v.observation?.reachable && hs.healthModel(v.observation.control, { now: wallNow(), current: v.observed === 'REACHABLE' }).anyRunning);
      if (running || delay === 0) scheduleHealthWatch();
    }, delay);
  }

  function healthMessage(site, msg) {
    healthMessages.set(site, msg);
    renderAll();
  }

  function healthFailure(err, { write }) {
    if (write) {
      const outcome = cm.writeOutcome(err);
      if (outcome === 'LEASE_NOT_HELD') store.leadershipInvalidatedAt = monoNow();
      const base = cm.writeFailureMessage(err, outcome, {});
      const text = hs.healthCodeText(err?.code);
      return { outcome, msg: { ...base, lines: [...(text ? [text] : []), ...(base.lines ?? [])] } };
    }
    const unknown = err?.outcomeUnknown || err?.status >= 500;
    return {
      outcome: unknown ? 'UNKNOWN' : 'REJECTED',
      msg: unknown
        ? { tone: 'amber', title: '檢測請求結果未知', lines: ['立即檢測是只讀的，可以再按一次；結果以觀測為準。'] }
        : { tone: 'red', title: '檢測請求被拒絕', lines: [hs.healthCodeText(err?.code) ?? '未定義的錯誤碼', err?.code ? `代碼：${err.code}` : `HTTP ${err?.status ?? '?'}`] },
    };
  }

  function probeLines(res) {
    const lines = [];
    if (res && typeof res === 'object') {
      if (typeof res.state === 'string') lines.push(`回報狀態：${res.state}${typeof res.phase === 'string' ? ` · ${res.phase}` : ''}`);
      if (typeof res.code === 'string') lines.push(`${hs.healthCodeText(res.code) ?? '未定義的錯誤碼'}（${res.code}）`);
    }
    lines.push('結果以後續觀測為準；送出成功不代表健康。');
    return lines;
  }

  // 立即檢測：只讀，主控與備機都可；不需確認。重複點擊由 busy 合併，後端也會合併進行中的檢測。
  async function onHealthCheck(site) {
    const key = `health:${site}`;
    if (busy.has(key) || !active || !session.isActive()) return;
    const g = generation;
    const hg = healthGen;
    busy.add(key);
    healthMessages.delete(site);
    renderAll();
    try {
      const res = await client.startHealthCheck(site, cm.randomUuid());
      if (g !== generation || hg !== healthGen) return;
      healthMessages.set(site, { tone: 'neutral', title: '已要求立即檢測（只讀）', lines: probeLines(res) });
      startHealthWatch();
    } catch (err) {
      if (g !== generation || hg !== healthGen) return;
      if (isAuthFailure(err)) { onAuthFailure(err); return; }
      healthMessages.set(site, healthFailure(err, { write: false }).msg);
    } finally {
      busy.delete(key);
      if (g === generation && hg === healthGen) renderAll();
    }
  }

  // 存儲讀寫驗證與重試清理：只限主中控；開啟確認時記下 generation，確認後重查領導權與存儲狀態。
  // 新驗證使用 intent（結果未知時以相同 requestId 重送，後端不重做）；重試清理沿用原驗證的 requestId。
  async function onStorageWrite(site, mode) {
    const busyKey = `${mode === 'cleanup' ? 'cleanup' : 'storage'}:${site}`;
    if (['health', 'storage', 'cleanup'].some((k) => busy.has(`${k}:${site}`)) || !active || !session.isActive()) return;
    const viewOf = () => {
      const d = derive();
      const v = d.views.find((x) => x.siteId === site) ?? null;
      const current = v?.observed === 'REACHABLE';
      const health = hs.healthModel(v?.observation?.reachable ? v.observation.control : null, { now: wallNow(), current });
      return { d, v, current, health };
    };
    const before = viewOf();
    const blockers = mode === 'cleanup'
      ? hs.cleanupRetryBlockers(before.health, { leadWritable: before.d.lead.writable, leadReasons: before.d.lead.reasons })
      : hs.storageWriteBlockers(before.health, { leadWritable: before.d.lead.writable, leadReasons: before.d.lead.reasons, current: before.current });
    if (blockers.length) { healthMessage(site, { tone: 'amber', title: '未送出', lines: blockers }); return; }
    const capturedLead = { generation: before.d.lead.lead?.generation ?? null };
    const cleanupId = before.health.write.requestId;
    const intentKey = `storage-check:${site}`;
    const pending = mode === 'write' ? intents.peek(intentKey) : null;
    const lines = mode === 'cleanup'
      ? [`站點：${site}`, '只清理上次未確認刪除的專用臨時物件，不再寫入。', `沿用原驗證 requestId：${cleanupId}`]
      : [`站點：${site}`, '在設定的存儲桶寫入一個專用臨時小物件，讀回比對後刪除；不讀寫業務資料。', '結果只代表這一次讀寫驗證，不等於之後的健康狀態。', '清理未確認時會顯示「清理待處理」，需由主中控重試清理。'];
    lines.push(`控制領導：此程序持有控制租約${capturedLead.generation !== null ? `（generation ${capturedLead.generation}）` : ''}；確認後會重新核對。`);
    if (pending) lines.push(`上次送出的結果未知：將以相同 requestId ${pending.id} 重送，後端不會重做。`);
    const ok = await confirmDialog({ title: mode === 'cleanup' ? '重試清理存儲探針' : '驗證存儲讀寫', lines, confirmLabel: mode === 'cleanup' ? '重試清理' : '開始驗證' });
    if (!ok || !active || !session.isActive()) return;
    const g = generation;
    const hg = healthGen;
    const after = viewOf();
    const stale = [
      ...cm.recheckLeadership({ captured: capturedLead, view: after.d.lead }),
      ...(mode === 'cleanup'
        // 領導權已由 recheckLeadership 核對，這裡只重查存儲狀態本身。
        ? hs.cleanupRetryBlockers(after.health, { leadWritable: true })
        : hs.storageWriteBlockers(after.health, { leadWritable: true, current: after.current })),
    ];
    if (mode === 'cleanup' && after.health.write.requestId !== cleanupId) stale.push('待清理的驗證已改變。');
    if (stale.length) {
      healthMessage(site, { tone: 'amber', title: '確認後條件已改變，未送出', lines: stale });
      refreshShort();
      return;
    }
    const intent = mode === 'cleanup' ? { id: cleanupId } : intents.acquire(intentKey, (id) => ({ requestId: id }));
    busy.add(busyKey);
    renderAll();
    try {
      const res = await client.startStorageCheck(site, intent.id);
      if (g !== generation || hg !== healthGen) return;
      if (mode === 'write') intents.release(intentKey);
      healthMessages.set(site, { tone: 'neutral', title: mode === 'cleanup' ? '已要求重試清理' : '已開始存儲讀寫驗證', lines: probeLines(res) });
      startHealthWatch();
    } catch (err) {
      if (g !== generation || hg !== healthGen) return;
      if (isAuthFailure(err)) { onAuthFailure(err); return; }
      const { outcome, msg } = healthFailure(err, { write: true });
      if (mode === 'write' && outcome !== 'UNKNOWN' && outcome !== 'LEASE_NOT_HELD') intents.release(intentKey);
      healthMessages.set(site, outcome === 'UNKNOWN' ? { ...msg, lines: [...(msg.lines ?? []), `requestId ${intent.id} 已保留；再次確認會以相同 requestId 送出，不會重做寫入。`] } : msg);
    } finally {
      busy.delete(busyKey);
      if (g === generation && hg === healthGen) { renderAll(); refreshShort(); }
    }
  }

  function onMembersClick(event) {
    const btn = event.target.closest?.('button[data-health-action]');
    if (!btn || btn.disabled) return;
    const site = btn.dataset.site;
    if (btn.dataset.healthAction === 'check') onHealthCheck(site);
    else if (btn.dataset.healthAction === 'storage') onStorageWrite(site, 'write');
    else if (btn.dataset.healthAction === 'cleanup') onStorageWrite(site, 'cleanup');
  }

  // 站點頁可見性：離開時取消加快的觀測並丟棄遲到回應（結果仍會由一般觀測更新）。
  function setSitesVisible(visible) {
    if (sitesVisible === visible) return;
    sitesVisible = visible;
    if (!visible) {
      healthGen += 1;
      stopHealthWatch();
      healthMessages.clear();
      for (const k of [...busy]) if (/^(health|storage|cleanup):/.test(k)) busy.delete(k);
    }
  }

  // 供其他頁（伺服器）判斷管理寫入：與本控制器的寫入閘門使用同一份租約檢視。
  function leadership() {
    if (!active) return { writable: false, reasons: ['多站中控尚未啟動，管理寫入已停用。'] };
    const lead = derive().lead;
    return { writable: lead.writable, reasons: lead.reasons };
  }

  return Object.freeze({ verifyEntry, start, stop, refreshNow, leadership, setSitesVisible, openDeployment: (id) => deploymentUi.lookup(id), isActive: () => active });
}
