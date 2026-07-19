package com.example.qte.game;

import org.springframework.stereotype.Component;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.EffectContext;
import com.example.qte.effect.TriggerType;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.room.GameRoom;

import lombok.RequiredArgsConstructor;

/**
 * ゲームの基本操作(プリミティブ)集。
 * 「ドローする」「回復する」「破壊判定する」といった、ルール本体(GameService)と
 * カード効果(CardEffectRegistry)の両方から使われる操作をここに集約する。
 *
 * GameServiceに置いたままだと効果側がGameServiceに依存して循環参照になるため、
 * 共通の土台としてこのクラスに切り出した(Batch 1の【知識】ad hoc実装の移設先)。
 */
@Component
@RequiredArgsConstructor
public class GameActions {

    /** 体力の上限。初期値20を超えて回復しない(※仮ルール。発注者確認待ち) */
    public static final int MAX_LP = 20;

    private final CardMasterRepository cards;

    /** ゾーン横断トリガー(マナ離脱→水龍)とON_ENTER発火のために参照する。
     *  Registry側はGameActionsをBean依存しない(ラムダは実行時にctx経由で受け取る)ため循環しない */
    private final CardEffectRegistry effects;

    /** ドロー。山札が空の状態で引こうとしたら敗北(発注者確認済み: デュエマ準拠) */
    public void drawCards(GameRoom room, PlayerState player, int count) {
        GameState state = room.getGameState();
        for (int i = 0; i < count; i++) {
            String cardId = player.getDeck().pollFirst();
            if (cardId == null) {
                room.addLog("%sの山札が尽きました".formatted(player.getDisplayName()));
                finish(room, state.opponentOf(player.getPlayerId()));
                return;
            }
            player.getHand().add(cardId);
        }
    }

    /** リーダーの回復(上限20) */
    public void healLeader(GameRoom room, PlayerState player, int amount) {
        int before = player.getLp();
        player.setLp(Math.min(MAX_LP, before + amount));
        room.addLog("%sのリーダーが%d回復(LP %d → %d)"
                .formatted(player.getDisplayName(), amount, before, player.getLp()));
    }

    /**
     * ミニオンを持ち主の手札に戻す(バウンス)。
     * 禁忌由来のミニオンは手札に戻らず消滅(Lost)ゾーンへ行く(総合ルール3-6)。
     */
    public void bounceToHand(GameRoom room, PlayerState owner, MinionInstance minion) {
        owner.getMinionZone().remove(minion);
        if (minion.isFromTaboo()) {
            owner.getLostZone().add(minion.getMaster().id());
            room.addLog("【%s】は禁忌カードのため消滅しました".formatted(minion.getMaster().name()));
            return;
        }
        owner.getHand().add(minion.getMaster().id());
        room.addLog("【%s】が手札に戻りました".formatted(minion.getMaster().name()));
    }

    /**
     * リーダーへのダメージ。火文明の被ダメージ参照カードのため、経路をここに一元化する。
     * 「ライフを減らす」表記もダメージとして扱う(発注者確認済み)。
     */
    public void damageLeader(GameRoom room, PlayerState player, int amount) {
        player.setLp(player.getLp() - amount);
        room.addLog("%sのリーダーに%dダメージ(残りLP %d)"
                .formatted(player.getDisplayName(), amount, player.getLp()));
        if (player.getLp() <= 0) {
            GameState state = room.getGameState();
            finish(room, state.opponentOf(player.getPlayerId()));
        }
        // TODO Batch 8(火文明): ターン内被ダメージ回数のカウントと
        // ON_LEADER_DAMAGEDトリガー(火炎の狂信者・反転の炎鏡)の発火をここで行う
    }

    /** 効果によるミニオンへのダメージ。適用と破壊判定を分離する原則に従う(設計判断2) */
    public void damageMinion(GameRoom room, PlayerState owner, MinionInstance minion, int amount) {
        minion.takeDamage(amount);
        room.addLog("【%s】に%dダメージ".formatted(minion.getMaster().name(), amount));
        checkDestruction(room, owner, minion);
    }

    /**
     * 破壊判定。HPが0以下なら場を離れる。
     * 【還元】持ちは墓地の代わりに裏向き・アンタップでマナへ置かれる
     * (マナが上限15枚のときは墓地へ。※仮ルール。発注者確認待ち)。
     */
    public void checkDestruction(GameRoom room, PlayerState owner, MinionInstance minion) {
        // 「戦闘では破壊されない」(ミカエル)のような破壊置換は光文明実装時にここへ差し込む
        if (minion.getCurrentHp() > 0) {
            return;
        }
        owner.getMinionZone().remove(minion);
        room.addLog("【%s】が破壊されました".formatted(minion.getMaster().name()));
        sendToTrashOrRestore(room, owner, minion.getMaster(), minion.isFromTaboo());
        // ON_DESTROYEDトリガー(執念の暗殺者など)は闇文明実装時にここで発火する
    }

    /** 使用し終わったスペルの後処理(通常は墓地、【還元】ならマナへ、禁忌由来なら消滅) */
    public void disposeUsedSpell(GameRoom room, PlayerState player, CardMaster spell, boolean fromTaboo) {
        sendToTrashOrRestore(room, player, spell, fromTaboo);
    }

    /**
     * 場・使用済みカードの行き先の判断を一元化する。
     * 優先順位: 禁忌由来(消滅) > 【還元】(裏向きでマナへ) > 墓地。
     * 禁忌由来のカードは墓地に行かないため、還元は構造的に機能しない(ルール3-6からの導出)。
     */
    private void sendToTrashOrRestore(GameRoom room, PlayerState owner, CardMaster card, boolean fromTaboo) {
        if (fromTaboo) {
            owner.getLostZone().add(card.id());
            room.addLog("【%s】は禁忌カードのため消滅しました".formatted(card.name()));
            return;
        }
        if (card.hasKeyword(Keyword.RESTORATION) && owner.getManaZone().size() < PlayerState.MAX_MANA) {
            ManaCard mana = new ManaCard(card.id(), false);
            mana.turnFaceDown();
            owner.getManaZone().add(mana); // アンタップ状態で置かれる(キーワード定義通り)
            room.addLog("【還元】【%s】が裏向きでマナに置かれました(マナ%d枚)"
                    .formatted(card.name(), owner.getManaZone().size()));
        } else {
            owner.getTrash().add(card.id());
        }
    }

    /**
     * 効果によってミニオンを場に「出す」(召喚ではない)。
     * 発注者確認済み裁定により【召喚時】(ON_SUMMON)は発動せず、
     * 登場時(ON_ENTER: 知識など)のみ発動する。ゾーン上限6体なら出せない。
     */
    public void putIntoFieldByEffect(GameRoom room, PlayerState owner, String cardId) {
        if (owner.isMinionZoneFull()) {
            return;
        }
        GameState state = room.getGameState();
        CardMaster master = cards.findById(cardId);
        MinionInstance minion = new MinionInstance(master, state.getTurnNumber());
        owner.getMinionZone().add(minion);
        room.addLog("【%s】が効果で場に出ました(召喚時効果は発動しない)".formatted(master.name()));
        effects.fire(TriggerType.ON_ENTER, minion, contextOf(room, owner, minion));
    }

    /** 「自分のマナがマナゾーンを離れた」イベントの発火。マナを動かした側が呼ぶ */
    public void manaLeft(GameRoom room, PlayerState owner) {
        effects.fireManaLeft(contextOf(room, owner, null));
    }

    private EffectContext contextOf(GameRoom room, PlayerState owner, MinionInstance source) {
        GameState state = room.getGameState();
        return new EffectContext(room, state, owner,
                state.opponentOf(owner.getPlayerId()), source, null, this);
    }

    public void finish(GameRoom room, PlayerState winner) {
        GameState state = room.getGameState();
        state.setStatus(GameStatus.FINISHED);
        state.setWinnerPlayerId(winner.getPlayerId());
        room.addLog("★ %s の勝利です ★".formatted(winner.getDisplayName()));
    }
}
