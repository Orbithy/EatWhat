// 多站總覽：從真實讀取結果整理「待處理問題、主中控、目前部署、站點狀態」。純函式，可在 Node 測試。
// 沒有資料就呈現空狀態或「未知」，不補成正常；後端沒提供的清單（例如部署歷史）不假裝存在。

import { workerCodeNormal, workerSummary } from './cluster-state.js';
import { healthIssues, healthModel } from './health-state.js';
import { effectiveJobState } from './deployment-state.js';

const TONE_RANK = { red: 0, amber: 1, neutral: 2 };

const JOB_COPY = {
  QUEUED: ['amber', '排隊中'],
  RUNNING: ['amber', '部署中'],
  SUCCEEDED: ['teal', '已完成'],
  FAILED: ['red', '失敗'],
  PARTIAL_FAILURE: ['red', '部分失敗'],
  OUTCOME_UNKNOWN: ['red', '結果未知'],
};
const TARGET_COPY = {
  PREFLIGHT: ['amber', '前置檢查'], PREPARED: ['amber', '已準備'], DEPLOYING: ['amber', '更新中'],
  SUCCEEDED: ['teal', '已更新'], ROLLED_BACK: ['red', '已恢復舊版本'], ROLLBACK_FAILED: ['red', '恢復失敗'],
};
const MEMBER_COPY = { REGISTERED: '已登記・未加入', JOINING: '加入中', ACTIVE: '運作中', DRAINING: '排空中', RETIRED: '已退役' };
const OBSERVED_COPY = {
  REACHABLE: ['teal', '可達'], UNKNOWN: ['amber', '未知'], STALE: ['red', '觀測失效'],
  LOADING: ['neutral', '讀取中'], NOT_OBSERVED: ['neutral', '未觀測'],
};
const LEAD_COPY = {
  LEADER: ['teal', '本入口是主中控'], SINGLE: ['teal', '單程序控制模式'], STANDBY: ['amber', '本入口是備機（唯讀）'],
  EXPIRED: ['red', '控制租約已過期'], UNCLAIMED: ['amber', '控制租約尚未取得'], UNKNOWN: ['red', '主中控狀態未知'],
};

export function jobSummary(job) {
  if (!job || typeof job !== 'object' || typeof job.state !== 'string') return null;
  const state = effectiveJobState(job);
  const [tone, label] = JOB_COPY[state] ?? ['amber', state];
  return {
    id: typeof job.id === 'string' ? job.id : null,
    releaseId: typeof job.releaseId === 'string' ? job.releaseId : null,
    state,
    tone,
    label,
    reason: typeof job.reason === 'string' ? job.reason : null,
    targets: (Array.isArray(job.targets) ? job.targets : [])
      .filter((t) => t && typeof t.site === 'string')
      .map((t) => {
        const [tTone, tLabel] = TARGET_COPY[t.state] ?? ['amber', String(t.state ?? '未知')];
        return { site: t.site, state: t.state ?? null, tone: tTone, label: tLabel };
      }),
  };
}

export function homeModel({ statusFreshness, obsFreshness, status, views = [], lead, leadershipRaw, anomalies = [], openGcCount = 0, job = null, now = Date.now() }) {
  const issues = [];
  const add = (tone, title, detail, route) => issues.push({ tone, title, detail, route });

  if (statusFreshness === 'ERROR' || statusFreshness === 'STALE') {
    add('red', statusFreshness === 'ERROR' ? '叢集狀態讀取失敗' : '叢集狀態已過期', '畫面資料已失效，依賴最新狀態的寫入已停用；會持續重試。', 'sites');
  }
  if (obsFreshness === 'ERROR') add('amber', '逐站觀測失敗', '各站可達性、同步積壓與排空數量目前未知。', 'sites');

  const role = lead?.role ?? 'UNKNOWN';
  if (!lead?.writable) add(role === 'STANDBY' || role === 'UNCLAIMED' ? 'amber' : 'red', LEAD_COPY[role]?.[1] ?? '主中控狀態未知', lead?.reasons?.[0] ?? '控制寫入已停用。', 'control');
  const handoff = leadershipRaw?.handoff;
  if (handoff && ['WAITING', 'TRANSFERRING'].includes(handoff.state)) add('amber', '主中控交接進行中', '交接期間不受理新的管理寫入；已接受的部署會繼續完成。', 'control');

  const summary = jobSummary(job);
  if (summary?.state === 'OUTCOME_UNKNOWN') add('red', '部署結果未知', '先核對遠端部署記錄；未核對前不能開始新部署，中控不會自動重送。', 'deploy');
  else if (summary && ['FAILED', 'PARTIAL_FAILURE'].includes(summary.state)) add('red', `部署${summary.label}`, summary.reason ? `原因：${summary.reason}` : '請查看逐站結果。', 'deploy');

  for (const v of views) {
    if (v.state === 'RETIRED') continue;
    if (v.observable && v.observed === 'UNKNOWN') add('amber', `${v.siteId} 觀測未知`, `節點未回應${v.observation?.code ? `（${v.observation.code}）` : ''}；同步積壓與排空數量未知，不能視為正常。`, 'sites');
    else if (v.observable && v.observed === 'NOT_OBSERVED' && obsFreshness === 'FRESH') add('amber', `${v.siteId} 未被觀測`, '觀測結果中沒有此站。', 'sites');
    // 健康檢測：只採用最新可達的觀測；觀測失效已另列問題，不重複判斷健康。
    if (v.observed === 'REACHABLE' && v.observation?.control) {
      for (const i of healthIssues(v.siteId, healthModel(v.observation.control, { now, current: true }))) add(i.tone, i.title, i.detail, 'sites');
    }
    if (v.state === 'DRAINING') add('amber', `${v.siteId} 排空中`, '需排空引用、物件、上傳與帳戶後才能完成退役。', 'sites');
    if (v.state === 'REGISTERED') add('neutral', `${v.siteId} 已登記、尚未加入`, '需要時可在站點頁開始加入；加入開始後不提供取消。', 'sites');
  }
  const open = (status?.pendingChanges ?? []).filter((c) => c.state !== 'APPLIED');
  if (open.length) add('neutral', '成員變更進行中', `${open[0].kind ?? '?'} ${open[0].siteId ?? ''} · ${open[0].state ?? '?'}`, 'sites');
  if (openGcCount > 0) add('neutral', `${openGcCount} 個 GC 決策尚未完成`, '等待參與站回報；可在進階運維續行。', 'advanced/gc');
  for (const a of anomalies) add('red', '能力回報異常', a, 'control');
  const worker = status?.worker?.lastCode;
  if (worker && !workerCodeNormal(worker)) add('amber', 'worker 回報錯誤碼', worker, 'sites');

  issues.sort((a, b) => TONE_RANK[a.tone] - TONE_RANK[b.tone]);

  const [leadTone, leadTitle] = LEAD_COPY[role] ?? LEAD_COPY.UNKNOWN;
  const live = views.filter((v) => v.state !== 'RETIRED');
  const observable = live.filter((v) => v.observable);
  return {
    issues,
    worstTone: issues.length ? issues[0].tone : 'teal',
    leader: { tone: leadTone, title: leadTitle, writable: Boolean(lead?.writable), detail: lead?.writable ? '管理操作可用' : (lead?.reasons?.[0] ?? '控制寫入已停用。') },
    deployment: summary,
    // 沒有 worker 資料時不顯示；有碼時沿用 workerSummary 的文字與色調（未知碼為琥珀色）。
    worker: status?.worker?.lastCode ? workerSummary(status.worker) : null,
    sites: {
      rows: live.map((v) => {
        const [tone, label] = OBSERVED_COPY[v.observed] ?? ['neutral', String(v.observed)];
        return { siteId: v.siteId, campus: v.mode === 'CAMPUS', member: MEMBER_COPY[v.state] ?? String(v.state ?? '未知'), tone, observed: label };
      }),
      reachable: observable.filter((v) => v.observed === 'REACHABLE').length,
      observable: observable.length,
      known: status !== null && status !== undefined,
    },
  };
}
