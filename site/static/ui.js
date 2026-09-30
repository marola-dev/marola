/** marola page chrome: language resolution, t() and the pt/en toggle — MIP-0054 §5.5, §5.8. */
(function () {
  'use strict';

  var CATALOG = window.MAROLA_I18N || {};
  var SOURCE = 'pt-BR';
  var PSEUDO = 'x-pseudo';
  var STORE_KEY = 'marola.lang';
  var SUPPORTED = Object.keys(CATALOG).filter(function (l) { return l !== PSEUDO; });
  var ATTRS = ['title', 'aria-label', 'placeholder'];

  function match(tag, supported) {
    if (!tag) return null;
    var lower = String(tag).toLowerCase(), primary = lower.split('-')[0];
    var exact = supported.filter(function (l) { return l.toLowerCase() === lower; })[0];
    return exact || supported.filter(function (l) { return l.toLowerCase().split('-')[0] === primary; })[0] || null;
  }

  /** §5.5: ?lang=, then the stored choice, then navigator.languages, then pt-BR. No globals. */
  function resolveLang(o) {
    o = o || {};
    var supported = o.supported || [SOURCE];
    if (o.param === PSEUDO) return PSEUDO;
    var hit = match(o.param, supported) || match(o.stored, supported);
    for (var i = 0; !hit && o.languages && i < o.languages.length; i++) hit = match(o.languages[i], supported);
    return hit || SOURCE;
  }

  // --- the ICU subset: {arg}, {n, plural, ...} with #, {x, select, ...} ------------------------.
  var parsed = {};
  function parse(s) {
    var i = 0;
    function word() {
      var m = /^\s*([^\s,{}]+)\s*/.exec(s.slice(i));
      if (!m) throw new Error('i18n: expected a name at ' + i + ' in ' + s);
      i += m[0].length; return m[1];
    }
    function expect(c) { if (s[i] !== c) throw new Error('i18n: expected ' + c + ' at ' + i + ' in ' + s); i++; }
    function message(inPlural) {
      var out = [], text = '';
      while (i < s.length && s[i] !== '}') {
        var c = s[i];
        if (c === '{' || (c === '#' && inPlural)) {
          if (text) { out.push(text); text = ''; }
          i++; out.push(c === '{' ? argument(inPlural) : { hash: true });
        } else { text += c; i++; }
      }
      if (text) out.push(text);
      return out;
    }
    function argument(inPlural) {
      var node = { name: word() };
      if (s[i] === ',') {
        i++; node.type = word(); expect(','); node.cases = {};
        while (/\s/.test(s[i] || '')) i++;
        while (i < s.length && s[i] !== '}') {
          var key = word(); expect('{');
          node.cases[key] = message(inPlural || node.type === 'plural'); expect('}');
          while (/\s/.test(s[i] || '')) i++;
        }
      }
      expect('}');
      return node;
    }
    var nodes = message(false);
    if (i !== s.length) throw new Error('i18n: unbalanced } in ' + s);
    return nodes;
  }

  // Intl rejects the private-use x-pseudo tag, so it formats like the source language.
  function intlLang(lang) { return lang === PSEUDO ? SOURCE : lang; }
  function number(n, lang) { return new Intl.NumberFormat(intlLang(lang)).format(n); }

  function format(nodes, args, lang, n) {
    return nodes.map(function (x) {
      if (typeof x === 'string') return x;
      if (x.hash) return number(n, lang);
      var v = args ? args[x.name] : undefined;
      if (!x.type) return v === undefined ? '{' + x.name + '}' : typeof v === 'number' ? number(v, lang) : String(v);
      if (x.type === 'plural') {
        var c = x.cases['=' + v] || x.cases[new Intl.PluralRules(intlLang(lang)).select(v)] || x.cases.other;
        return format(c, args, lang, v);
      }
      return format(x.cases[v] || x.cases.other, args, lang, n);
    }).join('');
  }

  var current = SOURCE;
  var listeners = [];

  function lookup(key, lang) {
    var cat = CATALOG[lang];
    return cat && Object.prototype.hasOwnProperty.call(cat, key) ? cat[key] : undefined;
  }

  /** The message for key in the current language, else pt-BR's, else the key itself. */
  function t(key, args, lang) {
    lang = lang || current;
    var msg = lookup(key, lang);
    if (msg === undefined) { msg = lookup(key, SOURCE); lang = SOURCE; }
    if (msg === undefined) return key;
    if (!parsed[msg]) parsed[msg] = parse(msg);
    return format(parsed[msg], args, lang);
  }

  function has(key) { return lookup(key, current) !== undefined || lookup(key, SOURCE) !== undefined; }

  // A key missing from every catalog leaves the source text as it is rather than showing the key.
  function applyLang(root) {
    root = root || document;
    Array.prototype.forEach.call(root.querySelectorAll('[data-i18n]'), function (el) {
      var k = el.getAttribute('data-i18n'); if (has(k)) el.textContent = t(k);
    });
    ATTRS.forEach(function (attr) {
      Array.prototype.forEach.call(root.querySelectorAll('[data-i18n-' + attr + ']'), function (el) {
        var k = el.getAttribute('data-i18n-' + attr); if (has(k)) el.setAttribute(attr, t(k));
      });
    });
    document.documentElement.lang = current;
    Array.prototype.forEach.call(document.querySelectorAll('#lang button[data-lang]'), function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-lang') === current));
    });
  }

  function setParam(name, value) {
    var u = new URL(location.href); u.searchParams.set(name, value); history.replaceState(null, '', u);
  }

  function readStored() { try { return window.localStorage.getItem(STORE_KEY); } catch (e) { return null; } }
  function writeStored(lang) { try { window.localStorage.setItem(STORE_KEY, lang); } catch (e) { /* private mode */ } }

  function setLang(lang) {
    if (lang !== PSEUDO && SUPPORTED.indexOf(lang) < 0) return;
    current = lang;
    if (lang !== PSEUDO) writeStored(lang);
    setParam('lang', lang);
    applyLang();
    listeners.forEach(function (fn) { fn(lang); });
  }

  current = resolveLang({
    param: new URLSearchParams(location.search).get('lang'),
    stored: readStored(),
    languages: navigator.languages || (navigator.language ? [navigator.language] : []),
    supported: SUPPORTED
  });
  applyLang();

  var toggle = document.getElementById('lang');
  if (toggle) toggle.addEventListener('click', function (e) {
    var b = e.target.closest('button[data-lang]');
    if (b && b.getAttribute('data-lang') !== current) setLang(b.getAttribute('data-lang'));
  });

  window.marolaI18n = {
    t: t,
    lang: function () { return current; },
    setLang: setLang,
    onLang: function (fn) { listeners.push(fn); },
    applyLang: applyLang,
    resolveLang: resolveLang,
    setParam: setParam,
    supported: SUPPORTED.slice()
  };
})();
