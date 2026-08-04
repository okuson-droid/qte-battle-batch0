package com.example.qte.manual.view;

/**
 * 配信用のログ1行。
 *
 * 時刻は整形済みの文字列で送る。Instant のまま載せると、
 * 配信経路の見え方が Jackson の時刻シリアライズ設定に依存してしまう。
 * ログは人が読むためのものであり、クライアントで再整形する必要が無い。
 */
public record ManualLogView(int seq, String time, String text) {
}
