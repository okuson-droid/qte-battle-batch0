package com.example.qte.manual;

import java.time.Instant;

/**
 * ログ1行(設計書 5-5)。
 *
 * ★手動モードのログは補助機能ではない。アプリは効果を解決しないため、
 * 「何が起きたのか」を記録できるのは人間だけであり、ログこそがこのモードの成果物である。
 * だから古い行を捨てず、テキストファイルとして書き出せるようにする(書き出しは Batch 19a)。
 *
 * @param seq  1始まりの通し番号。クライアントが差分だけを描き足すために使う
 * @param at   記録時刻。★DTOには Instant のまま載せず、表示用の文字列に整形して送る
 *             (シリアライズ設定への依存を配信経路に持ち込まないため)
 * @param text 本文。自動記録も自由メモも同じ形で入る
 */
public record ManualLogEntry(int seq, Instant at, String text) {
}
