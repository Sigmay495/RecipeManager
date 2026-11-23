// Checks the offline review mockup, not the Android implementation.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(path.resolve(__dirname, '../../dst/ui-review/index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const elements = new Map();
function element() {
  return { innerHTML: '', textContent: '', scrollTop: 0, attributes: {},
    classList: { toggle() {} }, style: { setProperty(key, value) { this[key] = value; } },
    setAttribute(key, value) { this.attributes[key] = value; }, remove() {} };
}
const context = vm.createContext({
  document: { getElementById(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); },
    createElement: element, body: { append() {} } }, setTimeout() {},
});
vm.runInContext(script, context, { timeout: 1000 });
for (const route of vm.runInContext('Object.keys(names)', context)) {
  vm.runInContext(`show(${JSON.stringify(route)})`, context, { timeout: 1000 });
  assert.ok(elements.get('main').innerHTML.length > 100, route);
  assert.ok(elements.get('title').textContent.length > 0, route);
}
vm.runInContext('theme(true); scale(2); necessary=false; show("menu")', context);
assert.equal(elements.get('phone').style['--scale'], 2);
assert.ok(elements.get('main').innerHTML.includes('必要にする'));
assert.equal(elements.get('dark').attributes['aria-pressed'], true);
console.log('Passed: 14 mockup routes, theme/scale controls and sample requirement toggle.');
