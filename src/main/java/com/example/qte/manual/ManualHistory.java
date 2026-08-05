package com.example.qte.manual;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Undo / Redo のスナップショットスタック(設計書 5-6 / Batch 21 設計書 6-3)。
 *
 * <h2>深さは部屋の種類が決める(21 設計書 1-1・6-3)</h2>
 * <ul>
 *   <li>全公開部屋 — 深さ {@link #MAX_DEPTH}(200)。Redo あり。現行のまま</li>
 *   <li>対戦部屋 — 深さ {@link #VERSUS_DEPTH}(1)。Redo なし</li>
 * </ul>
 * 1状態は数KB(カードIDと数値と短い文字列だけ)であり、200手で部屋あたり最大1MB程度に収まる。
 * 200手あれば一人回しの検証では実用上無制限に等しい。
 * 対戦で深さを1に絞るのは容量の都合ではなく、<b>相手の見ている盤面が何手も巻き戻ると
 * 何が起きたのか追えなくなる</b>ためである。取り消しの取り消し(Redo)を切るのも同じ理由で、
 * やり直したければ手で操作し直す。
 * 古いものから捨てるため、両端に触れる {@link ArrayDeque} を使う。
 *
 * <h2>★複製はこのクラスの中で行う</h2>
 * 設計書 5-6 は {@code history.push(state.copy())} と書いているが、実装では
 * {@link #push} が内部で複製する。呼び出し側の責務にすると、1箇所でも copy() を
 * 書き忘れた瞬間に「履歴に積んだはずの状態が、その後の操作で一緒に書き換わる」という、
 * 症状が出るまで気づけない不具合になる。器の側で閉じるほうが安い。
 *
 * <h2>★誰の操作かを一緒に積む(21 6-3)</h2>
 * 対戦部屋の Undo は「直前の操作をした席だけ」が使える。
 * その判定に必要な情報は履歴の中にしか無いため、スナップショットと対にして持つ。
 * 判定そのものは {@link ManualPermissions#denyUndo} が行う。
 */
public class ManualHistory {

    /** 全公開部屋の履歴の最大深さ。超えたぶんは古いものから捨てる */
    public static final int MAX_DEPTH = 200;

    /** 対戦部屋の履歴の最大深さ(21 6-3) */
    public static final int VERSUS_DEPTH = 1;

    /**
     * 積まれた1手。
     *
     * @param state     操作を適用する<b>前</b>の盤面
     * @param actorSeat その操作を行った席。★観戦者・全公開部屋の無所属者では null
     */
    private record Snapshot(ManualGameState state, ManualSeatId actorSeat) {
    }

    private final int maxDepth;

    private final boolean redoEnabled;

    /** 先頭が直近。Undo で取り出す */
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();

    /** 先頭が直近。Redo で取り出す */
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();

    /** 全公開部屋の既定(深さ200・Redo あり)。 */
    public ManualHistory() {
        this(MAX_DEPTH, true);
    }

    public ManualHistory(int maxDepth, boolean redoEnabled) {
        this.maxDepth = Math.max(1, maxDepth);
        this.redoEnabled = redoEnabled;
    }

    /** 部屋の種類から履歴を作る(21 6-3)。★深さの決定はここ1箇所に集約する。 */
    public static ManualHistory forRoom(ManualRoomType type) {
        return type.isRestricted()
                ? new ManualHistory(VERSUS_DEPTH, false)
                : new ManualHistory(MAX_DEPTH, true);
    }

    /**
     * 操作を適用する<b>前</b>の状態を積む。渡した状態はこの中で複製される。
     * 新しい操作を積んだ時点で Redo は無効になる(枝分かれした未来は保持しない)。
     *
     * @param actorSeat その操作を行った席(21 6-3)
     */
    public void push(ManualGameState state, ManualSeatId actorSeat) {
        undoStack.addFirst(new Snapshot(state.copy(), actorSeat));
        while (undoStack.size() > maxDepth) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    /**
     * 1手戻した状態を返す。戻せなければ空を返す。
     *
     * ★権限の判定はここで行わない({@link ManualPermissions#denyUndo} の責務)。
     * この器は「戻せるか」だけを知っており、「戻してよいか」は知らない。
     *
     * @param current   現在の状態。Redo のためにこの中で複製して保持する
     * @param actorSeat 取り消しを行った席。Redo 側の記録に使う
     */
    public Optional<ManualGameState> undo(ManualGameState current, ManualSeatId actorSeat) {
        if (undoStack.isEmpty()) {
            return Optional.empty();
        }
        if (redoEnabled) {
            redoStack.addFirst(new Snapshot(current.copy(), actorSeat));
        }
        return Optional.of(undoStack.removeFirst().state());
    }

    /**
     * 1手やり直した状態を返す。やり直せなければ空を返す。
     * ★対戦部屋({@code redoEnabled == false})では常に空である。
     */
    public Optional<ManualGameState> redo(ManualGameState current, ManualSeatId actorSeat) {
        if (!redoEnabled || redoStack.isEmpty()) {
            return Optional.empty();
        }
        undoStack.addFirst(new Snapshot(current.copy(), actorSeat));
        return Optional.of(redoStack.removeFirst().state());
    }

    /** 履歴を空にする。「リセットして引き直す」とデッキ読み込みで呼ぶ(設計書 5-6)。 */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return redoEnabled && !redoStack.isEmpty();
    }

    /** 次に取り消される1手を行った席(21 6-3)。無ければ null。 */
    public ManualSeatId lastActorSeat() {
        Snapshot top = undoStack.peekFirst();
        return top == null ? null : top.actorSeat();
    }

    public int undoDepth() {
        return undoStack.size();
    }

    public int redoDepth() {
        return redoStack.size();
    }

    /** Redo を提供する部屋か(対戦部屋では false。21 D6)。 */
    public boolean isRedoEnabled() {
        return redoEnabled;
    }
}
