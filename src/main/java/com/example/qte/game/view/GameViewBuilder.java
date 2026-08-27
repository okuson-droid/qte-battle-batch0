package com.example.qte.game.view;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.LeaderAbilitySpec;
import com.example.qte.effect.EvolutionSpec;
import com.example.qte.effect.RuleGuards;
import com.example.qte.effect.SpecialSummonSpec;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetSpec;
import com.example.qte.game.GameState;
import com.example.qte.game.GameStatus;
import com.example.qte.game.ManaCard;
import com.example.qte.game.ManaPayment;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardTextKeywords;
import com.example.qte.master.Keyword;
import com.example.qte.room.GameRoom;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.SeatId;

import lombok.RequiredArgsConstructor;

/**
 * GameStateから「そのプレイヤーに見えてよい情報だけ」を抜き出してGameViewを組み立てる。
 * 情報の非対称(手札・裏向きマナ)はすべてここで一元的に処理する(設計判断9)。
 * GameStateを変更しない読み取り専用のクラスである(変更はGameServiceのみ)。
 */
@Component
@RequiredArgsConstructor
public class GameViewBuilder {

    private final CardMasterRepository cards;
    private final StatCalculator stats;
    private final CardEffectRegistry effects;

    /** 攻撃可否の判定。サーバ側の検証と同じ判定をUI表示にも使う */
    private final RuleGuards guards;

    /**
     * 「効果が未実装」の印の判定(★Batch 47)。
     * デッキ構築で弾かなくなったカード(裁定D2)を、盤面で見分けられるようにするために使う。
     * クライアントに同じ判定を持たせない —— 実装済みかどうかを知っているのはサーバだけである。
     */
    private final com.example.qte.effect.EffectImplementation implementation;

    /**
     * viewerId の人に配信するビューを組み立てる。
     *
     * <p>★<b>Batch 66: viewerId は「席に着いた人」とは限らない。</b>
     * 観戦者({@code Spectator})の id もここへ来る。観戦者に見せるのは
     * <b>両席とも「相手として見えるぶん」だけ</b>である ——
     * つまり手札の中身も裏向きマナの中身も禁忌デッキの中身も入らない。
     * ★これは新しい規則ではなく、{@link #buildPlayerView} の
     * {@code isSelf = false} をそのまま両側に当てただけである。
     * 観戦専用の「見せてよい範囲」を書き足すと、
     * <b>フィルタが2本になった時点でどちらかが必ず遅れる</b>(設計判断9)。
     */
    public GameView build(GameRoom room, String viewerId) {
        PlayerSlot viewerSlot = room.findSlot(viewerId).orElse(null);
        RoomView roomView = buildRoomView(room, viewerSlot);
        GameState state = room.getGameState();
        if (state == null) {
            // 席の埋まり待ち / デッキの読み込み待ち: 盤面はまだ存在しない
            return new GameView(room.getRoomId(), GameStatus.WAITING.name(), 0, null, null,
                    false, false, false, null, roomView, null, null,
                    List.copyOf(room.getLog()));
        }
        // ★観戦者かどうかは<b>盤面が知っている</b>(GameState.hasPlayer)。
        //   部屋の席は試合が始まる前の帳簿であり、同じ問いに答えを2つ持たせない
        if (!state.hasPlayer(viewerId)) {
            return buildSpectatorView(room, state, roomView);
        }
        PlayerState you = state.playerOf(viewerId);
        PlayerState opponent = state.opponentOf(viewerId);

        boolean myTurn = state.getStatus() == GameStatus.PLAYING
                && viewerId.equals(state.getTurnPlayerId());
        boolean chooseOrder = state.getStatus() == GameStatus.SETUP
                && state.getFirstPlayerId() == null
                && viewerId.equals(room.getDiceWinnerId());
        boolean mulligan = state.getStatus() == GameStatus.SETUP
                && state.getFirstPlayerId() != null
                && !you.isMulliganDone();
        String winnerName = state.getWinnerPlayerId() == null ? null
                : state.playerOf(state.getWinnerPlayerId()).getDisplayName();

        return new GameView(
                room.getRoomId(),
                state.getStatus().name(),
                state.getTurnNumber(),
                state.getPhase().name(),
                state.getPhase().getDisplayName(),
                myTurn,
                chooseOrder,
                mulligan,
                winnerName,
                roomView,
                buildPlayerView(state, you, true, myTurn),
                buildPlayerView(state, opponent, false, myTurn),
                List.copyOf(room.getLog()));
    }

    /**
     * 観戦者のビュー(★Batch 66)。
     *
     * <p>★下段({@code you})が<b>席A</b>、上段({@code opponent})が<b>席B</b>である。
     * 観戦者に「自分」は無いので、どちらを下に置くかは決めの問題になる ——
     * 決めを毎回変えると、観戦者が2人いるときに違う盤面を見ることになる。
     * ★<b>席という枠は不変である</b>(A と B しか無い)ので、席で固定すれば必ず同じ絵になる。
     *
     * <p>★★<b>Batch 72 で訂正した。</b>66 はここに「席は不変(座り直しは無い)」と
     * 書いていたが、72 で<b>座り直しは在る</b>(席を立つ・観戦者から席へ)。
     * それでも上の判断は変わらない —— 固定しているのは<b>席という枠</b>であって、
     * そこに座っている人ではないからである。
     * ★<b>理由の文だけが古くなり、結論は正しいままだった</b>という形である
     * (67 の教訓・写しの、いちばん穏やかな顔)。
     *
     * <p>★{@code myTurn} は必ず false である。攻撃可否・使用可否のハイライトは
     * すべて {@code attackerSide = isSelf && viewerTurn} から生えているので、
     * これだけで<b>観戦者の画面には操作の導線が1つも出ない</b>。
     * ★<b>それでも守っているのはサーバである</b> —— 観戦者は playerId を持たないので、
     * 仮に画面を書き換えて操作を送っても {@code state.playerOf} が知らない id を弾く。
     */
    private GameView buildSpectatorView(GameRoom room, GameState state, RoomView roomView) {
        PlayerState seatA = state.getPlayer1();
        PlayerState seatB = state.getPlayer2();
        String winnerName = state.getWinnerPlayerId() == null ? null
                : state.playerOf(state.getWinnerPlayerId()).getDisplayName();
        return new GameView(
                room.getRoomId(),
                state.getStatus().name(),
                state.getTurnNumber(),
                state.getPhase().name(),
                state.getPhase().getDisplayName(),
                false,
                false,
                false,
                winnerName,
                roomView,
                buildPlayerView(state, seatA, false, false),
                buildPlayerView(state, seatB, false, false),
                List.copyOf(room.getLog()));
    }

    /** 受付の情報(★Batch 66)。盤面の有無によらず同じ組み立てを通る */
    private RoomView buildRoomView(GameRoom room, PlayerSlot viewerSlot) {
        // ★★Batch 72: 再戦の申し込みは「席」で送る。viewer 目線に加工しない ——
        //   加工すると、観戦者ぶんの意味をもう1つ決めることになる(設計判断9)
        PlayerSlot offerer = room.findSlot(room.getRematchOfferedBy()).orElse(null);
        return new RoomView(
                room.getOptions().name(),
                room.getOptions().spectatorAllowed(),
                room.spectatorCount(),
                seatView(room, SeatId.A),
                seatView(room, SeatId.B),
                viewerSlot == null ? null : viewerSlot.getSeat().name(),
                viewerSlot == null,
                offerer == null ? null : offerer.getSeat().name(),
                offerer == null ? null : offerer.getDisplayName());
    }

    private RoomView.SeatView seatView(GameRoom room, SeatId seat) {
        return room.slotOfSeat(seat)
                .map(s -> new RoomView.SeatView(
                        s.getDisplayName(), s.isDeckLoaded(), s.isReady()))
                .orElseGet(RoomView.SeatView::empty);
    }

    private PlayerView buildPlayerView(GameState state, PlayerState player, boolean isSelf, boolean viewerTurn) {
        // 手札: 自分には中身を、相手には枚数だけを見せる
        List<CardView> hand = null;
        if (isSelf) {
            hand = new java.util.ArrayList<>();
            for (int i = 0; i < player.getHand().size(); i++) {
                hand.add(buildHandCard(state, player, i));
            }
        }

        List<ManaView> mana = player.getManaZone().stream()
                .map(m -> toManaView(m, isSelf))
                .toList();

        boolean attackerSide = isSelf && viewerTurn;
        List<MinionView> minions = player.getMinionZone().stream()
                .map(m -> toMinionView(state, player, m, attackerSide))
                .toList();

        boolean leaderFrozen = player.getLeaderCannotAttackOnTurn() == state.getTurnNumber();
        // リーダーの攻撃可否も判定層(RuleGuards)に揃える。サーバの検証と同じ判断を通すことで
        // ボタンの活性と実際の可否がずれない(a2で未装備・攻撃済み・凍結を判定層へ集約済み)
        boolean leaderCanAttack = attackerSide
                && guards.leaderAttackDenial(state, player) == null;

        // 禁忌デッキの中身は所有者のみ閲覧できる(総合ルール3-2)。相手には枚数だけを送る
        List<CardView> taboo = null;
        if (isSelf) {
            taboo = player.getTabooDeck().stream()
                    .map(id -> buildCardView(state, player, cards.findById(id), -1, false))
                    .toList();
        }

        return new PlayerView(
                player.getDisplayName(),
                player.getLeader().name(),
                player.getLeader().id(),
                player.getLp(),
                player.getDeck().size(),
                player.getHand().size(),
                hand,
                player.getAvailableMp(),
                player.getManaZone().size(),
                mana,
                // ★★★Batch 70(裁定315〜317): 「これから払われるマナ」の順。
                //   ★<b>順序そのものを送る</b> —— クライアントは先頭 n 件を取るだけで、
                //     払い方の規則を1つも持たない(裁定234・67 の教訓「写し」)。
                //   ★相手のビューには入れない。自分がどのマナから払うつもりかは
                //     操作の手の内であり、盤面の公開情報ではないためである(設計判断9)。
                isSelf ? ManaPayment.normalOrder(player) : List.of(),
                isSelf ? ManaPayment.tabooOrder(player) : List.of(),
                minions,
                player.getTrash().size(),
                player.getTrash().stream().map(id -> cards.findById(id).name()).toList(),
                // ★Batch 53: 墓地の面には「墓地から特殊召喚できるか」を添える
                //   (《サモナーポップ・エンラ》)。相手の墓地でも同じ形で作られるが、
                //   操作できるのは自分の墓地だけなのでクライアント側が isSelf で絞る
                player.getTrash().stream()
                        .map(id -> buildCardView(state, player, cards.findById(id), -1, true))
                        .toList(),
                player.getLostZone().size(),
                player.getLostZone().stream().map(id -> cards.findById(id).name()).toList(),
                // ★Batch 44: 消滅の面(最上段の表示・一覧のフェイス化)。消滅は墓地と同じ公開情報である
                player.getLostZone().stream()
                        .map(id -> buildCardView(state, player, cards.findById(id), -1, false))
                        .toList(),
                player.getTabooDeck().size(),
                taboo,
                player.isManaChargedThisTurn(),
                player.isCannotUseCardsThisTurn(),
                player.isMulliganDone(),
                player.getLeader().text(),
                player.getDeckName(),
                player.getEquippedWeapon() == null ? null : player.getEquippedWeapon().name(),
                // ★Batch 44: 効果テキスト・文明はクライアントが card-library から引く(裁定144)。
                //   ビューにはIDだけを足す。名前で引かせないため(名前はIDと同じ、の原則)
                player.getEquippedWeapon() == null ? null : player.getEquippedWeapon().id(),
                player.getEquippedWeapon() == null ? null
                        : stats.effectiveWeaponAttack(state, player),
                leaderCanAttack,
                leaderFrozen,
                buildLeaderAbility(state, player, isSelf),
                buildRevealedCards(player),
                buildPendingChoice(state, player, isSelf));
    }

    /** 一時公開領域のカード(降臨の伝道師などが公開中の束)。空なら公開なし */
    private List<PlayerView.RevealedCardView> buildRevealedCards(PlayerState player) {
        List<String> revealed = player.getRevealedZone();
        List<PlayerView.RevealedCardView> views = new java.util.ArrayList<>();
        for (int i = 0; i < revealed.size(); i++) {
            CardMaster m = cards.findById(revealed.get(i));
            views.add(new PlayerView.RevealedCardView(i, m.name(), CardView.keywordNames(m),
                    m.hasKeyword(Keyword.GUARD)));
        }
        return views;
    }

    /**
     * 割り込み選択の問い合わせ(a9)。選択待ちでなければnull。
     * 候補の識別子(手札の位置・instanceId・墓地の位置・公開領域の位置)を、
     * 表示用のラベルに変換して届ける。クライアントは選んだ候補の位置を送り返す。
     */
    private PlayerView.PendingChoiceView buildPendingChoice(GameState state, PlayerState player, boolean isSelf) {
        // 選択の問い合わせは本人にしか見せない(相手のビューには出さない)
        if (!isSelf) {
            return null;
        }
        com.example.qte.effect.PendingChoice choice = player.getPendingChoice();
        if (choice == null) {
            return null;
        }
        List<PlayerView.PendingChoiceView.ChoiceCandidateView> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < choice.candidates().size(); i++) {
            String id = choice.candidates().get(i);
            String label;
            List<String> keywords = List.of();
            String minionInstanceId = null;
            switch (choice.kind()) {
                case HAND -> {
                    int idx = Integer.parseInt(id);
                    CardMaster m = cards.findById(player.getHand().get(idx));
                    label = m.name();
                    keywords = CardView.keywordNames(m);
                }
                case TRASH -> {
                    int idx = Integer.parseInt(id);
                    label = cards.findById(player.getTrash().get(idx)).name();
                }
                case REVEALED -> {
                    int idx = Integer.parseInt(id);
                    CardMaster m = cards.findById(player.getRevealedZone().get(idx));
                    label = m.name();
                    keywords = CardView.keywordNames(m);
                }
                case MINION -> {
                    // 候補は自分・相手どちらの場にもありうる(回帰の風穴の2回目対象など。
                    // 記法規約により「ミニオン」に側の限定が無い効果は両者の場を参照するため)
                    PlayerState opponent = state.opponentOf(player.getPlayerId());
                    MinionInstance minion = java.util.stream.Stream
                            .concat(player.getMinionZone().stream(), opponent.getMinionZone().stream())
                            .filter(mi -> mi.getInstanceId().equals(id))
                            .findFirst().orElse(null);
                    // 場を離れている場合(通常は起きない)は識別子をそのまま出す
                    label = minion != null ? minion.getMaster().name() : id;
                    minionInstanceId = id;
                }
                case MANA -> {
                    // 候補は自分のマナゾーン内の位置。裏向きのマナも本人には中身が見えるため
                    // (toManaView の contentVisible と同じ扱い)、カード名を出したうえで向きを添える
                    int idx = Integer.parseInt(id);
                    ManaCard mana = player.getManaZone().get(idx);
                    CardMaster m = cards.findById(mana.getCardId());
                    label = mana.isFaceUp() ? m.name() : m.name() + "(裏向き)";
                }
                // ★★Batch 68(裁定282): 割り込みで<b>ウェポン</b>を選ぶ経路ができた
                // (《天界の守護神 ゾディアック》の【召喚時】)。
                // 候補は "SELF" / "OPPONENT" という<b>側の名前</b>である ——
                // ウェポンは1人1つなので、ミニオンのような instanceId を持たない。
                // ★<b>どちらの側かを必ず添える。</b>同名のウェポンを両者が装備していることは
                //   普通に起きるので、名前だけでは押し間違える
                case WEAPON -> {
                    PlayerState side = "SELF".equals(id)
                            ? player : state.opponentOf(player.getPlayerId());
                    CardMaster weapon = side.getEquippedWeapon();
                    // 選んでいる間に外れている場合(通常は起きない)は側の名前をそのまま出す
                    label = weapon == null ? id
                            : "%s(%s)".formatted(weapon.name(), side == player ? "自分" : "相手");
                }
                // ★Batch 64: はい/いいえ。候補は1件だけで、選べば「はい」である。
                // クライアントは kind を見て2つのボタンに描き替えるので、ここの文言は保険にすぎない
                case CONFIRM -> label = "はい";
                default -> label = id;
            }
            candidates.add(new PlayerView.PendingChoiceView.ChoiceCandidateView(i, label, keywords, minionInstanceId));
        }
        return new PlayerView.PendingChoiceView(choice.kind().name(), candidates,
                choice.min(), choice.max(), choice.prompt(), player.getPendingChoiceCount(),
                // ★★Batch 70(指摘2): 「今プレイしているカード」。控えたのは requestChoice である
                choice.sourceCardId());
    }

    /** リーダー起動能力の状態。使用可否はサーバで評価する(UIはボタンの活性に使うだけ) */
    private PlayerView.LeaderAbilityView buildLeaderAbility(GameState state, PlayerState player, boolean isSelf) {
        LeaderAbilitySpec spec = effects.leaderAbilityOf(player.getLeader().id());
        if (spec == null) {
            return null;
        }
        boolean usable = isSelf
                && state.getStatus() == com.example.qte.game.GameStatus.PLAYING
                && player.getPlayerId().equals(state.getTurnPlayerId())
                && !player.isLeaderAbilityUsedThisTurn()
                && player.getAvailableMp() >= spec.mpCost()
                // 代償を払えない能力(冥府の禁皇: 裏向きマナが必要)はボタンを押せなくする
                && spec.condition().test(state, player);
        return new PlayerView.LeaderAbilityView(usable, spec.mpCost(), spec.description(),
                toReqViews(spec.targets()));
    }

    /** 手札のカード1枚のビュー。実効コスト・対象仕様・特殊召喚可否はサーバで評価して添える */
    private CardView buildHandCard(GameState state, PlayerState player, int handIndex) {
        return buildCardView(state, player, cards.findById(player.getHand().get(handIndex)), handIndex, false);
    }

    /**
     * カード1枚のビュー。handIndexが-1のときは手札以外(禁忌デッキ)のカードで、
     * 特殊召喚は手札からの召喚のため対象外とする。
     */
    private CardView buildCardView(GameState state, PlayerState player, CardMaster master,
            int handIndex, boolean inTrash) {
        SpecialSummonSpec special = handIndex < 0 ? null : effects.specialSummonOf(master.id());
        boolean canSpecial = special != null
                && special.condition().test(state, player, handIndex);
        // ★Batch 53: 墓地からの特殊召喚(《サモナーポップ・エンラ》)。
        // 手札の位置を持たないので -1 を渡す —— 墓地から出せると宣言しているカードの条件は
        // 手札の位置を参照しない(参照するなら手札からしか出せないはずである)。
        // 判定は GameService.specialSummonFromGrave が同じ述語でやり直す
        SpecialSummonSpec fromGrave = inTrash ? effects.specialSummonOf(master.id()) : null;
        boolean canSpecialFromGrave = fromGrave != null && fromGrave.fromGrave()
                && fromGrave.condition().test(state, player, -1);
        // 対象要求と確認の文言は、手札からでも墓地からでも同じ仕様を見せる
        SpecialSummonSpec shown = special != null ? special : (canSpecialFromGrave ? fromGrave : null);
        // ★Batch 52: 進化ミニオンの素材条件。手札以外(禁忌デッキ)のカードにも添える ——
        // 禁忌からの進化召喚も出し方は同じである(マスター裁定 E1)
        EvolutionSpec evolution = effects.evolutionOf(master.id());
        TargetSpec spec = effects.declarationTargetSpecOf(master.id());
        com.example.qte.effect.EnhancedCostSpec enhanced = effects.enhancedCostOf(master.id());
        // ★Batch 54:【賢魂：n】としての姿(裁定152)。
        // ★<b>2つの条件がそろって初めて導線を出す</b> —— テキストに【賢魂：n】があり、
        //   かつエンジンがその効果を持っていること。片方だけだと
        //   「押せるのに『未実装です』と言われるボタン」になる。
        // ★n の出どころはテキスト1つである(CardTextKeywords)。ビューは計算しない
        Integer soulCost = effects.soulSpellOf(master.id()) == null
                ? null : CardTextKeywords.soulCost(master.text());
        com.example.qte.effect.SoulSpellSpec soul = soulCost == null
                ? null : effects.soulSpellOf(master.id());
        return new CardView(
                master.id(),
                master.name(),
                master.type().name(),
                master.civilization().name(),
                master.cost(),
                master.cost() == null ? null : stats.effectiveCost(state, player, master),
                master.attack(),
                master.hp(),
                CardView.keywordNames(master),
                master.text(),
                toReqViews(spec),
                canSpecial,
                shown == null ? List.of() : toReqViews(shown.targets()),
                shown == null ? null : shown.description(),
                // ★Batch 70(裁定319): 確定の段で「何枚のマナを選ばせるか」に要る
                shown == null ? 0 : shown.mpCost(),
                spec.combinedTotal(),
                enhanced == null ? 0 : enhanced.extraCost(),
                enhanced == null ? null : enhanced.prompt(),
                implementation.isUnimplemented(master),
                evolutionMaterialIds(player, evolution),
                evolution == null ? 0 : evolution.minMaterials(),
                evolutionMax(player, evolution),
                evolution == null ? null : evolution.description(),
                canSpecialFromGrave,
                soulCost,
                soulCost == null ? null : stats.effectiveSoulCost(state, player, master, soulCost),
                soul == null ? List.of() : toReqViews(soul.targets()),
                soulCost == null ? null : CardTextKeywords.soulText(master.text()));
    }

    /**
     * 今この瞬間、この進化ミニオンの素材にできる自分の場のミニオン(★Batch 52)。
     *
     * ★<b>絞り込みそのものをサーバが行い、結果だけを送る。</b>
     * クライアントは条件を1つも知らないので、素材条件を増やしても
     * {@code battle.js} を直す必要がない(裁定163・195 の回避)。
     * 検証は {@code GameService.resolveMaterials} が<b>同じ述語で</b>やり直す ——
     * 届いた値を信用しないのは他の対象選択と同じである。
     */
    private List<String> evolutionMaterialIds(PlayerState player, EvolutionSpec evolution) {
        if (evolution == null) {
            return List.of();
        }
        return player.getMinionZone().stream()
                .filter(evolution.material())
                .map(MinionInstance::getInstanceId)
                .toList();
    }

    /** 素材の最大数。「1体以上」(不敗鉄人闘太)は今の場のミニオン数が実際の上限になる */
    private int evolutionMax(PlayerState player, EvolutionSpec evolution) {
        if (evolution == null) {
            return 0;
        }
        return Math.min(evolution.maxMaterials(), player.getMinionZone().size());
    }

    private List<CardView.TargetReqView> toReqViews(TargetSpec spec) {
        return spec.requirements().stream()
                .map(r -> new CardView.TargetReqView(
                        r.kind().name(), r.side().name(), r.count(), r.optional(), r.upTo(),
                        r.filters().stream().map(Enum::name).toList(), r.prompt()))
                .toList();
    }

    /** 裏向きマナの中身は持ち主にのみ公開する(発注者確認済みルール) */
    private ManaView toManaView(ManaCard mana, boolean isSelf) {
        boolean contentVisible = mana.isFaceUp() || isSelf;
        String cardId = contentVisible ? mana.getCardId() : null;
        String name = contentVisible ? cards.findById(mana.getCardId()).name() : null;
        return new ManaView(mana.isFaceUp(), mana.isTapped(), mana.isTemporary(), cardId, name);
    }

    private MinionView toMinionView(GameState state, PlayerState owner, MinionInstance minion, boolean attackerSide) {
        CardMaster master = minion.getMaster();
        // 凍結状態は攻撃可否とは別に、盤面に印を出すためだけに使う
        boolean frozen = minion.getCannotAttackOnTurn() == state.getTurnNumber();  
        // UIハイライト用の攻撃可否。サーバ側の判定(RuleGuards)をそのまま呼ぶことで、
        // 「押せるのに弾かれる」「押せないはずが押せる」というズレが構造的に起きないようにする
        boolean canAttackMinion = attackerSide
                && guards.minionAttackDenial(state, owner, minion, false) == null;
        boolean canAttackLeader = attackerSide
                && guards.minionAttackDenial(state, owner, minion, true) == null;

        List<String> keywords = java.util.stream.Stream.concat(
                        master.keywords().stream(),
                        minion.getGrantedKeywords().stream())
                .distinct()
                .map(Keyword::getDisplayName)
                .toList();

        // ミニオンの起動能力(a6)。使えるかの判定はサーバに揃える(押せるのに弾かれるズレを防ぐ)。
        // メインフェイズ・自分の手番・タップしていない・条件を満たす、が揃ってはじめて使える
        com.example.qte.effect.MinionAbilitySpec ability = effects.minionAbilityOf(master.id());
        boolean canUseAbility = ability != null
                && attackerSide
                && state.getPhase() == com.example.qte.game.TurnPhase.MAIN
                && !owner.isCannotUseCardsThisTurn()
                && ability.usableBy(state, owner, minion);

        return new MinionView(
                minion.getInstanceId(),
                master.id(),
                master.name(),
                stats.effectiveAttack(state, owner, minion),
                minion.getCurrentHp(),
                minion.getMaxHp(),
                keywords,
                canAttackMinion,
                canAttackLeader,
                frozen,
                minion.isTapped(),
                canUseAbility,
                ability == null ? null : ability.description(),
                implementation.isUnimplemented(master),
                minion.isEvolution(),
                minion.getUnder().stream()
                        .map(com.example.qte.game.StackedCard::cardId)
                        .toList());
    }
}
