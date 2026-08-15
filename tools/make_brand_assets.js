/**
 * Batch 33: ブランド資産(アイコン・OGP画像)の生成。
 *
 * ★手で描いた PNG をリポジトリに置くのではなく、<b>生成手順のほうを</b>置く。
 *   色や文言を直すときに、元データがどこにあるか分からなくなる状態を作らないためである
 *   (設計判断28「同じ情報を2箇所に置かない」の精神。色の正は favicon.svg と
 *   このファイルの CIV 配列であり、PNG はその出力にすぎない)。
 *
 * ★ラスタライズは Chromium で行う。ImageMagick の SVG 実装は簡易であり、
 *   角丸や rotate の再現が保証されない。ブラウザで描けば実際の見え方と一致する。
 *
 * 実行:
 *   PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node tools/make_brand_assets.js
 *
 * 出力(すべて src/main/resources/static/ 直下):
 *   favicon-32.png       … favicon.svg を読めない古いブラウザ用
 *   apple-touch-icon.png … iOS のホーム画面用(180x180)
 *   og-image.png         … OGP / Twitter カード用(1200x630)
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const STATIC = path.join(ROOT, 'src/main/resources/static');

// ★★Batch 39(レビュー B-2): 文明色は battle.css の :root から読む。
//   38 まではここに同じ7色を書き写しており、「ここを直すときは向こうも直すこと」
//   という覚えごとがコメントで運用されていた。覚えごとは必ず忘れられる。
//   ★このスクリプトは Node である。ブラウザと違い CSS を<b>ファイルとして</b>読めるので、
//     カスタムプロパティを正規表現で拾うだけで正に届く。
//   ★宣言順がそのまま帯の並びになる(無文明は帯に出さない)。
const CIV = (() => {
    const css = fs.readFileSync(path.join(STATIC, 'css/battle.css'), 'utf8');
    const root = (css.match(/:root\s*\{[^}]*\}/) || [''])[0];
    return Array.from(root.matchAll(/--civ-(?!none\b)[a-z]+:\s*(#[0-9a-f]{6})/gi))
        .map((m) => m[1]);
})();

const ICON_SVG = fs.readFileSync(path.join(STATIC, 'favicon.svg'), 'utf8');

const OG_HTML = `<!DOCTYPE html><html lang="ja"><head><meta charset="utf-8"><style>
  html, body { margin: 0; padding: 0; }
  body {
    width: 1200px; height: 630px; overflow: hidden;
    font-family: "Noto Sans CJK JP", sans-serif; color: #f8f9fa;
    background:
      radial-gradient(120% 100% at 20% 0%, rgba(107, 66, 168, 0.35) 0%, rgba(0,0,0,0) 60%),
      radial-gradient(120% 100% at 90% 100%, rgba(47, 111, 181, 0.30) 0%, rgba(0,0,0,0) 55%),
      #17191c;
    display: flex; flex-direction: column; justify-content: center;
    padding: 0 84px; box-sizing: border-box; position: relative;
  }
  /* ★SVG は width/height 属性(64)を持っている。CSS で上書きしないと 64px で描かれる */
  .mark { width: 132px; height: 132px; margin-bottom: 34px; }
  .mark svg { display: block; width: 100%; height: 100%; }
  h1 { font-size: 74px; font-weight: 700; margin: 0 0 18px; letter-spacing: 0.02em; }
  p { font-size: 30px; margin: 0; color: #ced4da; line-height: 1.5; }
  .bar { position: absolute; left: 0; right: 0; bottom: 0; height: 12px; display: flex; }
  .bar span { flex: 1; }
</style></head><body>
  <div class="mark">${ICON_SVG.replace(/<\?xml[^>]*\?>/, '').replace(/<!--[\s\S]*?-->/g, '')}</div>
  <h1>クイン・タブーエレメント</h1>
  <p>2人で遊ぶ、ブラウザだけのカードゲーム。<br>部屋のリンクを渡せばすぐ始められます。</p>
  <div class="bar">${CIV.map((c) => `<span style="background:${c}"></span>`).join('')}</div>
</body></html>`;

(async () => {
  const browser = await chromium.launch();

  // ---- アイコン(SVG をそのまま拡大縮小してラスタライズ)----
  for (const [file, size] of [['favicon-32.png', 32], ['apple-touch-icon.png', 180]]) {
    const page = await browser.newPage({
      viewport: { width: size, height: size }, deviceScaleFactor: 1,
    });
    await page.setContent(
      `<!DOCTYPE html><html><body style="margin:0">
       <div style="width:${size}px;height:${size}px">${ICON_SVG}</div></body></html>`);
    await page.locator('svg').evaluate((el, s) => {
      el.setAttribute('width', s); el.setAttribute('height', s);
    }, size);
    await page.screenshot({ path: path.join(STATIC, file), omitBackground: true });
    await page.close();
    console.log(`wrote ${file} (${size}x${size})`);
  }

  // ---- OGP 画像 ----
  const og = await browser.newPage({
    viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1,
  });
  await og.setContent(OG_HTML);
  await og.waitForTimeout(120);   // フォントの適用待ち
  await og.screenshot({ path: path.join(STATIC, 'og-image.png') });
  await og.close();
  console.log('wrote og-image.png (1200x630)');

  await browser.close();
})();
