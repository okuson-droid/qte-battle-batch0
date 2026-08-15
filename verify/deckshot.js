/**
 * デッキメーカーの目視用スクリーンショット(★Batch 39)。
 *
 * ★39 は色をほぼ全面的に差し替えている。機械判定はコントラスト比と
 *   「どの値を使ったか」しか見られないので、<b>見た目そのものは目視で確かめる</b>
 *   (32c の faceshot.js と同じ位置づけである)。
 *
 * 使い方:
 *   python3 verify/build_harness.py
 *   PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers OUT=/tmp/deck.png node verify/deckshot.js
 *   CONFIRM=1 を付けると、確認モーダルを開いた状態で撮る。
 *   ★Batch 40: VALIDATE=1 を付けると、検証一覧のモーダルを開いた状態で撮る。
 *   ★Batch 40 追補: HL=<コスト> を付けると、その列を押して強調した状態で撮る。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const RES = path.join(ROOT, 'src/main/resources');
const OUT = process.env.OUT || '/tmp/deckmaker.png';
const WITH_CONFIRM = process.env.CONFIRM === '1';
const WITH_VALIDATE = process.env.VALIDATE === '1';
const HL = process.env.HL || '';

/** ★カード台帳。verify.js の deckMakerLibrary と同じ形の、目で見るための最小データ */
function library() {
  const civs = ['WATER', 'FIRE', 'EARTH', 'WIND', 'LIGHT', 'DARK'];
  const types = ['MINION', 'EVOLUTION', 'SPELL', 'WEAPON'];
  const cards = [];
  for (const civ of civs) {
    cards.push({ id: `L-${civ}`, name: `${civ} のリーダー`, type: 'LEADER',
      civilization: civ, cost: 0, attack: null, hp: null,
      text: '【起動：１】自分の手札を1枚デッキの一番下に戻す。' });
    types.forEach((type, i) => {
      cards.push({ id: `C-${civ}-${i}`, name: `${civ} の${type}`, type,
        civilization: civ, cost: i + 1,
        attack: type === 'SPELL' ? null : i + 1,
        hp: (type === 'MINION' || type === 'EVOLUTION') ? i + 2 : null,
        text: '【守護】相手は、守護を持たない他のミニオンやリーダーに攻撃できない。' });
    });
  }
  return { meta: { backImageId: 'back' }, cards };
}

(async () => {
  const server = http.createServer((req, res) => {
    const url = req.url.split('?')[0];
    if (url === '/manual/api/card-library') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify(library()));
      return;
    }
    const file = url.startsWith('/css/') ? path.join(RES, 'static', url)
      : path.join(__dirname, 'harness-deckmaker.html');
    res.writeHead(200, {
      'Content-Type': file.endsWith('.css') ? 'text/css' : 'text/html; charset=utf-8',
    });
    res.end(fs.readFileSync(file));
  });
  await new Promise((resolve) => server.listen(0, resolve));
  const port = server.address().port;

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1500, height: 900 } });
  await page.goto(`http://127.0.0.1:${port}/harness-deckmaker.html`);
  await page.waitForSelector('#pool-grid .tile');
  await page.locator('#pool-grid .tile').first().click();          // 詳細ペインを埋める
  await page.locator('#pool-grid .tile').nth(1).click({ button: 'right' });
  await page.locator('#pool-grid .tile').nth(1).click({ button: 'right' });
  if (WITH_CONFIRM) {
    await page.locator('.civ-btn', { hasText: '水' }).click();
    await page.waitForTimeout(120);
  } else if (WITH_VALIDATE) {
    // ★Batch 40: リーダーを1枚入れてから開く。全部が赤い一覧より、
    //   ✓ と ! が混ざっている状態のほうが読み方を確かめられる
    await page.locator('.tab-btn', { hasText: 'リーダー' }).click();
    await page.locator('#pool-grid .tile').first().click({ button: 'right' });
    await page.locator('#validate-btn').click();
    await page.waitForTimeout(120);
  } else if (HL) {
    // ★Batch 40 追補: 強調は「立てる」より「落とす」で見せている。
    //   周りが暗くなりすぎていないかは目で見るしかない
    await page.locator('#pool-grid .tile').nth(2).click({ button: 'right' });
    await page.locator('#pool-grid .tile').nth(3).click({ button: 'right' });
    await page.locator(`.curve-col[data-cost="${HL}"]`).click();
    await page.waitForTimeout(120);
  } else {
    await page.evaluate(() => toast('デッキを保存しました'));
  }
  await page.waitForTimeout(120);
  await page.screenshot({ path: OUT });
  console.log(`wrote ${OUT}`);
  await browser.close();
  server.close();
})();
