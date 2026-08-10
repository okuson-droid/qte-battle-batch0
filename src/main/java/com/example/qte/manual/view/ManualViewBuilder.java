package com.example.qte.manual.view;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.qte.manual.ManualActor;
import com.example.qte.manual.ManualCardInstance;
import com.example.qte.manual.ManualCardMaster;
import com.example.qte.manual.ManualCardRepository;
import com.example.qte.manual.ManualGameState;
import com.example.qte.manual.ManualLogEntry;
import com.example.qte.manual.ManualLogRenderer;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualPermissions;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualSeat;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualStartPhase;
import com.example.qte.manual.ManualStartService;
import com.example.qte.manual.ManualViewpoint;
import com.example.qte.manual.ManualZone;

import lombok.RequiredArgsConstructor;

/**
 * 部屋の状態から、在室者1人ぶんの配信ビューを組み立てる。
 * 既存の {@code GameViewBuilder} と同じ位置づけの部品である。
 *
 * <h2>★Batch 21a でフィルタが動き始めた(設計書 3-3・B1)</h2>
 * 19a までは「在室者を受け取るが何も絞らない」形だった。フェイズ2の対戦部屋では、
 * 同じ盤面から人によって中身の違うビューが作られる。
 *
 * <b>非公開ゾーンは、カードオブジェクトを一切載せず枚数だけを送る。</b>
 * 裏向きスタブを並べる形も、中身を送ってクライアントで隠す形も採らない。
 * 前者は「枚数だけ」と同じ情報量なのに構造が複雑になり、後者は
 * 拡大画像・帯・DevTools のどれか1つの経路で必ず漏れる。
 * <b>届かないものは漏れない</b>という性質だけが、経路の数に依存せずに成立する。
 *
 * <h2>★マナだけが特例である(3-3)</h2>
 * マナは表向きと裏向きが混在し、表向きのカードは相手にも見えている。
 * したがって「表向きカードの配列 + 裏向きの枚数」を送る。
 * この特例は<b>ここ1箇所だけ</b>が知っていればよい。
 * {@link ManualViewpoint#canSeeZone} を MANA だけ true にすると、
 * 同じ判定を使うログのマスクまで公開扱いになってしまう。
 *
 * <h2>印刷値はここで引き直す</h2>
 * 状態モデルは cardId しか持たない。名前・文明・種別・コスト・印刷値は
 * 配信のたびに {@link ManualCardRepository} から引く。
 * 状態にカード定義を複製しておく手もあるが、そうするとスナップショットが太り、
 * カード定義を直したときに過去の履歴だけ古い値を持ち続けることになる。
 */
@Component
@RequiredArgsConstructor
public class ManualViewBuilder {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 切断猶予(5分)。★{@code ManualCleanupScheduler.GRACE_PERIOD} と対になる表示用の値 */
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(5);

    /**
     * ★Batch 29: 1回の配信に載せるログの行数(末尾から)。
     *
     * <h3>なぜ制限するのか</h3>
     * 27 まではログを<b>毎回全行</b>載せていた。ログは追記専用で上限が無いため
     * (設計書16 5-5「古いものを捨てない」)、N手目の配信にはN行が入る。
     * つまり累積送信量が<b>Nの2乗に比例</b>していた。実測では 2人・200手で 16MB、
     * 1配信あたりでもログ800行で 124KB に達する(28 設計解説1章)。
     *
     * <h3>捨てているわけではない</h3>
     * サーバ側の {@code ManualRoom.log} は従来どおり全行を保持し、
     * ダウンロード({@code ManualLobbyController.exportLog})は全文を返す。
     * 制限するのは<b>配信だけ</b>であり、5-4 の「ダウンロードだけ完全版という裏口を作らない」
     * とは逆向きの話である(配信が部分、ダウンロードが完全)。
     * 省略が起きていることは {@code logTotal} で画面に伝える。
     *
     * <h3>60行の根拠</h3>
     * ログ欄は既定で直近2行ぶんの高さであり、クリックで右列内に展開する(20b 2-5)。
     * 展開しても画面に入るのは数十行であり、それ以上は「読む」より「探す」領域で、
     * 検索のあるダウンロードの仕事である。
     */
    private static final int LOG_TAIL = 60;

    private final ManualCardRepository cards;

    private final ManualLogRenderer logRenderer;

    /**
     * ★開始シーケンス(Batch 23)。ビューが参照するのは
     * {@link ManualStartService#isPureElementAvailable()} だけである。
     * 設定の有無を判断する場所を2つ作らないため、サービスに聞く。
     */
    private final ManualStartService startService;

    public ManualGameView build(ManualRoom room, ManualOccupant viewer) {
        ManualGameState state = room.getGameState();
        ManualViewpoint viewpoint = ManualViewpoint.of(room, viewer);
        Instant now = Instant.now();

        List<ManualOccupantView> occupants = new ArrayList<>();
        for (ManualOccupant occupant : room.getOccupants()) {
            occupants.add(new ManualOccupantView(
                    occupant.getDisplayName(),
                    occupant.getRole(),
                    occupant.getSeatId(),
                    // ★視点は観戦者だけが持つ。プレイヤーに載せると「選べる」ように見える
                    occupant.isSeated() ? null : occupant.getSpectatorView(),
                    occupant.isConnected(),
                    graceSecondsLeft(occupant, now),
                    occupant == viewer));
        }

        // ★ログは配信のたびに閲覧者ごとへレンダリングする(5-1)。
        //   ダウンロード(ManualLobbyController)も同じ ManualLogRenderer を通る。
        //   ★Batch 29: 載せるのは末尾 LOG_TAIL 行だけである(LOG_TAIL の javadoc)。
        //   レンダリング自体もその行数ぶんしか回さないので、CPUも O(N) から定数になる。
        List<ManualLogEntry> entries = room.getLog();
        int logTotal = entries.size();
        List<ManualLogView> log = new ArrayList<>();
        for (ManualLogEntry entry : entries.subList(Math.max(0, logTotal - LOG_TAIL), logTotal)) {
            log.add(new ManualLogView(entry.seq(), TIME_FORMAT.format(entry.at()),
                    logRenderer.render(entry.event(), viewpoint)));
        }

        ManualActor actor = ManualActor.of(room, viewer);
        return new ManualGameView(
                room.getRoomId(),
                viewer == null ? null : viewer.getOccupantId(),
                cards.getBackImageId(),
                room.getType(),
                room.getOptions().name(),
                room.getOptions().spectatorAllowed(),
                viewpoint.viewerSeat(),
                viewer == null ? null : viewer.getRole(),
                viewpoint.isSpectator() ? viewpoint.spectatorView() : null,
                state.getTurnNumber(),
                state.getPhase(),
                buildSeat(state.getSeatA(), viewpoint),
                buildSeat(state.getSeatB(), viewpoint),
                buildShared(state),
                buildStart(room, actor),
                occupants,
                log,
                logTotal,
                // ★ボタンの活性と実際の可否が同じ判定を通る(設計判断34 の型)
                ManualPermissions.denyUndo(actor, room) == null,
                ManualPermissions.denyRedo(actor, room) == null && room.getHistory().canRedo());
    }

    /**
     * 開始シーケンスのビュー(★Batch 23 設計書9章)。
     *
     * ★<b>「押せるか」はすべて {@link ManualPermissions} の結果である。</b>
     * ここで条件を組み立て直さない。ボタンの活性と実際の可否が同じ関数を通るため、
     * 表示と検証が構造的にズレない(設計判断34。21a の canUndo / canRedo と同じ形)。
     */
    private ManualStartView buildStart(ManualRoom room, ManualActor actor) {
        ManualStartPhase phase = room.getStartPhase();
        boolean mayControl = ManualPermissions.denyStartControl(actor, room) == null;
        boolean mayOrder = ManualPermissions.denyOrderChoice(actor, room) == null;

        List<ManualSeatId> pending = new ArrayList<>(room.getMulliganPending());
        List<ManualSeatId> done = new ArrayList<>(room.getMulliganDone());
        List<ManualSeatId> mine = new ArrayList<>();
        if (phase == ManualStartPhase.MULLIGAN) {
            for (ManualSeatId seatId : pending) {
                if (!done.contains(seatId)
                        && ManualPermissions.denySeatAction(actor, seatId) == null) {
                    mine.add(seatId);
                }
            }
        }

        return new ManualStartView(
                phase,
                phase.isLocking(),
                room.getFirstSeat(),
                room.getOrderChooserSeat(),
                // ★ボタンの文言と実際の結果が同じ関数を通る(設計判断34)
                startService.subjectSeat(room, actor),
                phase == ManualStartPhase.IDLE && mayControl && beginnable(room, actor),
                phase == ManualStartPhase.ORDER_METHOD && mayControl,
                phase == ManualStartPhase.ORDER_CHOICE && mayOrder,
                pending,
                done,
                mine,
                waitingText(room, phase, pending, done),
                startService.isPureElementAvailable());
    }

    /**
     * 開始できる条件(2-3)。★対戦部屋は両席、全公開部屋は1席以上のデッキ読込が要る。
     * ★{@link com.example.qte.manual.ManualStartService#begin} と同じ条件である。
     * 判定が2つに分かれるのは避けたいが、片方は「押せるか」でもう片方は「押されたときの検証」で
     * あり、後者が唯一の正である(クライアントの活性は操作補助にすぎない。設計判断27)。
     */
    private boolean beginnable(ManualRoom room, ManualActor actor) {
        int loaded = 0;
        for (ManualSeatId seatId : ManualSeatId.values()) {
            if (room.getGameState().seat(seatId).getLastImport() != null) {
                loaded++;
            }
        }
        return actor.isRestricted() ? loaded == ManualSeatId.values().length : loaded > 0;
    }

    /**
     * 待機中の説明(7-3)。★<b>全員に同じ文を出す。</b>
     * 盤面が固まっている理由が画面に書かれていない状態を作らない(21 設計書 3-5)。
     * ★マリガンでは「選択中 / 確定済み」だけを出し、<b>何枚選んだかは出さない</b>(P11)。
     */
    private String waitingText(ManualRoom room, ManualStartPhase phase,
            List<ManualSeatId> pending, List<ManualSeatId> done) {
        return switch (phase) {
            case ORDER_METHOD -> "ゲームの開始方法を選んでいます";
            case ORDER_CHOICE -> "席%s が先攻・後攻を選んでいます".formatted(room.getOrderChooserSeat());
            case MULLIGAN -> {
                List<String> parts = new ArrayList<>();
                for (ManualSeatId seatId : pending) {
                    parts.add("席%s: %s".formatted(seatId, done.contains(seatId) ? "確定済み" : "選択中"));
                }
                yield "マリガンの確定を待っています(%s)".formatted(String.join(" / ", parts));
            }
            default -> null;
        };
    }

    /**
     * 共有ゾーン(20b 3-2)。席のビューとは別枠で1つだけ送る。
     * クライアントは {@code view.shared.PLAY} / {@code view.shared.REVEAL} で読む。
     *
     * ★共有ゾーンは定義上すべて公開である({@link ManualZone#isContentsPublic()})。
     * そこへ置くこと自体が「相手に見せる」という意思表示だからであり、フィルタは掛からない。
     */
    private Map<ManualZone, List<ManualCardView>> buildShared(ManualGameState state) {
        Map<ManualZone, List<ManualCardView>> shared = new EnumMap<>(ManualZone.class);
        for (Map.Entry<ManualZone, List<ManualCardInstance>> entry
                : state.getSharedZones().entrySet()) {
            List<ManualCardView> views = new ArrayList<>();
            for (ManualCardInstance card : entry.getValue()) {
                views.add(buildCard(card));
            }
            shared.put(entry.getKey(), views);
        }
        return shared;
    }

    private ManualSeatView buildSeat(ManualSeat seat, ManualViewpoint viewpoint) {
        Map<ManualZone, List<ManualCardView>> zones = new EnumMap<>(ManualZone.class);
        Map<ManualZone, Integer> counts = new EnumMap<>(ManualZone.class);
        // ★裏向きマナの枚数は誰にでも見せる。表向きが何枚あるかは盤面から数えられるため、
        //   裏向きの枚数を隠しても意味が無い(MPも公開している)。
        int manaFaceDown = (int) seat.zone(ManualZone.MANA).stream()
                .filter(ManualCardInstance::isFaceDown).count();

        for (ManualZone z : ManualZone.values()) {
            if (z.isShared()) {
                continue; // 共有ゾーンは席に属さない(20b 3-1)
            }
            List<ManualCardInstance> source = seat.zone(z);
            // ★枚数は全ゾーンぶん常に送る。「何枚あるか」は公開情報である(3-3)
            counts.put(z, source.size());

            if (viewpoint.canSeeZone(seat.getId(), z)) {
                zones.put(z, buildCards(deliverable(z, source)));
            } else if (z == ManualZone.MANA) {
                // ★特例: 表向きのマナだけを載せる(3-3)。裏向きは manaFaceDownCount の枚数だけ
                List<ManualCardInstance> faceUp = new ArrayList<>();
                for (ManualCardInstance card : source) {
                    if (!card.isFaceDown()) {
                        faceUp.add(card);
                    }
                }
                zones.put(z, buildCards(faceUp));
            }
            // ★ここで else を書かない。キーを作らないことが「見えない」の表現である
        }

        return new ManualSeatView(
                seat.getId(),
                seat.getLp(),
                // ★MPはマナのアンタップ枚数の派生値であり、相手にも数値として公開する(3-3)
                seat.availableMp(),
                seat.isDeckLoaded(),
                seat.getDeckName(),
                // ★リーダーは総合ルール 2-5 により両者が公開している
                seat.getLeader() == null ? null : buildCard(seat.getLeader()),
                zones,
                counts,
                manaFaceDown);
    }

    /**
     * ★Batch 29: 見えるゾーンのうち、実際に配信へ載せるぶんを選ぶ。
     *
     * <h3>山札だけ「最上段の1枚」に絞る</h3>
     * 山札は盤面では<b>パイル1枚ぶん</b>しか描かれない。中身が要るのは全面表示
     * ({@code openDeckFullscreen})を開いたときだけであり、それは常時ではない。
     * それでも 27 まで毎回30枚ぶんを配っており、実測で<b>盤面26KBのうち約10KBが
     * 山札と禁忌</b>だった(28 設計解説1-5)。山札の中身は
     * {@link #buildZoneCards} 経由で、開いたときにだけ取りに行く。
     *
     * <h3>★1枚だけは必ず載せる理由</h3>
     * 空にはしない。Batch 26 で山札のパイルは「一番上の1枚をドラッグできる」
     * ようになっており、その1枚の instanceId が要る。また左クリックのドローも
     * 「一番上」を前提にしている。ここを空にすると盤面の操作が静かに壊れる。
     * 山札の最上段は index 0 である({@code ManualGameService.drawCards} と揃えてある)。
     *
     * <h3>★禁忌(TABOO)は絞らない</h3>
     * 8枚しかなく、削っても効果が小さいのに、帯・拡大・ドラッグの経路がすべて
     * 「中身がある」前提で書かれている。効果の小さい変更のために壊す面を増やさない。
     *
     * <h3>★これは可視性の変更ではない</h3>
     * 「キーが在る = 見えている」という 21a 3-3 の規約はそのままである。
     * 変わるのは「届く配列が全部とは限らない」だけであり、これはマナが既に
     * そうなっている(表向きだけを載せる)。<b>枚数は counts が持つ</b>ので、
     * クライアントは配列の長さを枚数として使ってはならない。
     */
    private List<ManualCardInstance> deliverable(ManualZone zone,
            List<ManualCardInstance> source) {
        if (zone != ManualZone.DECK || source.isEmpty()) {
            return source;
        }
        return List.of(source.get(0));
    }

    /**
     * ★Batch 29: 1ゾーンの中身だけを組み立てる(山札の全面表示用)。
     *
     * 可視性の判定は {@link ManualViewpoint#canSeeZone} を通す。配信と同じ関数であり、
     * 「配信では隠れているのにこの口からは見える」を構造的に作らない。
     * 見えないゾーンを要求されたら例外にする(空配列を返すと、
     * 「空の山札」と「見せてもらえない山札」が区別できなくなる)。
     */
    public List<ManualCardView> buildZoneCards(ManualRoom room, ManualOccupant viewer,
            ManualSeatId seatId, ManualZone zone) {
        if (zone.isShared()) {
            throw new IllegalArgumentException("共有ゾーンは席を指定して取得できません: " + zone);
        }
        ManualViewpoint viewpoint = ManualViewpoint.of(room, viewer);
        if (!viewpoint.canSeeZone(seatId, zone)) {
            throw new IllegalArgumentException("このゾーンは公開されていません: " + zone);
        }
        return buildCards(room.getGameState().seat(seatId).zone(zone));
    }

    private List<ManualCardView> buildCards(List<ManualCardInstance> source) {
        List<ManualCardView> views = new ArrayList<>();
        for (ManualCardInstance card : source) {
            views.add(buildCard(card));
        }
        return views;
    }

    private ManualCardView buildCard(ManualCardInstance card) {
        List<ManualCardView> materials = new ArrayList<>();
        for (ManualCardInstance material : card.getMaterials()) {
            materials.add(buildCard(material));
        }
        ManualCardMaster master = card.isResolved() ? cards.findById(card.getCardId()) : null;
        if (master == null) {
            return new ManualCardView(
                    card.getInstanceId(), null, card.getFallbackName(), null, null, null,
                    null, null, null, card.getAttack(), card.getHp(),
                    card.isTapped(), card.isFaceDown(), card.isUsed(), card.getPlacedBySeat(),
                    List.copyOf(card.getLabels()), card.stackSize(), materials);
        }
        return new ManualCardView(
                card.getInstanceId(),
                master.id(),
                master.name(),
                master.imageId(),
                master.civilization(),
                master.type(),
                master.cost(),
                master.attack(),
                master.hp(),
                card.getAttack(),
                card.getHp(),
                card.isTapped(),
                card.isFaceDown(),
                card.isUsed(),
                card.getPlacedBySeat(),
                List.copyOf(card.getLabels()),
                card.stackSize(),
                materials);
    }

    /** 切断猶予の残り秒数(2-4)。接続中なら null。0未満にはしない。 */
    private Long graceSecondsLeft(ManualOccupant occupant, Instant now) {
        if (occupant.isConnected() || occupant.getDisconnectedAt() == null) {
            return null;
        }
        long left = GRACE_PERIOD.minus(Duration.between(occupant.getDisconnectedAt(), now))
                .toSeconds();
        return Math.max(0, left);
    }
}
