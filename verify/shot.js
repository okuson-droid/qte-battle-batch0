const http=require('http'),fs=require('fs'),path=require('path');
const {chromium}=require('playwright');const {baseView,card,syncCounts,startState}=require('./fixture');
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
 // ★Batch 22: 相手のマナ(表向きにタップ済みを混ぜ、裏はアンタップ2 / タップ1 に割れる)
 v.seatB.zones.MANA=[card('bm1','相手マナ1'),card('bm2','相手マナ2',{tapped:true}),
   card('bm3','裏1',{faceDown:true}),card('bm4','裏2',{faceDown:true}),
   card('bm5','裏3',{faceDown:true})];
 v.seatB.zones.TRASH=[card('bt1','相手墓地')];
 v.seatB.zones.WEAPON=[card('bw1','相手の武器',{type:'WEAPON',hp:null,printedHp:null})];
 syncCounts(v.seatA); syncCounts(v.seatB);
 v.seatA.mp=2; v.seatB.mp=3;
 // ★Batch 23: 開始シーケンスの画面も撮れるようにする(START=method|order|mulligan|banner)
 const stage=process.env.START||'';
 if(stage==='method') v.start=startState({phase:'ORDER_METHOD',locking:true,canChooseMethod:true});
 else if(stage==='order') v.start=startState({phase:'ORDER_CHOICE',locking:true,orderChooser:'A',canChooseOrder:true});
 else if(stage==='mulligan') v.start=startState({phase:'MULLIGAN',locking:true,firstSeat:'A',
   mulliganSeats:['A','B'],mulliganDone:[],myMulliganSeats:['A'],
   waiting:'マリガンの確定を待っています(席A: 選択中 / 席B: 選択中)'});
 else if(stage==='banner') v.start=startState({phase:'MULLIGAN',locking:true,firstSeat:'A',
   mulliganSeats:['A','B'],mulliganDone:['A'],myMulliganSeats:[],
   waiting:'マリガンの確定を待っています(席A: 確定済み / 席B: 選択中)'});
 else if(stage==='begin') v.start=startState({canBegin:true});
 await p.evaluate((view)=>{window.latestView=view;latestView=view;renderAll(view);},v);
 await p.waitForTimeout(300);
 // ★Batch 35: 決着の帯とログの決着行を目視するための経路。
 //   帯はビューの差分から出るので、renderAll ではなく applyView を2回通す。
 //   例: DECLARE=WIN PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node verify/shot.js
 const kind=process.env.DECLARE||'';
 if(kind){
  const label={WIN:'勝利',LOSE:'敗北',DRAW:'引き分け',CONCEDE:'投了'}[kind]||'勝利';
  const seq=(v.log[v.log.length-1]||{seq:0}).seq+1;
  const after=JSON.parse(JSON.stringify(v));
  after.log=after.log.concat([{seq,time:'10:00:04',text:`席A の ${label}を宣言した`}]);
  after.declarations=[{seq,seat:'A',declaration:kind,label}];
  await p.evaluate((views)=>{applyView(views[0]);applyView(views[1]);},[v,after]);
  await p.waitForTimeout(250);
 }
 // ★Batch 38: 開始の儀式を目視するための経路(RITE=dice|deal|mulligan)。
 //   儀式も差分と同じ effects の列から出るので、applyView を2回通すのは決着と同じである。
 //   AT で撮る時刻(ms)をずらせる。配りは 400 前後、マリガンは 700 前後が見どころである。
 //   例: RITE=deal AT=450 PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node verify/shot.js
 const riteKind=process.env.RITE||'';
 if(riteKind){
  const seq=(v.log[v.log.length-1]||{seq:0}).seq+1;
  const before=JSON.parse(JSON.stringify(v));
  before.start=startState({phase:'ORDER_METHOD',locking:true});
  const after=JSON.parse(JSON.stringify(before));
  after.start=startState({phase:'MULLIGAN',locking:true,firstSeat:'A',
    mulliganSeats:['A','B'],myMulliganSeats:['A'],waiting:'マリガンの確定を待っています'});
  const body=riteKind==='dice'
   ?{kind:'DICE',diceA:17,diceB:4,winner:'A',label:'席A が選択権',dealt:[]}
   :riteKind==='mulligan'
    ?{kind:'MULLIGAN',diceA:null,diceB:null,winner:null,label:null,
      dealt:[{seat:'A',back:3,drew:3}]}
    :{kind:'DEAL',diceA:null,diceB:null,winner:null,label:null,
      dealt:[{seat:'A',back:0,drew:4},{seat:'B',back:0,drew:5}]};
  after.rites=[{seq,rite:body}];
  await p.evaluate((views)=>{applyView(views[0]);applyView(views[1]);},[before,after]);
  await p.waitForTimeout(parseInt(process.env.AT||'400',10));
 }
 await p.screenshot({path:process.env.OUT||'verify/layout.png',fullPage:false});
 await b.close();server.close();console.log('shot ok');});
