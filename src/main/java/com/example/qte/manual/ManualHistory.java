package com.example.qte.manual;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Undo / Redo のスナップショットスタック(設計書 5-6)。
 *
 * ★Batch 17b で作るのは「積む器」までである。実際に積んで戻す操作は Batch 18a が行う。
 * 例外は {@link #clear()} で、デッキの読み込み(引き直し)の時点で履歴を空にする。
 * 読み込み前の盤面へ戻せることに意味は無いためである。
 *
 * <h2>深さ200で打ち切る(レビューJ反映)</h2>
 * 1状態は数KB(カードIDと数値と短い文字列だけ)であり、200手で部屋あたり最大1MB程度に収まる。
 * 200手あれば一人回しの検証では実用上無制限に等しい。
 * 古いものから捨てるため、両端に触れる {@link ArrayDeque} を使う。
 *
 * <h2>★複製はこのクラスの中で行う</h2>
 * 設計書 5-6 は {@code history.push(state.copy())} と書いているが、実装では
 * {@link #push(ManualGameState)} が内部で複製する。呼び出し側で copy() する必要は無い。
 * 呼び出し側の責務にすると、1箇所でも copy() を書き忘れた瞬間に
 * 「履歴に積んだはずの状態が、その後の操作で一緒に書き換わる」という、
 * 症状が出るまで気づけない不具合になる。器の側で閉じるほうが安い。
 */
public class ManualHistory {

    /** 履歴の最大深さ。超えたぶんは古いものから捨てる */
    public static final int MAX_DEPTH = 200;

    /** 先頭が直近。Undo で取り出す */
    private final Deque<ManualGameState> undoStack = new ArrayDeque<>();

    /** 先頭が直近。Redo で取り出す */
    private final Deque<ManualGameState> redoStack = new ArrayDeque<>();

    /**
     * 操作を適用する<b>前</b>の状態を積む。渡した状態はこの中で複製される。
     * 新しい操作を積んだ時点で Redo は無効になる(枝分かれした未来は保持しない)。
     */
    public void push(ManualGameState state) {
        undoStack.addFirst(state.copy());
        while (undoStack.size() > MAX_DEPTH) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    /**
     * 1手戻した状態を返す。戻せなければ空を返す。
     *
     * @param current 現在の状態。Redo のためにこの中で複製して保持する
     */
    public Optional<ManualGameState> undo(ManualGameState current) {
        if (undoStack.isEmpty()) {
            return Optional.empty();
        }
        redoStack.addFirst(current.copy());
        return Optional.of(undoStack.removeFirst());
    }

    /**
     * 1手やり直した状態を返す。やり直せなければ空を返す。
     *
     * @param current 現在の状態。Undo のためにこの中で複製して保持する
     */
    public Optional<ManualGameState> redo(ManualGameState current) {
        if (redoStack.isEmpty()) {
            return Optional.empty();
        }
        undoStack.addFirst(current.copy());
        return Optional.of(redoStack.removeFirst());
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
        return !redoStack.isEmpty();
    }

    public int undoDepth() {
        return undoStack.size();
    }

    public int redoDepth() {
        return redoStack.size();
    }
}
