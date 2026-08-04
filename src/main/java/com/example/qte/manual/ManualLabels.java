package com.example.qte.manual;

import java.util.List;

/**
 * 札(設計書 5-4)。キーワード9種も凍結などの一時状態も、すべて短いテキストに統一する。
 *
 * <h2>★アプリは札の意味を解釈しない</h2>
 * {@link #DEFAULTS} は画面のワンタッチボタンに並べる候補にすぎず、
 * これ以外の文字列も自由に付けられる。サーバはこの一覧に含まれるかどうかを検査しない。
 * カードテキストには {@code 【賢魂：3】} {@code 【破壊時】} のように既存9種に無い記法が既に
 * 現れており(設計書 5-4)、既定の一覧を検証に使った瞬間に、
 * カードが増えるたびにサーバの改修が要る形になってしまう。
 *
 * <h2>長さと個数だけは制限する</h2>
 * これは裁定ではなく入力の衛生である。札は状態モデルに入り、
 * スナップショットとして200件複製される(設計書 5-6)。
 * 長文を貼り付けられると履歴が丸ごと太る。自由メモ(5-5)がその受け皿である。
 */
public final class ManualLabels {

    /** 既定9種(設計書 5-4)。画面のワンタッチボタン用の候補であり、検証には使わない。 */
    public static final List<String> DEFAULTS = List.of(
            "速攻", "突進", "守護", "潜伏", "威圧", "貫通", "知識", "還元", "特殊召喚");

    /** 札1つの最大文字数。 */
    public static final int MAX_LENGTH = 24;

    /** 1枚のカードに付けられる札の最大個数。 */
    public static final int MAX_PER_CARD = 20;

    private ManualLabels() {
    }

    /** 前後の空白を落とし、空文字と長すぎるものを弾く。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("札の文字列が空です");
        }
        String label = raw.trim();
        if (label.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("札は %d 文字までです".formatted(MAX_LENGTH));
        }
        return label;
    }
}
