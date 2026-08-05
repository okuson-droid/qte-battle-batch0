package com.example.qte.manual;

import java.time.Instant;

/**
 * ログ1行(設計書 5-5 / Batch 21 設計書 5-1)。
 *
 * ★手動モードのログは補助機能ではない。アプリは効果を解決しないため、
 * 「何が起きたのか」を記録できるのは人間だけであり、ログこそがこのモードの成果物である。
 * だから古い行を捨てず、テキストファイルとして書き出せるようにする(書き出しは Batch 19a)。
 *
 * <h2>★Batch 21a で本文を {@link ManualLogEvent} に差し替えた</h2>
 * 20c までは {@code text} という1本の文字列だった。対戦部屋では同じ行が閲覧者ごとに
 * 違う見え方をしなければならない(5-2)ため、行の意味を構造のまま保持し、
 * <b>配信とダウンロードの瞬間に</b>閲覧者ごとの文字列へ変換する
 * ({@link ManualLogRenderer})。
 *
 * 通し番号と時刻だけがここに残ったのは、この2つが「部屋が採番するもの」だからである。
 * イベントを作る側({@link ManualOperationService})は番号も時刻も知らなくてよい。
 *
 * @param seq   1始まりの通し番号。クライアントが差分だけを描き足すために使う
 * @param at    記録時刻。★DTOには Instant のまま載せず、表示用の文字列に整形して送る
 *              (シリアライズ設定への依存を配信経路に持ち込まないため)
 * @param event 何が起きたか。★表示文字列ではない
 */
public record ManualLogEntry(int seq, Instant at, ManualLogEvent event) {
}
