// デモ用のカード定義。名前と本文は QTE の雰囲気に寄せた架空のものである(実在の台帳とは無関係)。
// hue は文明色の色相、sig は模様の角数。rarity は召喚演出の強さにだけ使う。
const civHue = { FIRE: 0, WATER: 212, WIND: 140, LIGHT: 44, DARK: 268, EARTH: 326 };
const rarityOf = (cost) => (cost >= 7 ? 'legendary' : cost >= 4 ? 'rare' : 'common');

const M = (id, name, civ, cost, atk, hp, text = '', extra = {}) =>
  ({ kind: 'minion', id, name, civ, cost, atk, hp, text, hue: civHue[civ], sig: 3 + (cost % 6), rarity: rarityOf(cost), ...extra });
const S = (id, name, civ, cost, effect, target, value, text) =>
  ({ kind: 'spell', id, name, civ, cost, effect, target, value, text, hue: civHue[civ], sig: 5 + (cost % 4), rarity: rarityOf(cost) });

export const MINIONS = [
  M('m-fire-1', '炎の従者', 'FIRE', 2, 2, 3, '【速攻】', { haste: true }),
  M('m-earth-1', '守りの岩兵', 'EARTH', 3, 1, 5, ''),
  M('m-wind-1', '雷翼の射手', 'WIND', 3, 2, 2, '【召喚時】ランダムな敵ミニオン1体に2ダメージ', { onSummon: 'zap2' }),
  M('m-water-1', '蒼海の番人', 'WATER', 4, 3, 4, ''),
  M('m-light-1', '白銀の聖騎士', 'LIGHT', 5, 4, 5, '【速攻】', { haste: true }),
  M('m-dark-1', '深淵の刈り手', 'DARK', 5, 5, 3, ''),
  M('m-fire-2', '紅蓮の古竜', 'FIRE', 7, 7, 6, '【速攻】', { haste: true }),
  M('m-earth-2', '創世神 ガイア', 'EARTH', 8, 8, 8, '【召喚時】このミニオン以外の、お互いの場のミニオンをすべて破壊', { onSummon: 'genesis' }),
];

/** target: 'enemyMinion' / 'anyMinion' / 'allyMinion' / 'none' */
export const SPELLS = [
  S('s-fire-1', 'マグマ・ストレート', 'FIRE', 1, 'fireball', 'enemyMinion', 3, 'ミニオン1体に3ダメージ'),
  S('s-wind-1', '天雷の連鎖', 'WIND', 4, 'chain', 'none', 2, '敵ミニオン全体に2ダメージ'),
  S('s-water-1', '蒼流の一撃', 'WATER', 3, 'chain', 'none', 3, '相手リーダーに3ダメージ', ),
  S('s-light-1', '聖光の癒し', 'LIGHT', 2, 'heal', 'none', 5, '自分のリーダーのLPを5回復'),
  S('s-earth-1', '大地の加護', 'EARTH', 2, 'buff', 'allyMinion', 2, '自分のミニオン1体に+2/+2'),
  S('s-dark-1', '冥府の断罪', 'DARK', 3, 'curse', 'enemyMinion', 0, '敵ミニオン1体を破壊'),
  S('s-dark-2', '虚無への追放', 'DARK', 4, 'curse', 'enemyMinion', 0, '敵ミニオン1体を消滅させる'),
];
SPELLS.find((s) => s.id === 's-water-1').hitsLeader = true;
SPELLS.find((s) => s.id === 's-dark-2').banish = true;

const ALL = new Map([...MINIONS, ...SPELLS].map((d) => [d.id, d]));
export const cardById = (id) => ALL.get(id);

export function randomCard(rng = Math.random) {
  const pool = rng() < 0.35 ? SPELLS : MINIONS;
  return pool[Math.floor(rng() * pool.length)];
}
