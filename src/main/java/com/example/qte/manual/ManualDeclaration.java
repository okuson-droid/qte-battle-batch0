package com.example.qte.manual;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 勝敗の宣言(設計書 5-3 の12)。
 *
 * ★これは<b>ログに1行足すだけ</b>の操作であり、盤面には一切触らない。
 * 手動モードは勝敗を判定しない(設計書 5-1)。LPが0を下回っても、山札が尽きても、
 * アプリは何も起こさない。「決着した」と記録できるのは人間だけである。
 *
 * 自由メモ(5-5)でも同じことは書けるが、決着は1試合に1回しか起きない節目であり、
 * 検証の成果物としてログを読み返すときの区切りになる。
 * 表記を揺らさないために列挙体にしてある。
 */
@Getter
@RequiredArgsConstructor
public enum ManualDeclaration {

    WIN("勝利"),
    LOSE("敗北"),
    DRAW("引き分け"),
    CONCEDE("投了");

    private final String displayName;
}
