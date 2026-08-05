const http=require('http'),fs=require('fs'),path=require('path');
const {chromium}=require('playwright');const {baseView,card}=require('./fixture');
const RES=path.join(path.resolve(__dirname,'..'),'src/main/resources');
const PNG=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==','base64');
const server=http.createServer((req,res)=>{const u=req.url.split('?')[0];
 if(u.startsWith('/cards/')){res.writeHead(200,{'Content-Type':'image/png'});return res.end(PNG);}
 let f=(u==='/'||u==='/harness.html')?path.join(__dirname,'harness.html'):path.join(RES,'static',u);
 if(!fs.existsSync(f)){res.writeHead(404);return res.end('nf');}
 const t=f.endsWith('.css')?'text/css':f.endsWith('.js')?'application/javascript':'text/html; charset=utf-8';
 res.writeHead(200,{'Content-Type':t});res.end(fs.readFileSync(f));});
server.listen(0,async()=>{
 const port=server.address().port;
 const b=await chromium.launch();
 const W=parseInt(process.env.W||'1280',10), H=parseInt(process.env.H||'950',10);
 const p=await b.newPage({viewport:{width:W,height:H}});
 await p.goto(`http://127.0.0.1:${port}/harness.html`);await p.waitForTimeout(200);
 const v=baseView();
 v.shared.PLAY=[card('p1','プレイ中の札')];
 v.seatA.zones.WEAPON=[card('w1','装備中の武器',{type:'WEAPON',hp:null,printedHp:null})];
 v.seatA.zones.MANA=[card('m1','マナ1'),card('m2','マナ2'),card('m3','マナ3',{faceDown:true})];
 v.seatA.zones.PRIVATE=[card('pv1','確認中')];
 v.seatA.zones.LOST=[card('l1','消滅1')];
 v.seatB.zones.FIELD=[card('bf1','相手の場')];
 await p.evaluate((view)=>{window.latestView=view;renderAll(view);},v);
 await p.waitForTimeout(300);
 await p.screenshot({path:process.env.OUT||'verify/layout.png',fullPage:false});
 await b.close();server.close();console.log('shot ok');});
