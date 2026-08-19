package com.example.qte.effect;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.qte.game.GameActions;
import com.example.qte.game.GameService;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardTextKeywords;

/**
 * 「そのカードの効果をエンジンが処理するか」を答える唯一の場所(★Batch 47)。
 *
 * <h2>なぜこのクラスが要るのか</h2>
 *
 * Ver1.1 の235枚のうち、効果の文があるのはおよそ220枚で、そのうち何枚かは
 * まだ実装されていない。未実装のカードをデッキに入れると「使えるのに何も起きない」
 * という気づきにくい不具合になる。Batch 46b まではこれを<b>入口で弾く</b>ことで
 * 避けていたが、それでは Ver1.1 の全カードでデッキが組めない(裁定D2)。
 * そこで<b>入れられるようにしたうえで、盤面に印を出す</b>方針へ切り替えた。
 * 印を出すには「実装済みか」を実行時に答えられる必要があり、それがこのクラスである。
 *
 * <h2>実装の置き場所は2つある(裁定164)</h2>
 *
 * <ol>
 * <li><b>登録あり</b>: {@link CardEffectRegistry} の9つの表のどれかに載っている。
 *     {@link CardEffectRegistry#isRegistered(String)} が実行時の表そのものを見て答える。</li>
 * <li><b>参照あり</b>: 表ではなく<b>ルール側の判定点に直接書かれている</b>。
 *     {@link RuleGuards}(できる／できないの判定)・{@link StatCalculator}(動的な数値)・
 *     {@link GameService}(ウェポンの攻撃時)・{@link GameActions}(置換効果)・
 *     {@link PlayerState}(ゾーン上限)がこれにあたる。
 *     <b>Java からは「どこかの if 文に書いてある」ことを知る手段が無い</b>ので、
 *     判定点を持つクラスが {@code IMPLEMENTED_CARDS} として自分で名乗る。</li>
 * </ol>
 *
 * <b>★この2つの和が「実装済み」である。</b> どちらか片方だけを見ると、
 * 実際には動いているカードを「未実装」と名指ししてしまう。
 *
 * <h2>「未実装」と言えるのは、効果の文があるときだけ</h2>
 *
 * キーワードしか書かれていないカード(《ホーリー・ガーディアン》の【守護】【潜伏】など)は、
 * エンジンにカードIDが1つも現れないが、キーワードの仕組みで正しく動く。
 * 効果の文が残るかの判定は {@link CardTextKeywords#hasEffectSentence(String)} が持つ
 * —— テキストの読み方の規則は1箇所に集める(裁定158)。
 *
 * <h2>★この判定が答えられないこと(裁定165)</h2>
 *
 * 見ているのは<b>カードID単位</b>である。したがって
 * <b>「効果文が3つあってそのうち1つだけ未実装」は検出できない</b>。
 * 【召喚時】が登録されていれば、同じカードの【常在】が未実装でも「実装済み」と答える。
 * 部分実装の追跡は P2 の各文明バッチが設計解説で受け持つ。
 */
@Component
public class EffectImplementation {

    /**
     * 表ではなくルール側に直接書かれているカードの全体。
     *
     * ★<b>ここに書き写さないこと。</b>各クラスの宣言を集めているだけである。
     * 新しくルール側へカードを書いたら、直したクラスの {@code IMPLEMENTED_CARDS} に足す。
     * 足し忘れは {@code tools/report_effects.py} が検出して止まる。
     */
    private static final Set<String> RULE_SIDE = ruleSide();

    private static Set<String> ruleSide() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(CardEffectRegistry.IMPLEMENTED_CARDS);
        all.addAll(RuleGuards.IMPLEMENTED_CARDS);
        all.addAll(StatCalculator.IMPLEMENTED_CARDS);
        all.addAll(GameService.IMPLEMENTED_CARDS);
        all.addAll(GameActions.IMPLEMENTED_CARDS);
        all.addAll(PlayerState.IMPLEMENTED_CARDS);
        return Set.copyOf(all);
    }

    private final CardEffectRegistry effects;

    public EffectImplementation(CardEffectRegistry effects) {
        this.effects = effects;
    }

    /** ルール側に直接書かれているカードの一覧(検証用。実装済み判定は下の2つを使う) */
    public static Set<String> ruleSideCards() {
        return RULE_SIDE;
    }

    /** このカードの効果をエンジンが処理するか(効果の文が無いカードは常に true) */
    public boolean isImplemented(CardMaster card) {
        if (!CardTextKeywords.hasEffectSentence(card.text())) {
            return true;
        }
        return effects.isRegistered(card.id()) || RULE_SIDE.contains(card.id());
    }

    /**
     * 「効果未実装」の印を出すか。
     * 画面に出る文言の根拠はこの1つのメソッドであり、クライアントは判定を持たない。
     */
    public boolean isUnimplemented(CardMaster card) {
        return !isImplemented(card);
    }
}
