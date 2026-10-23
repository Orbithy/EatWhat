// 伺服器頁：SSH 登記、主機指紋核對、連線檢測、首次安裝／版本更新計畫與任務。
// - 登入後才讀取清單；401／403 交給上層登出；其餘失敗把清單視為未知並停用寫入。
// - 寫入一律由使用者點擊；確認後重新讀取清單（canManage、revision）與主中控狀態再送出。
// - 憑據只在送出當下讀取；送出、取消、離開頁面與登出都會清空。不寫入瀏覽器儲存。
// - 寫入結果未知時不自動重送：重新讀取清單，或以原 requestId／計畫 UUID 查回。
// - 任務只有總體狀態，不顯示步驟或百分比。登出後到達的舊回應由 generation 柵欄丟棄。

import { h, replace } from './dom.js';
import { describeError, formatTime, shortId } from './format.js';
import { randomUuid } from './cluster-state.js';
import * as ss from './servers-state.js';

const JOB_POLL_MS = 4000;
const AUTH_LABEL = { PASSWORD: '密碼', PRIVATE_KEY: '私鑰' };
const KIND_LABEL = { INSTALL: '首次安裝', UPDATE: '版本更新', RECOVER: '恢復安裝' };

export function mountServers({ client, session, confirmDialog, onAuthFailure, getLeadership, onLeaseLost, onClusterChanged }) {
  const $ = (id) => document.getElementById(id);
  const els = {
    add: $('server-add'), status: $('servers-status'), result: $('servers-result'), steps: $('server-steps'),
    listPanel: $('servers-list-panel'), summary: $('servers-summary'), list: $('servers-list'),
    formPanel: $('server-form-panel'), formTitle: $('server-form-title'), form: $('server-form'),
    name: $('server-name'), host: $('server-host'), port: $('server-port'), username: $('server-username'),
    privilegeSudo: $('server-privilege-sudo'), privilegeNote: $('server-privilege-note'),
    localNote: $('server-local-note'), passwordField: $('server-password-field'), password: $('server-password'),
    keyField: $('server-key-field'), privateKey: $('server-private-key'), credentialHelp: $('server-credential-help'),
    credSource: $('server-credential-source'), credSourceInventory: $('server-credential-source-inventory'), credSourceNote: $('server-credential-source-note'),
    credIdField: $('server-credential-id-field'), credId: $('server-credential-id'),
    hostkeyStatus: $('server-hostkey-status'), hostkeys: $('server-hostkeys'), hostkeyCommand: $('server-hostkey-command'),
    hostkeyConfirmField: $('server-hostkey-confirm-field'), hostkeyConfirm: $('server-hostkey-confirm'), hostkeyFetch: $('server-hostkey-fetch'),
    formProblems: $('server-form-problems'), save: $('server-save'), formCancel: $('server-form-cancel'),
    planPanel: $('server-plan-panel'), planTitle: $('server-plan-title'), planForm: $('server-plan-form'),
    planRelease: $('server-plan-release'),
    planIdentity: $('server-plan-identity'), planRoleLegacy: $('server-plan-role-legacy'), planSlotField: $('server-plan-slot-field'),
    planSlot: $('server-plan-slot'), planIdentityNote: $('server-plan-identity-note'), planInstallFields: $('server-plan-install-fields'), planUrl: $('server-plan-url'),
    planPort: $('server-plan-port'), planDocker: $('server-plan-docker'), planUpdateNote: $('server-plan-update-note'),
    planProblems: $('server-plan-problems'), planCreate: $('server-plan-create'), planCancel: $('server-plan-cancel'),
    planView: $('server-plan-view'), planExecute: $('server-plan-execute'),
    jobView: $('server-job-view'), jobRefresh: $('server-job-refresh'), jobReconcile: $('server-job-reconcile'),
    clusterPanel: $('server-cluster-panel'), clusterPlanCreate: $('server-cluster-plan-create'), clusterCancel: $('server-cluster-cancel'),
    clusterPlanView: $('server-cluster-plan-view'), clusterExecute: $('server-cluster-execute'),
    clusterJobPanel: $('server-cluster-job-panel'), clusterReconnect: $('server-cluster-reconnect'), clusterJobView: $('server-cluster-job-view'),
    clusterJobRefresh: $('server-cluster-job-refresh'), clusterReconcile: $('server-cluster-job-reconcile'),
    clusterRecover: $('server-cluster-job-recover'), clusterRecoverNote: $('server-cluster-recover-note'),
    clusterIntro: $('server-cluster-intro'),
    clusterLookupForm: $('server-cluster-lookup-form'), clusterLookupId: $('server-cluster-lookup-id'), clusterLookupSubmit: $('server-cluster-lookup-submit'),
    jobLookupForm: $('server-job-lookup-form'), jobLookupId: $('server-job-lookup-id'), jobLookupSubmit: $('server-job-lookup-submit'),
  };

  let gen = 0;
  let data = null;
  let loadError = null;
  let loading = false;
  let view = 'list'; // list | form | plan
  let editing = null; // 編輯中的伺服器（新增時為 null）
  let probe = null; // { signature, keys }
  let keyIndex = -1;
  let forceHostKey = false;
  let probedSignature = null; // 已畫出的指紋清單，避免重繪造成焦點遺失
  let remoteHost = '';
  let pendingIntent = null; // 結果未知後沿用的 { signature, requestId }
  let planCtx = null; // { serverId, kind }
  let plan = null;
  let planExpiryTimer = null;
  let job = null;
  let jobTimer = null;
  let onPage = false;
  let dirtyForm = false;
  let dirtyPlan = false;
  let lastSaved = null; // 剛登記完成的伺服器，用於步驟提示
  // 接入集群：計畫、任務與重新連線狀態（任務 UUID 只存在記憶體；重新登入後由清單的 clusterJobId 查回）。
  let clusterCtx = null; // { serverId }
  let clusterPlan = null;
  let clusterJob = null;
  let clusterPending = null; // 已送出或正在查回、但尚未讀到任務的 UUID
  // 已送出 execute 的恢復計畫 UUID：之後即使查不到任務（404）也不再開放送出，只能以原 UUID 核對。
  const sentRecoveryPlans = new Set();
  let clusterTimer = null;
  let clusterFailures = 0;
  let clusterLastOk = null;
  let clusterSince = null;
  let clusterAutoLooked = null;
  // 恢復安裝：FAILED 伺服器的來源任務（唯讀讀取後才決定能否恢復）。serverId → { jobId, loading, error, job }
  const recoverySources = new Map();
  const busy = new Set();

  // ---------- 共用 ----------

  const serverById = (id) => data?.servers.find((s) => s.id === id) ?? null;

  function gateReasons() {
    const reasons = [];
    if (!session.isActive()) reasons.push('尚未登入。');
    if (!data) reasons.push(loadError ? `無法讀取伺服器清單（${describeError(loadError)}），寫入已停用。` : '伺服器清單尚未讀取，寫入已停用。');
    else if (!data.canManage) reasons.push(ss.gateReasonText(data.reason));
    const lead = getLeadership?.();
    if (lead && !lead.writable) reasons.push(...(lead.reasons.length ? lead.reasons : ['主中控狀態未確認，管理寫入已停用。']));
    return reasons;
  }
  const canWrite = () => gateReasons().length === 0;

  function message(tone, text, lines = []) {
    els.result.hidden = !text;
    els.result.className = `message message-${tone}`;
    if (!text) { els.result.replaceChildren(); return; }
    replace(els.result, h('span', { class: 'message-title' }, text), lines.length ? h('ul', { class: 'list' }, lines.map((l) => h('li', null, l))) : null);
  }

  function clearSecrets() {
    els.password.value = '';
    els.privateKey.value = '';
  }

  const status = (tone, text) => h('span', { class: `status status-${tone}` }, h('span', { class: `dot dot-${tone}`, 'aria-hidden': 'true' }), text);
  const raw = (text) => h('span', { class: 'mono', translate: 'no' }, text);
  const kv = (pairs) => h('dl', { class: 'kv' }, pairs.filter(Boolean).flatMap(([k, v]) => [h('dt', null, k), h('dd', null, v)]));

  function handleFailure(err, { onUnknown, reloadOn = [] } = {}) {
    const f = ss.serverFailure(err);
    if (f.outcome === 'LOGOUT') { onAuthFailure(err); return f; }
    if (f.leaseLost) onLeaseLost?.();
    if (f.outcome === 'UNKNOWN') {
      message('red', f.message);
      onUnknown?.();
      return f;
    }
    message([409, 423, 429].includes(err?.status) ? 'amber' : 'red', f.message, f.code && !f.message.includes(f.code) ? [`代碼：${f.code}`] : []);
    if (f.leaseLost || ['SERVER_REVISION_CHANGED', 'SERVER_NOT_FOUND', 'SERVER_PLAN_CHANGED', 'SERVER_PLAN_EXPIRED', 'INSTALLED_SERVER_ADDRESS_IMMUTABLE', ...reloadOn].includes(f.code)) load();
    return f;
  }

  // ---------- 讀取 ----------

  async function load() {
    if (!session.isActive()) return null;
    const g = gen;
    loading = true;
    render();
    try {
      const res = await client.listServers();
      if (g !== gen) return null;
      const normalized = ss.normalizeServerList(res);
      data = normalized;
      loadError = normalized ? null : { kind: 'protocol', serverMessage: '伺服器清單格式無效' };
      if (editing && data) editing = serverById(editing.id) ?? editing;
      autoLookupClusterJob();
      refreshRecoverySources();
    } catch (err) {
      if (g !== gen) return null;
      if (err?.status === 401 || err?.status === 403) { onAuthFailure(err); return null; }
      data = null;
      loadError = err;
    } finally {
      if (g === gen) { loading = false; render(); }
    }
    return data;
  }

  // ---------- 畫面 ----------

  function renderStatus() {
    const reasons = session.isActive() && (data || loadError) ? gateReasons() : [];
    els.status.hidden = reasons.length === 0;
    replace(els.status, reasons.length === 1 ? reasons[0] : h('ul', { class: 'list' }, reasons.map((r) => h('li', null, r))));
    els.add.disabled = !canWrite() || view !== 'list';
  }

  function renderSteps() {
    let current = 0;
    if (view === 'form' && !editing) current = probe && probe.signature === ss.addressSignature(readForm()) ? 2 : 1;
    else if (view === 'list' && lastSaved && serverById(lastSaved)?.connection.state === 'NOT_TESTED') current = 3;
    else if (view === 'plan' && planCtx?.kind === 'INSTALL') current = job && plan && job.id === plan.id ? 5 : 4;
    els.steps.hidden = current === 0;
    if (!current) return;
    const names = ['連線資料', '核對指紋', '連線檢測', '安裝計畫', '安裝任務'];
    replace(els.steps, names.map((n, i) => h('li', { class: i + 1 < current ? 'done' : i + 1 === current ? 'current' : '', 'aria-current': i + 1 === current ? 'step' : null },
      h('span', { 'aria-hidden': 'true' }, i + 1 < current ? '✓' : String(i + 1)), n)));
  }

  function locBadge(location) {
    return location === 'CONTROL_HOST'
      ? h('span', { class: 'loc-badge local' }, '中控本機')
      : h('span', { class: 'loc-badge' }, '遠端');
  }

  function installationBlock(s) {
    const inst = s.installation;
    if (!inst) {
      if (!data.installationEnabled) return h('p', { class: 'reserved' }, h('strong', null, '首次安裝'), h('span', null, '安裝功能未啟用（SERVER_INSTALLATION_DISABLED）。'));
      return null;
    }
    if (inst.state === 'FAILED') {
      return h('div', { class: 'alert alert-red' }, h('p', { class: 'message-title' }, '首次安裝在資料庫啟動後失敗，目標主機上的資料已保留。'),
        h('p', null, '這不是「未安裝」：不能直接重新安裝。請以任務 UUID 查看結果明細，並與伺服器管理員處理後續。'),
        inst.root ? h('p', null, '安裝目錄 ', raw(inst.root)) : null, inst.jobId ? h('p', null, '任務 ', raw(inst.jobId)) : null);
    }
    if (inst.state !== 'SUCCEEDED') {
      return h('div', { class: 'alert alert-amber' }, h('p', { class: 'message-title' }, `安裝狀態：${inst.state}`),
        h('p', null, '安裝結果尚未釐清；請以任務 UUID 查詢並核對遠端記錄。'), inst.jobId ? h('p', null, '任務 ', raw(inst.jobId)) : null);
    }
    if (inst.clusterMembership === 'ACTIVE') {
      return h('div', { class: 'install-block' },
        h('p', { class: 'install-title' }, '已加入集群'),
        kv([
          ['站點', raw(`${inst.siteId ?? '—'} · ${inst.instanceId ?? '—'}`)],
          ['業務 HTTP', h('span', null, raw(`127.0.0.1:${inst.httpPort ?? '—'}`), '（目標主機 Nginx 轉發）')],
          ['目前版本 SHA-256', raw(shortId(inst.currentSha256, 16))],
          s.clusterJobId ? ['接入任務', raw(s.clusterJobId)] : null,
        ]),
        h('p', { class: 'hint' }, '後端已核對 APPLIED／ACTIVE。成員與同步範圍以站點頁為準。'),
        h('p', null, routeLink('sites', '查看站點 →')));
    }
    return h('div', { class: 'install-block' },
      h('p', { class: 'install-title' }, inst.mode === 'EXTERNAL' || inst.clusterId ? `${ss.SITE_MODE_LABEL[inst.mode] ?? '站點'}已安裝 · 等待接入` : '獨立業務站已安裝 · 尚未加入多站集群'),
      kv([
        ['站點', raw(`${inst.siteId ?? '—'} · ${inst.instanceId ?? '—'}`)],
        ['業務 HTTP', h('span', null, raw(`127.0.0.1:${inst.httpPort ?? '—'}`), '（目標主機 Nginx 轉發）')],
        ['對外網址', raw(inst.appBaseUrl ?? '—')],
        ['安裝目錄', raw(inst.root ?? '—')],
        ['目前版本 SHA-256', raw(shortId(inst.currentSha256, 16))],
        ['恢復演練', raw(inst.restoreEvidenceId ?? '—')],
        ...identityRows(inst),
      ]),
      s.clusterJobId ? h('p', { class: 'hint' }, '最近的接入任務 ', raw(s.clusterJobId), '；狀態見下方「接入集群任務」。') : null,
      h('p', { class: 'hint' }, '安裝成功不等於集群成員；只有後端核對 APPLIED／ACTIVE 後才會顯示已加入。'));
  }

  function routeLink(route, text) {
    const a = document.createElement('a');
    a.className = 'link';
    a.href = `#/${route === 'sites' ? 'sites' : 'servers'}`;
    a.textContent = text;
    return a;
  }

  function serverCard(s) {
    const st = ss.serverStatus(s);
    const write = canWrite();
    const isBusy = (k) => busy.has(`${k}:${s.id}`);
    const checks = ss.connectionChecks(s.connection);
    const resources = ss.resourceLine(s.connection);
    const installDisabled = !write || !data.installationEnabled || s.connection.state !== 'CONNECTED' || busy.size > 0;
    const enroll = ss.enrollmentAvailability(s, data);
    const upd = ss.updateAvailability(s);
    const rec = ss.recoveryAvailability(s, recoverySources.get(s.id));
    return h('article', { class: `server-card${st.tone === 'red' ? ' is-red' : st.tone === 'amber' ? ' is-amber' : ''}`, 'data-server-id': s.id, 'aria-label': s.name },
      h('div', { class: 'server-head' }, h('h2', { class: 'server-name', translate: 'no' }, s.name), locBadge(s.location), status(st.tone, st.label)),
      h('dl', { class: 'server-facts' },
        h('div', null, h('dt', null, '連線'), h('dd', null, raw(ss.sshTarget(s)))),
        h('div', null, h('dt', null, '認證'), h('dd', null, `${AUTH_LABEL[s.authentication] ?? '未提供'}（${s.hasCredential ? '已儲存，不顯示' : '未儲存'}）`)),
        ss.credentialSourceText(s, ss.credentialInventoryCapability(data))
          ? h('div', null, h('dt', null, '憑據來源'), h('dd', null, ss.credentialSourceText(s, ss.credentialInventoryCapability(data)))) : null,
        h('div', null, h('dt', null, '主機指紋'), h('dd', null, s.hostKey ? raw(`${s.hostKey.type} ${s.hostKey.fingerprint}`) : '未登記'))),
      s.connection.state === 'FAILED' && s.connection.code
        ? h('p', { class: 'message message-red' }, `連線檢測失敗：${ss.serverCodeText(s.connection.code) ?? '請查看代碼'}（${s.connection.code}）。主機金鑰變更時需在「編輯」重新核對指紋。`)
        : null,
      h('div', { class: 'readiness', 'aria-label': '環境檢測' }, checks.map((c) => h('div', { class: `ready-item ${c.tone}` },
        h('span', { class: `dot dot-${c.tone === 'ok' ? 'teal' : c.tone === 'bad' ? 'red' : c.tone === 'warn' ? 'amber' : 'neutral'}`, 'aria-hidden': 'true' }),
        h('span', { class: 'ready-name' }, c.label), h('span', { class: 'ready-detail', translate: 'no' }, c.detail)))),
      resources ? h('p', { class: 'hint' }, resources) : null,
      installationBlock(s),
      enroll.show && enroll.reasons.length ? h('p', { class: 'hint enroll-reason' }, '接入集群：', enroll.reasons.join(' ')) : null,
      upd.show && upd.reasons.length ? h('p', { class: 'hint update-reason' }, '版本更新：', upd.reasons.join(' ')) : null,
      rec.mode !== 'none' && rec.mode !== 'recover' ? h('p', { class: 'hint recovery-reason' }, '恢復安裝：', rec.reasons.join(' ')) : null,
      h('div', { class: 'server-foot' },
        h('span', { class: 'meta' }, s.connection.checkedAt ? `最後檢測 ${formatTime(s.connection.checkedAt)}` : '尚未檢測', ` · revision ${s.revision}`),
        h('button', { type: 'button', class: 'btn btn-ghost btn-sm', 'data-action': 'test', 'data-id': s.id, disabled: !write || busy.size > 0 }, isBusy('test') ? '檢測中…（最多 45 秒）' : '連線檢測'),
        !st.installed && !st.unresolved ? h('button', { type: 'button', class: 'btn btn-primary btn-sm', 'data-action': 'install', 'data-id': s.id, disabled: installDisabled }, '建立首次安裝計畫…') : null,
        enroll.show ? h('button', { type: 'button', class: 'btn btn-primary btn-sm', 'data-action': 'cluster', 'data-id': s.id, disabled: !write || enroll.reasons.length > 0 || busy.size > 0 || clusterJobActive() }, '接入集群…') : null,
        st.joined ? routeLink('sites', '查看站點 →') : null,
        upd.show ? h('button', { type: 'button', class: 'btn btn-ghost btn-sm', 'data-action': 'update', 'data-id': s.id, disabled: !write || !data.installationEnabled || busy.size > 0 || upd.reasons.length > 0 }, '更新版本…') : null,
        rec.mode === 'recover' ? h('button', { type: 'button', class: 'btn btn-primary btn-sm', 'data-action': 'recover', 'data-id': s.id, disabled: !write || !data.installationEnabled || busy.size > 0 }, '恢復安裝…') : null,
        rec.mode === 'reconcile' ? h('button', { type: 'button', class: 'btn btn-primary btn-sm', 'data-action': 'source-job', 'data-id': s.id, disabled: busy.size > 0 }, '核對任務') : null,
        h('button', { type: 'button', class: 'btn btn-ghost btn-sm', 'data-action': 'edit', 'data-id': s.id, disabled: !write || busy.size > 0 }, '編輯'),
        h('button', { type: 'button', class: 'btn btn-ghost btn-sm btn-danger-ghost', 'data-action': 'remove', 'data-id': s.id, disabled: !write || busy.size > 0 }, '移除登記…')));
  }

  function renderList() {
    els.listPanel.hidden = view !== 'list';
    if (view !== 'list') return;
    if (!data) {
      els.summary.textContent = '';
      replace(els.list, h('p', { class: 'empty' }, loading ? '正在讀取伺服器清單…' : loadError ? `無法讀取伺服器清單：${describeError(loadError)}。狀態未知，寫入已停用。` : '尚未讀取伺服器清單。'));
      return;
    }
    const installed = data.servers.filter((s) => s.installation?.state === 'SUCCEEDED').length;
    els.summary.textContent = data.servers.length ? `共 ${data.servers.length} 台 · 已安裝 ${installed} 台${data.dropped ? ` · ${data.dropped} 筆格式無效未顯示` : ''}${loading ? ' · 重新讀取中…' : ''}` : '';
    if (!data.servers.length) {
      replace(els.list, h('section', { class: 'guide', 'aria-label': '尚未登記伺服器' },
        h('h2', null, '尚未登記任何伺服器'),
        h('p', null, '登記只儲存連線資料與加密的憑據；核對主機指紋前不會登入或執行任何指令。業務站與中控同機時，選「中控本機」。'),
        h('p', { class: 'guide-actions' }, h('button', { type: 'button', class: 'btn btn-primary', 'data-action': 'add', disabled: !canWrite() }, '添加 SSH 伺服器'))));
      return;
    }
    replace(els.list, data.servers.map(serverCard));
  }

  // ---------- 表單 ----------

  function selectedRadio(name) {
    return els.form.querySelector(`input[name="${name}"]:checked`)?.value ?? null;
  }

  function readForm() {
    const authentication = selectedRadio('server-auth');
    const inventory = selectedRadio('server-credential-source') === 'OPERATOR_INVENTORY';
    return {
      name: els.name.value,
      location: selectedRadio('server-location'),
      host: els.host.value,
      port: els.port.value,
      username: els.username.value,
      authentication,
      credential: authentication === 'PRIVATE_KEY' ? els.privateKey.value : els.password.value,
      probe,
      keyIndex,
      confirmed: els.hostkeyConfirm.checked,
      forceHostKey,
      // 未選擇（例如舊記錄的未知值）時為 undefined，需明確選擇；能力以目前清單為準。
      privilege: selectedRadio('server-privilege') ?? '',
      privilegeCap: ss.privilegeCapability(data),
      // 引用模式時不讀手動欄位，避免把已輸入的密碼／私鑰帶進引用請求。
      ...(inventory ? { credential: '' } : {}),
      credentialSource: selectedRadio('server-credential-source') ?? 'MANUAL',
      credentialId: inventory ? els.credId.value : '',
      inventoryCap: ss.credentialInventoryCapability(data),
    };
  }

  function setRadio(name, value) {
    for (const r of els.form.querySelectorAll(`input[name="${name}"]`)) r.checked = r.value === value;
  }

  function openForm(server) {
    view = 'form';
    editing = server;
    probe = null;
    keyIndex = -1;
    forceHostKey = false;
    probedSignature = null;
    dirtyForm = false;
    clearSecrets();
    els.hostkeyConfirm.checked = false;
    els.name.value = server?.name ?? '';
    setRadio('server-location', server?.location ?? 'REMOTE');
    remoteHost = server && server.location === 'REMOTE' ? server.host : '';
    els.host.value = server?.host ?? '';
    els.port.value = String(server?.port ?? 22);
    els.username.value = server?.username ?? '';
    setRadio('server-auth', server?.authentication ?? 'PASSWORD');
    // 新增預設直接執行（不擴權）；未知值不預選，避免悄悄改寫。
    setRadio('server-privilege', server ? server.privilege : 'NONE');
    // 預設手動；編輯時留空即保留原憑據（不論原來源，也不要求目前中控仍有該選項）。
    setRadio('server-credential-source', 'MANUAL');
    fillCredentialOptions();
    els.credId.value = '';
    els.formTitle.textContent = server ? `編輯「${server.name}」` : '添加 SSH 伺服器';
    els.save.textContent = server ? '儲存修改…' : '儲存登記…';
    message('neutral', '');
    render();
    els.name.focus();
  }

  function closeForm() {
    clearSecrets();
    view = 'list';
    editing = null;
    probe = null;
    keyIndex = -1;
    probedSignature = null;
    els.hostkeyConfirm.checked = false;
    render();
  }

  // 選項只含 label／id；清單變動時重建，已選但不再存在的值會被清空。
  function fillCredentialOptions() {
    const cap = ss.credentialInventoryCapability(data);
    const prev = els.credId.value;
    const sig = JSON.stringify(cap.options);
    if (els.credId.dataset.sig !== sig) {
      els.credId.dataset.sig = sig;
      replace(els.credId, [h('option', { value: '' }, cap.options.length ? '請選擇' : '沒有可用的專用密鑰'),
        ...cap.options.map((o) => h('option', { value: o.id, translate: 'no' }, `${o.label}（${o.id}）`))]);
    }
    els.credId.value = cap.options.some((o) => o.id === prev) ? prev : '';
  }

  function renderForm() {
    els.formPanel.hidden = view !== 'form';
    if (view !== 'form') return;
    const form = readForm();
    const local = form.location === 'CONTROL_HOST';
    const installed = Boolean(editing?.installation); // NOT_INSTALLED 已正規化為 null
    els.localNote.hidden = !local;
    els.host.readOnly = local;
    els.host.classList.toggle('locked', local);
    for (const input of [els.host, els.port, ...els.form.querySelectorAll('input[name="server-location"]')]) input.disabled = installed;
    fillCredentialOptions();
    const icap = form.inventoryCap;
    const inventory = form.credentialSource === 'OPERATOR_INVENTORY';
    // 舊後端（缺 sshCredentials）完全不顯示來源選擇；格式錯則顯示但停用引用選項。
    els.credSource.hidden = icap.reason === null && !icap.supported && !inventory;
    const invInput = els.credSourceInventory.querySelector('input');
    invInput.disabled = !(icap.supported && icap.options.length) && !inventory;
    els.credIdField.hidden = !inventory;
    els.credId.disabled = !icap.supported || !icap.options.length;
    for (const r of els.form.querySelectorAll('input[name="server-auth"]')) r.disabled = inventory;
    const editSrc = editing ? ss.credentialSourceText(editing, icap) : null;
    const cnote = inventory ? '中控會使用其伺服器上已配置的私鑰連線；瀏覽器只送出識別碼，不讀取、不顯示私鑰或路徑。'
      : [icap.reason, editSrc ? `目前憑據來源：${editSrc}；保持「手動輸入」並留空即可保留。` : null].filter(Boolean).join(' ');
    els.credSourceNote.hidden = !cnote;
    els.credSourceNote.textContent = cnote;
    const key = form.authentication === 'PRIVATE_KEY';
    els.passwordField.hidden = key || inventory;
    const pcap = form.privilegeCap;
    const sudoInput = els.privilegeSudo.querySelector('input');
    sudoInput.disabled = !pcap.sudo && !sudoInput.checked;
    els.privilegeSudo.hidden = !pcap.supported && !sudoInput.checked;
    const pnote = form.privilege === 'SUDO' ? (pcap.sudo ? '免密 sudo 只表示以 sudo -n 執行；是否具 root 權限以連線檢測與後端預檢為準。' : '後端未宣告支援免密 sudo，請改為直接執行。')
      : pcap.reason ?? (!pcap.supported ? '這個中控後端只支援直接執行。' : '');
    els.privilegeNote.hidden = !pnote;
    els.privilegeNote.textContent = pnote;
    els.keyField.hidden = !key || inventory;
    els.credentialHelp.hidden = inventory;
    const saved = editing?.hasCredential && editing.authentication === form.authentication;
    els.password.placeholder = saved ? '留空則保留原密碼' : '';
    els.privateKey.placeholder = saved ? '留空則保留原私鑰' : '-----BEGIN OPENSSH PRIVATE KEY-----';
    els.credentialHelp.textContent = saved
      ? `已儲存${AUTH_LABEL[editing.authentication]}（不顯示）；留空保留原憑據。${installed ? '已安裝的伺服器不能修改位置、主機或埠。' : ''}`
      : editing ? '改變認證方式時必須輸入新的憑據。憑據只送出一次，不會顯示或存在瀏覽器。' : '憑據只送出一次，中控加密保存，之後不會顯示或回傳，也不會存在瀏覽器。';

    // 主機指紋
    const signature = ss.addressSignature(form);
    const probeValid = probe && probe.signature === signature;
    const needs = ss.needsHostKeyCheck(form, editing) || forceHostKey;
    if (probeValid) {
      els.hostkeyStatus.textContent = probe.keys.length > 1 ? '取得多把主機公鑰；選擇要信任的一把，並在伺服器上核對指紋。這只是探測，尚未信任。' : '已取得主機公鑰；請在伺服器上核對指紋。這只是探測，尚未信任。';
    } else if (probe) {
      els.hostkeyStatus.textContent = '位置、主機、埠或使用者已改變，先前取得的指紋不再適用，請重新取得。';
    } else if (needs) {
      els.hostkeyStatus.textContent = editing ? '位置、主機、埠或使用者已改變（或要求重新核對），需要重新取得並確認主機指紋。' : '需要先取得主機指紋並確認，才能儲存登記。取得指紋只連線讀取主機公鑰，不登入、不執行指令。';
    } else {
      els.hostkeyStatus.replaceChildren('沿用已核對的指紋：', raw(`${editing.hostKey?.type ?? ''} ${editing.hostKey?.fingerprint ?? ''}`), '。主機金鑰變更時請按「重新核對主機指紋」。');
    }
    if (!probeValid) {
      // 沒有有效探測時一律清空，避免重新開啟表單後殘留上一台伺服器的金鑰。
      if (els.hostkeys.firstChild) els.hostkeys.replaceChildren();
      probedSignature = null;
    } else if (probedSignature !== signature + probe.keys.length) {
      probedSignature = signature + probe.keys.length;
      replace(els.hostkeys, h('fieldset', { class: 'hostkey-choices' }, h('legend', null, '主機公鑰'),
        probe.keys.map((k, i) => h('label', { class: 'choice' },
          h('input', { type: 'radio', name: 'server-hostkey', value: String(i), checked: i === keyIndex }),
          h('b', { translate: 'no' }, k.type), h('small', { class: 'mono fp', translate: 'no' }, k.fingerprint)))));
    }
    els.hostkeyCommand.hidden = !probeValid;
    els.hostkeyConfirmField.hidden = !probeValid;
    if (!probeValid) els.hostkeyConfirm.checked = false;
    els.hostkeyFetch.textContent = busy.has('probe') ? '取得中…' : probeValid ? '重新取得主機指紋' : editing && !needs ? '重新核對主機指紋' : '取得主機指紋';
    els.hostkeyFetch.disabled = busy.size > 0 || !canWrite() || Boolean(ss.hostProblem(ss.effectiveHost(form)) || ss.portProblem(form.port));

    const check = ss.serverFormCheck(form, editing);
    const problems = dirtyForm ? check.errors : [];
    replace(els.formProblems, problems.map((p) => h('li', null, p)));
    els.formProblems.hidden = problems.length === 0;
    els.save.disabled = busy.size > 0 || !canWrite() || check.errors.length > 0;
    els.formCancel.disabled = busy.has('save');
  }

  async function fetchHostKey() {
    const form = readForm();
    if (busy.size || !canWrite()) return;
    const host = ss.effectiveHost(form);
    const port = ss.parsePort(form.port);
    if (ss.hostProblem(host) || ss.portProblem(form.port)) { dirtyForm = true; render(); return; }
    const signature = ss.addressSignature(form);
    const g = gen;
    // 重新探測期間不保留舊金鑰的選取，避免確認到過時的指紋。
    probe = null;
    keyIndex = -1;
    els.hostkeyConfirm.checked = false;
    busy.add('probe');
    render();
    try {
      const res = await client.probeHostKey({ host, port });
      if (g !== gen) return;
      const keys = ss.normalizeHostKeys(res);
      if (!keys.length) { message('red', '伺服器沒有回傳可用的主機公鑰；未登記。'); return; }
      probe = { signature, keys };
      probedSignature = null; // 新的探測結果一定重畫，避免畫面殘留先前的選取
      keyIndex = keys.length === 1 ? 0 : -1;
      forceHostKey = true;
      els.hostkeyConfirm.checked = false;
      message('neutral', '');
    } catch (err) {
      if (g !== gen) return;
      handleFailure(err);
    } finally {
      if (g === gen) { busy.delete('probe'); render(); }
    }
  }

  function serverConfirmLines(body, server, hostKey) {
    const lines = [
      `名稱：${body.name}`,
      body.location === 'CONTROL_HOST' ? '位置：中控本機（運行中控的伺服器，不是開啟網頁的電腦）' : '位置：遠端伺服器',
      `連線：${ss.sshTarget({ ...body, privilege: body.privilege ?? 'NONE' })}`,
      `執行權限：${ss.PRIVILEGE_LABEL[body.privilege ?? 'NONE']}${body.privilege === 'SUDO' ? '（sudo -n，不傳 sudo 密碼）' : ''}`,
      body.credentialId
        ? `認證：私鑰（中控已配置的專用密鑰：${ss.credentialOption(ss.credentialInventoryCapability(data), body.credentialId)?.label ?? '未知標籤'}（${body.credentialId}），內容與路徑不經過瀏覽器）`
        : `認證：${AUTH_LABEL[body.authentication]}（${body.credential ? '使用新輸入的憑據，內容不顯示' : `保留原憑據${server && ss.credentialSourceText(server) ? `，來源：${ss.credentialSourceText(server, ss.credentialInventoryCapability(data))}` : ''}`}）`,
      `主機指紋：${hostKey.type} ${hostKey.fingerprint}${hostKey.fresh ? '（你已確認）' : '（沿用已核對）'}`,
    ];
    lines.push(server ? '修改後先前建立的計畫失效。' : '只儲存連線資料與加密的憑據；不登入、不執行指令。下一步可做連線檢測。');
    return lines;
  }

  async function submitForm(event) {
    event.preventDefault();
    dirtyForm = true;
    if (busy.size || !canWrite()) { render(); return; }
    const server = editing;
    const check = ss.serverFormCheck(readForm(), server);
    if (check.errors.length) { render(); return; }
    const preview = ss.serverRequestBody(readForm(), { server, requestId: '00000000-0000-4000-8000-000000000000' });
    const g = gen;
    busy.add('save');
    render();
    let body = null;
    try {
      const yes = await confirmDialog({ title: server ? '確認修改伺服器登記' : '確認添加 SSH 伺服器', lines: serverConfirmLines(preview, server, check.hostKey), confirmLabel: server ? '儲存修改' : '儲存登記' });
      if (!yes || g !== gen || !session.isActive()) return;
      // 確認後重新核對：清單可讀、可管理、主中控可寫、登記未被他人修改。
      await load();
      if (g !== gen) return;
      const current = server ? serverById(server.id) : null;
      const reasons = gateReasons();
      if (server && !current) reasons.push('這台伺服器的登記已不存在。');
      else if (server && current.revision !== server.revision) reasons.push('登記已被其他操作修改，請重新開啟表單。');
      if (reasons.length) { message('amber', '確認期間狀態已改變，未送出。', reasons); return; }
      body = ss.serverRequestBody(readForm(), { server: current, requestId: '00000000-0000-4000-8000-000000000000' });
      if (!body) { message('amber', '確認期間表單內容已改變，未送出。'); return; }
      const signature = ss.serverIntentSignature(body);
      body.requestId = pendingIntent?.signature === signature ? pendingIntent.requestId : randomUuid();
      pendingIntent = { signature, requestId: body.requestId, serverId: server?.id ?? body.requestId };
      clearSecrets();
      const res = server ? await client.updateServer(server.id, body) : await client.createServer(body);
      if (g !== gen) return;
      pendingIntent = null;
      const saved = ss.normalizeServer(res);
      lastSaved = saved?.id ?? (server ? null : body.requestId);
      closeForm();
      message('teal', server ? `已儲存「${body.name}」的修改。` : `已登記「${body.name}」。下一步：按「連線檢測」確認 Linux、Python、Docker Compose 與權限。`);
      await load();
    } catch (err) {
      if (g !== gen) return;
      clearSecrets();
      const intent = pendingIntent;
      handleFailure(err, {
        onUnknown: async () => {
          await load();
          if (g !== gen || !data || !intent) return;
          const found = serverById(intent.serverId);
          if (!server && found) {
            pendingIntent = null;
            lastSaved = found.id;
            closeForm();
            message('amber', '回應中斷，但重新讀取後確認登記已存在。', [`伺服器 UUID：${found.id}`]);
          } else if (server && found && found.revision !== server.revision) {
            pendingIntent = null;
            closeForm();
            message('amber', '回應中斷，重新讀取後登記的 revision 已改變；請檢查內容是否為你的修改。');
          } else {
            message('red', '結果未知，重新讀取後未看到變更。中控不會自動重送。', ['憑據已清空；如需重試，重新輸入憑據後再按儲存，會沿用同一個請求 ID，不會重複登記。']);
          }
        },
      });
    } finally {
      if (g === gen) { busy.delete('save'); render(); }
    }
  }

  // ---------- 檢測、移除 ----------

  async function testServer(server) {
    if (busy.size || !canWrite()) return;
    const g = gen;
    const key = `test:${server.id}`;
    busy.add(key);
    message('neutral', '');
    render();
    try {
      const res = await client.testServer(server.id, server.revision);
      if (g !== gen) return;
      const updated = ss.normalizeServer(res);
      if (updated && data) data = { ...data, servers: data.servers.map((s) => (s.id === updated.id ? updated : s)) };
      const st = updated ? ss.serverStatus(updated) : null;
      message(st?.tone === 'teal' ? 'teal' : 'amber', `「${server.name}」檢測完成：${st?.label ?? '回應格式無效'}。`, ['檢測只讀取版本與權限資訊，不安裝或修改伺服器。']);
      await load();
    } catch (err) {
      if (g !== gen) return;
      handleFailure(err, { onUnknown: () => load() });
    } finally {
      if (g === gen) { busy.delete(key); render(); }
    }
  }

  async function removeServer(server) {
    if (busy.size || !canWrite()) return;
    const g = gen;
    const key = `remove:${server.id}`;
    busy.add(key);
    render();
    try {
      const yes = await confirmDialog({
        title: `移除「${server.name}」的登記`,
        lines: [
          `只從中控刪除「${server.name}」的連線資料與加密的 SSH 憑據。`,
          '不會連線到伺服器，不刪除伺服器上的任何目錄、容器或資料。',
          server.installation ? '已安裝的業務站會繼續運行；移除後中控無法再對它更新。' : '之後若要使用，需重新添加並核對主機指紋。',
        ],
        confirmLabel: '移除登記',
      });
      if (!yes || g !== gen || !session.isActive()) return;
      await load();
      if (g !== gen) return;
      const current = serverById(server.id);
      const reasons = gateReasons();
      if (!current) reasons.push('登記已不存在。');
      else if (current.revision !== server.revision) reasons.push('登記已被其他操作修改，請重新確認。');
      if (reasons.length) { message('amber', '確認期間狀態已改變，未送出。', reasons); return; }
      const res = await client.removeServer(server.id, server.revision);
      if (g !== gen) return;
      if (res?.removed === true && res?.remoteDataDeleted === false) message('teal', `已移除「${server.name}」的登記；伺服器上的目錄、容器與資料都沒有刪除。`);
      else message('amber', '移除請求已回應，但回應內容無法確認；已重新讀取清單。');
      if (lastSaved === server.id) lastSaved = null;
      await load();
    } catch (err) {
      if (g !== gen) return;
      handleFailure(err, {
        onUnknown: async () => {
          await load();
          if (g !== gen || !data) return;
          if (!serverById(server.id)) message('amber', '回應中斷，重新讀取後登記已不存在（伺服器上的資料不受影響）。');
          else message('red', '結果未知，重新讀取後登記仍在。中控不會自動重送。');
        },
      });
    } finally {
      if (g === gen) { busy.delete(key); render(); }
    }
  }

  // ---------- 恢復安裝：來源任務（唯讀） ----------

  async function fetchRecoverySource(server, { force = false } = {}) {
    const jobId = server?.installation?.state === 'FAILED' ? server.installation.jobId : null;
    if (!jobId || !ss.isServerUuid(jobId)) { recoverySources.delete(server?.id); return; }
    const cur = recoverySources.get(server.id);
    if (!force && cur && cur.jobId === jobId && (cur.loading || cur.job || cur.error)) return;
    const g = gen;
    recoverySources.set(server.id, { jobId, loading: true, error: null, job: null });
    try {
      const j = ss.normalizeJob(await client.getServerJob(jobId));
      if (g !== gen) return;
      recoverySources.set(server.id, { jobId, loading: false, error: j ? null : { kind: 'protocol' }, job: j });
    } catch (err) {
      if (g !== gen) return;
      if (err?.status === 401 || err?.status === 403) { onAuthFailure(err); return; }
      recoverySources.set(server.id, { jobId, loading: false, error: err, job: null });
    } finally {
      if (g === gen) render();
    }
  }

  function refreshRecoverySources() {
    if (!data) return;
    for (const s of data.servers) {
      if (s.installation?.state === 'FAILED') fetchRecoverySource(s, { force: true });
      else recoverySources.delete(s.id);
    }
  }

  // ---------- 安裝／更新計畫 ----------

  function openPlan(server, kind) {
    view = 'plan';
    planCtx = { serverId: server.id, kind };
    plan = null;
    dirtyPlan = false;
    clearTimeout(planExpiryTimer);
    const releases = data?.releases ?? [];
    replace(els.planRelease, h('option', { value: '' }, '請選擇版本'), releases.map((r) => h('option', { value: r.id }, `${r.id}${r.sha256 ? ` · ${shortId(r.sha256, 12)}` : ''}`)));
    els.planRelease.value = '';
    els.planPort.value = String(ss.DEFAULT_HTTP_PORT);
    els.planUrl.value = '';
    els.planDocker.checked = false;
    // 身份：必須選擇時不預選；非必須時預選「不分配身份」（沿用舊獨立安裝）。
    const cap = ss.identityCapability(data);
    for (const r of els.planForm.querySelectorAll('input[name="server-plan-role"]')) r.checked = !cap.required && r.value === '';
    els.planSlot.value = '';
    // 版本更新沿用安裝時的埠與網址（唯讀）；恢復安裝使用原安裝的版本與設定，不需要這些欄位。
    if (kind === 'UPDATE' && server.installation) {
      els.planPort.value = String(server.installation.httpPort ?? '');
      els.planUrl.value = server.installation.appBaseUrl ?? '';
    }
    for (const input of [els.planPort, els.planUrl]) input.readOnly = kind !== 'INSTALL';
    els.planTitle.textContent = `${KIND_LABEL[kind]}計畫：${server.name}`;
    message('neutral', '');
    render();
    (kind === 'RECOVER' ? els.planCreate : els.planRelease).focus();
  }

  function closePlan() {
    view = 'list';
    planCtx = null;
    plan = null;
    clearTimeout(planExpiryTimer);
    for (const input of [els.planPort, els.planUrl]) input.readOnly = false;
    render();
  }

  const readPlanForm = () => ({
    releaseId: els.planRelease.value, httpPort: els.planPort.value, appBaseUrl: els.planUrl.value, installDocker: els.planDocker.checked,
    siteMode: els.planForm.querySelector('input[name="server-plan-role"]:checked')?.value ?? '', allocationSlot: els.planSlot.value,
  });
  const identity = () => ss.identityCapability(data);
  // 身份與分配：計畫、卡片與確認框共用的文字列。
  const identityRows = (x) => (x?.mode || x?.clusterId || x?.allocationSlot !== null && x?.allocationSlot !== undefined ? [
    ['站點角色', h('span', null, ss.SITE_MODE_LABEL[x.mode] ?? '未知角色', ' ', raw(x.mode ?? '—'))],
    ['分配編號', raw(x.allocationSlot ?? '—')],
    x.clusterId ? ['集群 ID', raw(x.clusterId)] : null,
  ] : []);

  function blockerList(codes) {
    return h('ul', { class: 'list list-amber' }, codes.map((c) => h('li', null, ss.serverCodeText(c) ?? `未定義的阻擋條件（${c}）`)));
  }

  function renderPlanView(server) {
    if (!plan) { els.planView.replaceChildren(); return; }
    const p = plan;
    const expired = ss.planExpired(p);
    const install = p.kind === 'INSTALL';
    const tone = p.state === 'READY' && !expired ? 'teal' : 'amber';
    const label = expired ? '已過期，請重新建立' : p.state === 'READY' ? '可執行' : '有阻擋條件';
    replace(els.planView,
      h('div', { class: 'plan-head' }, h('h3', { class: 'sub-title' }, '計畫 ', raw(p.id)), status(tone, label)),
      p.state === 'BLOCKED' && p.blockers.length ? h('div', { class: 'alert alert-amber' }, h('p', { class: 'message-title' }, '不能執行：'), blockerList(p.blockers),
        h('details', { class: 'disclosure' }, h('summary', null, '技術明細'), h('p', { class: 'mono', translate: 'no' }, p.blockers.join(', ')))) : null,
      p.kind === 'RECOVER' ? kv([
        ['目標伺服器', h('span', null, raw(server ? `${server.name} · ${ss.sshTarget(server)}` : p.serverId ?? '—'), `（revision ${p.serverRevision ?? '—'}）`)],
        ['來源任務', h('span', null, raw(p.sourceJobId ?? '—'), p.sourceStage ? ` · 失敗階段 ${p.sourceStage}` : '')],
        p.originalInstallJobId ? ['原安裝任務', raw(p.originalInstallJobId)] : null,
        ['原版本 SHA-256', raw(shortId(p.artifactSha256, 16))],
        ['站點／實例', raw(`${p.siteId ?? '—'} · ${p.instanceId ?? '—'}`)],
        ...identityRows(p).filter(Boolean).map(([k, v]) => [`${k}（保留原值）`, v]),
        ['業務 HTTP', h('span', null, raw(`127.0.0.1:${p.httpPort ?? '—'}`), ' · ', raw(p.appBaseUrl ?? '—'), '（不變）')],
        ['資料', p.dataPreserved ? '保留既有資料與 Docker 卷，不重建' : '計畫沒有明確回報保留資料'],
        ['密碼', p.passwordsPreserved ? '保留既有資料庫與管理員密碼，不重設' : '計畫沒有明確回報保留密碼'],
        ['管理員', p.administratorAction ? h('span', null, ss.ADMINISTRATOR_ACTION_TEXT[p.administratorAction] ?? '', ' ', raw(p.administratorAction)) : '—'],
        ['有效期限', `${formatTime(p.expiresAt)}`],
      ]) : null,
      p.kind === 'RECOVER' ? null : kv([
        ['目標伺服器', h('span', null, raw(server ? `${server.name} · ${ss.sshTarget(server)}` : p.serverId ?? '—'), `（revision ${p.serverRevision ?? '—'}）`)],
        ['版本', h('span', null, raw(p.releaseId ?? '—'), ' · SHA-256 ', raw(shortId(p.artifactSha256, 16)))],
        !install && p.fromSha256 ? ['目前版本', raw(shortId(p.fromSha256, 16))] : null,
        ['安裝目錄', h('span', null, raw(p.root ?? '—'), install ? '（固定；必須不存在，空目錄也不覆寫；不刪除既有目錄）' : '（沿用）')],
        ['業務 HTTP', h('span', null, raw(`127.0.0.1:${p.httpPort ?? '—'}`), '（只監聽目標主機，供目標主機 Nginx 轉發）')],
        ['對外網址', raw(p.appBaseUrl ?? '—')],
        ['服務', `${p.services.map((s) => ({ app: '業務後端', postgres: 'PostgreSQL', redis: 'Redis' })[s] ?? s).join('、') || '（未提供）'}；PostgreSQL 與 Redis 只在 Compose 內網，不公開主機埠`],
        ['物件儲存', p.storage?.endpoint ? h('span', null, '使用既有 RustFS ', raw(`${p.storage.endpoint}${p.storage.bucket ? ` · ${p.storage.bucket}` : ''}`)) : '使用中控既有的 RustFS 設定（不傳到瀏覽器）'],
        ...(install ? identityRows(p) : []),
        install && p.administratorAction ? ['管理員', h('span', null, ss.ADMINISTRATOR_ACTION_TEXT[p.administratorAction] ?? '未定義的管理員處理', p.administratorUsername ? h('span', null, '（', raw(p.administratorUsername), '）') : null, ' ', raw(p.administratorAction))]
          : install && p.administratorUsername ? ['初始管理員', h('span', null, '複製目前管理員 ', raw(p.administratorUsername), ' 到新業務站；之後兩邊帳密各自獨立變更')] : null,
        p.sameMachine ? ['同機', '與中控在同一台伺服器：使用獨立目錄與埠，不更新或覆蓋中控'] : null,
        install ? ['Docker', p.installDocker ? '將以伺服器既有 apt 軟體來源安裝 Docker／Compose' : '不改動系統套件（只使用已安裝的 Docker／Compose）'] : null,
        ['集群', p.mode === 'EXTERNAL' || p.clusterId ? '安裝成功後是「已安裝 · 等待接入」，不是 ACTIVE 成員；接入需另外完成' : '安裝後為獨立業務站，尚未加入多站集群；加入需另外配置資料面與加入協議'],
        ['有效期限', `${formatTime(p.expiresAt)}（15 分鐘）`],
      ]),
      p.environment ? h('details', { class: 'disclosure' }, h('summary', null, '環境明細'), kv(Object.entries(p.environment).map(([k, v]) => [k, raw(typeof v === 'string' ? v : JSON.stringify(v))]))) : null);
    clearTimeout(planExpiryTimer);
    const left = p.expiresAt ? Date.parse(p.expiresAt) - Date.now() : NaN;
    if (Number.isFinite(left) && left > 0) planExpiryTimer = setTimeout(() => render(), Math.min(left + 50, 2 ** 31 - 1));
  }

  function renderPlan() {
    els.planPanel.hidden = view !== 'plan';
    if (view !== 'plan' || !planCtx) return;
    const server = serverById(planCtx.serverId);
    const install = planCtx.kind === 'INSTALL';
    const recover = planCtx.kind === 'RECOVER';
    els.planRelease.closest('label').hidden = recover;
    els.planInstallFields.hidden = recover;
    els.planDocker.closest('label').hidden = !install;
    const cap = identity();
    const form = readPlanForm();
    els.planIdentity.hidden = !install || !cap.supported;
    els.planRoleLegacy.hidden = cap.required;
    for (const r of els.planForm.querySelectorAll('input[name="server-plan-role"]')) r.disabled = r.value !== '' && !cap.modes.includes(r.value);
    els.planSlotField.hidden = !(install && cap.supported && form.siteMode === 'EXTERNAL');
    const note = !install ? '' : cap.unknown ? cap.reason
      : cap.supported && form.siteMode === 'EXTERNAL' ? '校外業務站安裝成功後是「已安裝 · 等待接入」：不是 ACTIVE 成員，也沒有同步管理員帳號；接入需另外完成。'
        : cap.supported && cap.modes.length < 2 ? `後端只支援：${cap.modes.map((m) => ss.SITE_MODE_LABEL[m]).join('、')}。` : '';
    els.planIdentityNote.hidden = !note;
    els.planIdentityNote.textContent = note;
    els.planUpdateNote.hidden = install;
    if (planCtx.kind === 'UPDATE') els.planUpdateNote.textContent = '版本更新不會改動業務 HTTP 埠與對外網址（以下為安裝時的值，唯讀）；計畫會檢查恢復演練與相容性審查。';
    if (recover) els.planUpdateNote.textContent = '恢復安裝使用原安裝的版本、站點身份、資料與密碼，不需要選擇版本；先建立預覽，確認後才執行。';
    let errors;
    if (recover) {
      const rec = ss.recoveryAvailability(server, recoverySources.get(planCtx.serverId));
      errors = rec.mode === 'recover' ? [] : rec.reasons;
    } else {
      errors = ss.installFormCheck(readPlanForm(), { kind: planCtx.kind, releases: data?.releases ?? [], identity: identity() });
      if (!data?.releases.length) errors.unshift('中控沒有可用的版本（RELEASE_NOT_CONFIGURED）。');
      if (planCtx.kind === 'UPDATE' && server) errors.push(...ss.updateAvailability(server).reasons);
    }
    const shown = dirtyPlan ? errors : [];
    replace(els.planProblems, shown.map((e) => h('li', null, e)));
    els.planProblems.hidden = shown.length === 0;
    els.planCreate.disabled = busy.size > 0 || !canWrite() || !server || errors.length > 0 || !data?.installationEnabled;
    els.planCreate.textContent = busy.has('plan') ? '檢查中…（最多 45 秒）' : plan ? '重新建立計畫' : '建立計畫';
    renderPlanView(server);
    // 已有此計畫的任務時不再提供執行（重送同一計畫只會回傳原任務）；要重試請重新建立計畫。
    const used = Boolean(plan && job && job.id === plan.id);
    const executable = plan && plan.state === 'READY' && !ss.planExpired(plan) && !used;
    els.planExecute.hidden = !plan || used;
    els.planExecute.textContent = busy.has('execute') ? '送出中…' : install ? '確認安裝…' : recover ? '確認恢復安裝…' : '確認更新…';
    els.planExecute.disabled = !executable || busy.size > 0 || !canWrite();
  }

  async function createPlan(event) {
    event.preventDefault();
    dirtyPlan = true;
    if (busy.size || !canWrite() || !planCtx) { render(); return; }
    const server = serverById(planCtx.serverId);
    const recover = planCtx.kind === 'RECOVER';
    if (recover && ss.recoveryAvailability(server, recoverySources.get(planCtx.serverId)).mode !== 'recover') { render(); return; }
    if (planCtx.kind === 'UPDATE' && server && ss.updateAvailability(server).reasons.length) { render(); return; }
    const body = server && (recover ? ss.recoveryRequestBody(server) : ss.installRequestBody(readPlanForm(), { kind: planCtx.kind, server, releases: data.releases, identity: identity() }));
    if (!body) { render(); return; }
    const g = gen;
    busy.add('plan');
    plan = null;
    render();
    try {
      const res = recover ? await client.createRecoveryPlan(server.id, body) : await client.createServerPlan(server.id, planCtx.kind, body);
      if (g !== gen) return;
      plan = ss.normalizePlan(res);
      if (!plan) message('red', '計畫回應格式無效；未建立可執行的計畫。');
      else message('neutral', '');
    } catch (err) {
      if (g !== gen) return;
      handleFailure(err, { onUnknown: () => message('red', '建立計畫的結果未知。建立計畫只做唯讀檢查，可以再按一次「建立計畫」。') });
    } finally {
      if (g === gen) { busy.delete('plan'); render(); }
    }
  }

  function executeLines(p, server) {
    if (p.kind === 'RECOVER') {
      return [
        `恢復安裝：${server.name} · ${ss.sshTarget(server)}`,
        `來源任務：${p.sourceJobId}；原版本 SHA-256 ${shortId(p.artifactSha256, 16)}`,
        `站點／實例：${p.siteId ?? '—'} · ${p.instanceId ?? '—'}`,
        '保留既有資料、Docker 卷與密碼；不重建卷、不重設密碼、不刪除資料。',
        `管理員：${ss.ADMINISTRATOR_ACTION_TEXT[p.administratorAction] ?? p.administratorAction ?? '—'}`,
        '完成後仍未加入多站集群。結果未知時不自動重送，請以任務 UUID 核對。',
      ];
    }
    const install = p.kind === 'INSTALL';
    return [
      `${KIND_LABEL[p.kind]}：${server.name} · ${ss.sshTarget(server)}`,
      `版本：${p.releaseId} · SHA-256 ${shortId(p.artifactSha256, 16)}`,
      install ? `安裝目錄：${p.root}（必須不存在；空目錄也不覆寫）` : `安裝目錄：${p.root}（沿用）`,
      `業務 HTTP：127.0.0.1:${p.httpPort}（只供目標主機 Nginx 轉發）`,
      '服務：業務後端、PostgreSQL、Redis；PostgreSQL 與 Redis 不公開主機埠',
      '物件儲存：使用既有 RustFS',
      install && p.mode ? `站點角色：${ss.SITE_MODE_LABEL[p.mode] ?? p.mode} · 分配編號 ${p.allocationSlot ?? '—'}（首次執行前永久保留）` : null,
      install && p.administratorAction ? `管理員：${ss.ADMINISTRATOR_ACTION_TEXT[p.administratorAction] ?? p.administratorAction}` : install && p.administratorUsername ? `初始管理員：複製目前管理員「${p.administratorUsername}」，之後各自獨立變更` : null,
      install && (p.mode === 'EXTERNAL' || p.clusterId) ? '安裝成功後是等待接入，不是 ACTIVE 成員。' : null,
      install && p.installDocker ? '會以伺服器既有 apt 軟體來源安裝 Docker／Compose' : null,
      '不刪除既有目錄，不更動中控；完成後仍未加入多站集群。',
      '結果未知時不自動重送，請以任務 UUID 核對遠端記錄。',
    ].filter(Boolean);
  }

  async function executePlan() {
    if (busy.size || !canWrite() || !plan || !planCtx) return;
    const server = serverById(planCtx.serverId);
    if (!server) return;
    const captured = plan;
    const g = gen;
    busy.add('execute');
    render();
    try {
      const titles = { INSTALL: ['確認首次安裝', '確認安裝'], UPDATE: ['確認版本更新', '確認更新'], RECOVER: ['確認恢復安裝', '確認恢復'] }[captured.kind];
      const yes = await confirmDialog({ title: titles[0], lines: executeLines(captured, server), confirmLabel: titles[1] });
      if (!yes || g !== gen || !session.isActive()) return;
      // 確認後重新核對：計畫仍可執行、未過期，伺服器 revision 未變，入口仍可管理。
      const fresh = ss.normalizePlan(await client.getServerPlan(captured.id));
      if (g !== gen) return;
      await load();
      if (g !== gen) return;
      const current = serverById(server.id);
      const reasons = gateReasons();
      if (!fresh) reasons.push('無法重新讀取計畫。');
      else {
        if (fresh.state !== 'READY') reasons.push('計畫已有阻擋條件。');
        if (ss.planExpired(fresh)) reasons.push('計畫已過期，請重新建立。');
        plan = fresh;
      }
      if (!current) reasons.push('伺服器登記已不存在。');
      else if (fresh && current.revision !== fresh.serverRevision) reasons.push('伺服器登記已被修改，計畫失效，請重新建立。');
      if (current && captured.kind === 'UPDATE') reasons.push(...ss.updateAvailability(current).reasons);
      if (current && captured.kind === 'RECOVER') {
        // 恢復：重新讀取來源任務，仍須是已核實失敗、且與登記和計畫一致。
        await fetchRecoverySource(current, { force: true });
        if (g !== gen) return;
        const rec = ss.recoveryAvailability(serverById(server.id), recoverySources.get(server.id));
        if (rec.mode !== 'recover') reasons.push(...rec.reasons);
        if (fresh && fresh.sourceJobId !== current.installation?.jobId) reasons.push('來源任務與目前登記不符。');
      }
      if (reasons.length) { message('amber', '確認期間狀態已改變，未送出。', reasons); return; }
      const res = await client.executeServerPlan(captured.id);
      if (g !== gen) return;
      const j = ss.normalizeJob(res);
      if (!j) { message('amber', '執行請求已回應，但任務格式無效；請以計畫 UUID 查詢任務。'); els.jobLookupId.value = captured.id; return; }
      setJob(j);
      message('teal', `已送出${KIND_LABEL[captured.kind]}任務。`, ['任務只有總體狀態；完成前可離開此頁，之後以任務 UUID 查詢。']);
    } catch (err) {
      if (g !== gen) return;
      handleFailure(err, {
        onUnknown: async () => {
          els.jobLookupId.value = captured.id;
          message('red', '執行結果未知。中控不會自動重送；正以原計畫 UUID 查詢任務。');
          await lookupJob(captured.id, { quietNotFound: true });
          if (g === gen && (!job || job.id !== captured.id)) message('red', '執行結果未知，以計畫 UUID 查不到任務。', ['中控不會自動重送。稍後可再查詢；同一計畫再次執行會回傳原任務，不會重複安裝。']);
        },
      });
    } finally {
      if (g === gen) { busy.delete('execute'); render(); }
    }
  }

  // ---------- 任務 ----------

  function stopJobPoll() {
    clearTimeout(jobTimer);
    jobTimer = null;
  }

  function scheduleJobPoll() {
    stopJobPoll();
    if (!onPage || !ss.jobActive(job) || !session.isActive()) return;
    const g = gen;
    const id = job.id;
    jobTimer = setTimeout(async () => {
      jobTimer = null;
      if (g !== gen || !session.isActive()) return;
      try {
        const j = ss.normalizeJob(await client.getServerJob(id));
        if (g !== gen) return;
        if (j && job && j.id === job.id) setJob(j);
        else scheduleJobPoll();
      } catch (err) {
        if (g !== gen) return;
        if (err?.status === 401 || err?.status === 403) { onAuthFailure(err); return; }
        scheduleJobPoll();
      }
    }, JOB_POLL_MS);
  }

  function setJob(j) {
    const prev = job;
    const wasActive = ss.jobActive(prev) && prev?.id === j.id;
    job = j;
    if (wasActive && !ss.jobActive(j)) {
      // 任務結束時取代「已送出」提示，避免失敗後仍顯示送出成功。
      const [tone, label] = ss.jobCopy(j.state);
      message(tone, `${KIND_LABEL[j.kind] ?? '任務'}已結束：${label}（${j.state}）。`, j.state === 'FAILED' ? ['詳見下方任務明細。'] : []);
    }
    // 第一次看到某任務的結束狀態（含結果未知後查回時已結束）都重新讀取清單，讓卡片反映後端的安裝狀態。
    if (!ss.jobActive(j) && (wasActive || prev?.id !== j.id || prev?.state !== j.state)) load();
    render();
    scheduleJobPoll();
  }

  async function lookupJob(id, { quietNotFound = false } = {}) {
    if (!session.isActive()) return;
    if (!ss.isServerUuid(id)) { message('red', '請輸入有效的任務或計畫 UUID。'); return; }
    const g = gen;
    busy.add('lookup');
    render();
    try {
      const j = ss.normalizeJob(await client.getServerJob(id));
      if (g !== gen) return;
      if (!j) message('red', '任務回應格式無效。');
      else setJob(j);
    } catch (err) {
      if (g !== gen) return;
      if (err?.status === 404 && quietNotFound) return;
      if (err?.status === 404) message('amber', ss.serverCodeText('SERVER_JOB_NOT_FOUND'));
      else handleFailure(err);
    } finally {
      if (g === gen) { busy.delete('lookup'); render(); }
    }
  }

  async function reconcileJob() {
    if (busy.size || !canWrite() || !job || job.state !== 'OUTCOME_UNKNOWN') return;
    const captured = job;
    const g = gen;
    busy.add('reconcile');
    render();
    try {
      const yes = await confirmDialog({ title: '核對遠端記錄', lines: ['只讀取目標伺服器上的安裝／更新記錄，不重新安裝或更新。', `任務：${captured.id}`], confirmLabel: '核對記錄' });
      if (!yes || g !== gen || !session.isActive()) return;
      await load();
      if (g !== gen) return;
      const reasons = gateReasons();
      if (reasons.length) { message('amber', '確認期間狀態已改變，未送出。', reasons); return; }
      const j = ss.normalizeJob(await client.reconcileServerJob(captured.id));
      if (g !== gen) return;
      if (j) { setJob(j); message(j.state === 'SUCCEEDED' ? 'teal' : 'amber', `核對完成：${ss.jobCopy(j.state)[1]}（${j.state}）。`); } else message('amber', '核對已回應，但任務格式無效。');
      await load();
    } catch (err) {
      if (g !== gen) return;
      handleFailure(err, { onUnknown: () => lookupJob(captured.id) });
    } finally {
      if (g === gen) { busy.delete('reconcile'); render(); }
    }
  }

  function resultValue(v) {
    if (v === null || v === undefined) return '—';
    return typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? String(v) : JSON.stringify(v);
  }

  function renderJob() {
    els.jobReconcile.hidden = !(job && job.state === 'OUTCOME_UNKNOWN');
    els.jobReconcile.disabled = busy.size > 0 || !canWrite();
    els.jobReconcile.textContent = busy.has('reconcile') ? '核對中…' : '核對遠端記錄…';
    els.jobRefresh.hidden = !job;
    els.jobRefresh.disabled = busy.size > 0;
    els.jobLookupSubmit.disabled = busy.size > 0 || !session.isActive();
    if (!job) { replace(els.jobView, h('p', { class: 'empty' }, '本次登入尚未建立或查詢任務。')); return; }
    const [tone, label] = ss.jobCopy(job.state);
    const result = job.result ?? {};
    const server = job.serverId ? serverById(job.serverId) : null;
    replace(els.jobView,
      h('div', { class: 'plan-head' }, h('h3', { class: 'sub-title' }, `${KIND_LABEL[job.kind] ?? job.kind ?? '任務'} `, raw(job.id)), status(tone, `${label} · ${job.state}`)),
      kv([
        ['伺服器', raw(server ? server.name : job.serverId ?? '—')],
        ['建立時間', formatTime(job.createdAt)],
        ['版本 SHA-256', raw(shortId(job.artifactSha256, 16))],
        job.code ? ['代碼', h('span', null, ss.serverCodeText(job.code) ?? '', ' ', raw(job.code))] : null,
        ss.jobStageText(result.stage) ? ['階段', h('span', null, ss.jobStageText(result.stage), ' ', raw(result.stage))] : null,
        ...Object.entries(result).filter(([k]) => k !== 'stage' || !ss.jobStageText(result.stage)).map(([k, v]) => [k, raw(resultValue(v))]),
      ]),
      ss.jobActive(job) ? h('p', { class: 'hint' }, `任務只有總體狀態；每 ${JOB_POLL_MS / 1000} 秒自動重新讀取（只在此頁）。`) : null,
      job.state === 'OUTCOME_UNKNOWN' ? h('div', { class: 'alert alert-red' }, h('p', { class: 'message-title' }, '結果未知；中控不會自動重送。'), h('p', null, '請按「核對遠端記錄」，只讀取遠端記錄，不重新安裝或更新。')) : null,
      job.state === 'FAILED' && result.retryable === true ? h('div', { class: 'callout' }, h('p', { class: 'note' }, '失敗發生在資料庫啟動前，已清理本任務新建的檔案；伺服器回到未安裝。修好環境後可重新建立計畫。')) : null,
      job.state === 'FAILED' && result.retryable !== true ? h('div', { class: 'alert alert-red' }, h('p', { class: 'message-title' }, '任務失敗。'), h('p', null, '若失敗發生在資料庫啟動後，目標主機上的資料會保留，伺服器顯示為安裝失敗，不能當作未安裝。')) : null,
      job.state === 'SUCCEEDED' && job.kind === 'INSTALL' ? h('div', { class: 'callout' }, h('p', { class: 'note' }, '已安裝為獨立業務站，尚未加入多站集群；加入需另外配置資料面與加入協議。')) : null,
      result.restoreEvidenceId ? h('p', { class: 'hint' }, 'restoreEvidenceId 來自首次對新資料庫的實際備份與臨時庫恢復演練；不等於未來任意業務資料的災備驗收。') : null);
  }

  // ---------- 接入集群（空集群首個中控本機權威站） ----------

  const clusterJobActive = () => clusterPending !== null || ss.jobActive(clusterJob);
  // 唯一的接入任務讀取入口（唯讀 GET）：輪詢、查詢與恢復前重讀來源任務共用。
  const readClusterJob = (id) => client.getClusterJob(id);

  const isRecovery = () => clusterCtx?.kind === ss.MEMBER_RECOVERY_KIND;

  // 恢復原設定：沿用接入開關與寫入許可（租約／可管理），不要求「可接入」的條件。
  function recoveryReasons(server) {
    const reasons = gateReasons();
    if (!server) { reasons.push('伺服器登記已不存在。'); return reasons; }
    if (!data?.clusterEnrollmentEnabled) reasons.push(ss.SERVER_CODE_TEXT.CLUSTER_ENROLLMENT_DISABLED);
    if (clusterJobActive()) reasons.push('已有進行中的接入任務。');
    return reasons;
  }

  function clusterReasons(server) {
    if (isRecovery()) return recoveryReasons(server);
    const reasons = gateReasons();
    if (!server) { reasons.push('伺服器登記已不存在。'); return reasons; }
    const enroll = ss.enrollmentAvailability(server, data);
    if (!enroll.show) reasons.push('這台伺服器不是「已安裝且尚未加入集群」的狀態。');
    reasons.push(...enroll.reasons);
    if (clusterJobActive()) reasons.push('已有進行中的接入任務。');
    return reasons;
  }

  function openCluster(server) {
    view = 'cluster';
    // 接入方式由伺服器的安裝角色決定：校內權威站（INITIAL_AUTHORITY）或已安裝的校外業務站（REMOTE_MEMBER）。
    clusterCtx = { serverId: server.id, kind: ss.enrollmentAvailability(server, data).kind ?? null };
    clusterPlan = null;
    message('neutral', '');
    render();
    els.clusterPlanCreate.focus();
  }

  // 由失敗的接入任務進入恢復原設定預覽（任務歷史打開的任務也可用）。
  function openMemberRecovery(failed) {
    if (!failed || ss.memberRecoverySourceProblems(failed).length) return;
    view = 'cluster';
    clusterCtx = { serverId: failed.serverId, kind: ss.MEMBER_RECOVERY_KIND, failedId: failed.id };
    clusterPlan = null;
    message('neutral', '');
    render();
    els.clusterPlanCreate.focus();
  }

  function closeCluster() {
    view = 'list';
    clusterCtx = null;
    clusterPlan = null;
    render();
  }

  const CLUSTER_RELOAD_CODES = ['AUTHORITY_PLAN_CHANGED', 'AUTHORITY_PLAN_EXPIRED', 'CLUSTER_ALREADY_INITIALIZED', 'SERVER_NOT_INSTALLED', 'CLUSTER_ENROLLMENT_DISABLED'];

  async function createClusterPlan() {
    if (busy.size || !clusterCtx) return;
    const server = serverById(clusterCtx.serverId);
    const reasons = clusterReasons(server);
    if (reasons.length) { message('amber', '目前不能建立接入計畫。', reasons); render(); return; }
    const g = gen;
    busy.add('cplan');
    clusterPlan = null;
    render();
    try {
      let p;
      if (isRecovery()) {
        // 建立前重讀來源任務：只有仍是 FAILED＋MEMBER_CONFIGURATION_FAILED_PRESERVED 才送出。
        const failedId = clusterCtx.failedId;
        const source = ss.normalizeClusterJob(await readClusterJob(failedId));
        if (g !== gen) return;
        const problems = source ? ss.memberRecoverySourceProblems(source) : ['無法讀取來源任務。'];
        if (source && source.serverId !== clusterCtx.serverId) problems.push('來源任務的伺服器與目前不符。');
        if (problems.length) { message('amber', '目前不能建立恢復預覽。', problems); return; }
        p = ss.normalizeClusterPlan(await client.createMemberRecoveryPlan(failedId));
      } else {
        p = ss.normalizeClusterPlan(await client.createClusterPlan(server.id, server.revision));
      }
      if (g !== gen) return;
      clusterPlan = p;
      if (p) message('neutral', ''); else message('red', '接入計畫回應格式無效；未建立可執行的計畫。');
    } catch (err) {
      if (g !== gen) return;
      handleFailure(err, { reloadOn: CLUSTER_RELOAD_CODES, onUnknown: () => message('red', isRecovery() ? '建立恢復預覽的結果未知。建立預覽不會改動資料，可以再按一次（會產生新的預覽 UUID）。' : '建立接入計畫的結果未知。建立計畫不會改動資料，可以再按一次。') });
    } finally {
      if (g === gen) { busy.delete('cplan'); render(); }
    }
  }

  // 前端執行條件（READY、有效期、身份完整）＋計畫類型必須與這台伺服器的接入方式一致。
  function clusterPlanProblems(p) {
    const problems = ss.clusterPlanProblems(p);
    if (clusterCtx?.kind && p.kind !== clusterCtx.kind) problems.push('計畫類型與這台伺服器的接入方式不符。');
    if (isRecovery() && (p.failedId !== clusterCtx.failedId || p.serverId !== clusterCtx.serverId)) problems.push('恢復計畫的來源任務或伺服器與目前不符。');
    return problems;
  }

  const INTRO = {
    INITIAL_AUTHORITY: '首次接入只支援空集群：把「中控本機」的已安裝校內業務站設為首個權威站。中控會改接該站的資料庫並短暫重啟（約 30–120 秒）；兩邊原有資料與帳號都保留。這是單站集群，跨站同步尚未啟用。',
    REMOTE_MEMBER: '把已安裝的校外業務站設定並加入現有集群：保留原身份、分配、資料與帳號，中控不重啟。私有設定與網路就緒由後端預檢；這不是安裝第二中控。',
    [ss.MEMBER_RECOVERY_KIND]: '成員設定失敗後恢復原設定：以新的任務 UUID 還原失敗接入前的應用設定並只重建應用；保留原資料庫、身份與舊的失敗任務，中控不重啟。恢復後仍未加入集群，需要另外建立新的接入計畫。',
  };

  function renderClusterPlanView(server) {
    const p = clusterPlan;
    if (!p) { els.clusterPlanView.replaceChildren(); return; }
    const problems = clusterPlanProblems(p);
    const expired = ss.planExpired(p);
    const tone = problems.length === 0 ? 'teal' : 'amber';
    const label = expired ? '已過期，請重新建立' : problems.length === 0 ? '可執行' : p.state === 'READY' ? '不能執行' : '有阻擋條件';
    if (p.kind === ss.MEMBER_RECOVERY_KIND) {
      replace(els.clusterPlanView,
        h('div', { class: 'plan-head' }, h('h3', { class: 'sub-title' }, '恢復原設定預覽 ', raw(p.id)), status(tone, label)),
        p.blockers.length ? h('div', { class: 'alert alert-amber' }, h('p', { class: 'message-title' }, '不能執行：'), blockerList(p.blockers),
          h('details', { class: 'disclosure' }, h('summary', null, '技術明細'), h('p', { class: 'mono', translate: 'no' }, p.blockers.join(', ')))) : null,
        problems.length && p.state === 'READY' ? h('ul', { class: 'list list-amber' }, problems.map((x) => h('li', null, x))) : null,
        kv([
          ['方式', h('span', null, '恢復成員原設定 ', raw(p.kind))],
          ['來源失敗任務', raw(p.failedId ?? '—')],
          ['目標伺服器', h('span', null, raw(server ? `${server.name} · ${ss.sshTarget(server)}` : p.serverId ?? '—'), `（revision ${p.serverRevision ?? '—'}）`)],
          ['原身份', raw(`${p.siteId ?? '—'} · ${p.instanceId ?? '—'} · slot ${p.allocationSlot ?? '—'}`)],
          ['集群', raw(p.clusterId ?? '—')],
          ['原版本 SHA-256', raw(p.artifactSha256 ?? '—')],
          ['資料', p.originalDatabasePreserved ? '保留原資料庫、Redis、帳號與舊的失敗任務；只還原應用設定並重建應用。' : '計畫沒有明確回報保留原資料，不能執行。'],
          ['恢復後', p.newEnrollmentPlanRequired ? '仍未加入集群（NOT_JOINED）；需要另外建立新的接入計畫。' : '計畫沒有明確回報恢復後仍需新的接入計畫，不能執行。'],
          ['中控重啟', p.controllerRestartDeclaredFalse ? '不重啟' : '計畫沒有明確回報不重啟，不能執行。'],
          ['有效期限', p.expiresAt && Number.isFinite(Date.parse(p.expiresAt)) ? formatTime(p.expiresAt) : '未知（不能執行）'],
        ]));
      return;
    }
    if (p.kind === 'REMOTE_MEMBER') {
      replace(els.clusterPlanView,
        h('div', { class: 'plan-head' }, h('h3', { class: 'sub-title' }, '接入計畫 ', raw(p.id)), status(tone, label)),
        p.blockers.length ? h('div', { class: 'alert alert-amber' }, h('p', { class: 'message-title' }, '不能執行：'), blockerList(p.blockers),
          h('details', { class: 'disclosure' }, h('summary', null, '技術明細'), h('p', { class: 'mono', translate: 'no' }, p.blockers.join(', ')))) : null,
        problems.length && p.state === 'READY' ? h('ul', { class: 'list list-amber' }, problems.map((x) => h('li', null, x))) : null,
        kv([
          ['方式', h('span', null, '校外業務站接入 ', raw(p.kind))],
          ['伺服器', h('span', null, raw(server ? `${server.name} · ${ss.sshTarget(server)}` : p.serverId ?? '—'), `（revision ${p.serverRevision ?? '—'}）`)],
          ['站點／實例', raw(`${p.siteId ?? '—'} · ${p.instanceId ?? '—'}`)],
          ['角色／分配', h('span', null, `${ss.SITE_MODE_LABEL[p.mode] ?? '未知角色'} `, raw(`${p.mode ?? '—'} · slot ${p.allocationSlot ?? '—'}`), '（保留原值）')],
          ['集群', raw(p.clusterId ?? '—')],
          p.profileId ? ['成員設定', raw(p.profileId)] : null,
          ['版本 SHA-256', raw(p.artifactSha256 ?? '—')],
          ['基準 epoch', raw(p.baseEpoch ?? '—')],
          ['資料與帳號', p.originalDatabasePreserved ? '保留原資料、資料庫、Redis 與帳號，不重置' : '計畫沒有明確回報保留原資料。'],
          ['中控重啟', p.controllerRestartExpected ? '計畫回報會重啟中控。' : '不重啟'],
          ['有效期限', formatTime(p.expiresAt)],
        ]));
      return;
    }
    replace(els.clusterPlanView,
      h('div', { class: 'plan-head' }, h('h3', { class: 'sub-title' }, '接入計畫 ', raw(p.id)), status(tone, label)),
      p.blockers.length ? h('div', { class: 'alert alert-amber' }, h('p', { class: 'message-title' }, '不能執行：'), blockerList(p.blockers),
        h('details', { class: 'disclosure' }, h('summary', null, '技術明細'), h('p', { class: 'mono', translate: 'no' }, p.blockers.join(', ')))) : null,
      kv([
        ['方式', h('span', null, '空集群的首個權威站 ', raw(p.kind))],
        ['伺服器', h('span', null, raw(server ? `${server.name} · ${ss.sshTarget(server)}` : p.serverId ?? '—'), `（revision ${p.serverRevision ?? '—'}）`)],
        ['站點', h('span', null, raw(`${p.siteId ?? '—'} · ${p.instanceId ?? '—'}`), ` · slot ${p.allocationSlot ?? '—'}`)],
        ['集群', raw(p.clusterId ?? '—')],
        ['業務 HTTP', h('span', null, raw(`127.0.0.1:${p.httpPort ?? '—'}`), '（不變）')],
        ['權威資料庫', h('span', null, raw(`127.0.0.1:${p.databasePort ?? '—'}`), '（PostgreSQL 只在目標主機回環位址公開）')],
        ['資料與帳號', p.originalDatabasePreserved
          ? '保留業務站的 siteId、instanceId、資料、管理員與資料庫指紋；中控原本的資料庫也保留，不刪除。'
          : '計畫沒有明確回報保留中控原資料庫。'],
        p.administratorUsername ? ['管理員', h('span', null, '業務站管理員 ', raw(p.administratorUsername), ' 保留；不需要輸入密碼')] : null,
        ['中控重啟', p.controllerRestartExpected ? '中控會改接權威資料庫並短暫重啟（約 30–120 秒）；期間此頁會顯示重新連線並自動讀取任務。' : '計畫未預期重啟。'],
        ['範圍', p.singleSiteOnly ? '單站集群：跨站同步尚未啟用，新增其他成員會被拒絕。' : '（計畫未標示單站；仍不代表跨站同步已就緒）'],
        ['有效期限', formatTime(p.expiresAt)],
      ]));
  }

  function renderCluster() {
    els.clusterPanel.hidden = view !== 'cluster';
    if (view !== 'cluster' || !clusterCtx) return;
    const server = serverById(clusterCtx.serverId);
    const reasons = clusterReasons(server);
    els.clusterPlanCreate.disabled = busy.size > 0 || reasons.length > 0;
    const recovery = isRecovery();
    els.clusterPlanCreate.textContent = busy.has('cplan') ? '檢查中…（最多 45 秒）' : recovery ? (clusterPlan ? '重新建立恢復預覽' : '建立恢復預覽') : clusterPlan ? '重新建立接入計畫' : '建立接入計畫';
    els.clusterCancel.disabled = busy.has('cplan') || busy.has('cexec');
    els.clusterIntro.textContent = INTRO[clusterCtx.kind] ?? '這台伺服器的接入方式未知，已停用。';
    renderClusterPlanView(server);
    const used = Boolean(clusterPlan && (clusterJob?.id === clusterPlan.id || clusterPending === clusterPlan.id || sentRecoveryPlans.has(clusterPlan.id)));
    els.clusterExecute.hidden = !clusterPlan || used;
    els.clusterExecute.textContent = busy.has('cexec') ? '送出中…' : recovery ? '確認恢復原設定…' : '確認接入集群…';
    els.clusterExecute.disabled = !clusterPlan || used || busy.size > 0 || clusterPlanProblems(clusterPlan).length > 0 || reasons.length > 0;
  }

  function clusterConfirmLines(p, server) {
    if (p.kind === ss.MEMBER_RECOVERY_KIND) {
      return [
        `恢復「${server.name}」（${ss.sshTarget(server)}）在失敗接入前的應用設定，只重建應用。`,
        `來源失敗任務：${p.failedId}；原身份 ${p.siteId} · ${p.instanceId} · slot ${p.allocationSlot}。`,
        '保留原資料庫、Redis、帳號與舊的失敗任務；中控不重啟。',
        '恢復成功後仍未加入集群（NOT_JOINED）；需要另外建立新的接入計畫。',
        '沒有取消端點；送出結果不確定時只以這份預覽 UUID 查詢與核對，不會自動重送。',
      ];
    }
    if (p.kind === 'REMOTE_MEMBER') {
      return [
        `設定並接入校外業務站「${server.name}」（${ss.sshTarget(server)}）。`,
        `站點：${p.siteId} · ${p.instanceId}；分配編號 ${p.allocationSlot}（保留原值）；基準 epoch ${p.baseEpoch}。`,
        '保留原資料、資料庫、Redis 與帳號；只更新應用設定並加入集群，中控不重啟。',
        '遠端設定完成或等待加入都不算已加入；只有後端核對 APPLIED／ACTIVE 才顯示已加入。',
        '沒有取消端點；送出結果不確定時只以計畫 UUID 查詢與核對，不會自動重送。',
      ];
    }
    return [
      `把「${server.name}」（中控本機 ${ss.sshTarget(server)}）設為空集群的首個權威站。`,
      `站點：${p.siteId ?? '—'} · ${p.instanceId ?? '—'}；業務 HTTP 仍是 127.0.0.1:${p.httpPort ?? '—'}。`,
      `中控改接權威資料庫 127.0.0.1:${p.databasePort ?? '—'}（只在目標主機回環位址公開）。`,
      '保留業務站與中控兩邊的原有資料與帳號；中控原資料庫不刪除。',
      '中控會短暫重啟（約 30–120 秒）；頁面會自動重新連線，請勿重新送出。',
      '這是單站集群：跨站同步尚未啟用，新增其他成員會被拒絕。',
      '沒有取消端點；送出結果不確定時只以計畫 UUID 查詢與核對，不會自動重送。',
    ];
  }

  async function executeCluster() {
    if (busy.size || !clusterPlan || !clusterCtx || clusterJobActive()) return;
    const server = serverById(clusterCtx.serverId);
    if (!server) return;
    const captured = clusterPlan;
    const g = gen;
    let sent = false;
    busy.add('cexec');
    render();
    try {
      const recovery = captured.kind === ss.MEMBER_RECOVERY_KIND;
      const yes = await confirmDialog({ title: recovery ? '確認恢復原設定' : '確認接入集群', lines: clusterConfirmLines(captured, server), confirmLabel: recovery ? '確認恢復' : '確認接入' });
      if (!yes || g !== gen || !session.isActive()) return;
      // 確認後重新核對：重新讀取計畫與伺服器 revision，並套用主中控寫入閘門。
      const fresh = ss.normalizeClusterPlan(await client.getClusterPlan(captured.id));
      if (g !== gen) return;
      await load();
      if (g !== gen) return;
      const current = serverById(server.id);
      const reasons = clusterReasons(current);
      if (!fresh) reasons.push('無法重新讀取計畫。');
      else if (fresh.id !== captured.id) reasons.push('重新讀取的計畫與確認的不同。');
      else {
        reasons.push(...clusterPlanProblems(fresh));
        clusterPlan = fresh;
        if (current && current.revision !== fresh.serverRevision) reasons.push('伺服器登記已被修改，計畫失效，請重新建立。');
      }
      if (reasons.length) { message('amber', '確認期間狀態已改變，未送出。', reasons); return; }
      clusterPending = captured.id;
      els.clusterLookupId.value = captured.id;
      sent = true;
      if (recovery) sentRecoveryPlans.add(captured.id);
      const j = ss.normalizeClusterJob(await client.executeClusterPlan(captured.id));
      if (g !== gen) return;
      if (!j) {
        message('amber', '已送出，但任務格式無效；正以計畫 UUID 讀取任務。');
        startClusterWatch(captured.id);
        return;
      }
      clusterPending = null;
      setClusterJob(j, { announce: false });
      message('teal', ss.clusterAcceptedText(j));
    } catch (err) {
      if (g !== gen) return;
      const f = ss.serverFailure(err);
      if (!sent) {
        // 確認後的重新檢查失敗：execute 尚未送出。
        clusterPending = null;
        if (f.outcome === 'LOGOUT') { onAuthFailure(err); return; }
        message('amber', '確認後重新檢查失敗，接入請求未送出。', [f.message]);
      } else if (f.outcome === 'UNKNOWN') {
        // 結果未知：不重送 execute，只以原計畫 UUID 唯讀查詢。
        message('amber', ss.clusterUnknownSubmitText(captured));
        startClusterWatch(captured.id);
      } else {
        clusterPending = null;
        handleFailure(err, { reloadOn: CLUSTER_RELOAD_CODES });
      }
    } finally {
      if (g === gen) { busy.delete('cexec'); render(); }
    }
  }

  function stopClusterPoll() {
    clearTimeout(clusterTimer);
    clusterTimer = null;
  }

  function clusterWatchId() {
    return clusterPending ?? (ss.jobActive(clusterJob) ? clusterJob.id : null);
  }

  function scheduleClusterPoll(delay = ss.clusterPollDelay(clusterFailures)) {
    stopClusterPoll();
    const id = clusterWatchId();
    if (!id || !onPage || !session.isActive()) return;
    const g = gen;
    clusterTimer = setTimeout(() => {
      clusterTimer = null;
      if (g === gen) fetchClusterJob(id, { poll: true });
    }, delay);
  }

  function startClusterWatch(id) {
    clusterPending = id;
    clusterFailures = 0;
    els.clusterLookupId.value = id;
    scheduleClusterPoll(0);
    render();
  }

  function autoLookupClusterJob() {
    const id = data?.clusterJobId;
    if (!id || clusterJob || clusterPending || clusterAutoLooked === id || !onPage) return;
    clusterAutoLooked = id;
    els.clusterLookupId.value = id;
    fetchClusterJob(id, { poll: true, quiet: true });
  }

  async function fetchClusterJob(id, { poll = false, quiet = false } = {}) {
    if (!session.isActive()) return;
    if (!ss.isServerUuid(id)) { message('red', '請輸入有效的接入任務或計畫 UUID。'); return; }
    const g = gen;
    const watched = poll && clusterWatchId() === id;
    const superseded = () => watched && clusterWatchId() !== id;
    if (!poll) busy.add('clookup');
    render();
    try {
      const j = ss.normalizeClusterJob(await readClusterJob(id));
      if (g !== gen) return;
      // 遲到的輪詢回應：使用者已改看其他任務（例如從任務歷史打開）時丟棄，不覆寫目前顯示的任務。
      if (superseded()) return;
      clusterFailures = 0;
      clusterSince = null;
      clusterLastOk = Date.now();
      if (!j) { if (clusterPending === id) clusterPending = null; message('red', '接入任務回應格式無效。'); return; }
      const announce = clusterPending === id || (clusterJob?.id === j.id && ss.jobActive(clusterJob));
      if (clusterPending === id) clusterPending = null;
      setClusterJob(j, { announce: announce && !quiet });
    } catch (err) {
      if (g !== gen || superseded()) return;
      const kind = ss.clusterPollFailure(err);
      if (kind === 'AUTH') { onAuthFailure(err); return; }
      if (kind === 'ABORTED') return;
      if (kind === 'RECONNECT') {
        // 中控重啟期間的失敗不代表任務失敗：保留已知狀態與登入，持續唯讀重試。
        clusterFailures += 1;
        clusterSince ??= Date.now();
        if (!(clusterJob && clusterJob.id === id)) clusterPending = id;
        scheduleClusterPoll();
        return;
      }
      const wasPending = clusterPending === id;
      clusterPending = null;
      stopClusterPoll();
      if (kind === 'NOT_FOUND') {
        if (quiet) return;
        if (sentRecoveryPlans.has(id)) {
          // 恢復計畫：不建議重新送出；只以原 UUID 再查詢核對。
          message('amber', `中控目前查不到恢復任務 ${id}：送出結果仍未確認。不會重送；請稍後在下方以這個原 UUID 再查詢核對。`);
          return;
        }
        message('amber', wasPending
          ? '中控沒有這個接入任務：執行請求可能沒有送達。未自動重送；確認狀態後可再按「確認接入集群…」，同一計畫重送只會回傳原任務。'
          : ss.serverCodeText('AUTHORITY_JOB_NOT_FOUND'));
        return;
      }
      handleFailure(err);
    } finally {
      if (g === gen) { busy.delete('clookup'); render(); }
    }
  }

  function setClusterJob(j, { announce }) {
    clusterJob = j;
    if (ss.jobActive(j)) { scheduleClusterPoll(); render(); return; }
    stopClusterPoll();
    // 這個計畫的任務已結束：回到伺服器列表，讓卡片顯示後端回報的實際成員狀態。
    if (view === 'cluster' && clusterPlan?.id === j.id) { view = 'list'; clusterCtx = null; clusterPlan = null; }
    if (announce && j.kind === ss.MEMBER_RECOVERY_KIND) {
      if (ss.memberRecoveryVerified(j)) message('teal', '已恢復失敗接入前的原設定；目前仍未加入集群（NOT_JOINED）。要加入請另外建立新的接入計畫。');
      else if (j.state === 'SUCCEEDED') message('amber', '恢復任務已完成，但結果沒有明確回報 NOT_JOINED 與需要新的接入計畫；以伺服器清單為準。');
      else if (j.state === 'OUTCOME_UNKNOWN') message('red', '恢復結果未知。不會重送；請按「核對恢復記錄…」。');
      else message('red', `恢復任務已結束：${ss.jobCopy(j.state)[1]}（${j.state}）。`, j.code ? [ss.serverCodeText(j.code) ?? `代碼：${j.code}`] : []);
    } else if (announce) {
      if (ss.clusterJobVerified(j)) message('teal', j.result?.singleSiteOnly === true ? '已加入集群（後端已核對 APPLIED／ACTIVE；本次為單站集群，跨站同步尚未啟用）。' : '已加入集群（後端已核對 APPLIED／ACTIVE）。');
      else if (j.state === 'SUCCEEDED') message('amber', '接入任務已完成，但結果沒有明確回報 APPLIED／ACTIVE；以伺服器清單為準，未顯示為已加入。');
      else if (j.state === 'OUTCOME_UNKNOWN') message('red', '接入結果未知。不會重送；請按「核對實際成員狀態…」。');
      else message('red', `接入任務已結束：${ss.jobCopy(j.state)[1]}（${j.state}）。`, j.code ? [ss.serverCodeText(j.code) ?? `代碼：${j.code}`] : []);
    }
    load();
    onClusterChanged?.();
    render();
  }

  async function reconcileCluster() {
    if (busy.size || !canWrite() || !clusterJob || clusterJob.state !== 'OUTCOME_UNKNOWN') return;
    const captured = clusterJob;
    const g = gen;
    busy.add('creconcile');
    render();
    try {
      const yes = captured.kind === ss.MEMBER_RECOVERY_KIND
        ? await confirmDialog({ title: '核對恢復記錄', lines: ['只讀取遠端恢復回執與目前狀態，不重新恢復、不加入集群。', `恢復任務：${captured.id}`], confirmLabel: '核對' })
        : await confirmDialog({ title: '核對實際成員狀態', lines: ['只讀取遠端持久回執與實際成員狀態，不重新安裝、不重新切換資料庫。', `任務：${captured.id}`], confirmLabel: '核對' });
      if (!yes || g !== gen || !session.isActive()) return;
      await load();
      if (g !== gen) return;
      const reasons = gateReasons();
      if (reasons.length) { message('amber', '確認期間狀態已改變，未送出。', reasons); return; }
      const j = ss.normalizeClusterJob(await client.reconcileClusterJob(captured.id));
      if (g !== gen) return;
      if (j) setClusterJob(j, { announce: true }); else message('amber', '核對已回應，但任務格式無效。');
    } catch (err) {
      if (g !== gen) return;
      const f = ss.serverFailure(err);
      if (f.outcome === 'UNKNOWN') { message('amber', '核對結果未確認；正重新讀取任務。'); startClusterWatch(captured.id); }
      else handleFailure(err);
    } finally {
      if (g === gen) { busy.delete('creconcile'); render(); }
    }
  }

  function renderClusterJob() {
    const visible = Boolean(data?.clusterEnrollmentEnabled || clusterJob || clusterPending || view === 'cluster');
    els.clusterJobPanel.hidden = !visible;
    const reconnecting = clusterFailures > 0 && clusterWatchId() !== null;
    els.clusterReconnect.hidden = !reconnecting;
    if (reconnecting) {
      const late = clusterSince !== null && Date.now() - clusterSince > ss.CLUSTER_RESTART_EXPECTED_MS;
      els.clusterReconnect.textContent = `中控正在重新啟動或暫時無法連線，正在重新連線（已嘗試 ${clusterFailures} 次，約 ${ss.clusterPollDelay(clusterFailures) / 1000} 秒後再試）。`
        + (clusterLastOk ? `上次成功讀取：${formatTime(clusterLastOk)}。` : '')
        + '接入請求不會重新送出；任務狀態以重新連線後讀到的為準。'
        + (late ? '已超過預期的重啟時間（約 30–120 秒），仍會持續重試；必要時請檢查中控服務。' : '');
    }
    els.clusterReconcile.hidden = !(clusterJob && clusterJob.state === 'OUTCOME_UNKNOWN');
    els.clusterReconcile.disabled = busy.size > 0 || !canWrite();
    els.clusterReconcile.textContent = busy.has('creconcile') ? '核對中…' : clusterJob?.kind === ss.MEMBER_RECOVERY_KIND ? '核對恢復記錄…' : '核對實際成員狀態…';
    // 失敗且已保留的校外業務站接入任務：提供恢復原設定預覽入口（只建立預覽，執行另需確認）。
    const recoverable = Boolean(clusterJob && ss.memberRecoverySourceProblems(clusterJob).length === 0);
    const recoverReasons = recoverable ? [...recoveryReasons(serverById(clusterJob.serverId))] : [];
    els.clusterRecover.hidden = !recoverable;
    els.clusterRecover.disabled = !recoverable || busy.size > 0 || recoverReasons.length > 0;
    els.clusterRecoverNote.hidden = !recoverable;
    els.clusterRecoverNote.textContent = recoverable
      ? (recoverReasons.length ? `恢復原設定停用：${recoverReasons.join(' ')}` : '可預覽恢復失敗接入前的原設定；只建立預覽，執行前會再次確認。恢復後仍需新的接入計畫。')
      : '';
    els.clusterJobRefresh.hidden = !clusterJob;
    els.clusterJobRefresh.disabled = busy.size > 0;
    els.clusterLookupSubmit.disabled = busy.size > 0 || !session.isActive();
    if (!visible) { els.clusterJobView.replaceChildren(); return; } // 隱藏時也不留舊任務內容
    if (!clusterJob) {
      replace(els.clusterJobView, h('p', { class: 'empty' }, clusterPending ? h('span', null, '正在讀取接入任務 ', raw(clusterPending), '…') : '本次登入尚未建立或查詢接入任務。'));
      return;
    }
    const j = clusterJob;
    const [tone, label] = ss.jobCopy(j.state);
    const result = j.result ?? {};
    const server = j.serverId ? serverById(j.serverId) : null;
    const verified = ss.clusterJobVerified(j);
    replace(els.clusterJobView,
      h('div', { class: 'plan-head' }, h('h3', { class: 'sub-title' }, `${ss.CLUSTER_KIND_LABEL[j.kind] ?? '接入任務'} `, raw(j.id)), status(tone, `${label} · ${j.state}`)),
      kv([
        ['伺服器', raw(server ? server.name : j.serverId ?? '—')],
        ['階段', j.stage ? h('span', null, ss.clusterStageText(j.stage) ?? '', ' ', raw(j.stage)) : '—'],
        j.failedId ? ['來源失敗任務', raw(j.failedId)] : null,
        ['建立時間', formatTime(j.createdAt)],
        j.code ? ['代碼', h('span', null, ss.serverCodeText(j.code) ?? '', ' ', raw(j.code))] : null,
        ...Object.entries(result).map(([k, v]) => [k, raw(resultValue(v))]),
      ]),
      ss.jobActive(j) ? h('p', { class: 'hint' }, j.controllerRestartExpected === true
        ? `任務進行中；中控預期會短暫重啟。此頁每 ${ss.CLUSTER_POLL_MS / 1000} 秒唯讀讀取一次任務，連線中斷時自動退避重試。`
        : `任務進行中；每 ${ss.CLUSTER_POLL_MS / 1000} 秒唯讀讀取一次。`) : null,
      ss.memberRecoveryVerified(j) ? h('div', { class: 'callout' }, h('p', { class: 'note' }, '已恢復原設定；仍未加入集群（NOT_JOINED）。要加入請在伺服器列表另外建立新的接入計畫。')) : null,
      verified ? h('div', { class: 'callout' }, h('p', { class: 'note' }, result.singleSiteOnly === true ? '已加入集群。後端已核對 APPLIED／ACTIVE；本次為單站集群，跨站同步尚未啟用。' : '已加入集群。後端已核對 APPLIED／ACTIVE。'), h('p', null, routeLink('sites', '查看站點 →'))) : null,
      j.state === 'SUCCEEDED' && !verified && j.kind !== ss.MEMBER_RECOVERY_KIND ? h('div', { class: 'alert alert-amber' }, h('p', { class: 'message-title' }, '任務完成，但結果沒有明確回報 APPLIED／ACTIVE。'), h('p', null, '不顯示為已加入；以伺服器清單的成員狀態為準。')) : null,
      j.state === 'SUCCEEDED' && j.kind === ss.MEMBER_RECOVERY_KIND && !ss.memberRecoveryVerified(j) ? h('div', { class: 'alert alert-amber' }, h('p', { class: 'message-title' }, '恢復任務完成，但結果不符合恢復契約（NOT_JOINED＋需新的接入計畫）。'), h('p', null, '以伺服器清單的成員狀態為準；不顯示為已加入。')) : null,
      j.state === 'OUTCOME_UNKNOWN' ? h('div', { class: 'alert alert-red' }, h('p', { class: 'message-title' }, '結果未知；不會重送。'), h('p', null, j.kind === ss.MEMBER_RECOVERY_KIND
        ? '請按「核對恢復記錄…」，只讀取遠端恢復回執與目前狀態，不重新恢復、不加入集群。'
        : '請按「核對實際成員狀態…」，只讀取遠端回執與實際成員，不重新安裝或切換資料庫。')) : null);
  }

  function render() {
    renderStatus();
    renderSteps();
    renderList();
    renderForm();
    renderPlan();
    renderJob();
    renderCluster();
    renderClusterJob();
  }

  // ---------- 事件 ----------

  els.add.addEventListener('click', () => { if (canWrite() && !busy.size) openForm(null); });
  els.list.addEventListener('click', (event) => {
    const btn = event.target.closest('button[data-action]');
    if (!btn || btn.disabled) return;
    const action = btn.dataset.action;
    if (action === 'add') { openForm(null); return; }
    const server = serverById(btn.dataset.id);
    if (!server) return;
    if (action === 'test') testServer(server);
    else if (action === 'edit') openForm(server);
    else if (action === 'remove') removeServer(server);
    else if (action === 'install') openPlan(server, 'INSTALL');
    else if (action === 'update') openPlan(server, 'UPDATE');
    else if (action === 'cluster') openCluster(server);
    else if (action === 'recover') openPlan(server, 'RECOVER');
    else if (action === 'source-job' && server.installation?.jobId) { els.jobLookupId.value = server.installation.jobId; lookupJob(server.installation.jobId); }
  });

  els.form.addEventListener('change', (event) => {
    const t = event.target;
    if (t.name === 'server-location') {
      if (t.value === 'CONTROL_HOST') { remoteHost = els.host.value === ss.CONTROL_HOST_ADDRESS ? remoteHost : els.host.value; els.host.value = ss.CONTROL_HOST_ADDRESS; } else els.host.value = remoteHost;
    }
    if (t.name === 'server-auth') clearSecrets();
    if (t.name === 'server-credential-source') {
      // 切換來源一律清空手動憑據與選擇；引用模式固定私鑰認證。
      clearSecrets();
      els.credId.value = '';
      if (t.value === 'OPERATOR_INVENTORY') setRadio('server-auth', 'PRIVATE_KEY');
    }
    if (t.name === 'server-hostkey') { keyIndex = Number(t.value); els.hostkeyConfirm.checked = false; }
  });
  els.form.addEventListener('input', () => { dirtyForm = true; render(); });
  els.form.addEventListener('change', () => { dirtyForm = true; render(); });
  els.form.addEventListener('submit', submitForm);
  els.hostkeyFetch.addEventListener('click', fetchHostKey);
  els.formCancel.addEventListener('click', () => { if (!busy.has('save')) { message('neutral', ''); closeForm(); } });

  els.planForm.addEventListener('input', () => { dirtyPlan = true; render(); });
  els.planForm.addEventListener('change', () => { dirtyPlan = true; render(); });
  els.planForm.addEventListener('submit', createPlan);
  els.planCancel.addEventListener('click', () => { if (!busy.has('plan') && !busy.has('execute')) closePlan(); });
  els.planExecute.addEventListener('click', executePlan);

  els.jobRefresh.addEventListener('click', () => { if (job) lookupJob(job.id); });
  els.jobReconcile.addEventListener('click', reconcileJob);
  els.jobLookupForm.addEventListener('submit', (event) => { event.preventDefault(); lookupJob(els.jobLookupId.value.trim()); });

  els.clusterPlanCreate.addEventListener('click', createClusterPlan);
  els.clusterCancel.addEventListener('click', () => { if (!busy.has('cplan') && !busy.has('cexec')) closeCluster(); });
  els.clusterExecute.addEventListener('click', executeCluster);
  els.clusterJobRefresh.addEventListener('click', () => { if (clusterJob) fetchClusterJob(clusterJob.id); });
  els.clusterRecover.addEventListener('click', () => { if (!busy.size && clusterJob) openMemberRecovery(clusterJob); });
  els.clusterReconcile.addEventListener('click', reconcileCluster);
  els.clusterLookupForm.addEventListener('submit', (event) => { event.preventDefault(); fetchClusterJob(els.clusterLookupId.value.trim()); });

  render();

  return {
    // 進入頁面才讀取（登入後）；任務仍在進行時恢復輪詢。
    enter() {
      onPage = true;
      if (!session.isActive()) return;
      load();
      scheduleJobPoll();
      scheduleClusterPoll(0);
    },
    // 離開頁面：清空憑據並停止任務輪詢；表單內容保留。
    leave() {
      onPage = false;
      clearSecrets();
      stopJobPoll();
      stopClusterPoll();
    },
    load,
    // 由任務歷史進入既有詳情（只讀取；核對仍在原面板確認後進行）。
    openJob(id) {
      if (!ss.isServerUuid(id)) return;
      els.jobLookupId.value = id;
      lookupJob(id);
    },
    openClusterJob(id) {
      if (!ss.isServerUuid(id)) return;
      els.clusterLookupId.value = id;
      fetchClusterJob(id);
    },
    reset() {
      gen += 1;
      onPage = false;
      stopJobPoll();
      stopClusterPoll();
      clearTimeout(planExpiryTimer);
      clusterCtx = null; clusterPlan = null; clusterJob = null; clusterPending = null;
      recoverySources.clear();
      sentRecoveryPlans.clear();
      clusterFailures = 0; clusterLastOk = null; clusterSince = null; clusterAutoLooked = null;
      els.clusterLookupId.value = '';
      clearSecrets();
      data = null; loadError = null; loading = false; view = 'list'; editing = null; probe = null; keyIndex = -1;
      forceHostKey = false; probedSignature = null; pendingIntent = null; planCtx = null; plan = null; job = null;
      dirtyForm = false; dirtyPlan = false; lastSaved = null; remoteHost = '';
      busy.clear();
      els.hostkeyConfirm.checked = false;
      els.hostkeys.replaceChildren();
      els.name.value = ''; els.host.value = ''; els.port.value = '22'; els.username.value = '';
      els.planUrl.value = ''; els.planPort.value = String(ss.DEFAULT_HTTP_PORT); els.planDocker.checked = false; els.planRelease.replaceChildren();
      els.jobLookupId.value = '';
      message('neutral', '');
      render();
    },
    render,
  };
}
