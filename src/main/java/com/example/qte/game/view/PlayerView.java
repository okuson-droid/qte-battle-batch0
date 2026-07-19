package com.example.qte.game.view;

import java.util.List;

/**
 * プレイヤー1人分のビュー。
 * 自分用には hand(中身) を入れ、相手用には handCount のみ入れて hand は null にする。
 *
 * @param trashCardNames 墓地のカード名一覧(墓地は公開情報のため両者に送る)
 * @param trash          墓地のカードの中身。墓地を対象に取る効果の選択UIが使う(公開情報)
 * @param lostCount      消滅(Lost)ゾーンの枚数
 * @param lostCardNames  消滅ゾーンのカード名一覧(公開情報)
 * @param tabooCount     禁忌デッキの残り枚数(相手にも枚数だけは見える)
 * @param taboo          禁忌デッキの中身。所有者本人のビューにのみ入り、相手のビューではnull(3-2)
 * @param manaCharged   このターンのマナチャージを済ませたか(自動進行の判定に使う)
 * @param cannotUseCards このターンカードを使用できないか(静寂の瞑想)
 * @param mulliganDone  マリガンを完了したか
 * @param leaderCardId  リーダーのカードID(常在能力の有無をクライアントが判定するのに使う)
 * @param leaderText    リーダーカードの効果テキスト(いつでも確認できるようにする)
 * @param deckName      使用しているデッキ名(公開情報)
 * @param weaponName    装備中ウェポン名(未装備はnull)
 * @param weaponAttack  ウェポンの現在攻撃力(動的値込み。水刺客など)
 * @param leaderCanAttack 今リーダーが攻撃宣言できるか(自分のビューでのみ意味を持つ)
 * @param leaderFrozen  リーダーが凍結中か
 * @param leaderAbility リーダー起動能力の状態(能力を持たないリーダーはnull)
 */
public record PlayerView(
        String displayName,
        String leaderName,
        String leaderCardId,
        int lp,
        int deckCount,
        int handCount,
        List<CardView> hand,
        int availableMp,
        int totalMana,
        List<ManaView> manaZone,
        List<MinionView> minions,
        int trashCount,
        List<String> trashCardNames,
        List<CardView> trash,
        int lostCount,
        List<String> lostCardNames,
        int tabooCount,
        List<CardView> taboo,
        boolean manaCharged,
        boolean cannotUseCards,
        boolean mulliganDone,
        String leaderText,
        String deckName,
        String weaponName,
        Integer weaponAttack,
        boolean leaderCanAttack,
        boolean leaderFrozen,
        LeaderAbilityView leaderAbility) {

    /** リーダー起動能力のビュー */
    public record LeaderAbilityView(
            boolean usable,
            int mpCost,
            String description,
            List<CardView.TargetReqView> targets) {
    }
}
