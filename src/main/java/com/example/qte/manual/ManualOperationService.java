package com.example.qte.manual;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードの操作(設計書 5-3 の13項目 + 進化スタック + Undo/Redo)。
 *
 * <h2>★このクラスは「判断」を一切持たない(設計書 5-1)</h2>
 * コストの支払い・召喚時効果・戦闘の解決・攻撃可否・フェイズ強制・勝敗判定・デッキ切れは
 * すべて切ってある。LPは0未満になれるし、ミニオンゾーンには何体でも置けるし、
 * 山札が尽きても何も起きない。
 * <b>アプリが担うのは「同じ盤面を見ていることの保証」だけである。</b>
 *
 * ここで弾いているのは<b>要求そのものが成り立たない場合</b>だけである
 * (盤面に無いカードを指す / 同じカードを2回指す / 直接指定と増減を同時に載せる /
 * リーダーをゾーンへ動かそうとする)。これらはゲームの裁定ではなく、画面側の取りこぼしである。
 *
 * <h2>★1回の操作の型は {@link #apply(ManualRoom, Function)} が握る</h2>
 * 個々の操作メソッドは「状態を変更し、ログ本文を返す」だけの関数であり、
 * 履歴への push もログの追記も自分では行わない。
 * 17b が {@code ManualHistory.push()} の中で複製することにした理由(17b 2-4)と同じで、
 * 13項目のうち1箇所でも書き忘れた瞬間に <b>Undo を実行するまで症状が出ない不具合</b>になる。
 * 器の側で1箇所に閉じるほうが圧倒的に安い。
 *
 * <h2>★ロックは自分で取らない(17b 2-14)</h2>
 * 呼び出し側が {@code synchronized (room.getLock())} の中で呼ぶ前提である。
 */
@Service
@RequiredArgsConstructor
public class ManualOperationService {

    /** 1回のドローで引ける上限。デッキ切れ敗北の判定ではなく、事故要求の上限である */
    private static final int MAX_DRAW = 60;

    /** 自由メモの最大文字数(設計書 5-5) */
    private static final int MAX_NOTE_LENGTH = 500;

    /** ログにカード名を並べる上限。これを超えたら「ほかn枚」にまとめる */
    private static final int LOG_NAME_LIMIT = 5;

    /**
     * 表向きへ正規化するゾーン(Batch 20a 設計書 2-3・C1・C2)。
     * TABOO / DECK は正規化しない(C3)。MANA も対象外であり、
     * 表裏はドロップ先の表/裏ストリップ({@code faceDown} 明示)で決まる(C4)。
     */
    private static final Set<ManualZone> FACE_UP_ON_ARRIVAL = EnumSet.of(
            ManualZone.FIELD, ManualZone.WEAPON, ManualZone.TRASH,
            ManualZone.LOST, ManualZone.REVEAL, ManualZone.HAND,
            // ★20b 2-4: 新ゾーンも表向きへ揃える。PRIVATE は「自分が中身を見ている」
            //   状態のゾーンであり、裏向きのまま置けても確認の用をなさない。
            //   フェイズ2で相手に隠すのはゾーンフィルタの責務であり、faceDown とは別物である。
            ManualZone.PLAY, ManualZone.PRIVATE);

    private final ManualGameService gameService;

    private final ManualCardRepository cards;

    // ================= 1回の操作の型 =================

    /**
     * 盤面を変更する操作を1回適用する。★履歴への push はこの1箇所だけである。
     *
     * <h3>失敗したら操作前へ戻す</h3>
     * 変更を加えた後で例外が出ると、盤面が途中まで動いた状態で残る。
     * 17b の {@code ManualWsController.execute} は失敗時に
     * 「状態は変更されていないので操作者にだけ理由を返す」と書いてあり、
     * その約束をここで実際に守る。操作前のスナップショットを先に取っておき、
     * 例外が出たら丸ごと差し戻す。
     *
     * <h3>成功したときは同じオブジェクトを使い続ける</h3>
     * 差し替えるのは失敗したときだけである。
     * 毎回 {@code room.setGameState(...)} で入れ替える形にすると、
     * 操作をまたいで {@code ManualSeat} の参照を持っている呼び出し側の変更が
     * 黙って消える(18b / 19a が踏みやすい)。成功時は入れ替えない。
     *
     * <h3>ログは追記専用である(設計書 5-5)</h3>
     * ログは {@link ManualRoom} が持ち、{@link ManualGameState} の外にある。
     * 例外で差し戻した場合はログにも残さない。起きなかった操作の記録は嘘である。
     *
     * @param mutation 状態を変更し、ログ本文を返す関数。null を返せばログに残さない
     */
    public void apply(ManualRoom room, Function<ManualGameState, String> mutation) {
        ManualGameState state = room.getGameState();
        ManualGameState snapshot = state.copy();
        String logText;
        try {
            logText = mutation.apply(state);
        } catch (RuntimeException e) {
            room.setGameState(snapshot);
            throw e;
        }
        room.getHistory().push(snapshot);
        if (logText != null && !logText.isBlank()) {
            room.addLog(logText);
        }
    }

    /**
     * 盤面に触らない操作(自由メモ・勝敗の宣言)と、履歴そのものを動かす操作(Undo/Redo)。
     *
     * ★履歴に積まない。ログだけの操作を積むと、Undo が「見た目に何も起きない1手」を
     * 消費することになり、人間が数えている取り消し回数と食い違う。
     * Undo 自身を積まないのは、積んだ瞬間に Undo が自分を取り消せてしまうためである。
     */
    public void applyDirect(ManualRoom room, Function<ManualRoom, String> action) {
        String logText = action.apply(room);
        if (logText != null && !logText.isBlank()) {
            room.addLog(logText);
        }
    }

    // ================= 1. ゾーン間移動 =================

    /**
     * ゾーン間移動(設計書 5-3 の1)。挿入位置・表裏・複数枚をこれ1つで扱う。
     *
     * <h3>★FIELD / WEAPON を離れると数値は印刷値に戻る(設計書16 v2.4)</h3>
     * 受けているダメージや強化、装備品の ATK 変更は「その個体が場に居続けている間」
     * だけの状態であり、離れた瞬間に個体としての履歴は切れる。したがって
     * <b>移動元が FIELD または WEAPON で、移動先がそのゾーンと異なる</b>ときだけ、
     * 印刷値に戻す(FIELD → FIELD、WEAPON → WEAPON のように<b>同じ種類のゾーンへ
     * 移す場合は戻さない</b>。席をまたいで FIELD → FIELD へ移す「相手席のミニオンゾーンへ
     * 移す」操作が該当する)。
     * FIELD / WEAPON 以外からの移動(手札 → 場、墓地 → 場等)は元々何も持っていないか、
     * 既に印刷値であるかのどちらかなので、ここでは何もしない。
     *
     * <h3>旧 4-5-2 の3「帯から1枚抜いても数値は変えない」は撤回している(v2.3)</h3>
     * これも「FIELD を離れる」の一形態である。ただし {@link #evolve} が
     * 素材にする時点で既に印刷値へ戻しているため(下記)、帯から抜く操作自体は
     * 既に印刷値になっている数値をそのまま運ぶだけで、ここで重ねて何かする必要は無い。
     *
     * ★最上段を指定すれば束ごと動く(4-5-2 の1)。素材を指定すればその1枚だけが抜ける(同 2)。
     * どちらも {@link ManualBoardIndex#detach} が引き受けるため、ここに分岐は無い。
     */
    public String move(ManualGameState state, ManualOpRequest.Move request) {
        if (request.toZone() == null) {
            throw new IllegalArgumentException("移動先のゾーンが指定されていません");
        }
        // ★20b 3-2: 共有ゾーン(PLAY / REVEAL)は席に属さないため toSeat を無視する。
        //   ここで null に潰しておくと、以降の target 解決もログも1本の経路で済む。
        ManualSeatId toSeatId = request.toZone().isShared() ? null : seatOf(request.toSeat());
        List<ManualCardRef> refs = ManualBoardIndex.requireAll(state, request.cardIds());
        for (ManualCardRef ref : refs) {
            if (ref.isLeader()) {
                throw new IllegalArgumentException("リーダーはゾーンへ移動できません");
            }
        }
        refs.sort(ManualBoardIndex.BOARD_ORDER);

        String origin = describeOrigin(refs);
        String names = describeCards(refs);
        List<ManualCardInstance> target = state.cards(toSeatId, request.toZone());

        for (ManualCardRef ref : refs) {
            ManualBoardIndex.detach(state, ref);
        }
        // ★外したあとに付け替えを行う。先に行うと、ウェポン枠の中で並べ替えただけの移動で
        //   自分自身を墓地へ送ってしまう(WEAPON → 同じ WEAPON の付け替えは起こらない)。
        String replaced = replaceEquippedWeapon(state, toSeatId, request.toZone());
        int index = clampIndex(request.toIndex() == null ? target.size() : request.toIndex(),
                target.size());
        for (int i = 0; i < refs.size(); i++) {
            ManualCardRef ref = refs.get(i);
            ManualCardInstance card = ref.card();
            normalizeFaceDown(card, request);
            // ★FIELD / WEAPON を離れる(同じ種類のゾーンへの移動でない)ときだけ印刷値へ戻す
            if (isFieldLike(ref.zone()) && ref.zone() != request.toZone()) {
                resetToPrinted(card);
            }
            target.add(index + i, card);
        }

        String face = "";
        if (request.faceDown() != null) {
            face = request.faceDown() ? "(裏向き)" : "(表向き)";
        } else if (FACE_UP_ON_ARRIVAL.contains(request.toZone())) {
            // ★正規化で表向きになった場合も、何が起きたかがログに残るようにする(2-3)
            face = "(表向き)";
        }
        String destination = toSeatId == null
                ? "共有 %s".formatted(request.toZone().getDisplayName())
                : "席%s %s".formatted(toSeatId, request.toZone().getDisplayName());
        return "%s → %s%s に %d枚 移した %s%s".formatted(origin, destination,
                face, refs.size(), names, replaced);
    }

    /**
     * ウェポンの付け替え(Batch 20b・マスター確認済み)。
     *
     * <h3>★これは手動モードで唯一「アプリが行き先を決める」処理である</h3>
     * 設計書16 5-1 の「判断を実装しない」に対する明示的な例外であり、
     * リーダータイル自体をウェポンのドロップ先にした(20b 2-2)ことの帰結である。
     * 装備済みかどうかでドロップの当たり判定が変わると人間に説明できないため
     * 「いつでも落とせる」を選び、その代わりに古いウェポンの後始末をアプリが引き受ける。
     *
     * <h3>行き先は由来で分ける</h3>
     * 総合ルール 2-3 により、禁忌デッキ由来のカードは場を離れると墓地ではなく消滅へ行く。
     * 自動化した以上、行き先を間違えないのは実装の責任である
     * ({@link ManualCardInstance#isFromTaboo()})。
     *
     * <h3>それでも人間が上書きできる</h3>
     * 送り先はただの移動であり、Undo でも、墓地から消滅への手動移動でも取り消せる。
     * ログに何が起きたかを必ず残すのはそのためである。
     *
     * @return ログへ追記する文字列。付け替えが起きなければ空文字
     */
    private String replaceEquippedWeapon(ManualGameState state, ManualSeatId toSeatId,
            ManualZone toZone) {
        if (toZone != ManualZone.WEAPON || toSeatId == null) {
            return "";
        }
        List<ManualCardInstance> weapons = state.seat(toSeatId).zone(ManualZone.WEAPON);
        if (weapons.isEmpty()) {
            return "";
        }
        List<ManualCardInstance> old = new ArrayList<>(weapons);
        weapons.clear();
        List<String> moved = new ArrayList<>();
        for (ManualCardInstance card : old) {
            ManualZone destination = card.isFromTaboo() ? ManualZone.LOST : ManualZone.TRASH;
            resetToPrinted(card);
            card.setFaceDown(false);
            card.setUsed(false);
            state.seat(toSeatId).zone(destination).add(card);
            moved.add("《%s》→%s".formatted(displayName(card), destination.getDisplayName()));
        }
        return "(付け替え: %s)".formatted(String.join(" ", moved));
    }

    // ================= 9. 進化スタック =================

    /**
     * 進化スタックを積む(設計書 4-5-1)。
     *
     * <h3>★これはミニオンゾーンの枠数を N → 1 に減らす操作である</h3>
     * 通常のカードプレイに無い性質であり、実装上ここだけが特殊である。
     * N体の素材をゾーンから取り除き、その全部を1枚のカードの下に押し込んで、
     * 最も左の素材が居た枠へ置き直す。結果として N−1 枠が空く。
     *
     * <h3>★素材リストは平坦にする(17b 2-2)</h3>
     * 素材が既に進化スタックだった場合、その素材が抱えている材料を先に取り出してから
     * 素材自身を積む。これで階層は生まれず、{@code +n} バッジの n は
     * {@code materialCount()} そのものになる。
     * 設計書 4-5-1 が「3体を素材にすれば +3、その上にさらに進化を重ねれば +4」と
     * 定めているのは、数え方が階層を無視しているということである。
     *
     * <h3>★数値 — 上に乗るカードはそのまま、素材は印刷値に戻す(設計書16 訂正)</h3>
     * 上に乗せる進化ミニオンの数値には触らない。自分の印刷値を既に持っているためである。
     * <b>素材にする側は、独立したミニオンとしての履歴が切れるため、この時点で印刷値へ戻す。</b>
     * これは {@link #move} が「FIELD を離れると印刷値に戻る」と定めたのと同じ規則であり、
     * 素材になることも FIELD の独立した枠を失うという意味で場を離れる一形態である。
     * 既に別の進化ミニオンの素材だったカード(平坦化で持ち上がってきたもの)は、
     * その時点で既に印刷値になっているため、ここでの処理は二重適用になっても無害である。
     *
     * <h3>素材の並び順</h3>
     * 選択した順ではなく、ミニオンゾーンの左からの並び順で積む(設計書 4-5-1)。
     * 順序に意味は無いが、同じ操作が必ず同じ結果になるように固定する。
     */
    public String evolve(ManualGameState state, ManualOpRequest.Evolve request) {
        ManualSeatId seatId = seatOf(request.seat());
        List<ManualCardInstance> field = state.seat(seatId).zone(ManualZone.FIELD);

        ManualCardRef top = ManualBoardIndex.require(state, request.evolutionCardId());
        if (top.isLeader()) {
            throw new IllegalArgumentException("リーダーは進化ミニオンにできません");
        }
        if (top.isMaterial()) {
            throw new IllegalArgumentException("進化スタックの素材はそのままでは重ねられません。先に抜き出してください");
        }

        List<ManualCardRef> materials = new ArrayList<>();
        if (request.materialCardIds() != null && !request.materialCardIds().isEmpty()) {
            materials = ManualBoardIndex.requireAll(state, request.materialCardIds());
        }
        for (ManualCardRef ref : materials) {
            if (ref.card() == top.card()) {
                throw new IllegalArgumentException("進化ミニオン自身を素材にはできません");
            }
            if (!ref.isTopLevel() || ref.zone() != ManualZone.FIELD || ref.seatId() != seatId) {
                throw new IllegalArgumentException("素材にできるのは同じ席のミニオンゾーンにあるカードだけです");
            }
        }
        materials.sort(ManualBoardIndex.BOARD_ORDER);

        int slot = field.size();
        if (!materials.isEmpty()) {
            slot = field.indexOf(materials.get(0).card());
        } else if (request.toIndex() != null) {
            slot = request.toIndex();
        }

        List<ManualCardInstance> stacked = new ArrayList<>();
        for (ManualCardRef ref : materials) {
            ManualCardInstance material = ref.card();
            for (ManualCardInstance nested : material.getMaterials()) {
                resetToPrinted(nested); // 既に印刷値のはずだが、平坦化のたび念のため戻す
                stacked.add(nested);
            }
            material.getMaterials().clear();
            resetToPrinted(material); // ★素材になる瞬間、独立したミニオンとしての履歴が切れる
            stacked.add(material);
        }
        for (ManualCardRef ref : materials) {
            field.remove(ref.card());
        }
        ManualBoardIndex.detach(state, top);

        ManualCardInstance evolution = top.card();
        evolution.getMaterials().addAll(stacked);
        field.add(clampIndex(slot, field.size()), evolution);

        if (materials.isEmpty()) {
            return "席%s: 《%s》 を素材なしでミニオンゾーンへ出した".formatted(seatId, displayName(evolution));
        }
        return "席%s: 《%s》 を素材 %d体 に重ねた(ミニオン枠 %d → 1、スタック +%d)".formatted(
                seatId, displayName(evolution), materials.size(), materials.size(),
                evolution.materialCount());
    }

    // ================= 2. LP =================

    /** LP の変更(設計書 5-3 の2)。★上限20も下限0も強制しない。 */
    public String changeLp(ManualGameState state, ManualOpRequest.Lp request) {
        ManualSeatId seatId = seatOf(request.seat());
        ManualSeat seat = state.seat(seatId);
        if (request.value() != null && request.delta() != null) {
            throw new IllegalArgumentException("LP は直接指定と増減のどちらか一方だけ載せてください");
        }
        int before = seat.getLp();
        int after;
        if (request.value() != null) {
            after = request.value();
        } else if (request.delta() != null) {
            after = before + request.delta();
        } else {
            throw new IllegalArgumentException("LP の変更内容が指定されていません");
        }
        seat.setLp(after);
        return "席%s: LP %d → %d".formatted(seatId, before, after);
    }

    // ================= 3・4. ATK / HP =================

    /** ATK / HP の変更(設計書 5-3 の3・4)。現在値を直接書き換える1軸方式である。 */
    public String changeStats(ManualGameState state, ManualOpRequest.Stat request) {
        ManualCardInstance card = ManualBoardIndex.require(state, request.cardId()).card();
        if (request.attack() != null && request.attackDelta() != null) {
            throw new IllegalArgumentException("Attack は直接指定と増減のどちらか一方だけ載せてください");
        }
        if (request.hp() != null && request.hpDelta() != null) {
            throw new IllegalArgumentException("HP は直接指定と増減のどちらか一方だけ載せてください");
        }
        if (request.attack() == null && request.attackDelta() == null
                && request.hp() == null && request.hpDelta() == null) {
            throw new IllegalArgumentException("変更する数値が指定されていません");
        }
        String before = "%s/%s".formatted(numberText(card.getAttack()), numberText(card.getHp()));

        if (request.attack() != null) {
            card.setAttack(request.attack());
        } else if (request.attackDelta() != null) {
            card.setAttack(applyDelta(card.getAttack(), request.attackDelta(), "Attack"));
        }
        if (request.hp() != null) {
            card.setHp(request.hp());
        } else if (request.hpDelta() != null) {
            card.setHp(applyDelta(card.getHp(), request.hpDelta(), "HP"));
        }
        return "《%s》 %s → %s/%s".formatted(displayName(card), before,
                numberText(card.getAttack()), numberText(card.getHp()));
    }

    /**
     * 数値を印刷値へ戻す。設計書 5-3 の3・4 の一形態である。
     *
     * 移動では数値に触らないと決めた(→ {@link #move})ぶん、
     * 「墓地から場へ戻したので新品にしたい」といった場面の受け皿をここに置く。
     * ★自動では絶対に呼ばない。人間が明示的に押したときだけ動く。
     */
    public String resetStats(ManualGameState state, ManualOpRequest.Target request) {
        ManualCardInstance card = ManualBoardIndex.require(state, request.cardId()).card();
        if (!card.isResolved()) {
            throw new IllegalArgumentException("カード定義に突合できていないカードには印刷値がありません");
        }
        resetToPrinted(card);
        return "《%s》 の数値を印刷値 %s/%s に戻した".formatted(displayName(card),
                numberText(card.getAttack()), numberText(card.getHp()));
    }

    // ================= 5. 札 =================

    /** 札を付ける(設計書 5-3 の5 / 5-4)。★アプリは札の意味を解釈しない。 */
    public String addLabel(ManualGameState state, ManualOpRequest.Label request) {
        ManualCardInstance card = ManualBoardIndex.require(state, request.cardId()).card();
        String label = ManualLabels.normalize(request.label());
        if (card.getLabels().contains(label)) {
            throw new IllegalArgumentException("同じ札が既に付いています: " + label);
        }
        if (card.getLabels().size() >= ManualLabels.MAX_PER_CARD) {
            throw new IllegalArgumentException("1枚に付けられる札は %d 個までです".formatted(ManualLabels.MAX_PER_CARD));
        }
        card.getLabels().add(label);
        return "《%s》 に札「%s」を付けた".formatted(displayName(card), label);
    }

    /** 札を外す。{@code label} が空なら全部外す(設計書 4-4「左クリック 札 → その札を外す」)。 */
    public String removeLabel(ManualGameState state, ManualOpRequest.Label request) {
        ManualCardInstance card = ManualBoardIndex.require(state, request.cardId()).card();
        if (request.label() == null || request.label().isBlank()) {
            int count = card.getLabels().size();
            if (count == 0) {
                throw new IllegalArgumentException("外す札がありません");
            }
            card.getLabels().clear();
            return "《%s》 の札 %d個 をすべて外した".formatted(displayName(card), count);
        }
        String label = ManualLabels.normalize(request.label());
        if (!card.getLabels().remove(label)) {
            throw new IllegalArgumentException("その札は付いていません: " + label);
        }
        return "《%s》 から札「%s」を外した".formatted(displayName(card), label);
    }

    // ================= 6・7・8. タップ / 表裏 / 使用済み =================

    /** タップ・アンタップ(設計書 5-3 の6)。リーダーも対象である(設計書 4-3・レビューN)。 */
    public String tap(ManualGameState state, ManualOpRequest.Flag request) {
        List<ManualCardInstance> targets = flagTargets(state, request);
        int on = 0;
        for (ManualCardInstance card : targets) {
            boolean value = request.value() == null ? !card.isTapped() : request.value();
            card.setTapped(value);
            if (value) {
                on++;
            }
        }
        return flagLog(targets, on, "タップ", "アンタップ");
    }

    /** 表 / 裏の切り替え(設計書 5-3 の7)。表裏の概念があるすべてのカードが対象(4-4・レビューF)。 */
    public String flip(ManualGameState state, ManualOpRequest.Flag request) {
        List<ManualCardInstance> targets = flagTargets(state, request);
        int on = 0;
        for (ManualCardInstance card : targets) {
            boolean value = request.value() == null ? !card.isFaceDown() : request.value();
            card.setFaceDown(value);
            if (value) {
                on++;
            }
        }
        return flagLog(targets, on, "裏向きに", "表向きに");
    }

    /** ウェポンの使用済みフラグ(設計書 5-3 の8)。装備 / 解除は {@link #move} で行う。 */
    public String markUsed(ManualGameState state, ManualOpRequest.Flag request) {
        List<ManualCardInstance> targets = flagTargets(state, request);
        int on = 0;
        for (ManualCardInstance card : targets) {
            boolean value = request.value() == null ? !card.isUsed() : request.value();
            card.setUsed(value);
            if (value) {
                on++;
            }
        }
        return flagLog(targets, on, "使用済みに", "未使用に");
    }

    // ================= 10. ターン / フェイズ =================

    /** ターン番号の手動設定(設計書 5-3 の10)。★ターンが進んでも何も自動化しない。 */
    public String setTurn(ManualGameState state, ManualOpRequest.Turn request) {
        if (request.number() != null && request.delta() != null) {
            throw new IllegalArgumentException("ターン番号は直接指定と増減のどちらか一方だけ載せてください");
        }
        int before = state.getTurnNumber();
        int after;
        if (request.number() != null) {
            after = request.number();
        } else if (request.delta() != null) {
            after = before + request.delta();
        } else {
            throw new IllegalArgumentException("ターン番号の変更内容が指定されていません");
        }
        if (after < 1) {
            after = 1;
        }
        state.setTurnNumber(after);
        return "ターン %d → %d".formatted(before, after);
    }

    /** フェイズの手動設定(設計書 5-3 の10)。★表示だけであり、操作を制限しない。 */
    public String setPhase(ManualGameState state, ManualOpRequest.Phase request) {
        ManualPhase before = state.getPhase();
        ManualPhase after = before;
        if (request.phase() != null) {
            after = request.phase();
        } else if (request.step() != null && request.step() != 0) {
            int steps = Math.abs(request.step());
            if (steps > ManualPhase.values().length) {
                throw new IllegalArgumentException("フェイズの移動量が大きすぎます");
            }
            boolean ahead = request.step() > 0;
            for (int i = 0; i < steps; i++) {
                after = ahead ? after.forward() : after.backward();
            }
        } else {
            throw new IllegalArgumentException("フェイズの変更内容が指定されていません");
        }
        state.setPhase(after);
        return "フェイズ %s → %s".formatted(before.getDisplayName(), after.getDisplayName());
    }

    // ================= 11. ドロー / シャッフル =================

    /**
     * ドロー(設計書 4-4「左クリック 山札 → 1枚ドロー」)。
     * ★山札が尽きても敗北にしない(設計書 5-1・4-4)。引けた枚数だけ引いてログに残す。
     */
    public String draw(ManualGameState state, ManualOpRequest.Draw request) {
        ManualSeatId seatId = seatOf(request.seat());
        int count = request.count() == null ? 1 : request.count();
        if (count < 1 || count > MAX_DRAW) {
            throw new IllegalArgumentException("引く枚数は 1〜%d です".formatted(MAX_DRAW));
        }
        ManualSeat seat = state.seat(seatId);
        int drawn = gameService.drawCards(seat, count);
        if (drawn == 0) {
            return "席%s: 山札が空のため引けなかった".formatted(seatId);
        }
        if (drawn < count) {
            return "席%s: %d枚 引いた(山札が尽きたため %d枚 は引けなかった)".formatted(seatId, drawn, count - drawn);
        }
        return "席%s: %d枚 引いた(山札 残り %d枚)".formatted(seatId, drawn,
                seat.zone(ManualZone.DECK).size());
    }

    /** 山札のシャッフル(設計書 5-3 の11)。★並びはログに残さない(設計書 5-5)。 */
    public String shuffleDeck(ManualGameState state, ManualOpRequest.Seat request) {
        ManualSeatId seatId = seatOf(request.seat());
        ManualSeat seat = state.seat(seatId);
        gameService.shuffleDeck(seat);
        return "席%s: 山札 %d枚 をシャッフルした".formatted(seatId, seat.zone(ManualZone.DECK).size());
    }

    // ================= 12・13. 宣言 / メモ(ログのみ) =================

    /** 勝敗の宣言(設計書 5-3 の12)。★盤面には触らない。 */
    public String declare(ManualOpRequest.Declare request) {
        if (request.declaration() == null) {
            throw new IllegalArgumentException("宣言の内容が指定されていません");
        }
        String seatText = request.seat() == null ? "" : "席%s の ".formatted(request.seat());
        String note = "";
        if (request.note() != null && !request.note().isBlank()) {
            note = " — " + request.note().trim();
        }
        return "%s%sを宣言した%s".formatted(seatText, request.declaration().getDisplayName(), note);
    }

    /**
     * 自由メモ(設計書 5-3 の13 / 5-5)。
     * ★アプリは効果を解決しないため、<b>何が起きたのかを記録できるのは人間だけ</b>である。
     * これは補助機能ではなく、このモードの成果物を生む中核機能である。
     */
    public String note(ManualOpRequest.Note request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("メモが空です");
        }
        String text = request.text().trim();
        if (text.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("メモは %d 文字までです".formatted(MAX_NOTE_LENGTH));
        }
        return "メモ: " + text;
    }

    // ================= Undo / Redo =================

    /**
     * 1手戻す(設計書 5-6)。
     *
     * ★<b>ログは巻き戻さない。</b>ログは {@link ManualRoom} が持ち、状態の外にある(17b 2-5)。
     * 状態だけを戻し、「操作を1つ取り消した」をログに追記する。
     * アプリが効果を解決しない以上、何が起きたのかを記録できるのは人間だけであり、
     * ログはこのモードの成果物である。追記専用でなければならない。
     */
    public String undo(ManualRoom room) {
        ManualGameState restored = room.getHistory().undo(room.getGameState())
                .orElseThrow(() -> new IllegalStateException("取り消せる操作がありません"));
        room.setGameState(restored);
        return "操作を1つ取り消した(Undo。残り %d手)".formatted(room.getHistory().undoDepth());
    }

    /** 取り消した操作をやり直す(設計書 5-6)。新しい操作を積んだ時点で無効になる(17b 2-4)。 */
    public String redo(ManualRoom room) {
        ManualGameState restored = room.getHistory().redo(room.getGameState())
                .orElseThrow(() -> new IllegalStateException("やり直せる操作がありません"));
        room.setGameState(restored);
        return "取り消した操作を1つやり直した(Redo。残り %d手)".formatted(room.getHistory().redoDepth());
    }

    // ================= 補助 =================

    /** 席の既定はA席である。一人回しではA席しか使わない(設計書 6-1)。 */
    private ManualSeatId seatOf(ManualSeatId seatId) {
        return seatId == null ? ManualSeatId.A : seatId;
    }

    private List<ManualCardInstance> flagTargets(ManualGameState state,
            ManualOpRequest.Flag request) {
        List<ManualCardRef> refs = ManualBoardIndex.requireAll(state, request.cardIds());
        refs.sort(ManualBoardIndex.BOARD_ORDER);
        List<ManualCardInstance> targets = new ArrayList<>();
        for (ManualCardRef ref : refs) {
            targets.add(ref.card());
        }
        return targets;
    }

    private String flagLog(List<ManualCardInstance> targets, int on, String onWord, String offWord) {
        if (targets.size() == 1) {
            return "《%s》 を%sした".formatted(displayName(targets.get(0)), on == 1 ? onWord : offWord);
        }
        return "%d枚 を%s %d枚 / %s %d枚 にした".formatted(targets.size(), onWord, on, offWord,
                targets.size() - on);
    }

    /**
     * 表裏の正規化(Batch 20a 設計書 2-3)。移動先ゾーンによって表向きへ揃える。
     *
     * <h3>★クライアントの明示指定を優先する(D1)</h3>
     * {@code request.faceDown()} が明示されている場合はそちらを使い、正規化は行わない
     * (マナのストリップへのドロップ・2-2 のマナ用ボタンが意図した向きを上書きされないため)。
     * 明示が無いときだけ {@link #FACE_UP_ON_ARRIVAL} に基づいて表向きへ揃える。
     * TABOO / DECK / MANA はここで何もしない(現状維持。C3・C4)。
     *
     * <h3>★進化スタックの素材にも同じ規則を適用する(C5)</h3>
     * 素材は {@link ManualCardInstance#getMaterials()} が平坦なリストとして持つため、
     * 再帰は不要である(設計書「materials は平坦である」)。
     */
    private void normalizeFaceDown(ManualCardInstance card, ManualOpRequest.Move request) {
        applyFaceDownRule(card, request);
        for (ManualCardInstance material : card.getMaterials()) {
            applyFaceDownRule(material, request);
        }
    }

    private void applyFaceDownRule(ManualCardInstance card, ManualOpRequest.Move request) {
        if (request.faceDown() != null) {
            card.setFaceDown(request.faceDown());
        } else if (FACE_UP_ON_ARRIVAL.contains(request.toZone())) {
            card.setFaceDown(false);
        }
    }

    /**
     * 「場に居続けている」とみなすゾーンか(設計書16 v2.4)。
     * FIELD(ミニオン)と WEAPON(装備品)の両方が対象である。
     * どちらも離れた瞬間に印刷値へ戻る({@link #resetToPrinted}) 対象になる。
     */
    private boolean isFieldLike(ManualZone zone) {
        return zone == ManualZone.FIELD || zone == ManualZone.WEAPON;
    }

    /**
     * FIELD / WEAPON を離れる(または素材になる)カードの数値を印刷値へ戻す(設計書16 v2.3/v2.4)。
     *
     * ★突合できていないカード({@link ManualCardInstance#isResolved()} が false)は
     * 印刷値そのものが分からないため、何もしない。名前だけの灰色タイルに数値を
     * 勝手に生やすことはしない、というだけであり、5-1 の原則に反しない。
     */
    private void resetToPrinted(ManualCardInstance card) {
        if (!card.isResolved()) {
            return;
        }
        ManualCardMaster master = cards.findById(card.getCardId());
        card.setAttack(master.attack());
        card.setHp(master.hp());
    }

    /** 数値の表示。★印刷値が空欄(スペル・リーダー)なら null のままなので「-」と書く。 */
    private String numberText(Integer value) {
        return value == null ? "-" : value.toString();
    }

    private Integer applyDelta(Integer current, int delta, String axis) {
        if (current == null) {
            throw new IllegalArgumentException(
                    "%s の現在値が空欄のため増減できません。値を直接指定してください".formatted(axis));
        }
        return current + delta;
    }

    /**
     * ログに出すカード名。★突合できていないカードは名前だけを持つ(設計書 7-3)。
     *
     * カード定義を引けなくても例外にしない。ここはログ本文を組み立てているだけであり、
     * 名前が出せないことを理由に操作そのものを差し戻すのは筋が違う。
     */
    private String displayName(ManualCardInstance card) {
        if (!card.isResolved()) {
            return card.getFallbackName() == null ? "不明なカード" : card.getFallbackName();
        }
        try {
            return cards.findById(card.getCardId()).name();
        } catch (IllegalArgumentException e) {
            return card.getCardId();
        }
    }

    private String describeOrigin(List<ManualCardRef> refs) {
        ManualCardRef first = refs.get(0);
        for (ManualCardRef ref : refs) {
            if (ref.seatId() != first.seatId() || ref.zone() != first.zone()
                    || ref.isMaterial() != first.isMaterial()) {
                return "複数の場所";
            }
        }
        // ★20b: 共有ゾーンの判定を先に置く。共有ゾーンでは seatId が null であり、
        //   「席null」という文字列がログに出てしまうため(進化スタックが共有ゾーンに
        //   置かれている場合も同様である)。
        String place = first.isShared()
                ? "共有"
                : "席%s".formatted(first.seatId());
        if (first.isMaterial()) {
            return "%s 進化スタック".formatted(place);
        }
        return "%s %s".formatted(place, first.zone().getDisplayName());
    }

    private String describeCards(List<ManualCardRef> refs) {
        List<String> names = new ArrayList<>();
        for (ManualCardRef ref : refs) {
            if (names.size() >= LOG_NAME_LIMIT) {
                names.add("ほか%d枚".formatted(refs.size() - LOG_NAME_LIMIT));
                break;
            }
            names.add("《%s》".formatted(displayName(ref.card())));
        }
        return String.join(" ", names);
    }

    private int clampIndex(int value, int size) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, size);
    }
}
