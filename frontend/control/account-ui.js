// 帳號設定頁：只修改目前登入者自己的使用者名稱／密碼。
// - 確認對話框不顯示任何密碼。
// - 確認後重新讀取帳號狀態（canEdit、使用者名稱）再送出；條件改變就不送。
// - 成功或結果未知都清除全部密碼與權杖並回到登入頁；結果未知時不自動重送。
// - 登出後到達的舊回應一律丟棄（generation 柵欄）。

import { UNCONFIRMED_SUCCESS_TEXT, accountFailure, confirmedCredentialResult, credentialChange, normalizeAccount, readonlyReasonText } from './account-state.js';
import { describeError } from './format.js';

export function mountAccount({ client, session, confirmDialog, onReauthenticate, onAuthFailure }) {
  const $ = (id) => document.getElementById(id);
  const els = {
    username: $('account-current-username'),
    status: $('account-status'),
    form: $('account-form'),
    current: $('account-current-password'),
    newUsername: $('account-new-username'),
    newPassword: $('account-new-password'),
    confirm: $('account-confirm-password'),
    problems: $('account-problems'),
    submit: $('account-submit'),
    result: $('account-result'),
    me: $('me-name'),
  };
  let account = null;
  let loadError = null;
  let loading = false;
  let busy = false;
  let dirty = false;
  let generation = 0;

  const values = () => ({
    currentUsername: account?.username,
    currentPassword: els.current.value,
    newUsername: els.newUsername.value,
    newPassword: els.newPassword.value,
    confirmPassword: els.confirm.value,
  });

  function message(tone, text) {
    els.result.hidden = !text;
    els.result.className = `message message-${tone}`;
    els.result.textContent = text ?? '';
  }

  function clearSecrets() {
    els.current.value = '';
    els.newPassword.value = '';
    els.confirm.value = '';
  }

  function render() {
    els.username.textContent = account ? account.username : loading ? '讀取中…' : '—';
    els.me.textContent = account ? account.username : els.me.textContent;
    let statusText = '';
    if (loadError) statusText = `無法讀取帳號狀態：${describeError(loadError)}。儲存已停用。`;
    else if (account && !account.canEdit) statusText = readonlyReasonText(account.reason);
    else if (!account) statusText = loading ? '正在讀取帳號狀態…' : '';
    els.status.hidden = !statusText;
    els.status.textContent = statusText;
    const check = credentialChange(values());
    const problems = dirty ? check.errors : [];
    els.problems.replaceChildren(...problems.map((text) => { const li = document.createElement('li'); li.textContent = text; return li; }));
    els.problems.hidden = problems.length === 0;
    els.submit.disabled = busy || !account || !account.canEdit || check.errors.length > 0 || !session.isActive();
    els.submit.textContent = busy ? '處理中…' : '儲存變更…';
  }

  async function load() {
    if (!session.isActive()) return null;
    const gen = generation;
    loading = true;
    render();
    try {
      const data = await client.getAccount();
      if (gen !== generation) return null;
      account = normalizeAccount(data);
      loadError = account ? null : { kind: 'protocol', serverMessage: '帳號回應缺少 username' };
    } catch (err) {
      if (gen !== generation) return null;
      if (err?.status === 401 || err?.status === 403) { onAuthFailure(err); return null; }
      loadError = err;
    } finally {
      if (gen === generation) { loading = false; render(); }
    }
    return account;
  }

  async function submit(event) {
    event.preventDefault();
    dirty = true;
    if (busy || !session.isActive() || !account?.canEdit) { render(); return; }
    const check = credentialChange(values());
    if (check.errors.length) { render(); return; }
    const gen = generation;
    const capturedUsername = account.username;
    const lines = [
      check.changes.username ? `使用者名稱：改為 ${check.body.newUsername}` : '使用者名稱：不變',
      check.changes.password ? '密碼：將更新（不顯示內容）' : '密碼：不變',
      '成功後，所有既有登入（包含這個分頁與其他裝置）都會立即失效，需要重新登入。',
    ];
    busy = true;
    render();
    try {
      const yes = await confirmDialog({ title: '確認修改帳號', lines, confirmLabel: '確認修改並重新登入' });
      if (!yes || gen !== generation || !session.isActive()) return;
      // 確認後重新核對：入口是否仍可修改、使用者名稱是否未被其他程序改動。
      const fresh = normalizeAccount(await client.getAccount());
      if (gen !== generation || !session.isActive()) return;
      if (!fresh || !fresh.canEdit || fresh.username !== capturedUsername) {
        account = fresh ?? account;
        message('amber', fresh && !fresh.canEdit ? `確認期間狀態已改變，未送出：${readonlyReasonText(fresh.reason)}` : '確認期間帳號狀態已改變，未送出；請重新檢查後再試。');
        return;
      }
      const body = credentialChange({ ...values(), currentUsername: fresh.username }).body;
      if (!body) { message('amber', '確認期間表單內容已改變，未送出。'); return; }
      const result = await client.changeCredentials(body);
      if (gen !== generation) return;
      clearSecrets();
      const expected = body.newUsername ?? capturedUsername;
      if (!confirmedCredentialResult(result, expected)) {
        onReauthenticate({ message: UNCONFIRMED_SUCCESS_TEXT, username: null });
        return;
      }
      onReauthenticate({ message: '帳號已更新，所有舊登入已失效。請使用新的帳密重新登入。', username: expected });
    } catch (err) {
      if (gen !== generation) return;
      clearSecrets();
      const failure = accountFailure(err);
      if (failure.outcome === 'LOGOUT' || failure.outcome === 'UNKNOWN') {
        onReauthenticate({ message: failure.message, username: null });
        return;
      }
      message(failure.outcome === 'REJECTED' && err?.status === 429 ? 'amber' : 'red', failure.message);
    } finally {
      if (gen === generation) { busy = false; dirty = false; render(); }
    }
  }

  els.form.addEventListener('input', () => { dirty = true; message('neutral', ''); render(); });
  els.form.addEventListener('submit', submit);

  return {
    load,
    reset() {
      generation += 1;
      account = null;
      loadError = null;
      loading = false;
      busy = false;
      dirty = false;
      clearSecrets();
      els.newUsername.value = '';
      els.me.textContent = '';
      message('neutral', '');
      render();
    },
    render,
  };
}
