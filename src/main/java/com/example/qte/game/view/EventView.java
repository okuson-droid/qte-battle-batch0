package com.example.qte.game.view;

import java.util.List;

/**
 * 閲覧者へ届く出来事(★Batch 85a・裁定371〜376)。
 *
 * <p>★<b>作るのは {@code GameViewBuilder#buildEvents} の1箇所だけである</b>(設計判断9)。
 * 生の {@code GameEvent} はプレイヤーIDを持つので、そのまま送ってはいけない。
 *
 * <h2>席は閲覧者から見た向きで書く</h2>
 *
 * {@link #side} は {@code "YOU"} / {@code "OPPONENT"} である ——
 * {@code GameView} の {@code you} / {@code opponent} と<b>同じ向き</b>であり、
 * 観戦者なら {@code you} が A 席である({@code GameViewBuilder#buildSpectatorView} と同じ)。
 * ★設計書 3-1 の {@code leader:A} を<b>ここで変えた</b> —— ビューが席の文字を1つも持たないので、
 * A/B で書くとクライアントに「どちらが自分か」の対応表がもう1つ要る。
 *
 * @param kind   {@code GameEvent.Kind} の名前
 * @param side   出来事の主の席({@code YOU} / {@code OPPONENT})
 * @param src    発生元。ミニオンなら instanceId、リーダーなら {@code leader:YOU} / {@code leader:OPPONENT}。無ければ null
 * @param dst    宛先。書き方は src と同じ
 * @param amount 量。無ければ null
 * @param after  適用後の値。無ければ null
 * @param cards  カードID。★{@code DRAW} は本人にだけ入り、他の閲覧者には空で届く
 */
public record EventView(String kind, String side, String src, String dst, Integer amount,
        Integer after, List<String> cards) {
}
