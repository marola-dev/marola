// `node shot.js` renderiza capa.html nos dois PNGs: 460x200 (o tamanho do GitHub Sponsors) e 2x.
const {chromium}=require('playwright');const path=require('path');
(async()=>{const b=await chromium.launch();
for(const [s,o] of [[1,'marola-sponsors-460x200'],[2,'marola-sponsors-920x400']]){
 const p=await b.newPage({viewport:{width:460,height:200},deviceScaleFactor:s});
 await p.goto('file://'+path.join(__dirname,'capa.html'));await p.evaluate(()=>document.fonts.ready);await p.waitForTimeout(400);
 await p.screenshot({path:path.join(__dirname,o+'.png')});}
await b.close();})();
