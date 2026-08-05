/**
 * Batch 20b の実マウス検証。
 *
 * ★合成 DragEvent は一切使わない。すべて page.mouse.down / move / up で行う
 * (19b hotfix2・20a 3-1/3-2 の教訓。合成イベントはブラウザ本来のドラッグ機構を通らず、
 *  「ブラウザがドラッグを中断する」種類の不具合を原理的に再現できない)。
 *
 * ★補間は細かく刻む。20a の検証で、粗いステップだと小さいドロップ対象を飛び越えて
 *   dragover / drop 自体が発火しないことが判明している。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const { baseView, card } = require('./fixture');

const ROOT = path.resolve(__dirname, '..');
const RES = path.join(ROOT, 'src/main/resources');

// 1x1 透明 PNG。/cards/*.png はサンドボックスに実物が無いため、これで代替する
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

function startServer() {
  const server = http.createServer((req, res) => {
    const url = req.url.split('?')[0];
    if (url.startsWith('/cards/')) {
      res.writeHead(200, { 'Content-Type': 'image/png' });
      res.end(PNG);
      return;
    }
    let file;
    if (url === '/' || url === '/harness.html') file = path.join(__dirname, 'harness.html');
    else if (url.startsWith('/css/')) file = path.join(RES, 'static', url);
    else if (url.startsWith('/js/')) file = path.join(RES, 'static', url);
    else {
      res.writeHead(404);
      res.end('nf');
      return;
    }
    const body = fs.readFileSync(file);
    const type = file.endsWith('.css') ? 'text/css'
      : file.endsWith('.js') ? 'application/javascript' : 'text/html; charset=utf-8';
    res.writeHead(200, { 'Content-Type': type });
    res.end(body);
  });
  return new Promise((resolve) => server.listen(0, () => resolve(server)));
}

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -- ' + detail : ''}`);
}

/** 実マウスでドラッグする。ステップを細かく刻み、着地後に短い待機を挟む。 */
async function realDrag(page, fromSel, toSel, opts = {}) {
  const from = await page.locator(fromSel).first().boundingBox();
  const to = await page.locator(toSel).first().boundingBox();
  if (!from || !to) throw new Error(`要素が見つからない: ${fromSel} / ${toSel}`);
  const sx = from.x + from.width / 2;
  const sy = from.y + from.height / 2;
  const tx = to.x + (opts.tx === undefined ? to.width / 2 : opts.tx);
  const ty = to.y + (opts.ty === undefined ? to.height / 2 : opts.ty);

  await page.mouse.move(sx, sy);
  await page.mouse.down();
  const steps = 30;
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(sx + ((tx - sx) * i) / steps, sy + ((ty - sy) * i) / steps);
    await page.waitForTimeout(4);
  }
  await page.waitForTimeout(120);
  await page.mouse.up();
  await page.waitForTimeout(80);
}

async function render(page, view) {
  await page.evaluate((v) => {
    window.latestView = v;
    // eslint-disable-next-line no-undef
    renderAll(v);
  }, view);
}

async function sent(page) {
  return page.evaluate(() => window.__sent);
}
async function clearSent(page) {
  await page.evaluate(() => { window.__sent = []; });
}

(async () => {
  const server = await startServer();
  const port = server.address().port;
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 950 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto(`http://127.0.0.1:${port}/harness.html`);
  await page.waitForTimeout(200);

  const view = baseView();
  await render(page, view);

  // ---- 0. 描画が例外なく通ること / ターンUIが消えていること ----
  check('描画時にJSエラーが出ない', errors.length === 0, errors.join(' | '));
  check('ターン/フェイズUIが存在しない',
    (await page.locator('#turn-number, #phase-name, #turn-plus, #phase-fwd').count()) === 0);
  check('リーダー行が存在しない', (await page.locator('#seat-self-leader-row').count()) === 0);

  // ---- 1. センターラインが空のとき細いバーであること ----
  const playBox0 = await page.locator('.manual-center-half[data-zone="PLAY"]').boundingBox();
  check('空のセンターラインは約24px', playBox0.height <= 32, `h=${playBox0.height}`);

  // ---- 2. 手札 → センターライン(プレイ中)へ実マウスでドラッグ ----
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card', '.manual-center-half[data-zone="PLAY"]');
  let msgs = await sent(page);
  const playMove = msgs.find((m) => m.destination.endsWith('/move') && m.body.toZone === 'PLAY');
  check('手札→プレイ中のドロップで move が1件送られる',
    msgs.filter((m) => m.destination.endsWith('/move')).length === 1,
    JSON.stringify(msgs));
  check('共有ゾーンへの move は toSeat を持たない(null)',
    !!playMove && playMove.body.toSeat === null, JSON.stringify(playMove && playMove.body));
  check('共有ゾーンへ送られるのはドラッグしたカード',
    !!playMove && playMove.body.cardIds[0] === 'h1');

  // ---- 3. サーバ応答を模して再描画 → 自動展開 ----
  const view2 = baseView();
  view2.seatA.zones.HAND = [card('h2', '手札2')];
  view2.shared.PLAY = [card('h1', '手札1')];
  await render(page, view2);
  const playBox1 = await page.locator('.manual-center-half[data-zone="PLAY"]').boundingBox();
  // ★20c: カード幅の上限を72pxへ下げたため、展開時の高さも130→112pxになった
  check('カードが入るとセンターラインが自動展開する', playBox1.height >= 105,
    `h=${playBox1.height}`);
  // ★空の側は展開状態(manual-center-open)にならない。ただし高さは相方に合わせて伸びる。
  //   センターラインは1本の帯であり、片側だけ24pxで浮くと帯として読めなくなるためである。
  //   縦の収支に効くのは「帯全体の高さ」であり、そこは相方の130pxで決まっていて変わらない。
  check('公開側は展開状態にならない(空のまま)',
    !(await page.locator('.manual-center-half[data-zone="REVEAL"]').getAttribute('class'))
      .includes('manual-center-open'));
  check('展開しても帯全体は約130pxに収まる',
    (await page.locator('#center-line').boundingBox()).height <= 140);

  // ---- 4. センターライン → 手札(戻す)。共有ゾーンからのドラッグが成立すること ----
  await clearSent(page);
  await realDrag(page, '.manual-center-row .manual-hand-card', '#hand-row .hand-row');
  msgs = await sent(page);
  const back = msgs.find((m) => m.destination.endsWith('/move'));
  check('共有ゾーンからのドラッグで手札へ戻せる',
    !!back && back.body.toZone === 'HAND' && back.body.cardIds[0] === 'h1',
    JSON.stringify(back && back.body));

  // ---- 5. 0枚に戻ると自動収縮 ----
  await render(page, baseView());
  check('0枚に戻るとセンターラインが畳まれる',
    (await page.locator('.manual-center-half[data-zone="PLAY"]').boundingBox()).height <= 32);

  // ---- 6. リーダータイルへのドロップ = 装備 ----
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card', '#pile-grid .manual-leader-tile');
  msgs = await sent(page);
  const equip = msgs.find((m) => m.destination.endsWith('/move'));
  check('リーダータイルへ落とすと WEAPON への move になる',
    !!equip && equip.body.toZone === 'WEAPON' && equip.body.toSeat === 'A',
    JSON.stringify(equip && equip.body));
  check('リーダータイルへのドロップは1件だけ送られる',
    msgs.filter((m) => m.destination.endsWith('/move')).length === 1);

  // ---- 7. 装備済みでもドロップを受け付ける(旧・拒否規約の撤回) ----
  const view3 = baseView();
  view3.seatA.zones.WEAPON = [card('w1', '装備中の武器', { type: 'WEAPON', hp: null, printedHp: null })];
  await render(page, view3);
  check('装備中はリーダータイル右下にミニタイルが出る',
    (await page.locator('#pile-grid .manual-weapon-mini').count()) === 1);
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card', '#pile-grid .manual-leader-tile',
    { tx: 20, ty: 20 });
  msgs = await sent(page);
  check('装備済みでもドロップが受け付けられる',
    msgs.some((m) => m.destination.endsWith('/move') && m.body.toZone === 'WEAPON'),
    JSON.stringify(msgs));

  // ---- 8. ウェポンのミニタイル: ダブルクリックで操作モーダル ----
  await page.locator('#pile-grid .manual-weapon-mini').dblclick();
  await page.waitForTimeout(60);
  check('ミニタイルのダブルクリックでウェポン操作モーダルが開く',
    !(await page.locator('#weapon-modal').getAttribute('class')).includes('d-none'));
  await clearSent(page);
  await page.locator('#weapon-modal-actions button').first().click();
  msgs = await sent(page);
  check('ウェポン操作モーダルから使用済トグルを送れる',
    msgs.some((m) => m.destination.endsWith('/used')), JSON.stringify(msgs));
  await page.locator('#weapon-modal-close').click();
  await page.waitForTimeout(60);

  // ---- 9. 右列パイル: 山札ドラッグ(20a の回帰確認) ----
  await render(page, baseView());
  // ★20c: 自分のリーダー枠(.manual-leader-slot)も .manual-pile を共有するため6になる
  check('右列に5つのパイル+リーダー枠がある',
    (await page.locator('#pile-grid .manual-pile').count()) === 6
      && (await page.locator('#pile-grid .manual-leader-slot').count()) === 1);
  check('パイルにカード画像が敷かれている',
    (await page.locator('#pile-grid .manual-pile-face img').count()) >= 3);
  await clearSent(page);
  await realDrag(page, '#pile-grid .manual-pile[data-zone="DECK"] .manual-pile-face',
    '#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face');
  msgs = await sent(page);
  const deckDrag = msgs.find((m) => m.destination.endsWith('/move'));
  check('山札パイルのドラッグで一番上の1枚が動く(20a回帰)',
    !!deckDrag && deckDrag.body.cardIds[0] === 'd1' && deckDrag.body.toZone === 'TRASH',
    JSON.stringify(deckDrag && deckDrag.body));
  check('山札ドラッグの move は1件だけ(stopPropagation の回帰)',
    msgs.filter((m) => m.destination.endsWith('/move')).length === 1, JSON.stringify(msgs));

  // ---- 10. 山札の「上へ」枠を掴んでも山札ドラッグが始まらない(20a A4 の回帰) ----
  await clearSent(page);
  await realDrag(page, '#pile-grid .manual-pile[data-zone="DECK"] .zone-drop-mini',
    '#pile-grid .manual-pile[data-zone="LOST"] .manual-pile-face');
  msgs = await sent(page);
  check('「上へ」枠からは山札ドラッグが始まらない(20a A4回帰)',
    msgs.filter((m) => m.destination.endsWith('/move')).length === 0, JSON.stringify(msgs));

  // ---- 11. 手札 → 「上へ」枠。小さいドロップ対象への着地 ----
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card',
    '#pile-grid .manual-pile[data-zone="DECK"] .zone-drop-mini');
  msgs = await sent(page);
  const toTop = msgs.find((m) => m.destination.endsWith('/move'));
  check('山札の「上へ」枠へ落とすと toIndex 0 の move が1件',
    !!toTop && toTop.body.toZone === 'DECK' && toTop.body.toIndex === 0
      && msgs.filter((m) => m.destination.endsWith('/move')).length === 1,
    JSON.stringify(msgs));

  // ---- 12. シャッフルボタン ----
  await clearSent(page);
  await page.locator('#pile-grid .manual-pile[data-zone="DECK"] button').first().click();
  msgs = await sent(page);
  check('シャッフルボタンで shuffle が送られ、move は送られない',
    msgs.some((m) => m.destination.endsWith('/shuffle'))
      && !msgs.some((m) => m.destination.endsWith('/move')), JSON.stringify(msgs));

  // ---- 13. B席チップ: 公が無く確がある ----
  const chipTexts = await page.locator('#seat-opponent-top .zone-pile-mini').allTextContents();
  check('B席チップは[山][墓][消][禁][確]の5つ',
    chipTexts.length === 5 && chipTexts.some((t) => t.startsWith('確'))
      && !chipTexts.some((t) => t.startsWith('公')), JSON.stringify(chipTexts));
  check('B席にもリーダー+ウェポン合体タイルがある',
    (await page.locator('#seat-opponent-top .manual-leader-tile').count()) === 1);

  // ---- 14. ログの折りたたみ ----
  // ★20c: ログは拡大画像の右横にあり、既定で拡大画像と高さが揃っている
  const zoomBox = await page.locator('#zoom-panel').boundingBox();
  const logCollapsed = await page.locator('#log-box').boundingBox();
  check('ログが拡大画像の右横にあり、十分な幅を持つ',
    logCollapsed.x > zoomBox.x + zoomBox.width - 4
      && Math.abs(logCollapsed.y - zoomBox.y) < 60
      && logCollapsed.width >= 150,
    `zoom=${JSON.stringify(zoomBox)} log=${JSON.stringify(logCollapsed)}`);
  await page.locator('#log-box').click();
  await page.waitForTimeout(60);
  const logOpen = await page.locator('#log-box').boundingBox();
  await page.locator('#log-box').click();
  await page.waitForTimeout(60);
  const logAgain = await page.locator('#log-box').boundingBox();
  check('ログは既定で拡大画像と同程度、クリックで拡張し再クリックで畳む',
    logCollapsed.height <= 250 && logOpen.height >= 440 && logAgain.height <= 250,
    `${logCollapsed.height} -> ${logOpen.height} -> ${logAgain.height}`);

  // ---- 14-2. 横幅を使い切っていること(★20c) ----
  const rootWidth = await page.evaluate(() =>
    document.getElementById('manual-root').getBoundingClientRect().width);
  check('盤面がウィンドウ幅をほぼ使い切る(左右の余白が無い)',
    rootWidth >= 1280 - 24, `w=${rootWidth}`);
  const minionTile = await page.locator('#seat-self-minions .tile-slot-empty').first().boundingBox();
  check('ミニオン枠が幅に応じて広がる(110pxより大きい)',
    minionTile.width > 110, `w=${minionTile.width}`);

  // ---- 15. 縦の収まり ----
  const rootBox = await page.locator('#manual-root').boundingBox();
  check('盤面全体の高さが900px級に収まる', rootBox.height <= 900, `h=${rootBox.height}`);
  const handBottom = await page.evaluate(() => {
    const r = document.getElementById('hand-row').getBoundingClientRect();
    return r.bottom;
  });
  check('手札の下端が画面内(950px)に収まる', handBottom <= 950, `bottom=${handBottom}`);

  // ---- 16. LPモーダル(20a回帰) ----
  await clearSent(page);
  await page.locator('#pile-grid .manual-leader-tile .manual-tile-stats').click();
  await page.waitForTimeout(60);
  check('LP表示のクリックでLPモーダルが開く(20a回帰)',
    !(await page.locator('#lp-modal').getAttribute('class')).includes('d-none'));
  await page.locator('#lp-modal-minus5').click();
  msgs = await sent(page);
  check('LPモーダルの -5 で delta が送られ、モーダルは閉じない',
    msgs.some((m) => m.destination.endsWith('/lp') && m.body.delta === -5)
      && !(await page.locator('#lp-modal').getAttribute('class')).includes('d-none'),
    JSON.stringify(msgs));
  await page.locator('#lp-modal-close').click();

  check('全工程を通じてJSエラーが出ない', errors.length === 0, errors.join(' | '));

  await browser.close();
  server.close();

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exit(failed.length === 0 ? 0 : 1);
})();
