package com.example.qte.game;

import org.springframework.stereotype.Component;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.EffectContext;
import com.example.qte.effect.PendingChoice;
import com.example.qte.effect.PersistentAura;
import com.example.qte.effect.RuleGuards;
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

    // ---------------------------------------------------------------
    // ★Batch 47: このクラスが挙動を実装しているカード。
    // 以前はメソッドの中に文字列リテラルで直接書かれていた(裁定130 と同じ理由で定数化)。
    // ---------------------------------------------------------------

    private static final String SEALED_TABOO_DEMON = "QTE-M-DARK-18"; // 封印されし禁忌魔人
    private static final String EARTH_AEGIS = "QTE-M-EARTH-13";       // 大地の守護盾(肩代わり)
    private static final String CHANT_ORB = "QTE-M-LIGHT-28";         // 詠唱の宝珠(場を離れたとき)

    /**
     * このクラスが挙動を実装しているカード(★Batch 47)。
     * 趣旨と番人は {@link com.example.qte.effect.RuleGuards#IMPLEMENTED_CARDS} の説明を参照。
     */
    public static final java.util.Set<String> IMPLEMENTED_CARDS =
            java.util.Set.of(SEALED_TABOO_DEMON, EARTH_AEGIS, CHANT_ORB);

    /**
     * 「コストを支払わず場に出せない」カード(封印されし禁忌魔人)。
     * 蘇生・踏み倒し系の効果はここを必ず経由して弾く。
     * カードIDの直書きだが、判定を1か所に閉じ込めることを優先している。
     */
    private static final java.util.Set<String> NO_CHEAT_INTO_FIELD = java.util.Set.of(SEALED_TABOO_DEMON);

    private final CardMasterRepository cards;

    /** ゾーン横断トリガー(マナ離脱→水龍)とON_ENTER発火のために参照する。
     *  Registry側はGameActionsをBean依存しない(ラムダは実行時にctx経由で受け取る)ため循環しない */
    private final CardEffectRegistry effects;

    /** 「できる/できない」「起きる/起きない」の判定層(光文明の置換・禁止効果) */
    private final RuleGuards guards;

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
            // 【断罪の大天使】: このターンの3枚目以降のドローは、引く代わりに墓地へ置く。
            // 山札からカードを取り除く点は同じであり、行き先だけが変わる置換効果である
            boolean replaced = guards.isDrawReplaced(state, player, player.getDrawnCountThisTurn());
            player.setDrawnCountThisTurn(player.getDrawnCountThisTurn() + 1);
            if (replaced) {
                player.getTrash().add(cardId);
                room.addLog("【断罪の大天使】により、%sのドローは墓地に置かれました"
                        .formatted(player.getDisplayName()));
                continue;
            }
            player.getHand().add(cardId);
        }
    }

    /** 発生源を明示しない回復。文明別の累計量には計上されない */
    public void healLeader(GameRoom room, PlayerState player, int amount) {
        healLeader(room, player, amount, null);
    }

    /**
     * リーダーの回復(上限20)。回復の「回数」と、発生源の文明ごとの「累計量」の両方を数える。
     *
     * 累計量は、要求された量ではなく実際にLPが増えた量で数える(Ver.0.4)。
     * 「回復した」の素直な読みであり、体力が満タンの状態で回復を撃つだけで
     * 鳳凰神ヴォルカニクスレヴォの条件が貯まってしまうのを避けるためでもある。
     *
     * @param sourceCardId 回復させたカードのID。damageLeader の発生源引数と同じ役割で、
     *                     カードの文明を引くために使う。不明ならnull(文明別の集計に入らない)
     */
    public void healLeader(GameRoom room, PlayerState player, int amount, String sourceCardId) {
        int before = player.getLp();
        player.setLp(Math.min(MAX_LP, before + amount));
        int healed = player.getLp() - before;
        player.setHealedCountThisTurn(player.getHealedCountThisTurn() + 1);
        if (sourceCardId != null) {
            player.recordHealedAmount(healed, cards.findById(sourceCardId).civilization());
        }
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
        damageLeader(room, player, amount, null);
    }

    /**
     * @param sourceCardId ダメージの発生源カードID。自己誘発を禁じるカード
     *                     (反転の炎鏡「このカード以外の効果で」)の判定に使う。
     *                     戦闘ダメージなどカード効果由来でない場合はnull。
     */
    public void damageLeader(GameRoom room, PlayerState player, int amount, String sourceCardId) {
        // 【正義の御盾】などの軽減はここで一括して適用する。
        // 軽減後が0なら「ダメージを受けなかった」ものとして扱い、被ダメージトリガーも発火しない
        int reduced = guards.reduceLeaderDamage(room.getGameState(), player, amount);
        if (reduced <= 0) {
            room.addLog("%sのリーダーへのダメージは軽減されました".formatted(player.getDisplayName()));
            return;
        }
        amount = reduced;
        player.setLp(player.getLp() - amount);
        player.setLeaderDamagedCountThisTurn(player.getLeaderDamagedCountThisTurn() + 1);
        room.addLog("%sのリーダーに%dダメージ(残りLP %d)"
                .formatted(player.getDisplayName(), amount, player.getLp()));

        GameState state = room.getGameState();
        if (player.getLp() <= 0) {
            finish(room, state.opponentOf(player.getPlayerId()));
            return; // 決着後はトリガーを発火しない
        }
        // リーダー被ダメージのトリガー(火炎の狂信者・反転の炎鏡)。
        // 炎鏡自身が与えたダメージでは炎鏡を再誘発させないため、発生源を渡す
        effects.fireLeaderDamaged(contextOf(room, player, null), sourceCardId);
    }

    /**
     * 効果による破壊(ダメージを経由しない)。フレイム・スナイプなど。
     * 行き先の判断(消滅/還元/墓地)はcheckDestructionと同じ経路を使う。
     */
    public void destroyMinion(GameRoom room, PlayerState owner, MinionInstance minion) {
        destroyMinion(room, owner, minion, DestructionCause.EFFECT);
    }

    /** 破壊原因を明示する版。原因は破壊時トリガーの条件になる(ボーン・コレクター) */
    public void destroyMinion(GameRoom room, PlayerState owner, MinionInstance minion,
            DestructionCause cause) {
        if (!owner.getMinionZone().contains(minion)) {
            return; // 連鎖的な破壊で既に場を離れている場合がある
        }
        if (preventDestruction(room, owner, minion, cause)) {
            return;
        }
        leaveFieldByDestruction(room, owner, minion, cause);
    }

    /** 装備中ウェポンの破壊(武具昇華の炎)。破壊できたらtrue */
    public boolean destroyOwnWeapon(GameRoom room, PlayerState owner) {
        CardMaster weapon = owner.getEquippedWeapon();
        if (weapon == null) {
            return false;
        }
        owner.setEquippedWeapon(null);
        room.addLog("【%s】が破壊されました".formatted(weapon.name()));
        onWeaponLeftPlay(owner, weapon);
        sendToTrashOrRestore(room, owner, weapon, owner.isEquippedWeaponFromTaboo());
        owner.setEquippedWeaponFromTaboo(false);
        return true;
    }

    /** 効果によるミニオンへのダメージ。適用と破壊判定を分離する原則に従う(設計判断2) */
    public void damageMinion(GameRoom room, PlayerState owner, MinionInstance minion, int amount) {
        applyDamageToMinion(room, owner, minion, amount);
        checkDestruction(room, owner, minion, DestructionCause.EFFECT);
    }

    /**
     * 戦闘ダメージの適用。破壊判定は含めない。
     * ミニオン同士の戦闘は「両者に同時にダメージ → その後まとめて破壊判定」という順序のため、
     * 適用と判定を呼び出し側(GameService.attack)が分けて呼べるように分離している。
     */
    public void dealCombatDamage(GameRoom room, PlayerState owner, MinionInstance minion, int amount) {
        applyDamageToMinion(room, owner, minion, amount);
    }

    /** ダメージの適用と被ダメージトリガー(獄門の裁定者)の発火。全てのダメージ経路がここを通る */
    private void applyDamageToMinion(GameRoom room, PlayerState owner, MinionInstance minion, int amount) {
        if (amount <= 0) {
            return;
        }
        minion.takeDamage(amount);
        room.addLog("【%s】に%dダメージ".formatted(minion.getMaster().name(), amount));
        effects.fire(TriggerType.ON_MINION_DAMAGED, minion, contextOf(room, owner, minion));
    }

    /**
     * 破壊判定。HPが0以下なら場を離れる。
     * 【還元】持ちは墓地の代わりに裏向き・アンタップでマナへ置かれる
     * (マナが上限15枚のときは墓地へ。※仮ルール。発注者確認待ち)。
     */
    public void checkDestruction(GameRoom room, PlayerState owner, MinionInstance minion) {
        checkDestruction(room, owner, minion, DestructionCause.EFFECT);
    }

    /** 破壊原因を明示する版 */
    public void checkDestruction(GameRoom room, PlayerState owner, MinionInstance minion,
            DestructionCause cause) {
        if (minion.getCurrentHp() > 0 || !owner.getMinionZone().contains(minion)) {
            return;
        }
        // 破壊の置換(大天使ミカエル・聖光の守護聖)。
        // HPが0以下でも破壊されずに場へ残る点に注意(次の効果ダメージや効果破壊では処理される)
        if (preventDestruction(room, owner, minion, cause)) {
            return;
        }
        leaveFieldByDestruction(room, owner, minion, cause);
    }

    /** 破壊が無効化されるかを判定し、無効化された場合はログを残してtrueを返す */
    private boolean preventDestruction(GameRoom room, PlayerState owner, MinionInstance minion,
            DestructionCause cause) {
        if (!guards.isDestructionPrevented(room.getGameState(), owner, minion, cause)) {
            return false;
        }
        room.addLog("【%s】は破壊されなかった".formatted(minion.getMaster().name()));
        return true;
    }

    /**
     * ミニオンの破壊処理の唯一の実体。全ての破壊経路がここに合流する。
     *
     * 順序が意味を持つ: 場から取り除く → 行き先を決める(墓地/還元/消滅) →
     * ターン内の破壊記録 → 破壊されたミニオン自身のトリガー →
     * 場に残っている味方の監視トリガー。
     * 記録を先に行うのは、監視側の効果(不滅のネクロマンサーの蘇生)が
     * 「墓地にあること」を前提にするためである。
     */
    private void leaveFieldByDestruction(GameRoom room, PlayerState owner, MinionInstance minion,
            DestructionCause cause) {
        owner.getMinionZone().remove(minion);
        room.addLog("【%s】が破壊されました".formatted(minion.getMaster().name()));
        boolean wentToTrash = sendToTrashOrRestore(room, owner, minion.getMaster(), minion.isFromTaboo());

        owner.setOwnMinionDestroyedThisTurn(true);
        if (wentToTrash) {
            owner.getMinionsDestroyedThisTurn().add(minion.getMaster().id());
        }
        // 試合単位の破壊数(★Batch 48。天翔ケル霊鬼・シュテンの特殊召喚条件)。
        // 上の2行と違い、行き先(墓地・消滅・還元)を問わず「破壊された」事実だけを数える
        GameState state = room.getGameState();
        state.setMinionsDestroyedThisTurn(state.getMinionsDestroyedThisTurn() + 1);

        EffectContext ctx = contextOf(room, owner, minion);
        effects.fire(TriggerType.ON_DESTROYED, minion, ctx);
        if (cause == DestructionCause.COMBAT) {
            effects.fire(TriggerType.ON_DESTROYED_BY_COMBAT, minion, ctx);
        }
        effects.fireOwnMinionDestroyed(contextOf(room, owner, null), minion.getMaster().id());
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
    private boolean sendToTrashOrRestore(GameRoom room, PlayerState owner, CardMaster card, boolean fromTaboo) {
        if (fromTaboo) {
            owner.getLostZone().add(card.id());
            room.addLog("【%s】は禁忌カードのため消滅しました".formatted(card.name()));
            return false;
        }
        if (card.hasKeyword(Keyword.RESTORATION) && owner.getManaZone().size() < PlayerState.MAX_MANA) {
            ManaCard mana = new ManaCard(card.id(), false);
            mana.turnFaceDown();
            owner.getManaZone().add(mana); // アンタップ状態で置かれる(キーワード定義通り)
            room.addLog("【還元】【%s】が裏向きでマナに置かれました(マナ%d枚)"
                    .formatted(card.name(), owner.getManaZone().size()));
            return false;
        }
        owner.getTrash().add(card.id());
        return true;
    }

    /**
     * 効果によってミニオンを場に「出す」(召喚ではない)。
     * 発注者確認済み裁定により【召喚時】(ON_SUMMON)は発動せず、
     * 登場時(ON_ENTER: 知識など)のみ発動する。ミニオンゾーンが上限なら出せない。
     */
    public void putIntoFieldByEffect(GameRoom room, PlayerState owner, String cardId) {
        if (owner.isMinionZoneFull() || NO_CHEAT_INTO_FIELD.contains(cardId)) {
            return;
        }
        GameState state = room.getGameState();
        CardMaster master = cards.findById(cardId);
        MinionInstance minion = new MinionInstance(master, state.getTurnNumber());
        owner.getMinionZone().add(minion);
        room.addLog("【%s】が効果で場に出ました(召喚時効果は発動しない)".formatted(master.name()));
        EffectContext ctx = contextOf(room, owner, minion);
        effects.fire(TriggerType.ON_ENTER, minion, ctx);
        // 装備中のウェポンが「自分のミニオンが場に出た」に反応する(禁忌の冥魔剣)。
        // 蘇生・効果による「出す」でも発動するため、ON_ENTER の隣に置く(発注者確認済み)
        effects.fireAllyMinionEvent(TriggerType.ON_ALLY_MINION_ENTER, ctx);
    }

    // ---------------------------------------------------------------
    // 闇文明の基本操作(Batch 10a)
    // ---------------------------------------------------------------

    /** 「コストを支払わず場に出せない」カードか。蘇生系の効果が事前判定に使う */
    public boolean isCheatIntoFieldBlocked(String cardId) {
        return NO_CHEAT_INTO_FIELD.contains(cardId);
    }

    /**
     * 山札の上からN枚を墓地に置く(ミル)。
     * ドローとは異なり、山札が尽きても敗北しない(引いていないため)。
     *
     * @return 実際に墓地へ置いた枚数
     */
    public int mill(GameRoom room, PlayerState player, int count) {
        int moved = 0;
        for (int i = 0; i < count; i++) {
            String cardId = player.getDeck().pollFirst();
            if (cardId == null) {
                break;
            }
            player.getTrash().add(cardId);
            moved++;
        }
        room.addLog("%sが山札の上から%d枚を墓地に置きました(墓地%d枚)"
                .formatted(player.getDisplayName(), moved, player.getTrash().size()));
        return moved;
    }

    /**
     * 墓地のミニオンを場に「出す」(蘇生)。召喚ではないためON_ENTERのみ発動する。
     * 「コストを支払わず場に出せない」カードはここで弾かれる。
     *
     * @return 蘇生できたらtrue(墓地に無い・ゾーン満杯・踏み倒し禁止ならfalse)
     */
    public boolean reviveFromGrave(GameRoom room, PlayerState owner, String cardId) {
        if (!owner.getTrash().contains(cardId) || owner.isMinionZoneFull()
                || NO_CHEAT_INTO_FIELD.contains(cardId)) {
            return false;
        }
        owner.getTrash().remove(cardId);
        putIntoFieldByEffect(room, owner, cardId);
        return true;
    }

    /** 墓地のカード1枚を手札に戻す(墓場の怨念集合体・死霊の収鎌) */
    public boolean returnFromTrashToHand(GameRoom room, PlayerState owner, String cardId) {
        if (!owner.getTrash().remove(cardId)) {
            return false;
        }
        owner.getHand().add(cardId);
        room.addLog("【%s】が墓地から手札に戻りました".formatted(cards.findById(cardId).name()));
        return true;
    }

    /**
     * 表向きのマナを裏向きにする(マナを貪る怨霊・カース・ボーン)。
     *
     * @return 実際に裏向きにできた枚数
     */
    public int turnManaFaceDown(GameRoom room, PlayerState owner, int count) {
        int turned = 0;
        for (ManaCard mana : owner.getManaZone()) {
            if (turned >= count) {
                break;
            }
            if (mana.isFaceUp()) {
                mana.turnFaceDown();
                turned++;
            }
        }
        if (turned > 0) {
            room.addLog("%sのマナ%d枚が裏向きになりました".formatted(owner.getDisplayName(), turned));
        }
        return turned;
    }

    /** 裏向きのマナを表向きに戻す(禁忌の冥魔剣)。戻せた枚数を返す */
    public int turnManaFaceUp(GameRoom room, PlayerState owner, int count) {
        int turned = 0;
        for (ManaCard mana : owner.getManaZone()) {
            if (turned >= count) {
                break;
            }
            if (!mana.isFaceUp()) {
                mana.turnFaceUp();
                turned++;
            }
        }
        if (turned > 0) {
            room.addLog("%sのマナ%d枚が表向きに戻りました".formatted(owner.getDisplayName(), turned));
        }
        return turned;
    }

    /**
     * 裏向きのマナを破壊する(禁忌の代償・不滅のネクロマンサー)。
     * 総則では「マナの墓地送り」は破壊として扱わないが、これらのカードはテキストで
     * 「破壊」と書いているため、テキスト優先の原則によりここでは破壊として扱う。
     * 破壊されたカードは墓地へ行き、マナ離脱イベントが発火する。
     *
     * @return 実際に破壊できた枚数
     */
    public int destroyFaceDownMana(GameRoom room, PlayerState owner, int count) {
        int destroyed = 0;
        for (int i = owner.getManaZone().size() - 1; i >= 0 && destroyed < count; i--) {
            ManaCard mana = owner.getManaZone().get(i);
            if (mana.isFaceUp()) {
                continue;
            }
            owner.getManaZone().remove(i);
            owner.getTrash().add(mana.getCardId());
            destroyed++;
            room.addLog("裏向きのマナ【%s】が破壊されました".formatted(cards.findById(mana.getCardId()).name()));
        }
        if (destroyed > 0) {
            manaLeft(room, owner);
        }
        return destroyed;
    }

    /** 裏向きのマナ1枚を手札に戻す(冥府の禁皇の起動能力)。戻せたらtrue */
    public boolean returnFaceDownManaToHand(GameRoom room, PlayerState owner) {
        for (int i = owner.getManaZone().size() - 1; i >= 0; i--) {
            ManaCard mana = owner.getManaZone().get(i);
            if (mana.isFaceUp()) {
                continue;
            }
            owner.getManaZone().remove(i);
            owner.getHand().add(mana.getCardId());
            room.addLog("裏向きのマナ【%s】が手札に戻りました"
                    .formatted(cards.findById(mana.getCardId()).name()));
            manaLeft(room, owner);
            return true;
        }
        return false;
    }

    /**
     * 墓地のカードを裏向きでマナゾーンに置く(禁忌の墓地利用)。
     * マナチャージの1回制限とは別枠。マナ上限15枚に達している場合は置けない。
     */
    public boolean putTrashCardIntoManaFaceDown(GameRoom room, PlayerState owner, String cardId) {
        if (owner.getManaZone().size() >= PlayerState.MAX_MANA || !owner.getTrash().remove(cardId)) {
            return false;
        }
        ManaCard mana = new ManaCard(cardId, false);
        mana.turnFaceDown();
        owner.getManaZone().add(mana);
        room.addLog("【%s】が墓地から裏向きでマナに置かれました(マナ%d枚)"
                .formatted(cards.findById(cardId).name(), owner.getManaZone().size()));
        return true;
    }

    /** 「自分のマナがマナゾーンを離れた」イベントの発火。マナを動かした側が呼ぶ */
    public void manaLeft(GameRoom room, PlayerState owner) {
        effects.fireManaLeft(contextOf(room, owner, null));
    }

    // ---------------------------------------------------------------
    // 光文明の基本操作(Batch 11b)
    // ---------------------------------------------------------------

    /**
     * 詠唱の宝珠(QTE-M-LIGHT-28): ウェポンが場を離れたとき、次に唱えるスペルのコスト-1を付与する。
     * 破壊(destroyOwnWeapon)・新しいウェポンへの付け替え(GameService.equipWeapon)の
     * どちらの経路でも発動する(発注者確認済み)。ウェポンには破壊トリガーの仕組みがまだ無いため、
     * 「ウェポンが場を離れる」2箇所の処理から直接呼ぶ形にしている。
     */
    public void onWeaponLeftPlay(PlayerState owner, CardMaster weapon) {
        if (CHANT_ORB.equals(weapon.id())) {
            owner.getPersistentAuras().add(PersistentAura.untilNextSpell(CHANT_ORB));
        }
        // 暴風の双剣がこのターン積み上げた攻撃力の加算は、ウェポンが外れた時点で消える
        owner.setWeaponAttackBonusThisTurn(0);
        // 「このターン攻撃した」の記録は、あくまで今場にあるウェポンについてのものである(Ver.0.4)。
        // ここで落とすことで「攻撃した後に別のウェポンへ付け替えたら、新しいウェポンは
        // (攻撃していないため)ターン終了時に壊れない」という裁定が成立する。
        // 破壊・付け替えの両経路がこのメソッドを必ず通るため、落とす場所はここ1箇所でよい
        owner.setWeaponAttackedThisTurn(false);
    }

    /**
     * 山札の上からcount枚を表向きに取り出す(降臨の伝道師)。
     * ミルと違い、取り出したカードは呼び出し元が行き先(場に出す/山札の下に戻す)を決める。
     * 山札が尽きればそこまでしか取り出せない。
     */
    public java.util.List<String> revealFromTopOfDeck(GameRoom room, PlayerState player, int count) {
        java.util.List<String> revealed = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String cardId = player.getDeck().pollFirst();
            if (cardId == null) {
                break;
            }
            revealed.add(cardId);
        }
        room.addLog("%sが山札の上から%d枚を公開しました".formatted(player.getDisplayName(), revealed.size()));
        return revealed;
    }

    /** 公開した束を山札の下に、公開した順のまま戻す(降臨の伝道師) */
    public void returnToBottomOfDeck(PlayerState player, java.util.List<String> cardIds) {
        cardIds.forEach(id -> player.getDeck().addLast(id));
    }

    // ---------------------------------------------------------------
    // 風文明の基本操作(Batch 12a)
    // ---------------------------------------------------------------

    /**
     * 手札のカードを裏向きでマナゾーンに置く(a8。風のマナ変換)。
     *
     * マナチャージ(総合ルール6章-3)とは別の処理であり、1ターン1回の制限は立てない。
     * マナ上限15枚の判定だけは行う。
     *
     * 裏向きマナは闇文明が参照する資源(禁忌の代償・不滅のネクロマンサー)であり、
     * これは風から闇の資源を生成する初めての経路になる。
     *
     * @return 置けたらtrue(手札の指定が不正、またはマナが上限ならfalse)
     */
    public boolean putHandCardIntoManaFaceDown(GameRoom room, PlayerState owner, int handIndex) {
        if (handIndex < 0 || handIndex >= owner.getHand().size()) {
            return false;
        }
        if (owner.getManaZone().size() >= PlayerState.MAX_MANA) {
            room.addLog("マナが15枚のため、これ以上マナに置けません");
            return false;
        }
        String cardId = owner.getHand().remove(handIndex);
        ManaCard mana = new ManaCard(cardId, false);
        mana.turnFaceDown();
        owner.getManaZone().add(mana);
        room.addLog("%sが手札1枚を裏向きでマナに置きました(マナ%d枚)"
                .formatted(owner.getDisplayName(), owner.getManaZone().size()));
        return true;
    }

    /**
     * 表向きのマナ1枚を手札に戻す(Batch 12b。風のマナ変換)。
     * 既存の {@link #returnFaceDownManaToHand} は裏向き専用であり、表向き版が無かった。
     * 戻す1枚は末尾(最後に置かれたもの)から探す。
     *
     * @return 戻せたらtrue(表向きのマナが1枚もなければfalse)
     */
    public boolean returnFaceUpManaToHand(GameRoom room, PlayerState owner) {
        for (int i = owner.getManaZone().size() - 1; i >= 0; i--) {
            ManaCard mana = owner.getManaZone().get(i);
            if (!mana.isFaceUp()) {
                continue;
            }
            owner.getManaZone().remove(i);
            owner.getHand().add(mana.getCardId());
            room.addLog("表向きのマナ【%s】が手札に戻りました"
                    .formatted(cards.findById(mana.getCardId()).name()));
            manaLeft(room, owner);
            return true;
        }
        return false;
    }

    /**
     * マナゾーンの指定位置のカード1枚を手札に戻す(Batch 13c。地砕きの突撃兵の攻撃時効果)。
     *
     * 表向き・裏向きのどちらでも戻せる({@link #returnFaceUpManaToHand} は「向きで自動選択する」
     * 用途、こちらは「プレイヤーが選んだ1枚を戻す」用途であり役割が異なる)。
     *
     * マナがマナゾーンを離れるため、ゾーン横断トリガー({@link #manaLeft})の発火まで
     * このメソッドの中で行う。呼び出し側で発火を書くと漏れるため、ここに閉じている。
     *
     * @param index マナゾーン内の位置(0起点)
     * @return 戻せたらtrue(位置が範囲外ならfalse)
     */
    public boolean returnManaToHandAt(GameRoom room, PlayerState owner, int index) {
        if (index < 0 || index >= owner.getManaZone().size()) {
            return false;
        }
        ManaCard mana = owner.getManaZone().remove(index);
        owner.getHand().add(mana.getCardId());
        room.addLog("%sのマナ【%s】が手札に戻りました"
                .formatted(owner.getDisplayName(), cards.findById(mana.getCardId()).name()));
        manaLeft(room, owner);
        return true;
    }

    // ---------------------------------------------------------------
    // 土文明の基本操作(Batch 13a)
    //
    // 土のテーマは「マナ加速」であり、山札や手札のカードを表向きでマナゾーンへ置く
    // カードが繰り返し登場する。既存のマナ配置ヘルパ(putHandCardIntoManaFaceDown 等)は
    // すべて闇文明のための「裏向き」であり、表向きで置く経路が無かった。
    //
    // 配置の入口を placeCardInManaFaceUp の1箇所に集約する理由は2つある。
    //   1. マナ上限15枚の判定を1箇所にまとめる。
    //   2. 豊穣の地霊主(L012)が参照する「このターン何回マナに置かれたか」の計数と、
    //      配置イベント(ON_MANA_PLACED相当)の発火を、配置経路すべてで漏れなく行う。
    // マナチャージ(GameService.chargeMana)もこの入口を通す(発注者確認済み: マナチャージも
    // 配置回数に含む)。
    // ---------------------------------------------------------------

    /**
     * カード1枚を表向き・アンタップでマナゾーンに置く(土文明のマナ加速の唯一の入口)。
     *
     * マナが上限(15枚)に達している場合は置かず、計数もイベント発火もしない。
     * 置けた場合はターン内のマナ配置カウンタを進め、fireManaPlaced を発火する
     * (豊穣の地霊主が「2回目なら1ドロー」で反応する)。
     *
     * @return 置けたらtrue、マナ上限で置けなければfalse
     */
    public boolean placeCardInManaFaceUp(GameRoom room, PlayerState owner, String cardId) {
        if (owner.getManaZone().size() >= PlayerState.MAX_MANA) {
            room.addLog("マナが15枚のため、これ以上マナに置けません");
            return false;
        }
        // ManaCard(cardId, temporary) の第2引数は「一時マナか」であり、faceUpは既定でtrue
        owner.getManaZone().add(new ManaCard(cardId, false));
        owner.recordManaPlacement(room.getGameState().getTurnNumber());
        effects.fireManaPlaced(contextOf(room, owner, null));
        return true;
    }

    /**
     * 自分の山札の上から1枚を表向きでマナゾーンに置く。
     * 大地の精霊グラン・ガイア・リソース・豊穣の祈り・大地の恵み・ガイア・ハンマー・
     * 地砕きの突撃兵(破壊時)が共通で使う。
     *
     * 山札が空の場合は何も置かない(placeCardInManaFaceUp と同様、配置が起きなければ計数もしない)。
     * ドロー(空の山札からのドローは敗北)とは異なり、山札切れでマナに置けないだけでは敗北しない。
     *
     * @return 置けたらtrue
     */
    public boolean placeTopOfDeckInManaFaceUp(GameRoom room, PlayerState owner) {
        String cardId = owner.getDeck().pollFirst();
        if (cardId == null) {
            room.addLog("%sの山札が空のため、マナに置けませんでした".formatted(owner.getDisplayName()));
            return false;
        }
        boolean placed = placeCardInManaFaceUp(room, owner, cardId);
        if (placed) {
            room.addLog("%sが山札の上から1枚を表向きでマナに置きました(マナ%d枚)"
                    .formatted(owner.getDisplayName(), owner.getManaZone().size()));
        } else {
            // マナ上限で置けなかった場合、引いてしまったカードを山札の上へ戻す
            owner.getDeck().addFirst(cardId);
        }
        return placed;
    }

    /**
     * 手札の指定カードを表向きでマナゾーンに置く。
     * 苗木植えの精霊(【召喚時】)・大地の巨頭(リーダー起動能力)が使う。
     * 既存の {@link #putHandCardIntoManaFaceDown} の表向き版にあたる。
     *
     * @return 置けたらtrue(手札の指定が不正、またはマナ上限ならfalse)
     */
    public boolean placeHandCardIntoManaFaceUp(GameRoom room, PlayerState owner, int handIndex) {
        if (handIndex < 0 || handIndex >= owner.getHand().size()) {
            return false;
        }
        if (owner.getManaZone().size() >= PlayerState.MAX_MANA) {
            room.addLog("マナが15枚のため、これ以上マナに置けません");
            return false;
        }
        String cardId = owner.getHand().remove(handIndex);
        boolean placed = placeCardInManaFaceUp(room, owner, cardId);
        if (placed) {
            room.addLog("%sが手札1枚を表向きでマナに置きました(マナ%d枚)"
                    .formatted(owner.getDisplayName(), owner.getManaZone().size()));
        }
        return placed;
    }

    /**
     * 大地の守護盾(QTE-M-EARTH-13)による、リーダーへの攻撃の肩代わり(置換効果)。
     *
     * defender が大地の守護盾を装備している場合、リーダーへの攻撃ダメージの代わりに
     * このウェポンを破壊し、ダメージそのものを無効化する。ダメージが発生しないため、
     * 被ダメージトリガー(ON_LEADER_DAMAGED)は誘発しない(発注者確認済み)。
     *
     * ミニオンの攻撃(GameService.attack)・ウェポンでのリーダー攻撃(GameService.leaderAttack)の
     * どちらの経路でも、リーダーへLPダメージを与える直前にこのメソッドで肩代わりを試みる。
     * 攻撃宣言そのものは成立しているため、攻撃側の攻撃時効果は肩代わりの有無に関わらず発動する。
     *
     * @return 肩代わりが発生した(=リーダーへのダメージを無効化した)ならtrue
     */
    public boolean tryInterceptLeaderAttackWithShield(GameRoom room, PlayerState defender) {
        CardMaster weapon = defender.getEquippedWeapon();
        if (weapon == null || !EARTH_AEGIS.equals(weapon.id())) {
            return false;
        }
        room.addLog("【大地の守護盾】がリーダーへの攻撃を肩代わりしました");
        destroyOwnWeapon(room, defender);
        return true;
    }

    /**
     * 自分のマナをcount枚アンタップする(a6。静空の風使い)。
     * タップ(支払い)と表裏の反転はあったが、アンタップする操作は存在しなかった
     * (マナ加速のカードが既存カードプールに無かったため)。
     *
     * @return 実際にアンタップした枚数
     */
    public int untapMana(GameRoom room, PlayerState owner, int count) {
        int done = 0;
        for (ManaCard mana : owner.getManaZone()) {
            if (done >= count) {
                break;
            }
            if (mana.isTapped()) {
                mana.untap();
                done++;
            }
        }
        if (done > 0) {
            room.addLog("%sのマナが%d枚アンタップしました".formatted(owner.getDisplayName(), done));
        }
        return done;
    }

    /**
     * カードを山札に戻してシャッフルする(a7。サイクロン・リフレッシュ)。
     * 既存の returnToBottomOfDeck はシャッフルしないため別の操作である。
     */
    public void returnToDeckAndShuffle(GameRoom room, PlayerState owner, java.util.List<String> cardIds) {
        if (cardIds.isEmpty()) {
            return;
        }
        java.util.List<String> pool = new java.util.ArrayList<>(owner.getDeck());
        pool.addAll(cardIds);
        java.util.Collections.shuffle(pool);
        owner.getDeck().clear();
        owner.getDeck().addAll(pool);
        room.addLog("%sがカード%d枚を山札に戻してシャッフルしました"
                .formatted(owner.getDisplayName(), cardIds.size()));
    }

    /**
     * 効果の解決を中断し、プレイヤーに選択を問い合わせる(a9)。
     *
     * これを呼んだ効果は、その場では続きを実行せずに戻る。
     * 続きは GameService.resolveChoice → CardEffectRegistry.resolveChoice から
     * PendingChoice.resumeAt に応じて再開される。
     */
    public void requestChoice(GameRoom room, PlayerState owner, PendingChoice choice) {
        if (owner.getPendingChoice() != null) {
            // 1プレイヤーにつき同時に1つまで。二重に発生する経路があれば設計の誤りである
            throw new IllegalStateException("すでに選択待ちの効果があります");
        }
        owner.setPendingChoice(choice);
        room.addLog("%s: %s".formatted(owner.getDisplayName(), choice.prompt()));
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
