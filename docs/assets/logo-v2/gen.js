// marola logo v2: cada elemento leva o LaTeX do Desmos e a função JS equivalente, que desenha o PNG
// sem depender do desmos.com. `node gen.js` reescreve o estado, as expressões e os dois PNGs.
const fs=require('fs');const path=require('path');const out=f=>path.join(__dirname,f);const {chromium}=require('playwright');
const C={sky:'#eafafd',cb:'#19ddf0',c:'#1ac5da',cd:'#1badc5',mark:'#057b9e',navy:'#0b4261',w:'#ffffff'};
const R=10;
// curl params
const h=-0.6,k=2.3,R0=6.0,B=0.29,A0=-0.05,T=1.75*Math.PI,TH=0.5,T0=-0.9;
const ro=t=>R0*Math.exp(-B*t);
const Xo=t=>h+ro(t)*Math.cos(t+A0), Yo=t=>k+ro(t)*Math.sin(t+A0);
const ri=t=>ro(t)*(1-TH*Math.pow(1-t/T,0.7));const tt=u=>T0+(T-T0)*u;
const Xi=t=>h+ri(t)*Math.cos(t+A0), Yi=t=>k+ri(t)*Math.sin(t+A0);
// swell bands: f_n(x)=c+a sin(wx+p)+a/3 sin(2.3wx+1.7p)
const bands=[
 {n:1,c:-0.6,a:0.45,w:0.9,p:0.4,col:C.c},
 {n:2,c:-3.0,a:0.9,w:0.62,p:2.2,col:C.cd},
 {n:3,c:-4.9,a:1.0,w:0.55,p:4.1,col:C.mark},
 {n:4,c:-7.2,a:0.8,w:0.7,p:0.9,col:C.navy},
];
const N=(v)=>(+v.toFixed(4)).toString();
const E=[];let id=0;
const add=(o)=>E.push({id:String(++id),...o});
add({note:'marola logo v2: tudo dentro do disco x^2+y^2 <= 100'});
add({latex:'x^2+y^2\\le100',js:{type:'ineq',f:'(x,y)=>x*x+y*y<=100'},color:C.sky,fill:1,line:0,svg:{circle:[0,0,10]}});
add({latex:'(x-5.4)^2+(y-5.3)^2\\le2.2',js:{type:'ineq',f:'(x,y)=>(x-5.4)**2+(y-5.3)**2<=2.2'},color:'#b9f1f8',fill:1,line:0,svg:{circle:[5.4,5.3,Math.sqrt(2.2)]}});
// band 1 (horizonte) atrás da onda
for(const b of bands){add({latex:`f_{${b.n}}(x)=${N(b.c)}+${N(b.a)}\\sin(${N(b.w)}x+${N(b.p)})+${N(b.a/3)}\\sin(${N(2.3*b.w)}x+${N(1.7*b.p)})`,hidden:true});}
const fs_=b=>`(x=>${b.c}+${b.a}*Math.sin(${b.w}*x+${b.p})+${b.a/3}*Math.sin(${2.3*b.w}*x+${1.7*b.p}))`;
const bandExpr=b=>{const f=fs_(b);const fx=eval(f);const top=[];for(let x=-10;x<=10.001;x+=0.05)top.push([x,fx(x)]);add({latex:`y\\le f_{${b.n}}(x)\\left\\{x^2+y^2\\le100\\right\\}`,js:{type:'ineq',f:`(x,y)=>y<=${f}(x)&&x*x+y*y<=100`},color:b.col,fill:1,line:0,svg:{area:top}});
 add({latex:`y=f_{${b.n}}(x)\\left\\{x^2+f_{${b.n}}(x)^2\\le100\\right\\}`,js:{type:'curve',f},color:C.w,width:7});};
// wave body + curl
add({latex:`r_{o}(t)=${N(R0)}e^{-${N(B)}t}`,hidden:true});
add({latex:`r_{i}(t)=r_{o}(t)\\left(1-${N(TH)}\\left(1-\\frac{t}{${N(T)}}\\right)^{0.7}\\right)`,hidden:true});
add({latex:`S(a,t)=\\left(${N(h)}+a\\cos(t${N(A0)}),${N(k)}+a\\sin(t${N(A0)})\\right)`,hidden:true});
add({latex:`U=\\left[0,0.005,...,1\\right]`,hidden:true});add({latex:`v(u)=${N(T0)}+${N(T-T0)}u`,hidden:true});
const P0=[11,-4.2],o0=[Xo(0),Yo(0)],P1=[o0[0]+1.2,o0[1]-3.6];
add({latex:`P_{0}=(${N(P0[0])},${N(P0[1])})`,hidden:true});add({latex:`P_{1}=(${N(P1[0])},${N(P1[1])})`,hidden:true});
add({latex:`Q(s)=(1-s)^2P_{0}+2s(1-s)P_{1}+s^2S\\left(r_{o}(0),0\\right)`,hidden:true});
const lipLatex=`\\operatorname{polygon}\\left(\\operatorname{join}\\left(Q(U),S\\left(r_{o}(${N(T)}U),${N(T)}U\\right),S\\left(r_{i}(v(1-U)),v(1-U)\\right),\\left[\\left(${N(Xi(T0))},-7\\right),\\left(11,-7\\right)\\right]\\right)\\right)`;
const lipPts=[];for(let i=0;i<=200;i++){const q=i/200;lipPts.push([0,1].map(j=>(1-q)**2*P0[j]+2*q*(1-q)*P1[j]+q*q*o0[j]));}const OUT0=lipPts.length;for(let i=0;i<=200;i++){const t=T*i/200;lipPts.push([Xo(t),Yo(t)]);}for(let i=0;i<=200;i++){const t=tt(1-i/200);lipPts.push([Xi(t),Yi(t)]);}lipPts.push([Xi(T0),-7],[11,-7]);
add({latex:lipLatex,js:{type:'poly',pts:lipPts},color:C.cb,fill:1,line:0});
// barrel (interior do tubo) em branco
add({latex:`Q(t)\\left\\{0\\le t\\le1\\right\\}`,js:{type:'param',pts:lipPts.slice(0,201)},color:C.w,width:6});
add({latex:`S\\left(r_{o}(t),t\\right)\\left\\{0\\le t\\le${N(T)}\\right\\}`,js:{type:'param',pts:lipPts.slice(201,402)},color:C.w,width:6});
{const ip=[];for(let i=0;i<=300;i++){const t=tt(i/300);ip.push([Xi(t),Yi(t)]);}
add({latex:`S\\left(r_{i}(t),t\\right)\\left\\{${N(T0)}\\le t\\le${N(T)}\\right\\}`,js:{type:'param',pts:ip},color:C.w,width:4});}
bandExpr(bands[0]);
{const ts=[1.45,1.7,1.95,2.2,2.45,2.75,3.05],ds=[0.42,0.8,0.5,0.95,0.55,0.85,0.45],rs=[0.2,0.13,0.24,0.14,0.21,0.12,0.17];
 ts.forEach((t,i)=>{const r=ro(t)+ds[i],x=h+r*Math.cos(t+A0),y=k+r*Math.sin(t+A0);
 add({latex:`(x${x<0?'+'+N(-x):'-'+N(x)})^2+(y${y<0?'+'+N(-y):'-'+N(y)})^2\\le${N(rs[i]**2)}`,js:{type:'ineq',f:`(x,y)=>(x-(${x}))**2+(y-(${y}))**2<=${rs[i]**2}`},color:C.cb,fill:1,line:0,svg:{circle:[x,y,rs[i]]}});});}
for(const b of bands.slice(1))bandExpr(b);
// check
const ck=[[h-0.55,k-0.05],[h+0.15,k-0.75],[h+1.35,k+0.75]];
add({latex:`\\left(${ck.map(p=>`(${N(p[0])},${N(p[1])})`).join(',')}\\right)`.replace(/^\\left\(/,'\\left[').replace(/\\right\)$/,'\\right]'),js:{type:'poly',pts:ck,open:true},color:C.navy,width:13,lines:true,points:false});
// borda
add({latex:'x^2+y^2=100',js:{type:'circ'},color:C.navy,width:0});

const state={version:11,randomSeed:'marola',graph:{viewport:{xmin:-12,xmax:12,ymin:-12,ymax:12},showGrid:true,showXAxis:true,showYAxis:true,squareAxes:true},
 expressions:{list:E.filter(e=>!(e.width===0)).map(e=>{const o={type:e.note?'text':'expression',id:e.id};if(e.note){o.text=e.note;return o;}
  o.latex=e.latex;if(e.hidden){o.hidden=true;return o;}o.color=e.color;
  if(e.fill!==undefined){o.fillOpacity=String(e.fill);o.lineOpacity='0';o.lineWidth='0';}
  if(e.width){o.lineWidth=String(e.width);o.lineOpacity='1';}
  if(e.lines){o.lines=true;o.points=false;o.lineWidth=String(e.width);}
  if(e.js&&e.js.type==='poly'&&!e.js.open){o.fillOpacity='1';o.lineOpacity='0';o.lineWidth='0';}
  return o;})}};
fs.writeFileSync(out('marola-logo-v2.desmos.json'),JSON.stringify(state,null,1)+'\n');
fs.writeFileSync(out('colar-no-console.js'),'// desmos.com/calculator, F12, cole no Console e dê Enter\nCalc.setState('+JSON.stringify(state)+');\n');
fs.writeFileSync(out('marola-logo-v2.expressions.txt'),E.filter(e=>e.latex&&!(e.width===0)).map(e=>e.latex).join('\n')+'\n');
// SVG: the same drawing as vectors, with no background: the sky disk is left out
{const F=v=>+v.toFixed(3);const pts=a=>a.map(q=>F(q[0])+','+F(-q[1])).join(' ');const out_=[];
 for(const e of E){const j=e.js,v=e.svg;if(!j||e.color===C.sky)continue;const c=e.color;
  if(v&&v.circle)out_.push(`<circle cx="${F(v.circle[0])}" cy="${F(-v.circle[1])}" r="${F(v.circle[2])}" fill="${c}"/>`);
  else if(v&&v.area)out_.push(`<polygon clip-path="url(#d)" points="${pts([...v.area,[10,-10],[-10,-10]])}" fill="${c}"/>`);
  else if(j.type==='curve'){const a=[];for(let x=-10;x<=10.001;x+=0.05)a.push([x,eval(j.f)(x)]);out_.push(`<polyline clip-path="url(#d)" points="${pts(a)}" fill="none" stroke="${c}" stroke-width="${F(e.width/40)}" stroke-linecap="round"/>`);}
  else if(j.type==='param')out_.push(`<polyline clip-path="url(#d)" points="${pts(j.pts)}" fill="none" stroke="${c}" stroke-width="${F(e.width/40)}" stroke-linecap="round"/>`);
  else if(j.type==='poly'&&j.open)out_.push(`<polyline points="${pts(j.pts)}" fill="none" stroke="${c}" stroke-width="${F(e.width/40)}" stroke-linecap="round" stroke-linejoin="round"/>`);
  else if(j.type==='poly')out_.push(`<polygon clip-path="url(#d)" points="${pts(j.pts)}" fill="${c}"/>`);}
 fs.writeFileSync(out('marola-logo-v2.svg'),`<svg xmlns="http://www.w3.org/2000/svg" viewBox="-10.2 -10.2 20.4 20.4" role="img" aria-label="marola: uma onda quebrando dentro de um disco">\n<defs><clipPath id="d"><circle r="10"/></clipPath></defs>\n${out_.join('\n')}\n</svg>\n`);}
// render
const draw=(grid)=>`(()=>{const S=${grid?1200:1000},L=-12,H=12,sc=S/(H-L);const cv=document.createElement('canvas');cv.width=cv.height=S;document.body.appendChild(cv);const g=cv.getContext('2d');
const px=x=>(x-L)*sc,py=y=>(H-y)*sc;g.fillStyle='#fff';g.fillRect(0,0,S,S);
if(${grid}){g.lineWidth=1;for(let v=-12;v<=12;v++){g.strokeStyle=v%5?'#ececec':'#cfcfcf';g.beginPath();g.moveTo(px(v),0);g.lineTo(px(v),S);g.moveTo(0,py(v));g.lineTo(S,py(v));g.stroke();}
g.strokeStyle='#222';g.lineWidth=2;g.beginPath();g.moveTo(px(0),0);g.lineTo(px(0),S);g.moveTo(0,py(0));g.lineTo(S,py(0));g.stroke();}
return {S,sc,px,py,g};})()`;
(async()=>{const b=await chromium.launch();
for(const [grid,out] of [[false,'marola-logo-v2.png'],[true,'marola-logo-v2-grid.png']]){
const p=await b.newPage({viewport:{width:grid?1200:1000,height:grid?1200:1000}});await p.setContent('<body style="margin:0">');
await p.evaluate(({src,ser})=>{const E=eval(ser);const {S,sc,px,py,g}=eval(src);
const off=document.createElement('canvas');off.width=off.height=S;
for(const e of E){const j=e.js;if(!j)continue;g.save();
 if(j.type==='ineq'){const im=g.getImageData(0,0,S,S);const d=im.data;const c=e.color;const r=parseInt(c.slice(1,3),16),gg=parseInt(c.slice(3,5),16),bb=parseInt(c.slice(5,7),16);
  for(let Y=0;Y<S;Y++)for(let X=0;X<S;X++){const x=X/sc-12,y=12-Y/sc;let cov=0;for(const [ox,oy] of [[.25,.25],[.75,.25],[.25,.75],[.75,.75]])if(j.f(x+ox/sc,y-oy/sc))cov++;if(!cov)continue;const a=cov/4,i=(Y*S+X)*4;d[i]=d[i]*(1-a)+r*a;d[i+1]=d[i+1]*(1-a)+gg*a;d[i+2]=d[i+2]*(1-a)+bb*a;}
  g.putImageData(im,0,0);}
 else if(j.type==='curve'){g.beginPath();g.arc(px(0),py(0),10*sc,0,7);g.clip();g.strokeStyle=e.color;g.lineWidth=e.width*sc/40*1.0;g.lineCap='round';g.beginPath();for(let x=-10;x<=10;x+=0.02){const y=j.f(x);x===-10?g.moveTo(px(x),py(y)):g.lineTo(px(x),py(y));}g.stroke();}
 else if(j.type==='param'){g.beginPath();g.arc(px(0),py(0),10*sc,0,7);g.clip();g.strokeStyle=e.color;g.lineWidth=e.width*sc/40;g.lineCap='round';g.beginPath();j.pts.forEach((q,i)=>i?g.lineTo(px(q[0]),py(q[1])):g.moveTo(px(q[0]),py(q[1])));g.stroke();}
 else if(j.type==='poly'){if(!j.open){g.beginPath();g.arc(px(0),py(0),10*sc,0,7);g.clip();}g.beginPath();j.pts.forEach((q,i)=>i?g.lineTo(px(q[0]),py(q[1])):g.moveTo(px(q[0]),py(q[1])));
  if(j.open){g.strokeStyle=e.color;g.lineWidth=e.width*sc/40;g.lineCap='round';g.lineJoin='round';g.stroke();}else{g.closePath();g.fillStyle=e.color;g.fill();}}
 g.restore();}
},{src:draw(grid),ser:'['+E.map(e=>`{color:${JSON.stringify(e.color)},width:${e.width||0},js:${e.js?serialize(e.js):'null'}}`).join(',')+']'});
await p.screenshot({path:path.join(__dirname,out)});await p.close();}
await b.close();})();
function serialize(j){return '{'+Object.entries(j).map(([k,v])=>k+':'+((k==='f')?v:JSON.stringify(v))).join(',')+'}';}
