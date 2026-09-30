// 手札の並び(QTE の手札は左寄せの1列)と、ドローで滑り込む動き。
// 各カードの位置は CSS 変数(--x, --y, --r)で与え、CSS の transition で動かす。

export function layoutHand(stage) {
  const hand = stage.hand, cards = [...hand.children], n = cards.length;
  if (!n) return;
  const cw = cards[0].offsetWidth, width = hand.clientWidth;
  const sp = n > 1 ? Math.min(cw * 1.06, (width - cw - 8) / (n - 1)) : 0;
  cards.forEach((c, i) => {
    c.style.setProperty('--x', 4 + i * sp + 'px');
    c.style.setProperty('--y', '0px');
    c.style.setProperty('--r', '0deg');
    c.style.zIndex = i + 1;
  });
}

/** 山札(右の列)の方向から手札へ滑り込ませる */
export function drawToHand(stage, el) {
  el.style.setProperty('--x', stage.hand.clientWidth + 60 + 'px');
  el.style.setProperty('--y', '-40px');
  el.style.setProperty('--r', '20deg');
  stage.hand.appendChild(el);
  void el.offsetWidth;
  layoutHand(stage);
}

/** 手札から外していたカードを、今いる位置から元の場所へ滑って戻す */
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
