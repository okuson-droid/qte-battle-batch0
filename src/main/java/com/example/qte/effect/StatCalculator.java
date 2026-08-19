package com.example.qte.effect;

import org.springframework.stereotype.Component;

import com.example.qte.game.GameState;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.StatModifier;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.master.Keyword;

/**
 * ミニオンの現在攻撃力の評価器。
 *
 * 攻撃力は保存された値ではなく「評価するたびに計算される値」として扱う(設計判断4)。
 * 手札枚数のような参照元は刻々と変わるため、どこかに数値をキャッシュすると
 * 更新漏れのバグ(例: ドロー後に攻撃力表示が古いまま)を必ず生むからである。
 *
 * 適用順序: 基礎値 → 動的SET → 静的SET → 動的ADD → 静的ADD
 * (SET=値の置き換え、ADD=加算。設計判断12)
 */
@Component
public class StatCalculator {

    /** 墓地のカードの種別(スペルか否か)を判定するために参照する */
    private final CardMasterRepository cards;

    public StatCalculator(CardMasterRepository cards) {
        this.cards = cards;
    }

    // ---------------------------------------------------------------
    // 参照元の集計(闇文明: 墓地と裏向きマナを資源として数える)
    // ---------------------------------------------------------------

    /** 自分の墓地にあるスペル以外のカードの枚数(悪夢・墓場の怨念集合体) */
    public int nonSpellCountInTrash(PlayerState owner) {
        return (int) owner.getTrash().stream()
                .filter(id -> cards.findById(id).type() != CardType.SPELL)
                .count();
    }

    /** 自分の墓地にある特定のカードの枚数(群がる死霊王が数える「ゾンストライカー」) */
    public int countInTrash(PlayerState owner, String cardId) {
        return (int) owner.getTrash().stream().filter(cardId::equals).count();
    }

    /**
     * 手札のカードの現在コスト。コストも動的ステータス(設計判断5)。
     * 例: 双流の幻術師「場に居る知識の数Cost-1」(両者の場を参照: 発注者確認済み)
     */
    public int effectiveCost(GameState state, PlayerState owner, CardMaster card) {
        int cost = card.cost();

        // 【剛火の将】の起動能力: 次に手札から使用する火文明ミニオンのコスト-1(0にはならない)
        if (owner.getPendingFireMinionDiscount() > 0
                && card.type() == com.example.qte.master.CardType.MINION
                && card.civilization() == com.example.qte.master.Civilization.FIRE) {
            cost = Math.max(1, cost - 1);
        }
        // ---- 闇文明: 墓地・禁忌デッキ・生贄を参照する動的コスト ----
        // 悪夢: 墓地にあるスペル以外のカード1枚につきコスト-1
        if ("QTE-M-DARK-27".equals(card.id())) {
            cost -= nonSpellCountInTrash(owner);
        }
        // 群がる死霊王: 墓地にある「ゾンストライカー」の数だけコスト-1
        if ("QTE-M-DARK-21".equals(card.id())) {
            cost -= countInTrash(owner, "QTE-M-DARK-16");
        }
        // 封印されし禁忌魔人: 禁忌デッキの残り枚数だけコスト+1(唯一のコスト増加カード)
        if ("QTE-M-DARK-18".equals(card.id())) {
            cost += owner.getTabooDeck().size();
        }
        // 死者蘇生: 使用宣言時に生贄にした自分のミニオンの数だけコスト-1
        if ("QTE-M-DARK-12".equals(card.id())) {
            cost -= owner.getPendingSacrificeCount();
        }
        // 悪夢: このターン中、ミニオンの召喚コストを-4する(サブフェイズに使用したときのみ付与される)
        if (card.type() == CardType.MINION && owner.getThisTurnAuras().contains("QTE-M-DARK-27")) {
            cost -= 4;
        }
        // ---- 光文明: 場のミニオンによる常在のコスト軽減(累積する。下限は0) ----
        // 唱導の聖騎士(QTE-M-LIGHT-18)・戒律のガーディアン(QTE-M-LIGHT-20): 自分のスペルのコスト-1
        // 英知の水晶(QTE-M-LIGHT-19): 自分の【知識】カードのコスト-1
        // 戒律のガーディアン(QTE-M-LIGHT-20): 【守護】を持つカードのコスト-1
        for (MinionInstance minion : owner.getMinionZone()) {
            String id = minion.getMaster().id();
            boolean spellDiscounter = "QTE-M-LIGHT-18".equals(id) || "QTE-M-LIGHT-20".equals(id);
            if (spellDiscounter && card.type() == CardType.SPELL) {
                cost -= 1;
            }
            if ("QTE-M-LIGHT-19".equals(id) && card.keywords().contains(Keyword.KNOWLEDGE)) {
                cost -= 1;
            }
            if ("QTE-M-LIGHT-20".equals(id) && card.keywords().contains(Keyword.GUARD)) {
                cost -= 1;
            }
        }
        // 詠唱の宝珠: 破壊された後、次に唱えるスペルのコスト-1(ターンをまたいで持続)
        if (card.type() == CardType.SPELL && owner.getPersistentAuras().stream()
                .anyMatch(aura -> "QTE-M-LIGHT-28".equals(aura.cardId()))) {
            cost -= 1;
        }
        // 双流の幻術師: 場に居るミニオンの数だけコスト-1。
        // Ver.0.4 で参照が「【知識】を持つミニオンの数」から「ミニオンの数」全体に広がった。
        // 側の限定が無いため両者の場を数える(記法規約。従来と同じ)
        if ("QTE-M-WATER-21".equals(card.id())) {
            long minionsOnBoard = java.util.stream.Stream
                    .of(state.getPlayer1(), state.getPlayer2())
                    .mapToLong(p -> p.getMinionZone().size())
                    .sum();
            cost -= (int) minionsOnBoard;
        }
        // ---- 風文明: ターン内カウンタ・盤面参照による動的コスト ----
        // 詠唱の疾風騎士: 自分がこのターン中にスペルを唱えるたびコスト-1(このターン限定・下限0)
        if ("QTE-M-WIND-18".equals(card.id())) {
            cost -= owner.getSpellsCastThisTurn();
        }
        // 結集する風の精: 自分の場にあるミニオンの合計コスト分コスト-1
        if ("QTE-M-WIND-20".equals(card.id())) {
            cost -= owner.getMinionZone().stream()
                    .mapToInt(m -> m.getMaster().cost() == null ? 0 : m.getMaster().cost())
                    .sum();
        }
        // 詠唱の風詠士(リーダー): そのターン中3枚目に使うミニオンかスペルのコスト-1。
        // 使用カウンタは自身を含まない(裁定1)ため、「3枚目」はcardsUsedThisTurn==2の瞬間に一致する
        if ("QTE-M-WIND-15".equals(owner.getLeader().id())
                && (card.type() == CardType.MINION || card.type() == CardType.SPELL)
                && owner.getCardsUsedThisTurn() == 2) {
            cost -= 1;
        }
        // ---- 土文明: 自分のマナ枚数を参照する動的コスト(条件を満たすと固定値まで下がる) ----
        // 減算型ではなく固定値セット型。土カードは他文明の軽減対象ではないため競合しない。
        if ("QTE-M-EARTH-18".equals(card.id()) && owner.getManaZone().size() >= 7) {
            cost = 1; // 大地の狂戦士: マナ7枚以上でコスト1
        }
        // 地脈の覚醒(QTE-M-EARTH-27)の「マナ7枚以上でコスト2」は Ver.0.4 で撤廃された。
        // 基礎コストそのものが2に下がったため、条件を残すと常に真の分岐(=死んだコード)になる。
        // 大地の狂戦士(上の分岐)は同系統の条件だが Ver.0.4 でも変更されておらず、存続する
        return Math.max(0, cost);
    }

    /**
     * リーダーに装備中のウェポンの現在攻撃力。
     * 例: 影潜む水刺客「自分の場の潜伏の数Attack+1」— ウェポンにも動的値がある
     */
    public int effectiveWeaponAttack(GameState state, PlayerState owner) {
        CardMaster weapon = owner.getEquippedWeapon();
        if (weapon == null) {
            return 0;
        }
        int attack = weapon.attack();
        if ("QTE-M-WATER-28".equals(weapon.id())) {
            attack += (int) owner.getMinionZone().stream()
                    .filter(m -> m.hasKeyword(Keyword.STEALTH))
                    .count();
        }
        // 暴風の双剣: このターン中カードを使用するたびに累積した加算(ON_CARD_USEDが積む)。
        // ウェポンはMinionInstanceを持たないため、修正はプレイヤー単位で保持している
        attack += owner.getWeaponAttackBonusThisTurn();
        return Math.max(0, attack);
    }

    // ---------------------------------------------------------------
    // 攻撃回数(設計判断7: 攻撃回数は固定1ではなくカードごとの属性)
    // ---------------------------------------------------------------

    /**
     * このミニオンが1ターンに攻撃できる回数。
     *
     * 攻撃力・コストと同じく「評価するたびに計算する動的な値」として扱う(設計判断4)。
     * 印刷された性質(サイクロン・フェンサー)と、効果で与えられた一時的な追加
     * (ツイン・ストライク)の両方がありうるためである。
     * 後者は EXTRA_ATTACKS の修正としてインスタンスに積まれ、
     * 期限の管理は既存の expireThisTurnModifiers がそのまま担う。
     */
    public int maxAttacks(GameState state, PlayerState owner, MinionInstance minion) {
        int max = 1;
        // 印刷された「1ターンに2回攻撃できる」を持つカード(Batch 12b)
        if ("QTE-M-WIND-5".equals(minion.getMaster().id())) { // サイクロン・フェンサー
            max += 1;
        }
        if ("QTE-M-EARTH-19".equals(minion.getMaster().id())) { // 連撃の巨岩: 1ターンに2回攻撃できる
            max += 1;
        }
        for (StatModifier m : minion.getModifiers()) {
            if (m.stat() == StatModifier.Stat.EXTRA_ATTACKS) {
                max += m.value();
            }
        }
        return Math.max(1, max);
    }

    /**
     * リーダーが1ターンに攻撃できる回数。上限は装備しているウェポンによって決まる
     * (疾風のレイピア = 2回。それ以外 = 1回)。
     */
    public int maxLeaderAttacks(GameState state, PlayerState owner) {
        CardMaster weapon = owner.getEquippedWeapon();
        if (weapon != null && "QTE-M-WIND-14".equals(weapon.id())) { // 疾風のレイピア
            return 2;
        }
        return 1;
    }

    public int effectiveAttack(GameState state, PlayerState owner, MinionInstance minion) {
        String cardId = minion.getMaster().id();
        int attack = minion.getMaster().attack();

        // ---- 動的SET(カード固有のルール) ----
        // 知識の守護者: 攻撃力は自分の手札の枚数と同じになる(常に連動)
        if ("QTE-M-WATER-5".equals(cardId)) {
            attack = owner.getHand().size();
        }
        // 無尽蔵の巨神: 攻撃力は自分の手札の枚数と同じ(基礎0 + 手札枚数)
        if ("QTE-M-EARTH-22".equals(cardId)) {
            attack = owner.getHand().size();
        }

        // ---- 静的SET(インスタンスに積まれた修正) ----
        for (StatModifier m : minion.getModifiers()) {
            if (m.stat() == StatModifier.Stat.ATTACK && m.operation() == StatModifier.Operation.SET) {
                attack = m.value();
            }
        }

        // ---- 動的ADD ----
        // 墓場の怨念集合体: 自分の墓地にあるスペル以外のカード1枚につきAttack+1。
        // SETの後に評価しなければ加算が上書きで消えるため、必ずこの位置に置く
        if ("QTE-M-DARK-22".equals(cardId)) {
            attack += nonSpellCountInTrash(owner);
        }

        // ---- 動的ADD(オーラ) ----
        // 溢れ出る英知: このターン中、手札の枚数分だけ自分の「水文明」ミニオンの攻撃力+1(常に連動)。
        // Ver.0.4 で対象が「自分のミニオンすべて」から水文明に限定された。
        // 判定を評価側に置いているのは、オーラ適用後に出たミニオンにも同じ基準を効かせるためである
        // (オーラは付与時点のスナップショットではなく、評価のたびに場を見る)
        for (String aura : owner.getThisTurnAuras()) {
            if ("QTE-M-WATER-12".equals(aura)
                    && minion.getMaster().civilization() == Civilization.WATER) {
                attack += owner.getHand().size();
            }
        }

        // ---- 静的ADD ----
        for (StatModifier m : minion.getModifiers()) {
            if (m.stat() == StatModifier.Stat.ATTACK && m.operation() == StatModifier.Operation.ADD) {
                attack += m.value();
            }
        }

        return Math.max(0, attack);
    }
}
