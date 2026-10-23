// 測試輔助：模擬 fetch，記錄每次呼叫。不連線任何網路。

export function jsonResponse(status, body) {
  const text = body === undefined ? '' : typeof body === 'string' ? body : JSON.stringify(body);
  return {
    ok: status >= 200 && status < 300,
    status,
    async text() {
      return text;
    },
  };
}

export function fakeFetch(responder) {
  const calls = [];
  const impl = async (url, init = {}) => {
    calls.push({ url, init, body: init.body === undefined ? undefined : JSON.parse(init.body) });
    return responder(url, init, calls.length - 1);
  };
  impl.calls = calls;
  return impl;
}

export function fakeSession(name = 'satoken', value = 'Bearer abc123') {
  return { authHeader: () => ({ name, value }), isActive: () => true };
}

export const UUID_A = '44444444-4444-4444-8444-444444444444';
export const UUID_B = '55555555-5555-4555-8555-555555555555';
