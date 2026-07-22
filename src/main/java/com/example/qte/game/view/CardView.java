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
        String enhancedText) {

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
