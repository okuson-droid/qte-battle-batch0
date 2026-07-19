package com.example.qte.effect;

import java.util.List;

import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;

/**
 * 検証済みの対象。効果のラムダはインデックスではなくこの解決済みの実体を受け取る。
 *
 * 手札の対象カードは検証の時点で手札から取り除かれた状態で渡される(行き先は効果が決める:
 * 大蟹なら墓地、プレサージュなら山札の下、など)。
 *
 * @param selections TargetSpecのrequirementsと同じ順の選択結果
 */
public record ResolvedTargets(List<Selection> selections) {

    public Selection get(int requirementIndex) {
        return selections.get(requirementIndex);
    }

    /**
     * 要求1件分の選択結果。
     *
     * @param handCardIds 選ばれた手札カードのID(手札からは既に除去済み)
     * @param minions     選ばれた場のミニオン(支配者付き)
     * @param mana        選ばれたマナ(マナゾーンには残ったまま渡される。移動は効果が行う)
     * @param trashCardIds 選ばれた墓地のカードID(墓地には残ったまま渡される。移動は効果が行う)
     */
    public record Selection(List<String> handCardIds, List<TargetedMinion> minions,
            List<ManaCard> mana, List<String> trashCardIds) {
        public boolean isEmpty() {
            return handCardIds.isEmpty() && minions.isEmpty() && mana.isEmpty() && trashCardIds.isEmpty();
        }
    }

    /** 選ばれたミニオンとその支配者の組 */
    public record TargetedMinion(PlayerState owner, MinionInstance minion) {
    }
}
