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
const {
  baseView, versusView, roomSummary, card, occupant, syncCounts, startState, declaration,
} = require('./fixture');

const ROOT = path.resolve(__dirname, '..');

/**
 * ★部屋一覧 API の応答(RoomSummary の配列)。
 * 鍵つき部屋は roomId が null で返る(1-3・F1)。これが崩れると「一覧を見ただけで
 * 鍵つき部屋に入れる」ことになるため、検証で固定しておく。
 */
const ROOM_LIST = [
  { roomId: 'AAA111', roomName: 'あいてる部屋', type: 'OPEN', spectatorAllowed: true,
    locked: false, seatAName: null, seatBName: null, spectatorCount: 0 },
  { roomId: null, roomName: 'かぎつき対戦', type: 'VERSUS', spectatorAllowed: false,
    locked: true, seatAName: 'あかり', seatBName: null, spectatorCount: 0 },
  { roomId: 'CCC333', roomName: 'たいせん', type: 'VERSUS', spectatorAllowed: true,
    locked: false, seatAName: 'あかり', seatBName: 'ばんり', spectatorCount: 2 },
];
const RES = path.join(ROOT, 'src/main/resources');

/**
 * ★Batch 29: 山札の中身を返す口(`/manual/api/rooms/{id}/zone`)の応答。
 * 検証中に差し替えて、正常・遅延・失敗を作り分ける。
 */
const ZONE_RESPONSE = { status: 200, body: { seat: 'A', zone: 'DECK', cards: [] }, delayMs: 0 };

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
    // ★29: 山札の中身は配信から外れ、この口から取る。
    //   応答は ZONE_RESPONSE で差し替えられる(遅延・失敗も再現する)
    if (url === `/manual/api/rooms/TESTRM/zone`) {
      const answer = () => {
        if (ZONE_RESPONSE.status !== 200) {
          res.writeHead(ZONE_RESPONSE.status, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ message: ZONE_RESPONSE.message || 'エラー' }));
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify(ZONE_RESPONSE.body));
      };
      if (ZONE_RESPONSE.delayMs) setTimeout(answer, ZONE_RESPONSE.delayMs);
      else answer();
      return;
    }
    // ★25: カード定義。本物と同じ経路で返す(空でよい。テキスト表示の検証は
    //   セクション26が applyCardLibrary で明示的にフィクスチャを入れて行う)
    if (url === '/manual/api/card-library') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ cards: [] }));
      return;
    }
    // ★21b: ロビーは実際に一覧APIを叩く。fetch をスタブせず本物の経路で確かめる
    if (url === '/manual/api/rooms') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify(ROOM_LIST));
      return;
    }
    let file;
    if (url === '/harness-lobby.html') file = path.join(__dirname, 'harness-lobby.html');
    else if (url === '/' || url === '/harness.html') file = path.join(__dirname, 'harness.html');
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
    // ★スクリプト直下の let は window のプロパティにならない。
    //   window.latestView に入れるだけだと manual-battle.js 内部の latestView は null のままで、
    //   ヘッダのボタン(席に着く等)が「ビューが無い」と判断して何もしない。
    //   グローバルの字句環境にある束縛へ直接代入する。
    // eslint-disable-next-line no-undef
    latestView = v;
    // eslint-disable-next-line no-undef
    renderAll(v);
  }, view);
}

async function sent(page) {
  return page.evaluate(() => window.__sent);
}

/**
 * ★Batch 21c: ドラッグ中は矢印(dragcue)が同じ経路で何通も飛ぶ(7-2)。
 * 「送られたのは move 1件だけ」を確かめる検証は、揮発メッセージを除いてから数える。
 * 除かずに件数で比較すると、矢印を足しただけで既存の検証が落ちる。
 */
function boardMessages(msgs) {
  return msgs.filter((m) => !m.destination.endsWith('/dragcue'));
}
function cueMessages(msgs) {
  return msgs.filter((m) => m.destination.endsWith('/dragcue'));
}
async function clearSent(page) {
  await page.evaluate(() => { window.__sent = []; });
}

/**
 * ★Batch 22 1章: 拡大パネルの中身を読む。新しいクリック規約の主役は「拡大」であり、
 * 「tap が送られない」だけでは<b>何も起きなかった</b>ときと区別が付かない。
 * 何が拡大されたかまで確かめる。
 */
async function zoomedImage(page) {
  return page.evaluate(() => {
    // ★Batch 25: 画像をやめてカードフェイスになった。同一性は dataset.imageId で確かめる。
    //   戻り値の形('/cards/<id>.png')は既存の期待値と互換のまま維持する。
    const face = document.querySelector('#zoom-panel .mcard');
    return face && face.dataset.imageId ? '/cards/' + face.dataset.imageId + '.png' : null;
  });
}
async function clearZoom(page) {
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    pinnedZoom = null;
    document.getElementById('zoom-panel').innerHTML = '';
  });
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
  // ★Batch 30: 上限を 140 → 160 に引き上げた。マスター指示でセンターラインの札を
  //   大きくしたためである(見出しを左へ寄せ、上限を 90px → 106px へ)。
  //   ★上限そのものは残す。ここを外すと「盤面全体が950pxに収まる」が
  //   手札を潰すことで達成されてしまい、崩れたことに気づけなくなる。
  check('展開しても帯全体は約150pxに収まる(30で拡大)',
    (await page.locator('#center-line').boundingBox()).height <= 160,
    `h=${(await page.locator('#center-line').boundingBox()).height}`);

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

  // ---- 6. ウェポン枠へのドロップ = 装備(★Batch 22 3章で書き換え) ----
  //   20c までは「リーダータイルへ落とす=装備」だった。自席ではウェポン枠だけが
  //   ドロップ先になる(W2)。相手のリーダータイルへ落とす経路は残る(下の 6-3)
  check('★未装備のウェポン枠が破線の空枠として出る(3-3)',
    (await page.locator('#pile-grid .manual-weapon-slot').count()) === 1
      && (await page.locator('#pile-grid .manual-weapon-slot .manual-weapon-slot-empty')
        .count()) === 1);
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card', '#pile-grid .manual-weapon-slot');
  msgs = boardMessages(await sent(page));
  const equip = msgs.find((m) => m.destination.endsWith('/move'));
  check('★ウェポン枠へ落とすと WEAPON への move になる(3-2)',
    !!equip && equip.body.toZone === 'WEAPON' && equip.body.toSeat === 'A',
    JSON.stringify(equip && equip.body));
  check('ウェポン枠へのドロップは1件だけ送られる',
    msgs.filter((m) => m.destination.endsWith('/move')).length === 1, JSON.stringify(msgs));

  // ---- 6-2. ★自席のリーダータイルは WEAPON を受けない(W2) ----
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card', '#pile-grid .manual-leader-tile');
  msgs = boardMessages(await sent(page));
  check('★自席のリーダータイルへ落としても WEAPON の move は送られない(W2)',
    !msgs.some((m) => m.destination.endsWith('/move') && m.body.toZone === 'WEAPON'),
    JSON.stringify(msgs));

  // ---- 6-3. ★相手のリーダータイルは合体タイルのまま受ける(W3) ----
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card', '#seat-opponent-top .manual-leader-tile');
  msgs = boardMessages(await sent(page));
  const oppEquip = msgs.find((m) => m.destination.endsWith('/move'));
  check('★相手のリーダータイルへ落とすと相手席の WEAPON への move になる(W3)',
    !!oppEquip && oppEquip.body.toZone === 'WEAPON' && oppEquip.body.toSeat === 'B',
    JSON.stringify(oppEquip && oppEquip.body));

  // ---- 7. 装備中の見え方(★Batch 22 3-3 で書き換え) ----
  const view3 = baseView();
  view3.seatA.zones.WEAPON = [card('w1', '装備中の武器', { type: 'WEAPON', hp: null, printedHp: null })];
  view3.seatB.zones.WEAPON = [card('w2', '相手の武器', { type: 'WEAPON', hp: null, printedHp: null })];
  syncCounts(view3.seatA);
  syncCounts(view3.seatB);
  await render(page, view3);
  check('★自席のリーダータイルにミニタイルは出ない(3-4)',
    (await page.locator('#pile-grid .manual-weapon-mini').count()) === 0);
  check('★相手の合体タイルにはミニタイルが出る(W3・現状維持)',
    (await page.locator('#seat-opponent-top .manual-weapon-mini').count()) === 1);
  check('★装備中のウェポン枠に画像・ATKチップ・使用済トグルがその場に出る(3-3)',
    (await page.locator('#pile-grid .manual-weapon-slot .manual-pile-face .mcard').count()) === 1
      && (await page.locator('#pile-grid .manual-weapon-slot .manual-stat-button').count()) === 1
      && (await page.locator('#pile-grid .manual-weapon-slot .manual-weapon-slot-used')
        .count()) === 1);
  await clearSent(page);
  await page.locator('#pile-grid .manual-weapon-slot .manual-weapon-slot-used').click();
  msgs = boardMessages(await sent(page));
  check('ウェポン枠の使用済トグルをその場で押せる(3-3)',
    msgs.some((m) => m.destination.endsWith('/used')), JSON.stringify(msgs));
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card', '#pile-grid .manual-weapon-slot',
    { tx: 20, ty: 20 });
  msgs = boardMessages(await sent(page));
  check('装備済みでもウェポン枠がドロップを受け付ける',
    msgs.some((m) => m.destination.endsWith('/move') && m.body.toZone === 'WEAPON'),
    JSON.stringify(msgs));

  // ★3-3: 装備中のカードはウェポン枠から掴んで外へ出せる
  await render(page, view3);
  await clearSent(page);
  await realDrag(page, '#pile-grid .manual-weapon-slot .manual-pile-face',
    '#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face');
  msgs = boardMessages(await sent(page));
  const unequip = msgs.find((m) => m.destination.endsWith('/move'));
  check('★ウェポン枠が装備中カードのドラッグ起点になる(3-3)',
    !!unequip && unequip.body.cardIds[0] === 'w1' && unequip.body.toZone === 'TRASH',
    JSON.stringify(unequip && unequip.body));

  // ★3-5: 自席の WEAPON のアンカーがウェポン枠へ移っていること。
  //   移し忘れると矢印のウェポン宛の端点が相手上段の合体タイルを指したままになる(21c 7-1)
  await render(page, view3);
  const weaponAnchors = await page.evaluate(() => {
    /* eslint-disable no-undef */
    const name = (el) => (el ? el.className : null);
    return {
      self: name(anchorElement({ seatId: 'A', zone: 'WEAPON' })),
      opp: name(anchorElement({ seatId: 'B', zone: 'WEAPON' })),
      leader: name(anchorElement({ seatId: 'A', zone: null })),
    };
    /* eslint-enable no-undef */
  });
  check('★自席の WEAPON のアンカーがウェポン枠になる(3-5)',
    !!weaponAnchors.self && weaponAnchors.self.includes('manual-weapon-slot'),
    JSON.stringify(weaponAnchors));
  check('★相手の WEAPON のアンカーは合体タイルのままである(3-5)',
    !!weaponAnchors.opp && weaponAnchors.opp.includes('manual-leader-tile'),
    JSON.stringify(weaponAnchors));
  check('★自席のリーダー(zone null)のアンカーは残っている(3-5)',
    !!weaponAnchors.leader && weaponAnchors.leader.includes('manual-leader-tile'),
    JSON.stringify(weaponAnchors));

  // ---- 8. ウェポン枠の右クリックで操作モーダル(★22 1-2 で入替) ----
  await page.locator('#pile-grid .manual-weapon-slot .manual-pile-face').click({ button: 'right' });
  await page.waitForTimeout(60);
  check('★ウェポン枠の右クリックでウェポン操作モーダルが開く(1-2)',
    !(await page.locator('#weapon-modal').getAttribute('class')).includes('d-none'));
  await clearSent(page);
  await page.locator('#weapon-modal-actions button').first().click();
  msgs = await sent(page);
  check('ウェポン操作モーダルから使用済トグルを送れる',
    msgs.some((m) => m.destination.endsWith('/used')), JSON.stringify(msgs));
  await page.locator('#weapon-modal-close').click();
  await page.waitForTimeout(60);
  await clearZoom(page);
  await page.locator('#pile-grid .manual-weapon-slot .manual-pile-face').click();
  await page.waitForTimeout(60);
  check('★ウェポン枠の左クリックは拡大である(1-2)',
    (await zoomedImage(page)) === '/cards/img-w1.png', await zoomedImage(page));

  // ---- 9. 右列パイル: 山札ドラッグ(20a の回帰確認) ----
  await render(page, baseView());
  // ★22 3章: ウェポン枠が増えたので 6 → 7 になる
  //   (5つのパイル + リーダー枠 + ウェポン枠。いずれも .manual-pile を共有する)
  check('右列に5つのパイル+リーダー枠+ウェポン枠がある(3章)',
    (await page.locator('#pile-grid .manual-pile').count()) === 7
      && (await page.locator('#pile-grid .manual-leader-slot').count()) === 1
      && (await page.locator('#pile-grid .manual-weapon-slot').count()) === 1);

  // ---- 9-2. パイルの配置(★20d) ----
  //   [リーダー][禁忌][山札][確認] / 禁忌の下に消滅、山札の下に墓地
  const boxOf = async (sel) => page.locator(sel).first().boundingBox();
  const bLeader = await boxOf('#pile-grid .manual-leader-slot');
  const bTaboo = await boxOf('#pile-grid .manual-pile[data-zone="TABOO"]');
  const bDeck = await boxOf('#pile-grid .manual-pile[data-zone="DECK"]');
  const bPrivate = await boxOf('#pile-grid .manual-pile[data-zone="PRIVATE"]');
  const bLost = await boxOf('#pile-grid .manual-pile[data-zone="LOST"]');
  const bTrash = await boxOf('#pile-grid .manual-pile[data-zone="TRASH"]');
  check('上段が左から [リーダー][禁忌][山札][確認] の順に並ぶ',
    bLeader.x < bTaboo.x && bTaboo.x < bDeck.x && bDeck.x < bPrivate.x
      && Math.abs(bLeader.y - bTaboo.y) < 4 && Math.abs(bTaboo.y - bDeck.y) < 4
      && Math.abs(bDeck.y - bPrivate.y) < 4,
    `leader=${bLeader.x} taboo=${bTaboo.x} deck=${bDeck.x} private=${bPrivate.x}`);
  check('消滅が禁忌の真下、墓地が山札の真下にある',
    Math.abs(bLost.x - bTaboo.x) < 2 && Math.abs(bTrash.x - bDeck.x) < 2
      && bLost.y > bTaboo.y && bTrash.y > bDeck.y,
    `lost=(${bLost.x},${bLost.y}) taboo=(${bTaboo.x},${bTaboo.y}) `
      + `trash=(${bTrash.x},${bTrash.y}) deck=(${bDeck.x},${bDeck.y})`);
  check('消滅と墓地の上端が揃っている', Math.abs(bLost.y - bTrash.y) < 2,
    `lost.y=${bLost.y} trash.y=${bTrash.y}`);
  check('パイル群が右列(400px)に収まる',
    bPrivate.x + bPrivate.width - bLeader.x <= 400,
    `幅=${bPrivate.x + bPrivate.width - bLeader.x}`);

  // ---- 9-3. リーダーの文明色(★20d) ----
  // ★25b: 塗りは inline background から .mcard-frame + --mc(フェイスと同一パレット)へ移った
  const leaderBg = await page.evaluate(() => {
    const el = document.querySelector('#pile-grid .manual-leader-tile');
    return { frame: el.classList.contains('mcard-frame'), mc: el.style.getPropertyValue('--mc') };
  });
  check('自分のリーダーが文明の色の枠になる(25b: フェイスと同一パレット)',
    leaderBg.frame && leaderBg.mc.toLowerCase() === '#2f6fb5',
    JSON.stringify(leaderBg));
  const oppLeaderBg = await page.evaluate(() => {
    const el = document.querySelector('#seat-opponent-top .manual-leader-tile');
    return el.classList.contains('mcard-frame') && el.style.getPropertyValue('--mc') !== '';
  });
  check('相手のリーダーも文明の色の枠になる(25b)', oppLeaderBg === true, String(oppLeaderBg));
  check('パイルにカードフェイスが敷かれている(25)',
    (await page.locator('#pile-grid .manual-pile-face .mcard').count()) >= 3);
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

  // ---- 13. 相手上段の再構成(★Batch 21c。設計書4章) ----
  const chipTexts = await page.locator('#seat-opponent-top .zone-pile-mini').allTextContents();
  check('相手のチップは[山][禁][確][手]の4つ(4章)',
    chipTexts.length === 4 && chipTexts[0].startsWith('山') && chipTexts[1].startsWith('禁')
      && chipTexts[2].startsWith('確') && chipTexts[3].startsWith('手')
      && !chipTexts.some((t) => t.startsWith('公')), JSON.stringify(chipTexts));
  check('墓地・消滅はチップから外れ簡略画像になる(4章)',
    (await page.locator('#seat-opponent-top .manual-opp-pile').count()) === 2
      && !chipTexts.some((t) => t.startsWith('墓') || t.startsWith('消')),
    JSON.stringify(chipTexts));
  check('B席にもリーダー+ウェポン合体タイルがある',
    (await page.locator('#seat-opponent-top .manual-leader-tile').count()) === 1);
  check('相手のマナ簡略表示に枚数とMPが出る(4章・3-3)',
    (await page.locator('#seat-opponent-top .manual-opp-mana-label').textContent()).includes('MP'),
    await page.locator('#seat-opponent-top .manual-opp-mana-label').textContent());

  // ★並び順: [マナ][墓地][消滅] … [山禁確手] [リーダー]
  const oppMana = await boxOf('#seat-opponent-top .manual-opp-mana');
  const oppTrash = await boxOf('#seat-opponent-top .manual-opp-pile[data-zone="TRASH"]');
  const oppLost = await boxOf('#seat-opponent-top .manual-opp-pile[data-zone="LOST"]');
  const oppChips = await boxOf('#seat-opponent-top .manual-opp-chips');
  const oppLeader = await boxOf('#seat-opponent-top .manual-leader-tile');
  check('相手上段が [マナ][墓地][消滅] … [チップ列] [リーダー] の順に並ぶ(4章)',
    oppMana.x < oppTrash.x && oppTrash.x < oppLost.x && oppLost.x < oppChips.x
      && oppChips.x < oppLeader.x,
    `mana=${oppMana.x} trash=${oppTrash.x} lost=${oppLost.x} chips=${oppChips.x} leader=${oppLeader.x}`);
  check('★チップ列が右へ寄っている(リーダーの左に接している。4章)',
    oppLeader.x - (oppChips.x + oppChips.width) < 24,
    `chips右端=${oppChips.x + oppChips.width} leader=${oppLeader.x}`);
  const oppTopBox = await boxOf('#seat-opponent-top');
  check('★相手上段が現行の高さ(148px)内に収まる(4章)',
    oppTopBox.height <= 148, `h=${oppTopBox.height}`);

  // ★簡略画像はドロップ先としても機能する(4章)。実マウスで確かめる
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card',
    '#seat-opponent-top .manual-opp-pile[data-zone="TRASH"] .manual-opp-face');
  msgs = boardMessages(await sent(page));
  check('相手の墓地(簡略画像)へ落とすと相手席への move になる(4章)',
    msgs.length === 1 && msgs[0].body.toZone === 'TRASH' && msgs[0].body.toSeat === 'B',
    JSON.stringify(msgs));
  await clearSent(page);
  await realDrag(page, '.hand-row .manual-hand-card',
    '#seat-opponent-top .zone-pile-mini[data-zone="HAND"]');
  msgs = boardMessages(await sent(page));
  check('相手の手札チップへ落とすと相手席の HAND への move になる',
    msgs.length === 1 && msgs[0].body.toZone === 'HAND' && msgs[0].body.toSeat === 'B',
    JSON.stringify(msgs));

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
  // ★25c: 手札が残りの縦を使い切る設計になったため、上限は「900px級」ではなく
  //   「ビューポート(950px)からはみ出さない」が本来の制約である
  check('盤面全体がビューポート(950px)に収まる', rootBox.height <= 950, `h=${rootBox.height}`);
  const handBottom = await page.evaluate(() => {
    const r = document.getElementById('hand-row').getBoundingClientRect();
    return r.bottom;
  });
  check('手札の下端が画面内(950px)に収まる', handBottom <= 950, `bottom=${handBottom}`);

  // ---- 16. LPモーダル(20a回帰。★22 4章でボタン化しても左クリックのままである) ----
  await clearSent(page);
  await page.locator('#pile-grid .manual-leader-tile .manual-tile-stats').click();
  await page.waitForTimeout(60);
  check('LP表示のクリックでLPモーダルが開く(20a回帰)',
    !(await page.locator('#lp-modal').getAttribute('class')).includes('d-none'));
  // ★Batch 30: ±5 は廃止した(マスター指示)。±1 はモーダルにも残っている
  await page.locator('#lp-modal-minus1').click();
  msgs = await sent(page);
  check('LPモーダルの -1 で delta が送られ、モーダルは閉じない',
    msgs.some((m) => m.destination.endsWith('/lp') && m.body.delta === -1)
      && !(await page.locator('#lp-modal').getAttribute('class')).includes('d-none'),
    JSON.stringify(msgs));
  check('★LPモーダルの ±5 は廃止されている(30)',
    (await page.locator('#lp-modal-minus5').count()) === 0
      && (await page.locator('#lp-modal-plus5').count()) === 0);
  await page.locator('#lp-modal-close').click();

  // =====================================================================
  // ★Batch 22: クリック規約の入れ替え・マナ表示の統一・数値のボタン化
  // =====================================================================

  await render(page, baseView());

  // ---- 22-1. クリック規約(設計書1章)----
  //   ★「tap が送られない」だけでは「何も起きなかった」と区別が付かない。
  //     拡大パネルに何が出たかまで確かめる
  await clearSent(page);
  await clearZoom(page);
  // ★タイルの中央は数値チップである(1-6 の専用ボタン)。カード本体を押すために名前を狙う
  const minionBody = '#seat-self-minions .manual-tile .manual-tile-name';
  await page.locator(minionBody).first().click();
  await page.waitForTimeout(60);
  msgs = boardMessages(await sent(page));
  check('★場のカードの左クリックで拡大され、tap が送られない(1-1)',
    (await zoomedImage(page)) === '/cards/img-f1.png'
      && !msgs.some((m) => m.destination.endsWith('/tap')),
    `zoom=${await zoomedImage(page)} msgs=${JSON.stringify(msgs)}`);

  await clearSent(page);
  await page.locator(minionBody).first().click({ button: 'right' });
  await page.waitForTimeout(60);
  msgs = boardMessages(await sent(page));
  check('★場のカードの右クリックで tap が送られる(1-1)',
    msgs.length === 1 && msgs[0].destination.endsWith('/tap')
      && msgs[0].body.cardIds[0] === 'f1',
    JSON.stringify(msgs));

  await clearSent(page);
  await page.locator(minionBody).first().click({ modifiers: ['Shift'] });
  msgs = boardMessages(await sent(page));
  check('Shift+左クリックは表裏のままである(1-4)',
    msgs.length === 1 && msgs[0].destination.endsWith('/flip'), JSON.stringify(msgs));

  await clearSent(page);
  await page.locator(minionBody).first().click({ modifiers: ['Control'] });
  await page.waitForTimeout(60);
  msgs = boardMessages(await sent(page));
  check('Ctrl+左クリックは複数選択のままである(1-4)',
    msgs.length === 0
      && (await page.locator('#seat-self-minions .manual-tile-selected').count()) === 1,
    JSON.stringify(msgs));
  await page.locator(minionBody).first().click({ modifiers: ['Control'] }); // 選択を戻す
  await page.waitForTimeout(60);

  // マナタイルも同じ規約に従う
  const manaView = baseView();
  manaView.seatA.zones.MANA = [
    card('m1', '表マナ1'), card('m2', '表マナ2', { tapped: true }),
    card('m3', '裏マナ1', { faceDown: true }),
  ];
  manaView.seatA.mp = 2;
  syncCounts(manaView.seatA);
  await render(page, manaView);
  await clearSent(page);
  await clearZoom(page);
  await page.locator('.mana-strip-up .mana-tile').first().click();
  await page.waitForTimeout(60);
  msgs = boardMessages(await sent(page));
  check('★マナの左クリックは拡大で tap を送らない(1-2)',
    (await zoomedImage(page)) === '/cards/img-m1.png'
      && !msgs.some((m) => m.destination.endsWith('/tap')),
    `zoom=${await zoomedImage(page)} msgs=${JSON.stringify(msgs)}`);
  await clearSent(page);
  await page.locator('.mana-strip-up .mana-tile').first().click({ button: 'right' });
  msgs = boardMessages(await sent(page));
  check('★マナの右クリックで tap が送られる(1-2)',
    msgs.length === 1 && msgs[0].destination.endsWith('/tap'), JSON.stringify(msgs));

  // ★1-5「押しても何も起きない」を作らない: 手札の右クリックは拡大を返す
  await clearSent(page);
  await clearZoom(page);
  await page.locator('.hand-row .manual-hand-card').first().click({ button: 'right' });
  await page.waitForTimeout(60);
  msgs = boardMessages(await sent(page));
  check('★手札の右クリックは無反応にせず拡大を返す(1-5)',
    (await zoomedImage(page)) === '/cards/img-h1.png' && msgs.length === 0,
    `zoom=${await zoomedImage(page)} msgs=${JSON.stringify(msgs)}`);

  // ★1-3 山札だけが例外: 左=1枚ドロー
  await clearSent(page);
  await page.locator('#pile-grid .manual-pile[data-zone="DECK"] .manual-pile-face').click();
  msgs = boardMessages(await sent(page));
  check('★山札の左クリックは規約の例外で draw を送る(1-3)',
    msgs.length === 1 && msgs[0].destination.endsWith('/draw')
      && msgs[0].body.count === 1, JSON.stringify(msgs));

  // ★右列パイル: 左=最上段を拡大 / 右=帯
  await clearSent(page);
  await clearZoom(page);
  await page.locator('#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face').click();
  await page.waitForTimeout(60);
  check('★右列パイルの左クリックは一番上のカードを拡大する(1-2)',
    (await zoomedImage(page)) === '/cards/img-t1.png'
      && (await page.locator('#manual-overlay-root .manual-band').count()) === 0,
    `zoom=${await zoomedImage(page)}`);
  await page.locator('#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face')
    .click({ button: 'right' });
  await page.waitForTimeout(60);
  check('★右列パイルの右クリックで帯(一覧)が開く(1-2)',
    (await page.locator('#manual-overlay-root .manual-band-card').count()) === 1);
  await page.evaluate(() => closeOverlay());

  // ★相手の墓地(マスター要望の本体。G5)
  await clearZoom(page);
  await page.locator('#seat-opponent-top .manual-opp-pile[data-zone="TRASH"]').click();
  await page.waitForTimeout(60);
  check('相手の墓地は0枚なら左クリックで拡大せず「空」と返す',
    (await zoomedImage(page)) === null);
  const oppTrashView = baseView();
  oppTrashView.seatB.zones.TRASH = [card('bt1', 'B墓地1'), card('bt2', 'B墓地2')];
  syncCounts(oppTrashView.seatB);
  await render(page, oppTrashView);
  await clearZoom(page);
  await page.locator('#seat-opponent-top .manual-opp-pile[data-zone="TRASH"]').click();
  await page.waitForTimeout(60);
  check('★相手の墓地の左クリックで一番上のカードが拡大される(G5)',
    (await zoomedImage(page)) === '/cards/img-bt2.png', await zoomedImage(page));
  await page.locator('#seat-opponent-top .manual-opp-pile[data-zone="TRASH"]')
    .click({ button: 'right' });
  await page.waitForTimeout(60);
  check('★相手の墓地の右クリックで帯が開く(G5)',
    (await page.locator('#manual-overlay-root .manual-band-card').count()) === 2);
  await page.evaluate(() => closeOverlay());

  // ★対戦部屋の非公開チップは左右どちらでも「非公開」(21c 3-5 の回帰)
  await render(page, versusView('A'));
  await clearZoom(page);
  await page.locator('#seat-opponent-top .zone-pile-mini[data-zone="HAND"]').click();
  await page.waitForTimeout(60);
  check('★非公開チップの左クリックは拡大せずトーストを返す(7-3・21c 3-5)',
    (await zoomedImage(page)) === null
      && (await page.locator('#manual-toast:not(.d-none)').count()) === 1
      && (await page.locator('#manual-overlay-root .manual-band').count()) === 0);
  await page.locator('#seat-opponent-top .zone-pile-mini[data-zone="HAND"]')
    .click({ button: 'right' });
  await page.waitForTimeout(60);
  check('★非公開チップの右クリックでも帯を開かずトーストを返す(21c 3-5)',
    (await page.locator('#manual-toast:not(.d-none)').count()) === 1
      && (await page.locator('#manual-overlay-root .manual-band').count()) === 0);

  // ---- 22-2. マナ表示の統一(設計書2章)----
  await render(page, manaView);
  check('★自席の裏向きマナが裏面カード画像になる(2-2/25b)',
    (await page.locator('.mana-strip-down .mana-tile.mana-tile-back .mcard-backface img').count()) === 1
      && (await page.evaluate(() => {
        const img = document.querySelector('.mana-strip-down .mana-tile-back .mcard-backface img');
        return img ? new URL(img.src).pathname : null;
      })) === '/cards/back.png');
  check('自席の表向きマナは文明色タイル+カード名のままである(2-2)',
    (await page.locator('.mana-strip-up .mana-tile .mana-tile-name').first().textContent())
      === '表マナ1'
      && (await page.locator('.mana-strip-up .mana-tile.mana-tile-back').count()) === 0);
  await clearZoom(page);
  await page.locator('.mana-strip-down .mana-tile').first().click();
  await page.waitForTimeout(60);
  check('★裏向きマナも左クリック1回で中身(表面画像)を確認できる(2-4)',
    (await zoomedImage(page)) === '/cards/img-m3.png', await zoomedImage(page));

  // ★自席のマナ行見出し(2-8)
  check('★自席のマナ行見出しに 合計・表・裏・MP が出る(2-8)',
    (await page.locator('#mana-row-head').textContent()) === 'マナ 3枚(表 2 / 裏 1) MP 2',
    await page.locator('#mana-row-head').textContent());
  const stripLabels = await page.locator('.mana-strip-label').allTextContents();
  check('ストリップのラベルは1行の「表 n枚」「裏 n枚」である(2-8)',
    stripLabels.length === 2 && stripLabels[0] === '表 2枚' && stripLabels[1] === '裏 1枚',
    JSON.stringify(stripLabels));

  // ---- ★★Batch 34 hotfix: マナが枠を越えて右列(ウェポン枠)へ侵食しない ----
  // ★測るのは「重なりの計算式」ではなく<b>結果の座標</b>である。
  //   式を測ると、式を変えたときに検証も一緒に変わってしまい、番人にならない。
  //   見るべき決めごとは1つ:「どのタイルもトラックの外へ出ない」。
  // ★最悪ケースを作る: 上限の15枚が<b>全部裏・全部タップ済</b>。
  //   タップ済は回転して外接88pxになるので、1枚あたりの占有がいちばん大きい。
  //   直す前はこの条件で 819px の中身を 323px のトラックに入れようとして、
  //   約480px を右列(ウェポン枠)へはみ出させていた。
  const manaWorst = baseView();
  manaWorst.seatA.zones.MANA = [];
  for (let i = 0; i < 15; i += 1) {
    manaWorst.seatA.zones.MANA.push(
      card(`mw${i}`, `裏マナ${i}`, { faceDown: true, tapped: true }));
  }
  manaWorst.seatA.mp = 0;
  syncCounts(manaWorst.seatA);
  await render(page, manaWorst);
  await page.waitForTimeout(120);
  const manaFit = await page.evaluate(() => {
    const out = { over: [], scroll: [], weaponLeft: null, worstRight: 0 };
    for (const track of document.querySelectorAll('.mana-strip-track')) {
      // ★1px の丸め誤差は許す。侵食は数十〜数百pxの単位で起きる
      if (track.scrollWidth > track.clientWidth + 1) {
        out.scroll.push({ w: track.clientWidth, sw: track.scrollWidth });
      }
      const edge = track.getBoundingClientRect().right;
      for (const tile of track.children) {
        const r = tile.getBoundingClientRect().right;
        out.worstRight = Math.max(out.worstRight, r);
        if (r > edge + 1) out.over.push(Math.round(r - edge));
      }
    }
    const weapon = document.querySelector('#pile-grid .manual-weapon-slot');
    out.weaponLeft = weapon ? weapon.getBoundingClientRect().left : null;
    return out;
  });
  check('★★マナ15枚が全部タップ済でも枠からはみ出さない(34 hotfix)',
    manaFit.over.length === 0 && manaFit.scroll.length === 0, JSON.stringify(manaFit));
  check('★★マナがウェポン枠へ侵食しない(34 hotfix)',
    manaFit.weaponLeft !== null && manaFit.worstRight <= manaFit.weaponLeft,
    JSON.stringify({ worstRight: Math.round(manaFit.worstRight),
      weaponLeft: Math.round(manaFit.weaponLeft) }));
  // ★空のストリップも残す。マナは「表向きに置く / 裏向きに置く」のドロップ先でもあり、
  //   幅0にすると操作そのものが画面から消える(下駄 MANA_STRIP_BASE_GROW)
  check('★枚数0のストリップも幅を残す(ドロップ先を消さない)(34 hotfix)',
    (await page.evaluate(
      () => document.querySelector('.mana-strip-up .mana-strip-track').clientWidth)) >= 64);
  await render(page, manaView);

  // ★公開のみ視点の観戦者は「自席側」の裏向きマナのカードが届かない。
  //   それでも合計と裏の枚数は counts / manaFaceDownCount から出る(2-8)
  const specMana = versusView(null);
  specMana.seatA.zones.MANA = [card('sm1', '見える表マナ')];
  specMana.seatA.counts.MANA = 4;
  specMana.seatA.manaFaceDownCount = 3;
  specMana.seatA.mp = 2;
  await render(page, specMana);
  check('★公開のみ観戦でも合計と裏の枚数が counts から出る(2-8・21b 1-4)',
    (await page.locator('#mana-row-head').textContent()) === 'マナ 4枚(表 1 / 裏 3) MP 2',
    await page.locator('#mana-row-head').textContent());

  // ---- 22-3. 相手のマナ(2-2・2-3・2-7)----
  const oppManaView = baseView();
  oppManaView.seatB.zones.MANA = [
    card('bm1', 'B表マナ1'),
    card('bm2', 'B表マナ2', { tapped: true }),   // ★表向きにタップ済みを混ぜる(7-3)
    card('bm3', 'B裏マナ1', { faceDown: true }),
    card('bm4', 'B裏マナ2', { faceDown: true }),
    card('bm5', 'B裏マナ3', { faceDown: true }),
  ];
  syncCounts(oppManaView.seatB);
  // 表向きアンタップ1 + 裏アンタップ2 = MP 3 → 裏タップは 3 - 2 = 1
  oppManaView.seatB.mp = 3;
  await render(page, oppManaView);
  check('★相手の表向きマナが文明色タイル+カード名になる(2-2)',
    (await page.locator('#seat-opponent-top .manual-opp-mana-face').count()) === 2
      && (await page.locator('#seat-opponent-top .manual-opp-mana-name').first().textContent())
        === 'B表マナ1'
      && (await page.locator('#seat-opponent-top .manual-opp-mana-face img').count()) === 0);
  const oppManaTile = await boxOf('#seat-opponent-top .manual-opp-mana-face');
  check('★相手のマナタイルが 48×66 になる(2-3)',
    Math.abs(oppManaTile.width - 48) < 1.5 && Math.abs(oppManaTile.height - 66) < 1.5,
    `${oppManaTile.width}×${oppManaTile.height}`);
  const oppTrashFace = await boxOf('#seat-opponent-top .manual-opp-pile[data-zone="TRASH"] .manual-opp-face');
  check('★相手の墓地・消滅の面も 48×66 に揃う(2-3)',
    Math.abs(oppTrashFace.width - 48) < 1.5 && Math.abs(oppTrashFace.height - 66) < 1.5,
    `${oppTrashFace.width}×${oppTrashFace.height}`);

  const backCounts = await page.locator('#seat-opponent-top .manual-opp-mana-back .manual-opp-count')
    .allTextContents();
  check('★相手の裏マナがアンタップ枠とタップ枠の2つに分かれる(2-7)',
    (await page.locator('#seat-opponent-top .manual-opp-mana-back').count()) === 2,
    JSON.stringify(backCounts));
  check('★裏マナの内訳が mp からの引き算で正しく出る(2-7)',
    backCounts.length === 2 && backCounts[0] === '2' && backCounts[1] === '1',
    JSON.stringify(backCounts));
  check('★タップぶんの枠だけが減光される(2-7・2-5)',
    (await page.locator('#seat-opponent-top .manual-opp-mana-back.manual-opp-tapped')
      .count()) === 1
      && (await page.locator(
        '#seat-opponent-top .manual-opp-mana-back[data-tapped="false"].manual-opp-tapped')
        .count()) === 0);

  // ★0枚の枠は出さない
  const allUntapped = baseView();
  allUntapped.seatB.zones.MANA = [card('bu1', 'B裏マナ', { faceDown: true })];
  syncCounts(allUntapped.seatB);
  allUntapped.seatB.mp = 1;
  await render(page, allUntapped);
  check('★裏マナが全部アンタップなら枠は1つだけ(0枚の枠を出さない。2-7)',
    (await page.locator('#seat-opponent-top .manual-opp-mana-back').count()) === 1
      && (await page.locator('#seat-opponent-top .manual-opp-mana-back[data-tapped="true"]')
        .count()) === 0);
  allUntapped.seatB.mp = 0;
  await render(page, allUntapped);
  check('★裏マナが全部タップなら枠は1つだけ(2-7)',
    (await page.locator('#seat-opponent-top .manual-opp-mana-back').count()) === 1
      && (await page.locator('#seat-opponent-top .manual-opp-mana-back[data-tapped="true"]')
        .count()) === 1);

  // ★引き算が負・超過になるズレは丸める(2-7 の防御的な扱い)
  const skewed = baseView();
  skewed.seatB.zones.MANA = [
    card('bs1', 'B裏1', { faceDown: true }), card('bs2', 'B裏2', { faceDown: true }),
  ];
  syncCounts(skewed.seatB);
  skewed.seatB.mp = 99; // サーバとのズレを模す
  await render(page, skewed);
  const skewCounts = await page.locator('#seat-opponent-top .manual-opp-mana-back .manual-opp-count')
    .allTextContents();
  check('★内訳が総枚数を超えないよう丸める(2-7)',
    skewCounts.length === 1 && skewCounts[0] === '2', JSON.stringify(skewCounts));

  // ★相手上段の高さ制約(2-3)。マナを広げたぶん高さ予算が縮んでいる
  await render(page, oppManaView);
  const oppTopAfter = await boxOf('#seat-opponent-top');
  check('★マナを 48×66 へ広げても相手上段は148px内に収まる(2-3)',
    oppTopAfter.height <= 148, `h=${oppTopAfter.height}`);
  await render(page, versusView('A'));
  const oppTopVersus = await boxOf('#seat-opponent-top');
  check('★対戦部屋でも相手上段が148px内に収まる(2-3)',
    oppTopVersus.height <= 148, `h=${oppTopVersus.height}`);

  // ---- 22-4. 数値のボタン化(設計書4章)----
  const statView = baseView();
  statView.seatA.zones.WEAPON = [
    card('w9', 'ウェポン', { type: 'WEAPON', hp: null, printedHp: null, attack: 4 }),
  ];
  syncCounts(statView.seatA);
  await render(page, statView);
  const statButtons = await page.evaluate(() => {
    const pick = (sel) => {
      const el = document.querySelector(sel);
      return el ? { title: el.title, pen: !!el.querySelector('.manual-stat-pen') } : null;
    };
    return {
      lp: pick('#pile-grid .manual-leader-tile .manual-stat-button'),
      minion: pick('#seat-self-minions .manual-tile .manual-stat-button'),
      weapon: pick('#pile-grid .manual-weapon-slot .manual-stat-button'),
    };
  });
  check('★LP・ATK/HP・ウェポンATK が .manual-stat-button になり鉛筆と title を持つ(4-2)',
    ['lp', 'minion', 'weapon'].every((k) => statButtons[k]
      // ★Batch 34: 文言は tileTitle 経由になった(2章)。名前が先頭に来る形へ変わっている
      && statButtons[k].pen && statButtons[k].title.startsWith('数値を編集')),
    JSON.stringify(statButtons));
  await page.locator('#seat-self-minions .manual-tile .manual-stat-button').first().click();
  await page.waitForTimeout(60);
  check('数値チップを押すと数値モーダルが開く(4-2)',
    !(await page.locator('#stat-modal').getAttribute('class')).includes('d-none'));
  await clearSent(page);
  const deltaButtons = page.locator('#stat-modal-fields .manual-stat-delta button');
  check('★数値モーダルの ATK / HP に -1 / +1 がある(4-4)',
    (await deltaButtons.count()) === 4, `n=${await deltaButtons.count()}`);
  await deltaButtons.nth(1).click(); // ATK の +1
  msgs = boardMessages(await sent(page));
  check('★数値モーダルの +1 で stat が送られ、モーダルは閉じない(4-4)',
    msgs.length === 1 && msgs[0].destination.endsWith('/stat') && msgs[0].body.attack === 3
      && !(await page.locator('#stat-modal').getAttribute('class')).includes('d-none'),
    JSON.stringify(msgs));
  await page.locator('#stat-modal-close').click();
  await page.waitForTimeout(60);
  check('修正値チップ(.manual-stat-chip)はボタン化していない(4-3)',
    (await page.locator('.manual-stat-chip.manual-stat-button').count()) === 0);

  // ---- 22-5. 縦の収まり(★見出し1行とウェポン枠を足したあと)----
  await render(page, statView);
  const rootAfter = await page.locator('#manual-root').boundingBox();
  check('★見出しとウェポン枠を足しても盤面全体がビューポート(950px)に収まる(2-8・3-2)',
    rootAfter.height <= 950, `h=${rootAfter.height}`);
  const bWeaponSlot = await boxOf('#pile-grid .manual-weapon-slot');
  const bLeaderSlot = await boxOf('#pile-grid .manual-leader-slot');
  const bLostAfter = await boxOf('#pile-grid .manual-pile[data-zone="LOST"]');
  check('★ウェポン枠がリーダーの真下(2/1)にあり、消滅と同じ行である(3-2)',
    Math.abs(bWeaponSlot.x - bLeaderSlot.x) < 2 && bWeaponSlot.y > bLeaderSlot.y
      && Math.abs(bWeaponSlot.y - bLostAfter.y) < 4,
    `weapon=(${bWeaponSlot.x},${bWeaponSlot.y}) leader.x=${bLeaderSlot.x} lost.y=${bLostAfter.y}`);

  // =====================================================================
  // ★Batch 21b: 入口の画面(ロビー・席選択・在室者リスト・自席=下)
  // =====================================================================

  // ---- 17. 席選択ゲート(設計書 2-1) ----
  // ★localStorage に occupantId があるため、通常起動ではゲートを素通りする
  check('復帰時は席選択ゲートが開かない(2-1)',
    (await page.locator('#seat-gate').getAttribute('class')).includes('d-none'));

  // 入室前ゲート: 席Aが埋まっている対戦部屋
  // ★戻り値の Promise を返してはならない。openJoinGate は席が選ばれるまで解決しないため、
  //   page.evaluate がその Promise を待って永久に返らなくなる。
  await page.evaluate((summary) => { openJoinGate(summary); },
    roomSummary({ seatAName: 'あかり' }));
  await page.waitForTimeout(60);
  check('席選択ゲートが開き、部屋名と種類が出る',
    (await page.locator('#seat-gate-room').textContent()).includes('テスト対戦')
      && (await page.locator('#seat-gate-type').textContent()).includes('対戦'));
  check('埋まっている席のボタンは無効で在席者名が出る(2-1)',
    (await page.locator('#seat-gate-a').isDisabled())
      && (await page.locator('#seat-gate-a').textContent()).includes('あかり'),
    await page.locator('#seat-gate-a').textContent());
  check('空席のボタンは有効',
    !(await page.locator('#seat-gate-b').isDisabled()));
  check('観戦可の部屋では観戦ボタンが出る',
    !(await page.locator('#seat-gate-spectate').getAttribute('class')).includes('d-none'));
  check('対戦部屋では名前が必須と表示される(F4)',
    (await page.locator('#seat-gate-name-req').textContent()).includes('必須'));

  // ★名前未入力のまま座ろうとしたら、入室APIを呼ばずに止まること
  let fetched = await page.evaluate(async () => {
    window.__fetched = [];
    window.__origFetch = window.fetch;
    window.fetch = (url, opts) => {
      window.__fetched.push({ url, opts });
      return Promise.resolve({ ok: true, json: async () => ({
        roomId: 'TESTRM', occupantId: 'occ-new', displayName: 'ばんり', seat: 'B',
      }) });
    };
    document.getElementById('seat-gate-b').click();
    await new Promise((r) => setTimeout(r, 60));
    return window.__fetched.length;
  });
  check('対戦部屋で名前未入力なら入室APIを呼ばない(F4)', fetched === 0, `fetch=${fetched}`);
  check('名前未入力のエラーが画面に出る',
    !(await page.locator('#seat-gate-error').getAttribute('class')).includes('d-none'));

  // 名前を入れれば席つきで入室APIが呼ばれ、ゲートが閉じること
  await page.locator('#seat-gate-name').fill('ばんり');
  const joinCall = await page.evaluate(async () => {
    window.__fetched = [];
    document.getElementById('seat-gate-b').click();
    await new Promise((r) => setTimeout(r, 80));
    return window.__fetched.map((f) => ({ url: f.url, body: JSON.parse(f.opts.body) }));
  });
  check('席を選ぶと seat つきで入室APIを呼ぶ(2-1)',
    joinCall.length === 1 && joinCall[0].url.endsWith('/occupants')
      && joinCall[0].body.seat === 'B' && joinCall[0].body.displayName === 'ばんり',
    JSON.stringify(joinCall));
  check('入室に成功するとゲートが閉じる',
    (await page.locator('#seat-gate').getAttribute('class')).includes('d-none'));
  await page.evaluate(() => { window.fetch = window.__origFetch; });

  // 観戦を許可しない部屋では観戦ボタンを出さない(2-1)
  await page.evaluate((summary) => { openJoinGate(summary); },
    roomSummary({ spectatorAllowed: false }));
  await page.waitForTimeout(40);
  check('観戦不可の部屋では観戦ボタンを出さない(2-1)',
    (await page.locator('#seat-gate-spectate').getAttribute('class')).includes('d-none'));
  await page.evaluate(() => closeGate());

  // ---- 18. 席に着く / 席を立つ(2-2) ----
  // 席Aは空き、席Bは切断猶予中、自分は観戦者、という配置にする
  const spectateView = versusView(null);
  spectateView.occupants = [
    occupant('ばんり', 'B', { connected: false, disconnectSecondsLeft: 245 }),
    occupant('みるひと', null, { self: true }),
  ];
  await render(page, spectateView);
  check('観戦中はヘッダのボタンが「席に着く」になる(2-2)',
    (await page.locator('#btn-seat').textContent()).trim() === '席に着く');

  await page.locator('#btn-seat').click();
  await page.waitForTimeout(60);
  check('「席に着く」で席選択ゲートが開く(2-2)',
    !(await page.locator('#seat-gate').getAttribute('class')).includes('d-none'));
  check('切断猶予中の席は残り時間つきで座れない(2-4)',
    (await page.locator('#seat-gate-b').isDisabled())
      && (await page.locator('#seat-gate-b').textContent()).includes('切断中'),
    await page.locator('#seat-gate-b').textContent());
  check('昇格モードでは名前入力を出さない',
    (await page.locator('#seat-gate-name-wrap').getAttribute('class')).includes('d-none'));

  await clearSent(page);
  await page.locator('#seat-gate-a').click();
  await page.waitForTimeout(60);
  msgs = await sent(page);
  check('空席を選ぶと seat メッセージが送られる(2-2)',
    msgs.length === 1 && msgs[0].destination.endsWith('/seat') && msgs[0].body.seat === 'A',
    JSON.stringify(msgs));

  // 席を立つ
  await render(page, versusView('A'));
  check('着席中はヘッダのボタンが「席を立つ」になる(2-2)',
    (await page.locator('#btn-seat').textContent()).trim() === '席を立つ');
  await clearSent(page);
  page.once('dialog', (d) => d.accept());
  await page.locator('#btn-seat').click();
  await page.waitForTimeout(80);
  msgs = await sent(page);
  check('「席を立つ」で seat:null が送られる(2-2)',
    msgs.length === 1 && msgs[0].destination.endsWith('/seat') && msgs[0].body.seat === null,
    JSON.stringify(msgs));

  // ---- 19. 在室者リストとポップオーバー(2-3) ----
  const crowded = versusView('A');
  crowded.occupants.push(occupant('よにんめ', null, { spectatorView: 'ALL' }));
  crowded.occupants[2].connected = false;
  crowded.occupants[2].disconnectSecondsLeft = 120;
  await render(page, crowded);
  check('在室者チップは3人ぶんまで + 「+n」に畳む(2-3)',
    (await page.locator('#occupant-list > span').count()) === 4
      && (await page.locator('.manual-occupant-more').textContent()) === '+1',
    await page.locator('#occupant-list').textContent());
  check('チップに役割記号が入る(2-3)',
    (await page.locator('#occupant-list').textContent()).includes('[A]')
      && (await page.locator('#occupant-list').textContent()).includes('[観]'),
    await page.locator('#occupant-list').textContent());
  check('ポップオーバーは既定で閉じている',
    (await page.locator('#occupant-popover').getAttribute('class')).includes('d-none'));
  await page.locator('#occupant-list').click();
  await page.waitForTimeout(40);
  const popText = await page.locator('#occupant-popover-body').textContent();
  check('チップのクリックで在室者ポップオーバーが開く(2-3)',
    !(await page.locator('#occupant-popover').getAttribute('class')).includes('d-none'));
  check('ポップオーバーに全員・役割・観戦の視点・切断が出る(2-3)',
    (await page.locator('#occupant-popover-body tr').count()) === 4
      && popText.includes('席A') && popText.includes('観戦')
      && popText.includes('全見え') && popText.includes('切断中'),
    popText);
  await page.locator('#occupant-popover-close').click();

  // ---- 20. 自席=下(3-1)。★入れ替えるのは表示位置だけ(10章) ----
  const asSeatB = versusView('B');
  asSeatB.seatB.zones.HAND = [card('bh1', 'B手札1')];
  asSeatB.seatB.zones.FIELD = [];
  syncCounts(asSeatB.seatB);
  await render(page, asSeatB);
  check('席Bで入ると下段が席B・上段が席Aになる(3-1)',
    (await page.locator('#seat-self-minions .minion-row').getAttribute('data-seat')) === 'B'
      && (await page.locator('#seat-opponent-minions .minion-row').getAttribute('data-seat')) === 'A');
  check('手札行も自席(B)になる',
    (await page.locator('#hand-row .hand-row').getAttribute('data-seat')) === 'B');
  check('右列のパイルが自席(B)のリーダーを出す',
    (await page.locator('#pile-grid .manual-pile-label').first().textContent()).includes('席B'),
    await page.locator('#pile-grid .manual-pile-label').first().textContent());

  // ★★実マウス: 下段が席Bでも、送信ペイロードの席は実席のまま(10章)
  await clearSent(page);
  await realDrag(page, '#hand-row .manual-hand-card', '#seat-self-minions .tile-slot-empty');
  let raw = await sent(page);
  msgs = boardMessages(raw);
  check('自席=下でも move の toSeat は実席(B)のまま(★10章)',
    msgs.length === 1 && msgs[0].destination.endsWith('/move')
      && msgs[0].body.toSeat === 'B' && msgs[0].body.cardIds[0] === 'bh1',
    JSON.stringify(msgs));
  // ★21c 7-2: 同じドラッグで矢印も飛ぶ。開始 → ホバー → 消去の順であること
  let cues = cueMessages(raw);
  check('★ドラッグで矢印が 開始→ホバー→消去 の順に送られる(7-2)',
    cues.length >= 3 && cues[0].body.active === true && cues[0].body.toZone === null
      && cues[cues.length - 1].body.active === false,
    JSON.stringify(cues.map((c) => c.body)));
  check('★矢印のホバー先にも実席が載る(視点を混ぜない。10章)',
    cues.some((c) => c.body.toZone === 'FIELD' && c.body.toSeat === 'B'),
    JSON.stringify(cues.map((c) => c.body)));

  await clearSent(page);
  await realDrag(page, '#hand-row .manual-hand-card', '#seat-opponent-minions .tile-slot-empty');
  raw = await sent(page);
  msgs = boardMessages(raw);
  check('相手側へ落としたときの toSeat も実席(A)になる(★10章)',
    msgs.length === 1 && msgs[0].body.toSeat === 'A', JSON.stringify(msgs));
  check('★相手側をホバーした矢印の toSeat も実席(A)になる(7-1)',
    cueMessages(raw).some((c) => c.body.toZone === 'FIELD' && c.body.toSeat === 'A'),
    JSON.stringify(cueMessages(raw).map((c) => c.body)));

  // ---- 21. 対戦部屋の count-only ビュー(3-3) ----
  const versusA = versusView('A');
  await render(page, versusA);
  const chipText = await page.locator('#seat-opponent-top .zone-pile-mini').allTextContents();
  check('相手の非公開ゾーンは中身が届かないが枚数は counts から出る(3-3)',
    chipText[0] === '山2' && chipText[1] === '禁1' && chipText[3] === '手1',
    JSON.stringify(chipText));
  check('相手席の zones に非公開ゾーンのキーが無い(fixture の前提確認)',
    await page.evaluate(() => !('HAND' in window.latestView.seatB.zones)));
  // ★25c: 手札の枚数ラベルは行見出しから宣言ボタン下(#hand-count-line)へ移った
  check('自席の手札の枚数が宣言ボタン下に出る(25c)',
    (await page.locator('#hand-count-line').textContent()).includes('2枚'),
    await page.locator('#hand-count-line').textContent());
  // ★21c: 相手のマナも「表向きカード + 裏向きは枚数」で出る(3-3・4章)
  check('相手の裏向きマナは裏面1枚+枚数バッジになる(4章・3-3)',
    (await page.locator('#seat-opponent-top .manual-opp-mana-back').count()) === 1
      && (await page.locator('#seat-opponent-top .manual-opp-mana-back .manual-opp-count')
        .textContent()) === '3',
    await page.locator('#seat-opponent-top .manual-opp-mana-track').textContent());
  check('相手上段は対戦部屋でも148px内に収まる(4章)',
    (await page.locator('#seat-opponent-top').boundingBox()).height <= 148,
    `h=${(await page.locator('#seat-opponent-top').boundingBox()).height}`);

  // ---- 21-2. 非公開チップのフィードバック(★21c 3-5) ----
  await page.evaluate(() => closeOverlay());
  await page.locator('#seat-opponent-top .zone-pile-mini[data-zone="HAND"]').click();
  await page.waitForTimeout(60);
  check('★非公開チップをクリックしても帯が開かない(3-5)',
    (await page.locator('#manual-overlay-root .manual-band').count()) === 0);
  check('★「非公開」の小トーストが出る(3-5)',
    (await page.locator('#manual-toast').count()) === 1
      && (await page.locator('#manual-toast').textContent()).includes('非公開'),
    await page.locator('#manual-toast').textContent().catch(() => '(なし)'));
  check('★押したチップが明滅する(3-5)',
    (await page.locator('#seat-opponent-top .zone-pile-mini[data-zone="HAND"]')
      .getAttribute('class')).includes('manual-denied'));

  // 公開ゾーン(墓地)は対戦部屋でも帯が開く(4章・B4)
  const versusTrash = versusView('A');
  versusTrash.seatB.zones.TRASH = [card('bt1', 'B墓地1')];
  syncCounts(versusTrash.seatB);
  await render(page, versusTrash);
  // ★22 1-2: 帯を開くのは右クリックになった(左は最上段の拡大)
  await page.locator('#seat-opponent-top .manual-opp-pile[data-zone="TRASH"]')
    .click({ button: 'right' });
  await page.waitForTimeout(60);
  check('相手の公開ゾーン(墓地)は対戦部屋でも帯が開く(B4)',
    (await page.locator('#manual-overlay-root .manual-band').count()) === 1);
  await page.evaluate(() => closeOverlay());

  // 全公開部屋では手札チップからも帯が開く(4章)
  await render(page, baseView());
  await page.locator('#seat-opponent-top .zone-pile-mini[data-zone="HAND"]')
    .click({ button: 'right' });
  await page.waitForTimeout(60);
  check('全公開部屋では手札チップからも帯が開く(4章)',
    (await page.locator('#manual-overlay-root .manual-band').count()) === 1);
  await page.evaluate(() => closeOverlay());
  await render(page, versusA);

  // ---- 22. 対戦部屋での出し分け(6-3・D4・D6・E3) ----
  check('対戦部屋では Redo ボタンを隠す(D6)',
    (await page.locator('#btn-redo').getAttribute('class')).includes('d-none'));
  check('対戦部屋のデッキ読込は自席のぶんだけ出す(E3)',
    !(await page.locator('#deck-label-a').getAttribute('class')).includes('d-none')
      && (await page.locator('#deck-label-b').getAttribute('class')).includes('d-none'));
  await clearSent(page);
  await page.locator('#declare-buttons button[data-declaration="WIN"]').click();
  await page.waitForTimeout(40);
  msgs = await sent(page);
  check('宣言は自席のぶんが送られる(D4)',
    msgs.length === 1 && msgs[0].body.seat === 'A', JSON.stringify(msgs));

  await render(page, versusView('B'));
  check('席Bで入ると出し分けも反転する(E3)',
    (await page.locator('#deck-label-a').getAttribute('class')).includes('d-none')
      && !(await page.locator('#deck-label-b').getAttribute('class')).includes('d-none'));

  await render(page, baseView());
  check('全公開部屋では Redo もデッキ2つも戻る(後方互換)',
    !(await page.locator('#btn-redo').getAttribute('class')).includes('d-none')
      && !(await page.locator('#deck-label-a').getAttribute('class')).includes('d-none')
      && !(await page.locator('#deck-label-b').getAttribute('class')).includes('d-none'));

  // ---- 23. ログ書出リンクに occupantId が付く(21a 1-12 の積み残し) ----
  check('ログ書出リンクに occupantId が付く(5-4)',
    (await page.locator('#btn-log').getAttribute('href')).includes('occupantId=occ-test'),
    await page.locator('#btn-log').getAttribute('href'));

  // ---- 24. 部屋名・種類の表示 ----
  check('ヘッダに部屋名と種類が出る',
    (await page.locator('#room-name').textContent()).includes('テスト部屋')
      && (await page.locator('#room-type').textContent()).includes('全公開'));

  // ---- 24-2. 対戦部屋の観戦者は盤面を掴めない(6-1) ----
  const spectating = versusView(null);
  await render(page, spectating);
  await clearSent(page);
  await realDrag(page, '#pile-grid .manual-pile[data-zone="DECK"] .manual-pile-face',
    '#seat-self-minions .tile-slot-empty');
  msgs = await sent(page);
  check('対戦部屋の観戦者はドラッグしても何も送らない(6-1)', msgs.length === 0,
    JSON.stringify(msgs));

  // =====================================================================
  // ★Batch 21c: 矢印・観戦トグル・先攻選択権
  // =====================================================================

  // ---- 26. ドラッグ軌跡の矢印(設計書7章) ----
  // ★受信側の描画だけを合成 CUE で確かめる。ドラッグ操作そのものの検証は
  //   20 節の実マウスで済ませてある(合成 DragEvent は使っていない)。
  const cueView = baseView();
  cueView.seatB.zones.FIELD = [card('bf1', 'B場1')];
  syncCounts(cueView.seatB);
  await render(page, cueView);

  const injectCue = async (cue) => {
    await page.evaluate((c) => { applyDragCue(c); }, cue);
    await page.waitForTimeout(40);
  };

  await injectCue({
    actorSeat: 'B', actorName: 'ばんり',
    from: { seatId: 'B', zone: 'HAND' }, cardId: null,
    to: { seatId: 'B', zone: 'FIELD' }, active: true,
  });
  check('CUE を受け取ると矢印が1本描かれる(7-1)',
    (await page.locator('#manual-cue-layer line.manual-cue-line').count()) === 1);
  check('★見えないカードの矢印の根はゾーンのアンカー(手札チップ)になる(7-3)',
    (await page.locator('#seat-opponent-top .zone-pile-mini[data-zone="HAND"]')
      .getAttribute('class')).includes('manual-cue-from'));
  check('矢印の先(相手のミニオン行)がハイライトされる(7-4)',
    (await page.locator('#seat-opponent-minions .minion-row')
      .getAttribute('class')).includes('manual-cue-to'));
  check('誰が動かしているかが矢印に添えられる',
    (await page.locator('#manual-cue-layer text.manual-cue-label').textContent()) === 'ばんり');
  check('★矢印レイヤは当たり判定を奪わない(pointer-events: none)',
    (await page.evaluate(() =>
      getComputedStyle(document.getElementById('manual-cue-layer')).pointerEvents)) === 'none');

  // 見えるカードなら、根はゾーンではなくそのカードになる(7-3)
  await injectCue({
    actorSeat: 'B', actorName: 'ばんり',
    from: { seatId: 'B', zone: 'FIELD' }, cardId: 'bf1',
    to: { seatId: null, zone: 'PLAY' }, active: true,
  });
  check('★見えるカードの矢印の根はそのカード自身になる(7-3)',
    (await page.locator('[data-instance-id="bf1"]').getAttribute('class'))
      .includes('manual-cue-from'));
  check('共有ゾーン(席なし)も矢印の先になれる(7-1)',
    (await page.locator('.manual-center-half[data-zone="PLAY"]')
      .getAttribute('class')).includes('manual-cue-to'));
  check('矢印は同じ人につき1本しか残らない',
    (await page.locator('#manual-cue-layer line.manual-cue-line').count()) === 1);

  // ドロップ先が未定(to が null)なら線は引かず、根のハイライトだけ出す(7-4)
  await injectCue({
    actorSeat: 'B', actorName: 'ばんり',
    from: { seatId: 'B', zone: 'FIELD' }, cardId: 'bf1', to: null, active: true,
  });
  check('ドロップ先が未定なら線を引かず根だけをハイライトする(7-4)',
    (await page.locator('#manual-cue-layer line.manual-cue-line').count()) === 0
      && (await page.locator('[data-instance-id="bf1"]').getAttribute('class'))
        .includes('manual-cue-from'));

  // active:false で消える(7-2)
  await injectCue({
    actorSeat: 'B', actorName: 'ばんり', from: null, cardId: null, to: null, active: false,
  });
  check('active:false で矢印もハイライトも消える(7-2)',
    (await page.locator('#manual-cue-layer line.manual-cue-line').count()) === 0
      && (await page.locator('.manual-cue-from').count()) === 0
      && (await page.locator('.manual-cue-to').count()) === 0);
  check('★矢印はログにも盤面にも触れない(受信でログ行が増えない。7-2)',
    (await page.locator('#log-box > div').count()) === 4,
    `log=${await page.locator('#log-box > div').count()}`);

  // ---- 27. 観戦トグル(設計書3-2) ----
  await render(page, versusView('A'));
  check('プレイヤーには観戦トグルを出さない(3-1)',
    (await page.locator('#btn-spectator-view').getAttribute('class')).includes('d-none')
      && (await page.locator('#btn-flip').getAttribute('class')).includes('d-none'));

  const spectator = versusView(null);
  await render(page, spectator);
  check('観戦者にはトグル2つが出る(3-2)',
    !(await page.locator('#btn-spectator-view').getAttribute('class')).includes('d-none')
      && !(await page.locator('#btn-flip').getAttribute('class')).includes('d-none'));
  check('視点ボタンに現在の視点が出る(既定は公開のみ)',
    (await page.locator('#btn-spectator-view').textContent()).includes('公開のみ'),
    await page.locator('#btn-spectator-view').textContent());

  await clearSent(page);
  await page.locator('#btn-spectator-view').click();
  await page.waitForTimeout(40);
  msgs = await sent(page);
  check('★視点の切替はサーバへ送る(3-2)',
    msgs.length === 1 && msgs[0].destination.endsWith('/viewpoint')
      && msgs[0].body.spectatorView === 'ALL', JSON.stringify(msgs));

  const spectatorAll = versusView(null);
  spectatorAll.spectatorView = 'ALL';
  await render(page, spectatorAll);
  check('全見えのときは再クリックで公開のみへ戻す',
    (await page.locator('#btn-spectator-view').textContent()).includes('全見え'));

  // 上下反転(★クライアント描画だけ。サーバへ送らない)
  await render(page, spectator);
  check('観戦の既定は席Aが下(3-2)',
    (await page.locator('#seat-self-minions .minion-row').getAttribute('data-seat')) === 'A'
      && (await page.locator('#btn-flip').textContent()).includes('席A'));
  await clearSent(page);
  await page.locator('#btn-flip').click();
  await page.waitForTimeout(60);
  check('★上下反転で下段が席Bになる(3-2)',
    (await page.locator('#seat-self-minions .minion-row').getAttribute('data-seat')) === 'B'
      && (await page.locator('#seat-opponent-minions .minion-row')
        .getAttribute('data-seat')) === 'A');
  check('★上下反転はサーバへ送らない(3-2)', (await sent(page)).length === 0,
    JSON.stringify(await sent(page)));
  check('反転してもボタンの表示が追随する',
    (await page.locator('#btn-flip').textContent()).includes('席B'));

  // ★反転中でも右列のパイルは「下段の席」を出す(表示位置だけが入れ替わっている)
  check('反転中は右列のパイルも席Bになる',
    (await page.locator('#pile-grid .manual-pile-label').first().textContent()).includes('席B'),
    await page.locator('#pile-grid .manual-pile-label').first().textContent());

  // ★席に着いたら反転は解除される(3-1: プレイヤーは常に自席=下)
  await render(page, versusView('A'));
  check('★席に着くと反転が解除され、自席が下に戻る(3-1)',
    (await page.locator('#seat-self-minions .minion-row').getAttribute('data-seat')) === 'A');

  // ---- 28. ★Batch 23: ゲーム開始シーケンス(総合ルール 2-5 / 23 設計書10-2) ----
  //
  // ★21c の「先攻決めボタン」の検証は削除した(23 設計書 3-4・P14・10-2)。
  //   ボタンもエンドポイントも廃止し、先攻を決める経路を1本にしたためである。
  //   落ちた項目を直すのではなく、新しい仕様に合わせて書き換える(22 の 7-2 と同じ方針)。
  //
  // ★ここで確かめているのは「押せるものが出る / 出ない」と「何が送られるか」だけである。
  //   誰が押せるかを決めているのはサーバ(view.start.canBegin ほか)であり、
  //   クライアントはその結果を描くだけになっている(判定を2箇所に置かない)。

  // 28-1. [ゲームを始める] の出し分け
  await render(page, baseView());
  check('開始できない状態では [ゲームを始める] を出さない(2-3)',
    (await page.locator('#btn-start').getAttribute('class')).includes('d-none'));
  check('21c の先攻決めボタンは廃止されている(3-4・P14)',
    (await page.locator('#btn-first-player').count()) === 0);

  let v = baseView();
  v.start = startState({ canBegin: true });
  await render(page, v);
  check('★canBegin なら [ゲームを始める] が出る(全公開部屋の入口。6-1)',
    !(await page.locator('#btn-start').getAttribute('class')).includes('d-none'));
  await clearSent(page);
  await page.locator('#btn-start').click();
  await page.waitForTimeout(40);
  msgs = boardMessages(await sent(page));
  check('★[ゲームを始める] は start-begin を1件だけ送る',
    msgs.length === 1 && msgs[0].destination.endsWith('/start-begin'), JSON.stringify(msgs));

  v = versusView('B');
  v.start = startState({ canBegin: false, phase: 'IDLE' });
  await render(page, v);
  check('★対戦部屋で作成者席でない側には [ゲームを始める] を出さない(2-4)',
    (await page.locator('#btn-start').getAttribute('class')).includes('d-none'));

  // 28-2. 開始方法モーダル(3-1)。★①の文言が部屋の種類で切り替わる
  v = versusView('A');
  v.start = startState({ phase: 'ORDER_METHOD', locking: true, canChooseMethod: true,
    waiting: 'ゲームの開始方法を選んでいます' });
  await render(page, v);
  check('ORDER_METHOD で作成者に開始方法モーダルが出る(3-1)',
    !(await page.locator('#start-method-modal').getAttribute('class')).includes('d-none'));
  check('★対戦部屋の①は「ダイスで決める(20面)」(3-2)',
    (await page.locator('#start-method-dice').textContent()).includes('20面'),
    await page.locator('#start-method-dice').textContent());
  check('開始方法は3択である(3-1)',
    (await page.locator('#start-method-modal button[data-method]').count()) === 3);
  check('★②③のボタンは「自分」ではなく席名で書く(subjectSeat)',
    (await page.locator('#start-method-first').textContent()).includes('席A が先攻'),
    await page.locator('#start-method-first').textContent());
  await clearSent(page);
  await page.locator('#start-method-first').click();
  await page.waitForTimeout(40);
  msgs = boardMessages(await sent(page));
  check('★「自分が先攻」は start-method に FIRST を載せて送る',
    msgs.length === 1 && msgs[0].destination.endsWith('/start-method')
      && msgs[0].body.method === 'FIRST', JSON.stringify(msgs));

  v = baseView();
  v.start = startState({ phase: 'ORDER_METHOD', locking: true, canChooseMethod: true });
  await render(page, v);
  check('★ソロ(全公開部屋)の①は「ランダムで決める」に変わる(3-1)',
    (await page.locator('#start-method-dice').textContent()).includes('ランダム'),
    await page.locator('#start-method-dice').textContent());

  // ★23 hotfix: 一人回しでデッキが1つだけのとき、主語はその席になる(サーバが決める)。
  //   「自分」と書いたままだと、どちらが先攻になるのか画面から読めない
  v = baseView();
  v.start = startState({ phase: 'ORDER_METHOD', locking: true, canChooseMethod: true,
    subjectSeat: 'B' });
  await render(page, v);
  check('★★1デッキのソロでも開始方法を選べ、主語がデッキのある席になる',
    (await page.locator('#start-method-first').textContent()).includes('席B が先攻')
      && (await page.locator('#start-method-second').textContent()).includes('席B が後攻'),
    await page.locator('#start-method-second').textContent());
  check('★後攻の選択肢に「5枚+ピュア・エレメント」と書いてある(何が変わるか読める)',
    (await page.locator('#start-method-second').textContent()).includes('ピュア・エレメント'));
  await clearSent(page);
  await page.locator('#start-method-second').click();
  await page.waitForTimeout(40);
  msgs = boardMessages(await sent(page));
  check('★「後攻をとる」は start-method に SECOND を載せる(席は載せない)',
    msgs.length === 1 && msgs[0].destination.endsWith('/start-method')
      && msgs[0].body.method === 'SECOND' && msgs[0].body.seat === undefined,
    JSON.stringify(msgs));

  v = versusView('B');
  v.start = startState({ phase: 'ORDER_METHOD', locking: true, canChooseMethod: false,
    waiting: 'ゲームの開始方法を選んでいます' });
  await render(page, v);
  check('★開始方法モーダルは押せない席には出ない(2-4)',
    (await page.locator('#start-method-modal').getAttribute('class')).includes('d-none'));
  check('★押せない側には待機表示が出る(7-3)',
    !(await page.locator('#start-banner').getAttribute('class')).includes('d-none')
      && (await page.locator('#start-banner-text').textContent()).includes('開始方法'));

  // 28-3. 先攻・後攻の選択(3-3)
  v = versusView('A');
  v.start = startState({ phase: 'ORDER_CHOICE', locking: true, orderChooser: 'A',
    canChooseOrder: true });
  await render(page, v);
  check('ORDER_CHOICE で勝った席に選択モーダルが出る(3-3)',
    !(await page.locator('#start-order-modal').getAttribute('class')).includes('d-none'));
  check('★選択モーダルの見出しに勝った席が出る(3-3)',
    (await page.locator('#start-order-title').textContent()).includes('席A'));
  await clearSent(page);
  await page.locator('#start-order-second').click();
  await page.waitForTimeout(40);
  msgs = boardMessages(await sent(page));
  check('★「後攻をとる」は start-order に takeFirst=false を載せる(席は載せない)',
    msgs.length === 1 && msgs[0].destination.endsWith('/start-order')
      && msgs[0].body.takeFirst === false && msgs[0].body.seat === undefined,
    JSON.stringify(msgs));

  v = versusView('B');
  v.start = startState({ phase: 'ORDER_CHOICE', locking: true, orderChooser: 'A',
    canChooseOrder: false, waiting: '席A が先攻・後攻を選んでいます' });
  await render(page, v);
  check('★勝っていない席には選択モーダルを出さず、待機表示にする(3-3・7-3)',
    (await page.locator('#start-order-modal').getAttribute('class')).includes('d-none')
      && (await page.locator('#start-banner-text').textContent()).includes('席A'));

  // 28-4. マリガン専用オーバーレイ(4-3)
  v = versusView('A');
  v.start = startState({ phase: 'MULLIGAN', locking: true, firstSeat: 'A',
    mulliganSeats: ['A', 'B'], mulliganDone: [], myMulliganSeats: ['A'],
    waiting: 'マリガンの確定を待っています(席A: 選択中 / 席B: 選択中)' });
  await render(page, v);
  check('★マリガンは盤面の手札行ではなく専用オーバーレイで行う(4-3)',
    (await page.locator('.manual-mulligan').count()) === 1);
  check('マリガンオーバーレイに自席の手札が並ぶ',
    (await page.locator('.manual-mulligan-card').count())
      === v.seatA.zones.HAND.length,
    `${await page.locator('.manual-mulligan-card').count()}枚`);
  check('★選択の記法は既存の黄枠と同じで、初期状態では誰も選ばれていない(4-3)',
    (await page.locator('.manual-mulligan-picked').count()) === 0
      && (await page.locator('#mulligan-count').textContent()) === '0枚を戻す');

  await page.locator('.manual-mulligan-card').first().click();
  await page.waitForTimeout(30);
  check('★左クリックで選択できる(オーバーレイ内のローカル規約。4-3)',
    (await page.locator('.manual-mulligan-picked').count()) === 1
      && (await page.locator('#mulligan-count').textContent()) === '1枚を戻す',
    await page.locator('#mulligan-count').textContent());
  await page.locator('.manual-mulligan-card').first().click();
  await page.waitForTimeout(30);
  check('★もう一度の左クリックで選択を解除できる',
    (await page.locator('.manual-mulligan-picked').count()) === 0);

  await page.locator('.manual-mulligan-card').nth(1).click({ button: 'right' });
  await page.waitForTimeout(30);
  check('★右クリックはオーバーレイ内の拡大に割り当てる(4-3)',
    (await page.locator('#mulligan-preview .mcard').count()) === 1
      && (await page.locator('.manual-mulligan-picked').count()) === 0);
  check('★右クリックの拡大は右列の拡大パネルにも入る(閉じた後に残る)',
    (await zoomedImage(page)) !== null, String(await zoomedImage(page)));

  await page.locator('.manual-mulligan-card').first().click();
  await page.waitForTimeout(30);
  await clearSent(page);
  await page.locator('#mulligan-confirm').click();
  await page.waitForTimeout(40);
  msgs = boardMessages(await sent(page));
  check('★[確定] は mulligan を1件だけ送る(move の並びにしない。4-4)',
    msgs.length === 1 && msgs[0].destination.endsWith('/mulligan'), JSON.stringify(msgs));
  check('★mulligan には戻す手札と席だけを載せ、引く枚数は載せない(4-4・設計判断27)',
    msgs.length === 1 && msgs[0].body.seat === 'A'
      && Array.isArray(msgs[0].body.cardIds) && msgs[0].body.cardIds.length === 1
      && msgs[0].body.count === undefined && msgs[0].body.drawCount === undefined,
    JSON.stringify(msgs[0] && msgs[0].body));

  // 確定済みになったらオーバーレイを閉じ、相手を待つ表示に切り替わる
  v = versusView('A');
  v.start = startState({ phase: 'MULLIGAN', locking: true, firstSeat: 'A',
    mulliganSeats: ['A', 'B'], mulliganDone: ['A'], myMulliganSeats: [],
    waiting: 'マリガンの確定を待っています(席A: 確定済み / 席B: 選択中)' });
  await render(page, v);
  check('★確定するとオーバーレイが閉じ、待機表示に変わる(7-3)',
    (await page.locator('.manual-mulligan').count()) === 0
      && !(await page.locator('#start-banner').getAttribute('class')).includes('d-none'));
  check('★待機表示は「選択中 / 確定済み」だけで、枚数を出さない(P11)',
    !/\d+枚/.test(await page.locator('#start-banner-text').textContent()),
    await page.locator('#start-banner-text').textContent());

  // 28-5. ★開始中は盤面を操作できない(7-1)。★実マウスで確かめる
  v = baseView();
  v.start = startState({ phase: 'MULLIGAN', locking: true, firstSeat: 'A',
    mulliganSeats: ['A'], mulliganDone: ['A'], myMulliganSeats: [],
    waiting: 'マリガンの確定を待っています(席A: 確定済み)' });
  await render(page, v);
  await clearSent(page);
  await realDrag(page, '#hand-row .manual-hand-card',
    '#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face');
  msgs = boardMessages(await sent(page));
  check('★★開始シーケンス中は実マウスでドラッグしても何も送られない(7-1)',
    msgs.length === 0, JSON.stringify(msgs));
  await clearSent(page);
  await page.locator('#seat-self-minions .manual-tile-name').first()
    .click({ button: 'right' });
  await page.waitForTimeout(40);
  check('★開始中でもタップの右クリックはサーバが棄却する(送っても構わないが盤面は動かない)',
    true, '検証はサーバ側の JUnit が持つ(ManualPermissions.denyDuringStart)');

  // ★リセットだけは通る(7-2)。抜けられない画面を作らない
  await clearSent(page);
  page.once('dialog', (d) => d.accept());
  await page.locator('#start-banner .manual-start-reset').click();
  await page.waitForTimeout(60);
  msgs = boardMessages(await sent(page));
  check('★★開始中でも待機表示からリセットへ抜けられる(7-2)',
    msgs.some((m) => m.destination.endsWith('/reset')), JSON.stringify(msgs));

  // 28-6. 開始後(PLAYING)は元どおり操作できる
  v = baseView();
  v.start = startState({ phase: 'PLAYING', locking: false, firstSeat: 'A' });
  await render(page, v);
  check('PLAYING では開始まわりの画面が全部消える(1-4)',
    (await page.locator('.manual-mulligan').count()) === 0
      && (await page.locator('#start-banner').getAttribute('class')).includes('d-none')
      && (await page.locator('#start-method-modal').getAttribute('class')).includes('d-none')
      && (await page.locator('#btn-start').getAttribute('class')).includes('d-none'));
  await clearSent(page);
  await realDrag(page, '#hand-row .manual-hand-card',
    '#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face');
  msgs = boardMessages(await sent(page));
  // ★★22 2章の教訓: 「送られないこと」だけを確かめると、<b>何も起きなかった</b>ときと
  //   区別が付かない。同じ操作が PLAYING では通ることを対で確かめて初めて、
  //   1つ上の「開始中は送られない」が意味を持つ。
  check('★開始後は同じドラッグが通る(ロックが外れている)',
    msgs.length === 1 && msgs[0].destination.endsWith('/move')
      && msgs[0].body.toZone === 'TRASH', JSON.stringify(msgs));

  // ---- 29. 超ワイド画面(★21b の積み残し。相手上段の左右の離れ) ----
  const wide = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const wideErrors = [];
  wide.on('pageerror', (e) => wideErrors.push(String(e)));
  // ---- 26. カードフェイス(Batch 25) ----
  // 画像を廃し、ビューの項目+カード定義のテキストからフェイスを描く。
  // ★「ライブラリ未取得でも描ける」はここまでの全セクションが
  //   ライブラリ無しで走っていることが証明している。
  await render(page, baseView());
  check('★手札がテキストのカードフェイスで描かれ、印刷値が出る(25)',
    (await page.locator('.manual-hand-card .mcard.mcard-full').count()) === 2
      && (await page.locator('.manual-hand-card .mcard .mcard-atk').first().textContent()) === '⚔2'
      && (await page.locator('.manual-hand-card .mcard .mcard-hp').first().textContent()) === '♥3');
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    applyCardLibrary({ cards: [{ id: 'c-h1', imageId: 'img-h1', text: '検証用の効果テキスト' }] });
    // eslint-disable-next-line no-undef
    renderAll(latestView);
  });
  check('★カード定義の取得後は効果テキストがフェイスに載る(25)',
    (await page.locator('.manual-hand-card .mcard-text').first().textContent()) === '検証用の効果テキスト');
  await clearZoom(page);
  await page.locator('.manual-hand-card').first().click();
  await page.waitForTimeout(60);
  check('★拡大パネルの大フェイスにも効果テキストが出る(25)',
    (await page.locator('#zoom-panel .mcard-large .mcard-text').textContent()) === '検証用の効果テキスト');
  await page.evaluate(() => {
    // ★以降のセクションに影響を残さない(ライブラリ未取得状態へ戻す)
    // eslint-disable-next-line no-undef
    cardTextById = null;
    // eslint-disable-next-line no-undef
    cardTextByImage = null;
  });

  // ---- 28. 一人回しの席切替・タイルのフェイス化(25c) ----
  await render(page, baseView());
  check('★全公開部屋では着席者にも反転(操作席切替)ボタンが出る(25c)',
    !(await page.locator('#btn-flip').getAttribute('class')).includes('d-none')
      && (await page.locator('#btn-flip').textContent()).includes('操作: 席A')
      && (await page.locator('#btn-spectator-view').getAttribute('class')).includes('d-none'),
    await page.locator('#btn-flip').textContent());
  await page.locator('#btn-flip').click();
  await page.waitForTimeout(60);
  check('★切替で下段が席Bの完全なUIになり、宣言も席Bとして送る(25c)',
    (await page.locator('#seat-self-minions .minion-row').getAttribute('data-seat')) === 'B'
      // eslint-disable-next-line no-undef
      && (await page.evaluate(() => declareSeat)) === 'B'
      && (await page.locator('#hand-count-line').textContent()).includes('席B'));
  await page.locator('#btn-flip').click();
  await page.waitForTimeout(60);

  // 場のタイルのフェイス化(頭=コスト+名前 / 足=⚔・✎・♥ / 胴=効果テキスト)
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    applyCardLibrary({ cards: [{ id: 'c-f1', imageId: 'img-f1', text: 'タイル検証テキスト' }] });
    // eslint-disable-next-line no-undef
    renderAll(latestView);
  });
  const tileFoot = await page.locator('#seat-self-minions .manual-tile-face .mtf-foot').first();
  check('★場のミニオンがフェイス形式になり足に現在値が出る(25c)',
    (await page.locator('#seat-self-minions .manual-tile-face .mtf-cost').first().textContent()) === '3'
      && (await tileFoot.locator('.mtf-atk').textContent()) === '⚔2'
      && (await tileFoot.locator('.mtf-hp').textContent()) === '♥3'
      && (await tileFoot.locator('.mtf-edit').count()) === 1);
  check('★場のタイルにも効果テキストが載る(25c)',
    (await page.locator('#seat-self-minions .manual-tile-face .mtf-text').first().textContent())
      === 'タイル検証テキスト');

  // ---- ★Batch 34: 動的生成要素の title(レビュー A-3) ----
  // ★見るのは「付いているか」ではなく「<b>名前で呼べるか</b>」である。
  //   空でない title を数えるだけの判定は、`title=" "` でも通ってしまう。
  //   カード名・ゾーン名が実際に入っていることまで確かめる。
  // ★マナだけは baseView に無いので、この検証のためにマナ入りのビューを1回流す
  const titleView = baseView();
  titleView.seatA.zones.MANA = [card('tm1', 'タイトル用マナ')];
  syncCounts(titleView.seatA);
  await render(page, titleView);
  const titles = await page.evaluate(() => {
    const t = (sel) => {
      const el = document.querySelector(sel);
      return el ? (el.title || '') : null;
    };
    return {
      minion: t('#seat-self-minions .manual-tile'),
      hand: t('#hand-row .manual-hand-card'),
      mana: t('.mana-tile'),
      pile: t('#pile-grid .manual-pile[data-zone="TRASH"]'),
      leader: t('#pile-grid .manual-leader-tile'),
    };
  });
  // ★fixture のカード名(自席の場のミニオン)。名前で呼べることの証拠として使う
  const minionName = await page.locator('#seat-self-minions .manual-tile-name')
    .first().textContent();
  check('★動的生成された盤面の要素に title が付く(34・2章)',
    Object.values(titles).every((v) => typeof v === 'string' && v.length > 0),
    JSON.stringify(titles));
  check('★★title は名前で呼べる(カード名・ゾーン名が入っている)(34・2章)',
    titles.minion.startsWith(minionName)
      && titles.pile.startsWith('墓地')
      && titles.mana.includes('マナ')
      && titles.hand.length > 0,
    JSON.stringify(titles));
  // ★操作規約(左=見る / 右=動かす)は業界慣習と逆である。ヘルプを閉じた後にも
  //   参照できるよう、動かせる要素の title には操作が書かれていること
  check('★title に操作の書き方(左/右/ドラッグ)が入る(34・2章)',
    titles.minion.includes('左=') && titles.minion.includes('右=')
      && titles.minion.includes('ドラッグ='),
    titles.minion);
  // ★★文言は TITLE_HINTS 1箇所に集める(設計判断28)。
  //   生の日本語を title へ直接代入している箇所が残っていないことを見る
  const jsSrc = fs.readFileSync(
    path.join(RES, 'static/js/manual-battle.js'), 'utf8');
  const rawTitles = (jsSrc.match(/\.title = (?!tileTitle\()[^;\n]*/g) || [])
    .filter((line) => /[ぁ-んァ-ヶ一-龠]/.test(line));
  check('★★title の文言は tileTitle / TITLE_HINTS 経由で組み立てる(34・2章)',
    rawTitles.length === 0, rawTitles.join(' / '));

  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    cardTextById = null;
    // eslint-disable-next-line no-undef
    cardTextByImage = null;
  });

  // 入室ゲート: 全公開部屋は席の選択肢を1つに畳む(25c)
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    gateRoomType = 'OPEN';
    // eslint-disable-next-line no-undef
    applyGateSeats({ A: null, B: null }, { A: null, B: null }, true, null);
  });
  check('★全公開部屋のゲートは「席に着く」1つに畳まれる(25c)',
    (await page.evaluate(() => {
      const a = document.querySelector('#seat-gate-a') || document.querySelector('[id$="seat-gate-a"]');
      const b = document.querySelector('#seat-gate-b') || document.querySelector('[id$="seat-gate-b"]');
      return a && b && a.textContent.includes('両方を操作できます')
        && b.classList.contains('d-none');
    })) === true);
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    gateRoomType = 'VERSUS';
    // eslint-disable-next-line no-undef
    applyGateSeats({ A: null, B: null }, { A: null, B: null }, true, null);
  });
  check('★対戦部屋のゲートは従来どおり席A/Bを選べる(25c回帰)',
    (await page.evaluate(() => {
      const b = document.querySelector('#seat-gate-b') || document.querySelector('[id$="seat-gate-b"]');
      return b && !b.classList.contains('d-none') && b.textContent.includes('席Bに座る');
    })) === true);

  // =====================================================================
  // ★Batch 26: 盤面フェイスの仕上げ
  // =====================================================================

  // ---- 30. パイル面のドラッグ = 一番上の1枚(26 1章) ----
  // ★動いたのが「末尾」のカードであることを instanceId で確かめる。
  //   公開パイルの最上段は末尾であり、山札(先頭)とは逆である。ここを取り違えると
  //   「見えているカードと違う1枚が動く」というワーストの壊れ方をするため、
  //   「move が飛んだ」だけでは検証にならない。
  const pileView = baseView();
  pileView.seatA.zones.TRASH = [card('t1', '墓地の下'), card('t2', '墓地の一番上')];
  pileView.seatA.zones.LOST = [card('l1', '消滅の下'), card('l2', '消滅の一番上')];
  pileView.seatA.zones.PRIVATE = [card('p1', '確認の下'), card('p2', '確認の一番上')];
  syncCounts(pileView.seatA);

  for (const [zone, expected, label] of [
    ['TRASH', 't2', '墓地'], ['LOST', 'l2', '消滅'], ['PRIVATE', 'p2', '確認'],
  ]) {
    await render(page, pileView);
    await clearSent(page);
    const target = zone === 'LOST' ? 'TRASH' : 'LOST';
    await realDrag(page, `#pile-grid .manual-pile[data-zone="${zone}"] .manual-pile-face`,
      `#pile-grid .manual-pile[data-zone="${target}"] .manual-pile-face`);
    const moved = (await sent(page)).find((m) => m.destination.endsWith('/move'));
    check(`★${label}パイルのドラッグで末尾(=見えている1枚)が動く(26 1章)`,
      !!moved && moved.body.cardIds.length === 1 && moved.body.cardIds[0] === expected
        && moved.body.toZone === target,
      JSON.stringify(moved && moved.body));
  }

  await render(page, pileView);
  await clearSent(page);
  await realDrag(page, '#pile-grid .manual-pile[data-zone="TABOO"] .manual-pile-face',
    '#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face');
  check('★禁忌パイルはドラッグの対象外のまま(26 1章。裏面しか無く「見えている1枚」が無い)',
    (await sent(page)).filter((m) => m.destination.endsWith('/move')).length === 0
      && (await page.locator('#pile-grid .manual-pile[data-zone="TABOO"]')
        .evaluate((el) => el.draggable)) === false);

  // ---- 31. 自席リーダーのフェイス化(26 2章) ----
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    applyCardLibrary({ cards: [{ id: 'c-A-leader', imageId: 'img-A-leader',
      text: 'リーダー起動能力の検証テキスト' }] });
    // eslint-disable-next-line no-undef
    renderAll(latestView);
  });
  check('★自席リーダーが3段のフェイスになり効果テキストが載る(26 2章)',
    (await page.locator('#pile-grid .manual-leader-tile .mtf-text').textContent())
      === 'リーダー起動能力の検証テキスト'
      && (await page.locator('#pile-grid .manual-leader-tile .mtf-crown').count()) === 1
      && (await page.locator('#pile-grid .manual-leader-tile .mtf-foot .manual-tile-stats')
        .textContent()).includes('LP 20'));
  check('★相手上段のリーダーはテキストなしのまま(26 2章・マスター裁定)',
    (await page.locator('#seat-opponent-top .manual-leader-tile .mtf-text').count()) === 0
      && (await page.locator('#seat-opponent-top .manual-leader-tile .manual-tile-name')
        .count()) === 1);
  const leaderFaceBox = await page.locator('#pile-grid .manual-leader-tile').boundingBox();
  const deckPileBox = await page.locator('#pile-grid .manual-pile[data-zone="DECK"]').boundingBox();
  check('★自席リーダーの枠が下へ広がっても、山札パイルの行の高さを超えない(26 2章)',
    leaderFaceBox.height >= 140
      && leaderFaceBox.y + leaderFaceBox.height <= deckPileBox.y + deckPileBox.height,
    `leader.h=${leaderFaceBox.height} leader下端=${leaderFaceBox.y + leaderFaceBox.height} `
      + `deck下端=${deckPileBox.y + deckPileBox.height}`);

  // ---- 32. パイル面・ウェポン枠のテキスト(26 3章) ----
  const textView = baseView();
  textView.seatA.zones.TRASH = [card('t1', '墓地の一番上')];
  textView.seatA.zones.WEAPON = [card('w1', 'テストウェポン',
    { type: 'WEAPON', hp: null, printedHp: null })];
  syncCounts(textView.seatA);
  await render(page, textView);
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    applyCardLibrary({ cards: [
      { id: 'c-t1', imageId: 'img-t1', text: '墓地最上段のテキスト' },
      { id: 'c-w1', imageId: 'img-w1', text: 'ウェポンのテキスト' },
    ] });
    // eslint-disable-next-line no-undef
    renderAll(latestView);
  });
  check('★パイルの最上段とウェポン枠に効果テキストが出る(26 3章)',
    (await page.locator('#pile-grid .manual-pile[data-zone="TRASH"] .mcard-text').textContent())
      === '墓地最上段のテキスト'
      && (await page.locator('#pile-grid .manual-weapon-slot .mcard-text').textContent())
        === 'ウェポンのテキスト');
  // ★足の HP が枚数バッジの下に潜っていないこと(25 から残っていた重なり)
  const pileFoot = await page.evaluate(() => {
    const face = document.querySelector('#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face');
    const hp = face.querySelector('.mcard-hp').getBoundingClientRect();
    const badge = face.querySelector('.manual-pile-count').getBoundingClientRect();
    return { hpRight: Math.round(hp.right), badgeLeft: Math.round(badge.left) };
  });
  check('★パイル面の HP が枚数バッジに潜らない(26 3章)',
    pileFoot.hpRight <= pileFoot.badgeLeft, JSON.stringify(pileFoot));

  // ---- 33. 数値表示の簡素化(26 5章) ----
  // ★ATK と HP の両方が印刷値と違う状態を作る。差分チップ時代はここで足が溢れた。
  const statChangedView = baseView();
  statChangedView.seatA.zones.FIELD = [card('f1', '強化された場のミニオン',
    { attack: 12, printedAttack: 2, hp: 1, printedHp: 3 })];
  syncCounts(statChangedView.seatA);
  await render(page, statChangedView);
  const footFit = await page.evaluate(() => {
    const foot = document.querySelector('#seat-self-minions .manual-tile-face .mtf-foot');
    return { sw: foot.scrollWidth, cw: foot.clientWidth, text: foot.textContent };
  });
  check('★ATK・HP の両方を変えてもフェイスタイルの足が1行に収まる(26 5章)',
    footFit.sw <= footFit.cw, JSON.stringify(footFit));
  const changedMarks = await page.evaluate(() => {
    const marks = [...document.querySelectorAll('#seat-self-minions .manual-stat-changed')];
    return {
      count: marks.length,
      titles: marks.map((m) => m.title),
      chips: document.querySelectorAll('.manual-stat-chip').length,
    };
  });
  check('★変更された数値に下線クラスと title(印刷値)が付き、差分チップは消えている(26 5章)',
    changedMarks.count === 2 && changedMarks.chips === 0
      && changedMarks.titles.includes('印刷値 2') && changedMarks.titles.includes('印刷値 3'),
    JSON.stringify(changedMarks));

  // ---- 34. タップ表現(26 4章。回転の廃止) ----
  const tapView = baseView();
  tapView.seatA.zones.FIELD = [card('f1', 'タップ中の場', { tapped: true })];
  tapView.seatA.zones.MANA = [card('m1', 'タップ中のマナ', { tapped: true })];
  syncCounts(tapView.seatA);
  await render(page, tapView);
  const tapLook = await page.evaluate(() => {
    const tile = document.querySelector('#seat-self-minions .manual-tile-tapped');
    const mana = document.querySelector('#seat-self-mana-row .mana-tile.tapped');
    return {
      tileTransform: getComputedStyle(tile).transform,
      manaTransform: getComputedStyle(mana).transform,
      tileFilter: getComputedStyle(tile).filter,
      badge: tile.querySelector('.manual-tapped-badge')
        ? tile.querySelector('.manual-tapped-badge').textContent : null,
      manaBadge: mana.querySelectorAll('.manual-tapped-badge').length,
    };
  });
  // ★Batch 30 でマナだけ回転へ戻した(マスター指示)。裏向きマナは暗い絵であり、
  //   減光では「これはタップ済か」が読み取れなかった。回転は形が変わるので一目で分かる。
  //   ★場のタイルは 26 のまま減光+バッジである(縦長でテキストが載っており、
  //   回転すると読めなくなる)。この非対称は意図であり、検証で固定しておく。
  check('★場のタイルのタップは回転ではなく減光+バッジのまま(26 4章)',
    tapLook.tileTransform === 'none' && tapLook.badge === 'タップ'
      && tapLook.tileFilter.includes('brightness'),
    JSON.stringify(tapLook));
  check('★マナのタップは回転で表す(30・マスター指示。バッジは持たない)',
    tapLook.manaTransform !== 'none' && tapLook.manaBadge === 0,
    JSON.stringify(tapLook));

  // ---- 35. ★不具合修正: スクロールしても手札のサイズが変わらない(26 4章) ----
  // ★これが 25b から入っていた不具合の再現である。handCardMaxWidth がビューポート相対の
  //   top で残り高さを測っていたため、スクロール位置によって手札の幅が変わり、
  //   ビュー更新を伴う最頻の操作(タップ/アンタップ)で「サイズが崩れる」と観測された。
  //   ★従来の検証がスクロール0でしか走っていなかったため検出できなかった。
  const short = await browser.newPage({ viewport: { width: 1280, height: 660 } });
  const shortErrors = [];
  short.on('pageerror', (e) => shortErrors.push(String(e)));
  await short.goto(`http://127.0.0.1:${port}/harness.html`);
  await short.waitForTimeout(200);
  await render(short, baseView());
  const widthAtTop = await short.evaluate(() =>
    document.querySelector('#hand-row .manual-hand-card').getBoundingClientRect().width);
  await short.evaluate(() => window.scrollTo(0, 250));
  await short.waitForTimeout(60);
  await render(short, baseView());   // タップ等と同じ「再描画」を起こす
  const widthAfterScroll = await short.evaluate(() =>
    document.querySelector('#hand-row .manual-hand-card').getBoundingClientRect().width);
  check('★スクロールした状態で再描画しても手札の幅が変わらない(26 4章・不具合修正)',
    Math.abs(widthAtTop - widthAfterScroll) < 0.5,
    `top=${widthAtTop} scrolled=${widthAfterScroll}`);
  check('狭いビューポートでもJSエラーが出ない(26)', shortErrors.length === 0,
    shortErrors.join(' | '));
  await short.close();

  // =====================================================================
  // ★Batch 27: 進化スタックが画面から消えない(不具合修正の安全網)
  // =====================================================================
  //
  // ★本体の修正はサーバ側である(FIELD 以外へ移すとき束を解体する。
  //   ManualOperationService.unstack / ManualOperationTest)。ここで検証するのは
  //   クライアント側の安全網であり、「万一 FIELD 以外に束が残っても、素材へ到達する
  //   入口が必ずある」ことを固定する。不具合の本質は
  //   「カードが状態には在るのに画面のどこからも触れない」だったため、
  //   サーバを直しただけでは同じ壊れ方の再発を防げない。

  const stackView = baseView();
  const stackedTop = card('ev1', '進化ミニオン');
  stackedTop.stackSize = 3;
  stackedTop.materials = [card('sm1', '素材1'), card('sm2', '素材2')];
  stackView.seatA.zones.TRASH = [stackedTop];
  syncCounts(stackView.seatA);
  await render(page, stackView);
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    openZoneBand('A', 'TRASH');
  });
  await page.waitForTimeout(80);
  check('★墓地の帯でも束のカードに +n バッジが出る(27・安全網)',
    (await page.locator('.manual-band-card .manual-band-badge').count()) === 1
      && (await page.locator('.manual-band-card .manual-band-badge').textContent()) === '+2');
  await page.locator('.manual-band-card .manual-band-badge').click();
  await page.waitForTimeout(80);
  check('★FIELD 以外にある束でも進化スタックの帯を開ける(27・findStackTop)',
    (await page.locator('.manual-band-header span').textContent()).includes('進化スタック')
      && (await page.locator('.manual-band-card').count()) === 2
      && (await page.locator('.manual-band-card').first().getAttribute('data-instance-id'))
        === 'sm1');
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    closeOverlay();
  });
  await page.waitForTimeout(60);

  await page.evaluate(() => {
    // ★以降のセクションへライブラリの状態を残さない(25 と同じ後始末)
    // eslint-disable-next-line no-undef
    cardTextById = null;
    // eslint-disable-next-line no-undef
    cardTextByImage = null;
  });

  // =====================================================================
  // ★Batch 28: 接続の安定化 — 「落ちたように見える」をなくす
  // =====================================================================
  //
  // ★ハートビートそのものはサーバ設定(WebSocketConfig)であり、ここでは検証できない
  //   (ハーネスは StompJs をスタブに差し替えており、実接続を張らない)。
  //   ここで固定するのは<b>クライアントの見え方</b>である。
  //   27 まではサーバから「この部屋に入室していません」が返ると無言で
  //   location.reload() していた。対戦中に盤面が突然消えるため、人間には
  //   「ブラウザが落ちた」としか見えない。それが起きないことを検証する。

  await render(page, baseView());
  check('切断すると接続状態が警告色になる(28)',
    (await page.evaluate(() => {
      // eslint-disable-next-line no-undef
      setConnectionStatus('切断(再接続中...)', true);
      const el = document.getElementById('connection-status');
      return el.classList.contains('text-danger') && !el.classList.contains('text-muted');
    })) === true);
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    setConnectionStatus('接続済み');
  });

  await clearSent(page);
  const roomLost = await page.evaluate(() => {
    // ★reloadPage を差し替えてから呼ぶ。差し替えられること自体が
    //   「無言でリロードしない」の検証になっている
    window.__reloaded = 0;
    // eslint-disable-next-line no-undef
    reloadPage = () => { window.__reloaded++; };
    // eslint-disable-next-line no-undef
    showRoomLostFatal();
    const gate = document.getElementById('seat-gate');
    const err = document.getElementById('seat-gate-error');
    const buttons = document.getElementById('seat-gate-buttons');
    return {
      gateShown: gate && !gate.classList.contains('d-none'),
      message: err ? err.textContent : '',
      buttonLabel: buttons ? buttons.textContent.trim() : '',
      reloadedImmediately: window.__reloaded,
      statusWarn: document.getElementById('connection-status')
        .classList.contains('text-danger'),
    };
  });
  check('★部屋が消えたとき、無言でリロードせず理由を画面に出す(28)',
    roomLost.reloadedImmediately === 0 && roomLost.gateShown === true
      && roomLost.message.includes('サーバ上に存在しません')
      && roomLost.message.includes('復元できません')
      && roomLost.buttonLabel === '入り直す' && roomLost.statusWarn === true,
    JSON.stringify(roomLost));

  await page.locator('#seat-gate-buttons button').click();
  await page.waitForTimeout(60);
  check('★「入り直す」は席選択として送信されない(28・委譲リスナーへの伝播を止める)',
    (await page.evaluate(() => window.__reloaded)) === 1
      && (await sent(page)).filter((m) => m.destination.endsWith('/seat')).length === 0,
    JSON.stringify(await sent(page)));

  await page.reload();
  await page.waitForTimeout(200);

  // =====================================================================
  // ★Batch 29: 配信の軽量化・描画の軽量化
  // =====================================================================
  //
  // ★サーバ側(ログ末尾60行・山札は最上段1枚だけ)は Java の変更であり、
  //   ここで固定するのは<b>クライアントがその形の配信に耐えること</b>である。
  //   すなわち「配列の長さを枚数として使わない」「中身は別の口から取る」。

  // ---- 36. 山札の枚数は counts から取る(配列の長さではない) ----
  const thinView = baseView();
  // ★サーバの新しい振る舞いを再現する: 中身は最上段1枚だけ、枚数は counts が持つ
  thinView.seatA.zones.DECK = [card('d1', '山札の一番上')];
  thinView.seatA.counts.DECK = 30;
  await render(page, thinView);
  check('★山札パイルの枚数が counts の値になる(29・配列の長さではない)',
    (await page.locator('#pile-grid .manual-pile[data-zone="DECK"] .manual-pile-count')
      .textContent()) === '30');
  await clearSent(page);
  await realDrag(page, '#pile-grid .manual-pile[data-zone="DECK"] .manual-pile-face',
    '#pile-grid .manual-pile[data-zone="TRASH"] .manual-pile-face');
  const thinDrag = (await sent(page)).find((m) => m.destination.endsWith('/move'));
  check('★中身が1枚しか届かなくても山札のドラッグは一番上を動かす(29・20a回帰)',
    !!thinDrag && thinDrag.body.cardIds[0] === 'd1', JSON.stringify(thinDrag && thinDrag.body));

  // ---- 37. 山札の全面表示は別の口から中身を取る ----
  ZONE_RESPONSE.status = 200;
  ZONE_RESPONSE.delayMs = 0;
  ZONE_RESPONSE.body = { seat: 'A', zone: 'DECK',
    cards: [card('x1', '取得した1枚目'), card('x2', '取得した2枚目'), card('x3', '取得した3枚目')] };
  await render(page, thinView);
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    openDeckFullscreen('A');
  });
  await page.waitForTimeout(250);
  check('★山札の全面表示が別の口から取った中身を並べる(29)',
    (await page.locator('.manual-deck-list .manual-deck-row').count()) === 3
      && (await page.locator('.manual-fullscreen-header span').textContent()).includes('30枚'),
    await page.locator('.manual-fullscreen-header span').textContent());

  // 取得が遅いあいだは「読み込み中」であり、「山札が空です」にはしない
  ZONE_RESPONSE.delayMs = 400;
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    closeOverlay();
    // eslint-disable-next-line no-undef
    openDeckFullscreen('A');
  });
  await page.waitForTimeout(80);
  check('★取得中は「読み込み中」を出す(29・空の山札と区別する)',
    (await page.locator('#deck-fullscreen-status').textContent()).includes('読み込んでいます'));
  await page.waitForTimeout(600);
  ZONE_RESPONSE.delayMs = 0;

  // 取得に失敗したらそう言う(黙って空にしない)
  ZONE_RESPONSE.status = 400;
  ZONE_RESPONSE.message = 'このゾーンは公開されていません: DECK';
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    closeOverlay();
    // eslint-disable-next-line no-undef
    openDeckFullscreen('A');
  });
  await page.waitForTimeout(250);
  check('★取得に失敗したら理由を出す(29・黙って空にしない)',
    (await page.locator('#deck-fullscreen-status').textContent()).includes('公開されていません')
      && (await page.locator('.manual-deck-list .manual-deck-row').count()) === 0);
  // ★400 を<b>意図的に</b>返させたので、ブラウザが出す "Failed to load resource" は
  //   期待どおりの記録である。末尾の「JSエラーが出ない」から取り除く
  //   (無条件に無視すると、本物の通信エラーまで見逃す)。
  {
    const expected = errors.filter((e) => e.includes('400'));
    check('★取得失敗はブラウザのリソースエラーとしてだけ記録される(29)',
      expected.length === errors.length && expected.length > 0,
      errors.join(' | '));
    errors.length = 0;
  }
  ZONE_RESPONSE.status = 200;
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    closeOverlay();
  });

  // ---- 38. ログは差分追記する(全消し再構築をしない) ----
  const logView = baseView();
  logView.log = [];
  for (let i = 1; i <= 5; i++) {
    logView.log.push({ seq: i, time: '10:00:0' + i, text: 'ログ' + i });
  }
  logView.logTotal = 5;
  await render(page, logView);
  await page.evaluate(() => {
    // ★既に描いてある行に印を付ける。作り直されたら印が消える
    for (const line of document.querySelectorAll('#log-box div[data-seq]')) {
      line.dataset.mark = '1';
    }
  });
  const grown = baseView();
  grown.log = logView.log.concat([{ seq: 6, time: '10:00:06', text: 'ログ6' }]);
  grown.logTotal = 6;
  await render(page, grown);
  const logDom = await page.evaluate(() => ({
    lines: document.querySelectorAll('#log-box div[data-seq]').length,
    kept: document.querySelectorAll('#log-box div[data-mark]').length,
    lastText: document.querySelector('#log-box div[data-seq="6"]').textContent,
  }));
  check('★ログは既存行を作り直さず差分だけ足す(29)',
    logDom.lines === 6 && logDom.kept === 5 && logDom.lastText.includes('ログ6'),
    JSON.stringify(logDom));

  // 取りこぼし(seq が飛ぶ)は作り直す。飛んだまま追記すると行が抜ける
  const gapped = baseView();
  gapped.log = [{ seq: 20, time: '10:00:20', text: 'ログ20' }];
  gapped.logTotal = 20;
  await render(page, gapped);
  const gapDom = await page.evaluate(() => ({
    lines: document.querySelectorAll('#log-box div[data-seq]').length,
    kept: document.querySelectorAll('#log-box div[data-mark]').length,
    note: document.getElementById('log-omitted-note')
      ? document.getElementById('log-omitted-note').textContent : null,
  }));
  check('★seq が飛んだら作り直し、省略された行数を案内する(29)',
    gapDom.lines === 1 && gapDom.kept === 0 && gapDom.note !== null
      && gapDom.note.includes('19 行'),
    JSON.stringify(gapDom));

  // 省略が無ければ案内は出ない
  const noGap = baseView();
  noGap.log = [{ seq: 1, time: '10:00:01', text: 'ログ1' }];
  noGap.logTotal = 1;
  await render(page, noGap);
  check('★省略が無いときは案内を出さない(29)',
    (await page.locator('#log-omitted-note').count()) === 0);

  // =====================================================================
  // ★Batch 30: UI の詰め(マスター指摘9点)
  // =====================================================================

  // ---- 39. 薄すぎる文字を作らない ----
  // ★個別の色を1つずつ確かめるのではなく、「盤面のラベルはすべて 4.5:1 以上」を
  //   1本の条件で押さえる。色を足すたびに検証項目を足す必要が無くなり、
  //   薄い文字が<b>構造的に</b>入り込めなくなる。
  const contrastView = baseView();
  contrastView.shared = { PLAY: [card('p1', 'プレイ中のカード')], REVEAL: [] };
  await render(page, contrastView);
  await page.evaluate(() => {
    // ★★Batch 31: 背景の既定を <b>body の実際の背景色</b>にする。
    //   30 はここを白決め打ちにしていた。実ページは <body class="bg-dark text-light">
    //   の黒背景であり、そのため「黒背景で 1.12:1(ほぼ不可視)」の文字を
    //   合格と報告していた。ハーネス側も背景を再現するよう直してある。
    const parse = (c) => {
      const m = (c || '').match(/[\d.]+/g);
      if (!m) return [0, 0, 0, 1];
      const v = m.map(Number);
      // ★color(srgb r g b / a) 形式は 0〜1 で来る。0〜255 に揃える
      const scale = /^color\(/.test(c) ? 255 : 1;
      return [v[0] * scale, v[1] * scale, v[2] * scale,
        v.length > 3 ? v[3] : 1];
    };
    const bodyBg = parse(getComputedStyle(document.body).backgroundColor);
    const eff = (el) => {
      let n = el;
      while (n) {
        const raw = getComputedStyle(n).backgroundColor;
        if (raw && raw !== 'rgba(0, 0, 0, 0)' && raw !== 'transparent') {
          const c = parse(raw);
          if (c[3] >= 0.98) return c;
          // 半透明は親の上に重ねる(再帰)
          const p = n.parentElement ? eff(n.parentElement) : bodyBg;
          const a = c[3];
          return [c[0] * a + p[0] * (1 - a), c[1] * a + p[1] * (1 - a), c[2] * a + p[2] * (1 - a), 1];
        }
        n = n.parentElement;
      }
      return bodyBg;
    };
    const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    const L = (v) => 0.2126 * lin(v[0]) + 0.7152 * lin(v[1]) + 0.0722 * lin(v[2]);
    // ★対象は「盤面の背景の上に直に載るラベル」に限る。カードのフェイスは
    //   グラデーションの上に載っており、backgroundColor から背景を辿れないため
    //   機械判定の対象にしない(判定できないものを判定したふりにしない)。
    const targets = [
      '#hand-count-line', '#mana-row-head', '.mana-strip-label', '.manual-center-label',
      '.zone-drop-mini', '.manual-pile-label', '.manual-opp-label', '.manual-untap-note',
      '.manual-untap-btn', '.manual-pile-blank', '.manual-weapon-slot-used',
      '.manual-weapon-slot-atk', '.manual-center-send button',
      // ★★Batch 33: 切断オーバーレイ・接続の帯・コピーボタンも<b>同じ1本の条件</b>で見る。
      //   30 の「盤面のラベルは1本の条件で判定」に新しい文字を足したら必ずここへ足すこと。
      //   ★これらは表示中でなくても判定できる(色も背景も d-none とは無関係に解決される)。
      '#manual-offline .manual-offline-title', '#manual-offline p', '#manual-offline button',
      '#manual-conn-bar', '.manual-copy-btn',
      // ★Batch 34 hotfix: モーダルの × も同じ1本の条件で見る
      '.info-modal-x',
      // ★Batch 35: ログの決着行。★半透明(log-box)の上にさらに半透明を重ねている
      '.manual-log-decisive',
    ].map((s) => '#manual-root ' + s)
      // ★★Batch 32a: fx層のラベル(LPポップ)も<b>同じ1本の条件</b>で判定する。
      //   fx層は position: fixed で body 直下にあり #manual-root の中に無いため、
      //   ここで明示的に足さないと判定の網から静かに漏れる(設計書 2-5)。
      // ★Batch 35: 決着の帯も同じ1本の条件で見る(fx層に文字を足したら必ずここへ)。
      //   32b のターン帯はここに居た。退役に伴い、座席ごと勝敗の帯へ引き渡している
      .concat(['#manual-fx-layer .manual-fx-lp', '#manual-fx-layer .manual-fx-declare']);
    window.__contrastAudit = () => {
      const out = [];
      for (const sel of targets) {
        for (const el of document.querySelectorAll(sel)) {
          const t = (el.textContent || '').trim();
          if (!t) continue;
          const m = parse(getComputedStyle(el).color);
          const bg = eff(el);
          const ratio = (Math.max(L(m), L(bg)) + 0.05) / (Math.min(L(m), L(bg)) + 0.05);
          if (ratio < 4.5) {
            out.push({ sel, text: t.slice(0, 14), ratio: Math.round(ratio * 100) / 100 });
          }
        }
      }
      return out;
    };
  });
  check('★盤面のラベルはすべてコントラスト比 4.5:1 以上(31: 実ページと同じ黒背景で判定)',
    (await page.evaluate(() => window.__contrastAudit())).length === 0,
    JSON.stringify(await page.evaluate(() => window.__contrastAudit())));
  // ★★判定そのものが効いていることを確かめる。
  //   30 はこの確認をしていなかった。背景を白と決め打ちしていたため、
  //   黒背景では 1.12:1(ほぼ不可視)の文字を合格と報告し続けていた
  //   ——「常に通る飾りの検証」の実例である。22/23 の教訓がここでも効いている。
  const detected = await page.evaluate(() => {
    const el = document.getElementById('hand-count-line');
    const before = el.getAttribute('style') || '';
    el.style.setProperty('color', '#343a40', 'important');   // 30 までの色
    const found = window.__contrastAudit();
    el.setAttribute('style', before);
    return found;
  });
  check('★黒背景の上の暗い文字を「読めない」と検出できる(31・検出器が生きている確認)',
    detected.some((f) => f.sel === '#manual-root #hand-count-line' && f.ratio < 2),
    JSON.stringify(detected));

  // ---- 40. マナの回転は隣に食い込まない ----
  const manaRotView = baseView();
  manaRotView.seatA.zones.MANA = [];
  for (let i = 0; i < 8; i++) {
    manaRotView.seatA.zones.MANA.push(
      card('mn' + i, 'マナ' + i, { tapped: i % 2 === 0, faceDown: i >= 5 }));
  }
  syncCounts(manaRotView.seatA);
  await render(page, manaRotView);
  const manaLayout = await page.evaluate(() => {
    const tiles = [...document.querySelectorAll('#seat-self-mana-row .mana-tile')]
      .map((e) => { const r = e.getBoundingClientRect(); return { x: r.x, right: r.right }; });
    let overlaps = 0;
    for (let i = 0; i < tiles.length - 1; i++) {
      if (tiles[i].right > tiles[i + 1].x + 1) overlaps++;
    }
    return { count: tiles.length, overlaps };
  });
  check('★回転したマナが隣のタイルへ食い込まない(30・幅を確保している)',
    manaLayout.count === 8 && manaLayout.overlaps === 0, JSON.stringify(manaLayout));

  // ---- 41. 墓地の帯にタイプ別の内訳を出す ----
  const trashView = baseView();
  trashView.seatA.zones.TRASH = [
    card('tm1', '墓地ミニオン'), card('tm2', '墓地進化', { type: 'EVOLUTION' }),
    card('ts1', '墓地スペル', { type: 'SPELL' }), card('tw1', '墓地ウェポン', { type: 'WEAPON' }),
  ];
  syncCounts(trashView.seatA);
  await render(page, trashView);
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    openZoneBand('A', 'TRASH');
  });
  await page.waitForTimeout(80);
  check('★墓地の帯に種別ごとの枚数が出る(30・進化はミニオンに合算)',
    (await page.locator('.manual-band-breakdown').textContent())
      === 'ミニオン 2 / スペル 1 / ウェポン 1',
    await page.locator('.manual-band-breakdown').textContent());
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    closeOverlay();
  });

  // ---- 42. プレイ中のカードを墓地/消滅へ送るボタン ----
  const playView = baseView();
  playView.shared = { PLAY: [card('sp1', '使ったスペル', { type: 'SPELL', placedBySeat: 'B' })],
    REVEAL: [] };
  await render(page, playView);
  await clearSent(page);
  await page.locator('.manual-center-send .manual-send-trash').click();
  const toTrash = (await sent(page)).find((m) => m.destination.endsWith('/move'));
  check('★プレイ中の「墓地へ」は置いた席の墓地へ送る(30)',
    !!toTrash && toTrash.body.cardIds[0] === 'sp1' && toTrash.body.toZone === 'TRASH'
      && toTrash.body.toSeat === 'B', JSON.stringify(toTrash && toTrash.body));
  await clearSent(page);
  await page.locator('.manual-center-send .manual-send-lost').click();
  const toLost = (await sent(page)).find((m) => m.destination.endsWith('/move'));
  check('★「消滅へ」も並んでいる(30・行き先はアプリが決めない)',
    !!toLost && toLost.body.toZone === 'LOST' && toLost.body.toSeat === 'B',
    JSON.stringify(toLost && toLost.body));
  // ★ドラッグは残っている(ボタンは選択肢を1つ増やしただけである)
  await clearSent(page);
  await realDrag(page, '.manual-center-row .manual-hand-card', '#hand-row .hand-row');
  check('★プレイ中からのドラッグは従来どおり使える(30回帰)',
    (await sent(page)).filter((m) => m.destination.endsWith('/move')).length === 1);

  // ---- 43. リーダーのLPを盤面から直接増減する ----
  await render(page, baseView());
  await clearSent(page);
  await page.locator('#pile-grid .manual-lp-step').first().click();
  await page.waitForTimeout(40);
  await page.locator('#pile-grid .manual-lp-step').last().click();
  const lpMsgs = (await sent(page)).filter((m) => m.destination.endsWith('/lp'));
  check('★リーダータイルの − / ＋ が LP を1ずつ増減する(30)',
    lpMsgs.length === 2 && lpMsgs[0].body.delta === -1 && lpMsgs[1].body.delta === 1
      && lpMsgs.every((m) => m.body.value === undefined), JSON.stringify(lpMsgs));
  check('★LPの増減ボタンはモーダルを開かない(30・専用ボタンは規約の外)',
    (await page.locator('#lp-modal').getAttribute('class')).includes('d-none'));

  // ---- 44. すべてアンタップ ----
  const untapView = baseView();
  untapView.seatA.zones.MANA = [card('um1', 'マナ', { tapped: true }),
    card('um2', 'マナ2', { tapped: false })];
  untapView.seatA.zones.FIELD = [card('uf1', '場', { tapped: true })];
  untapView.seatA.zones.WEAPON = [card('uw1', 'ウェポン', { type: 'WEAPON', used: true })];
  untapView.seatA.leader.tapped = true;
  syncCounts(untapView.seatA);
  await render(page, untapView);
  await clearSent(page);
  await page.locator('.manual-untap-btn').click();
  await page.waitForTimeout(60);
  const untapMsgs = await sent(page);
  const tapMsg = untapMsgs.find((m) => m.destination.endsWith('/tap'));
  const usedMsg = untapMsgs.find((m) => m.destination.endsWith('/used'));
  check('★すべてアンタップがマナ・場・リーダーを value:false で戻す(30)',
    !!tapMsg && tapMsg.body.value === false
      && tapMsg.body.cardIds.includes('um1') && tapMsg.body.cardIds.includes('uf1')
      && tapMsg.body.cardIds.includes('A-leader')
      && !tapMsg.body.cardIds.includes('um2'),
    JSON.stringify(tapMsg && tapMsg.body));
  check('★ウェポンの使用済も戻す(30・マスター指示)',
    !!usedMsg && usedMsg.body.value === false && usedMsg.body.cardIds[0] === 'uw1',
    JSON.stringify(usedMsg && usedMsg.body));
  const untapBox = await page.locator('.manual-untap-slot').boundingBox();
  const privateBox = await page.locator('#pile-grid .manual-pile[data-zone="PRIVATE"]')
    .boundingBox();
  check('★アンタップボタンは確認パイルの真下にある(30・マスター指示)',
    Math.abs(untapBox.x - privateBox.x) < 2 && untapBox.y > privateBox.y,
    `untap=(${Math.round(untapBox.x)},${Math.round(untapBox.y)}) `
      + `private=(${Math.round(privateBox.x)},${Math.round(privateBox.y)})`);

  // ---- 45. ログのカード名がリンクになる ----
  const logCardView = baseView();
  logCardView.log = [{ seq: 1, time: '10:00:00', text: 'あかり が 《検証用のカード》 を 場 へ移動した' }];
  logCardView.logTotal = 1;
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    applyCardLibrary({ cards: [{ id: 'c-log', name: '検証用のカード', imageId: 'img-log',
      civilization: 'WATER', type: 'MINION', cost: 4, attack: 1, hp: 2, text: 'ログ用テキスト' }] });
  });
  await render(page, logCardView);
  check('★ログのカード名がリンクになる(30)',
    (await page.locator('#log-box .manual-log-card').count()) === 1
      && (await page.locator('#log-box .manual-log-card').textContent()) === '《検証用のカード》');
  await clearZoom(page);
  await page.locator('#log-box .manual-log-card').click();
  await page.waitForTimeout(60);
  check('★ログのカード名を押すと拡大パネルに出る(30)',
    (await page.locator('#zoom-panel .mcard-large .mcard-name').textContent()) === '検証用のカード'
      && (await page.locator('#zoom-panel .mcard-large .mcard-text').textContent()) === 'ログ用テキスト');
  // 定義に無い名前はただの文字のまま(押せそうで押せないものを作らない)
  const unknownLog = baseView();
  unknownLog.log = [{ seq: 2, time: '10:00:01', text: 'あかり が 《台帳に無いカード》 を 場 へ移動した' }];
  unknownLog.logTotal = 2;
  await render(page, unknownLog);
  check('★定義に無い名前はリンクにしない(30)',
    (await page.locator('#log-box div[data-seq="2"] .manual-log-card').count()) === 0);
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    cardTextById = null;
    // eslint-disable-next-line no-undef
    cardTextByImage = null;
    // eslint-disable-next-line no-undef
    cardByNameMap = null;
  });

  // =====================================================================
  // ★Batch 32a: 盤面演出(fx層)。設計書 notes/batch32-effects-design.md 4章
  //
  // ★演出はビューの差分から出る。したがって検証も<b>連続する2つのビューを流す</b>形で行う
  //   (applyView が唯一の入口。render() は renderAll を直接呼ぶので差分を作らない)。
  // =====================================================================

  /** 配信1件を反映する(実経路と同じ入口)。★render() と違い prevView が更新される */
  const deliver = async (target, view) => {
    await target.evaluate((v) => {
      // eslint-disable-next-line no-undef
      applyView(v);
    }, view);
  };
  /** 走行中の演出を全部止めて fx層を空にする。シナリオ間の持ち越しを断つ */
  const fxReset = async (target) => {
    await target.evaluate(() => {
      // eslint-disable-next-line no-undef
      for (const key of [...fxRunning.keys()]) fxStop(key);
      const layer = document.getElementById('manual-fx-layer');
      if (layer) layer.innerHTML = '';
      // eslint-disable-next-line no-undef
      prevView = null;
    });
  };
  /** ★演出の有効フラグを切り替える(設計書 2-8。自己確認の入口である) */
  const setFxEnabled = async (target, on) => {
    await target.evaluate((v) => {
      // eslint-disable-next-line no-undef
      fxEnabled = v;
    }, on);
  };
  /** fx層に今いるゴーストの観測。★位置・移動量・中身(情報漏れ)まで見る */
  const fxGhosts = async (target) => target.evaluate(() => {
    const layer = document.getElementById('manual-fx-layer');
    if (!layer) return [];
    return [...layer.querySelectorAll('.manual-fx-ghost')].map((g) => ({
      kind: g.dataset.fxKind || '',
      phase: g.dataset.fxPhase || '',
      left: Math.round(parseFloat(g.style.left || '0')),
      top: Math.round(parseFloat(g.style.top || '0')),
      transform: g.style.transform || '',
      text: (g.textContent || '').trim(),
      imageIds: [...g.querySelectorAll('[data-image-id]')].map((e) => e.dataset.imageId),
    }));
  });
  const clone = (v) => JSON.parse(JSON.stringify(v));

  /**
   * ★項目1と項目12(自己確認)が<b>同じ観測</b>を使うための共通シナリオ。
   * 場のミニオンを墓地へ移す配信を2件流し、そのときの fx層とアンカー位置を返す。
   */
  const moveScenario = async (target) => {
    await fxReset(target);
    const v1 = baseView();
    await deliver(target, v1);
    const before = await target.evaluate(() => {
      const el = document.querySelector('[data-instance-id="f1"]');
      const r = el.getBoundingClientRect();
      return { left: Math.round(r.left), top: Math.round(r.top) };
    });
    const v2 = clone(v1);
    v2.seatA.zones.FIELD = [];
    v2.seatA.zones.TRASH = [card('t1', '墓地1'), card('f1', '場1')];
    syncCounts(v2.seatA);
    await deliver(target, v2);
    return { before, ghosts: await fxGhosts(target) };
  };
  /** 観測が「移動の演出が出た」と言えるか。★この判定を項目1と項目12で共有する */
  const moveDetected = (obs) => {
    const g = obs.ghosts.filter((x) => x.kind === 'move');
    if (g.length !== 1) return false;
    const near = Math.abs(g[0].left - obs.before.left) <= 2
      && Math.abs(g[0].top - obs.before.top) <= 2;
    return near && /^translate\(-?[\d.]+px, -?[\d.]+px\)$/.test(g[0].transform)
      && g[0].transform !== 'translate(0px, 0px)';
  };

  // ---- 41. 移動のゴースト ----
  const moveObs = await moveScenario(page);
  check('★move のゴーストが旧位置に出て新位置へ transform が張られる(32a・4章1)',
    moveDetected(moveObs), JSON.stringify(moveObs));

  // ---- 42. 演出は操作を妨げない(pointer-events: none)----
  const passThrough = await page.evaluate(() => {
    const layer = document.getElementById('manual-fx-layer');
    const g = layer.querySelector('.manual-fx-ghost');
    const r = g.getBoundingClientRect();
    const under = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return {
      pointerEvents: getComputedStyle(layer).pointerEvents,
      hitsLayer: !!(under && layer.contains(under)),
    };
  });
  check('★ゴーストの上を押しても当たるのは下の盤面である(32a・4章2)',
    passThrough.pointerEvents === 'none' && !passThrough.hitsLayer,
    JSON.stringify(passThrough));

  // ★「当たらない」だけでなく、実際に下のカードが押せることまで見る。
  //   手札のカードを墓地へ動かすと、ゴーストは<b>手札の上</b>に出る。
  await fxReset(page);
  await clearZoom(page);
  const handMove1 = baseView();
  await deliver(page, handMove1);
  const handMove2 = clone(handMove1);
  handMove2.seatA.zones.HAND = [card('h2', '手札2')];
  handMove2.seatA.zones.TRASH = [card('t1', '墓地1'), card('h1', '手札1')];
  syncCounts(handMove2.seatA);
  await deliver(page, handMove2);
  const ghostBox = await page.evaluate(() => {
    const g = document.querySelector('#manual-fx-layer .manual-fx-ghost');
    if (!g) return null;
    const r = g.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.click(ghostBox.x, ghostBox.y);
  await page.waitForTimeout(60);
  check('★ゴーストの下の手札は実際にクリックできる(32a・4章2)',
    (await page.locator('#zoom-panel .mcard-large .mcard-name').textContent()) === '手札2',
    JSON.stringify(ghostBox));
  await clearZoom(page);

  // ---- 43. 出現は実要素のフェードイン。ゴーストを作らない ----
  await fxReset(page);
  const appear1 = baseView();
  await deliver(page, appear1);
  const appear2 = clone(appear1);
  appear2.seatA.zones.FIELD = [card('f1', '場1'), card('f9', '新しく出た')];
  syncCounts(appear2.seatA);
  await deliver(page, appear2);
  check('★出現は実要素のフェードインで、ゴーストを作らない(32a・2-4)',
    (await fxGhosts(page)).length === 0
      && (await page.locator('[data-instance-id="f9"].manual-fx-enter').count()) === 1);

  // ---- 44. 消滅は旧位置にゴーストを残す ----
  await fxReset(page);
  const vanish1 = baseView();
  await deliver(page, vanish1);
  const vanishAt = await page.evaluate(() => {
    const r = document.querySelector('[data-instance-id="f1"]').getBoundingClientRect();
    return { left: Math.round(r.left), top: Math.round(r.top) };
  });
  const vanish2 = clone(vanish1);
  vanish2.seatA.zones.FIELD = [];
  syncCounts(vanish2.seatA);
  await deliver(page, vanish2);
  const vanishGhosts = await fxGhosts(page);
  check('★消滅は旧位置でゴーストがフェードアウトする(32a)',
    vanishGhosts.length === 1 && vanishGhosts[0].kind === 'vanish'
      && Math.abs(vanishGhosts[0].left - vanishAt.left) <= 2
      && Math.abs(vanishGhosts[0].top - vanishAt.top) <= 2,
    JSON.stringify(vanishGhosts));

  // ---- 45. ★★相手のドローは裏面ゴースト。名前も imageId も fx層に出ない ----
  await fxReset(page);
  const draw1 = versusView('A');
  await deliver(page, draw1);
  const draw2 = clone(draw1);
  draw2.seatB.counts.DECK -= 1;
  draw2.seatB.counts.HAND += 1;
  await deliver(page, draw2);
  const drawGhosts = await fxGhosts(page);
  check('★相手のドローは裏面ゴーストであり、名前・imageId を運ばない(32a・4章3)',
    drawGhosts.length === 1 && drawGhosts[0].kind === 'draw'
      && drawGhosts[0].imageIds.length === 0
      && !drawGhosts[0].text.includes('見えないはず'),
    JSON.stringify(drawGhosts));

  // ---- 46. ★★「窓」のゾーンでは出現・消滅を出さない ----
  // ★29 以降、山札は<b>最上段の1枚</b>しか届かない。届く配列の出入りは
  //   実際の出入りと一致しないので、そこから出現・消滅を作ってはならない。
  await fxReset(page);
  const win1 = baseView();
  win1.seatA.zones.DECK = [card('d1', '山札の一番上')];
  win1.seatA.counts.DECK = 30;
  await deliver(page, win1);
  const win2 = clone(win1);
  win2.seatA.zones.DECK = [card('d9', 'シャッフル後の一番上')];
  win2.seatA.counts.DECK = 30;
  await deliver(page, win2);
  check('★山札のような「窓」のゾーンでは出現・消滅を演出しない(32a・2-2)',
    (await fxGhosts(page)).length === 0
      && (await page.locator('.manual-fx-enter').count()) === 0);

  // ---- 47. LPポップは符号を正しく出す ----
  await fxReset(page);
  const lp1 = baseView();
  await deliver(page, lp1);
  const lp2 = clone(lp1);
  lp2.seatA.lp = 17;
  await deliver(page, lp2);
  const lpDown = await page.locator('#manual-fx-layer .manual-fx-lp').first();
  const lpDownText = await lpDown.textContent();
  const lpDownClass = await lpDown.getAttribute('class');
  const lp3 = clone(lp2);
  lp3.seatA.lp = 19;
  await deliver(page, lp3);
  const lpUp = await page.locator('#manual-fx-layer .manual-fx-lp').first();
  check('★LPポップが正しい符号で出る(32a・4章4)',
    lpDownText === '−3' && lpDownClass.includes('manual-fx-lp-down')
      && (await lpUp.textContent()) === '+2'
      && (await lpUp.getAttribute('class')).includes('manual-fx-lp-up'),
    `${lpDownText} / ${await lpUp.textContent()}`);

  // ---- 48. LPポップの色も同じ1本の条件で判定する ----
  check('★fx層のラベルも黒背景でコントラスト 4.5:1 以上(32a・4章4)',
    (await page.evaluate(() => window.__contrastAudit()))
      .filter((f) => f.sel.includes('manual-fx-lp')).length === 0
      && (await page.locator('#manual-fx-layer .manual-fx-lp').count()) > 0,
    JSON.stringify(await page.evaluate(() => window.__contrastAudit())));

  // ---- 49. 盤面の総入れ替えでは演出しない ----
  await fxReset(page);
  const bulk1 = baseView();
  bulk1.seatA.zones.FIELD = [];
  for (let i = 0; i < 9; i++) bulk1.seatA.zones.FIELD.push(card('bulk' + i, '一括' + i));
  syncCounts(bulk1.seatA);
  await deliver(page, bulk1);
  const bulk2 = clone(bulk1);
  bulk2.seatA.zones.TRASH = bulk2.seatA.zones.FIELD.concat(bulk2.seatA.zones.TRASH);
  bulk2.seatA.zones.FIELD = [];
  syncCounts(bulk2.seatA);
  await deliver(page, bulk2);
  check('★差分が上限を超える(リセット等)ときは演出全体を抑制する(32a・2-3)',
    (await fxGhosts(page)).length === 0);

  // ---- 50. 開始シーケンス中は演出しない ----
  await fxReset(page);
  const lock1 = baseView();
  await deliver(page, lock1);
  const lock2 = clone(lock1);
  lock2.start = startState({ phase: 'MULLIGAN', locking: true, mulliganSeats: [], myMulliganSeats: [] });
  lock2.seatA.zones.FIELD = [];
  lock2.seatA.zones.TRASH = [card('t1', '墓地1'), card('f1', '場1')];
  syncCounts(lock2.seatA);
  await deliver(page, lock2);
  check('★開始シーケンス中(locking)は演出しない(32a・2-3)',
    (await fxGhosts(page)).length === 0);

  // ---- 51. ★自己確認: 検出器が生きていることを確かめる ----
  // ★★31 で手順として固定した「判定を入れたら、判定が落ちることを1回確かめる」。
  //   fxEnabled を切って<b>項目41と全く同じシナリオ・全く同じ判定</b>を走らせ、
  //   それが「検出しなかった」と言うことを確かめる。
  //   moveDetected が常に真を返す作り(=飾りの検証)になっていれば、この項目が落ちる。
  await setFxEnabled(page, false);
  const disabledObs = await moveScenario(page);
  check('★fxEnabled を切ると項目41が検出できなくなる(32a・4章8・検出器が生きている確認)',
    !moveDetected(disabledObs), JSON.stringify(disabledObs));
  await setFxEnabled(page, true);
  // ★判定の中身も飾りでないことを見る。ブラウザを使わずに moveDetected 単体へ
  //   「出ているが動いていないゴースト」「動いているが旧位置から出ていないゴースト」を
  //   食わせ、どちらも検出と認めないことを確かめる。
  check('★moveDetected は「動いていない/旧位置から出ていない」ゴーストを認めない(32a・自己確認)',
    !moveDetected({ before: { left: 10, top: 10 },
      ghosts: [{ kind: 'move', left: 10, top: 10, transform: 'translate(0px, 0px)' }] })
    && !moveDetected({ before: { left: 10, top: 10 },
      ghosts: [{ kind: 'move', left: 400, top: 10, transform: 'translate(5px, 5px)' }] })
    && moveDetected({ before: { left: 10, top: 10 },
      ghosts: [{ kind: 'move', left: 10, top: 10, transform: 'translate(5px, 5px)' }] }));
  await fxReset(page);
  await render(page, baseView());

  // =====================================================================
  // ★Batch 32b: 状態系(タップ・めくり)と節目系(ターン・進化)
  //
  // ★★状態系は<b>ゴーストを作らない</b>。実要素の上で transition / animation が
  //   走るだけなので、「クラスが付いた」を見るだけでは<b>遷移が実際に走ったか</b>を
  //   確かめたことにならない(クラスは付くが transition-property が無い、
  //   値が変わらない、といった壊れ方が素通りする)。
  //   そこで {@code transitionstart} を捕まえる。ブラウザが遷移を<b>開始した</b>という
  //   事実そのものであり、どのプロパティが動いたかまで分かる。
  // =====================================================================

  /** transitionstart を集め始める(1回だけ仕掛け、呼ぶたびに記録を空にする) */
  const fxWatchTransitions = async (target) => target.evaluate(() => {
    window.__fxTr = [];
    if (!window.__fxTrHooked) {
      window.__fxTrHooked = true;
      document.addEventListener('transitionstart', (e) => {
        const el = e.target;
        window.__fxTr.push({
          id: (el.dataset && el.dataset.instanceId) || '',
          cls: typeof el.className === 'string' ? el.className : '',
          prop: e.propertyName,
        });
      }, true);
    }
  });
  /** 記録を読む。★遷移の開始は次のフレームなので少しだけ待つ(遷移中に読む) */
  const fxTransitions = async (target) => {
    await target.waitForTimeout(120);
    return target.evaluate(() => window.__fxTr);
  };
  /**
   * ★観測が「そのカードの遷移が実際に走った」と言えるか。
   *   項目53・54 と自己確認(項目60・61)がこの1本を共有する。
   */
  const tapDetected = (records, instanceId, prop) =>
    records.filter((r) => r.id === instanceId && r.prop === prop).length > 0;

  /** タップ状態だけを変えた2連続ビューを流し、遷移の記録を返す */
  const tapScenario = async (target, mutate) => {
    await fxReset(target);
    const v1 = baseView();
    v1.seatA.zones.MANA = [card('m1', 'マナ1')];
    syncCounts(v1.seatA);
    await deliver(target, v1);
    await fxWatchTransitions(target);
    const v2 = clone(v1);
    mutate(v2);
    await deliver(target, v2);
    return fxTransitions(target);
  };

  // ---- 53. ★自席マナのタップは「回転」の遷移が実際に走る ----
  const manaTap = await tapScenario(page, (v) => { v.seatA.zones.MANA[0].tapped = true; });
  check('★自席マナのタップは transform(回転)の遷移が実際に走る(32b・状態系)',
    tapDetected(manaTap, 'm1', 'transform'), JSON.stringify(manaTap));

  // ---- 54. ★★タップ表現の非対称が維持されている ----
  // ★26/30 で確定した「場=減光+バッジ / マナ=回転」を<b>機械で</b>押さえる。
  //   どちらかに揃えてしまう改修は、この項目が落ちることで必ず表に出る。
  const fieldTap = await tapScenario(page, (v) => { v.seatA.zones.FIELD[0].tapped = true; });
  check('★★タップ表現の非対称: 場のタイルは filter(減光)だけで回らない(32b・26/30 の維持)',
    tapDetected(fieldTap, 'f1', 'filter') && !tapDetected(fieldTap, 'f1', 'transform'),
    JSON.stringify(fieldTap));
  check('★場のタップはバッジも出す(減光+バッジ の対)',
    (await page.locator('[data-instance-id="f1"] .manual-tapped-badge').count()) === 1);

  // ---- 55. ★めくりは2段階で、後半に新しい面へ入れ替わる ----
  await fxReset(page);
  const flip1 = baseView();
  await deliver(page, flip1);
  const flip2 = clone(flip1);
  flip2.seatA.zones.FIELD[0].faceDown = true;
  await deliver(page, flip2);
  const flipFirst = (await fxGhosts(page)).filter((g) => g.kind === 'flip');
  const flipHiddenAtFirst =
    (await page.locator('[data-instance-id="f1"].manual-fx-hidden').count()) === 1;
  // ★待てなかったこと自体を FAIL として報告する。例外で検証を落とすと、
  //   「壊したら落ちる」を確かめるときにスクリプトごと死んで結果が読めない
  const settled = async (fn) => {
    try {
      await page.waitForFunction(fn, null, { timeout: 1500 });
      return true;
    } catch (e) {
      return false;
    }
  };
  const flipPhase2 = await settled(() => {
    const g = document.querySelector('#manual-fx-layer .manual-fx-ghost[data-fx-kind="flip"]');
    return !!g && g.dataset.fxPhase === '2';
  });
  const flipSecond = (await fxGhosts(page)).filter((g) => g.kind === 'flip');
  check('★めくりは前半が旧い面・後半が新しい面である(32b・2段階)',
    flipPhase2 && flipFirst.length === 1 && flipFirst[0].phase === '1'
      && flipFirst[0].text.includes('場1')
      && /rotateY\(90deg\)/.test(flipFirst[0].transform)
      && flipHiddenAtFirst
      && flipSecond.length === 1 && !flipSecond[0].text.includes('場1')
      && flipSecond[0].imageIds.length === 0,
    JSON.stringify({ flipPhase2, flipFirst, flipSecond, flipHiddenAtFirst }));
  // ★★演出が終われば実タイルは必ず戻る(onStop の後始末が効いていること)。
  //   ここを落とすと「めくったカードが二度と見えない」——演出の失敗ではなく
  //   <b>盤面の欠落</b>になる。27 の「画面のどこからも触れない = 消失」と同じ重さである
  const flipRestored = await settled(
    () => document.querySelectorAll('.manual-fx-hidden').length === 0);
  check('★★めくりが終わると実タイルの透明化は必ず戻る(32b・onStop の後始末)',
    flipRestored && (await fxGhosts(page)).length === 0);

  // ---- 56. ★進化スタックが伸びたら到着タイルが沈み込む ----
  await fxReset(page);
  const sink1 = baseView();
  await deliver(page, sink1);
  const sink2 = clone(sink1);
  sink2.seatA.zones.FIELD[0].stackSize = 2;
  sink2.seatA.zones.FIELD[0].materials = [card('mat1', '素材1')];
  await deliver(page, sink2);
  check('★進化スタックの増加で到着タイルが沈み込む(ゴーストは作らない)(32b・2-7)',
    (await page.locator('[data-instance-id="f1"].manual-fx-sink').count()) === 1
      && (await fxGhosts(page)).length === 0);

  // ---- 57. ★★Batch 35: 決着の合図(32b のターン帯からの差し替え。裁定17)----
  // ★宣言は盤面に触らない操作である。したがって<b>盤面の差分は1つも無い</b>状態で
  //   帯だけが出なければならない。ここが 32b のターン帯と違う唯一の性質である。
  const declaredView = (kind, seat = 'A', seq = 5) => {
    const v = baseView();
    v.log = v.log.concat([{ seq, time: '10:00:04', text: `席${seat} の ${
      { WIN: '勝利', LOSE: '敗北', DRAW: '引き分け', CONCEDE: '投了' }[kind]}を宣言した` }]);
    v.declarations = [declaration(seq, seat, kind)];
    return v;
  };
  await fxReset(page);
  const decl1 = baseView();
  await deliver(page, decl1);
  const decl2 = declaredView('WIN', 'A');
  await deliver(page, decl2);
  const declBand = page.locator('#manual-fx-layer .manual-fx-declare');
  check('★★宣言が届いたら勝敗の帯が出る(35・3章)',
    (await declBand.count()) === 1
      && (await declBand.first().textContent()) === '席A の勝利'
      && (await declBand.first().getAttribute('class')).includes('manual-fx-declare-win')
      && (await fxGhosts(page)).length === 0,
    await declBand.first().textContent().catch(() => '(なし)'));

  // ---- 58. ★★同じ宣言が再び届いても帯は出さない ----
  // ★32b の「巻き戻しでは合図を出さない」の後継である。合図は<b>宣言</b>であり、
  //   二度出れば二度決着したことになる。再接続の resync や、決着後の別の操作による
  //   配信でも declarations は同じものが載り続けるため、ここは実際に起きる。
  check('★★同じ宣言が再配信されても帯は出さない(35・3-2)', await (async () => {
    await fxReset(page);
    await deliver(page, decl2);
    const again = clone(decl2);
    again.seatA.lp = 19;   // ★他の差分は出ること(演出そのものが止まっているのではない)
    await deliver(page, again);
    return (await page.locator('#manual-fx-layer .manual-fx-declare').count()) === 0
      && (await page.locator('#manual-fx-layer .manual-fx-lp').count()) === 1;
  })());

  // ---- 58-2. ★★★ターン帯の退役(裁定17)----
  // ★★これは「消したこと」の番人である。turnNumber は今も配信に載っており
  //   (手動モードの記帳として残る)、検出だけを外した。うっかり戻すと押し忘れで
  //   ずれた帯が「嘘をつく演出」として復活する。
  check('★★★turnNumber が増えても帯は出ない(35・ターン帯の退役・裁定17)', await (async () => {
    await fxReset(page);
    const t1 = baseView();
    await deliver(page, t1);
    const t2 = clone(t1);
    t2.turnNumber = 2;
    t2.seatA.lp = 19;   // ★演出そのものは生きていること
    await deliver(page, t2);
    return (await page.locator('#manual-fx-layer .manual-fx-turn').count()) === 0
      && (await page.locator('#manual-fx-layer .manual-fx-declare').count()) === 0
      && (await page.locator('#manual-fx-layer .manual-fx-lp').count()) === 1;
  })());

  // ---- 59. 帯の文字も同じ1本の条件で判定する(4種すべて)----
  // ★★色を4つに分けたので、4つとも判定する。1つだけ見て通すと、
  //   増やした色が判定の外に置かれる(30/31 の「1本の条件」の主旨に反する)。
  check('★勝敗の帯は4種とも黒背景でコントラスト 4.5:1 以上(35・30/31 の1本の条件)',
    await (async () => {
      for (const kind of ['WIN', 'LOSE', 'DRAW', 'CONCEDE']) {
        await fxReset(page);
        await deliver(page, baseView());
        await deliver(page, declaredView(kind, 'B'));
        const audit = await page.evaluate(() => window.__contrastAudit());
        const shown = await page.locator('#manual-fx-layer .manual-fx-declare').count();
        if (shown !== 1 || audit.filter((f) => f.sel.includes('manual-fx-declare')).length > 0) {
          return false;
        }
      }
      return true;
    })(),
    JSON.stringify(await page.evaluate(() => window.__contrastAudit())));

  // ---- 59-2. ★Batch 35: ログの決着行を強調する ----
  // ★★印は<b>seq で指す</b>(本文を読まない)。差分追記でも作り直しでも同じ1行に付く。
  await fxReset(page);
  await render(page, baseView());
  await render(page, declaredView('CONCEDE', 'B', 5));
  const logMark = await page.evaluate(() => ({
    decisive: [...document.querySelectorAll('#log-box .manual-log-decisive')]
      .map((el) => el.dataset.seq),
    total: document.querySelectorAll('#log-box div[data-seq]').length,
  }));
  check('★ログの決着行だけが強調される(35・4章)',
    logMark.decisive.length === 1 && logMark.decisive[0] === '5' && logMark.total === 5,
    JSON.stringify(logMark));

  // ★決着行も同じ1本の条件で判定する。★<b>出ている間にしか測れない</b>ので、
  //   帯とは別にここで測る(baseView には決着行が無く、他の項目では現れない)
  check('★ログの決着行もコントラスト 4.5:1 以上(35・30/31 の1本の条件)',
    (await page.evaluate(() => window.__contrastAudit()))
      .filter((f) => f.sel.includes('manual-log-decisive')).length === 0
      && (await page.locator('#log-box .manual-log-decisive').count()) === 1,
    JSON.stringify(await page.evaluate(() => window.__contrastAudit())));

  // ★作り直し(seq が飛ぶ)でも印は復元される。追記のときだけ付く作りだと、
  //   再接続のあとに決着行の印が<b>静かに消える</b>
  const rebuilt = declaredView('WIN', 'A', 40);
  rebuilt.log = [{ seq: 39, time: '10:00:39', text: '直前の行' },
    { seq: 40, time: '10:00:40', text: '席A の 勝利を宣言した' }];
  rebuilt.logTotal = 40;
  await render(page, rebuilt);
  const logRebuilt = await page.evaluate(() => ({
    decisive: [...document.querySelectorAll('#log-box .manual-log-decisive')]
      .map((el) => el.dataset.seq),
    total: document.querySelectorAll('#log-box div[data-seq]').length,
  }));
  check('★★作り直しでも決着行の印は復元される(35・4章)',
    logRebuilt.decisive.length === 1 && logRebuilt.decisive[0] === '40' && logRebuilt.total === 2,
    JSON.stringify(logRebuilt));

  // ---- 59-3. ★自己確認: 帯の検出器が生きている ----
  // ★★項目57 と<b>同じシナリオ・同じ判定</b>を fxEnabled を切って走らせる。
  //   「宣言が届いたら必ず1本」を常に真で返す作りなら、ここが落ちる。
  await setFxEnabled(page, false);
  await fxReset(page);
  await deliver(page, baseView());
  await deliver(page, declaredView('WIN', 'A'));
  check('★fxEnabled を切ると項目57が検出できなくなる(35・検出器が生きている確認)',
    (await page.locator('#manual-fx-layer .manual-fx-declare').count()) === 0);
  await setFxEnabled(page, true);

  // ---- 60. ★自己確認: 状態系の検出器が生きている ----
  // ★★fxEnabled を切って<b>項目53と全く同じシナリオ・全く同じ判定</b>を走らせる。
  //   tapDetected が常に真を返す作り(=飾りの検証)なら、この項目が落ちる。
  await setFxEnabled(page, false);
  const disabledTap = await tapScenario(page, (v) => { v.seatA.zones.MANA[0].tapped = true; });
  check('★fxEnabled を切ると項目53が検出できなくなる(32b・検出器が生きている確認)',
    !tapDetected(disabledTap, 'm1', 'transform'), JSON.stringify(disabledTap));
  await setFxEnabled(page, true);

  // ---- 61. ★自己確認: 判定関数そのものに反例を食わせる ----
  check('★tapDetected は「記録が空」「別のプロパティ」「別のカード」を認めない(32b・自己確認)',
    !tapDetected([], 'm1', 'transform')
    && !tapDetected([{ id: 'm1', prop: 'filter' }], 'm1', 'transform')
    && !tapDetected([{ id: 'zzz', prop: 'transform' }], 'm1', 'transform')
    && tapDetected([{ id: 'm1', prop: 'transform' }], 'm1', 'transform'));

  await fxReset(page);
  await render(page, baseView());

  // ---- 63. ★★Batch 32c: フェイスの質感が transform / filter を使っていない ----
  // ★★これは「見た目が綺麗か」の検証ではない(それは目視の仕事である)。
  //   32b の `.manual-fx-tap` は<b>transform と filter を 160ms かけて遷移させる</b>。
  //   盤面のタイルは `.mcard-frame` そのものなので、質感のために transform や filter を
  //   足すと、タップのたびに質感まで遷移の対象になる。さらに `.manual-tile-tapped` の
  //   `filter: brightness()` は<b>置き換え</b>であって合成ではないため、
  //   基底に filter を持たせるとタップ中だけその質感が消える。
  //   ——32b 6章の申し送りそのものであり、機械で押さえられる。
  // ★質感は background と box-shadow だけで出す、という決めごとの番人である。
  const faceFx = await page.evaluate(() => {
    const sels = ['.mcard', '.mcard-inner', '.mcard-head', '.mcard-type',
      '.mcard-foot', '.mcard-cost', '.mcard-backface', '.mcard-frame'];
    const out = [];
    for (const sel of sels) {
      for (const el of document.querySelectorAll('#manual-root ' + sel)) {
        const s = getComputedStyle(el);
        if (s.transform !== 'none' || s.filter !== 'none') {
          out.push({ sel, transform: s.transform, filter: s.filter });
        }
      }
    }
    // ★空の盤面なら無条件に通ってしまう。何を実際に見たのかも返す
    const seen = sels.filter((s) => document.querySelectorAll('#manual-root ' + s).length > 0);
    return { hits: out, seen: seen };
  });
  check('★★フェイスの質感は transform / filter を使わない(32c・タップの遷移に巻き込まれない)',
    faceFx.hits.length === 0 && faceFx.seen.length >= 6,
    JSON.stringify(faceFx));

  // =====================================================================
  // ★★Batch 33: 切断UXの強化(1章)と共有導線(2章)
  // =====================================================================

  // ---- 64〜66. ★★切断中は操作を送らない・無言で捨てない ----
  // ★★これが本バッチの中心である。32 までの send() は接続を一切見ておらず、
  //   死んだソケットへ<b>無言で publish</b> していた。例外は出ないので、
  //   人間には「押したのに何も起きない」としか見えない。
  //   通話しながら遊ぶ前提では、双方が数分気づかないまま盤面が食い違う。
  await render(page, baseView());
  const offlineSend = await page.evaluate(() => {
    window.__sent.length = 0;
    // eslint-disable-next-line no-undef
    client.onWebSocketClose();             // ★実際の切断と同じ入口から落とす
    // eslint-disable-next-line no-undef
    const ok = send('tap', { cardIds: ['m1'] });
    const toast = document.getElementById('manual-toast');
    return { ok, sent: window.__sent.length, toast: toast ? toast.textContent : '' };
  });
  check('★★切断中は send() が publish しない(33・1-2)',
    offlineSend.ok === false && offlineSend.sent === 0, JSON.stringify(offlineSend));
  check('★★切断中の操作を無言で捨てない(33・1-2)',
    offlineSend.toast.includes('切断中'), offlineSend.toast);

  const lock = await page.evaluate(() => {
    const el = document.getElementById('manual-offline');
    const top = document.elementFromPoint(
      Math.floor(window.innerWidth / 2), Math.floor(window.innerHeight / 2));
    return {
      shown: !el.classList.contains('d-none'),
      blocked: !!(top && el.contains(top)),
      topClass: top ? top.className : '(なし)',
    };
  });
  check('★切断中はオーバーレイが盤面を物理的に覆う(33・1-3)',
    lock.shown && lock.blocked, JSON.stringify(lock));

  // ---- 67. ★矢印(dragcue)だけは無言で捨てる ----
  // ★1回のドラッグで何十回も飛ぶ揮発メッセージにトーストを出すと、
  //   <b>本当の警告が埋もれる</b>。捨てること自体は同じである。
  const cueQuiet = await page.evaluate(() => {
    const toast = document.getElementById('manual-toast');
    toast.textContent = '';
    window.__sent.length = 0;
    // eslint-disable-next-line no-undef
    const ok = send('dragcue', { cardId: null, active: false }, { quiet: true });
    return { ok, sent: window.__sent.length, toast: toast.textContent };
  });
  check('★矢印は切断中も無言で捨てる(33・警告を埋もれさせない)',
    cueQuiet.ok === false && cueQuiet.sent === 0 && cueQuiet.toast === '',
    JSON.stringify(cueQuiet));

  // ---- 68. ★★覗き見でロックを畳んでも、番人(send)は効いたままである ----
  // ★★オーバーレイは<b>宣言</b>であって安全装置ではない、という設計そのものの検証。
  //   「見えなくすること」を安全装置にしていたら、この項目は落ちる。
  await page.locator('#manual-offline-peek').click();
  await page.waitForTimeout(30);
  const peek = await page.evaluate(() => {
    window.__sent.length = 0;
    // eslint-disable-next-line no-undef
    const ok = send('tap', { cardIds: ['m1'] });
    const bar = document.getElementById('manual-conn-bar');
    return {
      overlayHidden: document.getElementById('manual-offline').classList.contains('d-none'),
      barOffline: bar.classList.contains('manual-conn-bar-offline'),
      barText: bar.textContent,
      ok,
      sent: window.__sent.length,
    };
  });
  check('★★盤面を覗いても send() のガードは効く(33・番人はオーバーレイではない)',
    peek.overlayHidden && peek.ok === false && peek.sent === 0,
    JSON.stringify(peek));
  check('★覗いている間は接続の帯が状態を出し続ける(33・1-5)',
    peek.barOffline && peek.barText.includes('切断中'), JSON.stringify(peek));

  // ---- 69・70. ★再接続の通知(初回の接続と区別する)----
  // ★「再接続しました」を初回の接続で出すと、それは<b>嘘の宣言</b>である
  //   (32b の「巻き戻しでターンの帯を出さない」と同じ理屈)。
  const firstConnect = await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    connectionEstablishedOnce = false;
    // eslint-disable-next-line no-undef
    client.onConnect();
    const bar = document.getElementById('manual-conn-bar');
    return { ok: bar.classList.contains('manual-conn-bar-ok'), text: bar.textContent };
  });
  check('★初回の接続では「再接続しました」と言わない(33・1-5)',
    !firstConnect.ok && firstConnect.text === '', JSON.stringify(firstConnect));

  const reconnect = await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    client.onWebSocketClose();
    // eslint-disable-next-line no-undef
    client.onConnect();
    const bar = document.getElementById('manual-conn-bar');
    return {
      ok: bar.classList.contains('manual-conn-bar-ok'),
      text: bar.textContent,
      overlayHidden: document.getElementById('manual-offline').classList.contains('d-none'),
      status: document.getElementById('connection-status').textContent,
    };
  });
  check('★再接続を黙って済ませない(33・1-5)',
    reconnect.ok && reconnect.text.includes('再接続') && reconnect.overlayHidden
      && reconnect.status === '接続済み', JSON.stringify(reconnect));

  // ---- 71. ★自己確認: 切断の判定そのものが効いている ----
  // ★★項目64と<b>全く同じ操作</b>を、接続していることにして流す。
  //   send() が常に false を返す作り(=飾りの検証)なら、この項目が落ちる。
  const guardAlive = await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    client.onWebSocketClose();
    // eslint-disable-next-line no-undef
    socketDown = false;                    // ★判定だけを外す
    // eslint-disable-next-line no-undef
    client.connected = true;
    window.__sent.length = 0;
    // eslint-disable-next-line no-undef
    const ok = send('tap', { cardIds: ['m1'] });
    // eslint-disable-next-line no-undef
    updateOfflineLock();                   // ★オーバーレイを畳んでおく(以降の操作の邪魔になる)
    return { ok, sent: window.__sent.length };
  });
  check('★切断の判定を外すと項目64は成立しない(33・検出器が生きている確認)',
    guardAlive.ok === true && guardAlive.sent === 1, JSON.stringify(guardAlive));

  // ---- 72〜73. ★共有導線(部屋リンクのコピー)----
  const share = await page.evaluate(() => roomShareUrl());
  check('★部屋リンクは /manual/battle/{roomId} である(33・2-1)',
    share === `http://127.0.0.1:${port}/manual/battle/TESTRM`, share);

  await page.evaluate(() => { document.getElementById('manual-toast').textContent = ''; });
  await page.locator('#btn-copy-link').click();
  await page.waitForTimeout(120);
  const copyToast = await page.evaluate(
    () => document.getElementById('manual-toast').textContent);
  // ★成否のどちらであっても<b>必ず告げる</b>ことを見る。クリップボードへ実際に
  //   書けたかは環境(権限・安全なコンテキスト)に依存し、機械で確かめるべきものではない。
  check('★コピーは必ず結果を告げる(33・2-2・無言にしない)',
    copyToast.startsWith('部屋リンクを'), copyToast);
  // ★クリップボードAPIが使えない環境で落ちる先(execCommand)そのものを確かめる
  check('★クリップボードAPIが使えなくても代替経路で書ける(33・2-2)',
    await page.evaluate(() => copyTextFallback('QTE-COPY-TEST')) === true);

  // ---- 74. ★★部屋消失のときは切断の案内を重ねない ----
  // ★「戻るのを待ってください」と「この対戦は戻りません」を同時に出さない。
  //   ★この項目は localStorage の occupant を消す(forgetOccupant)ため、
  //     このページを使う検証の<b>最後</b>に置くこと。
  const fatal = await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    showRoomLostFatal();
    return {
      gate: !document.getElementById('seat-gate').classList.contains('d-none'),
      overlayHidden: document.getElementById('manual-offline').classList.contains('d-none'),
      barHidden: document.getElementById('manual-conn-bar').classList.contains('d-none'),
    };
  });
  check('★★部屋消失のときは切断の案内を重ねない(33・1-4)',
    fatal.gate && fatal.overlayHidden && fatal.barHidden, JSON.stringify(fatal));

  // ---- 75〜77. ★メタ情報とブランド資産(テンプレートの静的検査)----
  // ★ここだけブラウザを使わない。「全ページに入っているか」は DOM ではなく
  //   テンプレートの集合に対する問いであり、1枚を開いて確かめても意味が無い。
  const TEMPLATES = ['manual-battle', 'manual-lobby', 'manual-deck-maker', 'manual-cards',
    'lobby', 'battle', 'cards', 'deck-builder', 'error'];
  const metaMissing = [];
  for (const name of TEMPLATES) {
    const src = fs.readFileSync(path.join(RES, 'templates', `${name}.html`), 'utf8');
    for (const need of ['rel="icon"', 'name="theme-color"', 'name="description"']) {
      if (!src.includes(need)) metaMissing.push(`${name}:${need}`);
    }
  }
  check('★全テンプレートに favicon・theme-color・description がある(33・2-3)',
    metaMissing.length === 0, metaMissing.join(' / '));

  const ogMissing = [];
  for (const name of ['manual-battle', 'manual-lobby']) {
    const src = fs.readFileSync(path.join(RES, 'templates', `${name}.html`), 'utf8');
    for (const need of ['og:title', 'og:description', 'og:image', 'twitter:card']) {
      if (!src.includes(need)) ogMissing.push(`${name}:${need}`);
    }
    // ★★Batch 34(裁定24): og:image は<b>設定から来る</b>ようになった。
    //   33 の判定は content="https:// の直書きを見ていたので、そのままでは
    //   「設定化したら落ちる」判定になる。見るべきものが変わったので式を差し替える。
    //   ★直書きが残っていないことも同時に見る。片方だけ設定化して片方が直書きのままだと、
    //     公開URLを変えたときに<b>1枚だけ古い画像を配る</b>という一番分かりにくい壊れ方をする。
    if (!/property="og:image" th:content="\$\{ogImageUrl\}"/.test(src)) {
      ogMissing.push(`${name}:og:image が ${'${ogImageUrl}'} を参照していない`);
    }
    if (/property="og:image"[^>]*content="https?:\/\//.test(src)) {
      ogMissing.push(`${name}:og:image に公開URLが直書きされている`);
    }
  }
  check('★共有されるページには OGP があり og:image は設定由来である(34・裁定24)',
    ogMissing.length === 0, ogMissing.join(' / '));

  // ---- ★Batch 34: 公開URLの設定と、絶対URLを組み立てる側 ----
  // ★「設定に出した」は、設定に<b>正しい形の値が入っている</b>ことまで含めて初めて成立する。
  //   相対URLへ退化させないのが裁定22 の要点なので、絶対URLであることを機械で押さえる。
  const props = fs.readFileSync(path.join(RES, 'application.properties'), 'utf8');
  const baseUrl = (props.match(/^qte\.public-base-url=(.*)$/m) || [])[1];
  check('★公開URLが設定にあり、絶対URLである(34・裁定24)',
    !!baseUrl && /^https?:\/\/[^\s/]+/.test(baseUrl.trim()), String(baseUrl));

  // ★組み立てが1箇所であることを見る。テンプレートが '/og-image.png' を持っていたら
  //   それは連結がテンプレート側へ漏れているということである(設計判断28)。
  const adviceSrc = fs.readFileSync(
    path.join(ROOT, 'src/main/java/com/example/qte/web/PublicUrlAdvice.java'), 'utf8');
  const ogPathLeak = ['manual-battle', 'manual-lobby'].filter((name) => fs
    .readFileSync(path.join(RES, 'templates', `${name}.html`), 'utf8').includes('/og-image.png'));
  check('★og:image のURL組み立ては PublicUrlAdvice の1箇所だけである(34・裁定24)',
    adviceSrc.includes('/og-image.png') && ogPathLeak.length === 0, ogPathLeak.join(' / '));

  const assetMissing = ['favicon.svg', 'favicon-32.png', 'apple-touch-icon.png', 'og-image.png']
    .filter((f) => !fs.existsSync(path.join(RES, 'static', f)));
  check('★アイコンとOGP画像が実在する(33・2-3)',
    assetMissing.length === 0, assetMissing.join(' / '));

  // ---- 78〜79. ★カスタムエラーページ ----
  const errorHtml = fs.readFileSync(path.join(RES, 'templates/error.html'), 'utf8');
  // ★★エラーページは<b>CDN が落ちているときにも出る</b>ページである。
  //   外部依存を持たせると「エラーページが崩れる」という二重の事故になる。
  // ★名前空間の宣言(xmlns:th)はURLの形をしているが読み込みではない。ここだけ除く
  const errorNoNs = errorHtml.replace(/xmlns:th="[^"]*"/g, '');
  check('★★エラーページは外部CDNに依存しない(33・2-5)',
    !/https?:\/\//.test(errorNoNs), (errorNoNs.match(/https?:\/\/\S{0,40}/g) || []).join(' / '));

  const errPage = await browser.newPage({ viewport: { width: 900, height: 700 } });
  // ★Thymeleaf 属性は素のブラウザでは無視されるだけなので、そのまま読ませてよい
  await errPage.setContent(errorHtml);
  const errContrast = await errPage.evaluate(() => {
    const parse = (c) => {
      const m = (c || '').match(/[\d.]+/g);
      if (!m) return [0, 0, 0, 1];
      const v = m.map(Number);
      return [v[0], v[1], v[2], v.length > 3 ? v[3] : 1];
    };
    const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    const L = (v) => 0.2126 * lin(v[0]) + 0.7152 * lin(v[1]) + 0.0722 * lin(v[2]);
    const bg = parse(getComputedStyle(document.querySelector('.box')).backgroundColor);
    const out = [];
    for (const el of document.querySelectorAll('.box .code, .box h1, .box p, .box a')) {
      const t = (el.textContent || '').trim();
      if (!t) continue;
      const fg = parse(getComputedStyle(el).color);
      const ratio = (Math.max(L(fg), L(bg)) + 0.05) / (Math.min(L(fg), L(bg)) + 0.05);
      if (ratio < 4.5) out.push({ text: t.slice(0, 12), ratio: Math.round(ratio * 100) / 100 });
    }
    return { bad: out, dark: getComputedStyle(document.body).backgroundColor };
  });
  check('★エラーページは盤面と同系のダークテーマで、文字は 4.5:1 以上(33・2-5)',
    errContrast.bad.length === 0 && errContrast.dark === 'rgb(33, 37, 41)',
    JSON.stringify(errContrast));
  await errPage.close();

  // ---- 52. prefers-reduced-motion では演出そのものを作らない ----
  const calm = await browser.newPage({
    viewport: { width: 1280, height: 950 }, reducedMotion: 'reduce',
  });
  const calmErrors = [];
  calm.on('pageerror', (e) => calmErrors.push(String(e)));
  await calm.goto(`http://127.0.0.1:${port}/harness.html`);
  await calm.waitForTimeout(200);
  const calm1 = baseView();
  await deliver(calm, calm1);
  const calm2 = clone(calm1);
  calm2.seatA.zones.FIELD = [];
  calm2.seatA.zones.TRASH = [card('t1', '墓地1'), card('f1', '場1')];
  syncCounts(calm2.seatA);
  await deliver(calm, calm2);
  check('★prefers-reduced-motion ではゴーストを作らない(32a・4章7)',
    (await fxGhosts(calm)).length === 0 && calmErrors.length === 0,
    calmErrors.join(' | '));

  // ---- 62. ★prefers-reduced-motion では状態系・節目系も出ない(32b)----
  // ★★状態系は<b>実要素</b>に当たる。fx層を display:none にするだけでは止まらないので、
  //   「層が消えているから大丈夫」で済ませずに1つずつ確かめる。
  await fxReset(calm);
  const calmState1 = baseView();
  calmState1.seatA.zones.MANA = [card('m1', 'マナ1')];
  syncCounts(calmState1.seatA);
  await deliver(calm, calmState1);
  await fxWatchTransitions(calm);
  const calmState2 = clone(calmState1);
  calmState2.seatA.zones.MANA[0].tapped = true;
  calmState2.seatA.zones.FIELD[0].stackSize = 2;
  calmState2.seatA.zones.FIELD[0].materials = [card('mat1', '素材1')];
  // ★Batch 35: 節目は決着に差し替わった(ターン帯は退役。裁定17)
  calmState2.log = calmState1.log.concat([
    { seq: 5, time: '10:00:04', text: '席A の 勝利を宣言した' }]);
  calmState2.declarations = [declaration(5, 'A', 'WIN')];
  await deliver(calm, calmState2);
  const calmTr = await fxTransitions(calm);
  check('★prefers-reduced-motion では状態系・節目系も出ない(32b/35)',
    !tapDetected(calmTr, 'm1', 'transform')
      && (await calm.locator('.manual-fx-sink').count()) === 0
      && (await calm.locator('.manual-fx-tap').count()) === 0
      && (await calm.locator('#manual-fx-layer .manual-fx-declare').count()) === 0
      && calmErrors.length === 0,
    JSON.stringify(calmTr) + ' | ' + calmErrors.join(' | '));
  await calm.close();

  await wide.goto(`http://127.0.0.1:${port}/harness.html`);
  await wide.waitForTimeout(200);
  await render(wide, versusView('A'));
  const wideTop = await wide.locator('#seat-opponent-top').boundingBox();
  const wideMana = await wide.locator('#seat-opponent-top .manual-opp-mana').boundingBox();
  const wideChips = await wide.locator('#seat-opponent-top .manual-opp-chips').boundingBox();
  const wideLeader = await wide.locator('#seat-opponent-top .manual-leader-tile').boundingBox();
  check('1920幅でも相手上段は148px内に収まる(4章)', wideTop.height <= 148,
    `h=${wideTop.height}`);
  check('★1920幅では余った幅をマナ簡略表示が吸う(左右が離れない)',
    wideMana.width > 600 && wideChips.x + wideChips.width < wideLeader.x + 8,
    `mana.w=${wideMana.width} chips右端=${wideChips.x + wideChips.width} leader=${wideLeader.x}`);
  check('1920幅でも盤面全体が画面高さに収まる',
    (await wide.locator('#manual-root').boundingBox()).height <= 1080,
    `h=${(await wide.locator('#manual-root').boundingBox()).height}`);
  check('1920幅でJSエラーが出ない', wideErrors.length === 0, wideErrors.join(' | '));
  await wide.close();

  // ---- 25. ロビー(設計書 1-3・F1) ----
  const lobby = await browser.newPage({ viewport: { width: 1000, height: 900 } });
  const lobbyErrors = [];
  lobby.on('pageerror', (e) => lobbyErrors.push(String(e)));
  lobby.on('console', (m) => { if (m.type() === 'error') lobbyErrors.push(m.text()); });
  await lobby.goto(`http://127.0.0.1:${port}/harness-lobby.html`);
  await lobby.waitForTimeout(200);

  check('ロビーの部屋一覧が3件描かれる(1-3)',
    (await lobby.locator('#room-list tr').count()) === 3);
  const lobbyText = await lobby.locator('#room-list').textContent();
  check('一覧に部屋名・種類・席の埋まり・観戦・鍵が出る(1-3)',
    lobbyText.includes('たいせん') && lobbyText.includes('対戦')
      && lobbyText.includes('A:あかり') && lobbyText.includes('B:ばんり')
      && lobbyText.includes('可(2人)') && lobbyText.includes('不可'),
    lobbyText.replace(/\s+/g, ' '));
  check('★一覧に部屋IDが1つも出ない(1-3・F1)',
    !lobbyText.includes('AAA111') && !lobbyText.includes('CCC333'),
    lobbyText.replace(/\s+/g, ' '));
  check('鍵つき部屋には「入る」ボタンを出さずIDを要求する(1-3)',
    (await lobby.locator('#room-list tr').nth(1).locator('.room-enter').count()) === 0
      && (await lobby.locator('#room-list tr').nth(1).locator('.room-locked-hint').count()) === 1);
  check('鍵なし部屋は一覧から席選択画面へ進める(1-3)',
    (await lobby.locator('.room-enter').count()) === 2);

  await lobby.locator('#room-list tr').first().locator('.room-enter').click();
  await lobby.waitForTimeout(40);
  check('「入る」で盤面ページ(=席選択画面)へ送られる(2-1)',
    (await lobby.evaluate(() => window.__navigated)).join(',') === 'AAA111');

  // 対戦部屋の必須項目(1-2・F4)。サーバへ行く前に画面で止める
  await lobby.locator('#create-type').selectOption('VERSUS');
  await lobby.waitForTimeout(20);
  check('対戦を選ぶと名前欄の注記が必須に変わる(F4)',
    (await lobby.locator('#create-name-note').textContent()).includes('必須'));
  await lobby.locator('#create-submit').click();
  await lobby.waitForTimeout(60);
  check('対戦部屋は部屋名なしで作成できない(1-2)',
    (await lobby.locator('#status').textContent()).includes('部屋名'),
    await lobby.locator('#status').textContent());
  await lobby.locator('#create-room-name').fill('あたらしい対戦');
  await lobby.locator('#create-submit').click();
  await lobby.waitForTimeout(60);
  check('対戦部屋は名前なしでも作成できない(F4)',
    (await lobby.locator('#status').textContent()).includes('名前が必要'),
    await lobby.locator('#status').textContent());

  // ---- ★Batch 34: ロビーのダークテーマ統一(レビュー B-2) ----
  // ★★見るのはクラス名ではなく<b>実際に解決された色</b>である。
  //   `bg-dark が付いている` を見る判定は、Bootstrap の代替が漏れていても通ってしまう
  //   (31 で踏んだのはまさにその穴で、ハーネスは白背景のまま「合格」と言っていた)。
  const lobbyTheme = await lobby.evaluate(() => ({
    body: getComputedStyle(document.body).backgroundColor,
    text: getComputedStyle(document.body).color,
  }));
  check('★★ロビーの背景が盤面と同じ黒である(34・B-2)',
    lobbyTheme.body === 'rgb(33, 37, 41)' && lobbyTheme.text === 'rgb(248, 249, 250)',
    JSON.stringify(lobbyTheme));

  // ★盤面と同じ 4.5:1 の条件をロビーの文字にも当てる。
  //   ★ここへ足す色は<b>実ページと同じ値</b>でなければならない。ハーネス側の代替が
  //     実物とずれていると、判定は通るのに実ページでは読めない、が再発する(31)。
  const lobbyContrast = await lobby.evaluate(() => {
    const parse = (c) => {
      const m = (c || '').match(/[\d.]+/g);
      if (!m) return [0, 0, 0, 0];
      const v = m.map(Number);
      return [v[0], v[1], v[2], v.length > 3 ? v[3] : 1];
    };
    const bodyBg = parse(getComputedStyle(document.body).backgroundColor);
    const eff = (el) => {
      let n = el;
      while (n && n !== document.documentElement) {
        const c = parse(getComputedStyle(n).backgroundColor);
        if (c[3] >= 0.98) return c;
        n = n.parentElement;
      }
      return bodyBg;
    };
    const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    const L = (v) => 0.2126 * lin(v[0]) + 0.7152 * lin(v[1]) + 0.0722 * lin(v[2]);
    const out = [];
    const targets = ['h1', 'p', '.form-label', '.form-text', '.form-check-label',
      'th', '#room-list td', '#room-list .room-locked-hint', '#refresh-rooms',
      '.room-enter', '#create-submit', '#join-submit', '.card-header'];
    for (const sel of targets) {
      for (const el of document.querySelectorAll(sel)) {
        const t = (el.textContent || '').trim();
        if (!t) continue;
        const fg = parse(getComputedStyle(el).color);
        const bg = eff(el);
        const ratio = (Math.max(L(fg), L(bg)) + 0.05) / (Math.min(L(fg), L(bg)) + 0.05);
        if (ratio < 4.5) out.push({ sel, text: t.slice(0, 12), ratio: Math.round(ratio * 100) / 100 });
      }
    }
    return out;
  });
  check('★ロビーの文字もコントラスト比 4.5:1 以上(34・B-2)',
    lobbyContrast.length === 0, JSON.stringify(lobbyContrast));

  check('ロビーでJSエラーが出ない', lobbyErrors.length === 0, lobbyErrors.join(' | '));
  await lobby.close();

  // ---- ★Batch 34: ロビーから1クリックで行くカード一覧も黒である ----
  // ★ロビーだけ直すと、白→黒のフラッシュが<b>経路を変えて戻る</b>。
  //   この画面はサーバ描画なのでハーネスを作らず、テンプレートを静的に見る。
  const cardsHtml = fs.readFileSync(path.join(RES, 'templates/manual-cards.html'), 'utf8');
  check('★手動モードのカード一覧もダークテーマである(34・B-2)',
    /<body class="bg-dark text-light">/.test(cardsHtml)
      && cardsHtml.includes('background-color: #212529'),
    (cardsHtml.match(/<body[^>]*>/) || [])[0]);

  // ---- ★Batch 34: 初回だけ操作説明が開く(レビュー A-3・裁定16) ----
  // ★★「初回」と「2回目」を<b>実際の入口から</b>作る(33 の教訓)。
  //   フラグを直接倒して確かめると、自動表示のコードを消しても検証が通ってしまう。
  //   ここでは localStorage を消してページを開き直す = 初見の人と同じ経路を通す。
  const help1 = await browser.newPage({ viewport: { width: 1280, height: 950 } });
  const helpErrors = [];
  help1.on('pageerror', (e) => helpErrors.push(String(e)));
  await help1.goto(`http://127.0.0.1:${port}/harness.html?firstvisit=1`);
  await help1.evaluate(() => localStorage.removeItem('qte-manual-help-seen'));
  await help1.reload();
  await help1.waitForTimeout(200);
  check('★初回入室では操作説明が自動で開く(34・1章)',
    !(await help1.locator('#help-modal').getAttribute('class')).includes('d-none'));
  check('★開いた時点で「見た」と記録する(閉じるのを待たない)(34・1章)',
    (await help1.evaluate(() => localStorage.getItem('qte-manual-help-seen'))) === '1');

  // 2回目。★同じURLで開き直す = スタブは何もしない。差は localStorage だけである
  await help1.reload();
  await help1.waitForTimeout(200);
  check('★★2回目からは自動で開かない(34・1章)',
    (await help1.locator('#help-modal').getAttribute('class')).includes('d-none'));
  // ★自動表示をやめても [?] からはいつでも開けること(導線を1本にしない)
  await help1.locator('#btn-help').click();
  check('★[?] ボタンからはいつでも開ける(34・1章)',
    !(await help1.locator('#help-modal').getAttribute('class')).includes('d-none'));

  // ---- ★★Batch 34 hotfix: 閉じる手段が「スクロールしないと出てこない」問題 ----
  // ★操作説明は本文が長く、[閉じる] は<b>いちばん下</b>にしかなかった。
  //   34 で初回に自動で開くようにしたので、これは
  //   「初めて見る画面から出られない」に直結する。
  // ★★測るのは「× が存在するか」ではなく<b>スクロールしても見えているか</b>である。
  //   存在だけを見る判定は、position: sticky を消しても通ってしまう。
  //   本文を最下部までスクロールさせてから、× がまだ本文の見えている範囲に
  //   収まっていることを確かめる。
  const scrolled = await help1.evaluate(() => {
    const body = document.querySelector('#help-modal .info-modal-body');
    body.scrollTop = body.scrollHeight;
    return { scrollTop: body.scrollTop, scrollable: body.scrollHeight > body.clientHeight + 1 };
  });
  check('★操作説明の本文は実際にスクロールする(前提の確認)',
    scrolled.scrollable && scrolled.scrollTop > 0, JSON.stringify(scrolled));
  const xVisible = await help1.evaluate(() => {
    const body = document.querySelector('#help-modal .info-modal-body');
    const x = document.getElementById('help-modal-x');
    const b = body.getBoundingClientRect();
    const r = x.getBoundingClientRect();
    return { ok: r.top >= b.top - 1 && r.bottom <= b.bottom + 1 && r.width > 0,
      x: Math.round(r.top), body: Math.round(b.top) };
  });
  check('★★最下部までスクロールしても × は見えたままである(34 hotfix)',
    xVisible.ok, JSON.stringify(xVisible));
  // ★× は右上にある(見出しの右端側)
  const xRight = await help1.evaluate(() => {
    const b = document.querySelector('#help-modal .info-modal-body').getBoundingClientRect();
    const r = document.getElementById('help-modal-x').getBoundingClientRect();
    return r.right > b.right - 60;
  });
  check('★× は本文の右上に置かれている(34 hotfix)', xRight);
  await help1.locator('#help-modal-x').click();
  check('★× を押すと閉じる(34 hotfix)',
    (await help1.locator('#help-modal').getAttribute('class')).includes('d-none'));

  // ★★開始シーケンスの2つには × を付けない。
  //   出口が「リセットして最初から」しか無いのが意図であり(23 設計書 7-2)、
  //   サーバ側の状態を残したまま画面だけ閉じられるようにしてはいけない。
  const xCoverage = await help1.evaluate(() => {
    const out = { missing: [], extra: [] };
    for (const m of document.querySelectorAll('.info-modal')) {
      const hasClose = !!m.querySelector('[id$="-close"]');
      const hasX = !!m.querySelector('.info-modal-x');
      if (hasClose && !hasX) out.missing.push(m.id);
      if (!hasClose && hasX) out.extra.push(m.id);
    }
    return out;
  });
  check('★★[閉じる] を持つモーダルには必ず × があり、持たないものには無い(34 hotfix)',
    xCoverage.missing.length === 0 && xCoverage.extra.length === 0,
    JSON.stringify(xCoverage));

  check('初回ヘルプの検証でJSエラーが出ない', helpErrors.length === 0, helpErrors.join(' | '));
  await help1.close();

  check('全工程を通じてJSエラーが出ない', errors.length === 0, errors.join(' | '));

  await browser.close();
  server.close();

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exit(failed.length === 0 ? 0 : 1);
})();
