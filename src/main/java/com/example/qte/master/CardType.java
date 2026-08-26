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
    LEADER, MINION, EVOLUTION, SPELL, WEAPON;

    /**
     * ミニオンの一種か(★Batch 67)。
     *
     * <p><b>進化ミニオンもミニオンである。</b> 総合ルール 2-1 が
     * 「進化ミニオンはメインデッキ40枚に算入する」と定めているとおり、
     * 進化は召喚の仕方が違うだけで種族としてはミニオンの一種である。
     *
     * <p>この述語を置いたのは、《禁忌の墓地利用》の本文
     * 「自分の墓地にある<b>ミニオンでないカード</b>を2枚選び」を実装するにあたって、
     * 「ミニオンでない」の判定が<b>2箇所</b>に要ったからである ——
     * 使用条件({@code CardEffectRegistry.playConditions})と
     * 対象の検証({@code GameService.checkFilter})である。
     * 同じ規則を2箇所に書くと、片方だけが直された状態が何バッチも続く(裁定130)。
     *
     * <p>★<b>「ミニオンでない」を {@code != MINION} と書いてはいけない。</b>
     * その書き方は進化ミニオンを「ミニオンでないカード」に数えてしまう。
     */
    public boolean isMinion() {
        return this == MINION || this == EVOLUTION;
    }
}
