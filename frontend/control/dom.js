// 安全 DOM 建構：子節點字串一律成為文字節點；不支援 HTML 字串、inline 事件或 style 屬性。

const FORBIDDEN_ATTRS = new Set(['style', 'srcdoc', 'href', 'src', 'action', 'formaction']);

export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [key, value] of Object.entries(props)) {
      if (value === undefined || value === null || value === false) continue;
      if (/^on/i.test(key) || FORBIDDEN_ATTRS.has(key.toLowerCase())) {
        throw new Error(`不允許的屬性：${key}`);
      }
      if (key === 'class') el.className = String(value);
      else if (key === 'text') el.textContent = String(value);
      else if (key === 'dataset') Object.assign(el.dataset, value);
      else if (value === true) el.setAttribute(key, '');
      else el.setAttribute(key, String(value));
    }
  }
  appendChildren(el, children);
  return el;
}

export function appendChildren(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === undefined || child === null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

export function replace(el, ...children) {
  el.replaceChildren();
  return appendChildren(el, children);
}
