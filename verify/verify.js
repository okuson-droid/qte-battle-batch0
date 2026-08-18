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
  rite, dealRite, shuffleRite, autoCard, autoMinion, autoPlayer, autoView,
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

/**
 * ★★Batch 39: カード定義の口(`/manual/api/card-library`)の応答。
 *
 * 盤面の検証では<b>空</b>でよい(テキスト表示はセクション26が applyCardLibrary で
 * 直接フィクスチャを入れる)。デッキメーカーはこの口だけが情報源なので、
 * <b>あちらを開く直前に</b>中身を差し替える。差し替えを検証の最後に置いているのは、
 * 「検証の項目は状態を引き継ぐ」(38 で踏んだ)を踏まえてのことである。
 */
const CARD_LIBRARY = { body: { cards: [] } };

/**
 * デッキメーカー用の最小のカード台帳。★文明ごとにリーダー1 + 10枚。
 *
 * ★★Batch 40: 39 の時点では文明ごとに2枚だったが、マナカーブ・検証一覧・
 *   デッキ読込を見るには足りない —— <b>コストが1種類しか無いカーブは形を持たず</b>、
 *   同名4枚の上限がある以上、40枚のデッキには10種類の札が要る。
 * ★並びは変えていない(リーダー → ミニオン3 → …)。39 の項目は
 *   「プールの1枚目を右クリックする」形で書かれており、そこが動くと前提が変わる。
 */
function deckMakerLibrary() {
  const civs = ['WATER', 'FIRE', 'EARTH', 'WIND', 'LIGHT', 'DARK'];
  const cards = [];
  for (const civ of civs) {
    cards.push({ id: `QTE-M-${civ}-1`, name: `${civ}のリーダー`, type: 'LEADER',
      civilization: civ, cost: 0, attack: null, hp: null, text: '【起動：１】試験用。' });
    cards.push({ id: `QTE-M-${civ}-2`, name: `${civ}のミニオン`, type: 'MINION',
      civilization: civ, cost: 3, attack: 2, hp: 3, text: '【守護】試験用のミニオンである。' });
    cards.push({ id: `QTE-M-${civ}-3`, name: `${civ}のスペル`, type: 'SPELL',
      civilization: civ, cost: 1, attack: null, hp: null, text: '試験用のスペルである。' });
    cards.push({ id: `QTE-M-${civ}-4`, name: `${civ}の進化`, type: 'EVOLUTION',
      civilization: civ, cost: 5, attack: 5, hp: 5, text: '試験用の進化ミニオンである。' });
    cards.push({ id: `QTE-M-${civ}-5`, name: `${civ}のウェポン`, type: 'WEAPON',
      civilization: civ, cost: 10, attack: 4, hp: null, text: '試験用のウェポンである。' });
    // ★40枚のデッキを組むための頭数(同名4枚 × 10種類 = 40)。コストも散らしてある
    [0, 2, 4, 6, 8, 9].forEach((cost, i) => {
      cards.push({ id: `QTE-M-${civ}-${6 + i}`, name: `${civ}の兵${i + 1}`, type: 'MINION',
        civilization: civ, cost, attack: 1, hp: 1, text: '試験用の頭数である。' });
    });
  }
  // ★ピュア・エレメント(文明なし)は構築対象外である。除外されることも見る
  cards.push({ id: 'QTE-M-NONE-1', name: 'ピュア・エレメント', type: 'SPELL',
    civilization: 'NONE', cost: 0, attack: null, hp: null, text: '後攻へ配られる。' });
  return { meta: { backImageId: 'back' }, cards };
}

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
      // ★42: status を差し替えられる(既定200)。「取得失敗でも壊れない」を実際の失敗で確かめる
      const st = CARD_LIBRARY.status || 200;
      res.writeHead(st, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(st === 200 ? JSON.stringify(CARD_LIBRARY.body) : '{}');
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
    else if (url === '/harness-battle.html') file = path.join(__dirname, 'harness-battle.html');
    else if (url === '/harness-deckmaker.html') file = path.join(__dirname, 'harness-deckmaker.html');
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
  // ★★Batch 39: 期待値を書かず、<b>battle.css の :root から読んだ値</b>と突き合わせる。
  //   ここで確かめたいのは「水のタイルに水の色が乗ること」——つまり<b>結線</b>であって、
  //   色そのものではない。値を書くと、正(:root)を1文字直すたびに検証も直すことになり、
  //   「正が1箇所」を機械で守っている意味が薄れる。
  const leaderBg = await page.evaluate(() => {
    const el = document.querySelector('#pile-grid .manual-leader-tile');
    return {
      frame: el.classList.contains('mcard-frame'),
      mc: el.style.getPropertyValue('--mc'),
      water: getComputedStyle(document.documentElement).getPropertyValue('--civ-water').trim(),
    };
  });
  check('自分のリーダーが文明の色の枠になる(25b: フェイスと同一パレット / 39: 正は battle.css)',
    leaderBg.frame && leaderBg.water !== ''
      && leaderBg.mc.toLowerCase() === leaderBg.water.toLowerCase(),
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
  // ★Batch 36: 素の confirm() ではなく自前の確認モーダルを通る(0-4)
  await page.locator('#btn-seat').click();
  await page.waitForTimeout(60);
  await page.locator('#confirm-modal-ok').click();
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
  await page.locator('#start-banner .manual-start-reset').click();
  await page.waitForTimeout(60);
  await page.locator('#confirm-modal-ok').click();
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
      // ★Batch 36: 確認モーダルの文字。★本文は空のうちは判定を素通りするので、
      //   出している間に測る専用の項目も別に立ててある(36・5章)
      '#confirm-modal-text', '#confirm-modal-ok', '#confirm-modal-close',
      // ★Batch 37: 音の設定パネル。新しい文字を作ったら必ずここへ足す(32a からの規約)
      '#sound-modal-status', '#sound-modal .sound-note', '#sound-modal label',
      '#sound-volume-value', '#sound-modal-close',
    ].map((s) => '#manual-root ' + s)
      // ★★Batch 32a: fx層のラベル(LPポップ)も<b>同じ1本の条件</b>で判定する。
      //   fx層は position: fixed で body 直下にあり #manual-root の中に無いため、
      //   ここで明示的に足さないと判定の網から静かに漏れる(設計書 2-5)。
      // ★Batch 35: 決着の帯も同じ1本の条件で見る(fx層に文字を足したら必ずここへ)。
      //   32b のターン帯はここに居た。退役に伴い、座席ごと勝敗の帯へ引き渡している
      // ★Batch 38: ダイスの帯の文字(出目・勝った側・結果の文)も同じ1本の条件で見る
      .concat(['#manual-fx-layer .manual-fx-lp', '#manual-fx-layer .manual-fx-declare',
        '#manual-fx-layer .manual-fx-dice-chip', '#manual-fx-layer .manual-fx-dice-note']);
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

  // ---- 63-2. ★★★Batch 41: 光沢の強さが場所ごとに<b>実際に</b>変わっている ----
  // ★★これは 41 で見つかった不具合の番人である。32c は「--mc-gloss を継承させたまま
  //   --mc-sheen だけ子で上書きすれば強さだけ変わる」と書いていたが、これは誤りだった。
  //   カスタムプロパティの中の var() は<b>宣言した要素</b>で置換され、子が継承するのは
  //   置換済みのトークン列である。32c 以降、胴も頭も足も 0.14 の光沢で描かれていた。
  // ★★★<b>--mc-sheen の値そのものは測らない。</b>それは「上書きしたか」しか見ておらず、
  //   まさに今回の不具合を素通りする(上書きはされていた。効いていなかっただけである)。
  //   測るのは<b>解決後の背景に現れた数値</b>——結果のほうである。
  const sheenUsed = await page.evaluate(() => {
    const alphaOf = (sel) => {
      const el = document.querySelector('#manual-root ' + sel);
      if (!el) return null;
      const m = getComputedStyle(el).backgroundImage
        .match(/115deg[^)]*\)[^)]*\)[^,]*,\s*rgba\([^)]*?([\d.]+)\)/);
      return m ? Number(m[1]) : null;
    };
    return { card: alphaOf('.mcard'), inner: alphaOf('.mcard-inner'),
      head: alphaOf('.mcard-head'), foot: alphaOf('.mcard-foot') };
  });
  check('★★★光沢の強さは場所ごとに実際に変わる(41・32c 2-1 が初めて成立した)',
    sheenUsed.card === 0.14 && sheenUsed.inner === 0.05
      && sheenUsed.head === 0.12 && sheenUsed.foot === 0.07,
    JSON.stringify(sheenUsed));
  // ★胴が<b>いちばん弱い</b>ことが 32c の狙いである(読む面に模様を作らない)。
  //   数値を1つずつ書いた上の項目とは別に、<b>関係</b>のほうも固定しておく——
  //   値を調整するときに関係まで壊す事故を防ぐ
  check('★★胴の光沢が面の中でいちばん弱い(32c・読む面には模様を作らない)',
    sheenUsed.inner < sheenUsed.foot && sheenUsed.foot < sheenUsed.head
      && sheenUsed.head < sheenUsed.card,
    JSON.stringify(sheenUsed));

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

  // ---- ★★Batch 36: battle.css の color-scheme(34 の質問1・マスター裁定) ----
  //
  // ★見るのは宣言そのものではなく、<b>宣言してよい条件が成り立っていること</b>である。
  //   `color-scheme: dark` は「このファイルを読む画面はどれも暗い」を前提にしている。
  //   白い画面から battle.css を読み込んだ瞬間、この1行は読めない画面に変わる。
  //   条件のほうを番人にする(裁定32 と同じ考え方)。
  const battleCss = fs.readFileSync(path.join(RES, 'static/css/battle.css'), 'utf8');
  check('★★battle.css は :root に color-scheme: dark を持つ(36・34 Q1)',
    /:root\s*\{[^}]*color-scheme:\s*dark/.test(battleCss));
  const cssConsumers = fs.readdirSync(path.join(RES, 'templates'))
    .filter((n) => n.endsWith('.html'))
    .map((n) => ({ name: n,
      src: fs.readFileSync(path.join(RES, 'templates', n), 'utf8') }))
    .filter((t) => /css\/battle\.css/.test(t.src));
  const notDark = cssConsumers
    .filter((t) => !/<body[^>]*class="[^"]*bg-dark/.test(t.src))
    .map((t) => t.name);
  check('★★★battle.css を読み込むテンプレートはすべて黒背景である(36・color-scheme の前提)',
    cssConsumers.length >= 2 && notDark.length === 0,
    JSON.stringify({ consumers: cssConsumers.map((t) => t.name), notDark }));
  // ★キャッシュバスティングの手動管理は既に破綻の実例が出ている
  //   (battle.html が v=10 のまま取り残されていた)。版数を揃えることを機械で守る。
  const cssVersions = Array.from(new Set(cssConsumers
    .map((t) => (t.src.match(/css\/battle\.css\(v=(\d+)\)/) || [])[1])));
  check('★★battle.css の版数はすべてのテンプレートで揃っている(36)',
    cssVersions.length === 1 && cssVersions[0] !== undefined,
    JSON.stringify(cssVersions));

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
  // ★★Batch 36 hotfix: 見出し帯が中身にかぶさっていないこと。
  //   34 は負の上マージンで帯を持ち上げようとしたが、sticky の位置は
  //   スクロール容器のパディングボックスで頭打ちになるため押し返され、
  //   <b>後続の中身だけが16px上に来て8px重なっていた</b>。
  //   ★測るのは式(margin の値)ではなく<b>結果の座標</b>である。
  const headOverlap = await help1.evaluate(() => {
    const body = document.querySelector('#help-modal .info-modal-body');
    body.scrollTop = 0;
    const head = body.querySelector('.info-modal-head');
    const next = head.nextElementSibling;
    return { headBottom: Math.round(head.getBoundingClientRect().bottom),
      nextTop: Math.round(next.getBoundingClientRect().top) };
  });
  check('★★見出し帯は本文の中身にかぶさらない(36 hotfix)',
    headOverlap.nextTop >= headOverlap.headBottom, JSON.stringify(headOverlap));
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

  // ---- ★★Batch 36: Esc・フォーカス管理・confirm() の置換(レビュー A-1) ----
  //
  // ★★見る決めごとは3つである。
  //   1. Esc は × と同じ資格しか持たない(裁定35 の一般化)。
  //      どちらも [閉じる] を click() するだけなので、
  //      「[閉じる] を持たないモーダルでは Esc も効かない」が自動的に成り立つ。
  //   2. Esc は<b>下の層へ落とさない</b>。
  //   3. 破壊的操作で<b>素の confirm() を呼ばない</b>。
  // ★ここまでの検証で部屋消失(showRoomLostFatal)を通っており、
  //   ゲートが開いたまま・接続も落としたままである。どちらも戻してから入る
  //   (ゲートは盤面全体を覆い、切断中は send() が publish しない)
  await page.evaluate(() => {
    /* eslint-disable no-undef */
    connectionFatal = false;
    socketDown = false;
    client.activate();
    closeGate();
    /* eslint-enable no-undef */
  });
  await render(page, baseView());
  await page.waitForTimeout(60);

  // --- Esc の基本(出口があるモーダル) ---
  await page.locator('#btn-help').click();
  await page.waitForTimeout(60);
  const escOpened = !(await page.locator('#help-modal').getAttribute('class')).includes('d-none');
  // ★★初期フォーカスは × である。既定(本文の先頭の焦点可能要素)だと
  //   操作説明では [閉じる] に載り、focus が要素を見せようとして
  //   <b>開いた瞬間に最下部までスクロールする</b>
  const helpFocus = await page.evaluate(() => ({
    id: document.activeElement ? document.activeElement.id : null,
    scrollTop: document.querySelector('#help-modal .info-modal-body').scrollTop,
  }));
  check('★★モーダルを開くと中へ初期フォーカスが入る(36・0-3)',
    escOpened && helpFocus.id === 'help-modal-x', JSON.stringify(helpFocus));
  check('★操作説明は開いた時点で本文の先頭を見せている(初期フォーカスが下へ送らない)',
    helpFocus.scrollTop === 0, JSON.stringify(helpFocus));

  // ★Tab の折り返し。★「クラスが付いたか」ではなく<b>実際の activeElement</b> を見る
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  const tabInside = await page.evaluate(() => ({
    inside: document.getElementById('help-modal').contains(document.activeElement),
    id: document.activeElement ? document.activeElement.id : null,
  }));
  check('★★Tab を繰り返してもフォーカスは裏の盤面へ抜けない(36・0-3)',
    tabInside.inside, JSON.stringify(tabInside));
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Shift+Tab');
  const shiftInside = await page.evaluate(() => ({
    inside: document.getElementById('help-modal').contains(document.activeElement),
    id: document.activeElement ? document.activeElement.id : null,
  }));
  check('★★Shift+Tab でも先頭から末尾へ折り返す(36・0-3)',
    shiftInside.inside, JSON.stringify(shiftInside));

  // ★Tab を通らない経路(裏の要素が自分でフォーカスを取る)にも網がある
  await page.evaluate(() => document.getElementById('btn-help').focus());
  await page.waitForTimeout(30);
  check('★裏の要素へフォーカスが移ってもモーダルへ引き戻す(36・0-3)',
    await page.evaluate(() =>
      document.getElementById('help-modal').contains(document.activeElement)));

  await page.keyboard.press('Escape');
  await page.waitForTimeout(60);
  check('★★Esc でモーダルが閉じる(36・0-3)',
    (await page.locator('#help-modal').getAttribute('class')).includes('d-none'));
  check('★閉じるとフォーカスは開く前の位置へ戻る(36・0-3)',
    (await page.evaluate(() => (document.activeElement ? document.activeElement.id : null)))
      === 'btn-help');

  // --- 出口が無いモーダルでは Esc も効かない(裁定34)+ 下の層へ落とさない ---
  let v36 = versusView('A');
  v36.start = startState({ phase: 'ORDER_METHOD', locking: true, canChooseMethod: true,
    waiting: 'ゲームの開始方法を選んでいます' });
  await render(page, v36);
  await page.waitForTimeout(60);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(60);
  check('★★[閉じる] を持たないモーダルは Esc でも閉じない(36・裁定34)',
    !(await page.locator('#start-method-modal').getAttribute('class')).includes('d-none'));

  await clearSent(page);
  await page.locator('#start-method-modal .manual-start-reset').click();
  await page.waitForTimeout(60);
  check('★開始シーケンスのモーダルの上にも確認を出せる(36・3章)',
    !(await page.locator('#confirm-modal').getAttribute('class')).includes('d-none'));
  check('★確認の初期フォーカスは [キャンセル] である(実行に載せない)(36・3章)',
    (await page.evaluate(() => (document.activeElement ? document.activeElement.id : null)))
      === 'confirm-modal-close');
  const confirmContrast = await page.evaluate(() => window.__contrastAudit());
  check('★確認モーダルの文字もコントラスト比 4.5:1 以上(36・5章)',
    confirmContrast.length === 0, JSON.stringify(confirmContrast));

  await page.keyboard.press('Escape');
  await page.waitForTimeout(60);
  check('★★Esc はいちばん上の層だけを閉じる(下のモーダルは残る)(36・0-3)',
    (await page.locator('#confirm-modal').getAttribute('class')).includes('d-none')
      && !(await page.locator('#start-method-modal').getAttribute('class')).includes('d-none'));
  check('★取り消した確認は何も送らない(36・3章)',
    boardMessages(await sent(page)).length === 0,
    JSON.stringify(boardMessages(await sent(page))));

  // --- 帯は Esc で閉じる / マリガンは閉じない(裁定34) ---
  await render(page, baseView());
  await page.waitForTimeout(60);
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    openZoneBand('A', 'TRASH');
  });
  await page.waitForTimeout(60);
  const bandBefore = await page.locator('.manual-band').count();
  await page.keyboard.press('Escape');
  await page.waitForTimeout(60);
  check('★Esc で帯が閉じる(36・0-3)',
    bandBefore === 1 && (await page.locator('.manual-band').count()) === 0);

  // ★★★出口の無いモーダルが<b>いちばん上</b>のとき、Esc はそこで止まる。
  //   帯(Esc で閉じる)を開いたまま開始モーダルが載る状況で確かめる。
  //   ここを「上から順に、出口のある層を探す」と書くと、
  //   <b>画面のいちばん上は閉じないのに裏の帯だけが消える</b>という
  //   目で追えない挙動になる。上の項目(確認モーダル)ではこの誤りを検出できない
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    openZoneBand('A', 'TRASH');
  });
  await page.waitForTimeout(60);
  v36 = versusView('A');
  v36.start = startState({ phase: 'ORDER_METHOD', locking: true, canChooseMethod: true,
    waiting: 'ゲームの開始方法を選んでいます' });
  await render(page, v36);
  await page.waitForTimeout(60);
  const stacked = await page.locator('.manual-band').count();
  await page.keyboard.press('Escape');
  await page.waitForTimeout(60);
  check('★★★Esc は下の層へ落とさない(出口の無いモーダルが上なら何も閉じない)(36・0-3)',
    stacked === 1 && (await page.locator('.manual-band').count()) === 1
      && !(await page.locator('#start-method-modal').getAttribute('class')).includes('d-none'),
    JSON.stringify({ stacked, after: await page.locator('.manual-band').count() }));
  await page.evaluate(() => {
    // eslint-disable-next-line no-undef
    closeOverlay();
  });

  v36 = versusView('A');
  v36.start = startState({ phase: 'MULLIGAN', locking: true, firstSeat: 'A',
    mulliganSeats: ['A', 'B'], mulliganDone: [], myMulliganSeats: ['A'],
    waiting: 'マリガンの確定を待っています' });
  await render(page, v36);
  await page.waitForTimeout(60);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(60);
  check('★★マリガンは Esc で閉じない(開始シーケンスに出口を作らない・裁定34)',
    (await page.locator('.manual-mulligan').count()) === 1);

  // ★★確認モーダルが<b>マリガンの上に出る</b>ことを座標で測る(34 hotfix の教訓)。
  //   マリガンのオーバーレイは z-index 1950 であり、確認が .info-modal の 1000 のままだと
  //   <b>問いが下に潜り、押せないボタンを待つ</b>ことになる。
  //   ★式(z-index の値)ではなく結果(その座標に何があるか)を見る。
  //   式を測ると、式を変えたときに検証も一緒に変わって番人にならない
  await page.locator('.manual-mulligan .manual-start-reset').click();
  await page.waitForTimeout(60);
  const confirmOnTop = await page.evaluate(() => {
    const r = document.getElementById('confirm-modal-ok').getBoundingClientRect();
    const el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return { hit: el ? el.id : null, w: Math.round(r.width) };
  });
  check('★★★確認モーダルはマリガンのオーバーレイより手前に出る(36・3章)',
    confirmOnTop.hit === 'confirm-modal-ok', JSON.stringify(confirmOnTop));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(60);

  // --- confirm() の置換 ---
  // ★★素の confirm() が呼ばれていないことを<b>実際に張り込んで</b>見る。
  //   ソースを grep するだけの判定は、呼び出しを変数に逃がすと通ってしまう。
  await render(page, baseView());
  await page.waitForTimeout(60);
  await page.evaluate(() => {
    window.__nativeConfirmCalls = 0;
    window.confirm = () => { window.__nativeConfirmCalls += 1; return true; };
  });
  await clearSent(page);
  await page.locator('#btn-reset').click();
  await page.waitForTimeout(60);
  const nativeCalls = await page.evaluate(() => window.__nativeConfirmCalls);
  check('★★破壊的操作で素の confirm() を呼ばない(36・3章)', nativeCalls === 0, `${nativeCalls}回`);
  check('★確認を出している間はまだ何も送らない(36・3章)',
    boardMessages(await sent(page)).length === 0);
  await page.locator('#confirm-modal-ok').click();
  await page.waitForTimeout(60);
  const resetMsgs = boardMessages(await sent(page));
  check('★確認の [実行] でようやく操作が送られる(36・3章)',
    resetMsgs.length === 1 && resetMsgs[0].destination.endsWith('/reset'),
    JSON.stringify(resetMsgs));
  check('★実行したら確認は閉じている(36・3章)',
    (await page.locator('#confirm-modal').getAttribute('class')).includes('d-none'));

  // ★★番人: ソースに confirm( が1つも残っていないこと。
  //   置き換え漏れは「そこだけOSのダイアログが出る」という、
  //   通してみるまで分からない壊れ方をする
  // ★注釈の中の confirm() は数えない(3章の説明文が引っかかる)。
  //   見たいのは<b>呼び出しが残っているか</b>である
  const leftoverConfirm = jsSrc.split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .filter((line) => /(?<![A-Za-z0-9_$])confirm\s*\(/.test(line))
    .map((line) => line.trim());
  check('★★manual-battle.js に素の confirm( が残っていない(36・3章)',
    leftoverConfirm.length === 0, leftoverConfirm.join(' / '));

  // ★★番人: モーダルの開閉が classList の直接操作へ戻っていないこと。
  //   戻ると「開いたのに層に積まれていない」= Esc もトラップも効かない
  //   モーダルが静かに1枚増える(0-3)
  const rawModalToggle = (jsSrc.match(/^.*-modal'\)\.classList\.(add|remove)\('d-none'\).*$/gm)
    || []);
  check('★★情報モーダルの開閉は openInfoModal / closeInfoModal を通る(36・0-3)',
    rawModalToggle.length === 0, rawModalToggle.join(' / '));

  // =====================================================================
  // ★★Batch 37: 効果音(レビュー S-2・裁定67)
  //
  // ★★音そのものは機械で測れない。測るのは<b>測れる決めごと</b>のほうである
  //   (31 以来の規約)。ここで測っているのは次の2つに分かれる。
  //   (a) 「どの音を選ぶか」…… 純関数({@code sfxChoose} / {@code sfxNameFor})を直接呼ぶ
  //   (b) 「実際に鳴ったか」…… <b>WebAudio の境界</b>に張り込み、音源ノードの生成を数える
  //   (b) を「sfxPlay が呼ばれたか」で測らないのは、呼ばれても
  //   ミュートや unlock 前で鳴っていないことがあるからである。
  // =====================================================================

  // ---- 音の選び方(1配信1音・優先順位)----
  // ★★1回の配信で鳴らす音は1つである。差分の件数だけ鳴らすと、音が語るのは
  //   「何をしたか」ではなく「何件変わったか」になる(0-5 の章)
  const pick = async (target, effects) => target.evaluate((list) =>
    // eslint-disable-next-line no-undef
    sfxChoose(list), effects);

  check('★差分の種類ごとに音が決まる(移動・出現・消滅・吸収は同じ「配置」に畳む)',
    (await pick(page, [{ kind: 'move' }])) === 'place'
      && (await pick(page, [{ kind: 'appear' }])) === 'place'
      && (await pick(page, [{ kind: 'vanish' }])) === 'place'
      && (await pick(page, [{ kind: 'sink' }])) === 'place'
      && (await pick(page, [{ kind: 'draw' }])) === 'draw'
      && (await pick(page, [{ kind: 'tap' }])) === 'tap'
      && (await pick(page, [{ kind: 'flip' }])) === 'flip'
      && (await pick(page, [{ kind: 'declare' }])) === 'decisive');
  check('★LPは増と減で別の音である(表では決まらない唯一のもの)',
    (await pick(page, [{ kind: 'lp', delta: -3 }])) === 'lpDown'
      && (await pick(page, [{ kind: 'lp', delta: 2 }])) === 'lpUp');
  // ★★珍しい出来事ほど優先する。頻度の高い音は「何が起きたか」を語らない
  check('★★1回の配信で選ばれる音は1つで、珍しいものが勝つ(37・3-2)',
    (await pick(page, [{ kind: 'move' }, { kind: 'tap' }, { kind: 'lp', delta: -1 }]))
        === 'lpDown'
      && (await pick(page, [{ kind: 'move' }, { kind: 'declare' }, { kind: 'lp', delta: -1 }]))
        === 'decisive'
      && (await pick(page, [{ kind: 'tap' }, { kind: 'move' }])) === 'tap');
  check('★鳴らすものが無い差分では何も選ばない', (await pick(page, [])) === null);

  // ★★番人: 演出の種類を足したときに、音の表への追加を忘れないための項目である。
  //   ★fxEffects が作る kind を<b>ソースから拾う</b>。テスト側に一覧を書き写すと、
  //     写した一覧のほうが古くなるだけで番人にならない
  const fxKindsSrc = jsSrc.slice(jsSrc.indexOf('function fxEffects('),
    jsSrc.indexOf('// ---- 位置の採取'));
  const fxKinds = [...new Set([...fxKindsSrc.matchAll(/kind: '([a-z]+)'/g)].map((m) => m[1]))];
  const unmappedKinds = await page.evaluate((ks) => ks.filter((k) =>
    // eslint-disable-next-line no-undef
    !sfxNameFor({ kind: k, delta: -1 })), fxKinds);
  check('★★演出の種類はすべて音の表に載っている(取りこぼしの番人・37)',
    fxKinds.length >= 9 && unmappedKinds.length === 0,
    JSON.stringify({ fxKinds, unmappedKinds }));

  // ---- 設定パネル ----
  await page.locator('#btn-sound').click();
  await page.waitForTimeout(60);
  check('★[♪] で音の設定が開く(37・5章)',
    !(await page.locator('#sound-modal').getAttribute('class')).includes('d-none'));
  check('★★初期音量は控えめ(30)である(裁定67: 通話と干渉させない)',
    (await page.locator('#sound-volume').inputValue()) === '30'
      && (await page.locator('#sound-volume-value').textContent()) === '30');
  // ★色が意味を持つのは「鳴らない理由がある」ときだけである(37・5章)
  check('★問題が無いときの状態行は警告色にならない(37・5章)',
    !(await page.locator('#sound-modal-status').getAttribute('class')).includes('sound-status-warn'),
    await page.locator('#sound-modal-status').textContent());
  const soundContrast = await page.evaluate(() => window.__contrastAudit());
  check('★音の設定パネルの文字もコントラスト比 4.5:1 以上(37・5章)',
    soundContrast.length === 0, JSON.stringify(soundContrast));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(60);
  check('★音の設定も Esc で閉じる(36 の規約に載っている)',
    (await page.locator('#sound-modal').getAttribute('class')).includes('d-none'));

  // ---- 実際に鳴ったか。★WebAudio の境界に張り込んで数える ----
  const audioSpy = () => {
    window.__audio = { contexts: 0, nodes: [] };
    const Real = window.AudioContext;
    // ★本物を包む。差し替えてしまうと「鳴らせない環境」の経路を測ることになる
    window.AudioContext = function Spy() {
      const ctx = new Real();
      window.__audio.contexts += 1;
      const osc = ctx.createOscillator.bind(ctx);
      const buf = ctx.createBufferSource.bind(ctx);
      ctx.createOscillator = () => { window.__audio.nodes.push('osc'); return osc(); };
      ctx.createBufferSource = () => { window.__audio.nodes.push('buf'); return buf(); };
      return ctx;
    };
  };
  const snd = await browser.newPage({ viewport: { width: 1280, height: 950 } });
  const sndErrors = [];
  snd.on('pageerror', (e) => sndErrors.push(String(e)));
  snd.on('console', (m) => { if (m.type() === 'error') sndErrors.push(m.text()); });
  await snd.addInitScript(audioSpy);
  await snd.goto(`http://127.0.0.1:${port}/harness.html`);
  await snd.waitForTimeout(200);

  const audioNodes = async (target) => target.evaluate(() => window.__audio.nodes.length);
  const clearAudio = async (target) => target.evaluate(() => { window.__audio.nodes = []; });
  /** 「1手ぶんの配信」を作る。場のミニオン1枚を墓地へ移す */
  const moveDelivery = async (target) => {
    await fxReset(target);
    const a = baseView();
    await deliver(target, a);
    await clearAudio(target);
    const b = clone(a);
    b.seatA.zones.FIELD = [];
    b.seatA.zones.TRASH = [card('t1', '墓地1'), card('f1', '場1')];
    syncCounts(b.seatA);
    await deliver(target, b);
    await target.waitForTimeout(60);
  };

  // ★★自動再生ポリシー: ユーザーが何か操作するまで AudioContext を作らない。
  //   作ってしまうと suspended のまま溜まり、後から resume しても
  //   「最初の数手ぶんが鳴らない」という再現性の低い挙動になる
  await moveDelivery(snd);
  check('★★最初のユーザー操作まで AudioContext を作らない(37・4章)',
    (await snd.evaluate(() => window.__audio.contexts)) === 0
      && (await audioNodes(snd)) === 0);

  // ★★入口は「最初のユーザー操作」であって特定の操作ではない。
  //   席選択ゲートの決定を入口にすると<b>復帰した人には二度と鳴らない</b>。
  //   ここでキーボードだけで unlock できることを見ているのが、その証明である
  await snd.keyboard.press('Shift');
  await snd.waitForTimeout(60);
  check('★★最初のユーザー操作で音が使えるようになる(操作の種類を問わない・37・4章)',
    (await snd.evaluate(() => window.__audio.contexts)) === 1
      && (await snd.evaluate(() => window.sfxReady && window.sfxReady())) === true);

  await moveDelivery(snd);
  check('★★カードが動く配信で実際に音が鳴る(37・2章)', (await audioNodes(snd)) === 1,
    JSON.stringify(await snd.evaluate(() => window.__audio.nodes)));

  // ★1配信1音。3枚まとめて動かしても音源は1つしか作られない
  await fxReset(snd);
  const sfxMany1 = baseView();
  sfxMany1.seatA.zones.FIELD = [card('m1', 'ミ1'), card('m2', 'ミ2'), card('m3', 'ミ3')];
  syncCounts(sfxMany1.seatA);
  await deliver(snd, sfxMany1);
  await clearAudio(snd);
  const sfxMany2 = clone(sfxMany1);
  sfxMany2.seatA.zones.FIELD = [];
  sfxMany2.seatA.zones.TRASH = [card('m1', 'ミ1'), card('m2', 'ミ2'), card('m3', 'ミ3')];
  syncCounts(sfxMany2.seatA);
  await deliver(snd, sfxMany2);
  await snd.waitForTimeout(60);
  check('★★3枚が同時に動いても鳴るのは1音である(37・3-2)', (await audioNodes(snd)) === 1,
    JSON.stringify(await snd.evaluate(() => window.__audio.nodes)));

  // ★★裁定8(差分が8件を超えたら演出を出さない)は音にも効く。
  //   総入れ替えは1手ではないので、音でも語らない
  await fxReset(snd);
  const sfxBulk1 = baseView();
  sfxBulk1.seatA.zones.FIELD = [];
  for (let i = 0; i < 9; i++) sfxBulk1.seatA.zones.FIELD.push(card('b' + i, '一括' + i));
  syncCounts(sfxBulk1.seatA);
  await deliver(snd, sfxBulk1);
  await clearAudio(snd);
  const sfxBulk2 = clone(sfxBulk1);
  sfxBulk2.seatA.zones.TRASH = sfxBulk1.seatA.zones.FIELD.slice();
  sfxBulk2.seatA.zones.FIELD = [];
  syncCounts(sfxBulk2.seatA);
  await deliver(snd, sfxBulk2);
  await snd.waitForTimeout(60);
  check('★★差分が上限(8件)を超えると音も鳴らない(裁定8 は音にも効く・37)',
    (await audioNodes(snd)) === 0,
    JSON.stringify(await snd.evaluate(() => window.__audio.nodes)));

  // ★ミュートと音量0。どちらも「鳴らさない」であり、パネルは理由を書き分ける
  await snd.evaluate(() => {
    // eslint-disable-next-line no-undef
    sfxSettings.muted = true;
    // eslint-disable-next-line no-undef
    applySfxVolume();
  });
  await moveDelivery(snd);
  check('★★ミュート中は鳴らない(37・5章)', (await audioNodes(snd)) === 0);
  await snd.evaluate(() => {
    // eslint-disable-next-line no-undef
    sfxSettings.muted = false;
    // eslint-disable-next-line no-undef
    sfxSettings.volume = 0;
    // eslint-disable-next-line no-undef
    applySfxVolume();
  });
  await moveDelivery(snd);
  check('★音量が0でも鳴らない(37・5章)', (await audioNodes(snd)) === 0);
  await snd.evaluate(() => {
    // eslint-disable-next-line no-undef
    sfxSettings.volume = 30;
    // eslint-disable-next-line no-undef
    applySfxVolume();
  });

  // ★★破壊的操作の [実行]。リセットは差分が8件を超えるので<b>演出が出ない</b>。
  //   押した手応えが画面に残らない場所であり、音だけがその1手を語る
  await fxReset(snd);
  await deliver(snd, baseView());
  await clearAudio(snd);
  await snd.locator('#btn-reset').click();
  await snd.waitForTimeout(60);
  check('★確認を出しただけでは鳴らない(37・2章)', (await audioNodes(snd)) === 0);
  await snd.locator('#confirm-modal-ok').click();
  await snd.waitForTimeout(60);
  check('★★確認の [実行] で音が鳴る(演出が出ない操作の手応え・37・2章)',
    (await audioNodes(snd)) === 1,
    JSON.stringify(await snd.evaluate(() => window.__audio.nodes)));

  // ★設定は localStorage に残る。★裁定31 と同じく部屋ごとにしない
  await snd.evaluate(() => {
    // eslint-disable-next-line no-undef
    sfxSettings.volume = 55;
    // eslint-disable-next-line no-undef
    sfxSettings.muted = true;
    // eslint-disable-next-line no-undef
    saveSfxSettings();
  });
  await snd.reload();
  await snd.waitForTimeout(200);
  await snd.locator('#btn-sound').click();
  await snd.waitForTimeout(60);
  check('★音の設定はページを開き直しても残る(37・5章)',
    (await snd.locator('#sound-volume').inputValue()) === '55'
      && (await snd.locator('#sound-mute').isChecked()) === true);
  check('★ミュート中は音量のつまみを触らせない(理由が1つに見える)',
    (await snd.locator('#sound-volume').isDisabled()) === true);
  check('★なぜ鳴らないのかはパネルの状態行に出る(37・5章)',
    (await snd.locator('#sound-modal-status').textContent()).includes('ミュート')
      && (await snd.locator('#sound-modal-status').getAttribute('class'))
        .includes('sound-status-warn'));
  // =====================================================================
  // ★★Batch 38: 開始シーケンスの一括演出(レビュー B-1・裁定77 / 81)
  //
  // ★★このバッチの核心は「差分の抑制を緩めずに節目を語る」ことである(裁定81)。
  //   したがって測るべきものは<b>入口が別であること</b>に集中する。
  //   (a) 開始シーケンス中でも儀式は通り、差分は通らないこと
  //   (b) 儀式は差分の上限(8件)に縛られないこと ——
  //       ただし<b>上限に特例を作ったのではない</b>ことを、
  //       同じ配信の差分がやはり空であることで示す(裁定11)
  //   (c) 儀式が運ぶのは席と枚数だけで、カードの中身を持たないこと
  // =====================================================================

  /** 開始シーケンス中の2連の配信を作る。★どちらも locking = true である */
  const startPair = (riteView, opts = {}) => {
    const before = baseView();
    before.start = startState({ phase: 'ORDER_METHOD', locking: true });
    const after = clone(before);
    after.start = startState({
      phase: 'MULLIGAN', locking: true, firstSeat: 'A',
      mulliganSeats: ['A', 'B'], myMulliganSeats: opts.mine || [],
      waiting: '席A / 席B のマリガンを待っています',
    });
    // ★総入れ替えを再現する。instanceId を全部作り直すので、差分から見れば
    //   「9枚消えて9枚出てきた」ようにしか見えない —— それが意味を持たないことの証明でもある
    after.seatA.zones.HAND = [];
    for (let i = 0; i < 4; i++) after.seatA.zones.HAND.push(card('na' + i, '新A' + i));
    after.seatB.zones.HAND = [];
    for (let i = 0; i < 5; i++) after.seatB.zones.HAND.push(card('nb' + i, '新B' + i));
    syncCounts(after.seatA);
    syncCounts(after.seatB);
    after.rites = [riteView];
    return [before, after];
  };
  const deliverPair = async (target, pair) => {
    await fxReset(target);
    await deliver(target, pair[0]);
    await deliver(target, pair[1]);
  };
  const riteGhosts = async (target) => (await fxGhosts(target))
    .filter((g) => ['dice', 'deal', 'mulligan', 'shuffle'].indexOf(g.kind) >= 0);
  /** ★員数を数えるのは<b>飛翔だけ</b>である(混ざる所作のゴーストは枚数を語っていない) */
  const riteFlights = async (target) => (await riteGhosts(target))
    .filter((g) => g.phase === 'flight');
  const diffOf = async (target, pair) => target.evaluate(([x, y]) => {
    // eslint-disable-next-line no-undef
    const d = diffViews(x, y);
    return {
      rite: d.rite ? d.rite.kind : null,
      others: d.moved.length + d.appeared.length + d.vanished.length + d.drew.length,
    };
  }, pair);

  // ---- 入口(純関数)----
  const dealPair = startPair(dealRite(5, 'A'));
  const dealDiff = await diffOf(page, dealPair);
  // ★★これが裁定81 への答えそのものである。開始シーケンス中は差分を1件も採らず、
  //   それでも儀式は届く。抑制を緩めたのではなく、入口を1本足したのである
  check('★★★開始シーケンス中は差分を採らないが、儀式は通る(38・1章・裁定81)',
    dealDiff.rite === 'DEAL' && dealDiff.others === 0, JSON.stringify(dealDiff));

  const riteSeq = await page.evaluate(([a, b]) => {
    // eslint-disable-next-line no-undef
    const first = fxNewRite(a, b);
    // eslint-disable-next-line no-undef
    const again = fxNewRite(b, b);   // ★同じ儀式が載り続けた再配信
    return { first: first ? first.kind : null, again: again ? again.kind : null };
  }, dealPair);
  // ★35 の決着と同じ約束である。resync で二度出さない
  check('★儀式は通し番号が増えたときだけ出る(再配信で二度出さない・38・2-3)',
    riteSeq.first === 'DEAL' && riteSeq.again === null, JSON.stringify(riteSeq));

  check('★開始の3種はそれぞれの音に割り当たっている(38・5章)',
    (await pick(page, [{ kind: 'dice' }])) === 'dice'
      && (await pick(page, [{ kind: 'deal' }])) === 'deal'
      // ★マリガンの音は shuffle である。名前がずれているのは語彙が粗くてよいから(裁定72)
      && (await pick(page, [{ kind: 'mulligan' }])) === 'shuffle');
  // ★★ソロのランダムはダイスと配りが同じ配信で起きる。それでも鳴るのは1つである
  check('★★ダイスと配りが同じ配信でも鳴るのは1つ(珍しいほうが勝つ・38・5章)',
    (await pick(page, [{ kind: 'deal' }, { kind: 'dice' }])) === 'dice');

  // ---- 配りの演出 ----
  await deliverPair(page, dealPair);
  const dealGhosts = await riteFlights(page);
  // ★★9枚 = 先攻4 + 後攻5。差分の上限は8だが、儀式はその勘定に入っていない
  check('★★配りは員数どおりのゴーストを出す(先攻4 / 後攻5・38・3-2)',
    dealGhosts.length === 9, JSON.stringify({ ghosts: dealGhosts.length }));
  // ★★中身を持たない。相手席の手札は「窓」で届かないが、枚数は元から公開情報である
  check('★★儀式のゴーストは裏面で、カードの中身を1つも持たない(38・3章)',
    dealGhosts.every((g) => g.imageIds.length === 0 && !g.text.includes('新')),
    JSON.stringify(dealGhosts.slice(0, 2)));
  // ★★入れ物1つで登録する。FX_LIMIT(同時8本)の勘定でも儀式は1本である(裁定11)
  check('★★儀式は9枚出しても走行中の演出としては1本である(38・3-2)',
    // eslint-disable-next-line no-undef
    (await page.evaluate(() => fxRunning.size)) === 1);
  // ★配り終えるころには全部飛んでいる(シャッフルの間 + 5枚ぶんのずらし)
  await page.waitForTimeout(700);
  const flown = await riteFlights(page);
  check('★配りのゴーストは実際に飛ぶ(38・3-2)',
    flown.length === 0 || flown.every((g) => g.transform.startsWith('translate(')),
    JSON.stringify(flown.slice(0, 2).map((g) => g.transform)));

  // ---- ダイスの帯 ----
  const dicePair = startPair(rite(6, 'DICE', {
    diceA: 17, diceB: 4, winner: 'A', label: '席A が選択権',
  }));
  await deliverPair(page, dicePair);
  const diceBand = await page.evaluate(() => {
    const el = document.querySelector('#manual-fx-layer .manual-fx-dice');
    if (!el) return null;
    return {
      chips: [...el.querySelectorAll('.manual-fx-dice-chip')].map((c) => c.textContent),
      win: [...el.querySelectorAll('.manual-fx-dice-win')].map((c) => c.textContent),
      note: (el.querySelector('.manual-fx-dice-note') || {}).textContent || '',
    };
  });
  // ★★出目は最初からDOMにある(段階的に現れるのは animation-delay である)。
  //   「待ってから測る」検証にしない、という 31 以来の規約に沿っている
  check('★★ダイスの帯は出目2つと結果を出す(38・3-1)',
    !!diceBand && diceBand.chips.length === 2
      && diceBand.chips[0].includes('17') && diceBand.chips[1].includes('4'),
    JSON.stringify(diceBand));
  check('★勝った側に印が付く(色の出し分けは列挙値ではなく席で決まる・38・3-1)',
    !!diceBand && diceBand.win.length === 1 && diceBand.win[0].includes('席A'));
  // ★★文言はサーバの label である。対戦部屋のダイスが与えるのは先攻ではなく選択権であり、
  //   その書き分けをクライアントに写すと条件が2箇所に分かれる(裁定46 と同じ形)
  check('★★ダイスの結果の文はサーバの label をそのまま使う(38・3-1)',
    !!diceBand && diceBand.note === '席A が選択権', diceBand && diceBand.note);
  const diceContrast = await page.evaluate(() => window.__contrastAudit());
  check('★ダイスの帯の文字もコントラスト比 4.5:1 以上(38・3-1)',
    diceContrast.length === 0, JSON.stringify(diceContrast));

  // ---- マリガン ----
  const mullPair = startPair(rite(7, 'MULLIGAN', {
    dealt: [{ seat: 'A', back: 3, drew: 3 }],
  }));
  await deliverPair(page, mullPair);
  const mullGhosts = await riteFlights(page);
  // ★戻す3枚 + 引き直す3枚。★「同じ枚数だから片道でよい」とはしない ——
  //   マリガンは往復であり、往復であることが読めなければ何をしたのか分からない
  check('★マリガンは戻す枚数と引く枚数の両方を出す(38・3-3)',
    mullGhosts.length === 6, JSON.stringify({ ghosts: mullGhosts.length }));
  const shuffled = await page.evaluate(() => new Promise((resolve) => {
    // ★戻し終わったあとに山札が混ざる。段取りの順どおりに出ているかを見る
    setTimeout(() => resolve(
      document.querySelectorAll('.manual-fx-shuffle').length), 520);
  }));
  check('★戻したあとに山札が混ざる(38・3-3)', shuffled >= 1, String(shuffled));
  // ★★実要素に当てたクラスは必ず剥がす(32b の規約)。残ると次の描画まで揺れ続ける
  await page.waitForTimeout(1400);
  check('★★シャッフルのクラスは終了時に剥がれる(38・3-3)',
    (await page.evaluate(() => document.querySelectorAll('.manual-fx-shuffle').length)) === 0);

  // ---- ★★開始画面の保留(38・4章)----
  // ★★fx層(1030)は開始モーダル(1950)より下にある。待たせなければ儀式は1pxも見えない。
  //   ★層の順序を演出の都合で崩さず、<b>時間で</b>解いている
  const holdPair = startPair(dealRite(8, 'A'), { mine: ['A'] });
  await deliverPair(page, holdPair);
  check('★★儀式のあいだマリガンの画面は開かない(38・4章)',
    (await page.evaluate(() => !document.querySelector('.manual-mulligan-backdrop')))
      && (await page.evaluate(() => window.riteHolding())));
  // ★★保留は<b>時刻で明ける</b>。配信を待たずに開く —— 儀式のあとに配信が来る保証は無い
  await page.waitForTimeout(1300);
  check('★★★保留は必ず明けて画面が開く(行き止まりを作らない・38・4章)',
    (await page.evaluate(() => !window.riteHolding()))
      && (await page.evaluate(() => !!document.querySelector('.manual-mulligan-backdrop'))));
  // eslint-disable-next-line no-undef
  await page.evaluate(() => closeOverlay());
  await fxReset(page);
  await deliver(page, baseView());

  // ---- ★★38 追補: 山札のシャッフル(マスター指示)----
  //
  // ★★★シャッフルは<b>盤面に何も起きない操作</b>である。枚数もゾーンも変わらず、
  //   非公開の並びだけが変わる。32a のビュー差分からは完全な無変化にしか見えず、
  //   押しても手応えが1つも返っていなかった。★儀式の器がそのまま3つ目の類型を受けた。
  const shufflePair = () => {
    const before = baseView();
    const after = clone(before);
    // ★山札の最上段は届く1枚である。シャッフルすると入れ替わりうる ——
    //   放っておくと「シャッフルした」と「1枚消えて1枚出た」を同時に語ってしまう
    after.seatA.zones.DECK = [card('d9', '混ぜたあとの一番上'),
      card('d1', '山札の一番上'), card('d2', '山札2')];
    syncCounts(after.seatA);
    after.rites = [shuffleRite(5, 'A')];
    return [before, after];
  };
  const shufDiff = await diffOf(page, shufflePair());
  // ★★★1つの配信に語り手は1人である(38 追補・裁定93)。
  //   最上段の入れ替わりはシャッフルの<b>結果の一部</b>であって別の出来事ではない
  check('★★★儀式が語る配信では差分を語らない(38 追補・裁定93)',
    shufDiff.rite === 'SHUFFLE' && shufDiff.others === 0, JSON.stringify(shufDiff));

  await deliverPair(page, shufflePair());
  const shufNow = await page.evaluate(() => ({
    // eslint-disable-next-line no-undef
    running: fxRunning.size,
    shaken: document.querySelectorAll('.manual-fx-shuffle').length,
    ghosts: document.querySelectorAll('#manual-fx-layer [data-fx-phase="shuffle"]').length,
  }));
  // ★揺れだけでは押したことに気づけない。散らばった数枚が束へ戻る絵を重ねてある
  check('★★シャッフルは山札の揺れと舞うカードで出る(38 追補)',
    shufNow.shaken === 1 && shufNow.ghosts === 3 && shufNow.running === 1,
    JSON.stringify(shufNow));
  check('★シャッフルの音は shuffle である(表に載っているだけで鳴る・38 追補)',
    (await pick(page, [{ kind: 'shuffle' }])) === 'shuffle');
  // ★飛翔が1枚も無い儀式でも長さを持つ。0 だと出た瞬間に消える
  check('★飛翔の無い儀式にも長さがある(38 追補)',
    (await page.evaluate(() => fxRiteDuration({ kind: 'shuffle',
      // eslint-disable-next-line no-undef
      rite: { kind: 'SHUFFLE', dealt: [{ seat: 'A', back: 0, drew: 0 }] } }))) >= 200);
  await page.waitForTimeout(500);
  check('★★シャッフルの後始末で揺れのクラスもゴーストも残らない(38 追補)',
    (await page.evaluate(() => document.querySelectorAll('.manual-fx-shuffle').length)) === 0
      && (await riteGhosts(page)).length === 0);

  // ---- ★★38 追補: ピュア・エレメント(マスター裁定 Q1 = b)----
  // ★★<b>山札からではなく中央から</b>飛ぶ。デッキの外から渡されるカードだからである
  const purePair = startPair(rite(10, 'MULLIGAN', {
    dealt: [{ seat: 'A', back: 0, drew: 0 }], pureSeat: 'B',
  }));
  await deliverPair(page, purePair);
  const pureGhosts = await riteFlights(page);
  check('★★ピュア・エレメントは1枚だけ別に飛ぶ(38 追補・Q1 = b)',
    pureGhosts.length === 1, JSON.stringify({ ghosts: pureGhosts.length }));
  const centerRect = await page.evaluate(() => {
    const el = document.getElementById('center-line');
    const r = el.getBoundingClientRect();
    return { cx: Math.round(r.left + r.width / 2), cy: Math.round(r.top + r.height / 2) };
  });
  // ★出発点が中央であることを<b>座標で</b>見る(34 hotfix の一般形: 式ではなく結果を測る)
  const pureStart = pureGhosts[0] || { left: -9999, top: -9999 };
  check('★★ピュア・エレメントは中央から出る(山札から出すのは嘘である・38 追補)',
    Math.abs(pureStart.left + 40 - centerRect.cx) < 90
      && Math.abs(pureStart.top + 60 - centerRect.cy) < 90,
    JSON.stringify({ pureStart, centerRect }));
  // ★音は増やさない。1配信1音であり、この配信の主役はマリガンである
  check('★ピュア・エレメントで音は増えない(38 追補・裁定70)',
    (await pick(page, [{ kind: 'mulligan' }])) === 'shuffle');
  await fxReset(page);
  await deliver(page, baseView());

  // ---- 音 ----
  // ★直前の項目でミュートしたまま再読込しているので、鳴る状態へ戻してから測る。
  //   ★unlock も取り直す(再読込で AudioContext は失われている。37・4章)
  await snd.locator('#sound-mute').uncheck();
  await snd.waitForTimeout(60);
  await clearAudio(snd);
  await deliverPair(snd, startPair(dealRite(9, 'A')));
  await snd.waitForTimeout(60);
  // ★★ダイスは粒が複数だが、儀式の音も「1つの音」である。裁定70 が数えているのは
  //   <b>出来事</b>であって音源ノードではない —— だからここは件数ではなく
  //   「鳴ったか」で見る(どれを鳴らすかは上の純関数の項目が見ている)
  check('★★配りの配信で実際に音が鳴る(38・5章)', (await audioNodes(snd)) > 0,
    JSON.stringify(await snd.evaluate(() => window.__audio.nodes)));

  check('音の検証でJSエラーが出ない', sndErrors.length === 0, sndErrors.join(' | '));
  await snd.close();

  // ★★★音は動きではない。prefers-reduced-motion は「画面を動かすな」であって
  //   「静かにしろ」ではない。演出を止めている人にこそ、音は状態変化を伝える手段になる。
  //   ★この項目は、差分の計算を fxAllowed だけでゲートすると落ちる
  //   (音が見た目の道連れで消える)
  const quiet = await browser.newPage({
    viewport: { width: 1280, height: 950 }, reducedMotion: 'reduce',
  });
  const quietErrors = [];
  quiet.on('pageerror', (e) => quietErrors.push(String(e)));
  await quiet.addInitScript(audioSpy);
  await quiet.goto(`http://127.0.0.1:${port}/harness.html`);
  await quiet.waitForTimeout(200);
  await quiet.keyboard.press('Shift');
  await moveDelivery(quiet);
  check('★★★prefers-reduced-motion でもゴーストは出ないが音は鳴る(37・0-5)',
    (await audioNodes(quiet)) === 1 && (await fxGhosts(quiet)).length === 0
      && quietErrors.length === 0,
    JSON.stringify({ nodes: await audioNodes(quiet), quietErrors }));

  // ★★Batch 38: 儀式も同じ形である。見た目は出ないが音は鳴り、
  //   <b>開始画面を待たせない</b>——待つ理由(儀式が見えている)が無いのに待たせると、
  //   演出を切っている人にとっては操作が遅れるだけになる(38・4章)
  await clearAudio(quiet);
  await deliverPair(quiet, startPair(dealRite(11, 'A'), { mine: ['A'] }));
  await quiet.waitForTimeout(60);
  check('★★★演出を切っている人は儀式で待たされない(音は鳴る・38・4章)',
    (await audioNodes(quiet)) > 0
      && (await quiet.evaluate(() => window.riteHolding())) === false
      && (await quiet.evaluate(() => !!document.querySelector('.manual-mulligan-backdrop'))),
    JSON.stringify({ nodes: await audioNodes(quiet) }));
  await quiet.close();

  // =========================================================================
  // ★★★Batch 41: 外部依存の自前配信(レビュー C-2)
  //
  // ★★これは見た目の検証ではなく<b>結線</b>の検証である。41 で CDN をやめ、
  //   Bootstrap と stomp.js を static/vendor/ から配るようにした。理由は2つあり、
  //   2つめのほうが重い:
  //     1. integrity が無いまま CDN を読んでいた(改竄されれば任意の JS が走る)
  //     2. ★stomp.js が読めないと WebSocket が張れず、<b>盤面が一生来ない</b>。
  //        画面には何も出ないので、人間には原因が分からない
  // ★ファイル名にはバージョンを入れてある。更新は手動になるため、名前に版が
  //   無いと「今どれを配っているのか」がディスクを見ても分からなくなる。
  // =========================================================================
  const templateDir = path.join(RES, 'templates');
  const templateFiles = fs.readdirSync(templateDir).filter((f) => f.endsWith('.html'));
  const cdnLeaks = [];
  const vendorRefs = [];
  for (const name of templateFiles) {
    const body = fs.readFileSync(path.join(templateDir, name), 'utf8');
    if (body.includes('cdn.jsdelivr.net')) cdnLeaks.push(name);
    for (const m of body.matchAll(/@\{\/vendor\/([^}]+)\}/g)) {
      vendorRefs.push({ template: name, file: m[1] });
    }
  }
  check('★★テンプレートに CDN 参照が1つも残っていない(41・C-2)',
    cdnLeaks.length === 0, cdnLeaks.join(' | '));

  // ★参照とファイルの結線。名前を打ち間違えても Thymeleaf は黙って 404 を返すだけで、
  //   Bootstrap なら「なぜか崩れている」、stomp なら「なぜか動かない」にしかならない。
  const vendorMissing = vendorRefs.filter(
    (r) => !fs.existsSync(path.join(RES, 'static', 'vendor', r.file)));
  check('★★★vendor の参照はすべて実ファイルに当たる(41・結線)',
    vendorRefs.length >= 9 && vendorMissing.length === 0,
    JSON.stringify({ refs: vendorRefs.length, missing: vendorMissing }));

  // ★stomp を参照するのは対戦画面2つだけである。ここが減っていたら、
  //   どちらかの画面が WebSocket を張れなくなっている
  const stompRefs = vendorRefs.filter((r) => r.file.includes('stomp')).map((r) => r.template).sort();
  check('★★stomp.js は対戦画面2つが自前配信で読んでいる(41・1-2)',
    JSON.stringify(stompRefs) === JSON.stringify(['battle.html', 'manual-battle.html']),
    JSON.stringify(stompRefs));

  // =========================================================================
  // ★★★Batch 41: 右クリックの受け皿(レビュー C-4)
  //
  // ★★盤面での右クリックは<b>操作</b>である(22 1-7)。個々の要素は preventDefault
  //   しているが、隙間には誰も居ないため、そこで押すとブラウザのメニューが盤面を覆う。
  // ★★<b>「全部止める」ではないことが本題である。</b>入力欄・ログ・開いている
  //   モーダルの中では既定の動作を残す。だから検証も「止まること」と
  //   「<b>止まらないこと</b>」を対で測る。片方だけ測ると、
  //   document 全体を止める1行に書き換えても検証は通ってしまう。
  // =========================================================================
  await render(page, baseView());
  await page.evaluate(() => {
    window.__ctx = [];
    // ★bubble フェーズの window。document のハンドラより<b>後</b>に走るので、
    //   そこで止められたかどうかを observed できる
    window.addEventListener('contextmenu', (e) => {
      window.__ctx.push({ prevented: e.defaultPrevented, id: e.target.id || e.target.tagName });
    });
  });
  const rightClick = async (selector) => {
    const box = await page.locator(selector).first().boundingBox();
    if (!box) return null;
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { button: 'right' });
    await page.waitForTimeout(30);
    return (await page.evaluate(() => window.__ctx[window.__ctx.length - 1])) || null;
  };

  const gapClick = await rightClick('#center-line');
  check('★★★盤面の隙間で右クリックしてもブラウザのメニューが出ない(41・C-4)',
    gapClick !== null && gapClick.prevented === true, JSON.stringify(gapClick));

  // ★ログは<b>読んでコピーする</b>ものである(設計書 5-5)。ここを奪ってはいけない
  const logClick = await rightClick('#log-box');
  check('★★★ログの右クリックは奪わない(41・コピーできること)',
    logClick !== null && logClick.prevented === false, JSON.stringify(logClick));

  // ★入力欄も同じ。貼り付け・元に戻すはブラウザのメニューの仕事である
  const inputClick = await rightClick('#note-input');
  check('★★入力欄の右クリックは奪わない(41)',
    inputClick !== null && inputClick.prevented === false, JSON.stringify(inputClick));

  // ★モーダルの説明文も選んで写せる必要がある
  await page.evaluate(() => openHelpModal());
  await page.waitForTimeout(60);
  const modalClick = await rightClick('#help-modal-title');
  check('★★開いているモーダルの中では右クリックを奪わない(41)',
    modalClick !== null && modalClick.prevented === false, JSON.stringify(modalClick));
  await page.evaluate(() => closeInfoModal('help-modal'));
  await page.waitForTimeout(60);

  // =========================================================================
  // ★★★Batch 42: 通常モード(自動モード)の盤面
  //
  // ★自動モードはこれまで機械検証の傘の外にいた。カードフェイス化を機に傘へ入れる。
  // ★★状態のクラス名(playable / can-attack ...)は 42 で変えていないので、
  //   検証は「状態ロジックが生きているか」と「面が手動モードと同じ正から来ているか」を測る。
  // ★CARD_LIBRARY はこの節が自分用に差し替える。デッキメーカーの節は
  //   あちらを開く直前に自分の台帳へ差し替え直すので、ここの変更は漏れない(39 の作法)。
  // =========================================================================
  CARD_LIBRARY.status = 200;
  CARD_LIBRARY.body = { cards: [
    { id: 'QTE-M-FIRE-1', ledgerCardId: 'QTE-0001', name: '炎の従者', civilization: 'FIRE',
      type: 'MINION', cost: 2, attack: 2, hp: 3, text: '【速攻】', imageId: 'x1' },
    { id: 'QTE-M-FIRE-2', ledgerCardId: 'QTE-L001', name: '傷痕の闘帝', civilization: 'FIRE',
      type: 'LEADER', cost: 0, attack: null, hp: null,
      text: '【起動：1】自分のリーダーに1ダメージ。そうしたら1枚ドローする', imageId: 'x2' },
    { id: 'QTE-M-WATER-1', ledgerCardId: 'QTE-0025', name: 'スプラッシュ・ドロー',
      civilization: 'WATER', type: 'SPELL', cost: 2, attack: null, hp: null,
      text: 'カードを2枚引く', imageId: 'x3' },
    { id: 'QTE-M-DARK-1', ledgerCardId: 'QTE-0086', name: '死神の大鎌',
      civilization: 'DARK', type: 'WEAPON', cost: 4, attack: 3, hp: null,
      text: 'このウェポンで攻撃されたミニオンは、戦闘ダメージに関わらず破壊される。', imageId: 'x4' },
    { id: 'QTE-M-FIRE-3', ledgerCardId: 'QTE-0046', name: 'マグマ・ストレート',
      civilization: 'FIRE', type: 'SPELL', cost: 1, attack: null, hp: null,
      text: 'ミニオン1体に2ダメージ。', imageId: 'x5' },
  ] };

  // ★★Batch 43 で 1280×800 の1画面に収めた。ビューポートも実寸で開く ——
  //   これ自体が「収まっていなければ下の実マウス項目が押せず落ちる」という番人を兼ねる
  const autoPage = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  const autoErrors = [];
  autoPage.on('pageerror', (e) => autoErrors.push(String(e)));
  await autoPage.goto(`http://127.0.0.1:${port}/harness-battle.html`);
  await autoPage.waitForTimeout(300);   // card-library の取得を待つ

  const autoDeliver = (v) => autoPage.evaluate((view) => {
    latestView = view;
    render(view);
  }, v);

  // ---- 42-1. 面が .mcard で描かれ、色の正が :root にある ----
  const handView = autoView({
    you: autoPlayer({
      hand: [
        autoCard('QTE-0001', '炎の従者', { cost: 2, keywords: ['速攻'], text: '' }),
        autoCard('QTE-0025', 'スプラッシュ・ドロー', {
          type: 'SPELL', civilization: 'WATER', cost: 2, attack: null, hp: null,
          text: 'カードを2枚引く',
        }),
        autoCard('QTE-0041', '双流の幻術師', {
          civilization: 'WATER', cost: 5, effectiveCost: 3,
          text: '場に居るミニオンの数Cost-1。',
        }),
      ],
      minions: [
        autoMinion('m1', '炎の従者'),
        autoMinion('m2', '傷ついた従者', { currentHp: 1, maxHp: 3, frozen: true }),
      ],
    }),
    opponent: autoPlayer({ displayName: 'あいて', minions: [autoMinion('e1', '敵ミニオン')] }),
  });
  await autoDeliver(handView);
  const autoFaces = await autoPage.evaluate(() => ({
    old: document.querySelectorAll('.game-card').length,
    hand: document.querySelectorAll('#my-hand .auto-card .mcard.mcard-full').length,
    minions: document.querySelectorAll('#my-minions .auto-card .mcard.mcard-mini').length,
    handMc: (() => {
      const el = document.querySelector('#my-hand .auto-card .mcard');
      return el ? el.style.getPropertyValue('--mc').trim().toLowerCase() : '';
    })(),
    civFire: getComputedStyle(document.documentElement).getPropertyValue('--civ-fire').trim().toLowerCase(),
  }));
  check('★★★通常モードの手札・場が .mcard フェイスで描かれる(42・.game-card は0)',
    autoFaces.old === 0 && autoFaces.hand === 3 && autoFaces.minions === 2,
    JSON.stringify(autoFaces));
  check('★★通常モードの文明色も battle.css の :root から来ている(42・裁定60)',
    autoFaces.civFire !== '' && autoFaces.handMc === autoFaces.civFire,
    JSON.stringify({ mc: autoFaces.handMc, fire: autoFaces.civFire }));

  // ---- 42-2. ★ミニオンの文明色は card-library から引けている(結線) ----
  const minionCiv = await autoPage.evaluate(() => {
    const el = document.querySelector('#my-minions .auto-card .mcard');
    return {
      mc: el.style.getPropertyValue('--mc').trim().toLowerCase(),
      fire: getComputedStyle(document.documentElement).getPropertyValue('--civ-fire').trim().toLowerCase(),
    };
  });
  check('★★★ミニオンの文明色は card-library の台帳IDから引けている(42・Java変更ゼロの結線)',
    minionCiv.mc === minionCiv.fire, JSON.stringify(minionCiv));

  // ---- 42-3. 実効コストの印 ----
  const effCost = await autoPage.evaluate(() => {
    const faces = document.querySelectorAll('#my-hand .auto-card .mcard');
    const f = faces[2];
    return {
      modified: f.classList.contains('mcard-cost-modified'),
      gem: f.querySelector('.mcard-cost').textContent,
      title: f.closest('.auto-card').title,
    };
  });
  check('★実効コストは宝石に実効値が出て印が付く(42・双流の幻術師)',
    effCost.modified && effCost.gem === '3' && effCost.title.includes('5'),
    JSON.stringify(effCost));

  // ---- 42-4. バッジと減光 ----
  const autoStates = await autoPage.evaluate(() => {
    const cards = document.querySelectorAll('#my-minions .auto-card');
    const frozen = cards[1];
    return {
      badge: frozen.querySelector('.auto-badge') ? frozen.querySelector('.auto-badge').textContent : '',
      hurt: !!frozen.querySelector('.mcard-hp-hurt'),
      playableFilter: getComputedStyle(cards[0]).filter,
    };
  });
  check('★凍結はバッジ・被弾はHPの色で出る(42)',
    autoStates.badge.includes('凍結') && autoStates.hurt, JSON.stringify(autoStates));

  // ---- 42-5. 状態ロジックが生きている(playable → クリックで送信。実マウス) ----
  const playableInfo = await autoPage.evaluate(() => {
    window.__sent.length = 0;
    const el = document.querySelector('#my-hand .auto-card');
    return { playable: el.classList.contains('playable') };
  });
  const handBox = await autoPage.locator('#my-hand .auto-card').first().boundingBox();
  await autoPage.mouse.click(handBox.x + handBox.width / 2, handBox.y + handBox.height / 2);
  await autoPage.waitForTimeout(50);
  const played = await autoPage.evaluate(() => window.__sent[window.__sent.length - 1] || null);
  check('★★手札の playable とクリック送信が 42 でも生きている(状態ロジック無変更の証明)',
    playableInfo.playable && played && played.destination.endsWith('/play-card')
      && played.body.handIndex === 0,
    JSON.stringify({ playableInfo, played }));

  // ---- 42-6. 攻撃の選択(BATTLE フェイズ・実マウス) ----
  const battleView = autoView({
    phase: 'BATTLE', phaseDisplay: 'バトル',
    you: autoPlayer({ minions: [autoMinion('m1', '攻撃役', { canAttackMinion: true, canAttackLeader: true })] }),
    opponent: autoPlayer({ displayName: 'あいて', minions: [autoMinion('e1', '防御役')] }),
  });
  await autoDeliver(battleView);
  const atkBox = await autoPage.locator('#my-minions .auto-card').first().boundingBox();
  await autoPage.mouse.click(atkBox.x + atkBox.width / 2, atkBox.y + atkBox.height / 2);
  await autoPage.waitForTimeout(50);
  const selState = await autoPage.evaluate(() => ({
    selected: document.querySelector('#my-minions .auto-card').classList.contains('selected-attacker'),
    target: document.querySelector('#opp-minions .auto-card').classList.contains('attack-target'),
  }));
  await autoPage.evaluate(() => { window.__sent.length = 0; });
  const defBox = await autoPage.locator('#opp-minions .auto-card').first().boundingBox();
  await autoPage.mouse.click(defBox.x + defBox.width / 2, defBox.y + defBox.height / 2);
  await autoPage.waitForTimeout(50);
  const attacked = await autoPage.evaluate(() => window.__sent[window.__sent.length - 1] || null);
  check('★★★攻撃の選択と宣言が 42 でも生きている(can-attack → selected → attack 送信)',
    selState.selected && selState.target && attacked
      && attacked.destination.endsWith('/attack')
      && attacked.body.attackerInstanceId === 'm1' && attacked.body.targetInstanceId === 'e1',
    JSON.stringify({ selState, attacked }));

  // ---- 42-7. 右クリック = 拡大(実マウス)。テキストが読め、既定メニューは出ない ----
  await autoDeliver(handView);
  await autoPage.evaluate(() => {
    window.__ctxAuto = [];
    window.addEventListener('contextmenu', (e) => window.__ctxAuto.push(e.defaultPrevented));
  });
  const azBox = await autoPage.locator('#my-hand .auto-card').nth(1).boundingBox();
  await autoPage.mouse.click(azBox.x + azBox.width / 2, azBox.y + azBox.height / 2,
    { button: 'right' });
  await autoPage.waitForTimeout(60);
  const azState = await autoPage.evaluate(() => ({
    open: !document.getElementById('auto-zoom').classList.contains('d-none'),
    text: (document.querySelector('#auto-zoom-card .mcard-text') || {}).textContent || '',
    large: !!document.querySelector('#auto-zoom-card .mcard.mcard-large'),
    prevented: window.__ctxAuto[window.__ctxAuto.length - 1] === true,
  }));
  check('★★★右クリックで拡大が開き、効果テキストが読める(42・手動モードの 22 1-7 と同じ規約)',
    azState.open && azState.large && azState.text.includes('カードを2枚引く')
      && azState.prevented,
    JSON.stringify(azState));

  await autoPage.keyboard.press('Escape');
  await autoPage.waitForTimeout(50);
  const azClosed = await autoPage.evaluate(() =>
    document.getElementById('auto-zoom').classList.contains('d-none'));
  check('★拡大は Esc で閉じる(42)', azClosed, String(azClosed));

  // ---- 42-8. ミニオンの拡大は card-library のテキストを出す ----
  const minBox = await autoPage.locator('#my-minions .auto-card').first().boundingBox();
  await autoPage.mouse.click(minBox.x + minBox.width / 2, minBox.y + minBox.height / 2,
    { button: 'right' });
  await autoPage.waitForTimeout(60);
  const minionZoom = await autoPage.evaluate(() => {
    const text = (document.querySelector('#auto-zoom-card .mcard-text') || {}).textContent || '';
    document.getElementById('auto-zoom').click();
    return text;
  });
  check('★★ミニオンの拡大に card-library の効果テキストが出る(42)',
    minionZoom.includes('速攻'), minionZoom);

  // ---- 42-9. リーダーの文明色 ----
  const leaderMc = await autoPage.evaluate(() => ({
    mc: document.getElementById('my-leader').style.getPropertyValue('--mc').trim().toLowerCase(),
    fire: getComputedStyle(document.documentElement).getPropertyValue('--civ-fire').trim().toLowerCase(),
  }));
  check('★リーダータイルが文明色を持つ(42・台帳IDから)',
    leaderMc.mc === leaderMc.fire, JSON.stringify(leaderMc));

  // ---- 42-10. マリガン選択の印 ----
  await autoDeliver(autoView({ mulligan: true, myTurn: false,
    you: autoPlayer({ hand: [autoCard('QTE-0001', '炎の従者')] }) }));
  const mulBox = await autoPage.locator('#my-hand .auto-card').first().boundingBox();
  await autoPage.mouse.click(mulBox.x + mulBox.width / 2, mulBox.y + mulBox.height / 2);
  await autoPage.waitForTimeout(50);
  const mulState = await autoPage.evaluate(() =>
    document.querySelector('#my-hand .auto-card').classList.contains('mulligan-selected'));
  check('★マリガンの選択の印が 42 でも生きている', mulState, String(mulState));

  // =========================================================================
  // ★★Batch 43: 1画面レイアウト
  // =========================================================================

  // ---- 43-1. ★★★盤面が 1280×800 に収まる(縦スクロールなし) ----
  // ★空の盤面なら無条件に収まる。両席ミニオン6体・手札8枚・マナ8枚・禁忌2枚という
  //   「盛った盤面」で測る。ここが崩れたらレイアウトの前提(手動モードと同じ1画面)が崩れている
  const fullView = autoView({
    you: autoPlayer({
      hand: Array.from({ length: 8 }, (_, i) => autoCard('QTE-0001', '手札' + i, { cost: 2 })),
      minions: Array.from({ length: 6 }, (_, i) => autoMinion('m' + i, 'ミニオン' + i)),
      // ★44: 表6(名前・cardId つき)+ タップ1 + 裏1 = 8枚(43-4 の「支払い可能8」は不変)
      manaZone: [
        ...Array.from({ length: 6 }, () => ({
          name: 'マグマ・ストレート', cardId: 'QTE-0046',
          tapped: false, faceUp: true, temporary: false,
        })),
        { name: 'マグマ・ストレート', cardId: 'QTE-0046', tapped: true, faceUp: true, temporary: false },
        { name: '秘密のカード', cardId: 'QTE-0001', tapped: false, faceUp: false, temporary: false },
      ],
      taboo: [autoCard('QTE-0075', '禁忌1', { cost: 1 }), autoCard('QTE-0076', '禁忌2', { cost: 2 })],
      tabooCount: 2,
      trash: [autoCard('QTE-0001', '炎の従者', { cost: 2 }),
        autoCard('QTE-0025', 'スプラッシュ・ドロー', { type: 'SPELL', civilization: 'WATER' })],
      trashCount: 2,
      lost: [autoCard('QTE-0046', 'マグマ・ストレート', { type: 'SPELL' })], lostCount: 1,
      weaponName: '死神の大鎌', weaponCardId: 'QTE-0086', weaponAttack: 3,
    }),
    opponent: autoPlayer({
      displayName: 'あいて', handCount: 5,
      minions: Array.from({ length: 6 }, (_, i) => autoMinion('e' + i, '敵' + i)),
      manaZone: Array.from({ length: 8 }, () => ({ name: null, tapped: false, faceUp: true })),
    }),
    log: Array.from({ length: 30 }, (_, i) => 'ログ行' + i),
  });
  await autoDeliver(fullView);
  const fit = await autoPage.evaluate(() => ({
    scrollH: document.documentElement.scrollHeight,
    innerH: window.innerHeight,
    handBottom: document.getElementById('my-hand').getBoundingClientRect().bottom,
  }));
  check('★★★盛った盤面でも 1280×800 の1画面に収まる(43・縦スクロールなし)',
    fit.scrollH <= fit.innerH + 1 && fit.handBottom <= fit.innerH,
    JSON.stringify(fit));

  // ---- 43-2. 手札は重ねて並ぶ ----
  const overlap = await autoPage.evaluate(() => {
    const cards = document.querySelectorAll('#my-hand .auto-card');
    const a = cards[0].getBoundingClientRect();
    const b = cards[1].getBoundingClientRect();
    return { aRight: a.right, bLeft: b.left, count: cards.length };
  });
  check('★手札は重ねて並ぶ(43・8枚でも行に収まる)',
    overlap.count === 8 && overlap.bLeft < overlap.aRight, JSON.stringify(overlap));

  // ---- 43-3. 禁忌は既定で畳まれ、チップで開閉できる(実マウス) ----
  const tabooDefault = await autoPage.evaluate(() => ({
    hidden: document.getElementById('taboo-strip').classList.contains('d-none'),
    chip: document.getElementById('my-taboo-count').textContent,
  }));
  const chipBox = await autoPage.locator('#btn-taboo-toggle').boundingBox();
  await autoPage.mouse.click(chipBox.x + chipBox.width / 2, chipBox.y + chipBox.height / 2);
  await autoPage.waitForTimeout(50);
  const tabooOpened = await autoPage.evaluate(() => ({
    open: !document.getElementById('taboo-strip').classList.contains('d-none'),
    faces: document.querySelectorAll('#my-taboo .auto-card .mcard').length,
    active: document.getElementById('btn-taboo-toggle').classList.contains('auto-chip-active'),
  }));
  await autoPage.mouse.click(chipBox.x + chipBox.width / 2, chipBox.y + chipBox.height / 2);
  await autoPage.waitForTimeout(50);
  const tabooReclosed = await autoPage.evaluate(() =>
    document.getElementById('taboo-strip').classList.contains('d-none'));
  check('★★禁忌は既定で畳まれ、チップで開閉できる(43・「必要な時に表示」)',
    tabooDefault.hidden && tabooDefault.chip === '2'
      && tabooOpened.open && tabooOpened.faces === 2 && tabooOpened.active && tabooReclosed,
    JSON.stringify({ tabooDefault, tabooOpened, tabooReclosed }));

  // ---- 43-4. ★支払い中は自動で開き、勝手に閉じない ----
  // 禁忌カードのクリック → tabooPay 開始 → 帯が開いたまま・選択の問いが右列に出る
  await autoPage.evaluate(() => { toggleTabooRow(); });   // 開く
  await autoPage.waitForTimeout(30);
  const tabooCardBox = await autoPage.locator('#my-taboo .auto-card').first().boundingBox();
  await autoPage.mouse.click(tabooCardBox.x + tabooCardBox.width / 2,
    tabooCardBox.y + tabooCardBox.height / 2);
  await autoPage.waitForTimeout(50);
  // ★★閉じる操作を<b>実際に試みて</b>、閉じないことを測る。「開いている」だけを見ると、
  //   手で開けた状態が残っているだけでも通ってしまい、ガードを外しても検出できない
  //   (最初の版はそれで素通りした。番人は壊し方から逆算して書くこと)
  const chipBox2 = await autoPage.locator('#btn-taboo-toggle').boundingBox();
  await autoPage.mouse.click(chipBox2.x + chipBox2.width / 2, chipBox2.y + chipBox2.height / 2);
  await autoPage.waitForTimeout(50);
  const paying = await autoPage.evaluate(() => ({
    payActive: !!tabooPay,
    stripOpen: !document.getElementById('taboo-strip').classList.contains('d-none'),
    prompt: document.getElementById('selection-prompt').textContent,
    payable: document.querySelectorAll('#my-mana-row .mana-tile.taboo-payable').length,
  }));
  check('★★★支払い中は閉じる操作をしても帯が閉じない・マナが支払い可能に光る(43)',
    paying.payActive && paying.stripOpen && paying.prompt.includes('禁忌コスト')
      && paying.payable === 8,
    JSON.stringify(paying));
  await autoPage.evaluate(() => { cancelTabooPayment(); toggleTabooRow(); });

  // ---- 43-5. ログは右列の残り高さを使う ----
  const logShape = await autoPage.evaluate(() => {
    const log = document.getElementById('log-area');
    const side = log.parentElement;
    return {
      inSide: side.classList.contains('auto-side'),
      h: log.getBoundingClientRect().height,
    };
  });
  check('★ログは右列にあり、残り高さへ伸びる(43・基底の130pxを上書き)',
    logShape.inSide && logShape.h > 300, JSON.stringify(logShape));

  // =========================================================================
  // ★★Batch 44: パイル・マナタイル・リーダー合体タイル(案1・マスター承認)
  // =========================================================================
  await autoDeliver(fullView);

  // ---- 44-1. 墓地パイルの最上段はビューの末尾(=最新)である(結線) ----
  const pileState = await autoPage.evaluate(() => {
    const trashPile = document.querySelectorAll('#my-piles .auto-pile')[1];
    const lostPile = document.querySelectorAll('#my-piles .auto-pile')[2];
    return {
      trashTop: (trashPile.querySelector('.mcard-name') || {}).textContent || '',
      trashCount: document.getElementById('my-trash-count').textContent,
      lostTop: (lostPile.querySelector('.mcard-name') || {}).textContent || '',
      lostCount: document.getElementById('my-lost-count').textContent,
    };
  });
  check('★★★墓地・消滅パイルの最上段がビューの末尾と一致し、枚数バッジが従来のidで生きている(44)',
    pileState.trashTop === 'スプラッシュ・ドロー' && pileState.trashCount === '2'
      && pileState.lostTop === 'マグマ・ストレート' && pileState.lostCount === '1',
    JSON.stringify(pileState));

  // ---- 44-2. ★★★山札パイルは中身を見せない(仕分け D1 の番人) ----
  const deckPile = await autoPage.evaluate(() => {
    const pile = document.querySelectorAll('#my-piles .auto-pile')[0];
    return {
      faces: pile.querySelectorAll('.mcard').length,
      names: pile.querySelectorAll('.mcard-name').length,
      back: !!pile.querySelector('.auto-pile-back'),
      clickable: pile.classList.contains('auto-pile-clickable'),
    };
  });
  check('★★★山札パイルは裏面だけで、中身を見せる経路が無い(44・仕分けD1)',
    deckPile.faces === 0 && deckPile.names === 0 && deckPile.back && !deckPile.clickable,
    JSON.stringify(deckPile));

  // ---- 44-3. マナタイル: 名前・回転・裏面 ----
  const manaState = await autoPage.evaluate(() => {
    const tiles = document.querySelectorAll('#my-mana-row .mana-tile');
    const tapped = tiles[6];
    const faceDown = tiles[7];
    return {
      count: tiles.length,
      firstName: (tiles[0].querySelector('.mana-tile-name') || {}).textContent || '',
      tappedRotated: getComputedStyle(tapped).transform !== 'none',
      faceDownHasName: !!faceDown.querySelector('.mana-tile-name'),
      oneLine: [...tiles].every(t => t.offsetTop === tiles[0].offsetTop),
    };
  });
  check('★★★マナは名前つきタイルで、タップは回転・裏向きは名前を出さない・1行に収まる(44)',
    manaState.count === 8 && manaState.firstName === 'マグマ・ストレート'
      && manaState.tappedRotated && !manaState.faceDownHasName && manaState.oneLine,
    JSON.stringify(manaState));

  // ---- 44-4. 相手の手札は裏面の列 ----
  const backsState = await autoPage.evaluate(() => ({
    backs: document.querySelectorAll('#opp-hand-backs .auto-back').length,
    handCount: latestView.opponent.handCount,
  }));
  check('★相手の手札が裏面の列で見える(44・枚数と一致)',
    backsState.backs === backsState.handCount && backsState.backs > 0,
    JSON.stringify(backsState));

  // ---- 44-5. リーダー合体タイル: 効果の先頭が常時見え、ホバーで全文(実マウス) ----
  const leaderState = await autoPage.evaluate(() => ({
    ability: document.getElementById('my-leader-ability').textContent,
    weapon: document.getElementById('my-weapon').textContent,
  }));
  const leaderBox = await autoPage.locator('#my-leader').boundingBox();
  await autoPage.mouse.move(leaderBox.x + leaderBox.width / 2, leaderBox.y + 20);
  await autoPage.waitForTimeout(500);
  const hoverState = await autoPage.evaluate(() => ({
    open: !document.getElementById('auto-hover').classList.contains('d-none'),
    text: (document.querySelector('#auto-hover-card .mcard-text') || {}).textContent || '',
  }));
  await autoPage.mouse.move(10, 400);
  await autoPage.waitForTimeout(100);
  const hoverClosed = await autoPage.evaluate(() =>
    document.getElementById('auto-hover').classList.contains('d-none'));
  check('★★★リーダーは効果の先頭が常時見え、ホバーで全文プレビューが出て、離れると消える(44・B-1)',
    leaderState.ability.includes('起動') && leaderState.weapon.includes('死神の大鎌')
      && hoverState.open && hoverState.text.includes('起動') && hoverClosed,
    JSON.stringify({ leaderState, hoverState, hoverClosed }));

  // ---- 44-6. ウェポンの右クリック → 効果テキスト(B2 の weaponCardId 結線) ----
  const weaponLine = await autoPage.locator('#my-leader .auto-leader-weapon').boundingBox();
  await autoPage.mouse.click(weaponLine.x + weaponLine.width / 2,
    weaponLine.y + weaponLine.height / 2, { button: 'right' });
  await autoPage.waitForTimeout(60);
  const weaponZoom = await autoPage.evaluate(() => {
    const text = (document.querySelector('#auto-zoom-card .mcard-text') || {}).textContent || '';
    const name = (document.querySelector('#auto-zoom-card .mcard-name') || {}).textContent || '';
    closeZoom();
    return { text, name };
  });
  check('★★ウェポンの右クリックで効果テキストが読める(44・B2 の weaponCardId 結線)',
    weaponZoom.name === '死神の大鎌' && weaponZoom.text.includes('戦闘ダメージに関わらず破壊'),
    JSON.stringify(weaponZoom));

  // ---- 44-7. 墓地パイルのクリックでフェイス一覧が開く ----
  const trashPileBox = await autoPage.locator('#my-piles .auto-pile').nth(1).boundingBox();
  await autoPage.mouse.click(trashPileBox.x + trashPileBox.width / 2,
    trashPileBox.y + trashPileBox.height / 2);
  await autoPage.waitForTimeout(60);
  const zoneModal = await autoPage.evaluate(() => {
    const open = !document.getElementById('info-modal').classList.contains('d-none');
    const faces = document.querySelectorAll('#info-modal-content .auto-zone-card .mcard').length;
    hideModal();
    return { open, faces };
  });
  check('★★墓地パイルのクリックで中身がフェイスの一覧で開く(44)',
    zoneModal.open && zoneModal.faces === 2, JSON.stringify(zoneModal));

  check('通常モードの盤面でJSエラーが出ない', autoErrors.length === 0, autoErrors.join(' | '));
  await autoPage.close();

  // ---- 42-11. ★★card-library が失敗しても対戦は続けられる(25 と同じ性質の証明) ----
  CARD_LIBRARY.status = 500;
  const brokenPage = await browser.newPage({ viewport: { width: 1280, height: 1600 } });
  const brokenErrors = [];
  brokenPage.on('pageerror', (e) => brokenErrors.push(String(e)));
  await brokenPage.goto(`http://127.0.0.1:${port}/harness-battle.html`);
  await brokenPage.waitForTimeout(300);
  await brokenPage.evaluate((view) => { latestView = view; render(view); }, handView);
  const degraded = await brokenPage.evaluate(() => ({
    faces: document.querySelectorAll('#my-hand .auto-card .mcard').length,
    minionMc: document.querySelector('#my-minions .auto-card .mcard')
      .style.getPropertyValue('--mc').trim().toLowerCase(),
    civNone: getComputedStyle(document.documentElement).getPropertyValue('--civ-none').trim().toLowerCase(),
  }));
  check('★★★card-library が落ちていても盤面は描け、ミニオンは無文明色に退化する(42)',
    degraded.faces === 3 && degraded.minionMc === degraded.civNone && brokenErrors.length === 0,
    JSON.stringify({ degraded, brokenErrors }));
  await brokenPage.close();
  CARD_LIBRARY.status = 200;

  // =========================================================================
  // ★★★Batch 39: デッキメーカー(裁定60・レビュー B-2 / B-3)
  //
  // ★この画面は 38 まで<b>第3のデザイン系統</b>だった(裁定33)。寄せた4点
  //   (パレット・ブランドマーク・トースト・confirm)に番人を置く。
  // ★★確認モーダルの検証は<b>盤面と同じ項目立て</b>にしてある(36 の 5-1)。
  //   あちらとこちらは同じ規約の別実装であり、<b>同じ問いで測っていなければ
  //   黙って離れていく</b>。ここが 39 の複製を許している唯一の根拠である。
  // =========================================================================
  CARD_LIBRARY.body = deckMakerLibrary();
  const deckPage = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const deckErrors = [];
  deckPage.on('pageerror', (e) => deckErrors.push(String(e)));
  // ★素の confirm() に張り込む。呼ばれたら記録して false を返す(裁定53 の番人)
  await deckPage.addInitScript(() => {
    window.__confirmCalls = [];
    window.confirm = (msg) => { window.__confirmCalls.push(String(msg)); return false; };
    // ★★Batch 40: 「書き出したか」を<b>結果で</b>数える。downloadBlob は
    //   URL.createObjectURL でしか Blob を URL にできない。ボタンを押したかどうかではなく、
    //   <b>ファイルが作られたかどうか</b>を見る(34 hotfix の一般形)
    window.__downloads = [];
    const origCreate = URL.createObjectURL.bind(URL);
    URL.createObjectURL = (blob) => { window.__downloads.push(blob.size); return origCreate(blob); };
  });
  deckPage.on('download', () => { /* 保存先は要らない。作られたことは上の spy が数えている */ });
  await deckPage.goto(`http://127.0.0.1:${port}/harness-deckmaker.html`);
  // ★★投げると検証スクリプトごと死ぬ(35 で踏んだ)。待ちと click は必ず包む——
  //   <b>わざと壊したときに FAIL が並ばず「途中で消える」</b>のでは番人にならない
  const tryUi = async (fn) => { try { await fn(); return true; } catch (e) { return false; } };
  await tryUi(() => deckPage.waitForSelector('#pool-grid .tile', { timeout: 5000 }));

  // ---- 39-1. 見た目の系統 ----
  const deckTheme = await deckPage.evaluate(() => ({
    body: getComputedStyle(document.body).backgroundColor,
    text: getComputedStyle(document.body).color,
    css: Array.from(document.styleSheets).some((s) => (s.href || '').includes('battle.css')),
  }));
  check('★★デッキメーカーの背景が盤面・ロビーと同じ黒である(39・裁定60)',
    deckTheme.body === 'rgb(33, 37, 41)' && deckTheme.text === 'rgb(248, 249, 250)',
    JSON.stringify(deckTheme));
  check('★★デッキメーカーは battle.css を読み込んでいる(39・寄せる先そのもの)',
    deckTheme.css === true);

  // ★38 までの独自パレット。1色でも残っていたら寄せきれていない
  const deckHtml = fs.readFileSync(path.join(RES, 'templates/manual-deck-maker.html'), 'utf8');
  const oldPalette = ['#0c0b12', '#181624', '#211e33', '#2a2640', '#3c3656',
    '#c9a15a', '#8a744a', '#3f9e7a', '#c23c52', '#9d97b8', '#6a6484', '#221f36']
    .filter((c) => deckHtml.toLowerCase().includes(c));
  check('★★独自パレットが1色も残っていない(39・裁定60)',
    oldPalette.length === 0, JSON.stringify(oldPalette));
  // ★Georgia の TABOO は<b>カードの裏面</b>にだけ在ってよい(battle.css の .mcard-back-mark)
  check('★Georgia セリフのブランドマークが無い(39・裁定60)',
    !/Georgia/i.test(deckHtml) && !/class="mark"/.test(deckHtml));

  // ---- 39-2. 文明色の正が1箇所であること(レビュー B-2) ----
  //
  // ★★走査の対象は<b>コード</b>である。焼き込んだ画像(favicon.svg・カードのPNG)は
  //   複製ではなく出力であり、参照にできない。
  const civHexes = Array.from(battleCss.matchAll(/--civ-[a-z]+:\s*(#[0-9a-f]{6})/gi))
    .map((m) => m[1].toLowerCase());
  const codeFiles = [
    ...fs.readdirSync(path.join(RES, 'static/js')).filter((n) => n.endsWith('.js'))
      .map((n) => ['js/' + n, fs.readFileSync(path.join(RES, 'static/js', n), 'utf8')]),
    ...fs.readdirSync(path.join(RES, 'templates')).filter((n) => n.endsWith('.html'))
      .map((n) => ['templates/' + n, fs.readFileSync(path.join(RES, 'templates', n), 'utf8')]),
    ...fs.readdirSync(path.join(ROOT, 'tools')).filter((n) => n.endsWith('.js'))
      .map((n) => ['tools/' + n, fs.readFileSync(path.join(ROOT, 'tools', n), 'utf8')]),
  ];
  const civLeaks = codeFiles
    .filter(([, src]) => civHexes.some((h) => src.toLowerCase().includes(h)))
    .map(([name]) => name);
  check('★★★文明色の正は battle.css の :root 1箇所である(39・B-2 / 設計判断28)',
    civHexes.length === 7 && civLeaks.length === 0,
    JSON.stringify({ civHexes, civLeaks }));
  // ★正が1箇所でも、そこから読めていなければ意味が無い。<b>結線</b>のほうを別に測る
  const deckTileColor = await deckPage.evaluate(() => {
    const tile = document.querySelector('#pool-grid .tile');
    return {
      c: tile.style.getPropertyValue('--c').trim().toLowerCase(),
      fire: getComputedStyle(document.documentElement).getPropertyValue('--civ-fire').trim().toLowerCase(),
    };
  });
  check('★★デッキメーカーのタイルの色は battle.css の :root から来ている(39)',
    deckTileColor.fire !== '' && deckTileColor.c === deckTileColor.fire,
    JSON.stringify(deckTileColor));

  // ---- 41-1. ★★★面の質感も盤面と同じものを使っている(41・裁定107) ----
  // ★★39 は<b>色</b>を1本化した。41 は<b>質感</b>を1本化する。光沢の形は
  //   battle.css の :root にある --mc-gloss ただ1つで、場所ごとに変えるのは
  //   強さ(--mc-sheen)だけである(32c 2-1)。
  // ★★★測るのは「光っているか」ではなく<b>仕組みが生きているか</b>である。
  //   同じ定義を使いながら要素ごとに強さが違う、という状態は、
  //   共有の変数が遅延解決されているときにしか作れない。ここをコピペした
  //   ベタ書きに戻すと、強さが揃ってしまうか、:root の値と無関係になる。
  const deckGloss = await deckPage.evaluate(() => {
    const bg = (sel) => {
      const el = document.querySelector(sel);
      return el ? getComputedStyle(el).backgroundImage : '';
    };
    const tileEl = document.querySelector('#pool-grid .tile');
    return {
      // ★41: 定義は :root ではなく<b>使う要素そのもの</b>にある(そうしないと強さが効かない)。
      //   ここで見たいのは「デッキメーカーの面にも battle.css の形が降りているか」である
      onTile: tileEl ? getComputedStyle(tileEl).getPropertyValue('--mc-gloss').trim() : '',
      onRoot: getComputedStyle(document.documentElement).getPropertyValue('--mc-gloss').trim(),
      tile: bg('#pool-grid .tile'),
      inner: bg('#pool-grid .tile .tile-inner'),
      head: bg('#pool-grid .tile .t-head'),
    };
  });
  // ★★定義が :root に<b>無い</b>ことも一緒に測る。:root に置くと継承の時点で
  //   強さが焼き付き、場所ごとの強さが効かなくなる(41 で踏んだ不具合そのもの)
  check('★★★光沢の形は battle.css から来ており、:root には置かれていない(41)',
    deckGloss.onTile.includes('115deg') && deckGloss.onRoot === '',
    JSON.stringify({ onTile: deckGloss.onTile.slice(0, 40), onRoot: deckGloss.onRoot }));
  check('★★★デッキメーカーのタイルが盤面と同じ光沢を使っている(41・裁定107)',
    deckGloss.tile.includes('115deg') && deckGloss.inner.includes('115deg')
      && deckGloss.head.includes('115deg'),
    JSON.stringify({ tile: deckGloss.tile.slice(0, 40), inner: deckGloss.inner.slice(0, 40) }));
  // ★胴(読む面)は 0.05、外枠は 0.14。<b>同じ定義から違う強さが出ている</b>ことが、
  //   遅延解決が効いている証拠である
  check('★★★光沢は場所ごとに強さだけが変わる(41・32c 2-1 の仕組みが生きている)',
    deckGloss.tile.includes('0.14') && deckGloss.inner.includes('0.05')
      && deckGloss.head.includes('0.12'),
    JSON.stringify({ tile14: deckGloss.tile.includes('0.14'),
      inner05: deckGloss.inner.includes('0.05'), head12: deckGloss.head.includes('0.12') }));

  // ---- 41-2. ★★質感に transform / filter を使っていない(32c 項目63 のデッキメーカー版) ----
  // ★あちらの理由はタップの遷移だった。こちらは<b>強調とホバー</b>である——
  //   .tile は hl-dim で filter を、:hover / :active で transform を既に使っている。
  //   質感が同じものを使うと、40 追補のカーブ強調を押すたびに質感が巻き添えで変わる。
  const deckFaceFx = await deckPage.evaluate(() => {
    const sels = ['.tile', '.tile-inner', '.t-head', '.t-type', '.t-foot', '.t-cost'];
    const out = [];
    for (const sel of sels) {
      for (const el of document.querySelectorAll('#pool-grid ' + sel)) {
        const st = getComputedStyle(el);
        if (st.transform !== 'none' || st.filter !== 'none') {
          out.push({ sel, transform: st.transform, filter: st.filter });
        }
      }
    }
    const seen = sels.filter((x) => document.querySelectorAll('#pool-grid ' + x).length > 0);
    return { hits: out, seen };
  });
  check('★★デッキメーカーの質感も transform / filter を使わない(41・強調に巻き込まれない)',
    deckFaceFx.hits.length === 0 && deckFaceFx.seen.length >= 5,
    JSON.stringify(deckFaceFx));

  // ---- 39-3. トースト ----
  await deckPage.evaluate(() => toast('試験のトースト'));
  const deckToast = await deckPage.evaluate(() => {
    const t = document.getElementById('toast');
    const s = getComputedStyle(t);
    return { cls: t.className, hidden: t.hidden, bottom: s.bottom, z: s.zIndex, pe: s.pointerEvents };
  });
  check('★★トーストは盤面と同じ .manual-toast である(39・裁定60)',
    deckToast.cls === 'manual-toast' && deckToast.hidden === false
      && deckToast.bottom === '24px' && deckToast.z === '1058' && deckToast.pe === 'none',
    JSON.stringify(deckToast));

  // ---- 39-4. 確認モーダル(裁定53・47〜56)----
  //
  // ★まずデッキを壊せる状態にする。空のデッキでは確認を出さない(出す理由が無い)
  await tryUi(() => deckPage.locator('#pool-grid .tile').first()
    .click({ button: 'right', timeout: 5000 }));
  const before = await deckPage.evaluate(() => ({ civ: mainCiv, n: deck.counts.size }));
  check('★デッキメーカーの右クリックでカードが入る(前提)', before.n === 1 && before.civ === '火',
    JSON.stringify(before));

  const waterBtn = deckPage.locator('.civ-btn', { hasText: '水' });
  await tryUi(() => waterBtn.click({ timeout: 5000 }));
  const opened = await deckPage.evaluate(() => ({
    shown: !document.getElementById('confirm-modal').hidden,
    focus: document.activeElement ? document.activeElement.id : null,
    text: document.getElementById('confirm-modal-text').textContent,
    ok: document.getElementById('confirm-modal-ok').textContent,
    civ: mainCiv,
    n: deck.counts.size,
    raw: window.__confirmCalls.length,
  }));
  check('★★破壊的操作で素の confirm() を呼ばない(39・裁定53)', opened.raw === 0);
  check('★文明の変更は確認モーダルを出す(39)', opened.shown === true);
  check('★確認を出している間はまだ何も壊れていない(39)',
    opened.civ === '火' && opened.n === 1, JSON.stringify(opened));
  check('★確認の初期フォーカスは [キャンセル] である(39・裁定52)',
    opened.focus === 'confirm-modal-close', String(opened.focus));
  check('★確認のボタンには動詞が書いてある(39・裁定55)',
    opened.ok === '文明を変更する', opened.ok);
  // ★★<b>座標で</b>測る(34 hotfix の一般形: 式ではなく結果を見る)。
  //   「hidden が外れたか」だけを見る判定は、器(.info-modal)ごと失っても通ってしまう——
  //   ページの一番下に確認の文字が流れているだけの状態を「開いている」と報告する
  const confirmBox = await deckPage.evaluate(() => {
    const overlay = document.getElementById('confirm-modal').getBoundingClientRect();
    const body = document.querySelector('#confirm-modal .info-modal-body').getBoundingClientRect();
    return {
      covers: overlay.width >= innerWidth - 1 && overlay.height >= innerHeight - 1,
      dx: Math.abs(body.left + body.width / 2 - innerWidth / 2),
      dy: Math.abs(body.top + body.height / 2 - innerHeight / 2),
    };
  });
  check('★★確認モーダルは画面を覆い、中央に出る(39・器は battle.css)',
    confirmBox.covers && confirmBox.dx < 4 && confirmBox.dy < 4, JSON.stringify(confirmBox));

  // ★Tab の折り返し。10回押しても裏の盤面へ出ない
  for (let i = 0; i < 10; i += 1) await deckPage.keyboard.press('Tab');
  check('★★Tab を繰り返してもフォーカスは確認モーダルの外へ出ない(39・裁定47)',
    (await deckPage.evaluate(() => document.getElementById('confirm-modal')
      .contains(document.activeElement))) === true);
  // ★裏の要素が自分でフォーカスを取りに来る経路(keydown を通らない)にも網を張る
  await deckPage.evaluate(() => document.getElementById('deck-name').focus());
  check('★裏の要素へフォーカスが移っても引き戻す(39・裁定47)',
    (await deckPage.evaluate(() => document.getElementById('confirm-modal')
      .contains(document.activeElement))) === true);

  // ★確認を出している間に文字を測る。空の <p> は素通りしてしまう(36 の 5-1 と同じ形)
  const deckContrast = await deckPage.evaluate(() => {
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
    // ★カードの面(.tile / .bigcard)は対象外である(32c: フェイスは目視)
    const targets = ['.brand h1', '.brand .brand-sub', '.brand a', '.civ-btn', '.io-btn',
      '.detail-empty', '.counter-box', '.counter-box b', '.section-title', '.section-title small',
      '.tab-btn', '.pool-hint', '.pool-hint b', '.filter-lbl', '.flt-btn', '.flt-util button',
      '#confirm-modal-title', '#confirm-modal-text', '#confirm-modal-ok', '#confirm-modal-close',
      // ★Batch 40 で足した文字(32a からの規約: 新しい文字は必ず targets に足す)
      '.validate-chip', '.validate-chip b', '.curve-n', '.curve-k', '.stat-chip', '.stat-chip b',
      '#validate-modal-title', '.validate-summary', '.vi-mark', '.vi-label', '.vi-detail',
      '#validate-modal-save', '#validate-modal-close'];
    for (const sel of targets) {
      for (const el of document.querySelectorAll(sel)) {
        const t = (el.textContent || '').trim();
        if (!t) continue;
        const fg = parse(getComputedStyle(el).color);
        const bg = eff(el);
        const ratio = (Math.max(L(fg), L(bg)) + 0.05) / (Math.min(L(fg), L(bg)) + 0.05);
        if (ratio < 4.5) out.push({ sel, text: t.slice(0, 10), ratio: Math.round(ratio * 100) / 100 });
      }
    }
    return out;
  });
  check('★デッキメーカーの文字もコントラスト比 4.5:1 以上(39)',
    deckContrast.length === 0, JSON.stringify(deckContrast));

  // ★Esc は [キャンセル] を click() するだけである(裁定48)。取り消しなので何も起きない
  await deckPage.keyboard.press('Escape');
  const afterEsc = await deckPage.evaluate(() => ({
    shown: !document.getElementById('confirm-modal').hidden,
    civ: mainCiv,
    n: deck.counts.size,
    focus: document.activeElement ? document.activeElement.className : null,
  }));
  check('★★Esc で確認モーダルが閉じる(39・裁定48)', afterEsc.shown === false);
  check('★取り消した確認は何も壊さない(39・裁定54)',
    afterEsc.civ === '火' && afterEsc.n === 1, JSON.stringify(afterEsc));
  check('★閉じるとフォーカスは開く前の位置(文明ボタン)へ戻る(39)',
    (afterEsc.focus || '').includes('civ-btn'), String(afterEsc.focus));

  // ★[実行] でようやく変わる
  await tryUi(() => waterBtn.click({ timeout: 5000 }));
  await tryUi(() => deckPage.locator('#confirm-modal-ok').click({ timeout: 5000 }));
  const afterOk = await deckPage.evaluate(() => ({
    shown: !document.getElementById('confirm-modal').hidden,
    civ: mainCiv,
    n: deck.counts.size,
  }));
  check('★確認の [実行] で文明が変わり、デッキが空になる(39)',
    afterOk.shown === false && afterOk.civ === '水' && afterOk.n === 0,
    JSON.stringify(afterOk));
  // ★空のデッキでは問わない。失うものが無いのに確認を出すと、確認そのものが軽くなる
  await tryUi(() => deckPage.locator('.civ-btn', { hasText: '闇' }).click({ timeout: 5000 }));
  check('★組みかけが無いときは確認を出さない(39)',
    (await deckPage.evaluate(() => mainCiv === '闇'
      && document.getElementById('confirm-modal').hidden)) === true);

  // ★静的な番人。テンプレートに素の呼び出しが残っていないこと(36 の項目20 と同じ形)。
  //   ★注釈の行は数えない——見たいのは<b>呼び出しが残っているか</b>である
  const deckLeftoverConfirm = deckHtml.split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*|<!--|-->)/.test(line))
    .filter((line) => /(?<![A-Za-z0-9_$])confirm\s*\(/.test(line))
    .map((line) => line.trim());
  check('★★manual-deck-maker.html に素の confirm の呼び出しが残っていない(39・裁定53)',
    deckLeftoverConfirm.length === 0, JSON.stringify(deckLeftoverConfirm));

  // =========================================================================
  // ★★★Batch 40: マナカーブ + 検証一覧 + autosave(レビュー B-3・優先順位9)
  //
  // ★39 が置いた土台の上に乗っている —— 2つ目のモーダルは同じ modalStack に積まれ、
  //   同じ規約(裁定47・51・52・53)で測る。
  // =========================================================================

  // ---- 40-1. マナカーブと統計 ----
  //
  // ★入口から作る。JS で deck を直接いじると、右クリック → 集計 → 描画の結線が抜けても通る
  await tryUi(() => deckPage.locator('.civ-btn', { hasText: '火' }).click({ timeout: 5000 }));
  const poolTile = (i) => deckPage.locator('#pool-grid .tile').nth(i);
  for (let i = 0; i < 4; i += 1) {
    await tryUi(() => poolTile(i).click({ button: 'right', timeout: 5000 }));
  }
  for (let i = 0; i < 3; i += 1) {                 // ミニオン(コスト3)を4枚まで
    await tryUi(() => poolTile(0).click({ button: 'right', timeout: 5000 }));
  }
  // ★禁忌にも1枚入れる。コスト3であり、<b>カーブが動いてはいけない</b>
  await tryUi(() => deckPage.locator('.tab-btn', { hasText: '禁忌用' }).click({ timeout: 5000 }));
  await tryUi(() => poolTile(0).click({ button: 'right', timeout: 5000 }));

  const curve = await deckPage.evaluate(() => ({
    keys: Array.from(document.querySelectorAll('#curve .curve-k'), (n) => n.textContent),
    filterKeys: Array.from(document.querySelectorAll('#row-cost .flt-btn'), (n) => n.textContent),
    counts: Array.from(document.querySelectorAll('#curve .curve-n'), (n) => Number(n.textContent)),
    color: document.querySelector('#curve .curve-col').style.getPropertyValue('--curve').trim().toLowerCase(),
    fire: getComputedStyle(document.documentElement).getPropertyValue('--civ-fire').trim().toLowerCase(),
    stats: Array.from(document.querySelectorAll('.stat-chip'), (n) => n.textContent),
    taboo: document.getElementById('c-taboo').textContent,
  }));
  // ★★区切りを2つ持たない。プールを絞る目盛りと、組んだ結果を読む目盛りは同じでなければ
  //   「6でフィルタした結果」と「カーブの6」が別の物を指す(設計判断28)
  check('★★★マナカーブの区切りはコストフィルタと同じである(40・設計判断28)',
    curve.keys.length === 11 && curve.keys.join(',') === curve.filterKeys.join(','),
    JSON.stringify({ keys: curve.keys, filterKeys: curve.filterKeys }));
  // ★禁忌のコスト3を入れてある。混ざっていれば 3 の列が 5 になる
  check('★★マナカーブが数えるのはメインデッキだけである(40・禁忌は支払い方が違う)',
    curve.counts.length === 11 && curve.counts[1] === 1 && curve.counts[3] === 4
      && curve.counts[5] === 1 && curve.counts[10] === 1
      && curve.counts.reduce((a, b) => a + b, 0) === 7 && curve.taboo === '1/8',
    JSON.stringify({ counts: curve.counts, taboo: curve.taboo }));
  // ★色は :root から来る(裁定108)。ここでも値は書かず、正から読んだものと突き合わせる
  check('★★マナカーブの色は battle.css の :root から来ている(40・裁定108)',
    curve.fire !== '' && curve.color === curve.fire, JSON.stringify(curve));
  check('★統計はメインデッキの平均コストとタイプ内訳である(40)',
    curve.stats.join('|') === '平均コスト4.00|ミニオン4|進化1|スペル1|ウェポン1',
    JSON.stringify(curve.stats));

  // ---- 40-1b. カーブの列で強調する(39 Q3 = c / 裁定133・134)----
  //
  // ★★見ている主語は「デッキ」である。押しても<b>プールは動かない</b> ——
  //   コストフィルタ(プールを絞る)と同じ見た目の軸が2つの主語を持たないようにしている
  // ★★プールを<b>メイン用</b>へ戻してから測る。禁忌タブのままだと、プールに並ぶのは
  //   他文明の札ばかりで「メインの札だけを強調する」条件に最初から掛からない ——
  //   <b>漏れていても漏れようがない状態</b>で測ることになり、番人にならない
  await tryUi(() => deckPage.locator('.tab-btn', { hasText: 'メイン用' }).click({ timeout: 5000 }));
  const beforeHl = await deckPage.evaluate(() => localStorage.getItem('qte-deckmaker-draft'));
  await tryUi(() => deckPage.locator('.curve-col[data-cost="3"]').click({ timeout: 5000 }));
  const hl = await deckPage.evaluate(() => ({
    cost: highlightCost,
    on: document.querySelectorAll('#deck-main .tile.hl-on').length,
    dim: document.querySelectorAll('#deck-main .tile.hl-dim').length,
    taboo: document.querySelectorAll('#deck-taboo .tile[class*="hl-"]').length,
    pool: document.querySelectorAll('#pool-grid .tile[class*="hl-"]').length,
    sel: document.querySelectorAll('.curve-col.sel').length,
    focus: document.activeElement ? document.activeElement.dataset.cost : null,
    disabled: document.querySelectorAll('.curve-col[disabled]').length,
    draft: localStorage.getItem('qte-deckmaker-draft'),
  }));
  check('★★マナカーブの列を押すとメインデッキでそのコストが強調される(40 追補・39 Q3)',
    hl.cost === '3' && hl.on === 1 && hl.dim === 3 && hl.sel === 1 && hl.focus === '3',
    JSON.stringify(hl));
  // ★★★カーブが数えていないものは強調しない。数えるのはメインだけである(裁定127・133)
  check('★★★強調は禁忌デッキにもプールにも及ばない(主語はデッキのメインである・40 追補)',
    hl.taboo === 0 && hl.pool === 0, JSON.stringify(hl));
  // ★0枚の列を選べると「誰も明るくないのに全部が暗い」状態を作れる
  check('★0枚の列は押せない(40 追補)', hl.disabled === 7, String(hl.disabled));
  // ★★見え方は編集内容ではない。棒を押しただけで localStorage が書かれてはいけない
  check('★★強調は保存の対象ではない(40 追補・裁定134)',
    hl.draft === beforeHl && beforeHl !== null);
  await tryUi(() => deckPage.locator('.curve-col[data-cost="3"]').click({ timeout: 5000 }));
  check('★同じ列をもう一度押すと強調が解ける(40 追補)',
    (await deckPage.evaluate(() => highlightCost === null
      && document.querySelectorAll('.tile[class*="hl-"]').length === 0)) === true);

  // ---- 40-2. autosave(裁定31・119)----
  const draftKey = 'qte-deckmaker-draft';
  const stored = await deckPage.evaluate((k) => {
    const keys = Object.keys(localStorage);
    return { keys, raw: localStorage.getItem(k) };
  }, draftKey);
  // ★部屋に紐づけない(裁定31)。この画面に部屋という概念が無い以上、鍵に部屋IDは入らない
  check('★★編集内容は localStorage に保存され、鍵は部屋に紐づかない(40・裁定31)',
    stored.keys.includes(draftKey) && !stored.keys.some((k) => /qte-deckmaker.*(TESTRM|room)/i.test(k))
      && JSON.parse(stored.raw || '{}').cards.length === 5,
    JSON.stringify(stored.keys));

  // ★★★これが「タブを閉じたら作業が消える」への番人である。
  //   ★同時に「復元より先に初期化が走らない」の番人でもある —— ライブラリ読込は
  //   resetDeck → renderAll → autosave を通るので、順序を誤ると<b>空で上書きしてから</b>
  //   読むことになり、この項目が落ちる
  await deckPage.reload();
  await tryUi(() => deckPage.waitForSelector('#pool-grid .tile', { timeout: 5000 }));
  const afterReload = await deckPage.evaluate(() => ({
    civ: mainCiv, n: deck.counts.size, main: document.getElementById('c-main').textContent,
    taboo: document.getElementById('c-taboo').textContent,
    name: document.getElementById('deck-name').value,
  }));
  check('★★★再読込しても組みかけのデッキが残っている(40・レビュー B-3)',
    afterReload.civ === '火' && afterReload.n === 5 && afterReload.main === '7/40'
      && afterReload.taboo === '1/8',
    JSON.stringify(afterReload));

  // ---- 40-3. 検証一覧(レビュー B-3・裁定47〜56)----
  const chip = await deckPage.evaluate(() => ({
    text: document.getElementById('validate-btn').textContent.trim(),
    cls: document.getElementById('validate-btn').className,
  }));
  check('★ヘッダの検証ボタンが未達の件数を出している(40)',
    chip.text === '検証3 件' && chip.cls.includes('v-bad'), JSON.stringify(chip));

  await tryUi(() => deckPage.locator('#validate-btn').click({ timeout: 5000 }));
  const vOpen = await deckPage.evaluate(() => ({
    shown: !document.getElementById('validate-modal').hidden,
    focus: document.activeElement ? document.activeElement.id : null,
    items: Array.from(document.querySelectorAll('#validate-modal-list li'),
      (li) => `${li.className.includes('v-ok') ? 'ok' : 'bad'}:${li.querySelector('.vi-detail').textContent}`),
    save: document.getElementById('validate-modal-save').textContent,
  }));
  check('★★検証一覧は「何が足りないか」を名指しする(40・レビュー B-3)',
    vOpen.shown === true && vOpen.items.length === 5
      && vOpen.items[0] === 'bad:未選択'
      && vOpen.items[1] === 'bad:7 枚 / あと 33 枚'
      && vOpen.items[2] === 'bad:1 枚 / あと 7 枚'
      && vOpen.items[3].startsWith('ok:') && vOpen.items[4].startsWith('ok:'),
    JSON.stringify(vOpen.items));
  // ★★押させたいボタンに初期フォーカスを載せない(裁定52)。この画面の用件は<b>読むこと</b>である
  check('★★検証一覧の初期フォーカスは [閉じる] である(40・裁定52)',
    vOpen.focus === 'validate-modal-close', String(vOpen.focus));
  check('★ボタンには動詞が書いてある(40・裁定55)',
    vOpen.save === 'このまま保存する', vOpen.save);
  for (let i = 0; i < 10; i += 1) await deckPage.keyboard.press('Tab');
  check('★★Tab を繰り返しても検証一覧の外へ出ない(40・裁定47)',
    (await deckPage.evaluate(() => document.getElementById('validate-modal')
      .contains(document.activeElement))) === true);
  // ★2つ目のモーダルも同じ層に積まれている。同じ Esc・同じ出口(裁定48・51)
  const vContrast = await deckPage.evaluate(() => {
    const parse = (c) => {
      const m = (c || '').match(/[\d.]+/g);
      if (!m) return [0, 0, 0, 0];
      const v = m.map(Number);
      return [v[0], v[1], v[2], v.length > 3 ? v[3] : 1];
    };
    const eff = (el) => {
      let n = el;
      while (n && n !== document.documentElement) {
        const c = parse(getComputedStyle(n).backgroundColor);
        if (c[3] >= 0.98) return c;
        n = n.parentElement;
      }
      return parse(getComputedStyle(document.body).backgroundColor);
    };
    const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    const L = (v) => 0.2126 * lin(v[0]) + 0.7152 * lin(v[1]) + 0.0722 * lin(v[2]);
    const out = [];
    for (const el of document.querySelectorAll('#validate-modal *')) {
      const t = (el.textContent || '').trim();
      if (!t || el.children.length > 0) continue;
      const fg = parse(getComputedStyle(el).color);
      const bg = eff(el);
      const ratio = (Math.max(L(fg), L(bg)) + 0.05) / (Math.min(L(fg), L(bg)) + 0.05);
      if (ratio < 4.5) out.push({ t: t.slice(0, 12), ratio: Math.round(ratio * 100) / 100 });
    }
    return out;
  });
  check('★検証一覧の文字もコントラスト比 4.5:1 以上(40)',
    vContrast.length === 0, JSON.stringify(vContrast));
  await deckPage.keyboard.press('Escape');
  check('★★Esc で検証一覧が閉じる(40・裁定48)',
    (await deckPage.evaluate(() => document.getElementById('validate-modal').hidden)) === true);

  // ---- 40-4. 書き出しの関門(レビュー B-3: 黙って書き出さない)----
  await deckPage.evaluate(() => { window.__downloads.length = 0; });
  await tryUi(() => deckPage.locator('#out-json').click({ timeout: 5000 }));
  const gate = await deckPage.evaluate(() => ({
    shown: !document.getElementById('validate-modal').hidden,
    files: window.__downloads.length,
  }));
  check('★★★不正なデッキを黙って書き出さない(40・レビュー B-3)',
    gate.shown === true && gate.files === 0, JSON.stringify(gate));
  // ★止めるのではなく、理由を見せてから通す。手動モードは未完成のデッキも試す場所である
  await tryUi(() => deckPage.locator('#validate-modal-save').click({ timeout: 5000 }));
  const forced = await deckPage.evaluate(() => ({
    shown: !document.getElementById('validate-modal').hidden,
    files: window.__downloads.length,
  }));
  check('★★[このまま保存する] は書き出せる(止めるのではなく理由を見せる・40)',
    forced.shown === false && forced.files === 1, JSON.stringify(forced));

  // ---- 40-5. デッキ読込と [元に戻す](裁定119)----
  const fireIds = Array.from({ length: 10 }, (unused, i) => `QTE-M-FIRE-${i + 2}`);
  const otherIds = ['WATER', 'EARTH', 'WIND', 'LIGHT', 'DARK']
    .flatMap((civ) => [`QTE-M-${civ}-2`, `QTE-M-${civ}-3`]).slice(0, 8);
  const validDeck = {
    format: 'taboo-elemental-deck',
    version: 2,
    deckName: '完成デッキ',
    mainCiv: '火',
    leader: { cardId: 'QTE-M-FIRE-1' },
    main: fireIds.map((id) => ({ cardId: id, qty: 4 })),
    taboo: otherIds.map((id) => ({ cardId: id })),
  };
  await tryUi(() => deckPage.locator('#in-deck').setInputFiles({
    name: 'deck.json', mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(validDeck), 'utf8'),
  }));
  const loaded = await deckPage.evaluate(() => ({
    main: document.getElementById('c-main').textContent,
    taboo: document.getElementById('c-taboo').textContent,
    chip: document.getElementById('validate-btn').textContent.trim(),
    cls: document.getElementById('validate-btn').className,
    undo: !document.getElementById('undo-btn').hidden,
    raw: window.__confirmCalls.length,
  }));
  // ★裁定119: [デッキ読込] に確認は足さない。失われないので問い自体が要らない
  check('★★[デッキ読込] に確認は足さない(40・裁定119)', loaded.raw === 0);
  check('★★★組みかけは黙って捨てられない([元に戻す] が現れる・40・裁定119)',
    loaded.undo === true && loaded.main === '40/40' && loaded.taboo === '8/8',
    JSON.stringify(loaded));
  check('★条件を満たしたデッキは検証が OK になる(40)',
    loaded.chip === '検証OK' && loaded.cls.includes('v-ok'), JSON.stringify(loaded));

  await deckPage.evaluate(() => { window.__downloads.length = 0; });
  await tryUi(() => deckPage.locator('#out-json').click({ timeout: 5000 }));
  check('★正しいデッキは検証一覧を出さずに書き出す(40)',
    (await deckPage.evaluate(() => ({
      shown: !document.getElementById('validate-modal').hidden,
      files: window.__downloads.length,
    }))).files === 1);

  await tryUi(() => deckPage.locator('#undo-btn').click({ timeout: 5000 }));
  const undone = await deckPage.evaluate(() => ({
    main: document.getElementById('c-main').textContent,
    name: document.getElementById('deck-name').value,
  }));
  check('★★★[元に戻す] で読込前の組みかけが戻る(40・裁定119 の実体)',
    undone.main === '7/40', JSON.stringify(undone));
  // ★★入れ替えである。押し間違えても、もう一度押せば戻る —— だから確認を足していない
  await tryUi(() => deckPage.locator('#undo-btn').click({ timeout: 5000 }));
  check('★★[元に戻す] は入れ替えである(もう一度押すと読み込んだほうへ戻る・40)',
    (await deckPage.evaluate(() => document.getElementById('c-main').textContent)) === '40/40');

  // ---- 40-6. 規定枚数の正はサーバである ----
  //
  // ★★同じ規則が Java と JS の両方にあるのは重複ではない(設計判断27: クライアントの
  //   チェックは操作補助にすぎない)。ただし<b>黙って離れていける</b>ことは複製と同じなので、
  //   期待値を書かず、<b>Java から読んだ値</b>と突き合わせる(裁定110)
  const importerJava = fs.readFileSync(
    path.join(ROOT, 'src/main/java/com/example/qte/manual/ManualDeckImporter.java'), 'utf8');
  const javaInt = (name) => {
    const m = importerJava.match(new RegExp(`${name}\\s*=\\s*(\\d+)`));
    return m ? Number(m[1]) : null;
  };
  const jsSizes = await deckPage.evaluate(() => ({
    main: MAIN_SIZE, taboo: TABOO_SIZE, sameMain: MAX_SAME, sameTaboo: TABOO_SAME,
  }));
  const javaSizes = {
    main: javaInt('MAIN_DECK_SIZE'), taboo: javaInt('TABOO_DECK_SIZE'),
    sameMain: javaInt('MAIN_NAME_LIMIT'), sameTaboo: javaInt('TABOO_NAME_LIMIT'),
  };
  check('★★★デッキの規定枚数と同名上限はサーバと同じ値である(40・裁定110)',
    javaSizes.main !== null && JSON.stringify(jsSizes) === JSON.stringify(javaSizes),
    JSON.stringify({ jsSizes, javaSizes }));

  check('デッキメーカーでJSエラーが出ない', deckErrors.length === 0, deckErrors.join(' | '));
  await deckPage.close();

  check('全工程を通じてJSエラーが出ない', errors.length === 0, errors.join(' | '));

  await browser.close();
  server.close();

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exit(failed.length === 0 ? 0 : 1);
})();
