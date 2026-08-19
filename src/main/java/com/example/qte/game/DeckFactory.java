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
        WATER_STARTER.put("QTE-M-WATER-2", 2); // アクア・ジェリー 1/1/1 知識
        WATER_STARTER.put("QTE-M-WATER-16", 2); // 急流の狙撃手 2/2/1 知識・貫通
        WATER_STARTER.put("QTE-M-WATER-3", 2); // 潮流の魔導士 2/2/2 守護・召喚時:条件回復
        WATER_STARTER.put("QTE-M-WATER-18", 3); // 波濤の突撃兵 3/3/1 突進・攻撃時1ドロー
        WATER_STARTER.put("QTE-M-WATER-17", 2); // 知識の守り手 3/1/1 知識・還元・守護
        WATER_STARTER.put("QTE-M-WATER-4", 2); // 手札を喰らう大蟹 3/3/2 守護・召喚時:手札1枚捨て+バウンス(両者)
        WATER_STARTER.put("QTE-M-WATER-6", 2); // ディープシー・シャーク 4/4/3 突進・威圧
        WATER_STARTER.put("QTE-M-WATER-5", 2); // 知識の守護者 4/0+/5 守護・攻撃力=手札枚数
        WATER_STARTER.put("QTE-M-WATER-19", 2); // 英知の継承者 4/2/2 知識・守護・召喚時:任意捨てドロー
        WATER_STARTER.put("QTE-M-WATER-7", 2); // 水鏡の幻術師 5/5/3 突進・守護・召喚時2ドロー
        WATER_STARTER.put("QTE-M-WATER-20", 1); // 黄泉還る水龍 5/4/4 突進・潜伏(墓地トリガーはBatch 4)
        WATER_STARTER.put("QTE-M-WATER-21", 1); // 双流の幻術師 7/3/2 知識・動的コスト・召喚時3体バウンス
        WATER_STARTER.put("QTE-M-WATER-22", 1); // 知恵の双翼 8/4/4 知識・守護・特殊召喚
        WATER_STARTER.put("QTE-M-WATER-8", 1); // 海皇 ポセイドン 8/6/5 特殊召喚
        WATER_STARTER.put("QTE-M-WATER-23", 1); // 智将 ポセイドン・コア 9/5/5 知識・守護・特殊召喚・突進付与
        WATER_STARTER.put("QTE-M-WATER-24", 1); // 深海神 プレサージュ 10/6/6 知識・特殊召喚
        // ウェポン3枚
        WATER_STARTER.put("QTE-M-WATER-13", 1); // 真珠の三叉槍 (3/⚔2) リーダー攻撃時1ドロー
        WATER_STARTER.put("QTE-M-WATER-14", 1); // 氷結の杖 (2/⚔1) 知識・攻撃対象を凍結
        WATER_STARTER.put("QTE-M-WATER-28", 1); // 影潜む水刺客 (1/⚔0+) 貫通・潜伏の数だけ攻撃+1
        // スペル10枚
        WATER_STARTER.put("QTE-M-WATER-25", 2); // アクア・サーチ (1) 2ドロー+1枚捨て
        WATER_STARTER.put("QTE-M-WATER-9", 2); // スプラッシュ・ドロー (2) 2ドロー
        WATER_STARTER.put("QTE-M-WATER-10", 2); // 恵みの雨 (2) 4回復+1ドロー
        WATER_STARTER.put("QTE-M-WATER-26", 1); // 静寂の瞑想 (2) 3ドロー+使用制限・メイン最初のみ
        WATER_STARTER.put("QTE-M-WATER-27", 1); // 流転の書 (2) 1ドロー・還元
        WATER_STARTER.put("QTE-M-WATER-12", 1); // 溢れ出る英知 (5) 2ドロー+水文明バフ
        WATER_STARTER.put("QTE-M-WATER-11", 1); // タイダルウェーブ (3) 相手コスト4以下全バウンス
    }

    /** 火文明スターターデッキ: ミニオン28枚+ウェポン3枚+スペル9枚 */
    private static final Map<String, Integer> FIRE_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン28枚
        FIRE_STARTER.put("QTE-M-FIRE-2", 3); // フレア・ポーン 1/2/1
        FIRE_STARTER.put("QTE-M-FIRE-16", 3); // 血誓のバーサーカー 1/2/2 召喚時:自傷1(+条件2)
        FIRE_STARTER.put("QTE-M-FIRE-4", 3); // 火炎の狂信者 2/2+/2 被ダメージのたび攻撃+2
        FIRE_STARTER.put("QTE-M-FIRE-3", 3); // ブラッドレイジの突撃兵 2/3/3 召喚時:自傷2
        FIRE_STARTER.put("QTE-M-FIRE-17", 2); // 逆境の猛火者 2/1/1 召喚時:条件で踏み倒し
        FIRE_STARTER.put("QTE-M-FIRE-18", 2); // 痛撃の炎術師 3/1/2 知識・召喚時:条件で自傷1
        FIRE_STARTER.put("QTE-M-FIRE-6", 3); // インフェルノ・ハウンド 4/4/1 速攻
        FIRE_STARTER.put("QTE-M-FIRE-5", 2); // 赫灼の重戦士 4/4/4 召喚時:条件で速攻
        FIRE_STARTER.put("QTE-M-FIRE-19", 2); // 相打ちの咎人 4/2/2 召喚時:相互2ダメージ
        FIRE_STARTER.put("QTE-M-FIRE-20", 1); // 覚醒の炎童 4/1/1 知識・特殊召喚
        FIRE_STARTER.put("QTE-M-FIRE-7", 1); // 背水の烈火使い 4/3/5 守護・召喚時:手札全捨て
        FIRE_STARTER.put("QTE-M-FIRE-21", 1); // 背水の炎壁 7/5/3 守護・特殊召喚
        FIRE_STARTER.put("QTE-M-FIRE-8", 1); // 極炎竜 ヴォルカニクス 7/6/2 速攻・特殊召喚
        FIRE_STARTER.put("QTE-M-FIRE-22", 1); // 鳳凰神 ヴォルカニクスレヴォ 13/3/8 速攻・特殊召喚
        // ウェポン3枚
        FIRE_STARTER.put("QTE-M-FIRE-13", 1); // フレム・ダガー (1/⚔1) 知識
        FIRE_STARTER.put("QTE-M-FIRE-14", 1); // 魔剣 レーヴァテイン (3/⚔5) 攻撃時:自傷3
        FIRE_STARTER.put("QTE-M-FIRE-28", 1); // 反転の炎鏡 (3/⚔1) 自傷を水増しする
        // スペル9枚
        FIRE_STARTER.put("QTE-M-FIRE-9", 2); // イグニッション・バースト (1) 自傷2+2ドロー
        FIRE_STARTER.put("QTE-M-FIRE-23", 1); // フレイム・スナイプ (1) 守護HP5以下を破壊
        FIRE_STARTER.put("QTE-M-FIRE-10", 2); // マグマ・ストレート (2) ミニオン1体に3ダメージ
        FIRE_STARTER.put("QTE-M-FIRE-12", 1); // 捨て身の猛進 (3) 全体+1攻撃と突進
        FIRE_STARTER.put("QTE-M-FIRE-11", 1); // 命を削る烈火 (3) 自傷3+相手全体2ダメージ
        FIRE_STARTER.put("QTE-M-FIRE-27", 1); // 命喰いの火種 (2) 自傷3+2ドロー・還元
        FIRE_STARTER.put("QTE-M-FIRE-26", 1); // 再起の炎陣 (3) 1捨て1ドロー・還元
    }

    /** 闇スターターデッキ: ミニオン28枚+ウェポン3枚+スペル9枚 */
    private static final Map<String, Integer> DARK_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン28枚
        DARK_STARTER.put("QTE-M-DARK-2", 4); // カース・ボーン 1/2/1 召喚時:マナを裏向きに(できねば自壊)
        // ★Batch 46b: 5枚 → 4枚。Ver1.1 の本文から「このカードは4枚以上入れられる」が消え、
        //   同名4枚の例外そのものが無くなったため。減らした1枚は腐敗の投擲者に振り替えている
        //   (ミニオン28枚・スペル9枚という構成は保つ)。
        DARK_STARTER.put("QTE-M-DARK-16", 4); // ゾンストライカー 1/1/1 突進・墓地から全て展開
        DARK_STARTER.put("QTE-M-DARK-17", 4); // 腐敗の投擲者 2/2/1 召喚時:相手ミニオンに1ダメージ
        DARK_STARTER.put("QTE-M-DARK-3", 3); // 生贄を求める邪鬼 2/3/2 召喚時:生贄か自壊
        DARK_STARTER.put("QTE-M-DARK-20", 3); // 執念の暗殺者 4/3/3 召喚時3ダメージ・破壊のたびドロー
        DARK_STARTER.put("QTE-M-DARK-6", 2); // ボーン・コレクター 4/4/2 突進・戦闘破壊で1ドロー
        DARK_STARTER.put("QTE-M-DARK-5", 2); // 不滅のネクロマンサー 4/3/3 裏向きマナで蘇生
        DARK_STARTER.put("QTE-M-DARK-19", 1); // 死の知識人 3/0/3 守護・知識・還元
        DARK_STARTER.put("QTE-M-DARK-7", 1); // 這い寄る生霊 5/1/1 特殊召喚・知識
        DARK_STARTER.put("QTE-M-DARK-4", 1); // 裏切りの魔女 3/2/3 召喚時:条件付き除去
        DARK_STARTER.put("QTE-M-DARK-18", 1); // 封印されし禁忌魔人 2+/5/5 守護・踏み倒し不可
        DARK_STARTER.put("QTE-M-DARK-21", 1); // 群がる死霊王 6-/7/3 墓地のゾンストライカーで軽減
        DARK_STARTER.put("QTE-M-DARK-8", 1); // 冥界神ハデス 8/7/7 召喚時:全体破壊+蘇生
        // ウェポン3枚
        DARK_STARTER.put("QTE-M-DARK-13", 1); // 死神の大鎌 (2/⚔0) 攻撃対象を無条件破壊
        DARK_STARTER.put("QTE-M-DARK-28", 1); // 死霊の収鎌 (2/⚔1) 攻撃時:墓地から1枚回収
        DARK_STARTER.put("QTE-M-DARK-14", 1); // 禁忌の冥魔剣 (4/⚔1) 裏向きマナを表に+1ダメージ
        // スペル9枚
        DARK_STARTER.put("QTE-M-DARK-9", 2); // 絶望の連鎖 (1) 相互1体破壊
        DARK_STARTER.put("QTE-M-DARK-11", 2); // マナを貪る怨霊 (2) マナ2枚裏向き+3ドロー
        DARK_STARTER.put("QTE-M-DARK-10", 1); // 禁忌の代償 (2) 裏向きマナ1枚破壊+確定除去
        DARK_STARTER.put("QTE-M-DARK-24", 1); // 墓穴の呪い (3) 3枚ミル+HP条件の全体破壊
        DARK_STARTER.put("QTE-M-DARK-26", 1); // 冥府への道 (5) 確定除去
        DARK_STARTER.put("QTE-M-DARK-25", 1); // 禁忌の墓地利用 (5) 墓地のスペルを裏向きマナへ
        DARK_STARTER.put("QTE-M-DARK-12", 1); // 死者蘇生 (7-) 生贄で軽減+突進付き蘇生
    }

    /** 光スターターデッキ: ミニオン28枚+ウェポン3枚+スペル9枚 */
    private static final Map<String, Integer> LIGHT_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン28枚
        LIGHT_STARTER.put("QTE-M-LIGHT-16", 4); // 煌めきの盾 1/2/2 守護・攻撃不可
        LIGHT_STARTER.put("QTE-M-LIGHT-2", 3); // ライト・シールド 2/1/3 守護
        LIGHT_STARTER.put("QTE-M-LIGHT-17", 3); // 聖域の司祭 2/2/2 知識
        LIGHT_STARTER.put("QTE-M-LIGHT-3", 2); // 聖域の案内人 3/2/2 知識・条件で知識2回目
        LIGHT_STARTER.put("QTE-M-LIGHT-18", 2); // 唱導の聖騎士 3/2/2 還元・自分のスペルコスト-1
        LIGHT_STARTER.put("QTE-M-LIGHT-19", 2); // 英知の水晶 3/0/2 自分の知識カードコスト-1
        LIGHT_STARTER.put("QTE-M-LIGHT-20", 1); // 戒律のガーディアン 4/1/1 守護・スペル/守護コスト-1
        LIGHT_STARTER.put("QTE-M-LIGHT-4", 2); // 秩序の執行官 4/3/4 相手の特殊召喚を封じる
        LIGHT_STARTER.put("QTE-M-LIGHT-21", 1); // 光の召喚士 5/3/3 守護・召喚時:コスト3以下を1体踏み倒し
        LIGHT_STARTER.put("QTE-M-LIGHT-5", 1); // ホーリー・ガーディアン 5/3/6 守護・潜伏
        LIGHT_STARTER.put("QTE-M-LIGHT-22", 1); // 降臨の伝道師 6/1/1 守護・召喚時:山札4枚から守護を1体展開
        LIGHT_STARTER.put("QTE-M-LIGHT-6", 1); // 戒律の聖堂騎士 6/5/5 守護・相手のサブフェイズを封じる
        LIGHT_STARTER.put("QTE-M-LIGHT-7", 1); // 大天使ミカエル 7/4/8 守護・戦闘では破壊されない
        LIGHT_STARTER.put("QTE-M-LIGHT-23", 1); // 平和の結界 7/3/5 守護・Attack3以上は攻撃不可
        LIGHT_STARTER.put("QTE-M-LIGHT-8", 1); // 天界の守護神 ゾディアック 9/7/9 守護・相手リーダー攻撃不可
        LIGHT_STARTER.put("QTE-M-LIGHT-24", 1); // 断罪の大天使 10/8/8 守護・相手の3枚目以降のドローを置換
        LIGHT_STARTER.put("QTE-M-LIGHT-25", 1); // 創世神 ゾディアックアイリス 11/11/11 守護・潜伏・知識・リーダー攻撃不可
        // ウェポン3枚
        LIGHT_STARTER.put("QTE-M-LIGHT-13", 1); // 正義の御盾 (1/⚔0) リーダーへの被ダメージ-1
        LIGHT_STARTER.put("QTE-M-LIGHT-28", 1); // 詠唱の宝珠 (1/⚔1) 破壊時:次のスペルコスト-1
        LIGHT_STARTER.put("QTE-M-LIGHT-14", 1); // 聖剣エクスカリバー (4/⚔3) リーダー攻撃時:守護全体2回復
        // スペル9枚
        LIGHT_STARTER.put("QTE-M-LIGHT-9", 2); // 光の戒め (2) 凍結+1ドロー
        LIGHT_STARTER.put("QTE-M-LIGHT-10", 2); // ホーリー・シグナル (3) 相手の最高攻撃力を破壊
        LIGHT_STARTER.put("QTE-M-LIGHT-26", 1); // 聖光の武装解除 (3) ウェポン破壊・還元
        LIGHT_STARTER.put("QTE-M-LIGHT-27", 1); // 運命のリセット (4) 両者手札をシャッフルして引き直し
        LIGHT_STARTER.put("QTE-M-LIGHT-11", 2); // 聖なる降誕の儀式 (5) コスト7以下の守護を1体踏み倒し
        LIGHT_STARTER.put("QTE-M-LIGHT-12", 1); // 神の福音 (6) 光の守護を2体まで踏み倒し+その数だけドロー
    }

    /** 風スターターデッキ: ミニオン23枚+ウェポン4枚+スペル13枚 */
    private static final Map<String, Integer> WIND_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン23枚
        WIND_STARTER.put("QTE-M-WIND-2", 3); // ウィンド・ペティ 1/1/1 知識
        WIND_STARTER.put("QTE-M-WIND-16", 3); // 疾風の先陣 1/1/1 守護・突進
        WIND_STARTER.put("QTE-M-WIND-3", 3); // スカイ・スワロー 1/1/1 速攻
        WIND_STARTER.put("QTE-M-WIND-5", 2); // サイクロン・フェンサー 2/1/1 1ターンに2回攻撃
        WIND_STARTER.put("QTE-M-WIND-4", 2); // 嵐の呼び手 2/2/2 召喚時:条件でバウンス
        WIND_STARTER.put("QTE-M-WIND-6", 2); // ガイル・フォックス 3/2/2 召喚時:条件で潜伏
        WIND_STARTER.put("QTE-M-WIND-7", 2); // 突風のまとめ役 3/1/3 使用のたび全体+1(このターン)
        WIND_STARTER.put("QTE-M-WIND-17", 1); // 静空の風使い 4/1/1 潜伏・守護・タップでマナ加速
        WIND_STARTER.put("QTE-M-WIND-8", 1); // ストーム・カイザー 5/3/4 速攻・特殊召喚
        WIND_STARTER.put("QTE-M-WIND-18", 1); // 詠唱の疾風騎士 6/3/3 突進・スペルで軽減
        WIND_STARTER.put("QTE-M-WIND-19", 1); // 嵐の守り手 7/1/4 守護・特殊召喚
        WIND_STARTER.put("QTE-M-WIND-20", 1); // 結集する風の精 8/4/4 知識・還元・動的コスト軽減
        WIND_STARTER.put("QTE-M-WIND-21", 1); // 風神ヴァーユ 9/5/5 守護・特殊召喚
        // ウェポン4枚
        WIND_STARTER.put("QTE-M-WIND-13", 2); // 暴風の双剣 (2/⚔0+) 使用のたび攻撃+1(このターン)
        WIND_STARTER.put("QTE-M-WIND-14", 1); // 疾風のレイピア (3/⚔2) 1ターンに2回攻撃
        WIND_STARTER.put("QTE-M-WIND-28", 1); // 風護の杖 (3/⚔1) 知識・攻撃時:味方に体力+1と守護
        // スペル13枚
        WIND_STARTER.put("QTE-M-WIND-10", 2); // そよ風の加護 (1) 体力+1・守護付与
        WIND_STARTER.put("QTE-M-WIND-9", 2); // 追い風 (1) 攻撃力+1・1ドロー
        WIND_STARTER.put("QTE-M-WIND-24", 1); // 風弾の跳弾 (1) バウンス+2ダメージ・強化使用可
        WIND_STARTER.put("QTE-M-WIND-23", 1); // 風のマナ変換 (1) 表向き→裏向きマナ入れ替え
        WIND_STARTER.put("QTE-M-WIND-22", 1); // サイクロン・リフレッシュ (1) 2枚デッキ戻し+2ドロー
        WIND_STARTER.put("QTE-M-WIND-25", 2); // 選択の追い風 (2) 1ドロー+任意で守護を捨てて追加ドロー
        WIND_STARTER.put("QTE-M-WIND-11", 1); // ツイン・ストライク (2) 1体戦闘攻撃を2回に
        WIND_STARTER.put("QTE-M-WIND-26", 1); // 回帰の風穴 (2) バウンス・強化使用で再詠唱
        WIND_STARTER.put("QTE-M-WIND-27", 1); // 突風の祝福 (3) 体力+2・還元
        WIND_STARTER.put("QTE-M-WIND-12", 1); // 神風の大号令 (4) 使用枚数分の全体攻撃力+
    }

    /** 土スターターデッキ(Batch 13b版=土文明完成): ミニオン28枚+ウェポン3枚+スペル9枚 */
    private static final Map<String, Integer> EARTH_STARTER = new LinkedHashMap<>();
    static {
        // ミニオン(28枚)
        EARTH_STARTER.put("QTE-M-EARTH-16", 3); // 苗木植えの精霊 2/1/1 召喚時:手札1枚をマナ加速
        EARTH_STARTER.put("QTE-M-EARTH-17", 3); // 地砕きの突撃兵 3/3/1 突進・攻撃時マナ回収/破壊時マナ加速
        EARTH_STARTER.put("QTE-M-EARTH-2", 2); // ゴーレム・ウォール 3/1/5 守護
        EARTH_STARTER.put("QTE-M-EARTH-18", 3); // 大地の狂戦士 4/3/1 突進・動的コスト(マナ7+で1)
        EARTH_STARTER.put("QTE-M-EARTH-3", 3); // 大地の精霊グラン 5/4/4 召喚時:山札からマナ加速
        EARTH_STARTER.put("QTE-M-EARTH-4", 2); // アースクエイクジャイアント 6/6/3 召喚時:相手守護を全破壊
        EARTH_STARTER.put("QTE-M-EARTH-19", 2); // 連撃の巨岩 6/3/8 突進・2回攻撃・エンド時バウンス
        EARTH_STARTER.put("QTE-M-EARTH-5", 2); // 不動の岩石竜 7/6/8 守護・潜伏
        EARTH_STARTER.put("QTE-M-EARTH-6", 1); // タイタン・ウォリアー 8/8/8 突進・戦闘破壊で相手リーダー4ダメージ
        EARTH_STARTER.put("QTE-M-EARTH-7", 1); // 百獣の王ベヒーモス 9/10/10 バニラ
        EARTH_STARTER.put("QTE-M-EARTH-8", 1); // 創世神ガイア 9/6/7 特殊召喚・召喚時:自身以外全破壊
        EARTH_STARTER.put("QTE-M-EARTH-21", 1); // 天変地異のタイタン 10/7/7 召喚時:相手全体7ダメージ+2ドロー
        EARTH_STARTER.put("QTE-M-EARTH-20", 1); // 安らぎのガーディアン 10/4/4 守護・召喚時2回復/エンド時4回復
        EARTH_STARTER.put("QTE-M-EARTH-22", 1); // 無尽蔵の巨神 12/0+/8 速攻・攻撃力=手札枚数
        EARTH_STARTER.put("QTE-M-EARTH-23", 1); // 不動の絶対神ガイア 14/10/10 潜伏・攻撃時リーダー4ダメージ
        EARTH_STARTER.put("QTE-M-EARTH-24", 1); // 疾風怒濤のベヒーモス 15/6/8 速攻・エンド時バウンス

        // ウェポン(3枚)
        EARTH_STARTER.put("QTE-M-EARTH-13", 1); // 大地の守護盾 (2/⚔0) リーダーへの攻撃を肩代わり
        EARTH_STARTER.put("QTE-M-EARTH-14", 1); // ガイア・ハンマー (4/⚔4) 装備時:山札からマナ加速
        EARTH_STARTER.put("QTE-M-EARTH-28", 1); // 地響きの槌 (7/⚔2) 攻撃時:相手ミニオン全体2ダメージ

        // スペル(9枚)
        EARTH_STARTER.put("QTE-M-EARTH-25", 2); // 大地の開眼 (1) 1ドロー(マナ7+でさらに1)
        EARTH_STARTER.put("QTE-M-EARTH-9", 2); // 大地の恵み (3) 山札からマナ加速
        EARTH_STARTER.put("QTE-M-EARTH-10", 1); // 落石の罠 (3) 相手ミニオン1体に5ダメージ
        EARTH_STARTER.put("QTE-M-EARTH-26", 1); // ガイア・リソース (4) 山札からマナ加速・還元
        EARTH_STARTER.put("QTE-M-EARTH-11", 1); // 大地震 (4) お互いのコスト3以下を全破壊
        EARTH_STARTER.put("QTE-M-EARTH-27", 1); // 地脈の覚醒 (5) 動的コスト・還元(マナ加速本体)
        EARTH_STARTER.put("QTE-M-EARTH-12", 1); // 豊穣の祈り (5) 山札からマナ加速+2ドロー
    }

    private final CardMasterRepository cardMasterRepository;

    public DeckFactory(CardMasterRepository cardMasterRepository) {
        this.cardMasterRepository = cardMasterRepository;
        validate(WATER_STARTER);
        validate(FIRE_STARTER);
        validate(DARK_STARTER);
        validate(LIGHT_STARTER);
        validate(WIND_STARTER);
        validate(EARTH_STARTER);
    }

    /**
     * 禁忌デッキ(総合ルール1-3): リーダーと異なる文明のカード8枚、同名は1枚まで(ハイランダー)。
     * 現在は水リーダー用の火文明セットのみ用意している。
     * 選定基準は「Batch 7で効果を実装済みの火カード」であり、火文明の全面実装はBatch 8。
     */
    private static final List<String> FIRE_TABOO = List.of(
            "QTE-M-FIRE-2", // フレア・ポーン 1/2/1
            "QTE-M-FIRE-16", // 血誓のバーサーカー 1/2/2 召喚時:自傷1(+条件で2)
            "QTE-M-FIRE-9", // イグニッション・バースト (1) 自傷2+2ドロー
            "QTE-M-FIRE-3", // ブラッドレイジの突撃兵 2/3/3 召喚時:自傷2
            "QTE-M-FIRE-10", // マグマ・ストレート (2) ミニオン1体に3ダメージ
            "QTE-M-FIRE-14", // 魔剣 レーヴァテイン (3/⚔5) リーダー攻撃時:自傷3
            "QTE-M-FIRE-6", // インフェルノ・ハウンド 4/4/1 速攻
            "QTE-M-FIRE-5"  // 赫灼の重戦士 4/4/4 召喚時:条件で速攻
    );

    /** 水リーダー用の禁忌デッキ(火リーダーが使う。1-3: リーダーと異なる文明) */
    private static final List<String> WATER_TABOO = List.of(
            "QTE-M-WATER-25", // アクア・サーチ (1) 2ドロー+1枚捨て
            "QTE-M-WATER-2", // アクア・ジェリー 1/1/1 知識
            "QTE-M-WATER-9", // スプラッシュ・ドロー (2) 2ドロー
            "QTE-M-WATER-10", // 恵みの雨 (2) 4回復+1ドロー
            "QTE-M-WATER-3", // 潮流の魔導士 2/2/2 守護・召喚時:条件回復
            "QTE-M-WATER-14", // 氷結の杖 (2/⚔1) 知識・凍結
            "QTE-M-WATER-18", // 波濤の突撃兵 3/3/1 突進・攻撃時1ドロー
            "QTE-M-WATER-6"  // ディープシー・シャーク 4/4/3 突進・威圧
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
            case DARK -> DARK_STARTER;
            case LIGHT -> LIGHT_STARTER;
            case WIND -> WIND_STARTER;
            case EARTH -> EARTH_STARTER;
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
            // ★Batch 46b: 同名4枚の例外(ゾンストライカー)を撤廃した。
            // Ver1.1 の本文から「このカードは4枚以上入れられる」が消えており、
            // 例外を持つカードは235枚中0枚である。DeckValidator 側の例外表も同時に消した
            // —— 同じ規則が2箇所にあると、必ず片方だけが直される日が来る(裁定130)。
            if (count > 4) {
                throw new IllegalStateException("同名カードは4枚まで: " + cardId + " x" + count);
            }
        });
    }
}
