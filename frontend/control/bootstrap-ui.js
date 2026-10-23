// 在新伺服器安裝中控與業務（伺服器頁內的獨立面板）：分步表單 → 真實預覽 → 逐項確認權限 → 明確批准。
// - 能力只以 GET /controller-plans 判斷；404 說明未啟用，不改走單獨業務安裝。
// - 每次「建立預覽」使用新的 UUID；結果未知時保留 UUID，只能「查詢原預覽」，不自動換 UUID 重送。
// - 儲存密鑰只在建立預覽的那一次請求中送出，送出後立即清除；不寫入任何儲存、網址、記錄或畫面。
// - 只批准與預覽完全相同的 requestHash 與權限；修改任何輸入後原預覽作廢。不自動勾選權限、不自動批准、不自動重送。
// - 切換頁面、登出時丟棄遲到回應；批准成功只代表排隊，安裝在中控背景執行。

import { h, replace } from './dom.js';
import { describeError, formatTime } from './format.js';
import { randomUuid } from './cluster-state.js';
import * as ss from './servers-state.js';
import * as ts from './tasks-state.js';
import * as bs from './bootstrap-state.js';

export function mountBootstrap({ client, session, confirmDialog, onAuthFailure, getLeadership, openControllerJob }) {
  const $ = (id) => document.getElementById(id);
  const els = {
    open: $('bootstrap-open'), panel: $('bootstrap-panel'), status: $('bootstrap-status'), form: $('bootstrap-form'),
    source: $('bootstrap-source'), target: $('bootstrap-target'), release: $('bootstrap-release'), slot: $('bootstrap-slot'),
    sourceAddress: $('bootstrap-source-address'), targetAddress: $('bootstrap-target-address'), endpoint: $('bootstrap-endpoint'),
    wgPort: $('bootstrap-wg-port'), httpPort: $('bootstrap-http-port'), appUrl: $('bootstrap-app-url'),
    custom: $('bootstrap-storage-custom'), storageHelp: $('bootstrap-storage-help'), provider: $('bootstrap-provider'), s3Endpoint: $('bootstrap-s3-endpoint'),
    region: $('bootstrap-region'), bucket: $('bootstrap-bucket'), accessKey: $('bootstrap-access-key'), secretKey: $('bootstrap-secret-key'),
    pathStyle: $('bootstrap-path-style'), publicUrl: $('bootstrap-public-url'), cdn: $('bootstrap-cdn'),
    problems: $('bootstrap-problems'), preview: $('bootstrap-preview'), lookup: $('bootstrap-lookup'), close: $('bootstrap-close'),
    previewView: $('bootstrap-preview-view'), permissions: $('bootstrap-permissions'), permissionList: $('bootstrap-permission-list'),
    approve: $('bootstrap-approve'), jobLookup: $('bootstrap-job-lookup'), result: $('bootstrap-result'),
  };

  let gen = 0; // 頁面／登入世代：離頁、登出、關閉面板時遞增，丟棄遲到回應
  let onPage = false;
  let open = false;
  let caps = null; // { loading } | normalizeCapabilities 結果 | { notEnabled } | { error }
  let list = null; // normalizeServerList
  let listError = null;
  let busy = null; // 'preview' | 'lookup' | 'approve' | 'job'
  let sent = null; // 最近一次送出的非秘密摘要（含 UUID）
  let pendingId = null; // 預覽結果未知時保留的 UUID
  let preview = null; // 正規化後的預覽
  let previewSig = null; // 送出預覽時的表單意圖簽章
  let confirmed = new Set(); // 使用者逐項確認的權限
  let approveUnknown = null; // 批准結果未知時的 UUID
  let queued = null; // 已排隊的任務
  let dirty = false;

  const raw = (text) => h('span', { class: 'mono', translate: 'no' }, text);
  const status = (tone, text) => h('span', { class: `status status-${tone}` }, h('span', { class: `dot dot-${tone}`, 'aria-hidden': 'true' }), text);
  const kv = (rows) => h('dl', { class: 'kv' }, rows.filter(Boolean).map(([k, v]) => [h('dt', null, k), h('dd', null, v)]));
  const isAuth = (err) => err?.status === 401 || err?.status === 403;
  const codeOf = (err) => (typeof err?.code === 'string' ? err.code : null);
  const codeText = (c) => bs.bootstrapCodeText(c) ?? ts.controllerCodeText(c) ?? ss.serverCodeText(c);

  function message(tone, text, lines = []) {
    els.result.hidden = !text;
    els.result.className = `message message-${tone}`;
    if (!text) { els.result.replaceChildren(); return; }
    replace(els.result, h('span', { class: 'message-title' }, text), lines.length ? h('ul', { class: 'list' }, lines.map((l) => h('li', null, l))) : null);
  }

  function failureLines(err) {
    const c = codeOf(err);
    return [codeText(c) ?? describeError(err), c ? `代碼：${c}` : null].filter(Boolean);
  }

  function gateReasons() {
    const reasons = [];
    if (!session.isActive()) reasons.push('尚未登入。');
    if (!list) reasons.push(listError ? `無法讀取伺服器清單（${describeError(listError)}）。` : '伺服器清單尚未讀取。');
    else if (!list.canManage) reasons.push(ss.gateReasonText(list.reason));
    const lead = getLeadership?.();
    if (lead && !lead.writable) reasons.push(...(lead.reasons.length ? lead.reasons : ['主中控狀態未確認，管理寫入已停用。']));
    return reasons;
  }

  function clearSecrets() {
    els.accessKey.value = '';
    els.secretKey.value = '';
  }

  function readForm() {
    return {
      sourceId: els.source.value, targetId: els.target.value, releaseId: els.release.value, allocationSlot: els.slot.value.trim(),
      sourceAddress: els.sourceAddress.value.trim(), targetAddress: els.targetAddress.value.trim(), targetEndpoint: els.endpoint.value.trim(),
      wireguardPort: els.wgPort.value.trim(), httpPort: els.httpPort.value.trim(), appBaseUrl: els.appUrl.value.trim(),
      storageMode: els.form.querySelector('input[name="bootstrap-storage"]:checked')?.value ?? '',
      provider: els.provider.value, endpoint: els.s3Endpoint.value.trim(), region: els.region.value.trim(), bucket: els.bucket.value.trim(),
      accessKey: els.accessKey.value, secretKey: els.secretKey.value, pathStyle: els.pathStyle.checked,
      publicBaseUrl: els.publicUrl.value.trim(), cdnEndpoint: els.cdn.value.trim(),
    };
  }

  function discardPreview(reason) {
    if (!preview && !queued) return;
    preview = null; previewSig = null; confirmed = new Set(); approveUnknown = null;
    if (reason) message('amber', reason);
  }

  // ---------- 讀取 ----------

  async function loadAll() {
    const g = gen;
    caps = { loading: true };
    render();
    const [c, l] = await Promise.allSettled([client.getControllerCapabilities(), client.listServers()]);
    if (g !== gen) return;
    for (const r of [c, l]) if (r.status === 'rejected' && isAuth(r.reason)) { onAuthFailure(r.reason); return; }
    if (c.status === 'fulfilled') caps = bs.normalizeCapabilities(c.value);
    else caps = c.reason?.status === 404 ? { notEnabled: true } : { error: c.reason };
    if (l.status === 'fulfilled') { list = ss.normalizeServerList(l.value); listError = list ? null : { kind: 'protocol', serverMessage: '伺服器清單格式無效' }; }
    else { list = null; listError = l.reason; }
    fillSelects();
    render();
  }

  function fillSelects() {
    const { sources, targets } = bs.serverCandidates(list);
    const keep = (sel, options, empty) => {
      const prev = sel.value;
      replace(sel, h('option', { value: '' }, options.length ? '請選擇' : empty), options.map(([v, t]) => h('option', { value: v }, t)));
      sel.value = options.some(([v]) => v === prev) ? prev : '';
    };
    keep(els.source, sources.map((s) => [s.id, `${s.name} · ${ss.sshTarget(s)} · revision ${s.revision} · ${bs.sourceNote(s)}`]), '沒有已登記的伺服器');
    keep(els.target, targets.map((s) => [s.id, `${s.name} · ${ss.sshTarget(s)} · revision ${s.revision}`]), '沒有尚未安裝的目標伺服器');
    keep(els.release, (list?.releases ?? []).map((r) => [r.id, r.id]), '沒有可用的業務版本');
  }

  // ---------- 建立預覽 ----------

  async function createPreview(event) {
    event?.preventDefault();
    dirty = true;
    if (busy || pendingId || !caps?.canPlan || gateReasons().length) { render(); return; }
    const form = readForm();
    const id = randomUuid();
    const body = bs.planRequestBody(form, { id, list, capabilities: caps });
    if (!body) { render(); return; }
    const g = gen;
    discardPreview();
    queued = null;
    sent = {
      id, sourceServerId: body.sourceServerId, sourceRevision: body.sourceRevision, targetServerId: body.targetServerId, targetRevision: body.targetRevision,
      releaseId: body.releaseId, allocationSlot: body.allocationSlot,
      review: { sourceAddress: body.sourceAddress, targetAddress: body.targetAddress, targetEndpoint: body.targetEndpoint, wireguardPort: body.wireguardPort, httpPort: body.httpPort, appBaseUrl: body.appBaseUrl },
      storageMode: form.storageMode, storage: bs.storageSummary(form),
    };
    const sig = bs.intentSignature(form, list);
    // 密鑰只存在這一次請求本文中：送出前清除輸入欄。
    clearSecrets();
    busy = 'preview';
    message('neutral', '正在建立預覽（後端會做較慢的唯讀檢查，最多約 2 分鐘）…', [`預覽 UUID：${id}`]);
    render();
    try {
      const res = await client.createControllerPlan(body);
      if (g !== gen) return;
      acceptPreview(res, id, sig);
    } catch (err) {
      if (g !== gen) return;
      if (isAuth(err)) { onAuthFailure(err); return; }
      if (err?.outcomeUnknown || err?.status >= 500) {
        pendingId = id;
        previewSig = sig;
        message('amber', '建立預覽的結果未知（逾時、網路中斷或伺服器未回應）。已保留這個 UUID，請按「查詢原預覽」；不會自動換 UUID 重送。', [`預覽 UUID：${id}`, ...failureLines(err)]);
      } else {
        sent = null;
        message('red', '建立預覽被拒絕。', failureLines(err));
      }
    } finally {
      body.storage && (body.storage.accessKey = '', body.storage.secretKey = '');
      if (g === gen) { busy = null; render(); }
    }
  }

  function acceptPreview(res, id, sig) {
    const p = bs.normalizePreview(res, id);
    if (!p) { message('red', '預覽回應格式無效或與請求的 UUID 不符；不能批准。', [`預覽 UUID：${id}`]); return; }
    pendingId = null;
    if (p.state !== 'PREVIEW_ONLY') {
      // 同一 UUID 已被接受：顯示既有任務，不再批准。
      preview = null;
      queued = p;
      message('amber', '這個 UUID 已經被批准並排入背景執行；以下只顯示任務狀態。', [`任務 UUID：${p.id}`]);
      return;
    }
    preview = p;
    previewSig = sig;
    confirmed = new Set();
    const problems = bs.previewProblems(p, sent);
    message(problems.length ? 'amber' : 'teal', problems.length ? '已取得預覽，但有不能批准的問題。' : '已取得預覽。請核對內容並逐項確認權限。', problems);
  }

  async function lookupPreview() {
    if (busy || !pendingId || !session.isActive()) return;
    const id = pendingId;
    const g = gen;
    busy = 'lookup';
    render();
    try {
      const res = await client.getControllerPlan(id);
      if (g !== gen) return;
      acceptPreview(res, id, previewSig);
    } catch (err) {
      if (g !== gen) return;
      if (isAuth(err)) { onAuthFailure(err); return; }
      if (err?.status === 404) {
        pendingId = null; sent = null; previewSig = null;
        message('amber', '中控沒有這個預覽：建立請求可能沒有送達。可以重新建立預覽（會使用新的 UUID；自訂儲存需要重新輸入密鑰）。', [`原 UUID：${id}`, ...failureLines(err)]);
      } else {
        message('amber', '查詢原預覽的結果仍未知；UUID 繼續保留，稍後再查詢。', [`預覽 UUID：${id}`, ...failureLines(err)]);
      }
    } finally {
      if (g === gen) { busy = null; render(); }
    }
  }

  // ---------- 批准 ----------

  function approveProblems() {
    const problems = [...gateReasons()];
    if (!preview) { problems.push('沒有可批准的預覽。'); return problems; }
    problems.push(...bs.previewProblems(preview, sent));
    if (bs.intentSignature(readForm(), list) !== previewSig) problems.push('輸入已在預覽後改變；請重新建立預覽。');
    if (!bs.approvalBody(preview, confirmed)) problems.push('請逐項確認全部權限。');
    if (approveUnknown) problems.push('上一次批准的結果未知；請先查詢任務。');
    return problems;
  }

  async function approve() {
    if (busy || approveProblems().length) { render(); return; }
    const captured = preview;
    const body = bs.approvalBody(captured, confirmed);
    const g = gen;
    busy = 'approve';
    render();
    const src = list?.servers.find((s) => s.id === captured.sourceServerId);
    const dst = list?.servers.find((s) => s.id === captured.serverId);
    try {
      const yes = await confirmDialog({
        title: '批准在新伺服器安裝中控與業務',
        lines: [
          `來源：${src?.name ?? captured.sourceServerId}（revision ${captured.sourceRevision}）`,
          `目標：${dst?.name ?? captured.serverId}（revision ${captured.serverRevision}）`,
          `業務版本：${captured.releaseId}；分配槽位 ${captured.allocationSlot}`,
          `已逐項確認 ${body.permissions.length} 項權限；只批准這份預覽（requestHash ${captured.requestHash.slice(0, 12)}…）。`,
          '批准成功只代表已排隊：安裝由中控在背景執行，可以關閉此頁；完成與否以任務詳情為準。',
          '結果不確定時只以原 UUID 查詢，不會自動重送。',
        ],
        confirmLabel: '批准並排隊',
      });
      if (!yes || g !== gen || !session.isActive()) return;
      // 確認後重新核對：伺服器清單、租約與原預覽（requestHash、權限、有效期）。
      const [l, fresh] = await Promise.all([client.listServers(), client.getControllerPlan(captured.id)]);
      if (g !== gen) return;
      list = ss.normalizeServerList(l) ?? list;
      const now = bs.normalizePreview(fresh, captured.id);
      const reasons = [...gateReasons()];
      if (!now) reasons.push('無法重新讀取原預覽。');
      else {
        reasons.push(...bs.previewProblems(now, sent));
        if (now.requestHash !== captured.requestHash) reasons.push('預覽的 requestHash 已改變。');
        if (JSON.stringify(now.permissionsRequired) !== JSON.stringify(captured.permissionsRequired)) reasons.push('預覽的權限清單已改變。');
        const s = list?.servers.find((x) => x.id === sent.sourceServerId), t = list?.servers.find((x) => x.id === sent.targetServerId);
        if (!s || s.revision !== sent.sourceRevision || !t || t.revision !== sent.targetRevision) reasons.push('來源或目標伺服器的登記已改變。');
      }
      if (bs.intentSignature(readForm(), list) !== previewSig) reasons.push('輸入已在預覽後改變。');
      if (reasons.length) { message('amber', '確認期間狀態已改變，未批准。', reasons); return; }
      approveUnknown = captured.id;
      const res = await client.executeControllerPlan(captured.id, body);
      if (g !== gen) return;
      approveUnknown = null;
      const job = ts.normalizeControllerJob(res, captured.id);
      queueAccepted(job ?? { id: captured.id, state: null });
    } catch (err) {
      if (g !== gen) return;
      if (isAuth(err)) { onAuthFailure(err); return; }
      if (approveUnknown && (err?.outcomeUnknown || err?.status >= 500)) {
        message('amber', '批准的結果未知（逾時、網路中斷或伺服器未回應）。不會重送；請按「查詢任務」以原 UUID 確認是否已排隊。', [`UUID：${approveUnknown}`, ...failureLines(err)]);
        return;
      }
      approveUnknown = null;
      const c = codeOf(err);
      if (['BOOTSTRAP_PLAN_CHANGED', 'BOOTSTRAP_PLAN_EXPIRED', 'IDEMPOTENCY_CONFLICT'].includes(c)) discardPreview();
      message('red', '批准被拒絕。', failureLines(err));
    } finally {
      if (g === gen) { busy = null; render(); }
    }
  }

  function queueAccepted(job) {
    preview = null; previewSig = null; confirmed = new Set();
    queued = job;
    message('teal', '已排入背景執行。安裝由中控在背景進行，可以關閉此頁；這不代表已完成。', [`任務 UUID：${job.id}`]);
  }

  async function lookupJob() {
    if (busy || !approveUnknown || !session.isActive()) return;
    const id = approveUnknown;
    const g = gen;
    busy = 'job';
    render();
    try {
      const job = ts.normalizeControllerJob(await client.getControllerJob(id), id);
      if (g !== gen) return;
      approveUnknown = null;
      if (!job) { message('red', '任務回應格式無效。', [`UUID：${id}`]); return; }
      queueAccepted(job);
    } catch (err) {
      if (g !== gen) return;
      if (isAuth(err)) { onAuthFailure(err); return; }
      if (err?.status === 404) {
        approveUnknown = null;
        confirmed = new Set();
        message('amber', '中控沒有這個任務：批准可能沒有送達。若預覽仍有效，可重新逐項確認權限後再批准同一份預覽（不會建立新的 UUID）。', [`UUID：${id}`, ...failureLines(err)]);
      } else {
        message('amber', '查詢任務的結果仍未知；稍後再查詢。', [`UUID：${id}`, ...failureLines(err)]);
      }
    } finally {
      if (g === gen) { busy = null; render(); }
    }
  }

  // ---------- 畫面 ----------

  function renderStatus() {
    let tone = 'neutral', text = '';
    if (!caps || caps.loading) text = '正在讀取組合安裝能力…';
    else if (caps.notEnabled) { tone = 'amber'; text = '這個中控後端未啟用「在新伺服器安裝中控與業務」（舊後端或模組未開啟）。請改用伺服器卡片上原有的單獨業務安裝；本頁不會自動改用其他安裝方式。'; }
    else if (caps.error) { tone = 'red'; text = `無法讀取組合安裝能力：${describeError(caps.error)}。狀態未知，已停用。`; }
    else if (!caps.available) { tone = 'amber'; text = `${caps.problem} 已停用。`; }
    else if (!caps.canPlan) { tone = 'amber'; text = `目前不能建立組合安裝：${codeText(caps.reason) ?? '後端回報不允許'}${caps.reason ? `（${caps.reason}）` : ''}。`; }
    else text = `可以建立預覽；預覽有效 ${caps.planTtlSeconds ? Math.round(caps.planTtlSeconds / 60) : '—'} 分鐘${caps.backgroundExecution ? '，批准後在中控背景執行' : ''}。`;
    els.status.className = `message message-${tone}`;
    els.status.textContent = text;
  }

  function renderForm() {
    const form = readForm();
    const custom = form.storageMode === 'CUSTOM';
    els.custom.hidden = !custom;
    els.storageHelp.hidden = !custom;
    const r2 = form.provider === 'R2';
    els.region.disabled = r2 || Boolean(busy);
    if (r2) els.region.value = 'auto';
    const usable = caps?.available && caps.canPlan;
    for (const el of els.form.querySelectorAll('input, select')) el.disabled = !usable || Boolean(busy) || (el === els.region && r2);
    const check = bs.formProblems(form, { list, capabilities: usable ? caps : null });
    const problems = [...gateReasons(), ...(dirty ? check.errors : [])];
    if (pendingId) problems.unshift('上一次建立預覽的結果未知；請先查詢原預覽。');
    replace(els.problems, problems.map((p) => h('li', null, p)));
    els.problems.hidden = problems.length === 0;
    els.preview.disabled = !usable || Boolean(busy) || Boolean(pendingId) || check.errors.length > 0 || gateReasons().length > 0;
    els.preview.textContent = busy === 'preview' ? '建立預覽中…' : preview ? '重新建立預覽（新的 UUID）' : '建立預覽';
    els.lookup.hidden = !pendingId;
    els.lookup.disabled = Boolean(busy);
    els.lookup.textContent = busy === 'lookup' ? '查詢中…' : '查詢原預覽';
  }

  function serverLine(id, rev) {
    const s = list?.servers.find((x) => x.id === id);
    return h('span', null, raw(s ? `${s.name} · ${id}` : id ?? '—'), ` · revision ${rev ?? '—'}`);
  }

  function storageRows(st) {
    if (!st?.provided) return [['目標物件儲存', '預覽沒有提供儲存摘要']];
    if (!st.valid) return [['目標物件儲存', st.problems[0] ?? '格式無效']];
    return [
      ['儲存方式', st.mode ? h('span', null, `${ts.STORAGE_MODE_LABEL[st.mode]} `, raw(st.mode)) : '未知'],
      ['提供者', st.provider ? h('span', null, `${ts.STORAGE_PROVIDER_LABEL[st.provider]} `, raw(st.provider)) : '未知'],
      ['S3 API 地址', raw(st.endpoint ?? '未顯示')],
      ['桶／區域', raw(`${st.bucket ?? '—'} · ${st.region ?? '—'}`)],
      ['憑據', st.credentialsConfigured === true ? '已封存（不代表可連線或健康）' : st.credentialsConfigured === false ? '未封存' : '未知'],
    ];
  }

  function renderPreview() {
    const p = preview ?? queued;
    els.previewView.hidden = !p;
    if (!p) { els.previewView.replaceChildren(); return; }
    const isQueued = !preview;
    const problems = preview ? bs.previewProblems(preview, sent) : [];
    const d = isQueued ? ts.controllerJobDisplay(p) : null;
    const r = p.review ?? {};
    replace(els.previewView,
      h('div', { class: 'plan-head' }, h('h3', { class: 'sub-title' }, isQueued ? '組合安裝任務 ' : '組合安裝預覽 ', raw(p.id)),
        isQueued ? status(d.complete ? 'amber' : d.tone, d.complete ? '任務記錄回報完成（請在任務詳情核對）' : d.label) : status(problems.length ? 'amber' : 'teal', problems.length ? '不能批准' : '可確認權限')),
      problems.length ? h('ul', { class: 'list list-amber' }, problems.map((x) => h('li', null, x))) : null,
      kv([
        ['來源伺服器', serverLine(p.sourceServerId, p.sourceRevision)],
        ['目標伺服器', serverLine(p.serverId, p.serverRevision)],
        ['業務版本', h('span', null, raw(p.releaseId ?? '—'), ' · SHA-256 ', raw(p.artifactSha256 ?? '—'))],
        ['中控版本 SHA-256', raw(p.controllerSha256 ?? '—')],
        ['集群', h('span', null, raw(p.clusterId ?? '—'), ` · epoch ${p.epoch ?? '—'} · slot ${p.allocationSlot ?? '—'}`)],
        ['私網', raw(`${r.sourceAddress ?? '—'} → ${r.targetAddress ?? '—'}`)],
        ['WireGuard 入口', raw(`${r.targetEndpoint ?? '—'}:${r.wireguardPort ?? '—'}`)],
        ['業務', raw(`${r.appBaseUrl ?? '—'} · 端口 ${r.httpPort ?? '—'}`)],
        ...storageRows(p.storage),
        preview ? ['有效期限', p.expiresAt ? formatTime(p.expiresAt) : '未知'] : null,
        preview ? ['requestHash', raw(p.requestHash ?? '—')] : null,
        p.executionCode ? ['執行診斷碼', h('span', null, raw(p.executionCode), p.executionObservedAt ? ` · ${formatTime(p.executionObservedAt)}` : '', '（固定代碼，不代表原生成功或失敗）')] : null,
      ]),
      isQueued ? h('p', { class: 'note' }, '安裝在中控背景執行，可以關閉此頁。完成、失敗、結果未知或需要重新批准都以任務詳情為準。') : null,
      isQueued ? h('p', null, taskLink(p.id)) : null);
  }

  function taskLink(id) {
    const a = document.createElement('a');
    a.className = 'link';
    a.href = '#/tasks';
    a.dataset.controllerJob = id;
    a.textContent = '在任務歷史查看詳情 →';
    return a;
  }

  function renderPermissions() {
    const show = Boolean(preview);
    els.permissions.hidden = !show;
    if (!show) { els.permissionList.replaceChildren(); return; }
    const perms = preview.permissionsRequired ?? [];
    const key = JSON.stringify([preview.id, perms]);
    if (els.permissionList.dataset.key !== key) {
      els.permissionList.dataset.key = key;
      // 每份新預覽都從「全部未勾選」開始，不自動勾選。
      replace(els.permissionList, perms.map((code) => h('label', { class: 'confirm-check' },
        h('input', { type: 'checkbox', 'data-permission': code }),
        h('span', null, bs.permissionText(code), ' ', raw(code)))));
    }
    for (const box of els.permissionList.querySelectorAll('input[data-permission]')) {
      box.checked = confirmed.has(box.dataset.permission);
      box.disabled = Boolean(busy);
    }
  }

  function render() {
    els.panel.hidden = !open;
    els.open.disabled = !session.isActive();
    if (!open) return;
    renderStatus();
    renderForm();
    renderPreview();
    renderPermissions();
    const problems = preview ? approveProblems() : [];
    els.approve.hidden = !preview;
    els.approve.disabled = Boolean(busy) || problems.length > 0;
    els.approve.textContent = busy === 'approve' ? '批准中…' : '批准並排入背景執行…';
    els.jobLookup.hidden = !approveUnknown;
    els.jobLookup.disabled = Boolean(busy);
    els.jobLookup.textContent = busy === 'job' ? '查詢中…' : '查詢任務';
    els.close.disabled = busy === 'approve';
  }

  // ---------- 事件 ----------

  function openPanel() {
    if (!session.isActive()) return;
    open = true;
    gen += 1;
    busy = null;
    message('neutral', '');
    render();
    loadAll();
    els.panel.scrollIntoView?.({ block: 'start' });
  }

  // 建立預覽進行中被中斷（離頁、關閉）：回應會被丟棄，因此保留 UUID 為「結果未知」，之後只能查詢原預覽。
  function abandonInflight() {
    if (busy === 'preview' && sent) {
      pendingId = sent.id;
      message('amber', '建立預覽在完成前被中斷；已保留 UUID，請按「查詢原預覽」。', [`預覽 UUID：${sent.id}`]);
    }
  }

  function closePanel() {
    if (busy === 'approve') return;
    abandonInflight();
    gen += 1;
    busy = null;
    open = false;
    clearSecrets();
    render();
  }

  els.open.addEventListener('click', openPanel);
  els.close.addEventListener('click', closePanel);
  els.form.addEventListener('submit', createPreview);
  const onEdit = (event) => {
    if (event.target.closest?.('#bootstrap-permission-list')) return;
    dirty = true;
    if (event.target === els.provider) els.pathStyle.checked = els.provider.value !== 'R2';
    // 預覽後修改任何輸入：原預覽作廢，需要新的 UUID 與新的預覽。重新輸入密鑰也算修改（密鑰不進簽章，因此直接作廢）。
    const secretEdit = event.target === els.accessKey || event.target === els.secretKey;
    if (preview && (secretEdit || bs.intentSignature(readForm(), list) !== previewSig)) discardPreview('輸入已改變，原預覽作廢；請重新建立預覽（新的 UUID）。');
    render();
  };
  els.form.addEventListener('input', onEdit);
  els.form.addEventListener('change', onEdit);
  els.lookup.addEventListener('click', lookupPreview);
  els.approve.addEventListener('click', approve);
  els.jobLookup.addEventListener('click', lookupJob);
  els.permissionList.addEventListener('change', (event) => {
    const box = event.target.closest?.('input[data-permission]');
    if (!box || !preview) return;
    if (box.checked) confirmed.add(box.dataset.permission); else confirmed.delete(box.dataset.permission);
    render();
  });
  els.previewView.addEventListener('click', (event) => {
    const a = event.target.closest?.('a[data-controller-job]');
    if (!a || !ts.isTaskUuid(a.dataset.controllerJob)) return;
    openControllerJob?.(a.dataset.controllerJob);
  });

  render();

  return {
    enter() {
      onPage = true;
      render();
    },
    // 離頁：丟棄進行中的回應並清除密鑰；保留未知結果的 UUID，回來後可查詢。
    leave() {
      if (!onPage) return;
      onPage = false;
      abandonInflight();
      gen += 1;
      busy = null;
      clearSecrets();
      render();
    },
    // 登出：全部清除。
    reset() {
      gen += 1;
      onPage = false; open = false; busy = null;
      caps = null; list = null; listError = null; sent = null; pendingId = null; preview = null; previewSig = null;
      confirmed = new Set(); approveUnknown = null; queued = null; dirty = false;
      els.form.reset();
      clearSecrets();
      for (const sel of [els.source, els.target, els.release]) sel.replaceChildren();
      message('neutral', '');
      render();
    },
  };
}
