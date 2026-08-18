/**
 * ★Batch 42: 通常モード(自動モード)盤面の目視用サンプラ。
 * フェイス化した盤面を、状態(playable / can-attack / 凍結 / 実効コスト)込みで1枚に撮る。
 *
 * 使い方:
 *   PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers OUT=/tmp/auto.png node verify/autoshot.js
 *   ZOOM=1 を足すと拡大パネルを開いた状態で撮る
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const { autoView, autoPlayer, autoCard, autoMinion } = require('./fixture');

const ROOT = path.resolve(__dirname, '..');
const RES = path.join(ROOT, 'src/main/resources');
const OUT = process.env.OUT || '/tmp/autoboard.png';
const WITH_ZOOM = process.env.ZOOM === '1';

const LIBRARY = { cards: [
  { id: 'M1', ledgerCardId: 'QTE-0001', name: '炎の従者', civilization: 'FIRE',
    type: 'MINION', cost: 2, attack: 2, hp: 3, text: '【速攻】', imageId: 'x' },
  { id: 'M2', ledgerCardId: 'QTE-0044', name: '守りの岩兵', civilization: 'EARTH',
    type: 'MINION', cost: 3, attack: 1, hp: 5, text: '【守護】', imageId: 'x' },
  { id: 'M3', ledgerCardId: 'QTE-L001', name: '傷痕の闘帝', civilization: 'FIRE',
    type: 'LEADER', cost: 0, attack: null, hp: null,
    text: '【起動：1】自分のリーダーに1ダメージ。そうしたら1枚ドローする', imageId: 'x' },
  { id: 'M4', ledgerCardId: 'QTE-L002', name: '蒼海の賢者', civilization: 'WATER',
    type: 'LEADER', cost: 0, attack: null, hp: null, text: '【起動：1】…', imageId: 'x' },
] };

(async () => {
  const server = http.createServer((req, res) => {
    const url = req.url.split('?')[0];
    if (url === '/manual/api/card-library') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify(LIBRARY));
      return;
    }
    const file = url.startsWith('/css/') || url.startsWith('/js/')
      ? path.join(RES, 'static', url)
      : path.join(__dirname, url === '/' ? 'harness-battle.html' : url);
    fs.readFile(file, (e, d) => {
      if (e) { res.writeHead(404); res.end(); return; }
      const type = url.endsWith('.css') ? 'text/css'
        : url.endsWith('.js') ? 'text/javascript' : 'text/html; charset=utf-8';
      res.writeHead(200, { 'Content-Type': type });
      res.end(d);
    });
  });
  await new Promise((r) => server.listen(0, r));
  const port = server.address().port;

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await page.goto(`http://127.0.0.1:${port}/harness-battle.html`);
  await page.waitForTimeout(300);

  const view = autoView({
    turnNumber: 4, phase: 'MAIN', phaseDisplay: 'メイン',
    you: autoPlayer({
      lp: 16, availableMp: 4, totalMana: 6,
      manaZone: [
        { name: 'a', tapped: false, faceUp: true }, { name: 'b', tapped: false, faceUp: true },
        { name: 'c', tapped: false, faceUp: true }, { name: 'd', tapped: false, faceUp: true },
        { name: 'e', tapped: true, faceUp: true }, { name: null, tapped: true, faceUp: false },
      ],
      hand: [
        autoCard('QTE-0001', '炎の従者', { cost: 2, keywords: ['速攻'] }),
        autoCard('QTE-0025', 'スプラッシュ・ドロー', {
          type: 'SPELL', civilization: 'WATER', cost: 2, attack: null, hp: null,
          text: 'カードを2枚引く',
        }),
        autoCard('QTE-0041', '双流の幻術師', {
          civilization: 'WATER', cost: 5, effectiveCost: 3, attack: 2, hp: 3,
          keywords: ['知識'], text: '場に居るミニオンの数Cost-1。【召喚時】ミニオンを3体選び持ち主の手札に戻す',
        }),
        autoCard('QTE-0138', '創世神 ガイア', {
          civilization: 'EARTH', cost: 10, attack: 10, hp: 10, canSpecialSummon: true,
          text: '【召喚時】このミニオン以外の、お互いの場のミニオンをすべて破壊。',
        }),
      ],
      minions: [
        autoMinion('m1', '炎の従者', { cardId: 'QTE-0001' }),
        autoMinion('m2', '守りの岩兵', { cardId: 'QTE-0044', keywords: ['守護'], currentHp: 2, maxHp: 5 }),
        autoMinion('m3', '凍った従者', { cardId: 'QTE-0001', frozen: true }),
      ],
      taboo: [autoCard('QTE-0075', '禁忌の代償', {
        type: 'SPELL', civilization: 'DARK', cost: 1, attack: null, hp: null,
        text: '自分のマナゾーンの「裏向きのカード」1枚を破壊する。',
      })],
      tabooCount: 1,
      weaponName: '死神の大鎌', weaponAttack: 3,
    }),
    opponent: autoPlayer({
      displayName: 'あいて', leaderName: '蒼海の賢者', leaderCardId: 'QTE-L002',
      lp: 12, handCount: 4,
      manaZone: [
        { name: null, tapped: false, faceUp: true }, { name: null, tapped: true, faceUp: false },
      ],
      minions: [
        autoMinion('e1', '敵の従者', { cardId: 'QTE-0001' }),
        autoMinion('e2', 'タップ中の敵', { cardId: 'QTE-0044', tapped: true }),
      ],
    }),
    log: ['あなた: 炎の従者を召喚', 'あいて: スペルを使用', 'ターン4 開始'],
  });
  await page.evaluate((v) => { latestView = v; render(v); }, view);
  await page.waitForTimeout(200);

  if (process.env.TABOO === '1') {
    await page.evaluate(() => toggleTabooRow());
    await page.waitForTimeout(150);
  }
  if (WITH_ZOOM) {
    const box = await page.locator('#my-hand .auto-card').nth(2).boundingBox();
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { button: 'right' });
    await page.waitForTimeout(150);
  }

  // ★43: 1画面レイアウトなので fullPage にしない。見えているものが全てである
  await page.screenshot({ path: OUT });
  console.log('wrote ' + OUT);
  await browser.close();
  server.close();
})();
