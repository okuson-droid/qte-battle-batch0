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
 * @param revealedCards 一時公開領域のカード(降臨の伝道師などが公開中の束。空なら公開なし)
 * @param manaPayOrder  ★Batch 70(裁定315・316): 通常のコストを<b>自動で払うときの順</b>。
 *                      マナゾーン内の位置を、払われる順に並べたものである。
 *                      ★<b>クライアントは規則を持たない</b> —— コストが n なら先頭 n 件が
 *                      「これから払われるマナ」であり、ドラッグ中にそこを強調表示する。
 *                      規則をクライアントへ書き写すと、サーバの払い方が変わった日に
 *                      強調表示だけが黙って嘘になる(67 の教訓・写し)。★自分のビューにのみ入る
 * @param tabooPayOrder ★Batch 70(裁定317): 禁忌コストを自動で払うときの順(表向き → 裏向き)。
 *                      ★自分のビューにのみ入る。
 *                      ★★<b>「裏向きが墓地送りになるか」という真偽値はここに載せない。</b>
 *                      何枚払うかはカードごとに違うので、真偽値1つでは足りない ——
 *                      クライアントは<b>この順の先頭 n 件</b>の表裏を
 *                      {@code manaZone}(公開情報)から見て警告を出す(裁定317)
 * @param pendingChoice 割り込み選択の問い合わせ(a9)。選択待ちでなければnull
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
        List<Integer> manaPayOrder,
        List<Integer> tabooPayOrder,
        List<MinionView> minions,
        int trashCount,
        List<String> trashCardNames,
        List<CardView> trash,
        int lostCount,
        List<String> lostCardNames,
        List<CardView> lost,
        int tabooCount,
        List<CardView> taboo,
        boolean manaCharged,
        boolean cannotUseCards,
        boolean mulliganDone,
        String leaderText,
        String deckName,
        String weaponName,
        String weaponCardId,
        Integer weaponAttack,
        boolean leaderCanAttack,
        boolean leaderFrozen,
        LeaderAbilityView leaderAbility,
        List<RevealedCardView> revealedCards,
        PendingChoiceView pendingChoice) {

    /** リーダー起動能力のビュー */
    public record LeaderAbilityView(
            boolean usable,
            int mpCost,
            String description,
            List<CardView.TargetReqView> targets) {
    }

    /**
     * 一時公開領域のカード1枚のビュー(降臨の伝道師などが公開中の束)。
     *
     * @param index 公開した束の中での位置
     * @param guard 【守護】を持つか(降臨の伝道師の表示補助)
     */
    public record RevealedCardView(int index, String name, List<String> keywords, boolean guard) {
    }

    /**
     * 割り込み選択の問い合わせ(a9)。クライアントはこれを見て選択UIを出し、
     * 選んだ候補の位置(candidatesの0起点)を resolve-choice で送り返す。
     *
     * @param kind       何の中から選ぶか(HAND/MINION/TRASH/REVEALED)
     * @param candidates 選べる候補のビュー(選べないものは含まれない)
     * @param min        最低選択数(0なら「選ばない」を許す)
     * @param max        最大選択数
     * @param prompt     案内文
     * @param queued     ★Batch 64: 待っている問い合わせの総数(この1件を含む)。
     *                   2以上なら「答えてもまだ次がある」ということである
     * @param sourceCardId ★Batch 70(指摘2): この問い合わせを出したカードのID。
     *                   クライアントは<b>「プレイ中のカード」の面</b>をここから描く。
     *                   ★分からないときは null(そのときは面が出ないだけである)。
     *                   ★スペルでもミニオンの【召喚時】でも同じ値が入る(マスター確認)——
     *                   「今どのカードを解決しているか」は種別で変わる性質ではない
     */
    public record PendingChoiceView(
            String kind,
            List<ChoiceCandidateView> candidates,
            int min,
            int max,
            String prompt,
            int queued,
            String sourceCardId) {

        /**
         * 候補1件のビュー。
         *
         * @param index    PendingChoice.candidates 内での位置(送信に使う0起点の番号)
         * @param label    画面に出す名前(カード名など)
         * @param keywords 補助表示用のキーワード
         * @param minionInstanceId kindがMINIONのときだけ入る、場のミニオンのインスタンスID。
         *                 クライアントが場のミニオンを直接クリックしたとき、どの候補に対応するかを
         *                 このIDで特定する(Batch 12b。風護の杖・回帰の風穴の2回目対象)
         */
        public record ChoiceCandidateView(int index, String label, List<String> keywords,
                String minionInstanceId) {
        }
    }
}
