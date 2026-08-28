package com.example.qte.effect;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.qte.game.DestructionCause;
import com.example.qte.game.GameState;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.Keyword;

/**
 * 「できる／できない」「起きる／起きない」を盤面全体から判定する層。
 *
 * <b>なぜこのクラスが必要になったか。</b>
 * 闇文明までのカードは「操作を追加する」ものが中心で、{@code GameActions} に
 * メソッドを足せば実装できた。光文明は逆に「既存の処理を止める・置き換える」
 * カードの集合であり、追加すべきなのは操作ではなく<b>判定点</b>である。
 *
 * <pre>
 *   平和の結界     : Attack3以上のミニオンは攻撃できない
 *   煌めきの盾     : このカードは攻撃できない
 *   創世神         : このミニオンはリーダーを攻撃できない
 *   ゾディアック   : 相手のリーダーは攻撃できない
 *   大天使ミカエル : 戦闘では破壊されない
 *   聖光の守護聖   : 相手の効果では破壊されない
 *   正義の御盾     : リーダーへのダメージを-1
 *   断罪の大天使   : 相手の3枚目以降のドローを墓地送りに置換
 *   断罪の聖導者   : 相手はスペルを唱えられない
 *   秩序の執行官   : 相手は特殊召喚を行えない
 *   戒律の聖堂騎士 : 相手はサブフェイズを行えない
 * </pre>
 *
 * 条件はばらばらだが、判定される場所は「攻撃宣言」「破壊」「ダメージ」「ドロー」
 * 「使用」「フェイズ進行」の6箇所に集約できる。それぞれの入口をこのクラスに集め、
 * カードごとの条件をここに差し込む形にした。
 *
 * <b>戻り値の設計。</b> 攻撃可否は理由の文字列(不可なら理由・可ならnull)を返す。
 * 例外を投げるのはルール本体(GameService)の仕事であり、判定層は判定だけを行う。
 * こうしておくと、同じ判定をビュー生成側から呼んでボタンを無効化することもできる。
 */
@Component
public class RuleGuards {

    // 攻撃を禁止するカード
    private static final String PEACE_BARRIER = "QTE-M-LIGHT-23";   // 平和の結界(Attack3以上は攻撃不可)
    private static final String GLEAM_SHIELD = "QTE-M-LIGHT-16";    // 煌めきの盾(自身は攻撃不可)
    private static final String GENESIS_IRIS = "QTE-M-LIGHT-25";    // 創世神(自身はリーダーを攻撃不可)
    private static final String ABSOLUTE_GAIA = "QTE-M-EARTH-23";   // 不動の絶対神ガイア(自身はリーダーを攻撃不可・土文明)
    /** 獄門の裁定者(★Batch 57。Ver1.1 で「このミニオンはリーダーを攻撃できない」が付いた・闇文明) */
    private static final String WARDEN_JUDGE = "QTE-M-DARK-23";
    private static final String ZODIAC = "QTE-M-LIGHT-8";          // ゾディアック(相手リーダーは攻撃不可)
    private static final String HAKUREI = "QTE-M-WIND-34";         // ハク霊(自身は攻撃不可・★Batch 48)
    private static final String KOKUREI = "QTE-M-WIND-35";         // コク霊(自身は攻撃不可・★Batch 48)
    private static final String SUPPORT_TANUKI = "QTE-M-FIRE-33";  // 支援盾機狸(自身は攻撃不可・★Batch 51)
    // 破壊・ダメージ・ドローを置換するカード
    private static final String MICHAEL = "QTE-M-LIGHT-7";         // 大天使ミカエル(戦闘時ダメージを受けない・★Batch 59)
    private static final String HOLY_PROTECTOR_AURA = "QTE-M-LIGHT-1"; // 聖光の守護聖(相手の効果で破壊されない)
    private static final String JUSTICE_SHIELD = "QTE-M-LIGHT-13";  // 正義の御盾(リーダーへのダメージ-1)
    private static final String JUDGEMENT_ANGEL = "QTE-M-LIGHT-24"; // 断罪の大天使(3枚目以降のドローを置換)
    private static final String MOANIRU = "QTE-M-LIGHT-36";        // 光霊・モアニール(登場とダメージの置換・★Batch 50)
    // 行動そのものを禁止するカード
    private static final String ORDER_ENFORCER = "QTE-M-LIGHT-4";  // 秩序の執行官(相手は特殊召喚不可)
    private static final String TEMPLE_KNIGHT = "QTE-M-LIGHT-6";   // 戒律の聖堂騎士(相手はサブフェイズ不可)
    private static final String KOREKI = "QTE-M-LIGHT-31";         // 英霊・コレキ(相手は1ターンに1体しか出せない・★Batch 53)

    /**
     * 勝阿外(★Batch 54)。【常在】「相手はスペルを唱えられない」をここに書いている。
     *
     * <p>★<b>それでも {@link #IMPLEMENTED_CARDS} には入れない。</b>
     * このカードは【賢魂：2】のほうで {@code CardEffectRegistry.soulSpells} に載っており、
     * {@code isRegistered(String)} が既に真を返すからである。
     * 入れると、外しても何も落ちない宣言が1つ増える(53 のノアと同じ理由。裁定176 の正しい読み方)。
     */
    private static final String KATSUAGE = "QTE-M-EARTH-36";

    /** 英霊・コレキが許すミニオンの登場数(「1度しか」) */
    private static final int KOREKI_ENTRY_LIMIT = 1;

    /** 断罪の大天使が置換を始めるドロー枚数(このターンのN枚目以降) */
    private static final int DRAW_REPLACE_FROM = 3;

    /**
     * このクラスが挙動を実装しているカード(★Batch 47)。
     *
     * <b>なぜ宣言が要るのか。</b> 効果の実装には2つの置き場所がある ——
     * {@link CardEffectRegistry} の表に載るものと、このクラスのように
     * <b>ルール側の判定点に直接書かれるもの</b>である(裁定164)。
     * 後者は表に現れないため、Java からは「実装済みかどうか」を判定できない。
     * 「効果が未実装」の印({@link EffectImplementation})が実装済みの44枚を
     * 未実装と誤って名指ししないよう、判定点を持つクラスが自分で名乗る。
     *
     * <b>★中身は上の定数そのものである。</b> 別の場所にIDを書き写さないこと ——
     * 書き写した瞬間、判定に使うIDと宣言のIDが食い違いうる2つの正になる。
     * 宣言し忘れは {@code tools/report_effects.py} が検出する
     * (コメントを除いたソースに現れるカードIDのうち、登録にも宣言にも無いものがあれば止まる)。
     */
    public static final Set<String> IMPLEMENTED_CARDS = Set.of(
            PEACE_BARRIER, GLEAM_SHIELD, GENESIS_IRIS, ABSOLUTE_GAIA, ZODIAC, WARDEN_JUDGE,
            MICHAEL, HOLY_PROTECTOR_AURA, JUSTICE_SHIELD, JUDGEMENT_ANGEL,
            ORDER_ENFORCER, TEMPLE_KNIGHT, HAKUREI, KOKUREI, MOANIRU, SUPPORT_TANUKI,
            KOREKI);

    private final StatCalculator stats;

    public RuleGuards(StatCalculator stats) {
        this.stats = stats;
    }

    // ---------------------------------------------------------------
    // 攻撃宣言
    // ---------------------------------------------------------------

    /**
     * ミニオンが攻撃できない理由を返す。攻撃できるならnull。
     *
     * 従来 GameService.validateAttack に直書きされていた判定(攻撃回数・凍結・召喚酔い)も
     * ここへ移した。判定の置き場所が2つに分かれていると、カードが増えるたびに
     * 「どちらに書くか」を毎回考えることになるためである。
     *
     * @param targetIsLeader 攻撃対象がリーダーならtrue
     */
    public String minionAttackDenial(GameState state, PlayerState owner, MinionInstance attacker,
            boolean targetIsLeader) {
        if (attacker.getAttacksUsedThisTurn() >= stats.maxAttacks(state, owner, attacker)) {
            return "このミニオンはこのターン既に攻撃しています";
        }
        // 起動能力でタップしたミニオンは攻撃できない(裁定4)。
        // 【守護】【潜伏】はタップ中も機能するため、キーワードの評価には手を入れていない
        if (attacker.isTapped()) {
            return "このミニオンはタップ状態のため攻撃できません";
        }
        if (attacker.getCannotAttackOnTurn() == state.getTurnNumber()) {
            return "このミニオンは凍結していて攻撃できません";
        }
        if (attacker.getEnteredTurn() == state.getTurnNumber()) {
            // ★Batch 52: 進化ミニオンは出したターンからリーダーにもミニオンにも攻撃できる
            // (裁定157(1))。カード固有の性質ではなく<b>種別の規則</b>なので、
            // IMPLEMENTED_CARDS には現れない —— 名乗るべきカードが1枚も無い。
            boolean allowed = attacker.isEvolution()
                    || attacker.hasKeyword(Keyword.HASTE)
                    || (attacker.hasKeyword(Keyword.RUSH) && !targetIsLeader);
            if (!allowed) {
                return targetIsLeader
                        ? "出たターンにリーダーへ攻撃できるのは【速攻】持ちのみです"
                        : "出たターンのミニオンは攻撃できません(【速攻】【突進】を除く)";
            }
        }
        // ---- 光文明: カードによる攻撃の禁止 ----
        if (GLEAM_SHIELD.equals(attacker.getMaster().id())) {
            return "【煌めきの盾】は攻撃できません";
        }
        // ---- 風文明(★Batch 48): 自身は攻撃できない ----
        // ハク霊・コク霊は「ターンのはじめに自壊して相方を呼ぶ」ための器であり、
        // 壁として立つことはできても殴ることはできない
        if (HAKUREI.equals(attacker.getMaster().id())) {
            return "【ハク霊】は攻撃できません";
        }
        if (KOKUREI.equals(attacker.getMaster().id())) {
            return "【コク霊】は攻撃できません";
        }
        // ---- 火文明(★Batch 51): 自身は攻撃できない ----
        // 支援盾機狸は「【守護】を持つが殴れない0コストの壁」であり、
        // 煌めきの盾・ハク霊・コク霊と同じ形である(自分のリーダーを削る代償つき)
        if (SUPPORT_TANUKI.equals(attacker.getMaster().id())) {
            return "【支援盾機狸】は攻撃できません";
        }
        if (targetIsLeader && GENESIS_IRIS.equals(attacker.getMaster().id())) {
            return "【創世神 ゾディアックアイリス】はリーダーを攻撃できません";
        }
        if (targetIsLeader && ABSOLUTE_GAIA.equals(attacker.getMaster().id())) {
            return "【不動の絶対神ガイア】はリーダーを攻撃できません";
        }
        // ★Batch 57: 獄門の裁定者(Ver1.1)。9/9/9 と引き換えにリーダーを殴れない
        if (targetIsLeader && WARDEN_JUDGE.equals(attacker.getMaster().id())) {
            return "【獄門の裁定者】はリーダーを攻撃できません";
        }
        // 平和の結界は敵味方を問わず、Attack3以上の全てのミニオンを止める(自身も含む)
        if (isOnAnyField(state, PEACE_BARRIER)
                && stats.effectiveAttack(state, owner, attacker) >= 3) {
            return "【平和の結界】によりAttack3以上のミニオンは攻撃できません";
        }
        // ★★★Batch 74(裁定328): ここに在った《天界の守護神 ゾディアック》の判定を外した。
        // 本文は「相手のリーダー<b>は</b>攻撃できない」であり、「は」は主語である ——
        // 禁じているのは<b>リーダーが攻撃側になること</b>だけで、
        // 「相手のミニオンが自分のリーダーを狙えない」ことは1文字も書いていない。
        // ★外した実害は【貫通】持ちにだけ現れる。この判定は貫通による守護の無視
        // (GameService.validateAttack の後半)より<b>先に</b>走っていたので、
        // 貫通を持つミニオンがゾディアックを飛び越えてリーダーを殴れなかった。
        // ★残っているのは leaderAttackDenial 側(= リーダーが攻撃側になれない)である。
        // ---- 光文明(★Batch 50): 場全体で合計1回しか攻撃できない(英術・バンユー) ----
        // ★「ミニオン1体につき1回」ではなく<b>プレイヤーの場全体で1回</b>である(マスター裁定200)。
        // 個体の攻撃回数(いちばん上の maxAttacks の判定)とは数えている量が違うため、
        // 判定も別に置いている
        if (owner.getMinionAttackLimitedOnTurn() == state.getTurnNumber()
                && owner.getMinionAttacksUsedThisTurn() >= 1) {
            return "【英術・バンユー】の効果でこのターンはミニオンで1度しか攻撃できません";
        }
        return null;
    }

    // ---------------------------------------------------------------
    // 登場の置換(★Batch 50。光霊・モアニール)
    // ---------------------------------------------------------------

    /**
     * このミニオンが場に出る代わりに山札の下へ置かれるか(光霊・モアニール)。
     *
     * <blockquote>【常在】相手は自身のマナよりコストの大きいミニオンを場に出すとき、
     * 代わりに山札の下に置く。</blockquote>
     *
     * <b>「自身のマナ」はマナゾーンの枚数である</b>(マスター裁定201)。タップ・向きは問わない。
     * 使用可能MP(アンタップの枚数)で測ると、通常召喚は支払い直後に必ず0になるため
     * <b>ほぼ全てのミニオンが山札の下へ行く</b>ことになり、テキストの意図から外れる。
     *
     * <b>比べるのは印刷コストである。</b> 場に出るミニオンには動的コストの概念が無く
     * (コストが動くのは手札にある間だけ。透キ通ル・アヤカシの説明と同じ)、
     * 蘇生や効果による「出す」では手札を経由しないため、実効コストを引く先が無い。
     *
     * <b>誰が制限を受けるか。</b> テキストの「相手」はモアニールの持ち主から見た相手である。
     * したがって「場に出ようとしているミニオンの持ち主から見て、<b>相手の場</b>にモアニールが居る」
     * ときに置換が起きる。
     */
    public boolean isEntryToDeckBottom(GameState state, PlayerState owner,
            com.example.qte.master.CardMaster master) {
        if (!hasOnField(state.opponentOf(owner.getPlayerId()), MOANIRU)) {
            return false;
        }
        Integer cost = master.cost();
        return cost != null && cost > owner.getManaZone().size();
    }

    /**
     * リーダーへのダメージを肩代わりできるミニオン<b>すべて</b>(光霊・モアニール)。
     *
     * <blockquote>自分のリーダーがダメージを受けるとき代わりにこのカードを破壊する。</blockquote>
     *
     * <b>戦闘・効果を問わず、すべてのダメージが対象である</b>(マスター裁定202)。
     * 肩代わりが起きるとダメージは0になり、モアニール1体が破壊される。
     * 複数体並んでいれば、ダメージ1回につき1体ずつ消費される。
     *
     * <h2>★★★Batch 76(裁定348): 「先頭の1体」から「候補すべて」へ変えた</h2>
     *
     * 75 までここは {@code findFirst()} で<b>盤面の並び順の先頭</b>を返しており、
     * Javadoc は「どれが消えても盤面の結果は同じであり、プレイヤーに選ばせる意味が無い」と
     * 書いていた —— <b>強化を受けた個体と素の個体が並んでいれば、結果は同じではない</b>。
     * ★<b>「同じである」という前提のほうが誤っていた</b>(73 の教訓・前提)。
     *
     * <p>★どれを壊すかを問うのは {@code GameActions.tryReplaceLeaderDamageWithGuardian} である ——
     * <b>破壊そのものはここでは行わない。</b>このクラスは判定層であり、
     * 状態を変えるのは {@code GameActions} の仕事である(このクラスの冒頭の設計方針)。
     *
     * @return 候補の一覧(盤面の並び順)。1体も居なければ空
     */
    public List<MinionInstance> leaderDamageInterceptors(PlayerState target) {
        return target.getMinionZone().stream()
                .filter(m -> MOANIRU.equals(m.getMaster().id()))
                .toList();
    }

    /**
     * リーダー(ウェポン)が攻撃できない理由を返す。攻撃できるならnull。
     *
     * Batch 12a で、GameService.leaderAttack に直書きされていた3判定
     * (未装備・攻撃済み・凍結)をここへ移した。ミニオン側が Batch 11a で判定層へ
     * 移されたときに取り残されていたものであり、設計判断34の趣旨に反していた。
     * ここへ集めたことで、ビュー(ボタンの活性判定)が検証と同じ判定を呼べるようになり、
     * 「押せるのに弾かれる」ズレが構造的に起きなくなる。
     */
    public String leaderAttackDenial(GameState state, PlayerState owner) {
        if (owner.getEquippedWeapon() == null) {
            return "戦闘を行えるのはウェポンを装備したリーダーのみです";
        }
        if (owner.getLeaderAttacksUsedThisTurn() >= stats.maxLeaderAttacks(state, owner)) {
            return "リーダーはこのターン既に攻撃しています";
        }
        if (owner.getLeaderCannotAttackOnTurn() == state.getTurnNumber()) {
            return "リーダーは凍結していて攻撃できません";
        }
        if (hasOnField(state.opponentOf(owner.getPlayerId()), ZODIAC)) {
            return "【天界の守護神 ゾディアック】がいるためリーダーは攻撃できません";
        }
        return null;
    }

    // ---------------------------------------------------------------
    // 破壊の置換
    // ---------------------------------------------------------------

    /**
     * この破壊が無効化されるか。
     *
     * <b>「相手の効果による破壊」の判定について。</b>
     * 破壊処理は「誰の効果が原因か」を引数として持っていない。全ての破壊呼び出しに
     * 実行者を足すとカード28枚分の記述が変わるため、代わりに次の推定を用いる。
     *
     * <pre>
     *   効果による破壊で、破壊されるミニオンの持ち主がターンプレイヤーでない
     *     → その破壊はターンプレイヤー(=持ち主から見た相手)の効果によるもの
     * </pre>
     *
     * 現在のカードプールでは、自分のミニオンを破壊する効果(絶望の連鎖・生贄を求める邪鬼・
     * 死者蘇生・這い寄る生霊の自壊)は全て自分のターンに発動するため、この推定は正しく働く。
     * 相手のターン中に自分のミニオンを自ら破壊するカードが登場した場合は、
     * 破壊処理に実行者を渡す方式へ切り替える必要がある。
     */
    public boolean isDestructionPrevented(GameState state, PlayerState owner, MinionInstance minion,
            DestructionCause cause) {
        // ★Batch 59(区分5): 大天使ミカエルはここに居ない。
        // 旧「【守護】戦闘では破壊されない(ダメージは受ける)」は<b>破壊の置換</b>だったが、
        // 新「【守護】戦闘時ダメージを受けない」は<b>ダメージの置換</b>である
        // (裁定272)。判定は preventsCombatDamage が持つ。
        // 聖光の守護聖: 相手のカードや能力の効果による破壊を防ぐ(戦闘破壊は防げない)
        if (cause == DestructionCause.EFFECT && hasPersistentAura(owner, HOLY_PROTECTOR_AURA)) {
            return causedByOpponent(state, owner);
        }
        return false;
    }

    /**
     * このリーダーの破壊が無効化されるか(★★★Batch 74。裁定335)。
     *
     * <p>《聖光の守護聖》の本文は「次の相手のターン終了時まで、
     * <b>自分のリーダーと</b>自分のミニオンすべては『相手のカードや能力の効果で破壊されない』を得る」
     * である。★73 まで、オーラは<b>プレイヤーに付いている</b>のに、
     * 参照点が {@link #isDestructionPrevented}(ミニオン破壊)1本しか無かったため、
     * <b>本文の「自分のリーダーと」は実装のどこにも現れていなかった</b>。
     *
     * <h2>★★★これは「呼び出し元の無い関門」である</h2>
     * <b>現行の235枚に、リーダーを破壊するカードは1枚も無い。</b>
     * {@code GameActions} にも {@code GameService} にも {@code destroyLeader} に相当する入口は無く、
     * リーダーが場を去る道は「LPが0以下になる」1本だけである。
     * したがってこの関門を通る本物の経路は<b>存在しない</b> ——
     * 番人は {@code RuleGuards} を直接叩くしかない。
     *
     * <p>★<b>それでも実装したのはマスターの裁定である</b>(裁定335)。
     * 「いつ直すか」を書いても実装は自分では動かない(66 の教訓・<b>予定</b>)以上、
     * 「リーダーを破壊するカードが増えたら思い出す」は当てにできない。
     * ★★<b>ただし器だけを作ったのではない。</b>「相手由来か」の推定は
     * {@link #causedByOpponent} に切り出してミニオン側と共有しており、
     * 規則は1箇所のままである(裁定130)。
     * ★★★<b>《英霊・コレキ》《風弾の跳弾》《悪夢》に続く「本物の入口から観測できない挙動」の4件目</b>
     * として {@code notes/qte-pitfalls.md} に書き残した。
     */
    public boolean isLeaderDestructionPrevented(GameState state, PlayerState owner) {
        return hasPersistentAura(owner, HOLY_PROTECTOR_AURA) && causedByOpponent(state, owner);
    }

    /**
     * この破壊が「相手のカードや能力によるもの」か(上の推定の実体・★Batch 74 で切り出した)。
     * ミニオン側とリーダー側で同じ推定を使うため、規則を1箇所に置く(裁定130)。
     */
    private boolean causedByOpponent(GameState state, PlayerState owner) {
        return !owner.getPlayerId().equals(state.getTurnPlayerId());
    }

    // ---------------------------------------------------------------
    // ダメージ・ドローの置換
    // ---------------------------------------------------------------

    /** リーダーが実際に受けるダメージ量(正義の御盾による軽減後。下限0) */
    /**
     * このミニオンが戦闘ダメージを受けないか(★Batch 59・区分5)。
     *
     * <pre>
     *   旧: 「【守護】戦闘では破壊されない(ダメージは受ける)。」
     *   新: 「【守護】戦闘時ダメージを受けない。」
     * </pre>
     *
     * ★<b>置換される層が破壊からダメージへ1段下がった。</b>結果として見た目は似ているが、
     * 中身は別物である ——
     * <ul>
     *   <li>旧: HPは削れる。0以下のまま場に残り、<b>次の効果ダメージや効果破壊で落ちる</b>。</li>
     *   <li>新: HPが<b>削れない</b>。何度戦闘しても満身のままである。</li>
     * </ul>
     *
     * ★<b>「ダメージを受けたとき」に反応する誘発は発動しない</b>(マスター裁定272)。
     * 受けるダメージが0になるのだから、ダメージを受けた事実そのものが無い ——
     * 《獄門の裁定者》の【守護】被ダメージ誘発がその代表である。
     * この読みを実装として保証するために、<b>ダメージの適用そのものを止める</b>形にしてある
     * (量を0にして先へ進めると、適用側の {@code amount <= 0} で止まる作りに依存してしまう)。
     *
     * ★<b>効果ダメージは通る。</b>本文が「戦闘時」と限定しているためである(裁定211)。
     * ★<b>攻撃側・防御側のどちらでも受けない。</b>本文はどちらかに限定していない。
     */
    public boolean preventsCombatDamage(MinionInstance minion) {
        return MICHAEL.equals(minion.getMaster().id());
    }

    public int reduceLeaderDamage(GameState state, PlayerState target, int amount) {
        if (target.getEquippedWeapon() != null
                && JUSTICE_SHIELD.equals(target.getEquippedWeapon().id())) {
            return Math.max(0, amount - 1);
        }
        return amount;
    }

    /**
     * このドローが「引く代わりに墓地へ置く」に置換されるか(断罪の大天使)。
     * 数えるのはターンごとであり、ターン開始時の通常ドローも1枚目として含む。
     *
     * @param drawnSoFar このドローの直前までに、このターンで引いた枚数
     */
    public boolean isDrawReplaced(GameState state, PlayerState drawer, int drawnSoFar) {
        return hasOnField(state.opponentOf(drawer.getPlayerId()), JUDGEMENT_ANGEL)
                && drawnSoFar + 1 >= DRAW_REPLACE_FROM;
    }

    // ---------------------------------------------------------------
    // 行動の禁止
    // ---------------------------------------------------------------

    /**
     * スペルを唱えられない理由。唱えられるなら null。
     *
     * <h2>★Batch 54: 賢魂としての使用もここを通る(裁定152)</h2>
     *
     * 「その使用はルール上『スペルの使用』として扱う」以上、
     * <b>スペルを封じるものは賢魂も封じる</b>(マスター裁定 A2(3))。
     * 判定を1箇所に置いてあるので、呼ぶ側が姿を区別する必要はない。
     *
     * <h2>2枚の封じ方は違う</h2>
     *
     * <ul>
     * <li>《断罪の聖導者》は<b>ターンの刻印</b>である。効果を受けたそのターンだけ封じられる。</li>
     * <li>★《勝阿外》は<b>【常在】</b>である。場に居るあいだ、相手はずっと唱えられない
     *     (マスター裁定 B8-1)。状態として保存せず、問われるたびに場を見る ——
     *     保存すると、場を離れたときに解除し忘れる(落とし穴「【常在】は保存しない」)。</li>
     * </ul>
     */
    public String spellDenial(GameState state, PlayerState player) {
        if (player.getSpellSealedOnTurn() == state.getTurnNumber()) {
            return "【断罪の聖導者】の効果でこのターンはスペルを唱えられません";
        }
        if (hasOnField(state.opponentOf(player.getPlayerId()), KATSUAGE)) {
            return "相手の【勝阿外】の効果でスペルを唱えられません";
        }
        return null;
    }

    /**
     * このプレイヤーがミニオンを場に出せない理由(★Batch 53。《英霊・コレキ》)。出せるなら null。
     *
     * <blockquote>【常在】相手は自身のターン中1度しかミニオンを場に出せない</blockquote>
     *
     * <h2>「場に出す」はあらゆる登場を数える(マスター裁定)</h2>
     *
     * 通常召喚・進化召喚・特殊召喚・墓地からの召喚・効果による「出す」(蘇生・手札から出す・
     * マナから出す)を<b>すべて合わせて</b>、そのターンに1体までである。
     * 裁定193(「場に出る」は経路を問わない)に揃えた読みであり、
     * 数えるのは {@link PlayerState#countMinionEntry(int)} 1箇所である。
     *
     * <h2>「自身のターン中」の限定は本文どおりに写す(裁定211)</h2>
     *
     * 制限を受けるのは<b>その人が手番のとき</b>だけである。相手のターン中に起きる登場
     * (【破壊時】の蘇生・カムバックキーパーの自力復帰)は数に入るが、止められない。
     *
     * <h2>「1体だけ出て残りは出ない」(マスター裁定)</h2>
     *
     * 「ミニオンを3体場に出す」効果に当たったときは、1体目だけが出て残りは出ない。
     * <b>場が満杯のときとまったく同じ形</b>であり、出せなかったぶんは手札に戻る
     * (神の福音・ギガマウス・バイトの既存の扱い)。だからこの判定は
     * {@code GameActions.isFieldEntryBlocked} に合流させてあり、
     * 呼び出し側は「満杯か」と「コレキか」を区別しない。
     */
    public String minionEntryDenial(GameState state, PlayerState owner) {
        if (!owner.getPlayerId().equals(state.getTurnPlayerId())) {
            return null; // 「自身のターン中」に限る
        }
        if (!hasOnField(state.opponentOf(owner.getPlayerId()), KOREKI)) {
            return null;
        }
        if (owner.minionEntriesOn(state.getTurnNumber()) >= KOREKI_ENTRY_LIMIT) {
            return "【英霊・コレキ】の効果でこのターンはミニオンを1体しか場に出せません";
        }
        return null;
    }

    /** 特殊召喚を行えない理由(秩序の執行官)。行えるならnull */
    public String specialSummonDenial(GameState state, PlayerState player) {
        if (hasOnField(state.opponentOf(player.getPlayerId()), ORDER_ENFORCER)) {
            return "【秩序の執行官】の効果で【特殊召喚】を行えません";
        }
        return null;
    }

    /** サブフェイズを行えるか(戒律の聖堂騎士)。行えない場合はスキップする */
    public boolean canEnterSubPhase(GameState state, PlayerState player) {
        return !hasOnField(state.opponentOf(player.getPlayerId()), TEMPLE_KNIGHT);
    }

    // ---------------------------------------------------------------
    // 補助
    // ---------------------------------------------------------------

    /** 指定プレイヤーの場に、そのカードIDのミニオンがいるか */
    public boolean hasOnField(PlayerState player, String cardId) {
        return player.getMinionZone().stream()
                .anyMatch(m -> cardId.equals(m.getMaster().id()));
    }

    /** どちらかの場に、そのカードIDのミニオンがいるか(敵味方を問わない効果用) */
    private boolean isOnAnyField(GameState state, String cardId) {
        return hasOnField(state.getPlayer1(), cardId) || hasOnField(state.getPlayer2(), cardId);
    }

    /** 持続効果を持っているか */
    public boolean hasPersistentAura(PlayerState player, String cardId) {
        return player.getPersistentAuras().stream()
                .anyMatch(a -> a.cardId().equals(cardId));
    }
}
