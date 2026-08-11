/*
 * ★Batch 32c: カードフェイスの質感を<b>目視</b>で確かめるためのサンプラ。
 *
 * フェイスはグラデーションの上に載るためコントラストの機械判定の対象外である
 * (設計書32 3章)。判定できないものは人間が見る、という取り決めなので、
 * 全文明 × 全 variant を1枚に並べた画像を撮れるようにしておく。
 *
 * ★盤面と同じ黒背景(#212529)の上で撮ること(31 の教訓)。
 *
 * 使い方:
 *   PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers OUT=/tmp/faces.png node verify/faceshot.js
 */
const http = require('http'), fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const RES = path.join(path.resolve(__dirname, '..'), 'src/main/resources');
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const server = http.createServer((req, res) => {
    const u = req.url.split('?')[0];
    if (u.startsWith('/cards/')) { res.writeHead(200, { 'Content-Type': 'image/png' }); return res.end(PNG); }
    let f = (u === '/' || u === '/harness.html') ? path.join(__dirname, 'harness.html') : path.join(RES, 'static', u);
    if (!fs.existsSync(f)) { res.writeHead(404); return res.end('nf'); }
    const t = f.endsWith('.css') ? 'text/css' : f.endsWith('.js') ? 'application/javascript' : 'text/html; charset=utf-8';
    res.writeHead(200, { 'Content-Type': t });
    res.end(fs.readFileSync(f));
});
server.listen(0, async () => {
    const port = server.address().port;
    const b = await chromium.launch();
    const p = await b.newPage({ viewport: { width: 1180, height: 1000 }, deviceScaleFactor: 2 });
    await p.goto(`http://127.0.0.1:${port}/harness.html`);
    await p.waitForTimeout(200);
    await p.evaluate(() => {
        const CIVS = ['WATER', 'FIRE', 'WIND', 'LIGHT', 'DARK', 'EARTH', 'NONE'];
        const NAMES = {
            WATER: '蒼海の賢者', FIRE: '傷痕の闘帝', WIND: '疾風の導き手',
            LIGHT: '断罪の大天使', DARK: '黄泉の召喚主', EARTH: '連撃の巨岩', NONE: 'ピュア・エレメント',
        };
        const TEXT = '【守護】(相手は守護を持たない他のミニオンやリーダーに攻撃できない)'
            + '\n場に出たとき、カードを1枚引く。';
        const root = document.getElementById('manual-root');
        if (root) root.style.display = 'none';
        document.querySelectorAll('body > *').forEach((n) => { n.style.display = 'none'; });
        const wrap = document.createElement('div');
        wrap.style.cssText = 'padding:14px 16px;font-family:sans-serif;color:#f8f9fa;';
        const mk = (civ, variant, w, h) => {
            const c = {
                instanceId: civ + variant, cardId: 'X', imageId: null, name: NAMES[civ],
                civilization: civ, type: 'MINION', cost: 7, printedAttack: 5, printedHp: 4,
            };
            const box = document.createElement('div');
            box.style.cssText = `width:${w}px;height:${h}px;flex:0 0 auto;`;
            const face = cardFace(c, variant);
            face.querySelector('.mcard-text') && (face.querySelector('.mcard-text').textContent = TEXT);
            box.appendChild(face);
            return box;
        };
        const row = (label, build) => {
            const h = document.createElement('div');
            h.textContent = label;
            h.style.cssText = 'font-size:12px;margin:10px 0 4px;color:#adb5bd;';
            wrap.appendChild(h);
            const r = document.createElement('div');
            r.style.cssText = 'display:flex;gap:10px;align-items:flex-start;';
            CIVS.forEach((civ) => r.appendChild(build(civ)));
            wrap.appendChild(r);
        };
        row('large(拡大パネル) 150×210', (civ) => mk(civ, 'large', 150, 210));
        row('full(手札・マリガン) 100×140', (civ) => mk(civ, 'full', 100, 140));
        row('mini(帯・パイル) 82×114', (civ) => mk(civ, 'mini', 82, 114));
        row('micro(相手上段・山札行) 46×64', (civ) => mk(civ, 'micro', 46, 64));
        // 裏面と、盤面タイルの枠(.mcard-frame)も同じ地の上で見る
        const h2 = document.createElement('div');
        h2.textContent = '裏面(mcard-backface・画像未取得のフォールバック) / 盤面タイルの枠(.mcard-frame)';
        h2.style.cssText = 'font-size:12px;margin:10px 0 4px;color:#adb5bd;';
        wrap.appendChild(h2);
        const r2 = document.createElement('div');
        r2.style.cssText = 'display:flex;gap:10px;align-items:flex-start;';
        const back = cardBackFace();
        back.style.cssText = 'width:100px;height:140px;';
        r2.appendChild(back);
        CIVS.forEach((civ) => {
            const t = document.createElement('div');
            t.className = 'manual-tile mcard-frame';
            t.style.cssText = 'width:130px;height:140px;padding:4px;font-size:11px;border-radius:6px;';
            t.textContent = NAMES[civ];
            t.style.setProperty('--mc', civColor(civ));
            r2.appendChild(t);
        });
        wrap.appendChild(r2);
        document.body.appendChild(wrap);
        document.body.style.background = '#212529';
    });
    await p.waitForTimeout(250);
    await p.screenshot({ path: process.env.OUT || 'verify/faces.png', fullPage: true });
    await b.close();
    server.close();
    console.log('faceshot ok');
});
