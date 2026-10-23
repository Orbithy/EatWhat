// 管理員修改自己的使用者名稱／密碼：純驗證與錯誤分類（可在 Node 測試）。
// 密碼一律不 trim；確認密碼只在前端比對，不送後端。

export const USERNAME_RE = /^[A-Za-z0-9_]{3,32}$/;
export const PASSWORD_MIN_CHARS = 12;
export const PASSWORD_MAX_CHARS = 64;
export const PASSWORD_MAX_BYTES = 72;

const encoder = new TextEncoder();

export function usernameProblem(name) {
  if (!USERNAME_RE.test(name)) return '新使用者名稱需 3–32 個字元，只能使用英文字母、數字與底線。';
  if (/^\d{11}$/.test(name)) return '新使用者名稱不能是 11 位純數字。';
  return null;
}

export function passwordProblem(password) {
  const chars = [...password].length;
  if (chars < PASSWORD_MIN_CHARS || chars > PASSWORD_MAX_CHARS) return `新密碼需 ${PASSWORD_MIN_CHARS}–${PASSWORD_MAX_CHARS} 個字元。`;
  if (encoder.encode(password).length > PASSWORD_MAX_BYTES) return `新密碼以 UTF-8 計不得超過 ${PASSWORD_MAX_BYTES} bytes（中文等字元每個占 3 bytes）。`;
  if (/^\s+$/u.test(password)) return '新密碼不能全部是空白。';
  return null;
}

// 回傳 { errors, body, changes }；只有 errors 為空時 body 才可送出。未修改的欄位不放進 body。
export function credentialChange({ currentUsername, currentPassword = '', newUsername = '', newPassword = '', confirmPassword = '' }) {
  const errors = [];
  const changeUsername = newUsername !== '';
  const changePassword = newPassword !== '' || confirmPassword !== '';
  if (currentPassword === '') errors.push('請輸入目前密碼；所有修改都需要驗證目前密碼。');
  if (!changeUsername && !changePassword) errors.push('請至少修改使用者名稱或密碼其中一項；不修改的欄位請留空。');
  if (changeUsername) {
    const problem = usernameProblem(newUsername);
    if (problem) errors.push(problem);
    else if (typeof currentUsername === 'string' && newUsername === currentUsername) errors.push('新使用者名稱與目前相同；若不修改請留空。');
  }
  if (changePassword) {
    const problem = passwordProblem(newPassword);
    if (problem) errors.push(problem);
    if (newPassword !== confirmPassword) errors.push('兩次輸入的新密碼不一致。');
    if (currentPassword !== '' && newPassword === currentPassword) errors.push('新密碼不能與目前密碼相同。');
  }
  if (errors.length) return { errors, body: null, changes: { username: changeUsername, password: changePassword } };
  const body = { currentPassword };
  if (changeUsername) body.newUsername = newUsername;
  if (changePassword) body.newPassword = newPassword;
  return { errors, body, changes: { username: changeUsername, password: changePassword } };
}

export const ACCOUNT_ERROR_TEXT = Object.freeze({
  CURRENT_PASSWORD_INVALID: '目前密碼不正確，未修改。',
  INVALID_USERNAME: '新使用者名稱不符合規則，未修改。',
  INVALID_NEW_PASSWORD: '新密碼不符合規則，未修改。',
  NO_CREDENTIAL_CHANGES: '沒有需要修改的內容。',
  PASSWORD_UNCHANGED: '新密碼不能與目前密碼相同，未修改。',
  USERNAME_UNAVAILABLE: '這個使用者名稱無法使用，請換一個。',
  ACCOUNT_AUTHORITY_REQUIRED: '這個入口不能修改帳號，請改用權威站的主中控入口。',
  CONTROL_HANDOFF_PENDING: '主中控交接進行中，暫時不能修改帳號。',
  CREDENTIAL_RATE_LIMITED: '嘗試次數過多（每個帳號 5 分鐘內最多 5 次），請稍後再試。',
  ACCOUNT_SECURITY_UNAVAILABLE: '帳號安全服務暫時不可用，請稍後再試。',
  ACCOUNT_UPDATE_UNAVAILABLE: '帳號更新服務暫時不可用（尚未開始寫入），未修改，請稍後再試。',
  ACCOUNT_UPDATE_OUTCOME_UNKNOWN: '帳號更新可能已寫入，但伺服器未能確認結果。中控不會自動重送；已清除密碼與登入，請分別用新、舊帳密重新登入確認目前生效的是哪一組。',
  CONTROL_LEASE_NOT_HELD: '這個入口目前不是主中控，請改用主中控入口。',
  ACCOUNT_CREDENTIALS_CHANGED: '帳號憑證已變更，所有舊登入已失效，請重新登入。',
  AUTHENTICATION_REQUIRED: '登入已失效，請重新登入。',
  ADMIN_REQUIRED: '此帳號不具管理員權限，已登出。',
});

const KNOWN_503 = new Set(['ACCOUNT_SECURITY_UNAVAILABLE', 'ACCOUNT_UPDATE_UNAVAILABLE', 'CONTROL_LEASE_NOT_HELD']);

// LOGOUT：401／403 回到登入頁。UNKNOWN：網路、逾時、無法解析或未定義的 5xx，不自動重送，要求重新登入確認。
// REJECTED：400／409／429 與已定義的 503，保留登入。
export function accountFailure(err) {
  const code = typeof err?.code === 'string' ? err.code : null;
  if (err?.kind === 'validation') return { outcome: 'REJECTED', message: '輸入未通過檢查，未送出。' };
  if (err?.status === 401 || err?.status === 403) {
    return { outcome: 'LOGOUT', message: ACCOUNT_ERROR_TEXT[code] ?? (err.status === 403 ? ACCOUNT_ERROR_TEXT.ADMIN_REQUIRED : ACCOUNT_ERROR_TEXT.AUTHENTICATION_REQUIRED) };
  }
  if (code === 'ACCOUNT_UPDATE_OUTCOME_UNKNOWN') return { outcome: 'UNKNOWN', message: ACCOUNT_ERROR_TEXT.ACCOUNT_UPDATE_OUTCOME_UNKNOWN };
  if (err?.outcomeUnknown || (err?.status >= 500 && !(err.status === 503 && KNOWN_503.has(code)))) {
    return { outcome: 'UNKNOWN', message: '修改結果未知（網路中斷、逾時或伺服器未回應）。中控不會自動重送；已清除密碼與登入，請重新登入確認目前使用哪一組帳密。' };
  }
  if ([400, 409, 429, 503].includes(err?.status)) {
    return { outcome: 'REJECTED', message: ACCOUNT_ERROR_TEXT[code] ?? `伺服器拒絕本次修改（HTTP ${err.status}${code ? ` · ${code}` : ''}）。` };
  }
  return { outcome: 'REJECTED', message: `伺服器拒絕本次修改（HTTP ${err?.status ?? '?'}${code ? ` · ${code}` : ''}）。` };
}

export const UNCONFIRMED_SUCCESS_TEXT = '伺服器回應無法確認修改結果（缺少 reauthenticationRequired 或名稱不符）。中控不會自動重送；已清除密碼與登入，請重新登入確認目前使用哪一組帳密。';

// 只有 200 內容明確為 { username: 預期名稱, reauthenticationRequired: true } 才算成功；其餘視為結果未知。
export function confirmedCredentialResult(result, expectedUsername) {
  return Boolean(result) && typeof result === 'object' && !Array.isArray(result)
    && result.reauthenticationRequired === true
    && typeof result.username === 'string' && result.username === expectedUsername;
}

export const ACCOUNT_READONLY_TEXT = '這個入口目前不能修改帳號；請改用主中控入口，或稍後再試。';

// GET /account 的 reason 是代碼：已知代碼轉成說明，未知代碼不直接顯示，改用通用唯讀說明。
export function readonlyReasonText(reason) {
  return (typeof reason === 'string' && Object.hasOwn(ACCOUNT_ERROR_TEXT, reason)) ? ACCOUNT_ERROR_TEXT[reason] : ACCOUNT_READONLY_TEXT;
}

export function normalizeAccount(data) {
  if (!data || typeof data !== 'object' || typeof data.username !== 'string' || data.username === '') return null;
  return {
    username: data.username,
    canEdit: data.canEdit === true,
    reason: typeof data.reason === 'string' && data.reason ? data.reason : null,
  };
}
