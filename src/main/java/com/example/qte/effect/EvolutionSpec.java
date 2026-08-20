package com.example.qte.effect;

import java.util.function.Predicate;

import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;

/**
 * 進化ミニオンの召喚素材の仕様(★Batch 52。裁定154・157)。
 *
 * <h2>これは「効果」ではなく「出し方」である</h2>
 *
 * 進化ミニオンは、コストを支払って手札から出すという点では通常の召喚と変わらないが、
 * <b>自分の場のミニオンを素材として下に置く</b>ことが召喚の一部になっている。
 * つまりこの仕様が答えるのは「このカードの効果は何か」ではなく
 * <b>「このカードを場に出せるか」</b>である。
 *
 * <p>★したがって、この表への登録は
 * {@link CardEffectRegistry#isRegistered(String)} が数えない ——
 * 数えてしまうと、素材条件を書いただけで<b>効果が未実装のカードから印が消える</b>。
 * 逆に、素材条件しか効果の文を持たないカード(《海淵獣シラーカ》)と、
 * 素材の数そのものが効果であるカード(《不敗鉄人闘太》)は、
 * {@link CardEffectRegistry#IMPLEMENTED_CARDS} で名乗る(裁定176)。
 *
 * <h2>素材は必ず自分の場である(マスター裁定 A2)</h2>
 *
 * 18枚のうち6枚は本文が「ミニオン1体」としか書いておらず、
 * 裁定211(限定を本文どおりに写す)だけを見れば相手の場も含みうる。
 * だが素材は<b>自分の進化ミニオンの下に置かれる</b>ので、相手のミニオンを取れると
 * 事実上の最強除去になる。マスター裁定により、素材は常に自分の場に限る。
 * {@link #material} が {@link Predicate} 1つで足りているのはこのためである。
 *
 * @param minMaterials     必要な素材の最小数
 * @param maxMaterials     素材の最大数。「1体以上」(《不敗鉄人闘太》)のように上限の無い
 *                         カードは {@link PlayerState#MAX_MINION_ZONE_LIMIT} を入れる
 * @param statPerUnderCard 下にあるカード1枚につき Attack・HP に足す量(《不敗鉄人闘太》のみ 2)。
 *                         0 なら加算しない
 * @param description      素材条件の説明。画面の案内文になる
 * @param material         素材にできるミニオンか。自分の場のミニオンにだけ適用される
 */
public record EvolutionSpec(int minMaterials, int maxMaterials, int statPerUnderCard,
        String description, Predicate<MinionInstance> material) {

    public EvolutionSpec {
        if (minMaterials < 1 || maxMaterials < minMaterials) {
            throw new IllegalArgumentException("進化素材の数の指定が不正です");
        }
    }

    /** ちょうど count 体を素材にする(18枚のうち17枚がこの形) */
    public static EvolutionSpec exactly(int count, String description,
            Predicate<MinionInstance> material) {
        return new EvolutionSpec(count, count, 0, description, material);
    }

    /**
     * min 体以上を素材にする(《不敗鉄人闘太》の「自分ミニオン1体以上」)。
     * 上限は場のミニオンの最大数であり、実際に選べる数は盤面が決める。
     */
    public static EvolutionSpec atLeast(int min, int statPerUnderCard, String description,
            Predicate<MinionInstance> material) {
        return new EvolutionSpec(min, PlayerState.MAX_MINION_ZONE_LIMIT, statPerUnderCard,
                description, material);
    }
}
