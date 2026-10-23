import { handoffContext, handoffResultView, HANDOFF_REASONS } from './handoff-state.js';
import { describeError } from './format.js';

export function mountHandoff({ client, session, confirmDialog, onAuthFailure, refresh, snapshot, readLeadership }) {
  const byId = (id) => document.getElementById(id);
  const section = byId('cl-handoff'), form = byId('cl-handoff-form'), select = byId('cl-handoff-target');
  const submit = byId('cl-handoff-submit'), cancel = byId('cl-handoff-cancel'), status = byId('cl-handoff-status'), result = byId('cl-handoff-result');
  let busy = false, intent = null, generation = 0, controller = null, submitted = null;
  const context = () => { const s = snapshot(); return handoffContext(s.raw, s.fresh, s.enabled); };
  function message(text) { submitted = null; result.textContent = text; result.hidden = !text; }
  // 交接回應保留為結構，每次重畫與正式 leadership.handoff 合併，避免上方已終態、下方仍顯示 WAITING。
  function showSubmitted(response) {
    submitted = { id: response?.id, state: response?.state };
    if (!handoffResultView(submitted, null, false)) { message(`交接回應缺少有效 UUID（狀態 ${String(response?.state)}）；請以上方領導狀態為準。`); return; }
    syncSubmitted();
  }
  function syncSubmitted() {
    if (!submitted) return;
    const s = snapshot(), view = handoffResultView(submitted, s.raw, s.fresh);
    result.textContent = view.text; result.hidden = false;
  }
  function render() {
    const s = snapshot(), ctx = context();section.hidden = s.enabled !== true;
    const old = intent?.targetControllerId ?? select.value;
    select.replaceChildren(...ctx.candidates.map((entry) => {
      const option = document.createElement('option');option.value = entry.id;
      option.textContent = `${entry.name ?? '中控'} · ${entry.id}${entry.id === s.raw?.localControllerId ? '（目前連線）' : ''}`;return option;
    }));
    if (ctx.candidates.some((entry) => entry.id === old)) select.value = old;
    select.disabled = busy || ctx.pending || intent !== null;
    submit.disabled = busy || !session.isActive() || (!intent && !ctx.canRequest);
    submit.textContent = intent ? '核對並重送交接請求…' : '確認交接…';
    cancel.disabled = busy || !session.isActive() || !ctx.canCancel;
    status.textContent = `${ctx.reason}${ctx.current?.id ? ` · ${ctx.state} · ${ctx.current.id}` : ''}`;
    if (!ctx.pending && ['COMPLETED', 'CANCELLED', 'FAILED'].includes(ctx.state)) status.textContent += ` · ${HANDOFF_REASONS[ctx.current.reason] ?? ctx.current.reason}`;
    syncSubmitted();
  }
  async function fresh() {
    const [raw, caps] = await Promise.all([readLeadership({ signal: controller.signal }), client.getCapabilities({ signal: controller.signal })]);
    return handoffContext(raw, true, caps.leadershipHandoffEnabled);
  }
  async function act(action) {
    if (busy || !session.isActive()) return;
    const captured = context(), target = intent?.targetControllerId ?? select.value;
    if (action === 'request' && !intent && (!captured.canRequest || !captured.candidates.some((entry) => entry.id === target))) return;
    if (action === 'cancel' && !captured.canCancel) return;
    const gen = generation;busy = true;render();controller = new AbortController();
    try {
      const yes = await confirmDialog({ title: action === 'request' ? '確認切換主中控' : '確認取消交接', confirmLabel: action === 'request' ? '等待部署完成後交接' : '取消交接', lines: action === 'request'
        ? [`接管程序：${target}`, `目前 generation：${intent?.expectedGeneration ?? captured.generation}`, '目前部署會繼續完成；交接期間停止受理新部署。結果未知時需先核對遠端紀錄。', '完成後此入口可能變成備機，管理寫入需透過新主入口；同一邏輯權威站的身分保持。']
        : [`交接請求：${captured.current.id}`, '只取消仍在等待的交接；已交出的租約不能撤回。'] });
      if (!yes || gen !== generation || !session.isActive()) return;
      if (action === 'request' && intent) {
        try {
          const saved = await client.getHandoff(intent.requestId, { signal: controller.signal });
          if (gen !== generation) return;intent = null;showSubmitted(saved);return;
        } catch (err) { if (err.status !== 404) throw err; }
      }
      const now = await fresh();
      if (gen !== generation || !session.isActive()) return;
      let response;
      if (action === 'request') {
        const expected = intent?.expectedGeneration ?? captured.generation;
        if (!now.canRequest || now.generation !== expected || !now.candidates.some((entry) => entry.id === target)) { message('確認期間領導或目標狀態已改變，未送出；請重新讀取並確認。');return; }
        intent ??= { requestId: crypto.randomUUID(), targetControllerId: target, expectedGeneration: expected };
        response = await client.requestHandoff(intent, { signal: controller.signal });
      } else {
        if (!now.canCancel || now.current.id !== captured.current.id) { message('交接狀態已改變，未取消。');return; }
        response = await client.cancelHandoff(captured.current.id, { signal: controller.signal });
      }
      if (gen !== generation) return;
      intent = null;showSubmitted(response);
    } catch (err) {
      if (gen !== generation) return;
      if (err.status === 401 || err.status === 403) { onAuthFailure(err);return; }
      if (err.status >= 400 && err.status < 500) intent = null;
      message(`${describeError(err)}${intent ? `；結果未知，保留原請求 ${intent.requestId}，可核對並重送。` : ''}`);
    } finally { if (gen === generation) { busy = false;controller = null;render();refresh(); } }
  }
  form.addEventListener('submit', (event) => { event.preventDefault();act('request'); });
  cancel.addEventListener('click', () => act('cancel'));
  return { render, reset() { generation++;controller?.abort();controller = null;busy = false;intent = null;message('');section.hidden = true;submit.disabled = true;cancel.disabled = true;select.replaceChildren();status.textContent = ''; } };
}
