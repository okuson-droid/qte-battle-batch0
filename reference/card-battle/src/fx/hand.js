// 手札の扇形の並びと、ドローで滑り込む動き。
// 各カードの位置は CSS 変数（--x, --y, --r）で与え、CSS の transition で動かす。

/** 手札を扇形に並べ直す */
export function layoutHand(stage) {
  const hand = stage.hand, cards = [...hand.children], n = cards.length;
  if (!n) return;
  const cw = cards[0].offsetWidth, width = hand.clientWidth;
  const sp = n > 1 ? Math.min(cw * 0.88, (width - cw - 24) / (n - 1)) : 0;
  const start = width / 2 - (sp * (n - 1)) / 2 - cw / 2;
  cards.forEach((c, i) => {
    const off = i - (n - 1) / 2;
    c.style.setProperty('--x', start + i * sp + 'px');
    c.style.setProperty('--y', off * off * 2.2 + 'px');
    c.style.setProperty('--r', off * 4.5 + 'deg');
    c.style.zIndex = i + 1;
  });
}

/** 画面右外から手札へ滑り込ませる */
export function drawToHand(stage, el) {
  el.style.setProperty('--x', stage.hand.clientWidth + 40 + 'px');
  el.style.setProperty('--y', '-120px');
  el.style.setProperty('--r', '35deg');
  stage.hand.appendChild(el);
  void el.offsetWidth; // 初期位置を確定させてから並べ直す → transition で動く
  layoutHand(stage);
}

/**
 * 手札から外していたカードを、今いる位置から元の場所へ滑って戻す。
 * @param {number} scale  戻る直前の拡大率（ドラッグ中の大きさ）
 */
export function returnToHand(stage, el, index, { tilt = 0, scale = 1 } = {}) {
  const r = el.getBoundingClientRect(), hr = stage.hand.getBoundingClientRect();
  const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  el.style.transform = '';
  stage.hand.insertBefore(el, stage.hand.children[index] || null);
  el.style.transition = 'none';
  el.style.setProperty('--x', cx - hr.left - el.offsetWidth / 2 + 'px');
  el.style.setProperty('--y', cy - hr.top - el.offsetTop - el.offsetHeight / 2 + 'px');
  el.style.setProperty('--r', tilt + 'deg');
  el.style.setProperty('--s', scale);
  void el.offsetWidth;
  el.style.transition = '';
  el.style.removeProperty('--s');
  layoutHand(stage);
}
