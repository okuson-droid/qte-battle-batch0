package com.example.qte.web;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;

import lombok.RequiredArgsConstructor;

/**
 * WebSocketメッセージの入口。MVCの@Controllerに相当する層で、役割も同じ:
 * 「受け取る・検証を業務層に任せる・結果を返す」に徹し、ルールの中身は持たない。
 *
 * すべてのハンドラは共通の型で処理する:
 *   1) 部屋を特定する
 *   2) synchronized(room.getLock()) で「1部屋1操作」に直列化する
 *   3) GameServiceで状態を変更する(ルール違反は例外で拒否される)
 *   4) 成功: 両者へビューを配信 / 失敗: 操作者にだけエラーを返す
 */
@Controller
@RequiredArgsConstructor
public class GameWsController {

    private final GameRoomManager roomManager;
    private final GameService gameService;
    private final GameBroadcaster broadcaster;

    /** 入室したクライアントの購読準備完了通知。両者揃ったら試合を生成する */
    @MessageMapping("/room/{roomId}/ready")
    public void ready(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request, room -> {
            room.findSlot(request.playerId())
                    .orElseThrow(() -> new IllegalStateException("この部屋に入室していません"))
                    .setReady(true);
            gameService.startIfBothReady(room);
        });
    }

    /** ダイス勝者による先攻/後攻の選択 */
    @MessageMapping("/room/{roomId}/choose-order")
    public void chooseOrder(@DestinationVariable String roomId, ChooseOrderRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.chooseOrder(room, request.playerId(), request.goFirst()));
    }

    /** マナチャージ */
    @MessageMapping("/room/{roomId}/charge-mana")
    public void chargeMana(@DestinationVariable String roomId, HandActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.chargeMana(room, request.playerId(), request.handIndex()));
    }

    /** 手札のカードをプレイ(ミニオン召喚・スペル使用)。対象指定があればtargetsに載せて送られる */
    @MessageMapping("/room/{roomId}/play-card")
    public void playCard(@DestinationVariable String roomId, PlayCardRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playCard(
                room, request.playerId(), request.handIndex(), request.targets()));
    }

    /** 禁忌カードの使用(メインフェイズのみ・マナで直接コストを支払う) */
    @MessageMapping("/room/{roomId}/play-taboo")
    public void playTaboo(@DestinationVariable String roomId, TabooRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playTabooCard(
                room, request.playerId(), request.tabooIndex(),
                request.manaIndexes(), request.targets()));
    }

    /** 【特殊召喚】(条件・代替コストによる代替召喚) */
    @MessageMapping("/room/{roomId}/special-summon")
    public void specialSummon(@DestinationVariable String roomId, PlayCardRequest request) {
        execute(roomId, request.playerId(), room -> gameService.specialSummon(
                room, request.playerId(), request.handIndex(), request.targets()));
    }

    /** 攻撃(targetInstanceIdがnullならリーダー攻撃) */
    @MessageMapping("/room/{roomId}/attack")
    public void attack(@DestinationVariable String roomId, AttackRequest request) {
        execute(roomId, request.playerId(), room -> gameService.attack(
                room, request.playerId(), request.attackerInstanceId(), request.targetInstanceId()));
    }

    /** マリガン(手札の引き直し)。handIndexesが空なら引き直しなしで確定 */
    @MessageMapping("/room/{roomId}/mulligan")
    public void mulligan(@DestinationVariable String roomId, MulliganRequest request) {
        execute(roomId, request.playerId(), room -> gameService.mulligan(
                room, request.playerId(), request.handIndexes()));
    }

    /** リーダーの攻撃(ウェポン装備時のみ)。targetInstanceIdがnullならリーダー攻撃 */
    @MessageMapping("/room/{roomId}/leader-attack")
    public void leaderAttack(@DestinationVariable String roomId, AttackRequest request) {
        execute(roomId, request.playerId(), room -> gameService.leaderAttack(
                room, request.playerId(), request.targetInstanceId()));
    }

    /** リーダーの起動能力(メインフェイズ・1ターン1回) */
    @MessageMapping("/room/{roomId}/leader-ability")
    public void leaderAbility(@DestinationVariable String roomId, LeaderAbilityRequest request) {
        execute(roomId, request.playerId(), room -> gameService.useLeaderAbility(
                room, request.playerId(), request.targets()));
    }

    /**
     * 墓地からのミニオン召喚(リーダー【黄泉の召喚主】のみ・サブフェイズ)。
     * UIからの呼び出しはBatch 10bで追加する。
     */
    @MessageMapping("/room/{roomId}/summon-from-grave")
    public void summonFromGrave(@DestinationVariable String roomId, TrashActionRequest request) {
        execute(roomId, request.playerId(), room -> gameService.summonFromGrave(
                room, request.playerId(), request.trashIndex()));
    }

    /** フェイズを1つ進める */
    @MessageMapping("/room/{roomId}/next-phase")
    public void nextPhase(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.nextPhase(room, request.playerId()));
    }

    /**
     * 降臨の伝道師: 公開した4枚の中から場に出す【守護】ミニオンを選ぶ(新しいUI)。
     * 複数の【守護】があるときだけ呼ばれる(0体/1体は召喚時に自動で解決される)。
     */
    @MessageMapping("/room/{roomId}/resolve-reveal")
    public void resolveReveal(@DestinationVariable String roomId, RevealChoiceRequest request) {
        execute(roomId, request.playerId(), room -> gameService.resolveRevealChoice(
                room, request.playerId(), request.chosenIndex()));
    }

    /** ターン終了(残りフェイズを飛ばして相手にターンを渡す) */
    @MessageMapping("/room/{roomId}/end-turn")
    public void endTurn(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.endTurn(room, request.playerId()));
    }

    // ---- 共通処理 ----

    private void execute(String roomId, ActionRequest request, RoomAction action) {
        execute(roomId, request.playerId(), action);
    }

    private void execute(String roomId, String playerId, RoomAction action) {
        GameRoom room = roomManager.findRoom(roomId)
                .orElse(null);
        if (room == null) {
            broadcaster.sendError(roomId, playerId, "部屋が見つかりません: " + roomId);
            return;
        }
        try {
            synchronized (room.getLock()) {
                action.apply(room);
            }
            broadcaster.broadcast(room);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // ルール違反: 状態は変更されていないので、操作者にだけ理由を返す
            broadcaster.sendError(roomId, playerId, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface RoomAction {
        void apply(GameRoom room);
    }

    // ---- クライアントから受け取るメッセージの型 ----

    public record ActionRequest(String playerId) {
    }

    public record ChooseOrderRequest(String playerId, boolean goFirst) {
    }

    public record HandActionRequest(String playerId, int handIndex) {
    }

    public record TrashActionRequest(String playerId, int trashIndex) {
    }

    public record PlayCardRequest(String playerId, int handIndex, List<TargetChoice> targets) {
    }

    public record LeaderAbilityRequest(String playerId, List<TargetChoice> targets) {
    }

    public record MulliganRequest(String playerId, List<Integer> handIndexes) {
    }

    public record TabooRequest(String playerId, int tabooIndex,
            List<Integer> manaIndexes, List<TargetChoice> targets) {
    }

    public record AttackRequest(String playerId, String attackerInstanceId, String targetInstanceId) {
    }

    public record RevealChoiceRequest(String playerId, int chosenIndex) {
    }
}
