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
 * <h2>★Batch 60: 6文明のプリセットを Ver1.1 化した</h2>
 *
 * 46b で Ver1.1(235枚)へ移行したあとも、プリセットの中身は Ver0.4 の169枚のままだった ——
 * <b>新カード66枚が1枚も入っていなかった</b>。効果の実装が追いついていなかったからである
 * (進化は Batch 52 で解禁、最後の16枚は Batch 59 で完了)。
 * P5 が終わったので、6文明とも<b>その文明の新カード10枚を全種類</b>入れてある。
 *
 * <ul>
 * <li>合計40枚・同名4枚以内は不変。新カードを入れたぶんは、
 *     既存カードの<b>枚数を削って</b>作った(何が減ったかは各行の注記を参照)。</li>
 * <li>各デッキに<b>進化ミニオン3種を1枚ずつ</b>入れた。素材はどれもその文明の
 *     普通のミニオンで足りる(裁定E1・裁定154)。</li>
 * <li>リーダーは18枚とも<b>デッキの外</b>にあるので、ここには現れない。</li>
 * </ul>
 *
 * <p>★<b>これは「強いデッキ」ではなく「全カードに触れるデッキ」である。</b>
 * プリセットの役目は、遊びはじめた人がその文明の道具を一通り見られることであり、
 * 勝率を詰めることではない。バランスの調整は別の作業である。
 */
@Component
public class DeckFactory {

    /** カードID → 投入枚数。合計40枚・同名4枚以内(総合ルール1章) */
    private static final Map<String, Integer> WATER_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン(Ver0.4 からの20枚)
        WATER_STARTER.put("QTE-M-WATER-2", 1); // アクア・ジェリー 1/1/1 知識
        WATER_STARTER.put("QTE-M-WATER-16", 1); // 急流の狙撃手 2/2/1 知識・貫通
        WATER_STARTER.put("QTE-M-WATER-3", 1); // 潮流の魔導士 2/2/2 守護・召喚時:条件回復
        WATER_STARTER.put("QTE-M-WATER-18", 2); // 波濤の突撃兵 3/3/1 突進・攻撃時1ドロー
        WATER_STARTER.put("QTE-M-WATER-17", 1); // 知識の守り手 3/1/1 知識・還元・守護
        WATER_STARTER.put("QTE-M-WATER-4", 1); // 手札を喰らう大蟹 3/3/2 守護・召喚時:手札1枚捨て+バウンス(両者)
        WATER_STARTER.put("QTE-M-WATER-6", 1); // ディープシー・シャーク 4/4/3 突進・威圧
        WATER_STARTER.put("QTE-M-WATER-5", 1); // 知識の守護者 4/0+/5 守護・攻撃力=手札枚数
        WATER_STARTER.put("QTE-M-WATER-19", 1); // 英知の継承者 4/2/2 召喚時:4枚引いて3枚捨てる(★58)
        WATER_STARTER.put("QTE-M-WATER-7", 1); // 水鏡の幻術師 5/5/3 突進・守護・召喚時2ドロー
        WATER_STARTER.put("QTE-M-WATER-20", 1); // 黄泉還る水龍 5/4/4 突進・潜伏(墓地トリガーはBatch 4)
        WATER_STARTER.put("QTE-M-WATER-21", 1); // 双流の幻術師 7/3/2 知識・動的コスト・召喚時3体バウンス
        WATER_STARTER.put("QTE-M-WATER-22", 1); // 知恵の双翼 8/4/4 知識・守護・特殊召喚(知識2体を手札へ)
        WATER_STARTER.put("QTE-M-WATER-8", 1); // 海皇 ポセイドン 8/6/5 特殊召喚
        WATER_STARTER.put("QTE-M-WATER-23", 1); // 智将 ポセイドン・コア 9/5/5 知識・守護・特殊召喚・突進付与
        WATER_STARTER.put("QTE-M-WATER-24", 1); // 深海神 プレサージュ 10/6/6 知識・特殊召喚
        // ウェポン
        WATER_STARTER.put("QTE-M-WATER-13", 1); // 真珠の三叉槍 (3/⚔2) リーダー攻撃時1ドロー
        WATER_STARTER.put("QTE-M-WATER-14", 1); // 氷結の杖 (2/⚔1) 知識・攻撃対象を凍結
        WATER_STARTER.put("QTE-M-WATER-28", 1); // 影潜む水刺客 (1/⚔0+) 貫通・潜伏の数だけ攻撃+1
        // スペル
        WATER_STARTER.put("QTE-M-WATER-25", 1); // アクア・サーチ (1) 2ドロー+1枚捨て
        WATER_STARTER.put("QTE-M-WATER-9", 1); // スプラッシュ・ドロー (2) 2ドロー
        WATER_STARTER.put("QTE-M-WATER-10", 1); // 恵みの雨 (2) 4回復+1ドロー
        WATER_STARTER.put("QTE-M-WATER-26", 1); // 静寂の瞑想 (2) 3ドロー+使用制限・メイン最初のみ
        WATER_STARTER.put("QTE-M-WATER-27", 1); // 流転の書 (2) 1ドロー・還元
        WATER_STARTER.put("QTE-M-WATER-12", 1); // 溢れ出る英知 (5) 2ドロー+水文明バフ
        WATER_STARTER.put("QTE-M-WATER-11", 1); // タイダルウェーブ (3) 相手コスト4以下全バウンス

        // ★★Batch 60: Ver1.1 の新カード。P1〜P5 で効果を実装し終えたので、
        //   プリセットにも入れる(進化ミニオンは Batch 52 で解禁済み。裁定E1)。
        //   枚数を空けたぶんは、上の既存カードから同数だけ削っている(合計40枚は不変)。
        WATER_STARTER.put("QTE-M-WATER-33", 2); // 海獣タウギーナ 1/1/1 潜伏
        WATER_STARTER.put("QTE-M-WATER-34", 2); // 海獣ホウェライソ 2/2/2 潜伏・守護
        WATER_STARTER.put("QTE-M-WATER-35", 2); // 海獣リューグー 3/3/1 知識・突進・召喚時:潜伏が居れば1ドロー
        WATER_STARTER.put("QTE-M-WATER-30", 1); // ★進化 海淵獣シラーカ 3/2/2 (素材:水の潜伏なし)潜伏・知識
        WATER_STARTER.put("QTE-M-WATER-31", 1); // ★進化 海淵獣ラカブ 2/1/3 (素材:水の潜伏あり)召喚時3ドロー1捨て
        WATER_STARTER.put("QTE-M-WATER-32", 1); // ★進化 海淵獣ゾクシム 3/2/1 (素材:水以外)2ドロー・破壊時2捨て
        WATER_STARTER.put("QTE-M-WATER-36", 1); // 潮獣ビシャカワ (1) 潜伏の数だけリーダー回復
        WATER_STARTER.put("QTE-M-WATER-37", 1); // 潮獣コアンチ (1) 2回復+1ドロー1捨て
        WATER_STARTER.put("QTE-M-WATER-38", 1); // ギガマウス・バイト (15-) 手札の枚数だけ軽減+水のミニオン3体を突進付きで展開
        WATER_STARTER.put("QTE-M-WATER-39", 1); // アルキンティス (1/⚔0+) 知識の数だけ攻撃+1
    }

    /** 火文明スターターデッキ(★Batch 60 で Ver1.1 化): ミニオン23枚+進化3枚+ウェポン3枚+スペル11枚 */
    private static final Map<String, Integer> FIRE_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン(Ver0.4 からの18枚)
        FIRE_STARTER.put("QTE-M-FIRE-2", 2); // フレア・ポーン 1/2/1
        FIRE_STARTER.put("QTE-M-FIRE-16", 2); // 血誓のバーサーカー 1/2/2 召喚時:自傷1(+条件2)
        FIRE_STARTER.put("QTE-M-FIRE-4", 2); // 火炎の狂信者 2/2+/2 被ダメージのたび攻撃+2
        FIRE_STARTER.put("QTE-M-FIRE-3", 2); // ブラッドレイジの突撃兵 2/3/3 召喚時:自傷2
        FIRE_STARTER.put("QTE-M-FIRE-17", 1); // 逆境の猛火者 2/1/1 召喚時:条件で踏み倒し
        FIRE_STARTER.put("QTE-M-FIRE-18", 1); // 痛撃の炎術師 3/1/2 知識・召喚時:条件で自傷1
        FIRE_STARTER.put("QTE-M-FIRE-6", 2); // インフェルノ・ハウンド 4/4/1 速攻
        FIRE_STARTER.put("QTE-M-FIRE-5", 1); // 赫灼の重戦士 4/4/4 召喚時:条件で速攻
        FIRE_STARTER.put("QTE-M-FIRE-19", 1); // 相打ちの咎人 4/2/2 召喚時:相互2ダメージ
        FIRE_STARTER.put("QTE-M-FIRE-20", 1); // 覚醒の炎童 4/1/1 知識・特殊召喚
        FIRE_STARTER.put("QTE-M-FIRE-21", 1); // 背水の炎壁 7/5/3 守護・特殊召喚
        FIRE_STARTER.put("QTE-M-FIRE-8", 1); // 極炎竜 ヴォルカニクス 7/6/2 速攻・特殊召喚
        FIRE_STARTER.put("QTE-M-FIRE-22", 1); // 鳳凰神 ヴォルカニクスレヴォ 13/3/8 速攻・特殊召喚
        // ウェポン
        FIRE_STARTER.put("QTE-M-FIRE-13", 1); // フレム・ダガー (1/⚔1) 知識
        FIRE_STARTER.put("QTE-M-FIRE-14", 1); // 魔剣 レーヴァテイン (3/⚔5) 攻撃時:自傷3
        FIRE_STARTER.put("QTE-M-FIRE-28", 1); // 反転の炎鏡 (3/⚔1) 自傷を水増しする
        // スペル
        FIRE_STARTER.put("QTE-M-FIRE-9", 1); // イグニッション・バースト (1) 自傷2+2ドロー
        FIRE_STARTER.put("QTE-M-FIRE-23", 1); // フレイム・スナイプ (1) 守護HP5以下を破壊
        FIRE_STARTER.put("QTE-M-FIRE-10", 1); // マグマ・ストレート (2) ミニオン1体に3ダメージ
        FIRE_STARTER.put("QTE-M-FIRE-11", 1); // 命を削る烈火 (3) 自傷3+相手全体2ダメージ
        FIRE_STARTER.put("QTE-M-FIRE-27", 1); // 命喰いの火種 (2) 自傷3+2ドロー・還元
        FIRE_STARTER.put("QTE-M-FIRE-26", 1); // 再起の炎陣 (3) 1捨て1ドロー・還元

        // ★★Batch 60: Ver1.1 の新カード。P1〜P5 で効果を実装し終えたので、
        //   プリセットにも入れる(進化ミニオンは Batch 52 で解禁済み。裁定E1)。
        //   枚数を空けたぶんは、上の既存カードから同数だけ削っている(合計40枚は不変)。
        FIRE_STARTER.put("QTE-M-FIRE-33", 2); // 支援盾機狸 0/1/1 守護・攻撃不可・破壊時に自傷1
        FIRE_STARTER.put("QTE-M-FIRE-34", 2); // 乱戦鉄機狼 1/1/1 速攻・召喚時:自傷1(HP10以下なら相手へ)
        FIRE_STARTER.put("QTE-M-FIRE-35", 1); // 砲台鉄機虎 5/3/1 突進・特殊召喚(進化が居れば0)
        FIRE_STARTER.put("QTE-M-FIRE-30", 1); // ★進化 不敗鉄人闘太 5/0+/0+ (素材:自分1体以上)下の枚数×2
        FIRE_STARTER.put("QTE-M-FIRE-31", 1); // ★進化 追撃鉄人連太 2/2/5 (素材:自分の進化1体)2回攻撃
        FIRE_STARTER.put("QTE-M-FIRE-32", 1); // ★進化 飛翔鉄人走太 3/1/1 (素材:ミニオン1体)特殊召喚(場に3体で0)
        FIRE_STARTER.put("QTE-M-FIRE-36", 2); // ラスト・アタック (1) 自分1体破壊→相手に3ダメージ(進化なら全体+2)
        FIRE_STARTER.put("QTE-M-FIRE-37", 1); // リペア・チューナー (2) 1捨て2ドロー
        FIRE_STARTER.put("QTE-M-FIRE-38", 1); // アイアン・リターン (2) 手札を戻して枚数+1ドロー
        FIRE_STARTER.put("QTE-M-FIRE-39", 1); // ドレイン・ブラスト (3) 2体に4ダメージ+破壊数だけ回復・還元
    }

    /** 闇スターターデッキ(★Batch 60 で Ver1.1 化): ミニオン27枚+進化3枚+ウェポン3枚+スペル7枚 */
    private static final Map<String, Integer> DARK_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン(Ver0.4 からの16枚)
        DARK_STARTER.put("QTE-M-DARK-2", 2); // カース・ボーン 2/1/1 召喚時:自分のミニオン1体破壊+コスト分ミル(★58)
        // ★Batch 46b: 5枚 → 4枚。Ver1.1 の本文から「このカードは4枚以上入れられる」が消え、
        //   同名4枚の例外そのものが無くなったため。
        //   ★Batch 60: さらに 4枚 → 2枚。Ver1.1 の新カード11枚を入れる枠を作るためであり、
        //   ルールが変わったからではない。同名上限そのものは4枚のままである。
        DARK_STARTER.put("QTE-M-DARK-16", 3); // ゾンストライカー 1/1/1 突進・墓地から全て展開
        DARK_STARTER.put("QTE-M-DARK-17", 2); // 腐敗の投擲者 2/2/1 召喚時:相手ミニオンに1ダメージ
        DARK_STARTER.put("QTE-M-DARK-3", 2); // 生贄を求める邪鬼 2/3/2 召喚時:生贄か自壊
        DARK_STARTER.put("QTE-M-DARK-20", 2); // 執念の暗殺者 4/3/3 召喚時3ダメージ・破壊のたびドロー
        DARK_STARTER.put("QTE-M-DARK-6", 1); // ボーン・コレクター 4/4/2 突進・戦闘破壊で1ドロー
        DARK_STARTER.put("QTE-M-DARK-5", 1); // 不滅のネクロマンサー 4/3/3 裏向きマナで蘇生
        DARK_STARTER.put("QTE-M-DARK-4", 1); // 裏切りの魔女 3/2/3 召喚時:条件付き除去
        DARK_STARTER.put("QTE-M-DARK-21", 1); // 群がる死霊王 6-/7/3 墓地のゾンストライカーで軽減
        DARK_STARTER.put("QTE-M-DARK-8", 1); // 冥界神ハデス 8/7/7 召喚時:全体破壊+蘇生
        // ウェポン
        DARK_STARTER.put("QTE-M-DARK-13", 1); // 死神の大鎌 (2/⚔0) 攻撃対象を無条件破壊
        DARK_STARTER.put("QTE-M-DARK-28", 1); // 死霊の収鎌 (2/⚔1) 攻撃時:墓地から1枚回収
        DARK_STARTER.put("QTE-M-DARK-14", 1); // 禁忌の冥魔剣 (4/⚔1) 裏向きマナを表に+1ダメージ
        // スペル
        DARK_STARTER.put("QTE-M-DARK-9", 1); // 絶望の連鎖 (1) 相互1体破壊
        DARK_STARTER.put("QTE-M-DARK-11", 1); // マナを貪る怨霊 (2) マナ2枚裏向き+3ドロー
        DARK_STARTER.put("QTE-M-DARK-10", 1); // 禁忌の代償 (2) 裏向きマナ1枚破壊+確定除去
        DARK_STARTER.put("QTE-M-DARK-26", 1); // 冥府への道 (5) 確定除去
        DARK_STARTER.put("QTE-M-DARK-25", 1); // 禁忌の墓地利用 (5) 墓地のスペルを裏向きマナへ
        DARK_STARTER.put("QTE-M-DARK-12", 1); // 死者蘇生 (7-) 生贄で軽減+突進付き蘇生

        // ★★Batch 60: Ver1.1 の新カード。P1〜P5 で効果を実装し終えたので、
        //   プリセットにも入れる(進化ミニオンは Batch 52 で解禁済み。裁定E1)。
        //   枚数を空けたぶんは、上の既存カードから同数だけ削っている(合計40枚は不変)。
        DARK_STARTER.put("QTE-M-DARK-33", 2); // デビルズマイク 1/1/1 攻撃時:相手リーダーに1ダメージ
        DARK_STARTER.put("QTE-M-DARK-34", 2); // サモンズライト 2/1/2 召喚時1ダメージ・破壊時にコスト1を蘇生
        DARK_STARTER.put("QTE-M-DARK-35", 2); // カムバックキーパー 3/1/4 守護・場以外から墓地に置かれると自力で戻る
        DARK_STARTER.put("QTE-M-DARK-36", 1); // ダークネオンステージ 5/1/4 特殊召喚(場1枚+手札2枚捨てで0)
        DARK_STARTER.put("QTE-M-DARK-37", 1); // グレイヴガールズファン 5/2/4 守護・賢魂1(1ドロー+1セルフミル)
        DARK_STARTER.put("QTE-M-DARK-38", 1); // スタンディングテント 6/1/6 守護・召喚時2ドロー・賢魂2(踏み倒し)
        DARK_STARTER.put("QTE-M-DARK-39", 1); // 1stL「NEMれぬ夜のドリーミー」 7/0+/5 召喚時:他を全破壊+破壊数だけ攻撃+1
        DARK_STARTER.put("QTE-M-DARK-30", 1); // ★進化 リボーンライヴ・ノア 8/5/5 (素材:闇のHP4以上)墓地から3体展開
        DARK_STARTER.put("QTE-M-DARK-31", 1); // ★進化 サモナーポップ・エンラ 5/2/4 (素材:ミニオン1体)墓地からも特殊召喚
        DARK_STARTER.put("QTE-M-DARK-32", 1); // ★進化 サービスブレイク・メリィナ 6-/2/6 (素材:闇2体)他の味方に攻撃+1
        DARK_STARTER.put("QTE-M-DARK-22", 1); // ★墓場の怨念集合体 (動的)/0+/? 守護・召喚時:墓地のスペルを回収(裁定278 の道具)
        DARK_STARTER.put("QTE-M-DARK-27", 1); // ★悪夢 (2) サブフェイズなら全ミニオンのコスト-4。ただしこのターンの召喚時が死ぬ(裁定265)
    }

    /** 光スターターデッキ(★Batch 60 で Ver1.1 化): ミニオン26枚+進化3枚+ウェポン3枚+スペル8枚 */
    private static final Map<String, Integer> LIGHT_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン(Ver0.4 からの19枚)
        LIGHT_STARTER.put("QTE-M-LIGHT-16", 3); // 煌めきの盾 1/2/2 守護・攻撃不可
        LIGHT_STARTER.put("QTE-M-LIGHT-2", 2); // ライト・シールド 2/1/3 守護
        LIGHT_STARTER.put("QTE-M-LIGHT-17", 2); // 聖域の司祭 2/2/2 知識
        LIGHT_STARTER.put("QTE-M-LIGHT-3", 1); // 聖域の案内人 3/2/2 知識・条件で知識2回目
        LIGHT_STARTER.put("QTE-M-LIGHT-18", 1); // 唱導の聖騎士 3/2/2 還元・自分のスペルコスト-1
        LIGHT_STARTER.put("QTE-M-LIGHT-19", 1); // 英知の水晶 3/0/2 自分の知識カードコスト-1
        LIGHT_STARTER.put("QTE-M-LIGHT-20", 1); // 戒律のガーディアン 4/1/1 守護・スペル/守護コスト-1
        LIGHT_STARTER.put("QTE-M-LIGHT-4", 1); // 秩序の執行官 4/3/4 相手の特殊召喚を封じる
        LIGHT_STARTER.put("QTE-M-LIGHT-21", 1); // 光の召喚士 5/3/3 守護・召喚時:コスト3以下を1体踏み倒し
        LIGHT_STARTER.put("QTE-M-LIGHT-22", 1); // 降臨の伝道師 6/1/1 守護・召喚時:山札4枚から守護を1体展開
        LIGHT_STARTER.put("QTE-M-LIGHT-6", 1); // 戒律の聖堂騎士 6/5/5 守護・相手のサブフェイズを封じる
        LIGHT_STARTER.put("QTE-M-LIGHT-7", 1); // 大天使ミカエル 7/4/8 守護・戦闘では破壊されない
        LIGHT_STARTER.put("QTE-M-LIGHT-8", 1); // 天界の守護神 ゾディアック 9/7/9 守護・相手リーダー攻撃不可
        LIGHT_STARTER.put("QTE-M-LIGHT-24", 1); // 断罪の大天使 10/8/8 守護・相手の3枚目以降のドローを置換
        LIGHT_STARTER.put("QTE-M-LIGHT-25", 1); // 創世神 ゾディアックアイリス 11/11/11 守護・潜伏・知識・リーダー攻撃不可
        // ウェポン
        LIGHT_STARTER.put("QTE-M-LIGHT-13", 1); // 正義の御盾 (1/⚔0) リーダーへの被ダメージ-1
        LIGHT_STARTER.put("QTE-M-LIGHT-28", 1); // 詠唱の宝珠 (1/⚔1) 破壊時:次のスペルコスト-1
        LIGHT_STARTER.put("QTE-M-LIGHT-14", 1); // 聖剣エクスカリバー (4/⚔3) リーダー攻撃時:守護全体2回復
        // スペル
        LIGHT_STARTER.put("QTE-M-LIGHT-9", 1); // 光の戒め (2) 凍結+1ドロー
        LIGHT_STARTER.put("QTE-M-LIGHT-10", 1); // ホーリー・シグナル (3) 相手の最高攻撃力を破壊
        LIGHT_STARTER.put("QTE-M-LIGHT-26", 1); // 聖光の武装解除 (3) ウェポン破壊・還元
        LIGHT_STARTER.put("QTE-M-LIGHT-11", 1); // 聖なる降誕の儀式 (5) コスト7以下の守護を1体踏み倒し
        LIGHT_STARTER.put("QTE-M-LIGHT-12", 1); // 神の福音 (6) 光の守護を2体まで踏み倒し+その数だけドロー

        // ★★Batch 60: Ver1.1 の新カード。P1〜P5 で効果を実装し終えたので、
        //   プリセットにも入れる(進化ミニオンは Batch 52 で解禁済み。裁定E1)。
        //   枚数を空けたぶんは、上の既存カードから同数だけ削っている(合計40枚は不変)。
        LIGHT_STARTER.put("QTE-M-LIGHT-33", 2); // 光霊・ミルーア 2/2/2 守護・還元
        LIGHT_STARTER.put("QTE-M-LIGHT-34", 2); // 光霊・テングスン 2/2/1 常在:相手のスペルコスト+1
        LIGHT_STARTER.put("QTE-M-LIGHT-35", 2); // 光霊・ネフラ 3/3/1 召喚時:山札の上3枚から守護とスペルを回収
        LIGHT_STARTER.put("QTE-M-LIGHT-36", 1); // 光霊・モアニール 3/1/5 常在:相手の踏み倒しを山札の下へ/被ダメージを肩代わり
        LIGHT_STARTER.put("QTE-M-LIGHT-30", 1); // ★進化 英霊・ニュウキロ 5/3/5 (素材:守護HP2以上)相手のスペルを重くする
        LIGHT_STARTER.put("QTE-M-LIGHT-31", 1); // ★進化 英霊・コレキ 2/2/2 (素材:光1体)相手は1ターンに1体しか出せない
        LIGHT_STARTER.put("QTE-M-LIGHT-32", 1); // ★進化 英霊・タイガラム 7/4/6 (素材:光の守護)守護を踏み倒し・賢魂3
        LIGHT_STARTER.put("QTE-M-LIGHT-37", 1); // 英術・グラーニス (2) 2回復・還元
        LIGHT_STARTER.put("QTE-M-LIGHT-38", 1); // 英術・バンユー (5) 次の相手ターンのスペルと2回攻撃を封じる
        LIGHT_STARTER.put("QTE-M-LIGHT-39", 1); // 英術・スケアロック (6) 光のコスト3以下+進化を手札から踏み倒し
    }

    /** 風スターターデッキ(★Batch 60 で Ver1.1 化): ミニオン27枚+進化3枚+ウェポン3枚+スペル7枚 */
    private static final Map<String, Integer> WIND_STARTER = new LinkedHashMap<>();

    static {
        // ミニオン(Ver0.4 からの16枚)
        WIND_STARTER.put("QTE-M-WIND-2", 2); // ウィンド・ペティ 1/1/1 知識
        WIND_STARTER.put("QTE-M-WIND-16", 2); // 疾風の先陣 1/1/1 守護・突進
        WIND_STARTER.put("QTE-M-WIND-3", 2); // スカイ・スワロー 1/1/1 速攻
        WIND_STARTER.put("QTE-M-WIND-5", 1); // サイクロン・フェンサー 2/1/1 1ターンに2回攻撃
        WIND_STARTER.put("QTE-M-WIND-4", 1); // 嵐の呼び手 2/2/2 召喚時:条件でバウンス
        WIND_STARTER.put("QTE-M-WIND-6", 1); // ガイル・フォックス 3/2/2 召喚時:条件で潜伏
        WIND_STARTER.put("QTE-M-WIND-7", 1); // 突風のまとめ役 3/1/3 使用のたび全体+1(このターン)
        WIND_STARTER.put("QTE-M-WIND-17", 1); // 静空の風使い 4/1/1 潜伏・守護・タップでマナ加速
        WIND_STARTER.put("QTE-M-WIND-8", 1); // ストーム・カイザー 7/3/2 速攻・特殊召喚(5枚使用でコスト1。★58)
        WIND_STARTER.put("QTE-M-WIND-18", 1); // 詠唱の疾風騎士 6/3/3 突進・スペルで軽減
        WIND_STARTER.put("QTE-M-WIND-19", 1); // 嵐の守り手 7/1/4 守護・特殊召喚
        WIND_STARTER.put("QTE-M-WIND-20", 1); // 結集する風の精 8/4/4 知識・還元・動的コスト軽減
        WIND_STARTER.put("QTE-M-WIND-21", 1); // 風神ヴァーユ 9/5/5 守護・特殊召喚
        // ウェポン
        WIND_STARTER.put("QTE-M-WIND-13", 1); // 暴風の双剣 (2/⚔0+) 使用のたび攻撃+1(このターン)
        WIND_STARTER.put("QTE-M-WIND-14", 1); // 疾風のレイピア (3/⚔2) 1ターンに2回攻撃
        WIND_STARTER.put("QTE-M-WIND-28", 1); // 風護の杖 (3/⚔1) 知識・攻撃時:味方に体力+1と守護
        // スペル
        WIND_STARTER.put("QTE-M-WIND-10", 1); // そよ風の加護 (1) 体力+1・守護付与
        WIND_STARTER.put("QTE-M-WIND-9", 1); // 追い風 (1) 攻撃力+1・1ドロー
        WIND_STARTER.put("QTE-M-WIND-24", 1); // 風弾の跳弾 (1) 自壊+3ダメージ・強化使用+2(★58)
        WIND_STARTER.put("QTE-M-WIND-23", 1); // 風のマナ変換 (1) 表向き→裏向きマナ入れ替え
        WIND_STARTER.put("QTE-M-WIND-25", 1); // 選択の追い風 (2) 1ドロー+任意で守護を捨てて追加ドロー
        WIND_STARTER.put("QTE-M-WIND-27", 1); // 突風の祝福 (3) 体力+2・還元
        WIND_STARTER.put("QTE-M-WIND-12", 1); // 神風の大号令 (4) 使用枚数分の全体攻撃力+

        // ★★Batch 60: Ver1.1 の新カード。P1〜P5 で効果を実装し終えたので、
        //   プリセットにも入れる(進化ミニオンは Batch 52 で解禁済み。裁定E1)。
        //   枚数を空けたぶんは、上の既存カードから同数だけ削っている(合計40枚は不変)。
        WIND_STARTER.put("QTE-M-WIND-33", 2); // 透キ通ル・アヤカシ 1/1/1 突進・条件で0コスト・ターン終了時に自壊
        WIND_STARTER.put("QTE-M-WIND-34", 2); // ハク霊 2/2/3 ターン開始時に自壊→回復してコク霊を呼ぶ
        WIND_STARTER.put("QTE-M-WIND-35", 2); // コク霊 2/3/2 ターン開始時に自壊→相手に1ダメージしてハク霊を呼ぶ
        WIND_STARTER.put("QTE-M-WIND-36", 2); // 喚ビ集ウ・アヤカシ 2/1/1 召喚時:味方1体を破壊して2ドロー
        WIND_STARTER.put("QTE-M-WIND-37", 1); // 魂喰ラウ・オニ 5/2/4 召喚時:味方を全破壊し数だけ相手リーダーへ
        WIND_STARTER.put("QTE-M-WIND-38", 1); // 暴レ狂ウ・オニ 5/2/2 召喚時:味方を全破壊し数だけ相手全体へ+リーダー1
        WIND_STARTER.put("QTE-M-WIND-39", 1); // 天翔ケル霊鬼・シュテン 8/4/2 速攻・特殊召喚(8体破壊で1)
        WIND_STARTER.put("QTE-M-WIND-30", 1); // ★進化 黒ノ霊導者 5/3/3 (素材:風1体)守護・賢魂1
        WIND_STARTER.put("QTE-M-WIND-31", 1); // ★進化 白ノ霊知者 4/2/4 (素材:風1体)召喚時2ドロー+1体破壊・賢魂2・還元
        WIND_STARTER.put("QTE-M-WIND-32", 1); // ★進化 灰ノ霊呼者 3/2/4 (素材:風1体)召喚時:破壊時持ちを手札から2体展開
    }

    /** 土スターターデッキ(★Batch 60 で Ver1.1 化): ミニオン23枚+進化3枚+ウェポン3枚+スペル11枚 */
    private static final Map<String, Integer> EARTH_STARTER = new LinkedHashMap<>();
    static {
        // ミニオン(Ver0.4 からの17枚)
        EARTH_STARTER.put("QTE-M-EARTH-16", 2); // 苗木植えの精霊 2/1/1 召喚時:手札1枚をマナ加速
        EARTH_STARTER.put("QTE-M-EARTH-17", 2); // 地砕きの突撃兵 3/3/1 突進・攻撃時マナ回収/破壊時マナ加速
        EARTH_STARTER.put("QTE-M-EARTH-2", 1); // ゴーレム・ウォール 3/1/5 守護
        EARTH_STARTER.put("QTE-M-EARTH-18", 2); // 大地の狂戦士 4/3/1 突進・動的コスト(マナ7+で1)
        EARTH_STARTER.put("QTE-M-EARTH-3", 2); // 大地の精霊グラン 5/4/4 召喚時:山札からマナ加速
        EARTH_STARTER.put("QTE-M-EARTH-4", 1); // アースクエイクジャイアント 6/6/3 召喚時:相手守護を全破壊
        EARTH_STARTER.put("QTE-M-EARTH-19", 1); // 連撃の巨岩 6/3/8 突進・2回攻撃・エンド時バウンス
        EARTH_STARTER.put("QTE-M-EARTH-5", 1); // 不動の岩石竜 7/6/8 守護・潜伏
        EARTH_STARTER.put("QTE-M-EARTH-7", 1); // 百獣の王ベヒーモス 9/10/10 バニラ
        EARTH_STARTER.put("QTE-M-EARTH-8", 1); // 創世神ガイア 9/6/7 特殊召喚・召喚時:自身以外全破壊
        EARTH_STARTER.put("QTE-M-EARTH-21", 1); // 天変地異のタイタン 10/7/7 召喚時:相手全体7ダメージ+2ドロー
        EARTH_STARTER.put("QTE-M-EARTH-20", 1); // 安らぎのガーディアン 10/4/4 守護・召喚時2回復/エンド時4回復
        EARTH_STARTER.put("QTE-M-EARTH-23", 1); // 不動の絶対神ガイア 14/10/10 潜伏・攻撃時リーダー4ダメージ

        // ウェポン
        EARTH_STARTER.put("QTE-M-EARTH-13", 1); // 大地の守護盾 (2/⚔0) リーダーへの攻撃を肩代わり
        EARTH_STARTER.put("QTE-M-EARTH-14", 1); // ガイア・ハンマー (4/⚔4) 装備時:山札からマナ加速
        EARTH_STARTER.put("QTE-M-EARTH-28", 1); // 地響きの槌 (7/⚔2) 攻撃時:相手ミニオン全体2ダメージ

        // スペル
        EARTH_STARTER.put("QTE-M-EARTH-25", 1); // 大地の開眼 (1) 1ドロー(マナ7+でさらに1)
        EARTH_STARTER.put("QTE-M-EARTH-9", 1); // 大地の恵み (3) 山札からマナ加速
        EARTH_STARTER.put("QTE-M-EARTH-10", 1); // 落石の罠 (3) 相手ミニオン1体に5ダメージ
        EARTH_STARTER.put("QTE-M-EARTH-26", 1); // ガイア・リソース (4) 山札からマナ加速・還元
        EARTH_STARTER.put("QTE-M-EARTH-11", 1); // 大地震 (4) お互いのコスト3以下を全破壊
        EARTH_STARTER.put("QTE-M-EARTH-27", 1); // 地脈の覚醒 (2) マナ1枚を手札へ・還元・ターン1回(★58)
        EARTH_STARTER.put("QTE-M-EARTH-12", 1); // 豊穣の祈り (5) 山札からマナ加速+2ドロー

        // ★★Batch 60: Ver1.1 の新カード。P1〜P5 で効果を実装し終えたので、
        //   プリセットにも入れる(進化ミニオンは Batch 52 で解禁済み。裁定E1)。
        //   枚数を空けたぶんは、上の既存カードから同数だけ削っている(合計40枚は不変)。
        EARTH_STARTER.put("QTE-M-EARTH-33", 2); // 分那愚利(ブンナグリ) 2/1/2 突進・召喚時:相手1体に1ダメージ
        EARTH_STARTER.put("QTE-M-EARTH-34", 2); // 勝鼓美(カチコミ) 2/1/1 破壊時:マナ加速+マナからコスト3以下を場へ
        EARTH_STARTER.put("QTE-M-EARTH-35", 1); // 素手喧嘩(ステゴロ) 6/4/2 突進・攻撃時:自身をマナへ置いてマナから展開
        EARTH_STARTER.put("QTE-M-EARTH-36", 1); // 勝阿外(カツアゲ) 9/1+/3 常在:相手はスペルを唱えられない・賢魂2
        EARTH_STARTER.put("QTE-M-EARTH-30", 1); // ★進化 愚乱怒土地 6/3/3 (素材:土1体)威圧・賢魂3
        EARTH_STARTER.put("QTE-M-EARTH-31", 1); // ★進化 裏雷怒乗込 3/1/5 (素材:土1体)守護・攻撃時1ドロー
        EARTH_STARTER.put("QTE-M-EARTH-32", 1); // ★進化 武羅須斗最終 5/3/2 (素材:土1体)特殊召喚(マナ7+で1)・守護・還元
        EARTH_STARTER.put("QTE-M-EARTH-37", 2); // 仏恥義理(ブッチギリ) (2) 1ドロー+手札1枚を裏向きマナへ
        EARTH_STARTER.put("QTE-M-EARTH-38", 1); // 喧嘩上等(ケンカジョウトウ) (6) 相手1体をマナへ+マナからコスト6以下を場へ
        EARTH_STARTER.put("QTE-M-EARTH-39", 1); // 俺等地上覇夜露死苦 (9) 相手を全破壊+表向きマナからミニオンを1体展開
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
