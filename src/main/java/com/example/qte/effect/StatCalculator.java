package com.example.qte.effect;

import java.util.Set;

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

    // ---------------------------------------------------------------
    // ★Batch 47: 動的な値としてこのクラスが面倒を見ているカード。
    // 以前はメソッドの中に文字列リテラルで直接書かれていた。定数にしたのは、
    // 下の IMPLEMENTED_CARDS が同じIDをもう一度書き写す形になるのを避けるためである
    // (同じ値を2箇所に置くと、必ず片方だけが直される日が来る。裁定130)。
    // ---------------------------------------------------------------

    // 動的コスト
    private static final String NIGHTMARE = "QTE-M-DARK-27";            // 悪夢
    private static final String SWARM_LICH = "QTE-M-DARK-21";           // 群がる死霊王
    private static final String SEALED_TABOO_DEMON = "QTE-M-DARK-18";   // 封印されし禁忌魔人
    private static final String RAISE_DEAD = "QTE-M-DARK-12";           // 死者蘇生
    private static final String CHANT_PALADIN = "QTE-M-LIGHT-18";       // 唱導の聖騎士
    private static final String PRECEPT_GUARDIAN = "QTE-M-LIGHT-20";    // 戒律のガーディアン
    private static final String WISDOM_CRYSTAL = "QTE-M-LIGHT-19";      // 英知の水晶
    private static final String CHANT_ORB = "QTE-M-LIGHT-28";           // 詠唱の宝珠
    private static final String TWIN_ILLUSIONIST = "QTE-M-WATER-21";    // 双流の幻術師
    private static final String GALE_KNIGHT = "QTE-M-WIND-18";          // 詠唱の疾風騎士
    private static final String GATHERING_SYLPH = "QTE-M-WIND-20";      // 結集する風の精
    private static final String WIND_CHANTER_LEADER = "QTE-M-WIND-15";  // 詠唱の風詠士(リーダー)
    private static final String EARTH_BERSERKER = "QTE-M-EARTH-18";     // 大地の狂戦士
    private static final String SHEER_AYAKASHI = "QTE-M-WIND-33";       // 透キ通ル・アヤカシ(★Batch 48)
    private static final String GIGAMOUSE_BITE = "QTE-M-WATER-38";      // ギガマウス・バイト(★Batch 49)
    private static final String TENGSUN = "QTE-M-LIGHT-34";             // 光霊・テングスン(★Batch 50)
    private static final String NYUKIRO = "QTE-M-LIGHT-30";             // 英霊・ニュウキロ(★Batch 53)

    // 動的な攻撃力・攻撃回数
    private static final String SHADOW_ASSASSIN = "QTE-M-WATER-28";     // 影潜む水刺客(ウェポン)
    private static final String ARKINTIS = "QTE-M-WATER-39";            // アルキンティス(ウェポン・★Batch 49)
    private static final String KNOWLEDGE_GUARDIAN = "QTE-M-WATER-5";   // 知識の守護者
    private static final String ENDLESS_TITAN = "QTE-M-EARTH-22";       // 無尽蔵の巨神
    private static final String GRAVE_WRAITH_MASS = "QTE-M-DARK-22";    // 墓場の怨念集合体
    private static final String OVERFLOWING_WISDOM = "QTE-M-WATER-12";  // 溢れ出る英知(オーラ)
    private static final String CYCLONE_FENCER = "QTE-M-WIND-5";        // サイクロン・フェンサー
    private static final String BOULDER_BARRAGE = "QTE-M-EARTH-19";     // 連撃の巨岩
    private static final String GALE_RAPIER = "QTE-M-WIND-14";          // 疾風のレイピア(ウェポン)
    private static final String DREAMY = "QTE-M-DARK-39";               // 1stL「NEMれぬ夜のドリーミー」(★Batch 50)

    /**
     * 勝阿外(★Batch 54)。【常在】「相手の手札の枚数このミニオンの攻撃力+1」。
     *
     * <p>★<b>{@link #IMPLEMENTED_CARDS} には入れない。</b>【賢魂：2】のほうで
     * {@code CardEffectRegistry.soulSpells} に載っており、{@code isRegistered} が既に真を返す。
     * 入れると、外しても何も落ちない宣言が増えるだけである(53 のノアと同じ理由)。
     */
    private static final String KATSUAGE = "QTE-M-EARTH-36";
    private static final String RENTA = "QTE-M-FIRE-31";                // 追撃鉄人連太(★Batch 52)
    private static final String MERINA = "QTE-M-DARK-32";               // サービスブレイク・メリィナ(★Batch 52)

    /**
     * サービスブレイク・メリィナのコストの下限。
     * 本文は「このカードのコストは2以下にならない」なので、下限は<b>3</b>である
     * (「0にはならない」を下限1と読んでいる【剛火の将】と同じ writing である)。
     */
    private static final int MERINA_MIN_COST = 3;

    /**
     * ★このカードは<b>数えられる対象</b>であって、このクラスが挙動を実装しているカードではない。
     * 群がる死霊王が「墓地にある《ゾンストライカー》の数」を読むために名前を知っているだけである。
     * したがって下の {@link #IMPLEMENTED_CARDS} には入れない。
     */
    private static final String ZOMB_STRIKER = "QTE-M-DARK-16";

    /**
     * このクラスが挙動を実装しているカード(★Batch 47)。
     * 趣旨と番人は {@link RuleGuards#IMPLEMENTED_CARDS} の説明を参照。
     */
    public static final Set<String> IMPLEMENTED_CARDS = Set.of(
            NIGHTMARE, SWARM_LICH, SEALED_TABOO_DEMON, RAISE_DEAD,
            CHANT_PALADIN, PRECEPT_GUARDIAN, WISDOM_CRYSTAL, CHANT_ORB,
            TWIN_ILLUSIONIST, GALE_KNIGHT, GATHERING_SYLPH, WIND_CHANTER_LEADER,
            EARTH_BERSERKER, SHEER_AYAKASHI, GIGAMOUSE_BITE, TENGSUN, NYUKIRO,
            SHADOW_ASSASSIN, ARKINTIS, KNOWLEDGE_GUARDIAN, ENDLESS_TITAN,
            GRAVE_WRAITH_MASS, OVERFLOWING_WISDOM, CYCLONE_FENCER, BOULDER_BARRAGE,
            GALE_RAPIER, DREAMY, RENTA, MERINA);

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
        return effectiveCost(state, owner, card, card.cost(), card.type());
    }

    /**
     * 【賢魂：n】として使う場合の現在コスト(★Batch 54。裁定152)。
     *
     * <blockquote>スペルとして使う場合は<b>スペルのコスト軽減などの影響を受ける</b>
     * (= ルール上「スペルの使用」として扱う)。</blockquote>
     *
     * ★<b>軽減・増加の規則を書き写していない。</b> 基準コストを n に、種別をスペルに
     * 差し替えて<b>同じ計算</b>へ流す。写すと、次にスペルのコスト軽減が増えたときに
     * 片方だけが直される(裁定163)。
     *
     * ★<b>キーワードによる軽減はそのまま乗る</b>(マスター裁定 A3)。
     * 《戒律のガーディアン》の「【守護】を持つカードのコスト-1」は、
     * 《グレイヴガールズファン》を賢魂として使うときにも効く ——
     * キーワードはカードが持つものであって、使い方で消えたりしない。
     *
     * @param soulCost カードテキストが持つ n({@code CardTextKeywords.soulCost})
     */
    public int effectiveSoulCost(GameState state, PlayerState owner, CardMaster card, int soulCost) {
        return effectiveCost(state, owner, card, soulCost, CardType.SPELL);
    }

    /**
     * コスト計算の本体。
     *
     * @param baseCost 基準となるコスト(通常は印刷コスト、賢魂として使うなら n)
     * @param asType   この使用をどの種別として扱うか(賢魂として使うなら SPELL)
     */
    private int effectiveCost(GameState state, PlayerState owner, CardMaster card,
            int baseCost, CardType asType) {
        int cost = baseCost;

        // 【剛火の将】の起動能力: 次に手札から使用する火文明ミニオンのコスト-1(0にはならない)
        if (owner.getPendingFireMinionDiscount() > 0
                && asType == CardType.MINION
                && card.civilization() == com.example.qte.master.Civilization.FIRE) {
            cost = Math.max(1, cost - 1);
        }
        // ---- 闇文明: 墓地・禁忌デッキ・生贄を参照する動的コスト ----
        // 悪夢: 墓地にあるスペル以外のカード1枚につきコスト-1
        if (NIGHTMARE.equals(card.id())) {
            cost -= nonSpellCountInTrash(owner);
        }
        // 群がる死霊王: 墓地にある「ゾンストライカー」の数だけコスト-1
        if (SWARM_LICH.equals(card.id())) {
            cost -= countInTrash(owner, ZOMB_STRIKER);
        }
        // 封印されし禁忌魔人: 禁忌デッキの残り枚数だけコスト+1(唯一のコスト増加カード)
        if (SEALED_TABOO_DEMON.equals(card.id())) {
            cost += owner.getTabooDeck().size();
        }
        // 死者蘇生: 使用宣言時に生贄にした自分のミニオンの数だけコスト-1
        if (RAISE_DEAD.equals(card.id())) {
            cost -= owner.getPendingSacrificeCount();
        }
        // ★Batch 52: サービスブレイク・メリィナ(進化)
        // 「このカードのコストは自分の場に居るミニオンの数-1される。このカードのコストは2以下にならない。」
        // ★<b>減る量は「自分の場に居るミニオンの数」そのもの</b>である(マスター裁定)。
        //   末尾の「-1」は《悪夢》の「1枚につきコスト-1」と同じ<b>軽減の書き方</b>であって、
        //   数から1を引くという意味ではない。
        // ★下限は 3 である —— 本文は「2以下にならない」と書いており、2 を含む。
        //   一般の下限(0)とこのカードの下限は別の規則なので、下の Math.max(0, cost) に任せず
        //   ここで直接当てる。
        // ★「場に居るミニオンの数」は<b>進化召喚で素材を外す前</b>の数である。
        //   コストの評価は手札にある間に行われ、素材はまだ場に居る(裁定190 と同じ形)
        if (MERINA.equals(card.id())) {
            cost = Math.max(MERINA_MIN_COST, cost - owner.getMinionZone().size());
        }
        // 悪夢: このターン中、ミニオンの召喚コストを-4する(サブフェイズに使用したときのみ付与される)
        if (asType == CardType.MINION && owner.getThisTurnAuras().contains(NIGHTMARE)) {
            cost -= 4;
        }
        // ---- 光文明: 場のミニオンによる常在のコスト軽減(累積する。下限は0) ----
        // 唱導の聖騎士(QTE-M-LIGHT-18)・戒律のガーディアン(QTE-M-LIGHT-20): 自分のスペルのコスト-1
        // 英知の水晶(QTE-M-LIGHT-19): 自分の【知識】カードのコスト-1
        // 戒律のガーディアン(QTE-M-LIGHT-20): 【守護】を持つカードのコスト-1
        for (MinionInstance minion : owner.getMinionZone()) {
            String id = minion.getMaster().id();
            boolean spellDiscounter = CHANT_PALADIN.equals(id) || PRECEPT_GUARDIAN.equals(id);
            if (spellDiscounter && asType == CardType.SPELL) {
                cost -= 1;
            }
            if (WISDOM_CRYSTAL.equals(id) && card.keywords().contains(Keyword.KNOWLEDGE)) {
                cost -= 1;
            }
            if (PRECEPT_GUARDIAN.equals(id) && card.keywords().contains(Keyword.GUARD)) {
                cost -= 1;
            }
        }
        // 詠唱の宝珠: 破壊された後、次に唱えるスペルのコスト-1(ターンをまたいで持続)
        if (asType == CardType.SPELL && owner.getPersistentAuras().stream()
                .anyMatch(aura -> CHANT_ORB.equals(aura.cardId()))) {
            cost -= 1;
        }
        // 双流の幻術師: 場に居るミニオンの数だけコスト-1。
        // Ver.0.4 で参照が「【知識】を持つミニオンの数」から「ミニオンの数」全体に広がった。
        // 側の限定が無いため両者の場を数える(記法規約。従来と同じ)
        if (TWIN_ILLUSIONIST.equals(card.id())) {
            long minionsOnBoard = java.util.stream.Stream
                    .of(state.getPlayer1(), state.getPlayer2())
                    .mapToLong(p -> p.getMinionZone().size())
                    .sum();
            cost -= (int) minionsOnBoard;
        }
        // ---- 風文明: ターン内カウンタ・盤面参照による動的コスト ----
        // 詠唱の疾風騎士: 自分がこのターン中にスペルを唱えるたびコスト-1(このターン限定・下限0)
        if (GALE_KNIGHT.equals(card.id())) {
            cost -= owner.getSpellsCastThisTurn();
        }
        // 結集する風の精: 自分の場にあるミニオンの合計コスト分コスト-1
        if (GATHERING_SYLPH.equals(card.id())) {
            cost -= owner.getMinionZone().stream()
                    .mapToInt(m -> m.getMaster().cost() == null ? 0 : m.getMaster().cost())
                    .sum();
        }
        // 透キ通ル・アヤカシ(★Batch 48): 自分の場にコスト2以上のミニオンが居るときコスト0。
        // 減算型ではなく固定値セット型である(大地の狂戦士と同じ形)。数えるのは印刷コストであり、
        // 場のミニオンには動的コストの概念が無い(コストが動くのは手札にある間だけ)。
        // 「自分の場」なので相手の場は見ない。自身はまだ手札にいるので数えようがない
        if (SHEER_AYAKASHI.equals(card.id()) && owner.getMinionZone().stream()
                .anyMatch(m -> m.getMaster().cost() != null && m.getMaster().cost() >= 2)) {
            cost = 0;
        }
        // 詠唱の風詠士(リーダー): そのターン中3枚目に使うミニオンかスペルのコスト-1。
        // 使用カウンタは自身を含まない(裁定1)ため、「3枚目」はcardsUsedThisTurn==2の瞬間に一致する
        if (WIND_CHANTER_LEADER.equals(owner.getLeader().id())
                && (asType == CardType.MINION || asType == CardType.SPELL)
                && owner.getCardsUsedThisTurn() == 2) {
            cost -= 1;
        }
        // ---- 水文明 Ver1.1: 手札の枚数を参照する動的コスト(★Batch 49) ----
        // ギガマウス・バイト: 「自分の手札の枚数このカードのコスト-1」(印刷コスト15)。
        // ★数える手札には<b>このカード自身を含む</b>(マスター裁定190)。
        //   このメソッドが呼ばれるのは支払いの直前であり、カードはまだ手札にある
        //   (GameService.playSpell は 検証 → payCost → removePlayedAndTargets の順)。
        //   したがって getHand().size() をそのまま読めば裁定どおりになる。
        // ★選んだ3体もこの時点ではまだ手札にあるので、選択の有無でコストは動かない
        if (GIGAMOUSE_BITE.equals(card.id())) {
            cost -= owner.getHand().size();
        }
        // ---- 光文明 Ver1.1(★Batch 50): 相手のスペルを重くする常在 ----
        // 光霊・テングスン: 「【常在】相手はスペルを唱えるコスト+1される。」
        // ★数えるのは<b>相手の場</b>に居るテングスンである。このメソッドの owner は
        //   「カードを使おうとしている側」なので、その相手の場を見る。
        // ★複数体並べば累積する —— 唱導の聖騎士(自分のスペル-1)が体数ぶん重なるのと対称であり、
        //   テキストにも「1体につき」を否定する語が無い。
        // ★封印されし禁忌魔人(コスト+1)以来2枚目のコスト増加である
        if (asType == CardType.SPELL) {
            PlayerState across = state.opponentOf(owner.getPlayerId());
            cost += (int) across.getMinionZone().stream()
                    .filter(m -> TENGSUN.equals(m.getMaster().id()))
                    .count();
            // ---- 光文明 Ver1.1(★Batch 53): 英霊・ニュウキロ(進化) ----
            // 「【常在】相手のスペルのコストは自分の手札の数コスト+1される」
            // ★<b>増える量は「自分の手札の数」そのもの</b>である(マスター裁定)。
            //   末尾の「+1」は《悪夢》の「1枚につきコスト-1」と同じ<b>増減の書き方</b>であって、
            //   数に1を足すのではない —— 《サービスブレイク・メリィナ》の「-1」を
            //   そう読んだ(裁定230)のと同じ規則である。
            // ★「自分の」はニュウキロの持ち主から見た自分、つまり<b>スペルを唱える側の相手</b>である。
            //   テングスンと同じく、このメソッドの owner は「カードを使おうとしている側」なので
            //   数えるのは across の手札になる。
            // ★複数体並べれば累積する(テングスンと同じ。「1体につき」を否定する語が無い)
            long nyukiro = across.getMinionZone().stream()
                    .filter(m -> NYUKIRO.equals(m.getMaster().id()))
                    .count();
            cost += (int) nyukiro * across.getHand().size();
        }
        // ---- 土文明: 自分のマナ枚数を参照する動的コスト(条件を満たすと固定値まで下がる) ----
        // 減算型ではなく固定値セット型。土カードは他文明の軽減対象ではないため競合しない。
        // ★Batch 55(区分3a): マナ条件 7→6枚(rework-triage.md)。【突進】【還元】はテキストから自動で付く
        if (EARTH_BERSERKER.equals(card.id()) && owner.getManaZone().size() >= 6) {
            cost = 1; // 大地の狂戦士: マナ6枚以上でコスト1
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
        if (SHADOW_ASSASSIN.equals(weapon.id())) {
            attack += (int) owner.getMinionZone().stream()
                    .filter(m -> m.hasKeyword(Keyword.STEALTH))
                    .count();
        }
        // アルキンティス(★Batch 49): 「【常在】自分の場の【知識】の枚数Attackを+1する」。
        // 影潜む水刺客(潜伏を数える)と同じ形で、数える対象だけが【知識】に変わる。
        // 【常在】は保存しない —— 評価するたびに場を見る(計画 2-1 の (a))
        if (ARKINTIS.equals(weapon.id())) {
            attack += (int) owner.getMinionZone().stream()
                    .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE))
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
        if (CYCLONE_FENCER.equals(minion.getMaster().id())) { // サイクロン・フェンサー
            max += 1;
        }
        if (BOULDER_BARRAGE.equals(minion.getMaster().id())) { // 連撃の巨岩: 1ターンに2回攻撃できる
            max += 1;
        }
        // ★Batch 52: 追撃鉄人連太(進化)「【常在】このカードは2回攻撃できる」。
        // 進化ミニオンであること自体は攻撃回数に関係しない —— サイクロン・フェンサーと同じ形である
        if (RENTA.equals(minion.getMaster().id())) {
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
        if (weapon != null && GALE_RAPIER.equals(weapon.id())) { // 疾風のレイピア
            return 2;
        }
        return 1;
    }

    public int effectiveAttack(GameState state, PlayerState owner, MinionInstance minion) {
        String cardId = minion.getMaster().id();
        int attack = minion.getMaster().attack();

        // ---- 動的SET(カード固有のルール) ----
        // 知識の守護者: 攻撃力は自分の手札の枚数と同じになる(常に連動)
        if (KNOWLEDGE_GUARDIAN.equals(cardId)) {
            attack = owner.getHand().size();
        }
        // 無尽蔵の巨神: 攻撃力は自分の手札の枚数と同じ(基礎0 + 手札枚数)
        if (ENDLESS_TITAN.equals(cardId)) {
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
        if (GRAVE_WRAITH_MASS.equals(cardId)) {
            attack += nonSpellCountInTrash(owner);
        }
        // 1stL「NEMれぬ夜のドリーミー」(★Batch 50):
        // 「【常在】このターン中破壊されたミニオン1体につきこのターンの間Attack+1」。
        // ★数えるのは<b>両者の合計</b>であり、破壊された後の行き先も問わない(マスター裁定205)。
        //   「自分の」と書いていない条件は両者を見る(裁定156(2))ので、
        //   天翔ケル霊鬼・シュテンと同じ GameState のカウンタを読む(裁定185)。
        // ★【常在】は保存しない —— 評価するたびに数える。ターンが変われば
        //   カウンタが 0 に戻る(GameService.beginTurn)ので、「このターンの間」は自然に満たされる。
        // ★自身の【召喚時】(他のミニオンを全て破壊する)で増えた分もここに含まれる
        if (DREAMY.equals(cardId)) {
            attack += state.getMinionsDestroyedThisTurn();
        }
        // ★Batch 52: 不敗鉄人闘太(進化)「【常在】このカードのAttackとHPは下にあるミニオン1枚につき+2」。
        // ★カードIDで分岐していないのは、加算量を {@link EvolutionSpec} が運んでいるためである
        //   (場に出るときに1度だけ写す)。読むたびに束を数えるので【常在】として正しく振る舞う。
        // ★HP 側は MinionInstance.getMaxHp にある(あちらが HP の唯一の出どころ)
        attack += minion.getStatPerUnderCard() * minion.getUnder().size();
        // ★Batch 52: サービスブレイク・メリィナ(進化)「【常在】自分の他のミニオンのAttack+1」。
        // 「他の」なので自分自身には乗らない。複数体並べば累積する(常在の既定の扱い)
        for (MinionInstance other : owner.getMinionZone()) {
            if (MERINA.equals(other.getMaster().id()) && other != minion) {
                attack += 1;
            }
        }
        // ★Batch 54: 勝阿外「【常在】…相手の手札の枚数このミニオンの攻撃力+1」。
        // ★《英霊・ニュウキロ》(相手のスペルのコスト + 自分の手札の数)と<b>同じ書き方</b>であり、
        //   「相手の手札1枚につき Attack +1」と読む(マスター裁定 B8-3)。
        // ★数えるのは<b>このミニオンの持ち主から見た相手</b>の手札である。
        //   ニュウキロの owner は「カードを使おうとしている側」だったが、
        //   こちらの owner は「このミニオンの持ち主」なので、素直に opponentOf でよい。
        // ★【常在】は保存しない —— 評価するたびに相手の手札を数える(裁定4 の形)
        if (KATSUAGE.equals(cardId)) {
            attack += state.opponentOf(owner.getPlayerId()).getHand().size();
        }

        // ---- 動的ADD(オーラ) ----
        // 溢れ出る英知: このターン中、手札の枚数分だけ自分の「水文明」ミニオンの攻撃力+1(常に連動)。
        // Ver.0.4 で対象が「自分のミニオンすべて」から水文明に限定された。
        // 判定を評価側に置いているのは、オーラ適用後に出たミニオンにも同じ基準を効かせるためである
        // (オーラは付与時点のスナップショットではなく、評価のたびに場を見る)
        for (String aura : owner.getThisTurnAuras()) {
            if (OVERFLOWING_WISDOM.equals(aura)
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
