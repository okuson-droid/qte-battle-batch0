package com.example.qte.deck;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;

import lombok.RequiredArgsConstructor;

/**
 * デッキファイルの検証。総合ルール第1章(デッキ構築)をここで一元的に適用する。
 *
 * デッキファイルはユーザーのPCから来る、いくらでも書き換えられるデータである。
 * クライアント側のデッキビルダーも同じ規則で入力を制限するが、それは操作の補助にすぎず、
 * 対戦に使えるかどうかの最終判定は必ずこのクラスが行う
 * (WebSocketの操作検証と同じ考え方: 信用するのは自分の検証だけ)。
 */
@Component
@RequiredArgsConstructor
public class DeckValidator {

    public static final int MAIN_DECK_SIZE = 40;
    public static final int TABOO_DECK_SIZE = 8;
    public static final int MAX_SAME_NAME = 4;

    /**
     * 効果を実装済みの文明。未実装文明のカードは「入れられるのに何も起きない」ため禁止する。
     * Batch 13c で全6文明がそろった。
     *
     * <b>順序が定まる集合を使う理由。</b> この集合はリーダー選択画面の並び順にも使われる
     * ({@link #implementedCivilizations()})。{@code Set.of} は反復順が保証されないため、
     * 画面のリーダーの並びが実行のたびに変わってしまう。{@code EnumSet} は列挙体の宣言順で
     * 反復するため、並びが安定する。
     */
    private static final Set<Civilization> IMPLEMENTED =
            EnumSet.of(Civilization.WATER, Civilization.FIRE, Civilization.DARK, Civilization.LIGHT,
                    Civilization.WIND, Civilization.EARTH);

    /**
     * 効果を実装済みの文明(列挙体の宣言順)。
     * 「どの文明が遊べるか」の判断はこのクラスを唯一の正とし、画面側で列挙を書き写さない。
     */
    public static Set<Civilization> implementedCivilizations() {
        return java.util.Collections.unmodifiableSet(IMPLEMENTED);
    }

    /*
     * ★Batch 46b: 同名無制限(UNLIMITED_COPIES)の例外表を撤廃した。
     *
     * 「このカードは4枚以上入れられる」というテキストを持つカードは、Ver1.1 の235枚に
     * 1枚も存在しない(Batch 30 で確認し、46b の移行後も 0 枚である)。
     * 該当が無い例外表は、次に読む人に「そういう仕組みがある」と誤解させるだけで、
     * しかも表に載ったIDが今も正しいかを誰も確かめられない。
     * 必要になったら、そのカードが来たときに作り直すほうが安全である(裁定「同名無制限を
     * コードに書かない」の実行)。
     */

    private final CardMasterRepository cards;

    /** 検証してリーダーを返す。違反があればIllegalArgumentExceptionを投げる */
    public CardMaster validate(DeckDefinition deck) {
        if (deck == null) {
            throw new IllegalArgumentException("デッキファイルが読み込めませんでした");
        }
        if (deck.formatVersion() != DeckDefinition.CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "対応していないデッキファイル形式です(version=%d)".formatted(deck.formatVersion()));
        }
        CardMaster leader = requireCard(deck.leaderCardId(), "リーダー");
        if (leader.type() != CardType.LEADER) {
            throw new IllegalArgumentException("リーダーカードではありません: " + leader.name());
        }
        requireImplemented(leader);

        validateMain(deck, leader);
        validateTaboo(deck, leader);
        return leader;
    }

    /** メインデッキ: 40枚・リーダーと同一文明・同名4枚まで(1-2, 1-2-1) */
    private void validateMain(DeckDefinition deck, CardMaster leader) {
        if (deck.main() == null || deck.main().isEmpty()) {
            throw new IllegalArgumentException("メインデッキが空です");
        }
        int total = 0;
        Set<String> seen = new HashSet<>();
        for (DeckDefinition.Entry entry : deck.main()) {
            CardMaster card = requireCard(entry.cardId(), "メインデッキ");
            if (!seen.add(entry.cardId())) {
                throw new IllegalArgumentException("メインデッキに同じカードの行が重複しています: " + card.name());
            }
            if (entry.count() <= 0) {
                throw new IllegalArgumentException("枚数が不正です: " + card.name());
            }
            requireDeckable(card, "メインデッキ");
            if (card.civilization() != leader.civilization()) {
                throw new IllegalArgumentException(
                        "メインデッキはリーダーと同じ文明のカードのみです: " + card.name());
            }
            if (entry.count() > MAX_SAME_NAME) {
                throw new IllegalArgumentException(
                        "同名カードは%d枚までです: %s".formatted(MAX_SAME_NAME, card.name()));
            }
            requireImplemented(card);
            total += entry.count();
        }
        if (total != MAIN_DECK_SIZE) {
            throw new IllegalArgumentException(
                    "メインデッキは%d枚である必要があります(現在%d枚)".formatted(MAIN_DECK_SIZE, total));
        }
    }

    /** 禁忌デッキ: 8枚・リーダーと異なる文明・同名1枚まで(1-3, 1-3-1) */
    private void validateTaboo(DeckDefinition deck, CardMaster leader) {
        if (deck.taboo() == null) {
            throw new IllegalArgumentException("禁忌デッキがありません");
        }
        if (deck.taboo().size() != TABOO_DECK_SIZE) {
            throw new IllegalArgumentException(
                    "禁忌デッキは%d枚である必要があります(現在%d枚)"
                            .formatted(TABOO_DECK_SIZE, deck.taboo().size()));
        }
        Set<String> seen = new HashSet<>();
        for (String cardId : deck.taboo()) {
            CardMaster card = requireCard(cardId, "禁忌デッキ");
            if (!seen.add(cardId)) {
                throw new IllegalArgumentException("禁忌デッキは同名カード1枚までです: " + card.name());
            }
            requireDeckable(card, "禁忌デッキ");
            if (card.civilization() == leader.civilization()) {
                throw new IllegalArgumentException(
                        "禁忌デッキはリーダーと異なる文明のカードのみです: " + card.name());
            }
            requireImplemented(card);
        }
    }

    /**
     * デッキに入れてよい種別かの確認(★Batch 46b)。
     *
     * <ul>
     * <li><b>リーダー</b>はデッキの外にある1枚であり、山札にも禁忌にも入らない(総合ルール1章)。</li>
     * <li><b>進化ミニオン</b>は<b>★Batch 52 で解禁した。</b>
     *     46b〜51 のあいだ弾いていたのは「場に出す手段そのものがエンジンに無い」ためであり
     *     (裁定166)、手札で完全な死に札になるからだった。52 が進化エンジン
     *     (素材の指定・下に置く構造・場を離れるときの同伴。裁定154・157)を作り、
     *     18枚すべての素材条件を登録したので、その理由が消えた。
     *     ★メインにも<b>禁忌デッキにも入れられる</b>(マスター裁定 E1。出し方は通常と同じで、
     *     コストの支払い方だけが禁忌の作法になる)。裁定2 のとおりメイン40枚に算入する。
     *     効果が未実装のものは「出せるが効果が起きない」——
     *     裁定D2 の普通の姿であり、盤面には印が出る。</li>
     * </ul>
     */
    private void requireDeckable(CardMaster card, String where) {
        if (card.type() == CardType.LEADER) {
            throw new IllegalArgumentException("リーダーは%sに入れられません: %s".formatted(where, card.name()));
        }
    }

    private CardMaster requireCard(String cardId, String where) {
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException(where + "のカードIDが空です");
        }
        try {
            return cards.findById(cardId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("%sに存在しないカードがあります: %s".formatted(where, cardId));
        }
    }

    /**
     * 実装済みかの確認。
     *
     * <b>★Batch 47: 効果が未実装のスペルを弾くのをやめた。</b>
     *
     * <p>Batch 46b までは、効果の登録が無いスペルをここで拒否していた。
     * 「使えるのに何も起きない」が気づきにくい不具合になるためである。
     * だがそれは Ver1.1 の全カードでデッキが組めないということでもあり、
     * 裁定D2 が選んだ道は<b>「入れられるようにして、印を出す」</b>だった。
     * 気づきにくさの原因は「入れられること」ではなく「黙って不発になること」なので、
     * 印({@link com.example.qte.effect.EffectImplementation})が出るなら止める理由は無い。</p>
     *
     * <p>★<b>進化ミニオンだけは今も {@link #requireDeckable} が弾く</b>(裁定166)。
     * こちらは効果ではなく<b>場に出す手段そのもの</b>が無く、印を見てもできることが
     * 1つも無いためである。P3(Batch 54〜55)で解禁する。</p>
     *
     * <p>文明の検査は残している。全6文明が実装済みなので現在は誰も弾かないが、
     * 文明なし(ピュア・エレメント)はデッキに入るカードではないため、ここで止まる。</p>
     */
    private void requireImplemented(CardMaster card) {
        if (!IMPLEMENTED.contains(card.civilization())) {
            throw new IllegalArgumentException(
                    "%s文明はまだ実装されていません: %s"
                            .formatted(card.civilization().getDisplayName(), card.name()));
        }
    }
}
