import { effectiveJobState, executionReasons } from './deployment-state.js';
import * as dr from './deployment-recovery-state.js';
import { h, replace } from './dom.js';
import { describeError, formatTime } from './format.js';
import { isUuid } from './validate.js';

// Shared by legacy and cluster views. Tokens stay inside the existing session/client.
// recovery：{ preview, get, execute, reconcile }（docs/architecture/control-deployment-recovery-api.md）。
// 恢復只看 context.leaseOwnership（localLeader 且 HELD）與 capabilities.deploymentRecoveryEnabled，不沿用新部署的寫入閘門。
export function mountDeployment({ button, reasonsEl, session, confirmDialog, execute, getPlan, getCapabilities, getJob, reconcile, recheck, onAuthFailure, onJobChange, recovery = null }) {
  const panel = document.createElement('div');
  panel.className = 'subsection deploy-job';
  panel.setAttribute('aria-live', 'polite');
  button.parentElement.append(panel);
  const reconcileButton = document.createElement('button');
  reconcileButton.type = 'button'; reconcileButton.className = 'btn btn-ghost'; reconcileButton.textContent = '核對遠端部署紀錄';
  button.parentElement.append(reconcileButton);
  const recoveryEl = document.createElement('div');
  recoveryEl.className = 'subsection deploy-recovery';
  recoveryEl.setAttribute('aria-live', 'polite');
  button.parentElement.append(recoveryEl);
  const lookup = document.createElement('form');
  lookup.className = 'form-row';
  const label = document.createElement('label');
  label.textContent = '部署任務 UUID（重新登入後可查詢）';
  const input = document.createElement('input');
  input.type = 'text'; input.maxLength = 36; label.append(input);
  const submit = document.createElement('button');
  submit.type = 'submit'; submit.className = 'btn btn-ghost'; submit.textContent = '查詢部署任務';
  lookup.append(label, submit); button.parentElement.append(lookup);
  let context = {}, job = null, busy = false, timer = null, generation = 0, error = null, jobKey = '', panelKey = '', recoveryKey = '';
  // 恢復：目前顯示的恢復記錄、預覽意圖（同一原任務＋站點在結果不明時沿用 requestId）、送出結果是否未知。
  let rec = { plan: null, intent: null, submitUnknown: false, busy: '', message: null, timer: null, gen: 0 };

  // 總覽只呈現本次登入已讀取／執行的持久任務；任務狀態改變時通知外層。
  function notifyJob() {
    const key = dr.jobRenderKey(job);
    if (key !== jobKey) { jobKey = key; onJobChange?.(job); }
  }
  const text = (value) => h('p', null, value);
  const raw = (value) => h('span', { class: 'mono', translate: 'no' }, value);
  const codeLine = (label, value, describe) => (value ? h('p', null, `${label}：${describe(value) ?? '未定義'} `, raw(value)) : null);

  function recoveryTargetRow(target, state) {
    const stage = dr.targetStageLine(target);
    const row = h('div', { class: 'deploy-target' },
      h('p', null, raw(target.site ?? '—'), ` · ${target.state ?? '—'}${target.backupId ? ` · 備份 ${target.backupId}` : ''}`, stage ? ` · ${stage}` : ''));
    if (!recovery || !dr.targetRecoverable(state, target)) return row;
    const reasons = dr.previewReasons({ caps: context.capabilities, ownership: context.leaseOwnership, busy: busy || Boolean(rec.busy), active: dr.recoveryActive(rec.plan) });
    row.append(h('button', { type: 'button', class: 'btn btn-ghost btn-sm', 'data-recovery-preview': target.site, 'data-focus-key': `recovery-preview:${target.site}`, disabled: reasons.length > 0 || !session.isActive() }, rec.busy === `preview:${target.site}` ? '預覽中…' : '預覽恢復舊版'));
    if (reasons.length) row.append(h('p', { class: 'hint' }, `恢復舊版停用：${reasons.join(' ')}`));
    return row;
  }

  function draw() {
    const reasons = [...executionReasons(context.capabilities, context.plan), ...(context.extraReasons ?? [])];
    if (busy) reasons.push('正在送出部署請求。');
    const state = effectiveJobState(job);
    if (job && ['QUEUED', 'RUNNING', 'OUTCOME_UNKNOWN'].includes(state)) reasons.push(`部署任務 ${job.id}：${state}，需等待完成或核對遠端紀錄。`);
    button.disabled = reasons.length > 0 || !session.isActive();
    button.setAttribute('aria-disabled', String(button.disabled));
    button.classList.toggle('btn-locked', button.disabled);
    button.classList.toggle('btn-primary', !button.disabled);
    submit.disabled = busy;
    button.textContent = button.disabled ? '執行部署（目前不可用）' : '確認逐站部署…';
    const list = reasons.map((reason) => { const li = document.createElement('li'); li.textContent = reason; return li; });
    reasonsEl.replaceChildren(...list);
    // 只有內容改變才重繪，避免輪詢時按鈕失去焦點；鍵包含 stage／failureStage 與恢復可用性。
    const key = JSON.stringify([dr.jobRenderKey(job), error ? describeError(error) : '', busy, rec.busy, dr.recoveryActive(rec.plan),
      context.capabilities?.deploymentRecoveryEnabled === true, context.leaseOwnership?.known === true, context.leaseOwnership?.owned === true, session.isActive()]);
    if (key !== panelKey) {
      panelKey = key;
      const lines = [];
      if (error) lines.push(text(describeError(error)));
      if (job) {
        lines.push(text(`任務 ${job.id} · ${job.state} · ${job.releaseId ?? ''}`));
        if (job.reason) lines.push(text(job.reason));
        if (job.state === 'FAILED' && job.reason === 'ROLLBACK_FAILED') lines.push(text('舊應用恢復未通過健康檢查，請先處理該站故障。'));
        for (const target of job.targets ?? []) lines.push(recoveryTargetRow(target, state));
        if (state === 'OUTCOME_UNKNOWN') lines.push(text(job.state === 'OUTCOME_UNKNOWN' ? '結果未知。先核對遠端部署紀錄與執行版本；中控不會自動重送更新。' : `權威發布約束尚未收斂（${job.code ?? job.reason}），結果未知；需由目前主中控核對，不能視為成功。`));
        if (job.code === 'AUTHORITY_NO_DISPATCH_PROVEN' || job.reason === 'AUTHORITY_NO_DISPATCH_PROVEN') lines.push(text('已證明從未派發到遠端的失敗；如需重試，必須重新建立部署預覽。'));
        const attempts = dr.normalizeAttempts(job);
        if (attempts.length) {
          lines.push(h('div', { class: 'deploy-attempts' }, h('p', { class: 'strong' }, '手動恢復嘗試'),
            h('ul', { class: 'list' }, attempts.map((a) => h('li', null, raw(a.id), ` · ${a.siteId ?? '—'} · ${a.state ?? '—'}`,
              a.stage ? h('span', null, ` · ${dr.recoveryStageText(a.stage) ?? ''} `, raw(a.stage)) : null,
              a.code ? h('span', null, ` · ${dr.recoveryCodeText(a.code) ?? ''} `, raw(a.code)) : null, ' ',
              h('button', { type: 'button', class: 'btn btn-ghost btn-sm', 'data-recovery-view': a.id, 'data-focus-key': `recovery-view:${a.id}`, disabled: Boolean(rec.busy) }, '查看'))))));
        }
      }
      const active = panel.ownerDocument.activeElement;
      const focusKey = active && panel.contains(active) ? active.dataset?.focusKey : null;
      panel.replaceChildren(...lines);
      if (focusKey) [...panel.querySelectorAll('[data-focus-key]')].find((e) => e.dataset.focusKey === focusKey)?.focus({ preventScroll: true });
    }
    reconcileButton.disabled = busy || !job || state !== 'OUTCOME_UNKNOWN' || !session.isActive();
    drawRecovery();
    notifyJob();
  }

  // ---------- 手動恢復舊版 ----------

  function drawRecovery() {
    const p = rec.plan;
    const ownership = context.leaseOwnership;
    const execBlock = dr.executeReasons({ caps: context.capabilities, ownership, plan: p, busy: busy || Boolean(rec.busy) });
    const canReconcile = p && (p.state === 'OUTCOME_UNKNOWN' || rec.submitUnknown);
    const reconBlock = [...dr.leaseReasons(ownership), ...(rec.busy ? ['正在處理其他請求。'] : [])];
    const key = JSON.stringify([p, rec.submitUnknown, rec.busy, rec.message, execBlock, reconBlock, session.isActive()]);
    if (key === recoveryKey) return;
    recoveryKey = key;
    if (!p && !rec.message) { recoveryEl.replaceChildren(); return; }
    const [tone, stateLabel] = p ? dr.RECOVERY_STATE_COPY[p.state] : ['neutral', ''];
    const active = recoveryEl.ownerDocument.activeElement;
    const focusKey = active && recoveryEl.contains(active) ? active.dataset?.focusKey : null;
    replace(recoveryEl,
      h('h3', { class: 'sub-title' }, '恢復舊版（手動）'),
      rec.message ? h('div', { class: `message message-${rec.message.tone}`, role: 'status' }, h('span', { class: 'message-title' }, rec.message.text),
        rec.message.lines?.length ? h('ul', { class: 'list' }, rec.message.lines.map((l) => h('li', null, l))) : null) : null,
      p ? h('div', { class: 'recovery-plan' },
        h('p', null, h('span', { class: `status status-${tone}` }, h('span', { class: `dot dot-${tone}`, 'aria-hidden': 'true' }), stateLabel), ' ', raw(p.state)),
        h('dl', { class: 'kv' },
          ...[
            ['恢復 UUID', raw(p.id)], ['原部署任務', raw(p.jobId ?? '—')], ['目標站', h('span', null, raw(p.siteId ?? '—'), p.instanceId ? h('span', null, ' · ', raw(p.instanceId)) : null)],
            ['目前 SHA-256', raw(p.currentSha256 ?? '—')], ['要恢復的舊版 SHA-256', raw(p.restoreSha256 ?? '—')], ['原備份 UUID', raw(p.backupId ?? '—')],
            p.stage ? ['階段', h('span', null, `${dr.recoveryStageText(p.stage) ?? '未定義'} `, raw(p.stage))] : null,
            p.failureStage ? ['失敗階段', h('span', null, `${dr.recoveryStageText(p.failureStage) ?? '未定義'} `, raw(p.failureStage))] : null,
            p.code ? ['錯誤碼', h('span', null, `${dr.recoveryCodeText(p.code) ?? '未定義的錯誤碼'} `, raw(p.code))] : null,
            p.expiresAt ? ['預覽有效至', `${formatTime(p.expiresAt)}${dr.planExpired(p) ? '（已過期）' : ''}`] : null,
            ['原任務', p.parentState ? h('span', null, raw(p.parentState), p.parentReconciliationRequired === true ? '，仍需核對／封存' : p.parentReconciliationRequired === false ? '' : '（是否仍需核對：未知）') : '未回報'],
          ].filter(Boolean).flatMap(([k, v]) => [h('dt', null, k), h('dd', null, v)])),
        h('p', { class: 'hint' }, '只恢復應用的舊版 JAR，不恢復或重建資料庫，不改站點身份、管理員或密碼。'),
        h('p', { class: 'hint' }, '恢復舊版成功不等於新版部署成功：原任務仍會是 FAILED 或 PARTIAL_FAILURE。'),
        rec.submitUnknown ? h('p', { class: 'alert alert-amber' }, `執行回執遺失：保留恢復 UUID ${p.id}，只能核對結果；不會自動重送、不會建立新的 UUID。`) : null,
        h('div', { class: 'form-actions' },
          p.state === 'PREVIEW_ONLY' && !rec.submitUnknown ? h('button', { type: 'button', class: 'btn btn-warn btn-sm', 'data-recovery-execute': p.id, 'data-focus-key': 'recovery-execute', disabled: execBlock.length > 0 || !session.isActive() }, rec.busy === 'execute' ? '送出中…' : '確認恢復舊版…') : null,
          canReconcile ? h('button', { type: 'button', class: 'btn btn-primary btn-sm', 'data-recovery-reconcile': p.id, 'data-focus-key': 'recovery-reconcile', disabled: reconBlock.length > 0 || !session.isActive() }, rec.busy === 'reconcile' ? '核對中…' : '核對恢復結果…') : null,
          h('button', { type: 'button', class: 'btn btn-ghost btn-sm', 'data-recovery-refresh': p.id, 'data-focus-key': 'recovery-refresh', disabled: Boolean(rec.busy) }, '重新讀取')),
        p.state === 'PREVIEW_ONLY' && !rec.submitUnknown && execBlock.length ? h('p', { class: 'hint' }, `執行停用：${execBlock.join(' ')}`) : null,
        canReconcile && reconBlock.length ? h('p', { class: 'hint' }, `核對停用：${reconBlock.join(' ')}`) : null) : null);
    if (focusKey) [...recoveryEl.querySelectorAll('[data-focus-key]')].find((e) => e.dataset.focusKey === focusKey)?.focus({ preventScroll: true });
  }

  function recoveryFailure(err) {
    const code = typeof err?.code === 'string' ? err.code : null;
    return { text: dr.recoveryCodeText(code) ?? describeError(err), lines: code ? [`代碼：${code}`] : [] };
  }

  function stopRecoveryPoll() { clearTimeout(rec.timer); rec.timer = null; }

  // 只讀取本地恢復狀態；進行中時每 2 秒重讀。永不重送 execute。
  async function readRecovery(id, g) {
    stopRecoveryPoll();
    try {
      const r = dr.normalizeRecovery(await recovery.get(id));
      if (g !== rec.gen || !session.isActive()) return;
      if (r) rec.plan = r; else rec.message = { tone: 'red', text: '恢復記錄格式無效。' };
    } catch (err) {
      if (g !== rec.gen) return;
      if (auth(err)) return;
      rec.message = { tone: 'red', ...recoveryFailure(err) };
    } finally {
      if (g === rec.gen && session.isActive()) {
        draw();
        if (dr.recoveryActive(rec.plan)) rec.timer = setTimeout(() => readRecovery(id, g), 2000);
      }
    }
  }

  async function previewRecovery(siteId) {
    if (!recovery || !job || rec.busy || busy) return;
    const target = (job.targets ?? []).find((t) => t.site === siteId);
    const reasons = dr.previewReasons({ caps: context.capabilities, ownership: context.leaseOwnership, busy: false, active: dr.recoveryActive(rec.plan) });
    if (!target || !dr.targetRecoverable(effectiveJobState(job), target) || reasons.length) return;
    const g = rec.gen;
    const intentKey = `${job.id}:${siteId}`;
    // 同一原任務＋站點的預覽在結果不明時沿用 requestId（後端回傳原預覽）。
    const requestId = rec.intent?.key === intentKey ? rec.intent.requestId : globalThis.crypto.randomUUID();
    rec.intent = { key: intentKey, requestId };
    rec.busy = `preview:${siteId}`; rec.message = null; rec.submitUnknown = false;
    draw();
    try {
      const r = dr.normalizeRecovery(await recovery.preview(job.id, { requestId, siteId }));
      if (g !== rec.gen) return;
      rec.intent = null;
      if (!r) rec.message = { tone: 'red', text: '恢復預覽格式無效。' };
      else rec.plan = r;
    } catch (err) {
      if (g !== rec.gen) return;
      if (auth(err)) return;
      if (!(err?.outcomeUnknown || err?.status >= 500)) rec.intent = null;
      rec.message = { tone: 'red', ...recoveryFailure(err) };
    } finally {
      if (g === rec.gen) { rec.busy = ''; draw(); }
    }
  }

  async function executeRecovery() {
    const captured = rec.plan;
    if (!recovery || !captured || rec.busy || busy) return;
    if (dr.executeReasons({ caps: context.capabilities, ownership: context.leaseOwnership, plan: captured, busy: false }).length) return;
    const g = rec.gen;
    rec.busy = 'execute'; draw();
    try {
      const yes = await confirmDialog({ title: '確認恢復舊版', confirmLabel: '恢復舊版', lines: [
        `目標站：${captured.siteId}`, `目前 SHA-256：${captured.currentSha256 ?? '—'}`, `恢復為舊版 SHA-256：${captured.restoreSha256}`,
        `原備份 UUID：${captured.backupId ?? '—'}`, '只恢復應用，不恢復或重建資料庫；不改站點身份、管理員或密碼。',
        '恢復成功不等於新版部署成功；原任務仍是 FAILED 或 PARTIAL_FAILURE。', '回執遺失時只能核對，不會自動重送。',
      ] });
      if (!yes || g !== rec.gen || !session.isActive()) return;
      // 確認後重新核對：重新讀取預覽、能力與租約所有權。
      const [fresh, caps] = await Promise.all([recovery.get(captured.id).then(dr.normalizeRecovery), getCapabilities()]);
      if (g !== rec.gen || !session.isActive()) return;
      const reasons = dr.executeReasons({ caps, ownership: context.leaseOwnership, plan: fresh, busy: false });
      if (fresh && (fresh.restoreSha256 !== captured.restoreSha256 || fresh.siteId !== captured.siteId)) reasons.push('預覽內容已改變，請重新預覽。');
      if (fresh) rec.plan = fresh;
      if (reasons.length) { rec.message = { tone: 'amber', text: '確認期間狀態已改變，未送出。', lines: reasons }; return; }
      try {
        const r = dr.normalizeRecovery(await recovery.execute(captured.id, { restoreSha256: captured.restoreSha256 }));
        if (g !== rec.gen) return;
        if (r) rec.plan = r;
        rec.message = { tone: 'neutral', text: '已送出恢復舊版；以下狀態只讀取本地記錄。' };
        readRecovery(captured.id, g);
      } catch (err) {
        if (g !== rec.gen) return;
        if (auth(err)) return;
        if (err?.outcomeUnknown || err?.status >= 500) {
          // 回執遺失：保留同一恢復 UUID，只提供核對；只讀查詢本地狀態。
          rec.submitUnknown = true;
          rec.message = { tone: 'amber', text: '執行結果未知。', lines: [`恢復 UUID ${captured.id} 已保留，只能核對；不會自動重送或建立新的 UUID。`] };
          readRecovery(captured.id, g);
        } else rec.message = { tone: 'red', ...recoveryFailure(err) };
      }
    } catch (err) {
      if (g !== rec.gen) return;
      if (auth(err)) return;
      rec.message = { tone: 'red', text: '確認後重新檢查失敗，未送出。', lines: [recoveryFailure(err).text] };
    } finally {
      if (g === rec.gen) { rec.busy = ''; draw(); }
    }
  }

  async function reconcileRecovery() {
    const captured = rec.plan;
    if (!recovery || !captured || rec.busy || dr.leaseReasons(context.leaseOwnership).length) return;
    const g = rec.gen;
    rec.busy = 'reconcile'; draw();
    try {
      const yes = await confirmDialog({ title: '核對恢復結果', confirmLabel: '核對', lines: ['只查詢同一原目標、同一恢復 UUID 的遠端記錄；不重新執行恢復。', `恢復 UUID：${captured.id}`] });
      if (!yes || g !== rec.gen || !session.isActive()) return;
      if (dr.leaseReasons(context.leaseOwnership).length) { rec.message = { tone: 'amber', text: '確認期間租約狀態改變，未送出。', lines: dr.leaseReasons(context.leaseOwnership) }; return; }
      const r = dr.normalizeRecovery(await recovery.reconcile(captured.id));
      if (g !== rec.gen) return;
      if (r) { rec.plan = r; if (!['OUTCOME_UNKNOWN', 'QUEUED', 'RUNNING'].includes(r.state)) rec.submitUnknown = false; }
      rec.message = { tone: 'neutral', text: '核對已回應。' };
      if (dr.recoveryActive(rec.plan)) readRecovery(captured.id, g);
    } catch (err) {
      if (g !== rec.gen) return;
      if (auth(err)) return;
      rec.message = { tone: 'red', ...recoveryFailure(err) };
    } finally {
      if (g === rec.gen) { rec.busy = ''; draw(); }
    }
  }

  function resetRecovery() {
    stopRecoveryPoll();
    rec = { plan: null, intent: null, submitUnknown: false, busy: '', message: null, timer: null, gen: rec.gen + 1 };
    recoveryKey = '';
  }

  function onRecoveryClick(event) {
    const btn = event.target.closest?.('button');
    if (!btn || btn.disabled) return;
    if (btn.dataset.recoveryPreview) previewRecovery(btn.dataset.recoveryPreview);
    else if (btn.dataset.recoveryExecute) executeRecovery();
    else if (btn.dataset.recoveryReconcile) reconcileRecovery();
    else if (btn.dataset.recoveryRefresh && rec.plan) { rec.message = null; readRecovery(rec.plan.id, rec.gen); }
    else if (btn.dataset.recoveryView && isUuid(btn.dataset.recoveryView)) { rec.submitUnknown = false; rec.message = null; readRecovery(btn.dataset.recoveryView, rec.gen); }
  }
  panel.addEventListener('click', onRecoveryClick);
  recoveryEl.addEventListener('click', onRecoveryClick);

  function auth(err) {
    if (err.status === 401 || err.status === 403) { onAuthFailure(err); return true; }
    return false;
  }
  async function readJob(id, gen, retryMissing = false, until = Date.now() + 30000) {
    try {
      const result = await getJob(id);
      if (gen !== generation || !session.isActive()) return;
      job = result; error = null;
    } catch (err) {
      if (gen !== generation) return;
      error = err; auth(err);
    } finally {
      if (gen === generation && session.isActive()) {
        draw();
        if ((!job && retryMissing && Date.now() < until) || ['QUEUED', 'RUNNING'].includes(job?.state)) timer = setTimeout(() => readJob(id, gen, retryMissing, until), 2000);
      }
    }
  }
  button.addEventListener('click', async () => {
    if (button.disabled || busy || !session.isActive()) return;
    const captured = context.plan, capturedKey = context.confirmationKey, gen = generation;
    busy = true; draw();
    try {
      const yes = await confirmDialog({ title: '確認執行逐站部署', confirmLabel: '執行部署', lines: [
        `版本：${captured.releaseId}`, `SHA-256：${captured.artifactSha256}`,
        `站點：${(captured.members ?? captured.sites ?? []).map((m) => typeof m === 'string' ? m : m.siteId).join(' → ')}`,
        '將備份資料庫並逐站更新應用。當前站失敗會嘗試恢復舊應用，停止後續站點；已成功站點保留新版本。資料庫不自動回退。',
      ] });
      if (!yes || gen !== generation || !session.isActive()) return;
      const [plan, caps] = await Promise.all([getPlan(captured.id), getCapabilities()]);
      if (gen !== generation || !session.isActive()) return;
      const changed = context.confirmationKey !== capturedKey || context.plan?.id !== captured.id || plan.artifactSha256 !== captured.artifactSha256 || plan.epoch !== captured.epoch;
      const blocking = [...executionReasons(caps, plan), ...recheck(captured)];
      if (changed || blocking.length) throw { kind: 'validation', details: ['部署條件已改變，請重新讀取計畫並確認。', ...blocking] };
      const accepted = await execute(captured.id);
      if (gen !== generation || !session.isActive()) return;
      job = accepted;
      error = null; clearTimeout(timer); readJob(captured.id, gen);
    } catch (err) {
      if (gen !== generation) return;
      error = err; auth(err);
      // A missing response may still have created a job. Query the same plan UUID, never execute again.
      if (err.outcomeUnknown || err.status === 503) { clearTimeout(timer); readJob(captured.id, gen, true); }
    } finally {
      if (gen === generation) { busy = false; draw(); }
    }
  });
  reconcileButton.addEventListener('click', async () => {
    if (reconcileButton.disabled) return;
    const id = job.id, gen = generation;
    if (!await confirmDialog({title: '核對遠端紀錄', lines: ['只讀取各站部署紀錄，不重送更新、不恢復資料庫。'], confirmLabel: '核對紀錄'})) return;
    if (gen !== generation || !session.isActive()) return;
    busy = true; draw();
    try { const result = await reconcile(id); if (gen === generation && session.isActive()) { job = result; error = null; } }
    catch (err) { if (gen === generation) { error = err; auth(err); } }
    finally { if (gen === generation) { busy = false; draw(); } }
  });
  lookup.addEventListener('submit', (event) => {
    event.preventDefault();
    if (busy || !session.isActive() || !isUuid(input.value)) return;
    clearTimeout(timer); generation++; job = null; error = null; resetRecovery(); readJob(input.value, generation);
  });
  return {
    // 由任務歷史進入既有詳情：與手動查詢相同，只讀取。
    lookup(id) {
      if (busy || !session.isActive() || !isUuid(id)) return;
      input.value = id; clearTimeout(timer); generation++; job = null; error = null; resetRecovery(); readJob(id, generation);
    },
    render(value) { context = value; draw(); },
    reset() { generation++; clearTimeout(timer); timer = null; job = null; error = null; busy = false; input.value = ''; context = {}; resetRecovery(); panelKey = ''; draw(); },
    current: () => job,
  };
}
