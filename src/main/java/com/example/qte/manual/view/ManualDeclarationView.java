package com.example.qte.manual.view;

import com.example.qte.manual.ManualDeclaration;
import com.example.qte.manual.ManualSeatId;

/**
 * 配信に載せる勝敗宣言1件(★Batch 35 設計書 2-3)。
 *
 * <h2>★{@code seq} が2つの役目を1本で果たす</h2>
 * この {@code seq} は、同じ配信に入っている {@link ManualLogView#seq()} と<b>同じ番号</b>である
 * (どちらも同じログ行から作る)。したがってクライアントは
 * <ul>
 *   <li>この番号の行に強調を当てる(ログの決着行)</li>
 *   <li>番号が<b>増えたとき</b>だけ帯を出す(再配信で二度出さない)</li>
 * </ul>
 * を、突き合わせ用の別のIDを発明せずに行える。32b の {@code turnNumber} が
 * 「増えたときだけ」の合図だったのと同じ形であり、帯の検出機構ごと転用している。
 *
 * <h2>★{@code label} を載せる理由</h2>
 * 表示名の正は {@link ManualDeclaration#getDisplayName()} 1箇所である(設計判断28)。
 * クライアントに列挙値だけを渡して日本語を組み立てさせると、同じ表が2箇所に写る。
 * 列挙値({@code declaration})は<b>見た目の出し分け</b>にだけ使う。
 *
 * @param seq         元になったログ行の通し番号
 * @param seat        宣言の主語となる席。★全公開部屋でも席は必ず指定される
 * @param declaration 宣言の内容(WIN / LOSE / DRAW / CONCEDE)。色の出し分けに使う
 * @param label       表示名(「勝利」など)。★クライアントで組み立て直さない
 */
public record ManualDeclarationView(
        int seq,
        ManualSeatId seat,
        ManualDeclaration declaration,
        String label) {
}
