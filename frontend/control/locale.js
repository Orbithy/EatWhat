import { Converter } from './vendor/opencc/t2cn.js';

export const LOCALE_KEY = 'eatwhat.control.locale';
export const DEFAULT_LOCALE = 'zh-Hans';
const LOCALES = new Set(['zh-Hans', 'zh-Hant']);
const simplify = Converter({ from: 'twp', to: 'cn' });
const ATTRIBUTES = ['aria-label', 'title', 'placeholder'];
const EXCLUDED = '[translate="no"], script, style, code, pre, textarea';

export function readLocale(storage) {
  try { const value = storage.getItem(LOCALE_KEY); return LOCALES.has(value) ? value : DEFAULT_LOCALE; }
  catch { return DEFAULT_LOCALE; }
}
export function saveLocale(storage, value) {
  if (!LOCALES.has(value)) return false;
  try { storage.setItem(LOCALE_KEY, value); return true; } catch { return false; }
}

// OpenCC 台灣片語轉換在中控語境的少數誤譯：登出→注销（易被理解為註銷帳號）、執行→运行、
// 紀錄→纪录、稽核→审核、逾時→逾时。只套用在介面文字；translate="no" 的遠端值與輸入值不經過此處。
export const HANS_OVERRIDES = Object.freeze([['注销', '退出登录'], ['纪录', '记录'], ['审核', '审计'], ['逾时', '超时']]);
// OpenCC 台灣片語會把「執行」轉成「运行」；先在來源把「執行」換成簡體字形，讓原本的「運行」仍正確轉成「运行」。
const HANS_SOURCE_PRESERVE = Object.freeze([['執行', '执行']]);

// Keep the exact source for Traditional; conversion never writes form values or API state.
export function localizedValue(source, locale) {
  if (locale !== 'zh-Hans') return source;
  const prepared = HANS_SOURCE_PRESERVE.reduce((text, [from, to]) => text.split(from).join(to), source);
  return HANS_OVERRIDES.reduce((text, [from, to]) => text.split(from).join(to), simplify(prepared));
}

export function mountLocale(doc) {
  let storage;
  try { storage = doc.defaultView.localStorage; } catch { /* Storage can be blocked. */ }
  let locale = readLocale(storage);
  const select = doc.getElementById('locale-select');
  if (!select) return { disconnect() {} }; // An older HTML response may span a static update.
  const texts = new WeakMap(), attributes = new WeakMap();
  const excluded = (element) => !element || Boolean(element.closest(EXCLUDED));
  function text(node) {
    if (excluded(node.parentElement)) return;
    const previous = texts.get(node);
    const source = previous && node.data === previous.rendered ? previous.source : node.data;
    const rendered = localizedValue(source, locale);
    texts.set(node, { source, rendered });
    if (node.data !== rendered) node.data = rendered;
  }
  function attrs(element) {
    if (excluded(element)) return;
    const saved = attributes.get(element) ?? new Map();
    for (const name of ATTRIBUTES) {
      if (!element.hasAttribute(name)) { saved.delete(name); continue; }
      const value = element.getAttribute(name), previous = saved.get(name);
      const source = previous && value === previous.rendered ? previous.source : value;
      const rendered = localizedValue(source, locale);
      saved.set(name, { source, rendered });
      if (value !== rendered) element.setAttribute(name, rendered);
    }
    attributes.set(element, saved);
  }
  function update(root) {
    if (root.nodeType === 3) { text(root); return; }
    if (root.nodeType !== 1 || excluded(root)) return;
    attrs(root);
    const walker = doc.createTreeWalker(root, 1 | 4, { acceptNode(node) {
      const element = node.nodeType === 1 ? node : node.parentElement;
      return excluded(element) ? 2 : 1;
    } });
    let node;
    while ((node = walker.nextNode())) { if (node.nodeType === 3) text(node); else attrs(node); }
  }
  function apply() {
    doc.documentElement.lang = locale;
    select.value = locale;
    update(doc.body);
  }
  const observer = new doc.defaultView.MutationObserver((records) => {
    // Updating text nodes preserves focus, form selections, pending intents and open dialogs.
    for (const record of records) {
      if (record.type === 'childList') { for (const node of record.addedNodes) update(node); }
      else if (record.type === 'characterData') text(record.target);
      else attrs(record.target);
    }
  });
  observer.observe(doc.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRIBUTES });
  select.addEventListener('change', () => {
    if (!LOCALES.has(select.value)) return;
    locale = select.value; saveLocale(storage, locale); apply();
  });
  apply();
  return { disconnect: () => observer.disconnect() };
}
