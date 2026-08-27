package com.example.qte.effect;

/**
 * ターン終了で自動的に消えない持続効果。
 *
 * これまでの「このターン中」の効果は {@code PlayerState.thisTurnAuras} に置き、
 * ターン終了時に一括で消していた。光文明では期限の種類が異なる効果が登場したため、
 * 期限を型として持つ入れ物を用意した。
 *
 * <pre>
 *   詠唱の宝珠   : 次の自分のターンの終了時まで(★Batch 73 で ON_NEXT_SPELL から移した)
 *   聖光の守護聖 : 次の相手のターン終了時まで(自分のターンをまたぐ)
 * </pre>
 *
 * <h2>★★★Batch 73: {@code ON_NEXT_SPELL} を消した(裁定196 — 消した番人は書き残す)</h2>
 * 《詠唱の宝珠》の本文は Ver1.1 で
 * 「次の1枚」→「<b>次の自分のターンに唱える光のスペルすべて</b>」に変わっていた
 * ({@code notes/ver0.4-transcription-notes.md} 5章の台帳 0106 と、
 * 4章のルーリング #9「発注者確認済み」)。
 * <b>72 まで、実装は Ver0.4 の「次の1枚」のままだった。</b>
 *
 * <p>★★<b>Batch 56 は「光のスペル」への限定だけを入れて、枚数の側を見ていなかった。</b>
 * しかも {@code StatCalculator} のコメントが
 * 「Ver1.1 で『スペルすべて』から『光のスペル』に限定された」と書いたので、
 * <b>「すべて」のほうは実装済みであるかのように読めた</b> ——
 * 67 の教訓(写し)の新しい顔である: <b>直した箇所の記録が、直していない箇所を覆い隠す。</b>
 *
 * <p>★<b>新しい期限は作らなかった。</b>「次の自分のターンの終了時」は
 * {@link Expiry#AFTER_TURN_NUMBER} でそのまま表せる ——
 * 付与するときに<b>次の自分のターン番号</b>を計算して渡す
 * ({@code GameActions.onWeaponLeftPlay})。
 * ★★「番人が無い」と思ったらまず在るかどうかを見る(65 の教訓)は、<b>器にも効く</b>。
 *
 * <b>なぜカードごとのフィールドにしなかったか。</b>
 * 「詠唱の宝珠用のboolean」「守護聖用のint」と個別に持つ方が短く書けるが、
 * 期限の管理コード(いつ消すか)がそのたびにGameServiceへ散らばる。
 * 期限を {@link Expiry} として型に持たせておけば、消す処理は
 * 「期限切れのものを取り除く」1箇所で済み、以降の文明でも同じ器を使い回せる。
 *
 * @param cardId          この効果を与えたカードのID(効果の中身の識別に使う)
 * @param expiry          いつ消えるか
 * @param expiresAfterTurn {@link Expiry#AFTER_TURN_NUMBER} のとき、このターン番号の
 *                        終了時に消える。それ以外の期限では使わない(0)
 */
public record PersistentAura(String cardId, Expiry expiry, int expiresAfterTurn) {

    public enum Expiry {
        /**
         * 指定したターン番号の終了時に消える(聖光の守護聖・★Batch 73 から詠唱の宝珠も)。
         *
         * <p>★<b>73 の時点で、期限はこれ1種類になった。</b>
         * {@code ON_NEXT_SPELL}(次にスペルを唱えたら消える)は
         * 《詠唱の宝珠》ただ1つの使い手を失って消えた ——
         * <b>誰も登録しない器は残さない</b>(裁定178)。
         * ★もう一度要るようになったら、そのとき作り直せばよい。
         */
        AFTER_TURN_NUMBER
    }

    /** 指定ターンの終了時まで持続する効果を作る */
    public static PersistentAura untilEndOfTurn(String cardId, int turnNumber) {
        return new PersistentAura(cardId, Expiry.AFTER_TURN_NUMBER, turnNumber);
    }
}
