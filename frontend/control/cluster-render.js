// 多站叢集畫面渲染。所有遠端字串都經由 dom.js 成為文字節點。

import { CHANGE_VOTE_PHASES, GC_VOTE_PHASES } from './constants.js';
import { executionCapabilityView, planExecutionStat } from './deployment-state.js';
import { h, replace } from './dom.js';
import { describeError, formatBool, formatBytes, formatCount, formatDuration, formatTime, label, shortId, shortLabel } from './format.js';
import {
  badge,
  disclosure,
  dl,
  emptyState,
  groupTitle,
  kv,
  note,
  replaceKeepingDisclosures,
  show,
  stat,
} from './render.js';
import { auditOutcomeTone, counterGroups, isPlainObject } from './state.js';
import {
  candidateKey,
  drainItems,
  inFlightFor,
  memberAlerts,
  memberCounts,
  monoAge,
  takeoverModeText,
  workerSummary,
} from './cluster-state.js';
import { cleanupRetryBlockers, healthModel, resultCodeText, readCheckBlockers, storageWriteBlockers } from './health-state.js';

const MEMBER_STATE_LABEL = {
  REGISTERED: '已登記',
  JOINING: '加入中',
  ACTIVE: 'ACTIVE',
  DRAINING: '排空中',
  RETIRED: '已退役',
};
const MEMBER_STATE_TONE = { REGISTERED: 'amber', JOINING: 'amber', ACTIVE: 'teal', DRAINING: 'amber', RETIRED: 'neutral' };
const CHANGE_LABEL = { PREPARING: '準備', COMMITTED: '提交 epoch', INSTALLED: '已安裝', APPLIED: '已套用' };
const GC_LABEL = { PREPARING: '準備柵欄', COMMITTED: '已提交', DELETED: '全部刪除' };
const PHASE_LABEL = { PREPARED: 'PREPARED', APPLIED: 'APPLIED', RELEASED: 'RELEASED', DELETED: 'DELETED' };

function secs(ms) {
  return ms === null || ms === undefined ? '—' : formatDuration(ms);
}

export function memberStateBadge(state) {
  return badge(state ? `${MEMBER_STATE_LABEL[state] ?? state}${MEMBER_STATE_LABEL[state] && state !== 'ACTIVE' ? ` · ${state}` : ''}` : '狀態未提供', MEMBER_STATE_TONE[state] ?? 'neutral');
}

function observationBadge(view) {
  switch (view.observed) {
    case 'REACHABLE':
      return badge('可達 · 非健康判定', 'outline');
    case 'UNKNOWN':
      return badge(`UNKNOWN${view.observation?.code ? ` · ${view.observation.code}` : ''}`, 'amber');
    case 'STALE':
      return badge('觀測失效', 'red');
    case 'LOADING':
      return badge('觀測讀取中', 'neutral');
    default:
      return badge('未觀測', 'neutral');
  }
}

// 單調時間資源的新鮮度說明。
export function monoLine(freshness, res, now, { what = '資料' } = {}) {
  const age = monoAge(res, now);
  const flight = inFlightFor(res, now);
  const flightText = flight === null ? '' : `；本輪請求進行中 ${secs(flight)}`;
  switch (freshness) {
    case 'FRESH':
      return note(`${what}更新於 ${res.receivedWall ? formatTime(res.receivedWall) : '—'}（自請求開始 ${secs(age)}）${flightText}`);
    case 'STALE':
      return note(`${what}已過期：最後成功請求開始於 ${secs(age)} 前，以下內容不代表目前狀態${flightText}。`, 'amber');
    case 'ERROR':
      return note(
        `${what}讀取失敗：${describeError(res.error)}。${res.startedAt !== null ? `以下為 ${secs(age)} 前開始的舊資料，已失效。` : '尚無成功讀取的資料。'}${flightText}`,
        'red',
      );
    default:
      return note(flight === null ? `${what}讀取中…` : `${what}讀取中…（${secs(flight)}）`);
  }
}

// ---------- 標頭與總覽 ----------

export function renderClusterConnection(el, { statusFreshness, statusRes, obsFreshness, obsRes, now }) {
  const level = statusFreshness === 'ERROR' || obsFreshness === 'ERROR' ? 'ERROR' : statusFreshness === 'STALE' ? 'STALE' : statusFreshness;
  el.dataset.level = level;
  const main = { FRESH: '叢集狀態即時', STALE: '叢集狀態過期', ERROR: '讀取失敗', NEVER: '讀取中…' }[level] ?? '讀取中…';
  const obsAge = monoAge(obsRes, now);
  const flight = inFlightFor(obsRes, now);
  const parts = [];
  if (statusFreshness === 'ERROR') parts.push(describeError(statusRes.error));
  parts.push(obsAge === null ? '觀測尚無成功結果' : `觀測 ${secs(obsAge)} 前${obsFreshness === 'FRESH' ? '' : '（過期）'}`);
  if (flight !== null) parts.push(`觀測請求中 ${secs(flight)}`);
  if (obsFreshness === 'ERROR') parts.push(`觀測失敗：${describeError(obsRes.error)}`);
  replace(el, h('span', { class: 'dot', 'aria-hidden': 'true' }), h('span', { class: 'conn-text' }, main), h('span', { class: 'conn-sub' }, parts.join(' · ')));
}

function tile(term, value, tone, hint) {
  return h('li', { class: `tile tile-${tone}` }, h('span', { class: 'tile-label' }, term), h('span', { class: 'tile-value' }, value), hint ? h('span', { class: 'tile-hint' }, hint) : null);
}

export function renderClusterOverview(el, { status, statusFreshness, obsFreshness, obsRes, views, openGcCount, gcFreshness, maxActive, now, activeRun = null, runsFreshness = 'NEVER', lead = null }) {
  const counts = memberCounts(status?.members);
  const unknown = views.filter((v) => v.observed === 'UNKNOWN').length;
  const notObserved = views.filter((v) => v.observable && v.observed === 'NOT_OBSERVED').length;
  const open = (status?.pendingChanges ?? []).filter((c) => c.state !== 'APPLIED');
  const worker = workerSummary(status?.worker);
  const fresh = statusFreshness === 'FRESH';
  const leadInfo = LEAD_COPY[lead?.role ?? 'UNKNOWN'];
  replace(
    el,
    tile('控制領導', leadInfo.short, leadInfo.tileTone, lead?.writable ? '控制寫入可用' : '控制寫入停用'),
    tile('叢集 epoch', status?.epoch ?? '未知', fresh ? 'teal' : 'amber', fresh ? `clusterId ${shortId(status?.clusterId, 8)}` : '狀態未即時，寫入停用'),
    tile('ACTIVE 成員', status ? String(counts.ACTIVE) : '未知', status ? 'neutral' : 'amber', status ? `參與 ${counts.participating}/${maxActive} · 登記 ${counts.REGISTERED} · 退役 ${counts.RETIRED}` : ''),
    tile(
      '逐站觀測',
      { FRESH: '即時', STALE: '過期', ERROR: '失敗', NEVER: '讀取中' }[obsFreshness] ?? '未知',
      obsFreshness === 'FRESH' ? (unknown || notObserved ? 'amber' : 'teal') : obsFreshness === 'ERROR' ? 'red' : 'amber',
      `${monoAge(obsRes, now) === null ? '尚無結果' : `${secs(monoAge(obsRes, now))} 前`}${unknown ? ` · UNKNOWN ${unknown}` : ''}${notObserved ? ` · 未觀測 ${notObserved}` : ''}`,
    ),
    tile('進行中變更', status ? String(open.length) : '未知', open.length ? 'amber' : 'neutral', open[0] ? `${open[0].kind ?? '?'} ${open[0].siteId ?? ''} · ${open[0].state ?? '?'}` : '只有 APPLIED 後才能開下一個'),
    tile('未完成 GC', gcFreshness === 'NEVER' ? '讀取中' : String(openGcCount), openGcCount ? 'amber' : 'neutral', gcFreshness === 'FRESH' ? '多方刪除決策' : '清單未即時'),
    tile('worker', worker.code ?? '未知', worker.tone, worker.label),
    activeRun
      ? tile('驗收任務', shortLabel('runState', activeRun.run.state), 'teal', `${show(activeRun.run.suite)} · ${shortId(activeRun.run.id)}`)
      : runsFreshness === 'FRESH'
        ? tile('驗收任務', '無執行中', 'neutral', '固定套件，需手動啟動')
        : tile('驗收任務', '未知', 'amber', '任務清單未即時'),
  );
}

// ---------- 控制領導 ----------

const LEAD_COPY = {
  LEADER: { short: '本程序 leader', title: '此程序持有控制租約', tone: 'ok', tileTone: 'teal' },
  SINGLE: { short: '單程序', title: '單程序控制模式（未啟用主備租約）', tone: 'ok', tileTone: 'teal' },
  STANDBY: { short: '備機 · 唯讀', title: '此程序是備機（standby）', tone: 'warn', tileTone: 'amber' },
  EXPIRED: { short: '租約過期', title: '控制租約已過期，尚無程序接管', tone: 'bad', tileTone: 'red' },
  UNCLAIMED: { short: '未取得租約', title: '控制租約尚未被任何程序取得', tone: 'warn', tileTone: 'amber' },
  UNKNOWN: { short: '未知', title: '控制領導狀態未知', tone: 'unknown', tileTone: 'amber' },
};

function fact(term, value, { mono = false, tone } = {}) {
  return h('div', { class: `leader-fact${tone ? ` text-${tone}` : ''}` }, h('span', { class: 'leader-fact-label' }, term), h('span', { class: `leader-fact-value${mono ? ' mono' : ''}` }, value));
}

export function renderLeadership(el, view, { freshness, res, now, transferEnabled }) {
  const copy = LEAD_COPY[view.role] ?? LEAD_COPY.UNKNOWN;
  const lead = view.lead;
  const last = view.lastKnown;
  el.className = `leader-bar leader-${copy.tone}`;
  const age = monoAge(res, now);
  const sourceText = view.source === 'leadership' ? '/cluster/leadership' : view.source === 'status' ? 'status.leadership' : '—';
  replace(
    el,
    h(
      'div',
      { class: 'leader-head' },
      h('span', { class: 'leader-dot', 'aria-hidden': 'true' }),
      h('div', { class: 'leader-title' }, h('span', { class: 'leader-eyebrow' }, '控制領導（權威站主備租約）'), h('strong', null, copy.title)),
      badge(lead ? lead.state : last ? `最後已知 ${last.state}` : '未知', copy.tileTone === 'teal' ? 'teal' : copy.tileTone),
    ),
    h(
      'div',
      { class: 'leader-facts' },
      fact('本程序', lead ? (lead.localLeader ? '持有租約' : '未持有') : '未知', { tone: lead?.localLeader ? undefined : 'amber' }),
      fact('generation', lead?.generation ?? (last?.generation ? `${last.generation}（最後已知）` : '—'), { mono: true }),
      fact('到期（資料庫時鐘，原樣）', lead?.expiresAt ?? '—', { mono: true }),
      fact('接管模式', takeoverModeText(lead), { tone: lead?.takeoverMode ? undefined : 'amber' }),
      fact('資料來源', view.source ? `${sourceText}${view.source === 'leadership' ? ` · 請求開始於 ${secs(age)}前` : ''}` : { STALE: '已過期', ERROR: '讀取失敗', NEVER: '讀取中…' }[freshness] ?? '未知', {
        tone: view.lead ? undefined : 'amber',
      }),
    ),
    view.writable
      ? note('控制寫入可用；每次確認後仍會重新核對租約、generation 與成員 epoch。')
      : h('ul', { class: 'list list-amber' }, [...view.reasons, '成員、GC、故障注入、設定重載與部署預覽寫入已停用；讀取與登入態保留。'].map((r) => h('li', null, r))),
    view.invalidated ? note('剛收到 CONTROL_LEASE_NOT_HELD：在新的領導讀取完成前不採信舊資料。', 'amber') : null,
    note(
      transferEnabled === false
        ? 'coordinatorTransferEnabled=false：主備接管只在同一邏輯權威站的程序之間；尚未支援不同站點間轉移權威身分。'
        : `coordinatorTransferEnabled=${String(transferEnabled)}：以伺服器能力回報為準；本畫面不提供權威身分轉移操作。`,
    ),
  );
}

// ---------- 成員 ----------

// ---------- 健康檢測（站點卡片內） ----------

const rawCode = (code) => h('span', { class: 'mono health-code', translate: 'no' }, code);
const codeLine = (code, status) => (code ? h('span', { class: 'health-code-line' }, resultCodeText(code, status), ' ', rawCode(code)) : null);
const healthChip = (tone, text) => h('span', { class: `status status-${tone}` }, h('span', { class: `dot dot-${tone}`, 'aria-hidden': 'true' }), text);
const timePart = (labelText, value) => (value ? h('span', null, `${labelText} ${formatTime(value)}`) : null);

function healthCheckRow(c) {
  return h('li', { class: `health-check health-${c.tone}` },
    h('div', { class: 'health-check-head' },
      h('span', { class: 'health-check-name' }, c.label),
      healthChip(c.tone, c.statusLabel),
      c.status !== c.reported && c.provided && c.reported ? h('span', { class: 'health-reported mono', translate: 'no' }, `回報 ${c.reported}`) : null),
    c.key === 'storage' ? h('span', { class: 'health-scope' }, 'HEAD＋空列表（maxKeys=0）：確認應用憑據可存取這個桶、端點回應為合法 S3；不讀取業務物件內容，也不等於讀寫健康。') : null,
    codeLine(c.code, c.status),
    h('span', { class: 'health-times' },
      c.provided ? null : '未檢查（後端未提供）',
      c.status === 'CHECKING' ? timePart('開始', c.startedAt) : timePart('檢測於', c.checkedAt),
      c.finishing ? h('span', { class: 'text-amber' }, '底層檢測仍在結束') : null,
      c.validUntil ? h('span', { class: c.expired ? 'text-amber' : undefined }, `有效至 ${formatTime(c.validUntil)}${c.expired ? '（已過期）' : ''}`) : null,
      c.durationMs !== null ? h('span', null, `耗時 ${c.durationMs} ms`) : null));
}

function healthSection(v, ctx) {
  if (!v.observable) return null;
  const obs = v.observation;
  const current = v.observed === 'REACHABLE';
  const health = healthModel(obs?.reachable ? obs.control : null, { now: ctx.now, current });
  const site = v.siteId;
  const isBusy = (k) => ctx.busy.has(`${k}:${site}`);
  const anyBusy = ['health', 'storage', 'cleanup'].some(isBusy);
  const readBlock = readCheckBlockers({ observable: v.observable, sessionActive: ctx.sessionActive });
  const writeBlock = storageWriteBlockers(health, { leadWritable: ctx.leadWritable, leadReasons: ctx.leadReasons, current });
  const cleanupBlock = cleanupRetryBlockers(health, { leadWritable: ctx.leadWritable, leadReasons: ctx.leadReasons });
  const msg = ctx.messages.get(site);
  const w = health.write;
  return h('section', { class: 'subsection health', 'aria-label': `${site} 健康檢測` },
    groupTitle('健康檢測', h('span', { class: 'group-meta' }, current ? '自動每 30 秒、結果 60 秒有效、每項上限 5 秒' : '觀測不是最新：以下為最後已知，不代表目前健康')),
    !obs?.reachable ? note('節點未回應觀測：健康狀態未知。', 'amber') : !health.provided ? note('後端未提供健康檢測欄位：顯示為未檢查。', 'amber') : null,
    health.dependencyOnly ? h('p', { class: 'alert alert-amber' }, '這次觀測只含依賴檢測（',
      rawCode(health.observationStatus ?? '—'), ' · identityProof ', rawCode(health.identityProof ?? '—'),
      '）：節點身份只來自設定，不代表資料庫身份已核實。') : null,
    h('div', { class: 'health-summary' },
      h('div', { class: 'health-tile' }, h('span', { class: 'health-tile-label' }, '就緒（應用／資料庫／Redis）'), healthChip(health.readiness.tone, health.readiness.label)),
      h('div', { class: 'health-tile' }, h('span', { class: 'health-tile-label' }, 'S3 桶存取'), healthChip(health.storage.tone, health.storage.label))),
    h('ul', { class: 'health-checks' }, health.checks.map(healthCheckRow)),
    h('div', { class: `health-write health-${w.tone}` },
      h('div', { class: 'health-check-head' }, h('span', { class: 'health-check-name' }, '存儲讀寫驗證（本次）'), healthChip(w.tone, w.label),
        w.phaseText ? h('span', { class: 'health-reported' }, `階段：${w.phaseText}`) : null),
      h('span', { class: 'health-scope' }, '寫入、讀回並刪除專用臨時小物件；結果只代表該次驗證，不等於目前健康。'),
      codeLine(w.code, w.state),
      h('span', { class: 'health-times' }, timePart('開始', w.startedAt), timePart('更新', w.updatedAt), w.requestId ? h('span', null, 'requestId ', rawCode(w.requestId)) : null),
      w.state === 'CLEANUP_PENDING' ? h('p', { class: 'alert alert-amber' }, '臨時物件尚未確認清理：不能開始新的寫入驗證；請由主中控「重試清理…」（只清理，不再寫入）。') : null),
    msg ? h('div', { class: `message message-${msg.tone}`, role: 'status' }, h('span', { class: 'message-title' }, msg.title), msg.lines?.length ? h('ul', { class: 'list' }, msg.lines.map((l) => h('li', null, l))) : null) : null,
    h('div', { class: 'form-actions health-actions' },
      h('button', { type: 'button', class: 'btn btn-ghost btn-sm', 'data-health-action': 'check', 'data-site': site, 'data-focus-key': `health:check:${site}`, disabled: anyBusy || readBlock.length > 0 }, isBusy('health') ? '要求檢測中…' : '立即檢測'),
      w.state === 'CLEANUP_PENDING'
        ? h('button', { type: 'button', class: 'btn btn-warn btn-sm', 'data-health-action': 'cleanup', 'data-site': site, 'data-focus-key': `health:cleanup:${site}`, disabled: anyBusy || cleanupBlock.length > 0 }, isBusy('cleanup') ? '送出中…' : '重試清理…')
        : h('button', { type: 'button', class: 'btn btn-ghost btn-sm', 'data-health-action': 'storage', 'data-site': site, 'data-focus-key': `health:storage:${site}`, disabled: anyBusy || writeBlock.length > 0 }, isBusy('storage') ? '送出中…' : '驗證存儲讀寫…')),
    (w.state === 'CLEANUP_PENDING' ? cleanupBlock : writeBlock).length
      ? h('p', { class: 'hint health-blockers' }, `${w.state === 'CLEANUP_PENDING' ? '重試清理' : '存儲讀寫驗證'}停用：${(w.state === 'CLEANUP_PENDING' ? cleanupBlock : writeBlock).join(' ')}`)
      : null);
}

function numberStat(term, value, hint) {
  return stat(term, value === null || value === undefined ? '未知' : String(value), { tone: value === null || value === undefined ? 'amber' : value > 0 ? 'amber' : undefined, hint, mono: true });
}

function channelRow(ch) {
  return h(
    'li',
    { class: `channel${ch.state === 'BLOCKED' ? ' channel-blocked' : ''}` },
    h('div', { class: 'channel-head' }, h('span', { class: 'channel-name mono' }, ch.name), badge(label('channelState', ch.state), ch.state === 'BLOCKED' ? 'amber' : 'outline')),
    h(
      'div',
      { class: 'channel-meta' },
      h('span', { class: ch.maximumBlockSeconds === null ? 'text-amber' : undefined }, ch.maximumBlockSeconds === null ? 'BLOCK 上限缺值' : `BLOCK 上限 ${ch.maximumBlockSeconds} 秒`),
      h('span', null, ch.expiresAt ? `到期 ${formatTime(ch.expiresAt)}` : '無倒數'),
      h('span', null, `連線 ${formatCount(ch.acceptedConnections)} · ${formatBytes(ch.forwardedBytes)}`),
      h('span', { class: 'text-amber' }, 'worker 路由未確認'),
    ),
  );
}

function memberCard(v, { epoch, healthCtx }) {
  const obs = v.observation;
  const alerts = memberAlerts(v, epoch);
  const titleId = `cl-member-${v.siteId}`;
  const sync = obs?.sync ?? null;
  const control = obs?.control ?? null;
  return h(
    'article',
    { class: `site-card member-card${v.observed === 'STALE' ? ' is-stale' : ''}`, 'aria-labelledby': titleId },
    h(
      'header',
      { class: 'site-head' },
      h(
        'div',
        { class: 'site-title' },
        h('h3', { id: titleId, class: 'mono' }, v.siteId),
        h('span', { class: `mode-chip${v.mode === 'CAMPUS' ? ' mode-chip-campus' : ''}` }, v.mode === 'CAMPUS' ? 'CAMPUS 權威站' : show(v.mode)),
        h('span', { class: 'site-mode mono' }, `slot ${show(v.slot)}`),
      ),
      h('div', { class: 'badge-line' }, memberStateBadge(v.state), observationBadge(v)),
    ),
    v.observed === 'REACHABLE' ? note('可達只代表節點能回應觀測；不等於站點、儲存或業務健康。') : null,
    v.observable
      ? h(
          'div',
          { class: 'stat-row' },
          numberStat('待投遞', sync?.pendingDeliveries ?? null, 'pendingDeliveries'),
          numberStat('待套用 Inbox', sync?.pendingInbox ?? null, 'pendingInbox'),
          numberStat('檔案傳輸', sync?.fileTransfers ?? null, 'fileTransfers'),
          stat('dataPlaneReady', obs?.dataPlaneReady === null || obs?.dataPlaneReady === undefined ? '未知' : formatBool(obs.dataPlaneReady), {
            tone: obs?.dataPlaneReady === true ? undefined : 'amber',
            hint: '軟體能力，非健康',
          }),
        )
      : note(v.state === 'RETIRED' ? '已退役：身份、slot 與歷史保留，不在觀測範圍。' : '此狀態不在 /observations 範圍內（只觀測 ACTIVE 與 DRAINING）。'),
    alerts.length ? h('ul', { class: 'alerts', 'aria-label': `${v.siteId} 重要提醒` }, alerts.map((a) => h('li', { class: `alert alert-${a.tone}` }, a.text))) : null,
    healthCtx ? healthSection(v, healthCtx) : null,
    v.observable
      ? h(
          'section',
          { class: 'subsection', 'aria-label': `${v.siteId} 排空進度` },
          groupTitle('排空進度', h('span', { class: 'group-meta' }, '退役前必須全部為 0')),
          h('div', { class: 'metric-row' }, drainItems(obs?.drain ?? null).map((i) => numberStat(i.label, i.value, i.key))),
        )
      : null,
    obs?.channels?.length
      ? h('section', { class: 'subsection', 'aria-label': `${v.siteId} 代理通道` }, groupTitle('代理通道（線上觀測）'), h('ul', { class: 'channel-list' }, obs.channels.map(channelRow)))
      : null,
    disclosure(
      `${v.siteId}-identity`,
      `身份與技術欄位（epoch ${show(v.joinedEpoch)}–${v.retiredEpoch === null ? '…' : v.retiredEpoch}）`,
      dl([
        kv('instanceId', show(v.instanceId), { mono: true }),
        kv('profileId', show(v.profileId), { mono: true }),
        kv('登記資料庫指紋', show(v.fingerprint), { mono: true }),
        kv('joined_epoch / retired_epoch', `${show(v.joinedEpoch)} / ${show(v.retiredEpoch)}`, { mono: true }),
        obs ? kv('health', `${obs.health}${obs.code ? ` · ${obs.code}` : ''}`, { mono: true, tone: obs.reachable ? undefined : 'amber' }) : null,
        obs?.nodeFingerprint ? kv('節點資料庫指紋', obs.nodeFingerprint, { mono: true }) : null,
        obs?.nodeClusterId ? kv('節點 clusterId', obs.nodeClusterId, { mono: true }) : null,
        obs?.membership ? kv('節點成員狀態', `${show(obs.membership.state)} · epoch ${show(obs.membership.epoch)}${obs.membership.pendingChange ? ` · 待處理 ${obs.membership.pendingChange}` : ''}`, { mono: true }) : null,
        obs?.protocolVersion !== null && obs?.protocolVersion !== undefined ? kv('protocolVersion', String(obs.protocolVersion), { mono: true }) : null,
        control?.observedAt ? kv('control.observedAt（節點時鐘，原樣）', String(control.observedAt), { mono: true }) : null,
        control?.reason ? kv('control.reason', String(control.reason), { mono: true, tone: 'amber' }) : null,
      ]),
    ),
    isPlainObject(control?.database)
      ? disclosure(
          `${v.siteId}-queue`,
          '舊版資料庫統計（佇列明細）',
          h(
            'div',
            { class: 'counter-grid' },
            counterGroups(control.database).map((g) =>
              h(
                'div',
                { class: 'counter-group' },
                h('div', { class: 'counter-head' }, h('span', null, g.key), h('span', { class: 'mono' }, `合計 ${g.total}`)),
                g.entries.length ? h('ul', null, g.entries.map(([s, n]) => h('li', null, h('span', { class: 'mono' }, s), h('span', { class: 'mono num' }, String(n))))) : h('p', { class: 'empty' }, '無資料列'),
              ),
            ),
          ),
        )
      : null,
  );
}

export function renderMembers(el, views, { epoch, statusFreshness, statusRes, now, health = null }) {
  const current = views.filter((v) => v.state !== 'RETIRED');
  const retired = views.filter((v) => v.state === 'RETIRED');
  replaceKeepingDisclosures(
    el,
    statusFreshness === 'FRESH' ? null : monoLine(statusFreshness, statusRes, now, { what: '成員名單' }),
    current.length ? h('div', { class: 'member-grid' }, current.map((v) => memberCard(v, { epoch, healthCtx: health }))) : emptyState(statusRes?.data ? '沒有未退役的成員。' : '尚無成員名單。'),
    retired.length
      ? disclosure('cl-retired', `已退役身份（${retired.length}，保留墓碑，不可重用）`, h('div', { class: 'member-grid' }, retired.map((v) => memberCard(v, { epoch }))))
      : null,
  );
}

// ---------- 進度與 votes ----------

function stepper(list, labels) {
  return h(
    'ol',
    { class: 'stepper', 'aria-label': '進度' },
    list.map((s) =>
      h(
        'li',
        { class: `step step-${s.status}`, 'aria-current': s.status === 'current' ? 'step' : undefined },
        h('span', { class: 'step-dot', 'aria-hidden': 'true' }),
        h('span', { class: 'step-label' }, labels[s.state] ?? s.state),
        h('span', { class: 'step-code mono' }, s.state),
      ),
    ),
  );
}

function voteTable(matrix, phases, ariaLabel) {
  return h(
    'div',
    { class: 'table-wrap', role: 'region', 'aria-label': ariaLabel, tabindex: '0' },
    h(
      'table',
      { class: 'table' },
      h('thead', null, h('tr', null, h('th', { scope: 'col' }, '參與站'), h('th', { scope: 'col' }, 'instance'), phases.map((p) => h('th', { scope: 'col' }, PHASE_LABEL[p] ?? p)))),
      h(
        'tbody',
        null,
        matrix.rows.map((r) =>
          h(
            'tr',
            null,
            h('td', { class: 'mono' }, `${r.identity.siteId} · slot ${show(r.identity.slot)}${r.siteMismatch ? '（site_id 不符）' : ''}`),
            h('td', { class: 'mono cell-sub', title: show(r.identity.instanceId) }, shortId(r.identity.instanceId, 13)),
            phases.map((p) =>
              h('td', { class: `cell-vote${r.phases[p] ? ' cell-vote-done' : ''}` }, r.phases[p] ? `已確認 ${formatTime(r.phases[p])}` : '尚未確認'),
            ),
          ),
        ),
      ),
    ),
  );
}

function changeCard(c) {
  return h(
    'article',
    { class: 'progress-card' },
    h(
      'header',
      { class: 'run-head' },
      h('div', { class: 'plan-title' }, h('span', { class: 'plan-release' }, `${c.kind === 'JOIN' ? '加入' : c.kind === 'RETIRE' ? '退役' : show(c.kind)} · ${show(c.siteId)}`), h('span', { class: 'plan-id mono' }, c.id)),
      badge(c.unknownState ? `未知狀態 ${show(c.state)}` : c.state, c.open ? 'amber' : 'outline'),
    ),
    stepper(c.steps, CHANGE_LABEL),
    h('p', { class: 'pair-meta' }, `epoch ${show(c.baseEpoch)} → ${show(c.nextEpoch)} · 參與者 ${c.participants.length} · ${c.lastConfirmedAt ? `最後確認時間 ${formatTime(c.lastConfirmedAt)}` : '尚無確認時間'}`),
    c.state === 'INSTALLED' ? note('INSTALLED：所有站已安裝同一 epoch，仍在收集 RELEASED 回執；只有 APPLIED 才能開下一個變更。', 'amber') : null,
    c.matrix.unmatched.length ? note(`有 ${c.matrix.unmatched.length} 筆 vote 無法對應參與者 instance_id。`, 'amber') : null,
    disclosure(`change-${c.id}`, `逐站確認（${CHANGE_VOTE_PHASES.join(' / ')}）`, voteTable(c.matrix, CHANGE_VOTE_PHASES, '成員變更逐站確認')),
  );
}

export function renderChangeList(el, changes, { emptyText }) {
  replaceKeepingDisclosures(el, changes.length ? h('div', { class: 'progress-list' }, changes.map(changeCard)) : emptyState(emptyText));
}

function gcCard(g) {
  const d = g.decision;
  return h(
    'article',
    { class: 'progress-card' },
    h(
      'header',
      { class: 'run-head' },
      h('div', { class: 'plan-title' }, h('span', { class: 'plan-release mono' }, `${shortId(d.fileId, 13)} · r${show(d.revision)}`), h('span', { class: 'plan-id mono' }, d.operationId)),
      badge(g.unknownState ? `未知狀態 ${show(g.state)}` : g.state, g.open ? 'amber' : 'outline'),
    ),
    stepper(g.steps, GC_LABEL),
    h('p', { class: 'pair-meta' }, `epoch ${show(d.epoch)} · ${formatBytes(d.sizeBytes)} · 參與者 ${d.participants.length} · ${g.lastConfirmedAt ? `最後確認時間 ${formatTime(g.lastConfirmedAt)}` : '尚無確認時間'}`),
    note('PREPARED 也可能是負面柵欄（ABSENT）：只代表禁止晚到 revision，不代表該站曾有實體副本。DELETED 為各站回報值；缺席或斷線永遠不是確認。'),
    disclosure(
      `gc-${d.operationId}`,
      '逐站確認與檔案資訊',
      [
        dl([kv('fileId', show(d.fileId), { mono: true }), kv('sha256', show(d.sha256), { mono: true }), kv('sizeBytes', formatCount(d.sizeBytes), { mono: true })]),
        voteTable(g.matrix, GC_VOTE_PHASES, '多方 GC 逐站確認'),
      ],
    ),
  );
}

export function renderGcList(el, list, { freshness, res, now, pendingOnly }) {
  replaceKeepingDisclosures(
    el,
    freshness === 'FRESH' ? null : monoLine(freshness, res, now, { what: 'GC 清單' }),
    list.length ? h('div', { class: 'progress-list' }, list.map(gcCard)) : emptyState(pendingOnly ? '目前沒有未完成的 GC 決策。' : '沒有 GC 決策紀錄。'),
  );
}

export function renderGcCandidates(el, candidates, { freshness, res, now, checkFor }) {
  replace(
    el,
    freshness === 'FRESH' ? null : monoLine(freshness, res, now, { what: '候選清單' }),
    candidates.length
      ? h(
          'div',
          { class: 'table-wrap', role: 'region', 'aria-label': 'GC 候選', tabindex: '0' },
          h(
            'table',
            { class: 'table' },
            h('thead', null, h('tr', null, ['狀態', 'fileId · revision', '更新', '說明'].map((t) => h('th', { scope: 'col' }, t)))),
            h(
              'tbody',
              null,
              candidates.map((c) => {
                const reasons = checkFor(c);
                return h(
                  'tr',
                  { dataset: { key: candidateKey(c) } },
                  h('td', null, badge(show(c.state), c.state === 'ELIGIBLE' ? 'teal' : c.state === 'BLOCKED' ? 'red' : 'neutral')),
                  h('td', { class: 'mono', title: c.fileId }, `${shortId(c.fileId, 13)} · r${c.revision}`),
                  h('td', { class: 'cell-time' }, c.updatedAt ? formatTime(c.updatedAt) : '—'),
                  h('td', { class: 'cell-note' }, reasons.length ? reasons.join(' ') : '可發起；後端仍會重新檢查保留期、引用與柵欄。'),
                );
              }),
            ),
          ),
        )
      : emptyState('目前沒有已發現的候選（不代表沒有可回收的資料）。'),
  );
}

// ---------- 表單輔助 ----------

export function syncCheckboxes(container, options, name, emptyText) {
  const signature = JSON.stringify(options.map((o) => [o.value, o.label]));
  if (container.dataset.signature === signature) return;
  const first = container.dataset.signature === undefined;
  const previous = new Set([...container.querySelectorAll('input[type="checkbox"]')].filter((i) => i.checked).map((i) => i.value));
  container.replaceChildren(
    ...(options.length
      ? options.map((o) =>
          h('label', { class: 'seg-option' }, h('input', { type: 'checkbox', name, value: o.value, checked: first || previous.has(o.value) }), h('span', { class: 'mono' }, o.label)),
        )
      : [h('p', { class: 'empty' }, emptyText)]),
  );
  container.dataset.signature = signature;
}

export function renderProfileDetails(el, profile, reasons) {
  if (!profile) {
    replace(el, emptyState('沒有可選擇的 profile。'));
    return;
  }
  replace(
    el,
    dl([
      kv('siteId', profile.siteId, { mono: true }),
      kv('mode / slot', `${show(profile.mode)} / ${show(profile.slot)}`, { mono: true }),
      kv('instanceId', show(profile.instanceId), { mono: true }),
    ], 'kv kv-stack'),
    reasons.length ? h('ul', { class: 'list list-amber' }, reasons.map((r) => h('li', null, r))) : null,
  );
}

export function renderReleaseInfo(el, release) {
  if (!release) {
    replace(el, emptyState('沒有可選擇的制品。'));
    return;
  }
  replace(el, dl([kv('sha256', show(release.sha256), { mono: true }), kv('acceptanceEvidenceId', show(release.acceptanceEvidenceId), { mono: true })], 'kv kv-stack'));
}

// ---------- 部署預覽 ----------

export function renderClusterPreview(el, pv, { error } = {}) {
  if (!pv) {
    replaceKeepingDisclosures(el, error ? note(describeError(error), 'red') : null, emptyState('尚未建立預覽。預覽只綁定 epoch 與精確成員，不會部署。'));
    return;
  }
  replaceKeepingDisclosures(
    el,
    error ? note(describeError(error), 'red') : null,
    h(
      'div',
      { class: 'plan-head' },
      h('div', { class: 'plan-title' }, h('span', { class: 'plan-release mono' }, show(pv.releaseId)), h('span', { class: 'plan-id mono' }, pv.id)),
      badge(pv.state === 'PREVIEW_ONLY' ? '僅預覽 · PREVIEW_ONLY' : show(pv.state), pv.state === 'PREVIEW_ONLY' ? 'outline' : 'red'),
    ),
    h(
      'div',
      { class: 'stat-row' },
      stat('綁定 epoch', show(pv.epoch), { tone: pv.epochStale ? 'red' : undefined, hint: pv.epochStale ? `目前 ${pv.currentEpoch}，預覽已過期` : '與目前叢集一致', mono: true }),
      stat('SHA-256 對照', pv.sha === 'MATCH' ? '一致' : pv.sha === 'MISMATCH' ? '不一致' : '無法對照', { tone: pv.sha === 'MATCH' ? 'teal' : pv.sha === 'MISMATCH' ? 'red' : 'amber', hint: '與 /releases 比對' }),
      stat('成員', String(pv.members.length), { tone: pv.memberChanges.length ? 'amber' : undefined, hint: pv.memberChanges.length ? `${pv.memberChanges.length} 個已非目前 ACTIVE 身份` : '精確 UUID' }),
      stat('executionEnabled', planExecutionStat(pv.executionEnabled).text, { tone: planExecutionStat(pv.executionEnabled).tone, mono: true }),
    ),
    dl([kv('artifactSha256', show(pv.artifactSha256), { mono: true }), kv('建立時間', pv.createdAt ? formatTime(pv.createdAt) : '—'), kv('requestHash', show(pv.requestHash), { mono: true })], 'kv kv-stack'),
    h('section', { class: 'subsection' }, groupTitle(`阻擋項（${pv.blockers.length}）`), pv.blockers.length ? h('ol', { class: 'blockers' }, pv.blockers.map((b) => h('li', null, b.title))) : note('未列出阻擋項；執行前仍會重新核對部署條件。', 'amber')),
    disclosure(
      'cl-preview-members',
      `綁定成員（${pv.members.length}）`,
      dl(pv.members.map((m) => kv(`${m.siteId} · slot ${show(m.slot)}`, `${show(m.mode)} · ${show(m.instanceId)}`, { mono: true }))),
    ),
    disclosure('cl-preview-steps', `未來部署步驟預覽（${pv.steps.length}）· 僅預覽，未執行`, [
      note('以下步驟只是預覽：未執行任何渲染、傳輸、備份或遷移檢查。', 'amber'),
      pv.steps.length ? h('ol', { class: 'steps' }, pv.steps.map((s) => h('li', null, s.title))) : emptyState('API 未回傳步驟。'),
    ]),
  );
}

// ---------- 稽核 ----------

export function renderClusterAudit(el, entries, { freshness, res, now }) {
  replace(
    el,
    monoLine(freshness, res, now, { what: '成員／GC 稽核' }),
    entries.length
      ? h(
          'div',
          { class: 'table-wrap', role: 'region', 'aria-label': '成員與 GC 稽核', tabindex: '0' },
          h(
            'table',
            { class: 'table' },
            h('thead', null, h('tr', null, ['時間', '動作', '站點', 'epoch', '操作者', 'request_id'].map((t) => h('th', { scope: 'col' }, t)))),
            h(
              'tbody',
              null,
              entries.map((e) =>
                h(
                  'tr',
                  null,
                  h('td', { class: 'cell-time' }, e.createdAt ? formatTime(e.createdAt) : '—'),
                  h('td', { class: 'mono', translate: 'no' }, show(e.action)),
                  h('td', { class: 'mono', translate: 'no' }, show(e.siteId)),
                  h('td', { class: 'mono', translate: 'no' }, show(e.epoch)),
                  h('td', { class: 'mono', translate: 'no' }, show(e.actor)),
                  h('td', { class: 'mono cell-sub', title: show(e.requestId) }, shortId(e.requestId, 13)),
                ),
              ),
            ),
          ),
        )
      : emptyState('沒有成員／GC 稽核紀錄。'),
  );
}

export function renderFileAudit(el, entries, { freshness, res, now }) {
  replace(
    el,
    monoLine(freshness, res, now, { what: '故障／預覽／任務稽核' }),
    entries.length
      ? h(
          'div',
          { class: 'table-wrap', role: 'region', 'aria-label': '故障、預覽與任務稽核', tabindex: '0' },
          h(
            'table',
            { class: 'table' },
            h('thead', null, h('tr', null, ['時間', '結果', '動作', '目標', '操作者', 'requestId'].map((t) => h('th', { scope: 'col' }, t)))),
            h(
              'tbody',
              null,
              entries.map((e) =>
                h(
                  'tr',
                  null,
                  h('td', { class: 'cell-time' }, e.at ? formatTime(e.at) : '—'),
                  h('td', null, badge(show(e.outcome), auditOutcomeTone(e.outcome))),
                  h('td', { class: 'mono', translate: 'no' }, show(e.action)),
                  h('td', { class: 'mono', translate: 'no' }, show(e.target)),
                  h('td', { class: 'mono', translate: 'no' }, show(e.actor)),
                  h('td', { class: 'mono cell-sub', title: String(e.requestId ?? '') }, shortId(e.requestId, 13)),
                ),
              ),
            ),
          ),
        )
      : emptyState('沒有故障／預覽／任務稽核紀錄。'),
  );
}

// ---------- 能力 ----------

export function renderClusterCapSummary(el, caps, anomalies, error) {
  if (!caps) {
    replace(el, error ? badge('能力讀取失敗', 'red') : badge('讀取中', 'neutral'));
    return;
  }
  replace(
    el,
    badge(show(caps.topology), caps.topology === 'SINGLE_CLUSTER' ? 'outline' : 'red'),
    badge(`上限 ${show(caps.maximumActiveSites)} 站 · ${show(caps.allocationSlots)} slots`, 'neutral'),
    badge(executionCapabilityView(caps.deploymentExecutionEnabled).badge, executionCapabilityView(caps.deploymentExecutionEnabled).tone),
    badge(caps.remoteFailureMatrixAccepted === false ? '遠端故障矩陣未驗收' : '遠端故障矩陣旗標異常', 'amber'),
    anomalies.length ? badge(`異常 ${anomalies.length}`, 'red') : null,
  );
}

export function renderClusterCapabilities(el, caps, anomalies, { freshness, res, now }) {
  replace(
    el,
    freshness === 'FRESH' ? null : monoLine(freshness, res, now, { what: '能力' }),
    caps
      ? dl([
          kv('topology', show(caps.topology), { mono: true }),
          kv('maximumActiveSites', show(caps.maximumActiveSites), { mono: true }),
          kv('allocationSlots', show(caps.allocationSlots), { mono: true }),
          kv('coordinatorTransferEnabled', `${String(caps.coordinatorTransferEnabled)}${caps.coordinatorTransferEnabled === false ? ' · 權威站不可轉移或退役' : ''}`, { mono: true }),
          kv('businessSessionShared', `${String(caps.businessSessionShared)} · 業務登入態由後端跨站接續，中控不處理`, { mono: true }),
          kv('deploymentExecutionEnabled', executionCapabilityView(caps.deploymentExecutionEnabled).detail, { mono: true, tone: executionCapabilityView(caps.deploymentExecutionEnabled).state === 'UNKNOWN' ? 'amber' : undefined }),
          kv('remoteFailureMatrixAccepted', `${String(caps.remoteFailureMatrixAccepted)} · 跨地域斷網尚待驗收`, { mono: true, tone: 'amber' }),
        ])
      : null,
    anomalies.length ? h('ul', { class: 'list list-red' }, anomalies.map((a) => h('li', null, a))) : null,
    note('業務個人資料接收授權只能由帳戶本人在權威站操作；中控不提供代授權，也不呼叫 consent API。'),
  );
}
