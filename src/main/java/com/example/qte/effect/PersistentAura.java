package com.example.qte.effect;

/**
 * ターン終了で自動的に消えない持続効果。
 *
 * これまでの「このターン中」の効果は {@code PlayerState.thisTurnAuras} に置き、
 * ターン終了時に一括で消していた。光文明では期限の種類が異なる効果が登場したため、
 * 期限を型として持つ入れ物を用意した。
 *
 * <pre>
 *   詠唱の宝珠   : 次にスペルを唱えるまで(ターンをまたぐ・1回きり)
 *   聖光の守護聖 : 次の相手のターン終了時まで(自分のターンをまたぐ)
 * </pre>
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
        /** 指定したターン番号の終了時に消える(聖光の守護聖) */
        AFTER_TURN_NUMBER,
        /** 次にスペルを唱えたら消える。ターンをまたいで残る(詠唱の宝珠) */
        ON_NEXT_SPELL
    }

    /** 指定ターンの終了時まで持続する効果を作る */
    public static PersistentAura untilEndOfTurn(String cardId, int turnNumber) {
        return new PersistentAura(cardId, Expiry.AFTER_TURN_NUMBER, turnNumber);
    }

    /** 次にスペルを唱えるまで持続する効果を作る */
    public static PersistentAura untilNextSpell(String cardId) {
        return new PersistentAura(cardId, Expiry.ON_NEXT_SPELL, 0);
    }
}
