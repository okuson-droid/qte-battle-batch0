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

    private final ManualCardRepository cards;

    private final ManualLogRenderer logRenderer;

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
        List<ManualLogView> log = new ArrayList<>();
        for (ManualLogEntry entry : room.getLog()) {
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
                occupants,
                log,
                // ★ボタンの活性と実際の可否が同じ判定を通る(設計判断34 の型)
                ManualPermissions.denyUndo(actor, room) == null,
                ManualPermissions.denyRedo(actor, room) == null && room.getHistory().canRedo());
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
                zones.put(z, buildCards(source));
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
