package com.example.qte.manual.view;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.qte.manual.ManualCardInstance;
import com.example.qte.manual.ManualCardMaster;
import com.example.qte.manual.ManualCardRepository;
import com.example.qte.manual.ManualGameState;
import com.example.qte.manual.ManualLogEntry;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualSeat;
import com.example.qte.manual.ManualZone;

import lombok.RequiredArgsConstructor;

/**
 * 部屋の状態から、在室者1人ぶんの配信ビューを組み立てる。
 * 既存の {@code GameViewBuilder} と同じ位置づけの部品である。
 *
 * <h2>★フェイズ1はフィルタを掛けない(設計書 6-1)</h2>
 * 一人回しは全公開であり、視点フィルタは動かない。
 * それでも {@link #build(ManualRoom, ManualOccupant)} が在室者を引数に取るのは、
 * フェイズ2の対戦・観戦でここが分岐点になるためである。
 * 引数を後から足すと、呼び出し側(配信)まで掘り返すことになる。
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

    private final ManualCardRepository cards;

    public ManualGameView build(ManualRoom room, ManualOccupant viewer) {
        ManualGameState state = room.getGameState();
        List<ManualOccupantView> occupants = new ArrayList<>();
        for (ManualOccupant occupant : room.getOccupants()) {
            occupants.add(new ManualOccupantView(occupant.getDisplayName(), occupant.getRole(),
                    occupant.isConnected(), occupant == viewer));
        }
        List<ManualLogView> log = new ArrayList<>();
        for (ManualLogEntry entry : room.getLog()) {
            log.add(new ManualLogView(entry.seq(), TIME_FORMAT.format(entry.at()), entry.text()));
        }
        return new ManualGameView(
                room.getRoomId(),
                viewer == null ? null : viewer.getOccupantId(),
                cards.getBackImageId(),
                state.getTurnNumber(),
                state.getPhase(),
                buildSeat(state.getSeatA()),
                buildSeat(state.getSeatB()),
                occupants,
                log,
                room.getHistory().canUndo(),
                room.getHistory().canRedo());
    }

    private ManualSeatView buildSeat(ManualSeat seat) {
        Map<ManualZone, List<ManualCardView>> zones = new EnumMap<>(ManualZone.class);
        for (ManualZone z : ManualZone.values()) {
            List<ManualCardView> views = new ArrayList<>();
            for (ManualCardInstance card : seat.zone(z)) {
                views.add(buildCard(card));
            }
            zones.put(z, views);
        }
        return new ManualSeatView(
                seat.getId(),
                seat.getLp(),
                seat.availableMp(),
                seat.isDeckLoaded(),
                seat.getDeckName(),
                seat.getLeader() == null ? null : buildCard(seat.getLeader()),
                zones);
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
                    card.isTapped(), card.isFaceDown(), card.isUsed(),
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
                List.copyOf(card.getLabels()),
                card.stackSize(),
                materials);
    }
}
