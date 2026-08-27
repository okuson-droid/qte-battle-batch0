package com.example.qte.effect;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.qte.game.GameState;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.master.Keyword;

/**
 * 対象指定の絞り込みを1箇所に持つクラス(★Batch 68 で新設)。
 *
 * <h2>なぜ切り出したのか</h2>
 *
 * 裁定282 により、【召喚時】【登場時】の対象は<b>ミニオンが場に出てから</b>選ぶことになった。
 * 選ぶ場所が「使用の宣言」から「効果の解決中の割り込み」へ移る、ということである。
 *
 * <p>ところが 67 までの割り込み({@link PendingChoice})は<b>候補をサーバが手で列挙する</b>形で
 * あり、{@link TargetSpec.Filter} を持っていなかった。15枚をそのまま移すと、
 * <b>同じ絞り込みの規則が「宣言時の検証」と「割り込みの候補列挙」の2箇所に生まれる</b>
 * (裁定130 が戒めた形そのものである)。
 *
 * <p>そこで規則をこのクラス1つに集め、<b>2つの使い方</b>を持たせた。
 *
 * <ul>
 *   <li><b>検証</b>({@code GameService.validateTargets})…… 選ばれたものが要求を満たすか
 *   <li><b>列挙</b>({@code CardEffectRegistry.fire})…… 要求を満たすものは今の盤面にどれか
 * </ul>
 *
 * <p>★<b>判定は {@link #rejectReason} ただ1つである。</b>検証はその戻り値を例外にし、
 * 列挙は null かどうかだけを見る —— <b>断る文言も1箇所にある</b>ことになる
 * (文言で照合している試験が壊れないのはそのためである)。
 *
 * <h2>★このクラスは盤面を1文字も変えない</h2>
 *
 * 唯一の例外が {@link #selectionFrom} の {@code Kind.HAND} である ——
 * 手札から選ばれたカードは<b>手札から取り除いた状態</b>で効果へ渡すという規約が
 * Batch 4 から続いており(行き先は効果が決める)、それに従っている。
 */
@Component
public class TargetCandidates {

    private final CardMasterRepository cards;
    private final StatCalculator stats;

    public TargetCandidates(CardMasterRepository cards, StatCalculator stats) {
        this.cards = cards;
        this.stats = stats;
    }

    // ---------------------------------------------------------------
    // 1. 判定 —— 絞り込みの規則はここだけにある
    // ---------------------------------------------------------------

    /**
     * この要求の絞り込みに引っかかる理由。通るなら null。
     *
     * @param viewer 選ぶ側のプレイヤー(Side と潜伏の判定の基準)
     * @param minion 場のミニオンを見ているなら実体。手札・墓地なら null
     */
    public String rejectReason(GameState state, PlayerState viewer, TargetSpec.Requirement req,
            CardMaster master, MinionInstance minion) {
        for (TargetSpec.Filter filter : req.filters()) {
            String reason = switch (filter) {
                case KNOWLEDGE -> keywordReason(master, minion, Keyword.KNOWLEDGE, "【知識】");
                case GUARD -> keywordReason(master, minion, Keyword.GUARD, "【守護】");
                // ★★★Batch 74(裁定341): 進化ミニオンもミニオンである(裁定310・総合ルール2-1)。
                // 73 まで、この1行だけが {@code == CardType.MINION} のままだった ——
                // すぐ下の NON_MINION_CARD は Batch 67 の時点で {@code isMinion()} に直っており、
                // <b>1つのクラスの中で表と裏の判定が食い違っていた</b>。
                // ★弾いていたのは「進化を出すと素材ゼロで場に立つ」からであって(裁定308(b) の暫定)、
                //   種別の読みが違ったからではない。74 は素材を選ばせる段を作って暫定を外した。
                case MINION_CARD -> master.type().isMinion() ? null
                        : "ミニオンカードを選んでください";
                case HP_5_OR_LESS -> {
                    // 現在HPで判定する(ダメージを受けた大型ミニオンも対象になる)
                    int hp = minion != null ? minion.getCurrentHp()
                            : (master.hp() == null ? Integer.MAX_VALUE : master.hp());
                    yield hp <= 5 ? null : "HP5以下のミニオンを選んでください";
                }
                case COST_4_OR_LESS -> costAtMost(master, 4);
                case COST_3_OR_LESS -> costAtMost(master, 3);
                case COST_7_OR_LESS -> costAtMost(master, 7);
                case SPELL_CARD -> master.type() == CardType.SPELL ? null
                        : "スペルカードを選んでください";
                // ★Batch 67: 進化ミニオンもミニオンである(CardType.isMinion 1箇所で判定する)
                case NON_MINION_CARD -> master.type().isMinion()
                        ? "ミニオンでないカードを選んでください" : null;
                case LIGHT_CIVILIZATION -> civilization(master, Civilization.LIGHT, "光");
                case WATER_CIVILIZATION -> civilization(master, Civilization.WATER, "水");
                case WIND_CIVILIZATION -> civilization(master, Civilization.WIND, "風");
                case HIGHEST_ATTACK_OPPONENT -> {
                    // ホーリー・シグナル: 相手の場で現在攻撃力が最も高いミニオンだけを選べる
                    PlayerState targetSide = state.opponentOf(viewer.getPlayerId());
                    int max = targetSide.getMinionZone().stream()
                            .mapToInt(m -> stats.effectiveAttack(state, targetSide, m))
                            .max().orElse(Integer.MIN_VALUE);
                    int thisAttack = minion != null ? stats.effectiveAttack(state, targetSide, minion)
                            : Integer.MIN_VALUE;
                    yield thisAttack >= max ? null
                            : "相手の場で最も攻撃力の高いミニオンを選んでください";
                }
                // 絞り込みではなく潜伏チェックの上書き指示(下の isStealthBlocked が見る)
                case IGNORES_STEALTH -> null;
                case EVOLUTION_MINION -> master.type() == CardType.EVOLUTION ? null
                        : "進化ミニオンを選んでください";
            };
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private String keywordReason(CardMaster master, MinionInstance minion, Keyword keyword, String label) {
        // 場のミニオンは付与されたキーワードも含めて判定する
        boolean has = minion != null ? minion.hasKeyword(keyword) : master.hasKeyword(keyword);
        return has ? null : label + "を持つカードを選んでください";
    }

    private String costAtMost(CardMaster master, int max) {
        return master.cost() != null && master.cost() <= max ? null
                : "コスト%d以下のカードを選んでください".formatted(max);
    }

    private String civilization(CardMaster master, Civilization civ, String label) {
        return master.civilization() == civ ? null : label + "文明のカードを選んでください";
    }

    /**
     * 【潜伏】により、この要求ではこのミニオンを対象にできないか。
     *
     * <p>★潜伏は「<b>相手の</b>カードや能力の対象にならない」である。自分のミニオンは
     * 潜伏を持っていても自分で対象にできる。{@code IGNORES_STEALTH} を持つ要求
     * (《ホーリー・シグナル》)だけがこれを上書きする。
     */
    public boolean isStealthBlocked(PlayerState viewer, TargetSpec.Requirement req,
            PlayerState owner, MinionInstance minion) {
        return owner != viewer && minion.hasKeyword(Keyword.STEALTH)
                && !req.filters().contains(TargetSpec.Filter.IGNORES_STEALTH);
    }

    // ---------------------------------------------------------------
    // 2. 列挙 —— 今の盤面で、この要求を満たすものはどれか
    // ---------------------------------------------------------------

    /**
     * この要求を満たす候補を、今の盤面から列挙する(★Batch 68)。
     *
     * <p>戻り値の形は {@link PendingChoice} の候補と同じである ——
     * HAND / TRASH / MANA はゾーン内の位置、MINION は instanceId、
     * WEAPON は "SELF" / "OPPONENT"。
     *
     * <p>★<b>「選べないものを最初から入れない」</b>のが割り込みの規約であり(64)、
     * ここもそれに従う。候補が空なら、呼び出し側は問い合わせずに不発とする
     * (裁定302: 成立しない選択肢は並べない)。
     *
     * <p>★★<b>そのミニオン自身も候補に入る。</b>裁定305 が
     * 《生贄を求める邪鬼》について「自分自身も『自分のミニオン2体』に数える」と定めており、
     * 本文が「自分の<b>他の</b>」と限定していない限り除外しない(裁定156(2))。
     * 67 までは<b>場に出る前に選んでいたので、構造的に自身が候補に入らなかった</b> ——
     * 裁定282 の実装で初めてこの裁定どおりの盤面が作れるようになった。
     */
    public List<String> candidatesFor(GameState state, PlayerState viewer,
            TargetSpec.Requirement req) {
        List<String> out = new ArrayList<>();
        switch (req.kind()) {
            case HAND -> {
                for (int i = 0; i < viewer.getHand().size(); i++) {
                    CardMaster master = cards.findById(viewer.getHand().get(i));
                    if (rejectReason(state, viewer, req, master, null) == null) {
                        out.add(String.valueOf(i));
                    }
                }
            }
            case TRASH -> {
                for (int i = 0; i < viewer.getTrash().size(); i++) {
                    CardMaster master = cards.findById(viewer.getTrash().get(i));
                    if (rejectReason(state, viewer, req, master, null) == null) {
                        out.add(String.valueOf(i));
                    }
                }
            }
            case MANA -> {
                for (int i = 0; i < viewer.getManaZone().size(); i++) {
                    out.add(String.valueOf(i));
                }
            }
            case MINION -> {
                for (PlayerState side : sidesFor(state, viewer, req.side())) {
                    for (MinionInstance minion : side.getMinionZone()) {
                        if (isStealthBlocked(viewer, req, side, minion)) {
                            continue;
                        }
                        if (rejectReason(state, viewer, req, minion.getMaster(), minion) == null) {
                            out.add(minion.getInstanceId());
                        }
                    }
                }
            }
            case WEAPON -> {
                for (PlayerState side : sidesFor(state, viewer, req.side())) {
                    if (side.getEquippedWeapon() != null) {
                        out.add(side == viewer ? "SELF" : "OPPONENT");
                    }
                }
            }
        }
        return out;
    }

    private List<PlayerState> sidesFor(GameState state, PlayerState viewer, TargetSpec.Side side) {
        PlayerState opponent = state.opponentOf(viewer.getPlayerId());
        return switch (side) {
            case SELF -> List.of(viewer);
            case OPPONENT -> List.of(opponent);
            case ANY -> List.of(viewer, opponent);
        };
    }

    // ---------------------------------------------------------------
    // 3. 解決 —— 選ばれた候補を、効果が受け取る形に組み立てる
    // ---------------------------------------------------------------

    /**
     * 割り込みで選ばれた候補から、効果へ渡す選択結果を組み立てる(★Batch 68)。
     *
     * <p>★<b>{@code Kind.HAND} だけは盤面を変える</b> ——
     * 選ばれた手札は取り除いた状態で効果へ渡す、というのが Batch 4 からの規約である
     * (行き先は効果が決める: 大蟹なら墓地、猛火者なら場)。
     * 位置で指しているので、<b>降順に取り除く</b>(先に前を抜くと後ろがずれる)。
     *
     * <p>★候補が盤面から動いていた場合は {@code GameService.resolveChoice} の
     * {@code hasDriftedCandidate} が先に弾くので、ここでは考えない(裁定110)。
     */
    public ResolvedTargets.Selection selectionFrom(GameState state, PlayerState viewer,
            TargetSpec.Requirement req, List<String> chosen) {
        List<String> handCardIds = new ArrayList<>();
        List<ResolvedTargets.TargetedMinion> minions = new ArrayList<>();
        List<ManaCard> mana = new ArrayList<>();
        List<String> trashCardIds = new ArrayList<>();
        List<PlayerState> weapons = new ArrayList<>();
        switch (req.kind()) {
            case HAND -> {
                List<Integer> indexes = new ArrayList<>(chosen.stream().map(Integer::parseInt).toList());
                indexes.sort((a, b) -> b - a); // 降順に取り除く
                for (int idx : indexes) {
                    handCardIds.add(viewer.getHand().remove(idx));
                }
            }
            case TRASH -> chosen.forEach(position ->
                    trashCardIds.add(viewer.getTrash().get(Integer.parseInt(position))));
            case MANA -> chosen.forEach(position ->
                    mana.add(viewer.getManaZone().get(Integer.parseInt(position))));
            case MINION -> {
                for (String instanceId : chosen) {
                    for (PlayerState side : List.of(viewer, state.opponentOf(viewer.getPlayerId()))) {
                        side.getMinionZone().stream()
                                .filter(m -> m.getInstanceId().equals(instanceId))
                                .findFirst()
                                .ifPresent(m -> minions.add(
                                        new ResolvedTargets.TargetedMinion(side, m)));
                    }
                }
            }
            case WEAPON -> {
                for (String side : chosen) {
                    weapons.add("SELF".equals(side) ? viewer
                            : state.opponentOf(viewer.getPlayerId()));
                }
            }
        }
        return new ResolvedTargets.Selection(handCardIds, minions, mana, trashCardIds, weapons);
    }

    /**
     * この要求に対応する {@link PendingChoice.Kind}(★Batch 68)。
     *
     * <p>★<b>2つの列挙体は別物であり続ける</b>(裁定111 の「複製だが規約は共有」と同じ立場)。
     * {@code TargetSpec.Kind} は「カードが何を要求するか」、{@code PendingChoice.Kind} は
     * 「クライアントが何を選ぶ画面を出すか」であり、後者には REVEALED・CONFIRM という
     * 前者に無い値がある。ここが両者をつなぐ<b>唯一の場所</b>である ——
     * 番人は {@code Batch68TargetChoiceTest} が持つ。
     */
    public PendingChoice.Kind pendingKindOf(TargetSpec.Kind kind) {
        return switch (kind) {
            case HAND -> PendingChoice.Kind.HAND;
            case MINION -> PendingChoice.Kind.MINION;
            case MANA -> PendingChoice.Kind.MANA;
            case TRASH -> PendingChoice.Kind.TRASH;
            case WEAPON -> PendingChoice.Kind.WEAPON;
        };
    }
}
