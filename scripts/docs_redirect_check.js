#!/usr/bin/env node
// docs_redirect_check — runs mkdocs/overrides/404.html's script against a stub location: the four
// 404-forward prefixes of MIP-0074 Appendix B go to their new home with path, query and hash kept,
// longest prefix first; anything else stays on the not-found page.
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const PAGE = fs.readFileSync(path.resolve(__dirname, '..', 'mkdocs/overrides/404.html'), 'utf8');
const scripts = [...PAGE.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);

let fails = 0;
function ok(cond, label, detail) {
  if (cond) console.log('  ok   ' + label);
  else { console.log('  FAIL ' + label + (detail ? ' — ' + detail : '')); fails++; }
}

function visit(url) {
  const u = new URL(url);
  let to = null;
  const location = { pathname: u.pathname, search: u.search, hash: u.hash, replace: x => { to = x; } };
  vm.runInNewContext(scripts.join('\n'), { window: { location } });
  return to;
}

console.log('docs_redirect_check:');
ok(scripts.length === 1, '404.html has exactly one inline script');
ok(/\{%-?\s*extends\s+"main\.html"/.test(PAGE), '404.html extends the theme, so the page keeps the site chrome');
const D = 'https://docs.marola.dev';
[
  ['/api/ row', D + '/api/scala/index.html', '/5-Repos/marola-app/api-docs/scala/index.html'],
  ['/repos/marola-ml/api/ row', D + '/repos/marola-ml/api/marola_ml.html', '/5-Repos/marola-ml/api-docs/python/marola_ml.html'],
  ['/repos/ row', D + '/repos/marola-site/', '/5-Repos/marola-site/'],
  ['/MIPs/ row', D + '/MIPs/MIP-0074-docs-umbrella-landing-and-repo-docs/', '/6-MIPs/MIP-0074-docs-umbrella-landing-and-repo-docs/'],
  ['/MIPs/ row, the index', D + '/MIPs/', '/6-MIPs/'],
  ['/repos/marola-ml/api/x beats /repos/x', D + '/repos/marola-ml/api/x', '/5-Repos/marola-ml/api-docs/python/x'],
  ['/repos/marola-ml/ (not its api) takes /repos/', D + '/repos/marola-ml/', '/5-Repos/marola-ml/'],
  ['query and hash kept', D + '/MIPs/MIP-0070/?q=split#5-8', '/6-MIPs/MIP-0070/?q=split#5-8'],
  ['query and hash kept on the longest prefix', D + '/repos/marola-ml/api/m.html?x=1#top', '/5-Repos/marola-ml/api-docs/python/m.html?x=1#top'],
  ['an unmatched path is not forwarded', D + '/nope/', null],
  ['a prefix needs its slash: /apiary/ is not /api/', D + '/apiary/', null],
  ['a new URL is not forwarded again', D + '/5-Repos/marola-site/gone/', null],
  ['a new MIPs URL is not forwarded again', D + '/6-MIPs/gone/', null]
].forEach(([label, from, want]) => {
  const got = visit(from);
  ok(got === want, label + ': ' + from + ' → ' + (want || 'stays on the 404'), 'got ' + got);
});

if (fails === 0) { console.log('docs_redirect_check: ok'); process.exit(0); }
console.error('docs_redirect_check: ' + fails + ' failure(s)'); process.exit(1);
