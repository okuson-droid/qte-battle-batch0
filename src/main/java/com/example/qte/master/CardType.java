package com.example.qte.master;

/**
 * カードの種別。表記形式(Cost/Attack/HPの有無)と対応する。
 *
 * <p><b>★Batch 46b で EVOLUTION を足した。</b> Ver1.1(235枚)には進化ミニオンが18枚あり、
 * カードマスタを {@code manual-cards.json} へ差し替えた以上、この列挙体で表せなければ
 * 起動時のロードそのものが落ちる。
 *
 * <p><b>ただし「表せる」ことと「遊べる」ことは別である。</b> 進化を場に出す手段は
 * エンジンにまだ無い(素材の指定・下に置く構造・場を離れるときの同伴は P3 = Batch 54〜55)。
 * そのため進化ミニオンは {@link com.example.qte.deck.DeckValidator} がデッキ構築で弾く(裁定166)。
 * 効果未実装のミニオンは「出せるが何も起きない」で済むが、進化は<b>出す手段そのものが無い</b>ので、
 * デッキに入れると手札で完全な死に札になる。
 *
 * <p>手動モードは別系統の {@link com.example.qte.manual.ManualCardType} を持つ(設計書 2-1)。
 * あちらは以前から EVOLUTION を持っており、今回それに追いついた形である。
 * 型を共有しないという判断はそのまま維持する —— 片方の都合がもう片方の制約にならないようにするためである。
 */
public enum CardType {
    LEADER, MINION, EVOLUTION, SPELL, WEAPON
}
