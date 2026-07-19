package com.example.qte.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.qte.deck.DeckDefinition;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Civilization;

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

    /** 火文明スターターデッキ: ミニオン28枚+ウェポン3枚+スペル9枚 */
    private static final Map<String, Integer> FIRE_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン28枚
        FIRE_STARTER.put("QTE-0047", 3); // フレア・ポーン 1/2/1
        FIRE_STARTER.put("QTE-0044", 3); // 血誓のバーサーカー 1/2/2 召喚時:自傷1(+条件2)
        FIRE_STARTER.put("QTE-0045", 3); // 火炎の狂信者 2/2+/2 被ダメージのたび攻撃+2
        FIRE_STARTER.put("QTE-0055", 3); // ブラッドレイジの突撃兵 2/3/3 召喚時:自傷2
        FIRE_STARTER.put("QTE-0066", 2); // 逆境の猛火者 2/1/1 召喚時:条件で踏み倒し
        FIRE_STARTER.put("QTE-0049", 2); // 痛撃の炎術師 3/1/2 知識・召喚時:条件で自傷1
        FIRE_STARTER.put("QTE-0001", 3); // インフェルノ・ハウンド 4/4/1 速攻
        FIRE_STARTER.put("QTE-0050", 2); // 赫灼の重戦士 4/4/4 召喚時:条件で速攻
        FIRE_STARTER.put("QTE-0059", 2); // 相打ちの咎人 4/2/2 召喚時:相互2ダメージ
        FIRE_STARTER.put("QTE-0062", 1); // 覚醒の炎童 4/1/1 知識・特殊召喚
        FIRE_STARTER.put("QTE-0063", 1); // 背水の烈火使い 4/8/8 召喚時:手札全捨て
        FIRE_STARTER.put("QTE-0057", 1); // 背水の炎壁 6/5/3 守護・特殊召喚
        FIRE_STARTER.put("QTE-0051", 1); // 極炎竜 ヴォルカニクス 7/6/2 速攻・特殊召喚
        FIRE_STARTER.put("QTE-0058", 1); // 鳳凰神 ヴォルカニクスレヴォ 13/3/8 速攻・特殊召喚
        // ウェポン3枚
        FIRE_STARTER.put("QTE-0067", 1); // フレム・ダガー (1/⚔1) 知識
        FIRE_STARTER.put("QTE-0061", 1); // 魔剣 レーヴァテイン (3/⚔5) 攻撃時:自傷3
        FIRE_STARTER.put("QTE-0048", 1); // 反転の炎鏡 (3/⚔1) 自傷を水増しする
        // スペル9枚
        FIRE_STARTER.put("QTE-0064", 2); // イグニッション・バースト (1) 自傷1+2ドロー
        FIRE_STARTER.put("QTE-0054", 1); // フレイム・スナイプ (1) 守護HP5以下を破壊
        FIRE_STARTER.put("QTE-0046", 2); // マグマ・ストレート (2) ミニオン1体に3ダメージ
        FIRE_STARTER.put("QTE-0053", 1); // 捨て身の猛進 (3) 全体+1攻撃と突進
        FIRE_STARTER.put("QTE-0056", 1); // 命を削る烈火 (3) 自傷3+相手全体2ダメージ
        FIRE_STARTER.put("QTE-0060", 1); // 命喰いの火種 (3) 自傷3+2ドロー・還元
        FIRE_STARTER.put("QTE-0052", 1); // 再起の炎陣 (3) 1捨て1ドロー・還元
    }

    private final CardMasterRepository cardMasterRepository;

    public DeckFactory(CardMasterRepository cardMasterRepository) {
        this.cardMasterRepository = cardMasterRepository;
        validate(WATER_STARTER);
        validate(FIRE_STARTER);
    }

    /**
     * 禁忌デッキ(総合ルール1-3): リーダーと異なる文明のカード8枚、同名は1枚まで(ハイランダー)。
     * 現在は水リーダー用の火文明セットのみ用意している。
     * 選定基準は「Batch 7で効果を実装済みの火カード」であり、火文明の全面実装はBatch 8。
     */
    private static final List<String> FIRE_TABOO = List.of(
            "QTE-0047", // フレア・ポーン 1/2/1
            "QTE-0044", // 血誓のバーサーカー 1/2/2 召喚時:自傷1(+条件で2)
            "QTE-0064", // イグニッション・バースト (1) 自傷1+2ドロー
            "QTE-0055", // ブラッドレイジの突撃兵 2/3/3 召喚時:自傷2
            "QTE-0046", // マグマ・ストレート (2) ミニオン1体に3ダメージ
            "QTE-0061", // 魔剣 レーヴァテイン (3/⚔5) リーダー攻撃時:自傷3
            "QTE-0001", // インフェルノ・ハウンド 4/4/1 速攻
            "QTE-0050"  // 赫灼の重戦士 4/4/4 召喚時:条件で速攻
    );

    /** 水リーダー用の禁忌デッキ(火リーダーが使う。1-3: リーダーと異なる文明) */
    private static final List<String> WATER_TABOO = List.of(
            "QTE-0028", // アクア・サーチ (1) 1ドロー
            "QTE-0026", // アクア・ジェリー 1/1/1 知識
            "QTE-0025", // スプラッシュ・ドロー (2) 2ドロー
            "QTE-0002", // 恵みの雨 (2) 4回復+1ドロー
            "QTE-0029", // 潮流の魔導士 2/2/2 召喚時:条件回復
            "QTE-0031", // 氷結の杖 (2/⚔1) 凍結
            "QTE-0003", // 波濤の突撃兵 3/3/1 突進・攻撃時1ドロー
            "QTE-0011"  // ディープシー・シャーク 4/4/3 突進・威圧
    );

    /**
     * デッキファイル(検証済み)からメインデッキを生成する。
     * 検証はDeckValidatorが済ませている前提で、ここでは並べてシャッフルするだけ。
     */
    public List<String> createMainDeckFrom(DeckDefinition deck) {
        List<String> list = new ArrayList<>(40);
        deck.main().forEach(e -> {
            for (int i = 0; i < e.count(); i++) {
                list.add(e.cardId());
            }
        });
        Collections.shuffle(list);
        return list;
    }

    /** デッキファイルの禁忌デッキ(順序は保持する。所有者が並べた順に表示される) */
    public List<String> createTabooDeckFrom(DeckDefinition deck) {
        return new ArrayList<>(deck.taboo());
    }

    /** リーダーの文明に対応するプリセットのメインデッキ(1-2: リーダーと同一文明のみ) */
    public List<String> createMainDeck(CardMaster leader) {
        Map<String, Integer> definition = switch (leader.civilization()) {
            case WATER -> WATER_STARTER;
            case FIRE -> FIRE_STARTER;
            default -> throw new IllegalStateException(
                    leader.civilization().getDisplayName() + "文明のメインデッキは未実装です");
        };
        return buildShuffled(definition);
    }

    /** リーダーの文明と異なる文明の禁忌デッキ(1-3) */
    public List<String> createTabooDeck(CardMaster leader) {
        List<String> taboo = new ArrayList<>(
                leader.civilization() == Civilization.FIRE ? WATER_TABOO : FIRE_TABOO);
        validateTaboo(leader, taboo);
        return taboo;
    }

    /** 禁忌デッキの検証。8枚・リーダーと異なる文明・同名1枚まで(1-3, 1-3-1) */
    private void validateTaboo(CardMaster leader, List<String> taboo) {
        if (taboo.size() != 8) {
            throw new IllegalStateException("禁忌デッキは8枚である必要があります: " + taboo.size());
        }
        if (taboo.size() != Set.copyOf(taboo).size()) {
            throw new IllegalStateException("禁忌デッキに同名カードを複数入れることはできません");
        }
        for (String cardId : taboo) {
            CardMaster card = cardMasterRepository.findById(cardId);
            if (card.civilization() == leader.civilization()) {
                throw new IllegalStateException(
                        "禁忌デッキにはリーダーと異なる文明のカードしか入れられません: " + card.name());
            }
        }
    }

    /** シャッフル済みの水スターターデッキ(カードIDのリスト)を生成する */
    public List<String> createWaterStarterDeck() {
        return buildShuffled(WATER_STARTER);
    }

    private List<String> buildShuffled(Map<String, Integer> definition) {
        List<String> deck = new ArrayList<>(40);
        definition.forEach((cardId, count) -> {
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
