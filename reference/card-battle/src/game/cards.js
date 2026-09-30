// カード定義（デモ用）。見た目に使う値（hue: 色相, sig: 模様の角数）もここに持たせる。

export const MINIONS = [
  { id: 'beetle', name: '黄金の甲虫', cost: 1, atk: 2, hp: 1, rarity: 'common', hue: 45, tribe: '獣', sig: 4 },
  { id: 'wolf', name: '灰燼の狼', cost: 2, atk: 3, hp: 2, rarity: 'common', hue: 18, tribe: '獣', sig: 3 },
  { id: 'archer', name: '森の射手', cost: 2, atk: 2, hp: 3, rarity: 'common', hue: 120, tribe: 'エルフ', sig: 6 },
  { id: 'sentinel', name: '石壁の番兵', cost: 3, atk: 1, hp: 6, rarity: 'common', hue: 200, tribe: 'ゴーレム', sig: 4 },
  { id: 'swordsman', name: '蒼雷の剣士', cost: 4, atk: 4, hp: 3, rarity: 'rare', hue: 215, tribe: '人間', sig: 5 },
  { id: 'oracle', name: '星詠みの巫女', cost: 5, atk: 3, hp: 5, rarity: 'rare', hue: 285, tribe: '人間', sig: 8 },
  { id: 'abyssKnight', name: '深淵の騎士', cost: 7, atk: 6, hp: 5, rarity: 'legendary', hue: 265, tribe: '不死', sig: 7 },
  { id: 'crimsonDragon', name: '紅蓮の古竜', cost: 8, atk: 7, hp: 7, rarity: 'legendary', hue: 2, tribe: 'ドラゴン', sig: 6 },
].map((d) => ({ kind: 'minion', ...d }));

/** target: 'enemy'（敵1体）/ 'ally'（味方1体）/ 'allyMinion'（味方ミニオン1体）/ 'none'（対象なし） */
export const SPELLS = [
  { id: 'fireball', effect: 'fireball', name: '火球', cost: 4, hue: 18, sig: 3, rarity: 'common', target: 'enemy', value: 4, desc: '敵1体に4ダメージ' },
  { id: 'chain', effect: 'chain', name: '雷鳴の連鎖', cost: 5, hue: 205, sig: 5, rarity: 'rare', target: 'none', value: 2, desc: '敵ミニオン全体に2ダメージ' },
  { id: 'heal', effect: 'heal', name: '癒しの光', cost: 2, hue: 110, sig: 8, rarity: 'common', target: 'ally', value: 5, desc: '味方1体の体力を5回復' },
  { id: 'buff', effect: 'buff', name: '力の加護', cost: 3, hue: 45, sig: 4, rarity: 'rare', target: 'allyMinion', value: 2, desc: '味方ミニオン1体に+2/+2' },
].map((d) => ({ kind: 'spell', ...d }));

const ALL = new Map([...MINIONS, ...SPELLS].map((d) => [d.id, d]));

export function cardById(id) {
  const def = ALL.get(id);
  if (!def) throw new Error(`不明なカード: ${id}`);
  return def;
}

const pickWith = (rng, list) => list[Math.floor(rng() * list.length)];

/** レア度に偏りを付けてミニオンを 1 枚選ぶ */
export function randomMinion(rng) {
  const x = rng(), rarity = x < 0.15 ? 'legendary' : x < 0.45 ? 'rare' : 'common';
  return pickWith(rng, MINIONS.filter((d) => d.rarity === rarity));
}

/** 山札の代わり：3 割で呪文、残りはミニオン */
export function randomCard(rng) {
  return rng() < 0.3 ? pickWith(rng, SPELLS) : randomMinion(rng);
}
