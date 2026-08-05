package com.example.qte.manual;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 観戦者の視点(設計書16 11-2 / Batch 21 設計書 3-2)。
 *
 * ★これは<b>プレイヤーには適用されない</b>。プレイヤーは常に「自席の情報だけが見える」であり、
 * 選択肢が無い。観戦者だけが2択を持つのは、観戦の目的が2つに割れているためである。
 * 検証・解説のための観戦は全部見えたほうがよく、対戦を横で楽しむ観戦は
 * プレイヤーと同じ情報量でなければ面白くない。
 *
 * <h2>切替はサーバへ送る(3-2)</h2>
 * 「全部送っておいてクライアントで隠す」形にすると、公開のみ視点の観戦者のブラウザに
 * 相手の手札が届いてしまう。3-3 が「カードオブジェクトを一切載せない」と定めた意味が消える。
 * したがって切替はサーバに届き、サーバが以後のビューとログのフィルタを変える。
 */
@Getter
@RequiredArgsConstructor
public enum ManualSpectatorView {

    /** 全見え。両席の手札・山札・禁忌・確認まで見える */
    ALL("全見え"),

    /** 公開のみ。両プレイヤーが互いに見せている情報だけが見える(既定) */
    PUBLIC_ONLY("公開のみ");

    private final String displayName;
}
