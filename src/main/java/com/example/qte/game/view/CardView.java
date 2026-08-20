package com.example.qte.game.view;

import java.util.List;

import com.example.qte.master.CardMaster;
import com.example.qte.master.Keyword;

/**
 * 手札のカード1枚の表示情報。
 *
 * @param civilization      文明(英語名。LIGHT_CIVILIZATIONフィルタのハイライトにクライアントが使う)
 * @param cost              印刷コスト
 * @param effectiveCost     現在の実効コスト(動的コスト。双流の幻術師など)
 * @param targets           プレイ時に要求される対象選択(クライアントの選択UIがこれを読んで進行する)
 * @param canSpecialSummon  今この瞬間、特殊召喚の条件を満たしているか(サーバ判定)
 * @param specialTargets    特殊召喚時に要求される対象選択
 * @param specialSummonText 特殊召喚の確認ダイアログ用の説明文
 * @param combinedTotal     対象要求をまたいだ選択数の合計制約(0なら制約なし。サイクロン・リフレッシュ)
 * @param enhancedCost      追加コストによる強化使用の追加コスト(0なら強化使用なし。回帰の風穴・風弾の跳弾)
 * @param enhancedText      強化使用の確認ダイアログ用の説明文(強化使用がなければnull)
 * @param effectUnimplemented ★Batch 47。効果の文があるのにエンジンが処理を持っていないカード。
 *                          使ってもテキストどおりには動かないことを盤面に印として出す。
 *                          判定の正はサーバ({@link com.example.qte.effect.EffectImplementation})であり、
 *                          クライアントは同じ判定を持たない
 * @param evolutionMaterialIds ★Batch 52。今この瞬間、このカードの進化素材にできる
 *                          自分の場のミニオンの instanceId(進化ミニオン以外は空)。
 *                          ★<b>素材条件の判定をクライアントに持たせない</b>ための形である ——
 *                          18枚の条件は文明・キーワード・体力・進化かどうかの組み合わせで
 *                          10種類以上あり、{@link com.example.qte.effect.TargetSpec.Filter} に
 *                          足すと {@code battle.js} にも同じ数だけ {@code case} が要る(裁定195)。
 *                          サーバが候補そのものを送れば、規則は1箇所にしか存在しない(裁定163)
 * @param evolutionMin      必要な素材の最小数(進化ミニオン以外は0)
 * @param evolutionMax      素材の最大数。「1体以上」のカードは今の場のミニオン数まで
 * @param evolutionText     素材条件の説明(進化ミニオン以外はnull)
 */
public record CardView(
        String cardId,
        String name,
        String type,
        String civilization,
        Integer cost,
        Integer effectiveCost,
        Integer attack,
        Integer hp,
        List<String> keywords,
        String text,
        List<TargetReqView> targets,
        boolean canSpecialSummon,
        List<TargetReqView> specialTargets,
        String specialSummonText,
        int combinedTotal,
        int enhancedCost,
        String enhancedText,
        boolean effectUnimplemented,
        List<String> evolutionMaterialIds,
        int evolutionMin,
        int evolutionMax,
        String evolutionText) {

    public static List<String> keywordNames(CardMaster master) {
        return master.keywords().stream().map(Keyword::getDisplayName).toList();
    }

    /** 対象要求1件のクライアント向け表現 */
    public record TargetReqView(
            String kind,
            String side,
            int count,
            boolean optional,
            boolean upTo,
            List<String> filters,
            String prompt) {
    }
}
