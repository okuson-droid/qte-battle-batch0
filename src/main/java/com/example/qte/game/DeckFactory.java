package com.example.qte.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.qte.master.CardMasterRepository;

/**
 * 構築済みデッキの生成。デッキビルダーは作らない方針(仕様1-2)のため、
 * デッキはここにコードで定義する。
 *
 * 水スターターデッキ(Batch 4版=水文明完成): ミニオン27枚+ウェポン3枚+スペル10枚。
 * 水文明の全カードタイプが解禁済み。
 */
@Component
public class DeckFactory {

    /** カードID → 投入枚数。合計40枚・同名4枚以内(総合ルール1章) */
    private static final Map<String, Integer> WATER_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン30枚
        WATER_STARTER.put("QTE-0026", 2); // アクア・ジェリー 1/1/1 知識
        WATER_STARTER.put("QTE-0039", 2); // 急流の狙撃手 2/2/1 知識・貫通
        WATER_STARTER.put("QTE-0029", 2); // 潮流の魔導士 2/2/2 召喚時:条件回復
        WATER_STARTER.put("QTE-0003", 3); // 波濤の突撃兵 3/3/1 突進・攻撃時1ドロー
        WATER_STARTER.put("QTE-0027", 2); // 知識の守り手 3/1/1 知識・還元・守護
        WATER_STARTER.put("QTE-0034", 2); // 手札を喰らう大蟹 3/3/2 召喚時:手札1枚捨て+バウンス
        WATER_STARTER.put("QTE-0011", 2); // ディープシー・シャーク 4/4/3 突進・威圧
        WATER_STARTER.put("QTE-0037", 2); // 知識の守護者 4/0+/5 守護・攻撃力=手札枚数
        WATER_STARTER.put("QTE-0021", 2); // 英知の継承者 4/2/2 知識・召喚時:任意捨てドロー
        WATER_STARTER.put("QTE-0042", 2); // 水鏡の幻術師 5/5/3 召喚時2ドロー
        WATER_STARTER.put("QTE-0040", 1); // 黄泉還る水龍 5/4/4 突進・潜伏(墓地トリガーはBatch 4)
        WATER_STARTER.put("QTE-0041", 1); // 双流の幻術師 7/3/2 知識・動的コスト・召喚時2体バウンス
        WATER_STARTER.put("QTE-0032", 1); // 知恵の双翼 8/3/3 知識・特殊召喚
        WATER_STARTER.put("QTE-0038", 1); // 海皇 ポセイドン 8/6/5 特殊召喚
        WATER_STARTER.put("QTE-0035", 1); // 智将 ポセイドン・コア 9/5/5 知識・特殊召喚・突進付与
        WATER_STARTER.put("QTE-0020", 1); // 深海神 プレサージュ 10/6/6 知識・特殊召喚
        // ウェポン3枚
        WATER_STARTER.put("QTE-0030", 1); // 真珠の三叉槍 (3/⚔2) リーダー攻撃時1ドロー
        WATER_STARTER.put("QTE-0031", 1); // 氷結の杖 (2/⚔1) 攻撃対象を凍結
        WATER_STARTER.put("QTE-0022", 1); // 影潜む水刺客 (1/⚔0+) 貫通・潜伏の数だけ攻撃+1
        // スペル10枚
        WATER_STARTER.put("QTE-0028", 2); // アクア・サーチ (1) 1ドロー
        WATER_STARTER.put("QTE-0025", 2); // スプラッシュ・ドロー (2) 2ドロー
        WATER_STARTER.put("QTE-0002", 2); // 恵みの雨 (2) 4回復+1ドロー
        WATER_STARTER.put("QTE-0033", 1); // 静寂の瞑想 (1) 3ドロー+使用制限
        WATER_STARTER.put("QTE-0036", 1); // 流転の書 (2) 1ドロー・還元
        WATER_STARTER.put("QTE-0024", 1); // 溢れ出る英知 (5) 3ドロー+全体バフ
        WATER_STARTER.put("QTE-0023", 1); // タイダルウェーブ (4) 相手コスト4以下全バウンス
    }

    private final CardMasterRepository cardMasterRepository;

    public DeckFactory(CardMasterRepository cardMasterRepository) {
        this.cardMasterRepository = cardMasterRepository;
        validate(WATER_STARTER);
    }

    /** シャッフル済みの水スターターデッキ(カードIDのリスト)を生成する */
    public List<String> createWaterStarterDeck() {
        List<String> deck = new ArrayList<>(40);
        WATER_STARTER.forEach((cardId, count) -> {
            for (int i = 0; i < count; i++) {
                deck.add(cardId);
            }
        });
        Collections.shuffle(deck);
        return deck;
    }

    /** デッキ定義の検証。不正ならアプリを起動させない(起動時に落とす方針) */
    private void validate(Map<String, Integer> deckDefinition) {
        int total = deckDefinition.values().stream().mapToInt(Integer::intValue).sum();
        if (total != 40) {
            throw new IllegalStateException("デッキは40枚である必要があります: " + total + "枚");
        }
        deckDefinition.forEach((cardId, count) -> {
            cardMasterRepository.findById(cardId); // 存在チェック(なければ例外)
            if (count > 4) {
                // ゾンストライカーのようなテキストによる上書きは、該当カード実装時に対応する
                throw new IllegalStateException("同名カードは4枚まで: " + cardId + " x" + count);
            }
        });
    }
}
