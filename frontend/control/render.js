// 畫面渲染。所有遠端字串都經由 dom.js 的 h()/replace() 成為文字節點。
// 版面原則：核心狀態與告警優先；技術欄位以原生 <details> 收合，且輪詢重繪時保留展開狀態。

import { SITE_IDS, SUITE_SCOPES } from './constants.js';
import { executionCapabilityView, planExecutionStat } from './deployment-state.js';
import { h, replace } from './dom.js';
import {
  describeError,
  formatAge,
  formatBool,
  formatBytes,
  formatCount,
  formatDuration,
  formatTime,
  formatTimeWithAge,
  isMissing,
  label,
  parseTime,
  shortId,
  shortLabel,
  siteLabel,
} from './format.js';
import {
  auditDetails,
  auditOutcomeTone,
  channelSummary,
  counterGroups,
  gcSummary,
  isPlainObject,
  normalizeConfiguredChannels,
  planShaStatus,
  runNotes,
  scalarCounters,
  siteAlerts,
  toDisplayItems,
} from './state.js';

// ---------- 共用元件 ----------

export function badge(text, tone = 'neutral') {
  return h('span', { class: `badge badge-${tone}` }, text);
}

export function kv(term, value, { mono = false, tone } = {}) {
  const dd = h('dd', { class: mono ? 'mono' : undefined }, value);
  if (tone) dd.classList.add(`text-${tone}`);
  return [h('dt', null, term), dd];
}

export function dl(items, cls = 'kv') {
  return h('dl', { class: cls }, items);
}

export function note(text, tone = 'neutral') {
  return h('p', { class: `note note-${tone}` }, text);
}

export function emptyState(text) {
  return h('p', { class: 'empty' }, text);
}

export function show(value) {
  return isMissing(value) ? '（未提供）' : String(value);
}

export function stat(term, value, { tone, hint, mono = false } = {}) {
  return h(
    'div',
    { class: `stat${tone ? ` stat-${tone}` : ''}` },
    h('span', { class: 'stat-label' }, term),
    h('span', { class: `stat-value${mono ? ' mono' : ''}` }, value),
    hint ? h('span', { class: 'stat-hint' }, hint) : null,
  );
}

export function groupTitle(text, extra) {
  return h('h4', { class: 'group-title' }, h('span', null, text), extra ?? null);
}

// 原生收合區。key 用於輪詢重繪後還原使用者的展開／收合選擇。
export function disclosure(key, summary, body, { open = false } = {}) {
  return h(
    'details',
    { class: 'disclosure', dataset: { key }, open },
    h('summary', null, h('span', { class: 'disclosure-title' }, summary)),
    h('div', { class: 'disclosure-body' }, body),
  );
}

export function replaceKeepingDisclosures(container, ...children) {
  const states = new Map();
  for (const d of container.querySelectorAll('details[data-key]')) states.set(d.dataset.key, d.open);
  // 輪詢重繪時保留鍵盤焦點：有 data-focus-key 的控制項重繪後重新聚焦。
  const active = container.ownerDocument?.activeElement;
  const focusKey = active && container.contains(active) ? active.dataset?.focusKey ?? null : null;
  replace(container, ...children);
  for (const d of container.querySelectorAll('details[data-key]')) {
    if (states.has(d.dataset.key)) d.open = states.get(d.dataset.key);
  }
  if (focusKey) {
    for (const el of container.querySelectorAll('[data-focus-key]')) {
      if (el.dataset.focusKey === focusKey) { el.focus({ preventScroll: true }); break; }
    }
  }
}

export function freshnessLine(freshness, resource, now) {
  const at = resource?.receivedAt;
  switch (freshness) {
    case 'FRESH':
      return note(`更新於 ${formatTimeWithAge(at, now)}`, 'neutral');
    case 'STALE':
      return note(`資料過期：最後成功讀取於 ${formatTimeWithAge(at, now)}，以下內容不代表目前狀態。`, 'amber');
    case 'ERROR':
      return note(
        `讀取失敗：${describeError(resource.error)}。${at ? `以下為 ${formatTime(at)} 的舊資料，已失效。` : '尚無任何成功讀取的資料。'}`,
        'red',
      );
    default:
      return note('讀取中…', 'neutral');
  }
}

// 保留使用者選擇的同步式 <select> 更新：選項未變時不重建，避免打斷操作。
export function syncSelect(select, options, emptyLabel) {
  const signature = JSON.stringify(options.map((o) => [o.value, o.label]));
  if (select.dataset.signature === signature) return;
  const previous = select.value;
  select.replaceChildren();
  if (options.length === 0) {
    select.append(h('option', { value: '', disabled: true, selected: true }, emptyLabel));
  } else {
    for (const opt of options) select.append(h('option', { value: opt.value }, opt.label));
    if (options.some((o) => o.value === previous)) select.value = previous;
  }
  select.dataset.signature = signature;
}

export function renderList(el, items, tone = 'neutral') {
  if (!items.length) {
    el.hidden = true;
    el.replaceChildren();
    return;
  }
  el.hidden = false;
  el.className = `list list-${tone}`;
  replace(el, items.map((text) => h('li', null, text)));
}

export function renderMessage(el, message) {
  if (!message) {
    el.hidden = true;
    el.replaceChildren();
    return;
  }
  el.hidden = false;
  el.className = `message message-${message.tone ?? 'neutral'}`;
  replace(
    el,
    h('p', { class: 'message-title' }, message.title),
    message.lines?.length ? h('ul', null, message.lines.map((l) => h('li', null, l))) : null,
  );
}

// ---------- 標頭 ----------

export function renderConnection(el, summary, now, loggedIn) {
  if (!loggedIn) {
    el.dataset.level = 'OFF';
    replace(el, h('span', { class: 'dot', 'aria-hidden': 'true' }), h('span', { class: 'conn-text' }, '未登入'));
    return;
  }
  let main;
  let sub = '';
  switch (summary.level) {
    case 'FRESH':
      main = '資料即時';
      sub = `更新 ${formatAge(now - summary.oldestSuccess)}`;
      break;
    case 'STALE':
      main = '資料過期';
      sub = `最後成功 ${formatAge(now - summary.oldestSuccess)}`;
      break;
    case 'ERROR':
      main = '讀取失敗';
      sub = describeError(summary.error);
      break;
    default:
      main = '讀取中…';
  }
  el.dataset.level = summary.level;
  replace(
    el,
    h('span', { class: 'dot', 'aria-hidden': 'true' }),
    h('span', { class: 'conn-text' }, main),
    sub ? h('span', { class: 'conn-sub' }, sub) : null,
  );
}

export function renderEnvChip(el, capabilities) {
  const env = isPlainObject(capabilities) ? capabilities.environmentKind : null;
  if (isMissing(env)) {
    el.hidden = true;
    el.replaceChildren();
    return;
  }
  el.hidden = false;
  el.className = `env-chip${env === 'isolated-acceptance' ? '' : ' env-chip-warn'}`;
  el.textContent = env === 'isolated-acceptance' ? '隔離驗收環境' : `環境：${String(env)}（非預期）`;
  el.setAttribute('title', `environmentKind=${String(env)}`);
}

// ---------- 總覽 ----------

function siteTileStatus(sv) {
  switch (sv.effectiveStatus) {
    case 'OBSERVED':
      return { text: '已觀測', tone: 'teal' };
    case 'UNKNOWN':
      return { text: `未知 · ${show(sv.reported)}`, tone: 'amber' };
    case 'MISSING':
      return { text: '未回報', tone: 'amber' };
    case 'LOADING':
      return { text: '讀取中', tone: 'neutral' };
    default:
      return { text: '資料失效', tone: 'red' };
  }
}

function overviewTile(term, value, tone, hint) {
  return h(
    'li',
    { class: `tile tile-${tone}` },
    h('span', { class: 'tile-label' }, term),
    h('span', { class: 'tile-value' }, value),
    hint ? h('span', { class: 'tile-hint' }, hint) : null,
  );
}

export function renderOverview(el, { summary, sitesView, runsFreshness, activeEntry, now }) {
  const connTone = { FRESH: 'teal', STALE: 'amber', ERROR: 'red' }[summary.level] ?? 'neutral';
  const connText = { FRESH: '即時', STALE: '過期', ERROR: '失效' }[summary.level] ?? '讀取中';
  const pair = sitesView.pairStatus;
  const tiles = [
    overviewTile(
      '中控資料',
      connText,
      connTone,
      summary.oldestSuccess ? `最後成功 ${formatAge(now - summary.oldestSuccess)}` : '尚未成功讀取',
    ),
    overviewTile(
      '兩站配對',
      pair ? shortLabel('pairStatus', pair) : '未知',
      pair === 'VERIFIED_DISTINCT' ? 'teal' : pair === 'SAME_DATABASE_REJECTED' ? 'red' : 'amber',
      pair ?? (sitesView.lastKnownPairStatus ? `最後已知 ${sitesView.lastKnownPairStatus}` : '資料不可用'),
    ),
    ...SITE_IDS.map((id) => {
      const sv = sitesView.sites[id];
      const status = siteTileStatus(sv);
      const alerts = siteAlerts(sv).length;
      return overviewTile(
        siteLabel(id),
        status.text,
        status.tone,
        sv.effectiveStatus === 'OBSERVED' ? `${alerts} 項提醒 · 非健康判定` : alerts ? `${alerts} 項提醒` : '',
      );
    }),
    activeEntry
      ? overviewTile('驗收任務', shortLabel('runState', activeEntry.run.state), 'teal', `${show(activeEntry.run.suite)} · ${shortId(activeEntry.run.id)}`)
      : runsFreshness === 'FRESH'
        ? overviewTile('驗收任務', '無執行中', 'neutral', '可手動啟動')
        : overviewTile('驗收任務', '未知', 'amber', '任務清單未即時'),
  ];
  replace(el, tiles);
}

// ---------- 能力 ----------

export function renderCapSummary(el, resource) {
  const data = isPlainObject(resource.data) ? resource.data : null;
  if (!data) {
    replace(el, resource.error ? badge('讀取失敗', 'red') : badge('讀取中', 'neutral'));
    return;
  }
  const configured = normalizeConfiguredChannels(data);
  const configuredCount = SITE_IDS.reduce((n, id) => n + configured.sites[id].length, 0);
  replace(
    el,
    badge(executionCapabilityView(data.deploymentExecutionEnabled).badge, executionCapabilityView(data.deploymentExecutionEnabled).tone),
    data.remoteFaultMatrixAutomated === false ? badge('遠端故障矩陣未自動化', 'amber') : badge(`遠端故障矩陣：${show(data.remoteFaultMatrixAutomated)}`, 'amber'),
    configured.configured ? badge(`設定恢復通道 ${configuredCount}`, 'neutral') : badge('未提供設定恢復通道', 'amber'),
  );
}

export function renderCapabilities(el, resource, freshness, now) {
  const data = isPlainObject(resource.data) ? resource.data : null;
  if (!data) {
    replace(el, freshnessLine(freshness, resource, now));
    return;
  }
  const env = data.environmentKind;
  const suites = toDisplayItems(data.testSuites).map((i) => i.title);
  replace(
    el,
    dl([
      kv('API 版本', show(data.apiVersion), { mono: true }),
      kv('環境類型', show(env), { mono: true, tone: env === 'isolated-acceptance' ? undefined : 'amber' }),
      kv(
        '部署執行',
        executionCapabilityView(data.deploymentExecutionEnabled).detail,
        { tone: executionCapabilityView(data.deploymentExecutionEnabled).state === 'UNKNOWN' ? 'amber' : undefined },
      ),
      kv(
        '遠端故障矩陣',
        data.remoteFaultMatrixAutomated === false
          ? 'false · 未自動化，需另行人工驗收'
          : `${show(data.remoteFaultMatrixAutomated)} · 本頁不據此判定遠端驗收`,
        { tone: 'amber' },
      ),
      kv('測試套件', suites.length ? suites.join('、') : '（未提供）', { mono: true }),
    ]),
    configuredChannelsSection(normalizeConfiguredChannels(data)),
    freshness === 'FRESH' ? null : freshnessLine(freshness, resource, now),
  );
}

function configuredChannelsSection(configured) {
  return h(
    'section',
    { class: 'subsection', 'aria-label': '操作員設定的恢復通道清單' },
    groupTitle('設定的恢復通道 configuredChannels'),
    note('操作員預先設定的離線恢復清單，只用於 RESTORE 選擇；不代表節點在線、代理狀態或 worker 路由已驗證，也不用於 BLOCK。'),
    configured.configured
      ? dl(
          SITE_IDS.map((id) => {
            const list = configured.sites[id];
            const text = list.length
              ? list.map((c) => `${c.name}（${c.state ?? '狀態未提供'}${c.source ? ` · ${c.source}` : ''}）`).join('、')
              : '未設定（不猜測通道名稱）';
            return kv(siteLabel(id), text, { mono: true, tone: list.length ? undefined : 'amber' });
          }),
        )
      : note('伺服器未提供 configuredChannels；節點未觀測時無法從設定清單選擇 RESTORE。', 'amber'),
  );
}

// ---------- 站點 ----------

function pairTone(status) {
  if (status === 'VERIFIED_DISTINCT') return 'teal';
  if (status === 'SAME_DATABASE_REJECTED') return 'red';
  return 'amber';
}

export function renderPairBar(el, sitesView, now) {
  const pair = sitesView.pairStatus;
  replace(
    el,
    h(
      'div',
      { class: 'pair-main' },
      h('span', { class: 'pair-label' }, '兩站資料庫配對'),
      pair
        ? badge(label('pairStatus', pair), pairTone(pair))
        : badge(sitesView.lastKnownPairStatus ? `未知（最後已知：${sitesView.lastKnownPairStatus}）` : '未知', 'amber'),
    ),
    h(
      'p',
      { class: 'pair-meta' },
      `伺服器觀測 ${sitesView.observedAt ? formatTimeWithAge(sitesView.observedAt, now) : '（無）'} · 本頁接收 ${
        sitesView.receivedAt ? formatTimeWithAge(sitesView.receivedAt, now) : '（尚未成功讀取）'
      }`,
    ),
    sitesView.trusted ? null : freshnessLine(sitesView.freshness, { receivedAt: sitesView.receivedAt, error: sitesView.error }, now),
  );
}

function siteStatusBadge(sv) {
  const s = siteTileStatus(sv);
  const text = sv.effectiveStatus === 'OBSERVED' ? 'OBSERVED · 已觀測' : s.text;
  return badge(text, s.tone);
}

function checkStat(term, value) {
  if (value === 'NOT_CHECKED') return stat(term, '未檢查', { tone: 'amber', hint: 'NOT_CHECKED' });
  if (isMissing(value)) return stat(term, '未提供', { tone: 'amber' });
  return stat(term, String(value), { mono: true });
}

function expiresText(iso, now) {
  const t = parseTime(iso);
  if (t === null) return formatTime(iso);
  const diff = t - now;
  return `${formatTime(t)}（${diff >= 0 ? `約 ${formatDuration(diff)} 後` : `已過 ${formatDuration(-diff)}`}，依本機時鐘）`;
}

function channelRow(ch, now) {
  const blocked = ch.state === 'BLOCKED';
  return h(
    'li',
    { class: `channel${blocked ? ' channel-blocked' : ''}` },
    h(
      'div',
      { class: 'channel-head' },
      h('span', { class: 'channel-name mono' }, ch.name),
      badge(label('channelState', ch.state), blocked ? 'amber' : ch.state === 'FORWARDING' ? 'outline' : 'neutral'),
    ),
    h(
      'div',
      { class: 'channel-meta' },
      h('span', { class: ch.maximumBlockSeconds === null ? 'text-amber' : undefined }, ch.maximumBlockSeconds === null ? 'BLOCK 上限缺值' : `BLOCK 上限 ${ch.maximumBlockSeconds} 秒`),
      h('span', null, ch.expiresAt ? `到期 ${expiresText(ch.expiresAt, now)}` : '無倒數'),
      h('span', { class: 'text-amber' }, ch.routingVerified ? 'routingVerified=true（仍需人工確認）' : 'worker 路由未確認'),
    ),
  );
}

function channelSection(sv, now) {
  const summary = channelSummary(sv.channels);
  return h(
    'section',
    { class: 'subsection', 'aria-label': `${siteLabel(sv.id)} 代理通道` },
    groupTitle('代理通道', h('span', { class: 'group-meta' }, `${summary.forwarding} 放行 · ${summary.blocked} 阻斷`)),
    sv.channels.length
      ? h('ul', { class: 'channel-list' }, sv.channels.map((ch) => channelRow(ch, now)))
      : emptyState('API 未回報任何通道。'),
    note('FORWARDING 只表示代理放行，不代表跨站網路正常或 worker 流量已接入代理。'),
    sv.channels.length
      ? disclosure(
          `${sv.id}-channel-stats`,
          '通道傳輸統計',
          dl(
            sv.channels.map((ch) =>
              kv(ch.name, `連線 ${formatCount(ch.acceptedConnections)} · 已轉送 ${formatBytes(ch.forwardedBytes)} · routingVerified=${String(ch.routingVerifiedRaw)}`, { mono: true }),
            ),
          ),
        )
      : null,
  );
}

function queueSection(sv) {
  const db = sv.database;
  const groups = counterGroups(db);
  const scalars = scalarCounters(db);
  return h(
    'section',
    { class: 'subsection', 'aria-label': `${siteLabel(sv.id)} 同步佇列摘要` },
    groupTitle('同步佇列摘要', h('span', { class: 'group-meta' }, 'API 原值，不判定收斂')),
    scalars.length
      ? h('div', { class: 'metric-row' }, scalars.map(([key, n]) => stat(shortLabel('counterScalar', key).split(' ')[0], String(n), { hint: key, mono: true })))
      : null,
    groups.length
      ? h(
          'ul',
          { class: 'chip-row', 'aria-label': '各分組合計' },
          groups.map((g) => h('li', { class: 'chip' }, h('span', null, shortLabel('counterGroup', g.key).split(' ')[0]), h('span', { class: 'mono num' }, String(g.total)))),
        )
      : emptyState('API 未回傳任何計數分組。'),
    groups.length
      ? disclosure(
          `${sv.id}-queue-detail`,
          `佇列與複製明細（${groups.length} 組）`,
          h(
            'div',
            { class: 'counter-grid' },
            groups.map((g) =>
              h(
                'div',
                { class: 'counter-group' },
                h('div', { class: 'counter-head' }, h('span', null, shortLabel('counterGroup', g.key)), h('span', { class: 'mono' }, `合計 ${g.total}`)),
                g.entries.length
                  ? h('ul', null, g.entries.map(([state, n]) => h('li', null, h('span', { class: 'mono' }, state), h('span', { class: 'mono num' }, String(n)))))
                  : h('p', { class: 'empty' }, '無資料列'),
              ),
            ),
          ),
        )
      : null,
  );
}

function identityDisclosure(sv, now) {
  const db = sv.database;
  const checks = db
    ? `V35 ${formatBool(db.v35)} · 受限角色 ${formatBool(db.restrictedRole)} · 協調守衛 ${formatBool(db.coordinationGuard)}`
    : '無資料庫身份資料';
  return disclosure(
    `${sv.id}-identity`,
    `身份與技術欄位（${checks}）`,
    dl([
      kv('觀測狀態', label('observationStatus', sv.reported)),
      sv.reason ? kv('原因', label('reason', sv.reason), { tone: 'amber' }) : null,
      kv('節點觀測時間', sv.observedAt ? formatTimeWithAge(sv.observedAt, now) : '（未提供）'),
      kv('與總觀測時間差', sv.lagMs === null ? '無法判斷' : `${formatDuration(sv.lagMs)}${sv.lagExceeded ? '（超過 30 秒）' : ''}`, {
        tone: sv.lagExceeded === false ? undefined : 'amber',
      }),
      kv('siteId', show(sv.siteId), { mono: true }),
      kv('instanceId', show(sv.instanceId), { mono: true }),
      kv('mode', show(sv.mode), { mono: true }),
      db ? kv('databaseFingerprint', show(db.databaseFingerprint), { mono: true }) : null,
      db ? kv('V35', formatBool(db.v35), { tone: db.v35 === true ? undefined : 'red' }) : null,
      db ? kv('restrictedRole', formatBool(db.restrictedRole), { tone: db.restrictedRole === true ? undefined : 'red' }) : null,
      db ? kv('coordinationGuard', formatBool(db.coordinationGuard), { tone: db.coordinationGuard === true ? undefined : 'red' }) : null,
      sv.deploymentExecutionEnabled === undefined
        ? null
        : kv('節點部署執行', sv.deploymentExecutionEnabled === false ? 'false · 已鎖定' : `${show(sv.deploymentExecutionEnabled)}（異常）`, {
            tone: sv.deploymentExecutionEnabled === false ? undefined : 'red',
          }),
    ]),
  );
}

function siteCard(sv, now) {
  const titleId = `site-title-${sv.id}`;
  const stale = sv.effectiveStatus === 'STALE';
  const alerts = siteAlerts(sv);
  const channels = channelSummary(sv.channels);
  return h(
    'article',
    { class: `site-card${stale ? ' is-stale' : ''}`, 'aria-labelledby': titleId },
    h(
      'header',
      { class: 'site-head' },
      h('div', { class: 'site-title' }, h('h3', { id: titleId }, siteLabel(sv.id)), sv.mode ? h('span', { class: 'site-mode mono' }, sv.mode) : null),
      siteStatusBadge(sv),
    ),
    sv.effectiveStatus === 'OBSERVED'
      ? note('OBSERVED 只代表資料庫身份、V35 與協調守衛已核對，不等於站點健康、儲存健康或同步收斂。')
      : null,
    sv.present
      ? h(
          'div',
          { class: 'stat-row' },
          stat('最後觀測', sv.observedAt ? formatAge(now - parseTime(sv.observedAt)) : '未提供', {
            tone: sv.lagExceeded === true || stale ? 'amber' : undefined,
            hint: sv.observedAt ? formatTime(sv.observedAt) : undefined,
          }),
          checkStat('就緒檢查', sv.readiness),
          checkStat('儲存可達', sv.storageReachability),
          stat('通道', channels.total ? `${channels.forwarding} 放行 / ${channels.blocked} 阻斷` : '無', {
            tone: channels.blocked ? 'amber' : undefined,
          }),
        )
      : sv.effectiveStatus === 'LOADING'
        ? emptyState('讀取中…')
        : null,
    alerts.length
      ? h(
          'ul',
          { class: 'alerts', 'aria-label': `${siteLabel(sv.id)} 重要提醒` },
          alerts.map((a) => h('li', { class: `alert alert-${a.tone}` }, a.text)),
        )
      : null,
    sv.database ? queueSection(sv) : null,
    sv.present ? channelSection(sv, now) : null,
    sv.present ? identityDisclosure(sv, now) : null,
  );
}

export function renderSites(el, sitesView, now) {
  replaceKeepingDisclosures(el, SITE_IDS.map((id) => siteCard(sitesView.sites[id], now)));
}

// ---------- GC 對照 ----------

function gcSide(entry, available, now) {
  if (!available) return h('td', { class: 'gc-side muted' }, '此站無資料庫資料');
  if (!entry) return h('td', { class: 'gc-side muted' }, '未在此站最近清單');
  return h(
    'td',
    { class: 'gc-side' },
    h('div', { class: 'badge-line' }, badge(show(entry.role), 'outline'), badge(show(entry.state), 'neutral')),
    h('div', { class: 'cell-sub mono' }, `peer_prepared=${formatBool(entry.peer_prepared)} · peer_deleted=${formatBool(entry.peer_deleted)}`),
    h('div', { class: 'cell-sub' }, `更新 ${entry.updated_at ? formatTimeWithAge(entry.updated_at, now) : '（未提供）'}`),
  );
}

function gcNotes(row) {
  const notes = [];
  if (row.presence === 'BOTH') notes.push('兩站皆有回報');
  else notes.push(row.presence === 'ONLY_CAMPUS' ? '僅 campus 回報' : '僅 external 回報');
  if (row.stateDiffers) notes.push('兩站狀態不同');
  if (row.sameRole) notes.push('兩站角色相同，請人工確認');
  if (row.identityMismatch) notes.push('file_id／revision 不一致');
  return notes.join('；');
}

export function renderGc(el, gc, now) {
  const anyData = SITE_IDS.some((id) => gc.availability[id]);
  const summary = gcSummary(gc);
  const head = h(
    'thead',
    null,
    h('tr', null, h('th', { scope: 'col' }, '操作 operation_id'), h('th', { scope: 'col' }, '檔案 · 修訂'), SITE_IDS.map((id) => h('th', { scope: 'col' }, siteLabel(id))), h('th', { scope: 'col' }, '對照')),
  );
  const body = h(
    'tbody',
    null,
    gc.rows.map((row) => {
      const ref = row.sides.campus ?? row.sides.external;
      return h(
        'tr',
        null,
        h('td', { class: 'mono', title: row.operationId ?? '' }, row.operationId ? shortId(row.operationId, 13) : '（無）'),
        h('td', { class: 'mono', title: String(ref?.file_id ?? '') }, `${shortId(ref?.file_id, 13)} · r${show(ref?.revision)}`),
        SITE_IDS.map((id) => gcSide(row.sides[id], gc.availability[id], now)),
        h('td', { class: 'cell-note' }, gcNotes(row)),
      );
    }),
  );
  replace(
    el,
    gc.stale ? note('站點資料已失效：以下 GC 對照為最後已知回報。', 'red') : null,
    anyData && gc.rows.length
      ? h(
          'ul',
          { class: 'chip-row', 'aria-label': 'GC 對照摘要' },
          h('li', { class: 'chip' }, h('span', null, '操作'), h('span', { class: 'mono num' }, String(summary.total))),
          h('li', { class: 'chip' }, h('span', null, '兩站皆有'), h('span', { class: 'mono num' }, String(summary.both))),
          h('li', { class: `chip${summary.oneSided ? ' chip-amber' : ''}` }, h('span', null, '單邊回報'), h('span', { class: 'mono num' }, String(summary.oneSided))),
          h('li', { class: `chip${summary.stateDiffers ? ' chip-amber' : ''}` }, h('span', null, '狀態不同'), h('span', { class: 'mono num' }, String(summary.stateDiffers))),
        )
      : null,
    !anyData
      ? emptyState('兩站皆沒有可用的 recentGc 資料（未觀測或資料庫查詢失敗）。')
      : gc.rows.length === 0
        ? emptyState('兩站的 recentGc 皆為空。')
        : h('div', { class: 'table-wrap', role: 'region', 'aria-label': 'GC 協調操作對照表', tabindex: '0' }, h('table', { class: 'table' }, head, body)),
  );
}

// ---------- 驗收任務 ----------

function runStateBadge(state) {
  const tone = {
    QUEUED: 'amber',
    RUNNING: 'teal',
    COMMAND_SUCCEEDED: 'outline',
    FAILED: 'red',
    CANCELLED: 'neutral',
    INTERRUPTED: 'amber',
  }[state] ?? 'neutral';
  return badge(label('runState', state), tone);
}

const suiteRowCache = new WeakMap();

export function resetSuites(container) {
  suiteRowCache.delete(container);
  container.replaceChildren();
}

// 以套件 ID 為鍵就地更新，避免每次輪詢重建按鈕而失去鍵盤焦點。
export function syncSuites(container, suites, blockersFor, onStart, idPrefix = 'suite') {
  const keys = suites.map((s) => s.id).join('\u0000');
  let cache = suiteRowCache.get(container);
  if (!cache || cache.keys !== keys) {
    const rows = new Map();
    const nodes = suites.map((suite, index) => {
      const reasonsId = `${idPrefix}-reasons-${index}`;
      const nameId = `${idPrefix}-name-${index}`;
      const button = h('button', { type: 'button', class: 'btn btn-primary btn-sm', 'aria-describedby': reasonsId, 'aria-labelledby': `${nameId}-action ${nameId}` }, h('span', { id: `${nameId}-action` }, '執行'));
      button.addEventListener('click', () => onStart(suite.id));
      const status = h('div', { class: 'suite-status' });
      const reasons = h('ul', { class: 'list list-amber', id: reasonsId });
      const row = h(
        'li',
        { class: 'suite' },
        h('div', { class: 'suite-main' }, h('span', { class: 'suite-name mono', id: nameId }, suite.id), status),
        h('div', { class: 'suite-actions' }, button),
        reasons,
      );
      rows.set(suite.id, { button, status, reasons });
      return row;
    });
    if (suites.length === 0) container.replaceChildren(h('li', { class: 'empty' }, '伺服器未回傳任何套件。'));
    else container.replaceChildren(...nodes);
    cache = { keys, rows };
    suiteRowCache.set(container, cache);
  }
  for (const suite of suites) {
    const row = cache.rows.get(suite.id);
    if (!row) continue;
    const reasons = blockersFor(suite);
    replace(
      row.status,
      h('span', { class: 'mono' }, show(suite.scope)),
      suite.enabled ? badge('已啟用', 'outline') : badge('未啟用', 'amber'),
      SUITE_SCOPES[suite.id] ? null : badge('非契約套件', 'red'),
    );
    renderList(row.reasons, reasons, 'amber');
    row.button.disabled = reasons.length > 0;
  }
}

export function renderActiveRun(el, entry, freshness, now) {
  el.dataset.state = entry ? 'active' : freshness === 'FRESH' ? 'idle' : 'unknown';
  if (!entry) {
    replace(
      el,
      freshness === 'FRESH'
        ? h('p', { class: 'active-empty' }, '目前沒有排隊或執行中的任務。')
        : note('任務清單已失效，無法確認目前是否有任務在執行。', 'amber'),
    );
    return;
  }
  const run = entry.run;
  replace(
    el,
    h('div', { class: 'run-head' }, h('span', { class: 'run-suite mono' }, show(run.suite)), runStateBadge(run.state)),
    freshness === 'FRESH' && entry.source === 'list'
      ? null
      : note(
          entry.source === 'lookup'
            ? `此為 ${entry.fetchedAt ? formatTime(entry.fetchedAt) : '先前'} 查詢的快照，可能已變化。`
            : '任務清單已失效：此為最後已知狀態，可能已變化。',
          'amber',
        ),
    dl([
      kv('任務 ID', run.id, { mono: true }),
      kv('範圍', show(run.scope), { mono: true }),
      kv('建立', run.createdAt ? formatTimeWithAge(run.createdAt, now) : '（未提供）'),
      kv('遠端驗收', label('remoteAcceptance', run.remoteAcceptance), { tone: 'amber' }),
    ]),
  );
}

export function renderRunHistory(el, entries, freshness, resource, now) {
  const rows = entries.map(({ run, source, error }) => {
    const notes = runNotes(run);
    if (error) notes.push(`最後一次重新查詢失敗：${describeError(error)}`);
    return h(
      'tr',
      null,
      h('td', null, runStateBadge(run.state)),
      h('td', { class: 'mono' }, show(run.suite)),
      h('td', { class: 'cell-time' }, run.createdAt ? formatTime(run.createdAt) : '—'),
      h('td', { class: 'cell-time' }, run.completedAt ? formatTime(run.completedAt) : '—'),
      h('td', { class: 'mono' }, isMissing(run.exitCode) ? '—' : String(run.exitCode)),
      h('td', { class: 'mono', title: run.id }, shortId(run.id)),
      h('td', { class: 'cell-sub' }, source === 'list' ? '伺服器清單' : 'ID 查詢'),
      h('td', { class: 'cell-note' }, notes.length ? h('ul', { class: 'plain' }, notes.map((n) => h('li', null, n))) : '—'),
    );
  });
  replace(
    el,
    freshnessLine(freshness, resource, now),
    entries.length === 0
      ? emptyState('尚無任務紀錄。清單含目前任務與最多 1000 筆本機持久歷史；更早的任務可依 ID 查詢。')
      : h(
          'div',
          { class: 'table-wrap', role: 'region', 'aria-label': '任務紀錄', tabindex: '0' },
          h(
            'table',
            { class: 'table' },
            h('thead', null, h('tr', null, ['狀態', '套件', '建立', '完成', 'exitCode', 'ID', '來源', '說明'].map((t) => h('th', { scope: 'col' }, t)))),
            h('tbody', null, rows),
          ),
        ),
  );
}

// ---------- 部署預覽 ----------

export function renderReleaseDetails(el, release) {
  if (!release) {
    replace(el, emptyState('沒有可選擇的制品。'));
    return;
  }
  replace(
    el,
    dl([
      kv('sha256', show(release.sha256), { mono: true }),
      kv('acceptanceEvidenceId', show(release.acceptanceEvidenceId), { mono: true, tone: isMissing(release.acceptanceEvidenceId) ? 'amber' : undefined }),
    ], 'kv kv-stack'),
  );
}

function displayItemList(items, cls) {
  return h(
    'ol',
    { class: cls },
    items.map((item) =>
      h(
        'li',
        null,
        h('span', { class: 'item-title' }, item.title),
        item.details.length ? dl(item.details.map(([k, v]) => kv(k, v, { mono: true })), 'kv kv-compact') : null,
      ),
    ),
  );
}

function observationSnapshot(observations, now) {
  if (!isPlainObject(observations)) return emptyState('計畫未包含觀測快照。');
  const sites = isPlainObject(observations.sites) ? observations.sites : {};
  return dl([
    kv('配對狀態', label('pairStatus', observations.pairStatus), { tone: observations.pairStatus === 'VERIFIED_DISTINCT' ? undefined : 'amber' }),
    kv('快照時間', observations.observedAt ? formatTimeWithAge(observations.observedAt, now) : '（未提供）'),
    SITE_IDS.map((id) => {
      const s = isPlainObject(sites[id]) ? sites[id] : null;
      const text = s ? `${label('observationStatus', s.observationStatus)}${s.reason ? ` · ${s.reason}` : ''}` : '未回報';
      return kv(siteLabel(id), text, { tone: s?.observationStatus === 'OBSERVED' ? undefined : 'amber' });
    }),
  ]);
}

export function renderPlan(el, plan, { releases, now, error, receivedAt }) {
  if (!plan) {
    replaceKeepingDisclosures(
      el,
      error ? renderErrorNote(error) : null,
      emptyState('尚未建立預覽計畫。建立計畫只產生預覽，不會部署。'),
    );
    return;
  }
  const sha = planShaStatus(plan, releases);
  const blockers = toDisplayItems(plan.blockers);
  const steps = toDisplayItems(plan.steps);
  replaceKeepingDisclosures(
    el,
    error ? renderErrorNote(error) : null,
    h(
      'div',
      { class: 'plan-head' },
      h('div', { class: 'plan-title' }, h('span', { class: 'plan-release mono' }, show(plan.releaseId)), h('span', { class: 'plan-id mono' }, show(plan.id))),
      badge(label('planState', plan.state), plan.state === 'PREVIEW_ONLY' ? 'outline' : 'red'),
    ),
    h(
      'div',
      { class: 'stat-row' },
      stat('SHA-256 對照', sha === 'MATCH' ? '一致' : sha === 'MISMATCH' ? '不一致' : '無法對照', {
        tone: sha === 'MATCH' ? 'teal' : sha === 'MISMATCH' ? 'red' : 'amber',
        hint: '與 /releases 回報值比對',
      }),
      stat('目標站點', Array.isArray(plan.sites) ? plan.sites.map(String).join('、') : show(plan.sites), { mono: true }),
      stat('executionEnabled', planExecutionStat(plan.executionEnabled).text, { tone: planExecutionStat(plan.executionEnabled).tone, mono: true }),
      stat('建立時間', plan.createdAt ? formatAge(now - parseTime(plan.createdAt)) : '未提供', { hint: plan.createdAt ? formatTime(plan.createdAt) : undefined }),
    ),
    dl([kv('artifactSha256', show(plan.artifactSha256), { mono: true }), kv('本頁讀取', receivedAt ? formatTimeWithAge(receivedAt, now) : '—')], 'kv kv-stack'),
    h(
      'section',
      { class: 'subsection' },
      groupTitle(`阻擋項（${blockers.length}）`),
      blockers.length ? displayItemList(blockers, 'blockers') : note('未列出阻擋項；執行前仍會重新核對部署條件。', 'amber'),
    ),
    disclosure(
      'plan-steps',
      `未來部署步驟預覽（${steps.length}）· 僅預覽，未執行`,
      [
        note('以下步驟只是預覽：未執行 Compose 渲染、遠端制品傳輸、備份或遷移檢查。', 'amber'),
        steps.length ? displayItemList(steps, 'steps') : emptyState('API 未回傳步驟。'),
      ],
    ),
    disclosure('plan-observations', '建立計畫時的觀測快照（不會自動更新）', observationSnapshot(plan.observations, now)),
  );
}

function renderErrorNote(error) {
  return note(describeError(error), 'red');
}

// ---------- 稽核 ----------

export function renderAudit(el, entries, freshness, resource, now) {
  replace(
    el,
    freshnessLine(freshness, resource, now),
    entries.length === 0
      ? emptyState('沒有稽核紀錄。')
      : h(
          'div',
          { class: 'table-wrap', role: 'region', 'aria-label': '操作稽核', tabindex: '0' },
          h(
            'table',
            { class: 'table' },
            h('thead', null, h('tr', null, ['時間', '結果', '動作', '目標', '請求時限', '操作者', '其他細節', 'requestId'].map((t) => h('th', { scope: 'col' }, t)))),
            h(
              'tbody',
              null,
              entries.map((e) => {
                const details = auditDetails(e);
                return h(
                  'tr',
                  null,
                  h('td', { class: 'cell-time' }, e.at ? formatTime(e.at) : '—'),
                  h('td', null, badge(show(e.outcome), auditOutcomeTone(e.outcome))),
                  h('td', { class: 'mono', translate: 'no' }, show(e.action)),
                  h('td', { class: 'mono', translate: 'no' }, show(e.target)),
                  h('td', { class: 'mono' }, details.seconds === null ? '—' : `${details.seconds} 秒`),
                  h('td', { class: 'mono', translate: 'no' }, show(e.actor)),
                  h('td', { class: 'mono cell-note', translate: 'no' }, details.extra.length ? details.extra.map(([k, v]) => `${k}=${v}`).join('；') : '—'),
                  h('td', { class: 'mono cell-sub', title: String(e.requestId ?? '') }, shortId(e.requestId, 13)),
                );
              }),
            ),
          ),
        ),
  );
}
