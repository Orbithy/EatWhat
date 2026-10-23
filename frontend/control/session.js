// 管理員權杖只存在這個閉包變數中：不寫入任何瀏覽器儲存、cookie、URL 或日誌。
// 重新整理頁面即遺失，需重新登入。

import { bearerValue, isValidHeaderName } from './api.js';

export function createSession() {
  let auth = null;

  const session = {
    set(tokenName, tokenValue) {
      if (!isValidHeaderName(tokenName)) throw new TypeError('tokenName 無效');
      const value = bearerValue(tokenValue);
      auth = { name: tokenName, value };
    },
    clear() {
      auth = null;
    },
    isActive() {
      return auth !== null;
    },
    tokenName() {
      return auth ? auth.name : null;
    },
    authHeader() {
      return auth ? { name: auth.name, value: auth.value } : null;
    },
    // 防止意外序列化或除錯輸出洩漏權杖。
    toJSON() {
      return { active: auth !== null };
    },
  };
  return Object.freeze(session);
}
