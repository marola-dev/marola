// marola logo v2: uma onda em espiral de Fibonacci sobre o mar, só com curvas paramétricas, no traço
// do desenho do h0ffmann/smart-alerts. `node gen.js` escreve o SVG e o estado do Desmos;
// `node gen.js png` também renderiza o PNG (precisa do playwright).
const fs = require('fs'), path = require('path');
const out = f => path.join(__dirname, f);

const H = 200;               // Desmos tem y para cima: y_desmos = H - y_svg
const C = [100, 100], R = 88; // o disco do logo
const S = 6, FIB = [1, 1, 2, 3, 5, 8, 13];
const START = [37, 128];     // onde a onda sai do mar
const INK = '#057b9e', SAMPLE = '#19ddf0', W = 3.2;

const f = x => +x.toFixed(4);
const exprs = [];
const add = (latex, min, max, fn) => exprs.push({ latex, min, max, fn });

// a espiral: quartos de círculo com raios S × (13, 8, 5, 3, 2, 1, 1), de fora para dentro
const ends = [];
{
  const right = h => [-h[1], h[0]];
  let p = START, h = [0, -1];
  for (const r of FIB.slice().reverse().map(n => S * n)) {
    const rv = right(h), c = [p[0] + r * rv[0], p[1] + r * rv[1]], e = [c[0] + r * h[0], c[1] + r * h[1]];
    const cy = H - c[1], a0 = Math.atan2((H - p[1]) - cy, p[0] - c[0]);
    add(`\\left(${c[0]}+${r}\\cos(t),${cy}+${r}\\sin(t)\\right)`, a0 - Math.PI / 2, a0,
      t => [c[0] + r * Math.cos(t), H - (cy + r * Math.sin(t))]);
    ends.push(e); p = e; h = rv;
  }
}

// o mar: três marolas, cordas do disco com um seno. A primeira passa pelo pé da onda e pelo fundo
// do terceiro arco, e some entre os dois, onde a onda está de pé.
const chord = (y0, m) => { const hw = Math.sqrt(R * R - (y0 - C[1]) ** 2) - m; return [C[0] - hw, C[0] + hw]; };
const sea = (y0, A, k, p0, lo, hi) => {
  const yd = H - y0;
  add(`\\left(t,${yd}${A < 0 ? '' : '+'}${A}\\sin(${f(k)}t${p0 < 0 ? '' : '+'}${f(p0)})\\right)`, lo, hi,
    t => [t, H - (yd + A * Math.sin(k * t + p0))]);
};
{
  const [lo, hi] = chord(START[1], 8), k = 4 * Math.PI / (ends[2][0] - START[0]);
  sea(START[1], -3.5, k, -k * START[0], lo, START[0]);
  sea(START[1], -3.5, k, -k * START[0], ends[2][0], hi);
}
sea(150, 4, 0.14, 1.9, ...chord(150, 14));
sea(170, 3.5, 0.16, 3.6, ...chord(170, 16));

// o disco
add(`\\left(${C[0]}+${R}\\cos(t),${H - C[1]}+${R}\\sin(t)\\right)`, 0, 2 * Math.PI,
  t => [C[0] + R * Math.cos(t), C[1] - R * Math.sin(t)]);

// o check da qualidade da água, dentro da onda
for (const [a, b] of [[[66, 96], [77, 107]], [[77, 107], [97, 83]]]) {
  add(`\\left(${a[0]}+${b[0] - a[0]}t,${H - a[1]}${a[1] - b[1] < 0 ? '' : '+'}${a[1] - b[1]}t\\right)`, 0, 1,
    t => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
}

const state = {
  version: 11,
  graph: { viewport: { xmin: 0, xmax: 200, ymin: 0, ymax: 200 }, showGrid: false, showXAxis: false, showYAxis: false },
  expressions: { list: exprs.map((e, i) => ({
    type: 'expression', id: String(i + 1), color: INK, lineWidth: String(W), latex: e.latex,
    parametricDomain: { min: e.min.toFixed(6), max: e.max.toFixed(6) },
  })) },
};
const poly = (e, n) => Array.from({ length: n + 1 }, (_, i) => e.fn(e.min + (e.max - e.min) * i / n).map(v => v.toFixed(2)).join(',')).join(' ');
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200" width="200" height="200" role="img" aria-label="marola logo v2: uma onda em espiral de Fibonacci sobre o mar, desenhada a partir das expressões do Desmos">
  <title>marola logo v2</title>
  <desc>A onda tem sete quartos de círculo com raios 6 x (13, 8, 5, 3, 2, 1, 1). A linha azul é cada expressão de marola-logo-v2.desmos.json amostrada em 241 pontos; a linha ciano por dentro, em 65.</desc>
  <g fill="none" stroke="${INK}" stroke-width="${W}" stroke-linecap="round" stroke-linejoin="round">
${exprs.map(e => `    <polyline points="${poly(e, 240)}"/>`).join('\n')}
  </g>
  <g fill="none" stroke="${SAMPLE}" stroke-width="1" stroke-linecap="round" stroke-linejoin="round">
${exprs.map(e => `    <polyline points="${poly(e, 64)}"/>`).join('\n')}
  </g>
</svg>
`;
fs.writeFileSync(out('marola-logo-v2.svg'), svg);
fs.writeFileSync(out('marola-logo-v2.desmos.json'), JSON.stringify(state, null, 2) + '\n');
fs.writeFileSync(out('marola-logo-v2.expressions.txt'), exprs.map(e => `${e.latex}\\left\\{${f(e.min)}\\le t\\le${f(e.max)}\\right\\}`).join('\n') + '\n');

if (process.argv[2] === 'png') (async () => {
  const { chromium } = require('playwright');
  const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 200, height: 200 }, deviceScaleFactor: 5 });
  await p.setContent(`<body style="margin:0;background:#fff">${svg}</body>`);
  await p.screenshot({ path: out('marola-logo-v2.png') });
  await b.close();
})();
