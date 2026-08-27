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

    /**
     * 入室したクライアントの購読準備完了通知。両者揃ったら試合を生成する。
     *
     * <p>★<b>Batch 66: 観戦者もこれを送る。</b>観戦者に「準備完了」は無いが、
     * 送らせないと<b>最初のビューが届くのが誰かの次の操作まで待ちになる</b>
     * (配信は誰かが動いたときにしか起きない)。席に着いていない人は
     * 準備の旗を立てず、{@code execute} の末尾の配信だけを受け取る。
     * ★席にも観戦者にも見つからない id は、理由を返して弾く。
     */
    @MessageMapping("/room/{roomId}/ready")
    public void ready(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request, room -> {
            var slot = room.findSlot(request.playerId()).orElse(null);
            if (slot == null) {
                room.findSpectator(request.playerId()).orElseThrow(
                        () -> new IllegalStateException("この部屋に入室していません"));
                return;
            }
            slot.setReady(true);
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
                room, request.playerId(), request.handIndex(), request.targets(), request.enhanced(),
                request.materialIds(), request.manaIndexes()));
    }

    /**
     * 手札のミニオンを【賢魂：n】として使う(★Batch 54。裁定152)。
     *
     * ★<b>{@code play-card} と別の宛先にしている。</b> どちらの姿で使うかは
     * カードの種別ではなく<b>プレイヤーの宣言</b>であり、宛先そのものが宣言になる。
     * 素材も強化コストも伴わないので {@code materialIds} / {@code enhanced} は読まない。
     */
    @MessageMapping("/room/{roomId}/play-soul")
    public void playSoul(@DestinationVariable String roomId, PlayCardRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playSoulCard(
                room, request.playerId(), request.handIndex(), request.targets(),
                request.manaIndexes()));
    }

    /** 禁忌カードの使用(メインフェイズのみ・マナで直接コストを支払う) */
    @MessageMapping("/room/{roomId}/play-taboo")
    public void playTaboo(@DestinationVariable String roomId, TabooRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playTabooCard(
                room, request.playerId(), request.tabooIndex(),
                request.manaIndexes(), request.targets(), request.materialIds()));
    }

    /**
     * 禁忌カードを【賢魂：n】として使う(★Batch 54。マスター裁定 A6)。
     * 退けるマナは n 枚である —— 賢魂として使うならコストは n だからである。
     */
    @MessageMapping("/room/{roomId}/play-taboo-soul")
    public void playTabooSoul(@DestinationVariable String roomId, TabooRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playTabooSoulCard(
                room, request.playerId(), request.tabooIndex(),
                request.manaIndexes(), request.targets()));
    }

    /** 【特殊召喚】(条件・代替コストによる代替召喚) */
    @MessageMapping("/room/{roomId}/special-summon")
    public void specialSummon(@DestinationVariable String roomId, PlayCardRequest request) {
        execute(roomId, request.playerId(), room -> gameService.specialSummon(
                room, request.playerId(), request.handIndex(), request.targets(),
                request.materialIds(), request.manaIndexes()));
    }

    /**
     * <b>墓地からの</b>【特殊召喚】(★Batch 53。《サモナーポップ・エンラ》)。
     * 手札からの特殊召喚と違うのは出どころだけで、素材も対象も同じ形で送る。
     */
    @MessageMapping("/room/{roomId}/special-summon-from-grave")
    public void specialSummonFromGrave(@DestinationVariable String roomId, GraveSummonRequest request) {
        execute(roomId, request.playerId(), room -> gameService.specialSummonFromGrave(
                room, request.playerId(), request.trashIndex(), request.targets(),
                request.materialIds()));
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

    /** ミニオンの起動能力(メインフェイズ・自身をタップ。a6) */
    @MessageMapping("/room/{roomId}/minion-ability")
    public void minionAbility(@DestinationVariable String roomId, MinionAbilityRequest request) {
        execute(roomId, request.playerId(), room -> gameService.useMinionAbility(
                room, request.playerId(), request.instanceId(), request.targets()));
    }

    /**
     * 墓地からのミニオン召喚(リーダー【黄泉の召喚主】のみ・サブフェイズ)。
     *
     * ★<b>Batch 60(裁定278(c)): 対象を選ぶ【召喚時】も通るようになった。</b>
     * 受け取る型を {@code TrashActionRequest} から {@link GraveSummonRequest} へ変えている ——
     * 墓地からの【特殊召喚】と<b>まったく同じ形</b>(墓地の位置 + 対象)であり、
     * 型を2つに分ける理由が無い。{@code materialIds} はこちらでは読まない
     * (墓地からの「召喚」で出せるのはミニオンだけで、進化は【特殊召喚】の側を通る)。
     */
    @MessageMapping("/room/{roomId}/summon-from-grave")
    public void summonFromGrave(@DestinationVariable String roomId, GraveSummonRequest request) {
        execute(roomId, request.playerId(), room -> gameService.summonFromGrave(
                room, request.playerId(), request.trashIndex(), request.targets()));
    }

    /** フェイズを1つ進める */
    @MessageMapping("/room/{roomId}/next-phase")
    public void nextPhase(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.nextPhase(room, request.playerId()));
    }

    /**
     * 割り込み選択の解決(a9)。効果の途中でプレイヤーに問い合わせた選択の答えを受け取る。
     * 降臨の伝道師をはじめ、風文明の各カードがこの1本の経路を共有する。
     */
    @MessageMapping("/room/{roomId}/resolve-choice")
    public void resolveChoice(@DestinationVariable String roomId, ResolveChoiceRequest request) {
        execute(roomId, request.playerId(), room -> gameService.resolveChoice(
                room, request.playerId(), request.chosenIndexes()));
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

    // ★Batch 60: TrashActionRequest(playerId + trashIndex だけ)は削除した。
    // 最後の使い手だった summon-from-grave が、裁定278(c) で対象を伴うようになり
    // GraveSummonRequest へ移ったためである。「墓地の位置を送る操作」の型は1つでよい。

    /**
     * @param materialIds ★Batch 52。進化召喚の素材にする自分の場のミニオンの instanceId。
     *                    進化ミニオン以外では空(または未送信)である
     */
    /**
     * @param manaIndexes ★Batch 70(裁定319): 払うマナの位置。
     *                    <b>クリックからのプレイだけが送ってくる</b> ——
     *                    ドラッグ&ドロップは空で送り、サーバが
     *                    {@code ManaPayment.normalOrder} の順に自動で払う(裁定315・316)。
     */
    public record PlayCardRequest(String playerId, int handIndex,
            List<TargetChoice> targets, boolean enhanced, List<String> materialIds,
            List<Integer> manaIndexes) {

        public List<String> materialIds() {
            return materialIds == null ? List.of() : materialIds;
        }

        public List<Integer> manaIndexes() {
            return manaIndexes == null ? List.of() : manaIndexes;
        }
    }

    /**
     * 墓地を出どころにする召喚(★Batch 53)。手札の位置ではなく墓地の位置を送る。
     *
     * ★Batch 60: <b>墓地からの【特殊召喚】と、墓地からの召喚(《黄泉の召喚主》)が共用する。</b>
     * 後者は {@code materialIds} を読まない —— 出せるのはミニオンだけだからである。
     */
    public record GraveSummonRequest(String playerId, int trashIndex,
            List<TargetChoice> targets, List<String> materialIds) {

        public List<String> materialIds() {
            return materialIds == null ? List.of() : materialIds;
        }
    }

    public record LeaderAbilityRequest(String playerId, List<TargetChoice> targets) {
    }

    public record MulliganRequest(String playerId, List<Integer> handIndexes) {
    }

    public record TabooRequest(String playerId, int tabooIndex,
            List<Integer> manaIndexes, List<TargetChoice> targets, List<String> materialIds) {

        public List<String> materialIds() {
            return materialIds == null ? List.of() : materialIds;
        }
    }

    public record AttackRequest(String playerId, String attackerInstanceId, String targetInstanceId) {
    }

    /** 割り込み選択の答え。選んだ候補の位置(PendingChoice.candidates内の0起点)の一覧 */
    public record ResolveChoiceRequest(String playerId, List<Integer> chosenIndexes) {
    }

    public record MinionAbilityRequest(String playerId, String instanceId, List<TargetChoice> targets) {
    }
}
