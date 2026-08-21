package com.example.qte.game;

import org.springframework.stereotype.Component;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.EffectContext;
import com.example.qte.effect.PendingChoice;
import com.example.qte.effect.PersistentAura;
import com.example.qte.effect.RuleGuards;
import com.example.qte.effect.StatCalculator;
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

    /**
     * 場に出るミニオンへ写す常在の値を問い合わせるために参照する(★Batch 58。《剛火の将》)。
     * 評価器そのものであって循環はしない —— {@link StatCalculator} は
     * カードマスタしか見ない。
     */
    private final StatCalculator stats;

    /**
     * 場に出るミニオンの実体を作る唯一の入口(★Batch 58)。
     *
     * <b>なぜ入口を1つにしたか。</b>《剛火の将》の常在(場にある【速攻】を持つカードのHP+2)は
     * 場に出る瞬間に加算量を写す形で実装されている({@code MinionInstance.rushHpBonus})。
     * {@code new MinionInstance(...)} は召喚({@code GameService.summonToField})と
     * 効果による登場({@link #putIntoField})の2箇所にあり、両方で写すのを忘れないためには
     * <b>作る場所を1つにするしかない</b>(裁定163)。
     */
    public MinionInstance newFieldMinion(GameState state, CardMaster master, boolean fromTaboo) {
        MinionInstance minion = new MinionInstance(master, state.getTurnNumber(), fromTaboo);
        minion.setRushHpBonus(stats.rushHpBonus(state));
        return minion;
    }

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
                room.addLog("【断罪の大天使】により、%sのドローは墓地に置かれました"
                        .formatted(player.getDisplayName()));
                putIntoTrashFromElsewhere(room, player, cardId); // 山札から = 場以外から(★Batch 50)
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
        // ★Batch 52: 進化の素材は進化ミニオンと一緒に移動する(裁定154)。
        // 本体が消滅しても、束のカードは<b>それぞれ自分の出自に従う</b>(マスター裁定 C3)
        dispatchUnderCards(room, owner, minion, UnderDestination.HAND);
        if (minion.isFromTaboo()) {
            owner.getLostZone().add(minion.getMaster().id());
            room.addLog("【%s】は禁忌カードのため消滅しました".formatted(minion.getMaster().name()));
            return;
        }
        owner.getHand().add(minion.getMaster().id());
        room.addLog("【%s】が手札に戻りました".formatted(minion.getMaster().name()));
    }

    /**
     * 進化ミニオンを山札へ戻すときに、下にあったカードのうち一緒に戻すものを返す
     * (★Batch 52。裁定154。《サイクロン・リフレッシュ》が唯一の使い手)。
     *
     * 山札へ戻す経路だけは、戻すカードをいったん集めてから
     * {@link #returnToDeckAndShuffle} に渡す形になっているため、
     * {@code dispatchUnderCards} と違って<b>行き先へ入れるところまではやらない</b>。
     * 禁忌由来のカードの消滅だけはここで済ませる(戻す一覧に混ぜてはいけない)。
     */
    public java.util.List<String> underCardsForDeck(GameRoom room, PlayerState owner,
            MinionInstance minion) {
        java.util.List<String> toDeck = new java.util.ArrayList<>();
        for (StackedCard stacked : minion.getUnder()) {
            CardMaster card = cards.findById(stacked.cardId());
            if (stacked.fromTaboo()) {
                owner.getLostZone().add(card.id());
                room.addLog("【%s】の下にあった【%s】は禁忌カードのため消滅しました"
                        .formatted(minion.getMaster().name(), card.name()));
            } else {
                toDeck.add(card.id());
            }
        }
        if (!minion.getUnder().isEmpty()) {
            room.addLog("【%s】の下にあった%d枚も一緒に移動しました"
                    .formatted(minion.getMaster().name(), minion.getUnder().size()));
        }
        return toDeck;
    }

    /** 進化の下にあったカードの行き先(★Batch 52。裁定154) */
    private enum UnderDestination {
        /** 墓地(【還元】と禁忌の判断は {@link GameActions#sendToTrashOrRestore} に任せる) */
        TRASH,
        /** 持ち主の手札 */
        HAND,
        /** 裏向きでマナゾーンへ */
        MANA_FACE_DOWN
    }

    /**
     * 進化ミニオンが場を離れるとき、下にあったカードを一緒に運ぶ(★Batch 52。裁定154)。
     *
     * <h2>これは破壊ではない(マスター裁定 C1)</h2>
     *
     * 束のカードの【破壊時】は発動しない。素材は破壊されたのではなく<b>同伴しただけ</b>である。
     * ★束が {@link MinionInstance} ではなく {@link StackedCard} なので、
     * 発火させようとしても引く相手が居ない —— <b>条件分岐ではなく構造で守られている。</b>
     * 破壊数のカウンタ({@code minionsDestroyedThisTurn} の2種)にも数えない。
     *
     * <h2>行き先は1枚ずつ自分の出自に従う(マスター裁定 C3)</h2>
     *
     * 禁忌由来のカードだけが消滅ゾーンへ行く。進化ミニオン本体が禁忌由来でも、
     * 通常デッキ由来の素材は墓地(手札・マナ)へ行く。
     *
     * ★<b>呼ぶのは場から取り除いた直後である。</b>本体の行き先を決めるより先に呼ぶと、
     * 【還元】でマナが増えたぶんだけマナ上限の判定がずれる。
     */
    private void dispatchUnderCards(GameRoom room, PlayerState owner, MinionInstance minion,
            UnderDestination destination) {
        if (minion.getUnder().isEmpty()) {
            return;
        }
        for (StackedCard stacked : minion.getUnder()) {
            CardMaster card = cards.findById(stacked.cardId());
            if (stacked.fromTaboo()) {
                owner.getLostZone().add(card.id());
                room.addLog("【%s】の下にあった【%s】は禁忌カードのため消滅しました"
                        .formatted(minion.getMaster().name(), card.name()));
                continue;
            }
            switch (destination) {
                case TRASH -> sendToTrashOrRestore(room, owner, card, false);
                case HAND -> owner.getHand().add(card.id());
                case MANA_FACE_DOWN -> {
                    ManaCard mana = new ManaCard(card.id(), false);
                    mana.turnFaceDown();
                    owner.getManaZone().add(mana);
                }
            }
        }
        room.addLog("【%s】の下にあった%d枚も一緒に移動しました"
                .formatted(minion.getMaster().name(), minion.getUnder().size()));
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
        // 【光霊・モアニール】(★Batch 50): リーダーがダメージを受けるとき、代わりにこのカードを破壊する。
        // 軽減(正義の御盾)より後に置いているのは、軽減で0になった場合は
        // 「ダメージを受けていない」からである —— 受けていないものを肩代わりはできない
        if (tryReplaceLeaderDamageWithGuardian(room, player)) {
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
        // ★Batch 52: 進化の素材は一緒に墓地へ行く(裁定154)。★素材は破壊されていないので
        // 【破壊時】は発動せず、下の破壊数のカウンタ2種にも数えない(マスター裁定 C1)
        dispatchUnderCards(room, owner, minion, UnderDestination.TRASH);
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
        disposeUsedCard(room, player, spell, fromTaboo, spell.hasKeyword(Keyword.RESTORATION));
    }

    /**
     * 使用し終わったカードの後処理(★Batch 54。賢魂として使った場合の入口)。
     *
     * <b>なぜ【還元】を引数で受けるのか。</b>【賢魂：n】を持つカードは
     * <b>2つの姿</b>を持ち、キーワードは姿ごとに違う(裁定152・マスター裁定 B1)。
     * 《白ノ霊知者》の【還元】は<b>賢魂としての姿</b>にだけ付いており、
     * ミニオンとして破壊されたときは墓地へ行く。
     * {@code spell.hasKeyword(...)} を直に見ると、姿の区別が消える。
     *
     * ★<b>還元の判定を呼び出し側で計算しないこと。</b>
     * 出どころは {@link com.example.qte.master.CardTextKeywords} の2つのメソッド
     * ({@code extract} / {@code soulKeywords})だけである。
     *
     * @param restoration このカードが今の姿で【還元】を持つか
     */
    public void disposeUsedCard(GameRoom room, PlayerState player, CardMaster card,
            boolean fromTaboo, boolean restoration) {
        sendToTrashOrRestore(room, player, card, fromTaboo, restoration);
    }

    /**
     * 場・使用済みカードの行き先の判断を一元化する。
     * 優先順位: 禁忌由来(消滅) > 【還元】(裏向きでマナへ) > 墓地。
     * 禁忌由来のカードは墓地に行かないため、還元は構造的に機能しない(ルール3-6からの導出)。
     */
    private boolean sendToTrashOrRestore(GameRoom room, PlayerState owner, CardMaster card, boolean fromTaboo) {
        return sendToTrashOrRestore(room, owner, card, fromTaboo, card.hasKeyword(Keyword.RESTORATION));
    }

    /** 上の版に「今の姿が【還元】を持つか」を明示して渡す形(★Batch 54。賢魂の2つの姿のため) */
    private boolean sendToTrashOrRestore(GameRoom room, PlayerState owner, CardMaster card,
            boolean fromTaboo, boolean restoration) {
        if (fromTaboo) {
            owner.getLostZone().add(card.id());
            room.addLog("【%s】は禁忌カードのため消滅しました".formatted(card.name()));
            return false;
        }
        if (restoration && owner.getManaZone().size() < PlayerState.MAX_MANA) {
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
     * このプレイヤーが今、ミニオンを場に出せない状態か(★Batch 53)。
     *
     * <h2>なぜ {@code isMinionZoneFull()} を直に見る形をやめたか</h2>
     *
     * 《英霊・コレキ》(相手は自身のターン中1度しかミニオンを場に出せない)により、
     * 「出せない理由」が場の枚数以外にもできた。呼び出し側が満杯だけを見ていると、
     * <b>コレキで弾かれたカードが宙に浮いて消える</b> ——
     * ギガマウス・バイトは「満杯なら手札に戻す」を自分で判断しており、
     * 満杯でなければ手札から抜いたまま {@code putIntoFieldByEffect} の null を捨ててしまう。
     * 黄泉還る水龍・ゾンストライカーの {@code while} ループに至っては
     * <b>無限ループ</b>になる(墓地から消えないのに出られない)。
     *
     * <p>★<b>「出られるか」を問う場所を1つにする</b>のが答えである(裁定163)。
     * 呼び出し側は理由を区別しない —— 満杯でもコレキでも、やることは同じである。
     */
    public boolean isFieldEntryBlocked(GameRoom room, PlayerState owner) {
        if (owner.isMinionZoneFull()) {
            return true;
        }
        GameState state = room.getGameState();
        return state != null && guards.minionEntryDenial(state, owner) != null;
    }

    /**
     * 進化の素材を場から取り除いて束にし、付与されていた効果だけを引き継ぐ(★Batch 53)。
     *
     * <p>Batch 52 はこの処理を {@code GameService.summonToField} の中に直接書いていた。
     * 53 が<b>効果による「出す」でも進化を場に出せる</b>ようにしたことで、
     * まったく同じ処理が {@link #putIntoFieldByEffect} にも要ることになった。
     * ★<b>52 の「着地は1箇所」をもう1段深めて、束を作る処理そのものを1箇所にした。</b>
     * 2箇所に書くと、必ずどちらかが引き継ぎ(裁定224)を忘れる。
     *
     * <p>★<b>呼ぶのは「場に出られる」ことが確定した後である</b>(裁定232)。
     * モアニールの置換で場に出られなかった場合、素材は場に残らなければならない。
     */
    public void attachEvolutionMaterials(GameRoom room, PlayerState owner, MinionInstance minion,
            java.util.List<MinionInstance> materials) {
        if (materials == null || materials.isEmpty()) {
            return;
        }
        for (MinionInstance material : materials) {
            owner.getMinionZone().remove(material);
            // 素材が進化ミニオンなら、その下の束もそのまま引き継ぐ(裁定222)。
            // 束は下から順なので、先に古い束を積んでから素材自身を載せる
            for (StackedCard stacked : material.getUnder()) {
                minion.putUnder(stacked);
            }
            minion.putUnder(new StackedCard(material.getMaster().id(), material.isFromTaboo()));
            minion.inheritGrantsFrom(material);
        }
        com.example.qte.effect.EvolutionSpec spec = effects.evolutionOf(minion.getMaster().id());
        minion.setStatPerUnderCard(spec == null ? 0 : spec.statPerUnderCard());
        room.addLog("%sが【%s】へ進化しました(素材%d体・下に%d枚)"
                .formatted(owner.getDisplayName(), minion.getMaster().name(),
                        materials.size(), minion.getUnder().size()));
    }

    /**
     * ミニオンが場に出た直後の共通処理(★Batch 53)。
     *
     * 数え上げ1つと発火3つからなる。<b>召喚(GameService.summonToField)と
     * 効果による「出す」({@link #putIntoFieldByEffect})の両方が必ずここを通る。</b>
     * 違うのは【召喚時】(ON_SUMMON)だけで、それは召喚側が先に焚く。
     *
     * <p>★52 までは同じ4行が2箇所に並んでいた。53 が
     * <b>「このターンに何体場に出たか」</b>(《英霊・コレキ》)を数える必要から1本にまとめた ——
     * 数える場所が2つあると、片方だけを直した日に制限がすり抜ける。
     */
    public void fireEntryTriggers(GameRoom room, PlayerState owner, MinionInstance minion,
            EffectContext ctx) {
        // 登場の数え上げ(★Batch 53。《英霊・コレキ》)。経路を問わず1体ずつ数える
        owner.countMinionEntry(room.getGameState().getTurnNumber());
        effects.fire(TriggerType.ON_ENTER, minion, ctx);
        // 装備中のウェポンが「自分のミニオンが場に出た」に反応する(禁忌の冥魔剣)。
        // 蘇生・効果による「出す」でも発動するため、ON_ENTER の隣に置く(発注者確認済み)
        effects.fireAllyMinionEvent(TriggerType.ON_ALLY_MINION_ENTER, ctx);
        // 両者のリーダーが「場にミニオンが出た」に反応する(★Batch 49。ロロイヨ伯爵)。
        // 召喚か効果による「出す」かを問わない(マスター裁定193)
        effects.fireAnyMinionEntered(ctx);
    }

    /**
     * 効果によってミニオンを場に「出す」(召喚ではない)。
     * 発注者確認済み裁定により【召喚時】(ON_SUMMON)は発動せず、
     * 登場時(ON_ENTER: 知識など)のみ発動する。ミニオンゾーンが上限なら出せない。
     *
     * @return 場に出たミニオン。出せなかった(場が満杯・出すことが禁じられている)なら null。
     *         ★Batch 49 で void から変更した。ギガマウス・バイトが、出した3体に
     *         その場で【突進】を与えるために実体を必要とするためである。
     *         「場の末尾を取る」形にしなかったのは、ON_ENTER の中でさらに別のミニオンが
     *         場に出ることがあり(黄泉還る水龍)、末尾が別人になりうるからである
     */
    public MinionInstance putIntoFieldByEffect(GameRoom room, PlayerState owner, String cardId) {
        return putIntoFieldByEffect(room, owner, cardId, java.util.List.of());
    }

    /**
     * 効果によって<b>進化ミニオン</b>を素材つきで場に「出す」(★Batch 53。《英術・スケアロック》)。
     *
     * <h2>これは召喚ではない(マスター裁定)</h2>
     *
     * 進化であっても、効果で出したなら<b>【召喚時】は発動せず登場時(ON_ENTER)だけが発動する</b>。
     * 裁定220(進化召喚は召喚である)は「進化召喚する場合」の話であり、ここと衝突しない。
     *
     * <h2>それでも素材は要る(裁定226)</h2>
     *
     * 素材の検証(条件・数)は<b>呼び出し側</b>が済ませて実体を渡す。
     * ここは「場に出られるか」を確かめてから束にするだけである。
     *
     * <p>★<b>素材を1体でも取るなら場の上限を見ない。</b>
     * 進化は素材を最低1体消費するので、場のミニオンが増えることは構造的に起こらない
     * ({@code GameService.summonToField} と同じ判断)。
     * ★ただし《英霊・コレキ》の制限は素材の有無に関係なく掛かる ——
     * あれが数えているのは<b>場に出た体数</b>であって場の空きではない。
     */
    public MinionInstance putIntoFieldByEffect(GameRoom room, PlayerState owner, String cardId,
            java.util.List<MinionInstance> materials) {
        return putIntoFieldByEffect(room, owner, cardId, materials, false);
    }

    /**
     * 効果によって、<b>禁忌デッキ由来のカード</b>を場に出す(★Batch 54)。
     *
     * <h2>なぜこの版が要るのか</h2>
     *
     * 【賢魂】の使用は禁忌デッキからも行える(マスター裁定 A6)。
     * 《スタンディングテント》の【賢魂：2】は<b>使用したカード自身を場に出す</b>ので、
     * 禁忌由来のまま場に立つミニオンが初めて「効果による出す」から生まれる。
     * ★<b>印を持たせないと、場を離れたときに墓地へ行ってしまう</b> ——
     * 禁忌カードは消滅しなければならない(総合ルール3-6)。
     *
     * ★他のすべての「効果で出す」は手札・墓地・マナ・山札から来るため、
     * 禁忌由来でありえない({@code fromTaboo=false} の版でよい)。
     */
    public MinionInstance putIntoFieldByEffect(GameRoom room, PlayerState owner, String cardId,
            java.util.List<MinionInstance> materials, boolean fromTaboo) {
        boolean evolution = materials != null && !materials.isEmpty();
        GameState state = room.getGameState();
        if (NO_CHEAT_INTO_FIELD.contains(cardId)) {
            return null;
        }
        if (evolution) {
            if (state != null && guards.minionEntryDenial(state, owner) != null) {
                return null;
            }
        } else if (isFieldEntryBlocked(room, owner)) {
            return null;
        }
        CardMaster master = cards.findById(cardId);
        // 【光霊・モアニール】(★Batch 50): 場に出る代わりに山札の下へ置く置換効果。
        // ★null を返すが、<b>行き先はここで決まっている</b>(呼び出し側が手札へ戻すと二重になる)。
        // 「出せなかったぶんを手札に戻す」カード(神の福音・ギガマウス・バイト)は、
        // 呼ぶ前に isFieldEntryBlocked() を自分で見る形に揃えてある。
        // ★進化の素材はまだ場に居る —— 出られないなら下にも置かれない(裁定232)
        if (guards.isEntryToDeckBottom(state, owner, master)) {
            // ★禁忌由来のカードは山札に戻せない(3-6)。消滅ゾーンへ送る
            // (GameService.summonToField のモアニール分岐と同じ判断である)
            if (fromTaboo) {
                owner.getLostZone().add(cardId);
                room.addLog("【光霊・モアニール】: 【%s】は場に出られず、禁忌カードのため消滅しました"
                        .formatted(master.name()));
            } else {
                owner.getDeck().addLast(cardId);
                room.addLog("【光霊・モアニール】: 【%s】は場に出る代わりに山札の下へ置かれました"
                        .formatted(master.name()));
            }
            return null;
        }
        MinionInstance minion = newFieldMinion(state, master, fromTaboo);
        attachEvolutionMaterials(room, owner, minion, materials);
        owner.getMinionZone().add(minion);
        room.addLog("【%s】が効果で場に出ました(召喚時効果は発動しない)".formatted(master.name()));
        fireEntryTriggers(room, owner, minion, contextOf(room, owner, minion));
        return minion;
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
            putIntoTrashFromElsewhere(room, player, cardId); // 山札から = 場以外から(★Batch 50)
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
    public MinionInstance reviveFromGrave(GameRoom room, PlayerState owner, String cardId) {
        // ★Batch 53: 「場が満杯か」ではなく「場に出られるか」を問う(《英霊・コレキ》)
        if (!owner.getTrash().contains(cardId) || isFieldEntryBlocked(room, owner)
                || NO_CHEAT_INTO_FIELD.contains(cardId)) {
            return null;
        }
        owner.getTrash().remove(cardId);
        MinionInstance revived = putIntoFieldByEffect(room, owner, cardId);
        if (revived != null) {
            // 【演舞の墓守】(★Batch 50): 自分の墓地から場に出たミニオンは、そのターンAttack+1。
            // ★<b>墓地から場に出す経路はここに集約してある</b>(黄泉還る水龍・ゾンストライカー・
            // 不滅のネクロマンサー・死者蘇生・冥界神ハデス・ハク霊/コク霊・サモンズライト・
            // カムバックキーパー)。もう1本の経路である「墓地からの召喚」(黄泉の召喚主)は
            // 召喚なのでこのメソッドを通らず、GameService.summonFromGrave が同じ発火口を呼ぶ
            effects.fireMinionEnteredFromGrave(contextOf(room, owner, revived));
        }
        return revived;
    }

    /**
     * カード1枚を<b>場以外のゾーンから</b>自分の墓地に置く(★Batch 50)。
     *
     * <h2>なぜ入口を1つにしたか</h2>
     *
     * 《カムバックキーパー》は「場以外から自分の墓地に置かれたとき」に反応する。
     * このイベントは<b>場を離れる処理({@link #leaveFieldByDestruction})を通らない</b>ため、
     * 既存の破壊トリガーではまったく拾えない。手札からの discard・山札からのミル・
     * 断罪の大天使によるドローの置換・裏向きマナの破壊が、これまでは
     * {@code getTrash().add(...)} を各所に直接書く形で散らばっていた。
     *
     * <p>反応する側を1箇所にまとめるには、<b>置く側も1箇所にまとめるしかない</b>(裁定163)。
     * したがってこのメソッドが「場以外から墓地へ」の唯一の入口である。
     *
     * <h2>★【還元】は適用しない(既存の挙動を変えない)</h2>
     *
     * 行き先の分岐({@link #sendToTrashOrRestore})は<b>場を離れたカードと使用済みのカード</b>の
     * ためのものであり、手札から捨てられたカードは従来から素直に墓地へ行っていた。
     * ここで還元を効かせると、このバッチと無関係な既存カードの挙動が変わる。
     */
    public void putIntoTrashFromElsewhere(GameRoom room, PlayerState owner, String cardId) {
        owner.getTrash().add(cardId);
        effects.fireCardPutIntoTrashFromElsewhere(contextOf(room, owner, null), cardId);
    }

    /**
     * 【光霊・モアニール】(★Batch 50): リーダーへのダメージを、このミニオンの破壊で肩代わりする。
     *
     * 戦闘・効果を問わずすべてのダメージが対象である(マスター裁定202)。
     * 肩代わりが起きたダメージは<b>0になる</b>ため、被ダメージトリガー
     * (火炎の狂信者・反転の炎鏡)も誘発しない —— 大地の守護盾
     * ({@link #tryInterceptLeaderAttackWithShield})と同じ扱いである。
     *
     * <p>効果ダメージ({@link #damageLeader})・ミニオンの攻撃・ウェポンでの攻撃の
     * 3経路すべてから呼ばれる。どれを選ぶかの判定は {@link RuleGuards#leaderDamageInterceptor} にある。
     *
     * @return 肩代わりが発生した(=リーダーへのダメージを無効化した)ならtrue
     */
    public boolean tryReplaceLeaderDamageWithGuardian(GameRoom room, PlayerState target) {
        MinionInstance interceptor = guards.leaderDamageInterceptor(target);
        if (interceptor == null) {
            return false;
        }
        room.addLog("【%s】がリーダーへのダメージを肩代わりしました"
                .formatted(interceptor.getMaster().name()));
        destroyMinion(room, target, interceptor);
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
            room.addLog("裏向きのマナ【%s】が破壊されました".formatted(cards.findById(mana.getCardId()).name()));
            putIntoTrashFromElsewhere(room, owner, mana.getCardId()); // マナから = 場以外から(★Batch 50)
            destroyed++;
        }
        if (destroyed > 0) {
            manaLeft(room, owner);
        }
        return destroyed;
    }

    // ★Batch 57: returnFaceDownManaToHand(裏向きのマナ1枚を手札に戻す)は削除した。
    // 唯一の使い手だった《冥府の禁皇》の起動能力が Ver1.1 で参照ゾーンを墓地へ変えたため、
    // 呼び出し元が1つも無くなったからである(死んだコードを残さない)。
    // 表向き版の returnFaceUpManaToHand(風のマナ変換)は使い手が居るので残っている。

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
     * カード1枚を裏向き・アンタップでマナゾーンに置く(★Batch 54)。
     *
     * {@link #placeCardInManaFaceUp} の裏向き版である。
     * ★<b>裏向きの経路は {@code fireManaPlaced} を発火しない</b> ——
     * 51 以前からの非対称をそのまま踏襲している(《豊穣の地霊主》は表向きの配置しか見ていない)。
     * 揃えるかどうかは裁定を仰いでおらず、P5 の宿題である。
     *
     * @return 置けたらtrue、マナ上限で置けなければfalse
     */
    public boolean placeCardInManaFaceDown(GameRoom room, PlayerState owner, String cardId) {
        if (owner.getManaZone().size() >= PlayerState.MAX_MANA) {
            room.addLog("マナが15枚のため、これ以上マナに置けません");
            return false;
        }
        ManaCard mana = new ManaCard(cardId, false);
        mana.turnFaceDown();
        owner.getManaZone().add(mana);
        return true;
    }

    /**
     * 自分の山札の上から1枚を裏向きでマナゾーンに置く(★Batch 54。《勝阿外》の【賢魂：2】)。
     *
     * ★<b>マナ上限で置けなかったときは、そのカードを山札の上に戻す</b>
     * (マスター裁定 B7-2「山札の上のまま」)。
     * {@link #placeTopOfDeckInManaFaceUp} が既にこの形であり、規則を2つ作っていない。
     *
     * @return 置けたらtrue
     */
    public boolean placeTopOfDeckInManaFaceDown(GameRoom room, PlayerState owner) {
        String cardId = owner.getDeck().pollFirst();
        if (cardId == null) {
            room.addLog("%sの山札が空のため、マナに置けませんでした".formatted(owner.getDisplayName()));
            return false;
        }
        boolean placed = placeCardInManaFaceDown(room, owner, cardId);
        if (placed) {
            room.addLog("%sが山札の上から1枚を裏向きでマナに置きました(マナ%d枚)"
                    .formatted(owner.getDisplayName(), owner.getManaZone().size()));
        } else {
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

    // ---------------------------------------------------------------
    // ★Batch 51: マナゾーンと場の行き来
    //
    // 51 まで、マナゾーンの出入り口は「手札・山札・墓地 ⇄ マナ」だけだった。
    // 土文明の Ver1.1 は<b>マナと場を直接つなぐ</b>4枚(勝鼓美・素手喧嘩・喧嘩上等・
    // 俺等地上覇夜露死苦)を持ち込むため、その2方向をここに1本ずつ作る。
    //
    // ★どちらも「置く/出す」であって召喚でも破壊でもない。
    //   したがって【召喚時】も【破壊時】も発動しない(既存の bounceToHand・
    //   reviveFromGrave と同じ流儀)。
    // ---------------------------------------------------------------

    /**
     * マナゾーンの指定位置のカードを場に出す(★Batch 51。「マナから場へ」の唯一の入口)。
     *
     * 召喚ではないため【召喚時】は発動せず、{@code putIntoFieldByEffect} と同じく
     * ON_ENTER のみが発動する。踏み倒し禁止(封印されし禁忌魔人)もそちらで弾かれる。
     *
     * <b>取り除く前に出せるかを確かめている。</b> {@code putIntoFieldByEffect} が
     * null を返す理由は2つあり(場が満杯 / 光霊・モアニールの置換)、前者で null が返ると
     * マナからは消えたのに場にも山札にも居ない「宙に浮いたカード」が生まれる。
     * 満杯と踏み倒し禁止を<b>先に</b>見ることで、マナから取り除いた後は
     * 「場に出る」か「モアニールにより山札の下へ行く」かのどちらかに必ず決まる。
     *
     * <b>manaLeft を最後に発火する理由。</b> このイベントは黄泉還る水龍を場に出しうる。
     * 先に発火すると、その1体で場が埋まって本命が出せなくなる。
     * マナから取り除く → 場に出す → 離脱を告げる、の順に固定する
     * ({@link #returnManaToHandAt} と同じ順序である)。
     *
     * @param index マナゾーン内の位置(0起点)
     * @return 場に出たミニオン。出せなかった(位置が不正・ミニオンでない・場が満杯・
     *         踏み倒し禁止・モアニールの置換)場合は null
     */
    public MinionInstance putManaCardIntoField(GameRoom room, PlayerState owner, int index) {
        if (index < 0 || index >= owner.getManaZone().size()) {
            return null;
        }
        ManaCard mana = owner.getManaZone().get(index);
        CardMaster master = cards.findById(mana.getCardId());
        if (master.type() != com.example.qte.master.CardType.MINION) {
            room.addLog("【%s】はミニオンではないため、マナから場に出せません".formatted(master.name()));
            return null;
        }
        // ★Batch 53: マナは先に取り除くので、出られないなら<b>ここで止める</b>。
        // 満杯だけを見ていると《英霊・コレキ》で弾かれたマナカードが消える
        if (isFieldEntryBlocked(room, owner)) {
            room.addLog("場に出せないため、マナから場に出せませんでした");
            return null;
        }
        if (isCheatIntoFieldBlocked(mana.getCardId())) {
            room.addLog("【%s】はコストを支払わずに場に出せません".formatted(master.name()));
            return null;
        }
        owner.getManaZone().remove(index);
        MinionInstance minion = putIntoFieldByEffect(room, owner, mana.getCardId());
        if (minion != null) {
            room.addLog("%sのマナから【%s】が場に出ました"
                    .formatted(owner.getDisplayName(), master.name()));
        }
        manaLeft(room, owner);
        return minion;
    }

    /**
     * 場のミニオン1体を裏向きでマナゾーンに置く(★Batch 51。喧嘩上等・素手喧嘩)。
     *
     * 破壊ではないため【破壊時】は発動しない。禁忌由来のミニオンは場を離れると消滅する
     * (総合ルール3-6)ため、マナには行かず消滅ゾーンへ送る —— {@link #bounceToHand} と
     * まったく同じ扱いである。
     *
     * ★マナ上限(15枚)で置けない場合は<b>場から動かさない。</b>
     * 先に場から取り除いてしまうと、行き先が無いカードが消えてしまう。
     *
     * @return マナに置けた(または禁忌により消滅した)ならtrue
     */
    public boolean putFieldMinionIntoManaFaceDown(GameRoom room, PlayerState owner,
            MinionInstance minion) {
        if (!owner.getMinionZone().contains(minion)) {
            return false; // 連鎖する効果で既に場を離れている
        }
        // ★Batch 52: 進化ミニオンは下にあるカードも一緒にマナへ行く(裁定154・マスター裁定 C2)。
        // ★マナ上限の判定は<b>束を含めた全枚数</b>で行い、全部置けないなら1枚も動かさない ——
        //   本体だけマナに入って束が宙に浮く、という状態を作らないためである。
        //   禁忌由来のカードはマナに行かず消滅するので、枚数の勘定から外す
        int needed = (minion.isFromTaboo() ? 0 : 1)
                + (int) minion.getUnder().stream().filter(s -> !s.fromTaboo()).count();
        if (owner.getManaZone().size() + needed > PlayerState.MAX_MANA) {
            room.addLog("マナが%d枚のため、【%s】を%sマナに置けませんでした"
                    .formatted(owner.getManaZone().size(), minion.getMaster().name(),
                            minion.getUnder().isEmpty() ? "" : "下のカードごと"));
            return false;
        }
        owner.getMinionZone().remove(minion);
        dispatchUnderCards(room, owner, minion, UnderDestination.MANA_FACE_DOWN);
        if (minion.isFromTaboo()) {
            owner.getLostZone().add(minion.getMaster().id());
            room.addLog("【%s】は禁忌カードのため消滅しました".formatted(minion.getMaster().name()));
            return true;
        }
        ManaCard mana = new ManaCard(minion.getMaster().id(), false);
        mana.turnFaceDown();
        owner.getManaZone().add(mana);
        room.addLog("【%s】が場から裏向きでマナに置かれました(マナ%d枚)"
                .formatted(minion.getMaster().name(), owner.getManaZone().size()));
        return true;
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
