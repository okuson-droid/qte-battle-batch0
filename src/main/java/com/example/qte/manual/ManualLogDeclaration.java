package com.example.qte.manual;

/**
 * 勝敗宣言のログ1行が持つ<b>構造</b>(★Batch 35 設計書 2-2)。
 *
 * <h2>なぜ本文とは別に構造を残すのか</h2>
 * 宣言は{@link ManualLogKind#isPlain() 本文がそのまま出る種別}であり、
 * 21a の分類でいえば「文字列で確定できる行」である。それでも構造を添えるのは、
 * <b>本文を読み返すのが人間だけではなくなった</b>からである。
 * Batch 35 で勝敗の帯とログの強調行を出すにあたり、クライアントは
 * 「この行が決着である」「どちらの席の何の宣言か」を知る必要がある。
 *
 * ★<b>本文から復元してはならない。</b>「席A の 勝利を宣言した」を正規表現で読むのは、
 * 21a が文字列ログをやめた理由({@link ManualLogEvent} の javadoc)を
 * 表示側で繰り返すことである。書いた側が構造を渡すほうが安い。
 *
 * @param seat        <b>宣言の主語</b>となる席。★{@link ManualLogEvent#actorSeat()} とは別物である。
 *                    全公開部屋では1人が両席を操作するため、押した人には席が無いことがある
 * @param declaration 宣言の内容。表示名の正は {@link ManualDeclaration#getDisplayName()} 1箇所である
 */
public record ManualLogDeclaration(ManualSeatId seat, ManualDeclaration declaration) {
}
