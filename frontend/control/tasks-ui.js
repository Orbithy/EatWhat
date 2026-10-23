// 任務歷史頁（#/tasks）：只讀列表、篩選、游標分頁，以及權威發布約束診斷。
// - 詳情連結只由「類型＋UUID」白名單組合，不使用回應中的 detailRoute。
// - 列表不核對、不重送；核對在既有詳情面板中確認後進行。
// - 同一頁的請求合併；篩選改變、離開頁面、登出時丟棄遲到回應。讀取失敗不顯示成空列表。

import { h, replace } from './dom.js';
import { describeError, formatTime, shortId } from './format.js';
import * as ts from './tasks-state.js';

export function mountTasks({ client, clusterClient, session, onAuthFailure, openDetail, confirmDialog }) {
  const $ = (id) => document.getElementById(id);
  const els = {
    fencesPanel: $('tasks-fences-panel'), fences: $('tasks-fences'), observed: $('tasks-observed'),
    filter: $('tasks-filter'), type: $('tasks-type'), state: $('tasks-state'), server: $('tasks-server'),
    status: $('tasks-status'), list: $('tasks-list'), more: $('tasks-more'), reload: $('tasks-reload'),
    openForm: $('tasks-open-form'), openType: $('tasks-open-type'), openId: $('tasks-open-id'),
    ctlPanel: $('tasks-controller-panel'), ctlView: $('tasks-controller-view'), ctlObserved: $('tasks-controller-observed'),
    ctlReload: $('tasks-controller-reload'), ctlClose: $('tasks-controller-close'),
  };

  let gen = 0; // 頁面／登入世代：離開頁面或登出時遞增
  let listGen = 0; // 列表世代：篩選或重新讀取時遞增
  let onPage = false;
  let items = [];
  let nextCursor = null;
  let observedAt = null;
  let loaded = false;
  let error = null;
  let moreError = null;
  let dropped = 0;
  let inflight = null; // { query, promise }
  let serverNames = new Map();
  let fences = null; // { loading, error, data, details: Map(jobId → 'exists'|'missing'|'error'|'loading') }
  let fenceGen = 0; // 權威診斷自己的請求世代：頁內重讀時遲到的列表與詳情回應一律丟棄
  // 中控與業務安裝任務的只讀詳情：{ id, loading, error, job, readAt }；切換 UUID、離頁、登出時遞增 ctlGen 丟棄遲到回應。
  let ctl = null;
  let ctlGen = 0;
  let pendingOpen = null; // 由其他頁面要求開啟的安裝任務 UUID：進入任務頁後才讀取，避免被 enter() 的世代遞增丟棄

  const raw = (text) => h('span', { class: 'mono', translate: 'no' }, text);
  const status = (tone, text) => h('span', { class: `status status-${tone}` }, h('span', { class: `dot dot-${tone}`, 'aria-hidden': 'true' }), text);
  const filters = () => ({ type: els.type.value, state: els.state.value, serverId: els.server.value });
  const isAuth = (err) => err?.status === 401 || err?.status === 403;

  function codeLine(code) {
    if (!code) return null;
    return h('span', { class: 'task-code' }, ts.taskCodeText(code) ?? '', ' ', raw(code));
  }

  function errorText(err) {
    const code = typeof err?.code === 'string' ? err.code : null;
    return [ts.taskCodeText(code) ?? describeError(err), code ? h('span', null, ' ', raw(code)) : null];
  }

  // 詳情連結：固定路由的錨點；點擊時先交給既有面板讀取，再由錨點切換頁面。
  function detailLink(type, id, text = '查看詳情') {
    const target = ts.taskDetailTarget(type, id);
    const href = ts.taskDetailHref(target);
    if (!target || !href) return h('span', { class: 'muted small' }, '無對應詳情');
    const a = document.createElement('a');
    a.className = 'link';
    a.href = href;
    a.dataset.taskType = type;
    a.dataset.taskId = id;
    a.textContent = `${text} →`;
    return a;
  }

  // ---------- 列表 ----------

  function fetchPage({ more = false } = {}) {
    if (!session.isActive()) return Promise.resolve();
    if (more && !nextCursor) return Promise.resolve();
    const query = ts.taskQuery({ ...filters(), cursor: more ? nextCursor : null });
    // 合併相同請求；也不在同一篩選下同時發兩頁。
    if (inflight && (inflight.query === query || more)) return inflight.promise;
    const g = gen;
    const lg = listGen;
    const stale = () => g !== gen || lg !== listGen;
    const promise = (async () => {
      try {
        const page = ts.normalizeTaskPage(await client.listTasks(query));
        if (stale()) return;
        if (!page) throw { kind: 'protocol', serverMessage: '任務列表格式無效' };
        items = more ? ts.mergeTasks(items, page.items) : page.items;
        dropped = (more ? dropped : 0) + page.dropped;
        nextCursor = page.nextCursor;
        observedAt = page.observedAt;
        loaded = true;
        if (more) moreError = null; else error = null;
      } catch (err) {
        if (stale()) return;
        if (isAuth(err)) { onAuthFailure(err); return; }
        if (more) moreError = err;
        else { error = err; items = []; nextCursor = null; loaded = false; }
      } finally {
        if (!stale()) { inflight = null; render(); }
      }
    })();
    inflight = { query, promise, more };
    render();
    return promise;
  }

  function clearList() {
    inflight = null;
    items = []; nextCursor = null; observedAt = null; loaded = false; error = null; moreError = null; dropped = 0;
  }

  function restart() {
    listGen += 1;
    clearList();
    render();
    fetchPage();
  }

  async function loadServers() {
    const g = gen;
    try {
      const res = await client.listServers();
      if (g !== gen || !res || !Array.isArray(res.servers)) return;
      serverNames = new Map(res.servers.filter((s) => s && ts.isTaskUuid(s.id) && typeof s.name === 'string').map((s) => [s.id, s.name]));
      const current = els.server.value;
      replace(els.server, h('option', { value: '' }, '全部伺服器'), [...serverNames].map(([id, name]) => h('option', { value: id }, `${name} · ${shortId(id, 8)}`)));
      els.server.value = serverNames.has(current) ? current : '';
      render();
    } catch (err) {
      if (g !== gen) return;
      if (isAuth(err)) onAuthFailure(err);
      // 伺服器名稱讀不到時只保留「全部伺服器」；不影響任務列表。
    }
  }

  // ---------- 權威發布約束 ----------

  async function loadFences() {
    if (!clusterClient) return;
    const g = gen;
    const fg = ++fenceGen;
    const stale = () => g !== gen || fg !== fenceGen;
    // 重讀期間不沿用舊的詳情結果，避免舊查詢寫入新狀態。
    fences = { loading: true, error: null, data: fences?.data ?? null, details: new Map() };
    render();
    try {
      const data = ts.normalizeFences(await clusterClient.getDeploymentFences());
      if (stale()) return;
      const current = { loading: false, error: null, data, details: new Map() };
      fences = current;
      render();
      for (const f of data.active) checkLocalDetail(f.jobId, current, fg);
    } catch (err) {
      if (stale()) return;
      if (isAuth(err)) { onAuthFailure(err); return; }
      fences = { loading: false, error: err, data: null, details: new Map() };
      render();
    }
  }

  // 只讀檢查本地是否還有這個部署任務的詳情；不核對、不改變約束。
  // owner：發出查詢時的 fences 物件；只有它仍是目前物件且世代未變時才寫入。
  async function checkLocalDetail(jobId, owner, fg) {
    const g = gen;
    const current = () => g === gen && fg === fenceGen && owner === fences;
    owner.details.set(jobId, 'loading');
    try {
      await client.getDeployment(jobId);
      if (!current()) return;
      owner.details.set(jobId, 'exists');
    } catch (err) {
      if (!current()) return;
      if (isAuth(err)) { onAuthFailure(err); return; }
      owner.details.set(jobId, err?.status === 404 ? 'missing' : 'error');
    } finally {
      if (current()) render();
    }
  }

  function renderFences() {
    els.fencesPanel.hidden = !clusterClient;
    if (!clusterClient) return;
    if (!fences || (fences.loading && !fences.data)) { replace(els.fences, h('p', { class: 'empty' }, '正在讀取權威發布約束…')); return; }
    if (fences.error) { replace(els.fences, h('p', { class: 'message message-red' }, '無法讀取權威發布約束：', ...errorText(fences.error), '。狀態未知，不代表沒有未結束的發布。')); return; }
    const d = fences.data;
    // 重讀期間保留上次結果時必須標示，舊的空列表不能寫成目前已確認沒有任務。
    const stale = fences.loading ? h('p', { class: 'message message-neutral' }, '正在重新讀取，以下為上次結果。') : null;
    if (!d.available) { replace(els.fences, stale, h('p', { class: 'message message-amber' }, ts.taskCodeText(d.reason) ?? '權威發布約束診斷不可用。', ' ', raw(d.reason))); return; }
    if (!d.active.length) { replace(els.fences, stale, h('p', { class: 'empty' }, stale ? '上次結果沒有未結束的約束；目前狀態尚未確認。' : '目前沒有未結束的權威發布約束。')); return; }
    replace(els.fences, stale, d.dropped ? h('p', { class: 'message message-amber' }, `${d.dropped} 筆約束記錄格式無效未顯示；診斷不完整，不能視為已列出全部未結束的發布。`) : null, h('ul', { class: 'task-list' }, d.active.map((f) => {
      const local = fences.details.get(f.jobId);
      return h('li', { class: 'task-row is-unresolved' },
        h('div', { class: 'task-head' }, h('span', { class: 'task-type' }, '權威發布'), status('red', '待核對'), raw(f.jobId)),
        h('div', { class: 'task-meta' },
          f.state ? h('span', null, '狀態 ', raw(f.state)) : null,
          h('span', null, `派發授權：${f.dispatchAuthorized === true ? '已授權' : f.dispatchAuthorized === false ? '未授權' : '未知'}`),
          f.epoch !== null ? h('span', null, `epoch ${f.epoch}`) : null,
          f.generation !== null ? h('span', null, `generation ${f.generation}`) : null,
          f.createdAt ? h('span', null, `建立 ${formatTime(f.createdAt)}`) : null),
        ts.taskCodeText(f.state) ? h('p', { class: 'hint' }, ts.taskCodeText(f.state)) : null,
        local === 'missing'
          ? h('p', { class: 'alert alert-amber' }, '本地沒有這個任務的詳情：請恢復原共享任務狀態後，再由目前主中控核對。不能以清除約束或建立新任務繞過。')
          : local === 'error' ? h('p', { class: 'hint' }, '暫時無法確認本地詳情是否存在。')
            : local === 'exists' ? h('p', null, detailLink('DEPLOYMENT', f.jobId, '查看詳情並核對')) : h('p', { class: 'hint' }, '正在確認本地詳情…'));
    })));
  }

  // ---------- 畫面 ----------

  function taskRow(t) {
    const d = ts.taskDisplay(t);
    const server = t.serverId ? serverNames.get(t.serverId) : null;
    return h('li', { class: `task-row${d.unresolved ? ' is-unresolved' : ''}` },
      h('div', { class: 'task-head' },
        h('span', { class: 'task-type' }, ts.TASK_TYPE_LABEL[t.type] ?? t.type),
        status(d.tone, d.label),
        raw(t.id)),
      h('div', { class: 'task-meta' },
        h('span', null, '狀態 ', raw(t.state)),
        t.stage ? h('span', null, '階段 ', raw(t.stage)) : null,
        t.serverId ? h('span', null, '伺服器 ', raw(server ? `${server} · ${shortId(t.serverId, 8)}` : t.serverId)) : null,
        t.siteId ? h('span', null, '站點 ', raw(t.siteId)) : null,
        t.releaseId ? h('span', null, '版本 ', raw(t.releaseId)) : null,
        t.artifactSha256 ? h('span', null, 'SHA-256 ', raw(shortId(t.artifactSha256, 12))) : null,
        t.createdAt ? h('span', null, `建立 ${formatTime(t.createdAt)}`) : null,
        h('span', null, `更新 ${formatTime(t.updatedAt)}`)),
      codeLine(t.code),
      h('div', { class: 'task-actions' }, detailLink(t.type, t.id, d.unresolved && t.type !== 'CONTROLLER_INSTALL' ? '查看詳情並核對' : '查看詳情')));
  }

  // ---------- 中控與業務安裝任務（只讀） ----------

  async function openControllerJob(id, { notice = null } = {}) {
    if (!ts.isTaskUuid(id) || !session.isActive()) return;
    const g = gen;
    const cg = ++ctlGen;
    const stale = () => g !== gen || cg !== ctlGen;
    // 同一 UUID 重新讀取時保留上次結果並標示；切換 UUID 時不沿用。
    const previous = ctl && ctl.id === id ? ctl.job : null;
    ctl = { id, loading: true, error: null, job: previous, readAt: ctl && ctl.id === id ? ctl.readAt : null };
    render();
    try {
      const job = ts.normalizeControllerJob(await client.getControllerJob(id), id);
      if (stale()) return;
      if (!job) throw { kind: 'protocol', serverMessage: '安裝任務回應格式無效或與請求的 UUID 不符' };
      ctl = { id, loading: false, error: null, job, readAt: new Date().toISOString(), notice };
    } catch (err) {
      if (stale()) return;
      if (isAuth(err)) { onAuthFailure(err); return; }
      // 讀取失敗不沿用舊結果冒充目前狀態。
      ctl = { id, loading: false, error: err, job: null, readAt: null, notice };
    } finally {
      if (!stale()) render();
    }
  }

  // 撤銷尚未派發的排隊任務：只在後端明確回報 canCancelBeforeDispatch＝true 時提供；確認後重讀原任務再送出原 requestHash。
  // 不因 canManage 或交接等待單獨停用（後端允許現時主中控管理員撤銷）。結果不確定或競態拒絕時只重新讀取原任務，不重送。
  async function cancelControllerJob() {
    if (!ctl?.job || ctl.loading || ctl.cancelling || !session.isActive() || !ts.controllerJobCancellable(ctl.job)) return;
    const id = ctl.id;
    const original = ctl.job;
    const g = gen;
    const cg = ctlGen;
    const stale = () => g !== gen || cg !== ctlGen;
    let sent = false;
    ctl = { ...ctl, cancelling: true, notice: null };
    render();
    try {
      const yes = await confirmDialog({
        title: '撤銷未派發的安裝任務',
        lines: [
          `任務：${id}`,
          '只撤銷尚未派發的排隊；不刪除伺服器、資料或任何設定，也不是回滾。',
          '撤銷後原批准與 UUID 保留供稽核，不能重跑；要部署需另建新的預覽（新 UUID、新預檢、新批准）。',
          '若中控已開始派發，撤銷會被拒絕，以原任務實際狀態為準。',
        ],
        confirmLabel: '撤銷佇列',
      });
      if (!yes || stale() || !session.isActive()) return;
      // 確認後重讀原任務：仍可撤銷且 requestHash 未變才送出。
      const fresh = ts.normalizeControllerJob(await client.getControllerJob(id), id);
      if (stale()) return;
      if (!fresh || !ts.controllerJobCancellable(fresh) || fresh.requestHash !== original.requestHash) {
        ctl = { ...ctl, job: fresh ?? ctl.job, readAt: fresh ? new Date().toISOString() : ctl.readAt, notice: { tone: 'amber', text: '確認期間任務狀態已改變，未撤銷。' } };
        return;
      }
      sent = true;
      const res = ts.normalizeControllerJob(await client.cancelControllerJob(id, { requestHash: fresh.requestHash }), id);
      if (stale()) return;
      if (!res) { openControllerJob(id, { notice: { tone: 'amber', text: '撤銷已回應，但格式無效；已重新讀取原任務。' } }); return; }
      ctl = { id, loading: false, error: null, job: res, readAt: new Date().toISOString(),
        notice: ts.controllerJobCancelled(res) ? { tone: 'teal', text: '已撤銷（未派發）。沒有在伺服器上執行任何安裝。' } : { tone: 'amber', text: '撤銷已回應，但結果沒有明確顯示「未派發即撤銷」；以任務狀態為準。' } };
    } catch (err) {
      if (stale()) return;
      if (isAuth(err)) { onAuthFailure(err); return; }
      const c = typeof err?.code === 'string' ? err.code : null;
      if (c === 'BOOTSTRAP_DISPATCH_ALREADY_CLAIMED') {
        openControllerJob(id, { notice: { tone: 'red', text: '已有派發意圖落盤，未撤銷；已重新讀取原任務。', code: c } });
        return;
      }
      if (sent && (err?.outcomeUnknown || err?.status >= 500)) {
        openControllerJob(id, { notice: { tone: 'amber', text: '撤銷結果未知；不會重送，已重新讀取原任務。' } });
        return;
      }
      ctl = { ...ctl, notice: { tone: 'red', text: `撤銷被拒絕：${ts.controllerCodeText(c) ?? describeError(err)}`, code: c } };
    } finally {
      if (!stale()) { if (ctl?.cancelling) ctl = { ...ctl, cancelling: false }; render(); }
    }
  }

  function ctlErrorText(err) {
    const c = typeof err?.code === 'string' ? err.code : null;
    if (err?.status === 404) return ['找不到這個安裝任務（可能 UUID 錯誤或任務記錄不在這個中控）。', c ? h('span', null, ' ', raw(c)) : null];
    return [ts.controllerCodeText(c) ?? ts.taskCodeText(c) ?? describeError(err), c ? h('span', null, ' ', raw(c)) : null];
  }

  const kvRow = (term, value) => (value === null || value === undefined ? null : [h('dt', null, term), h('dd', null, value)]);
  const rawOr = (v, missing = '未提供') => (v === null || v === undefined || v === '' ? h('span', { class: 'muted' }, missing) : raw(String(v)));
  const serverLine = (id, rev) => (id ? h('span', null, raw(serverNames.has(id) ? `${serverNames.get(id)} · ${id}` : id), rev !== null ? ` · revision ${rev}` : '') : h('span', { class: 'muted' }, '未提供'));

  function stepRow(r) {
    const d = ts.controllerStepDisplay(r.state);
    return h('li', { class: `task-row${d.tone === 'red' ? ' is-unresolved' : ''}` },
      h('div', { class: 'task-head' }, h('span', { class: 'task-type' }, ts.CONTROLLER_PHASE_LABEL[r.phase] ?? '未知階段'), status(d.tone, d.label), raw(r.phase ?? '—')),
      h('div', { class: 'task-meta' },
        r.state ? h('span', null, '狀態 ', raw(r.state)) : null,
        r.remoteState ? h('span', null, '遠端 ', raw(r.remoteState)) : null,
        r.dispatchedAt ? h('span', null, `派發 ${formatTime(r.dispatchedAt)}`) : null,
        r.observedAt ? h('span', null, `觀察 ${formatTime(r.observedAt)}`) : null,
        r.finishedAt ? h('span', null, `結束 ${formatTime(r.finishedAt)}`) : null,
        r.id ? h('span', null, 'UUID ', raw(r.id)) : null),
      r.operations?.length ? h('ul', { class: 'task-list' }, r.operations.map((o) => {
        const od = ts.controllerStepDisplay(o.state);
        return h('li', { class: 'task-meta' }, h('span', { class: 'task-type' }, ts.CONTROLLER_OPERATION_LABEL[o.operation] ?? '未知操作'), raw(o.operation ?? '—'),
          status(od.tone, od.label), o.role ? h('span', null, '角色 ', raw(o.role)) : null, o.remoteState ? h('span', null, '遠端 ', raw(o.remoteState)) : null,
          o.observedAt ? h('span', null, `觀察 ${formatTime(o.observedAt)}`) : null);
      })) : null);
  }

  // 目標物件儲存：只顯示公開設定；憑據已封存不等於可連線或健康。
  function storageSection(st) {
    const title = h('h3', { class: 'sub-title' }, '目標物件儲存');
    if (!st?.provided) return [title, h('p', { class: 'hint' }, '回應沒有提供目標物件儲存設定（舊任務或舊後端）；狀態未知。')];
    if (!st.valid) return [title, h('p', { class: 'message message-amber' }, st.problems[0] ?? '目標物件儲存欄位格式無效。', '狀態未知。')];
    const known = (label, rawCode) => label ?? h('span', { class: 'muted' }, '未知', rawCode ? h('span', null, ' ', raw(rawCode)) : null);
    return [title,
      h('dl', { class: 'kv' },
        kvRow('提供者', st.provider ? h('span', null, `${ts.STORAGE_PROVIDER_LABEL[st.provider]} `, raw(st.provider)) : known(null, st.providerRaw)),
        kvRow('設定來源', st.mode ? h('span', null, `${ts.STORAGE_MODE_LABEL[st.mode]} `, raw(st.mode)) : known(null, st.modeRaw)),
        kvRow('S3 API 地址', rawOr(st.endpoint, '未顯示')),
        kvRow('桶', rawOr(st.bucket, '未顯示')),
        kvRow('區域', rawOr(st.region, '未顯示')),
        kvRow('憑據', st.credentialsConfigured === true ? '已封存（不代表可連線或健康）' : st.credentialsConfigured === false ? '未封存' : h('span', { class: 'muted' }, '未知'))),
      st.problems.length ? h('ul', { class: 'list list-amber' }, st.problems.map((x) => h('li', null, x))) : null,
      h('details', { class: 'disclosure' }, h('summary', null, '其他儲存設定'),
        h('dl', { class: 'kv' },
          kvRow('公開網址', rawOr(st.publicBaseUrl, '未設定或未顯示')),
          kvRow('CDN 地址', rawOr(st.cdnEndpoint, '未設定或未顯示')),
          kvRow('path style', st.pathStyle === null ? h('span', { class: 'muted' }, '未知') : raw(String(st.pathStyle)))))];
  }

  function renderController() {
    els.ctlPanel.hidden = !ctl;
    els.ctlReload.disabled = !ctl || ctl.loading || ctl.cancelling || !session.isActive();
    if (!ctl) { els.ctlView.replaceChildren(); els.ctlObserved.textContent = ''; return; }
    els.ctlObserved.textContent = ctl.readAt ? `讀取於 ${formatTime(ctl.readAt)}` : '';
    const head = h('p', { class: 'hint' }, '任務 ', raw(ctl.id));
    if (ctl.error) { replace(els.ctlView, head, h('p', { class: 'message message-red' }, '無法讀取安裝任務：', ...ctlErrorText(ctl.error), '。狀態未知。')); return; }
    if (!ctl.job) { replace(els.ctlView, head, h('p', { class: 'empty' }, '讀取中…')); return; }
    const j = ctl.job;
    const d = ts.controllerJobDisplay(j);
    const notice = ctl.notice ? h('p', { class: `message message-${ctl.notice.tone}` }, ctl.notice.text, ctl.notice.code ? h('span', null, ' ', raw(ctl.notice.code)) : null) : null;
    replace(els.ctlView,
      notice,
      ctl.loading ? h('p', { class: 'message message-neutral' }, '正在重新讀取，以下為上次結果。') : null,
      h('div', { class: 'task-head' }, h('span', { class: 'task-type' }, '中控與業務安裝'), status(d.tone, d.label), raw(j.id)),
      d.unresolved && j.requiresReapproval === true ? h('p', { class: 'alert alert-amber' }, '需要重新批准：在重新批准前不能繼續，也不能視為完成。本頁不提供重新批准。') : null,
      d.unresolved && d.label.startsWith('結果未知') ? h('p', { class: 'alert alert-red' }, '結果未知：不能視為完成或失敗；需要由後端核對實際狀態。本頁不提供核對或重送。') : null,
      d.complete ? h('p', { class: 'note' }, '成功只是歷史記錄，不代表目前健康；請以站點與中控頁的即時狀態為準。') : null,
      d.cancelled ? h('p', { class: 'note' }, '已撤銷（未派發）：沒有安裝也不是回滾。原批准與 UUID 保留供稽核，不能重跑；要部署請另建新的預覽（新 UUID、新預檢、新批准）。') : null,
      ts.controllerJobCancellable(j) && !ctl.loading ? h('div', { class: 'alert alert-amber' },
        h('p', null, '這個任務尚未派發任何階段，可以撤銷佇列。撤銷只移除排隊，不刪除伺服器或資料，也不是回滾。'),
        h('button', { type: 'button', class: 'btn btn-warn btn-sm', 'data-ctl-action': 'cancel', disabled: ctl.cancelling || !session.isActive() }, ctl.cancelling ? '撤銷中…' : '撤銷未派發任務…')) : null,
      d.notes.length ? h('ul', { class: 'list list-amber' }, d.notes.map((n) => h('li', null, n))) : null,
      j.code ? h('p', { class: 'task-code' }, ts.controllerCodeText(j.code) ?? '', ' ', raw(j.code)) : null,
      h('dl', { class: 'kv' },
        kvRow('整體狀態', rawOr(j.state, '缺失')),
        kvRow('目前階段', j.stage ? h('span', null, `${ts.CONTROLLER_STAGE_LABEL[j.stage] ?? '未知階段'} `, raw(j.stage)) : h('span', { class: 'muted' }, '未提供')),
        kvRow('來源伺服器', serverLine(j.sourceServerId, j.sourceRevision)),
        kvRow('目標伺服器', serverLine(j.serverId, j.serverRevision)),
        kvRow('業務版本', h('span', null, rawOr(j.releaseId), ' · SHA-256 ', rawOr(j.artifactSha256))),
        kvRow('中控版本 SHA-256', rawOr(j.controllerSha256)),
        kvRow('集群', h('span', null, rawOr(j.clusterId), ` · epoch ${j.epoch ?? '—'} · slot ${j.allocationSlot ?? '—'}`)),
        kvRow('需要重新批准', j.requiresReapproval === null ? h('span', { class: 'muted' }, '未知') : j.requiresReapproval ? '是' : '否'),
        kvRow('需要核對', j.requiresReconciliation === null ? h('span', { class: 'muted' }, '未知') : j.requiresReconciliation ? '是' : '否'),
        kvRow('已開始變更', j.effectsStarted === null ? null : j.effectsStarted ? '是' : '否'),
        // 執行診斷碼：只呈現固定代碼與時間，不解讀成原生執行成功或失敗。
        kvRow('執行診斷碼', j.executionCode ? h('span', null, raw(j.executionCode), j.executionObservedAt ? ` · ${formatTime(j.executionObservedAt)}` : '', '（固定診斷碼，不代表原生成功或失敗）') : null),
        kvRow('建立', j.createdAt ? formatTime(j.createdAt) : null),
        kvRow('接受', j.acceptedAt ? formatTime(j.acceptedAt) : null),
        kvRow('結束', j.finishedAt ? formatTime(j.finishedAt) : null)),
      j.permissionsRequired ? h('details', { class: 'disclosure' }, h('summary', null, `批准的權限（${j.permissionsRequired.length}）`),
        h('ul', { class: 'list' }, j.permissionsRequired.map((p) => h('li', null, raw(p))))) : h('p', { class: 'hint' }, '回應沒有提供批准的權限清單。'),
      j.review && Object.keys(j.review).length ? h('details', { class: 'disclosure' }, h('summary', null, '公開網路設定'),
        h('dl', { class: 'kv' }, Object.entries(j.review).map(([k, v]) => [h('dt', null, raw(k)), h('dd', null, raw(v))]))) : null,
      storageSection(j.storage),
      h('h3', { class: 'sub-title' }, '逐階段狀態'),
      j.steps ? h('ol', { class: 'task-list' }, j.steps.map(stepRow)) : h('p', { class: 'message message-amber' }, '回應沒有提供階段清單；逐階段狀態未知。'));
  }

  function render() {
    renderController();
    renderFences();
    const loadingFirst = Boolean(inflight && !inflight.more);
    els.observed.textContent = observedAt ? `讀取於 ${formatTime(observedAt)}` : '';
    if (error) {
      els.status.hidden = false;
      els.status.className = 'message message-red';
      replace(els.status, '無法讀取任務歷史：', ...errorText(error), '。這不代表沒有任務。');
    } else if (dropped) {
      els.status.hidden = false;
      els.status.className = 'message message-amber';
      replace(els.status, `${dropped} 筆記錄格式無效未顯示。`);
    } else {
      els.status.hidden = true;
      els.status.replaceChildren();
    }
    if (error) els.list.replaceChildren();
    else if (!loaded) replace(els.list, h('li', { class: 'empty' }, loadingFirst ? '讀取中…' : '尚未讀取。'));
    else if (!items.length) replace(els.list, h('li', { class: 'empty' }, '沒有符合條件的任務。'));
    else replace(els.list, items.map(taskRow), moreError ? h('li', { class: 'message message-red' }, '載入下一頁失敗：', ...errorText(moreError), '；已載入的項目保留。') : null);
    els.more.hidden = !nextCursor || Boolean(error);
    els.more.disabled = Boolean(inflight);
    els.more.textContent = inflight?.more ? '載入中…' : '載入更多';
    els.reload.disabled = loadingFirst;
    for (const s of [els.type, els.state, els.server]) s.disabled = !session.isActive();
  }

  // ---------- 事件 ----------

  els.filter.addEventListener('change', () => restart());
  els.more.addEventListener('click', () => fetchPage({ more: true }));
  els.reload.addEventListener('click', () => { restart(); loadFences(); });
  for (const container of [els.list, els.fences]) {
    container.addEventListener('click', (event) => {
      const a = event.target.closest?.('a[data-task-id]');
      if (!a) return;
      const target = ts.taskDetailTarget(a.dataset.taskType, a.dataset.taskId);
      if (!target) { event.preventDefault(); return; }
      if (target.target === 'controller-job') { event.preventDefault(); showController(target.id); return; }
      openDetail(target);
    });
  }
  els.openForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const target = ts.taskDetailTarget(els.openType.value, els.openId.value.trim());
    if (!target) {
      els.status.hidden = false;
      els.status.className = 'message message-amber';
      replace(els.status, '請選擇類型並輸入有效的 UUID。');
      return;
    }
    if (target.target === 'controller-job') { els.status.hidden = true; showController(target.id); return; }
    const href = ts.taskDetailHref(target);
    if (!href) return;
    openDetail(target);
    const a = document.createElement('a');
    a.href = href;
    a.click();
  });
  els.ctlReload.addEventListener('click', () => { if (ctl && !ctl.loading && !ctl.cancelling) openControllerJob(ctl.id); });
  els.ctlView.addEventListener('click', (event) => { if (event.target.closest?.('[data-ctl-action="cancel"]')) cancelControllerJob(); });
  els.ctlClose.addEventListener('click', () => { ctlGen += 1; ctl = null; render(); });

  function showController(id) {
    openControllerJob(id);
    els.ctlPanel.scrollIntoView?.({ block: 'start' });
  }

  render();

  return {
    enter() {
      onPage = true;
      if (!session.isActive()) return;
      gen += 1;
      listGen += 1;
      clearList();
      loadServers();
      loadFences();
      fetchPage();
      if (pendingOpen) { const id = pendingOpen; pendingOpen = null; showController(id); }
    },
    // 其他頁面（例如組合安裝排隊後）要求開啟只讀詳情：已在任務頁就直接讀取，否則等 enter()。
    openControllerJob(id) {
      if (!ts.isTaskUuid(id)) return;
      if (onPage && session.isActive()) showController(id); else pendingOpen = id;
    },
    // 離開頁面：丟棄進行中的回應，下次進入重新讀取。
    leave() {
      if (!onPage) return;
      onPage = false;
      gen += 1;
      inflight = null;
      // 離頁：丟棄進行中的安裝任務讀取；保留已讀到的結果，不保留「讀取中」。
      ctlGen += 1;
      if (ctl?.loading) ctl = ctl.job ? { ...ctl, loading: false } : null;
      // 撤銷進行中離頁：回應會被丟棄，結果未知；回來後以「重新讀取」為準，不重送。
      if (ctl?.cancelling) ctl = { ...ctl, cancelling: false, notice: { tone: 'amber', text: '撤銷在離開頁面時中斷，結果未知；請按「重新讀取」確認原任務狀態（不會自動重送）。' } };
      render();
    },
    reload() {
      if (!onPage || !session.isActive()) return;
      restart();
      loadFences();
    },
    reset() {
      gen += 1;
      listGen += 1;
      onPage = false;
      clearList();
      fences = null;
      fenceGen += 1;
      ctl = null;
      ctlGen += 1;
      pendingOpen = null;
      serverNames = new Map();
      replace(els.server, h('option', { value: '' }, '全部伺服器'));
      els.type.value = ''; els.state.value = ''; els.openId.value = '';
      render();
    },
  };
}
