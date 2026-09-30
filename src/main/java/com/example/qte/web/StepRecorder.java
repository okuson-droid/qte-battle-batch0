package com.example.qte.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.example.qte.game.GameEvent;
import com.example.qte.game.view.EventView;
import com.example.qte.game.view.GameView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.room.GameRoom;
import com.example.qte.web.GameBroadcaster.GameStep;

/**
 * 解決の途中を段として控える係(★Batch 84a・裁定362)。
 *
 * <h2>なぜ「記録係」という形なのか</h2>
 *
 * 通常モードは<b>1操作 = 1配信</b>である
 * ({@code GameWsController#execute} は {@code synchronized} を抜けてから
 * {@code broadcast} を1回だけ呼ぶ)。解決の途中の状態は、配信の時点で
 * <b>もうサーバのどこにも残っていない</b> ——
 * したがって段を作るには<b>解決の最中に控える</b>しかない。
 *
 * <p>★{@code GameRoom} はこの器を知らない。知っているのは
 * 「{@link Consumer} が居れば、ログ行を1本渡す」だけである
 * ({@code GameState} を Spring のビーンにしない方針を崩さないため)。
 *
 * <h2>★★★段の上限は 8 である(裁定370)</h2>
 *
 * <b>クライアントの上限(累計時間)とは別の上限である。</b>
 * ★<b>守っているものが違う</b> —— こちらは<b>配信量</b>を守り、
 * あちらは<b>操作できない時間</b>を守る(裁定364)。
 * ★★値が違ってよいのは、裁定358 が「揃えないことにも理由が要る」と決めたのと同じ形である。
 *
 * <p>★8 という値の根拠は {@code FX_LIMIT}(= {@code SFX_DIFF_LIMIT})と同じものである ——
 * <b>8段を超えたぶんは、送っても演出されない</b>。
 * ★★<b>値を揃えたのではなく、根拠が1つなのである。</b>
 *
 * <p>★★★<b>超えたぶんは捨てるのではなく畳む。</b>ログ行は
 * {@code GameRoom} 側に1行も欠けずに残るので、人は何が起きたかをログで追える
 * (裁定368・畳んだことは画面に出す)。
 *
 * <h2>実測(Batch 84a・`notes/batch84a-design-notes.md`)</h2>
 *
 * カード169枚を本物の入口から1枚ずつ使って測った結果、
 * <b>1操作あたりの段数は平均 2.33段・86.4%が2段以下</b>であり、
 * ★<b>8段で切ると 169枚中160枚(94.7%)は1段も畳まれない</b>。
 * ★★1ビューは約20KB なので、上限が無いと最悪 511KB(24段)になる。
 */
public final class StepRecorder implements Consumer<String> {

    /** ★段の上限。{@code FX_LIMIT} と同じ根拠の 8(裁定370) */
    public static final int LIMIT = 8;

    /**
     * 1段ぶんの控え。★閲覧者ごとにビューと出来事を作ってある。
     *
     * <p>★★Batch 85a: <b>出来事もここで閲覧者ごとに切り落とす</b>(設計書 3-1)——
     * ビューと同じ場所・同じ閲覧者一覧で作るので、<b>段のビューと段の出来事の視点がずれない</b>。
     */
    private record StepFrame(String logLine, Map<String, GameView> byViewer,
            Map<String, List<EventView>> eventsByViewer) {
    }

    private final GameViewBuilder viewBuilder;
    private final GameRoom room;
    private final List<String> viewerIds;
    private final List<StepFrame> frames = new ArrayList<>();
    private int folded;
    /** ★最後のログ行の後に起きた出来事(最終状態に付ける・裁定374)。閲覧者ごと */
    private final Map<String, List<EventView>> tailByViewer = new HashMap<>();

    private StepRecorder(GameViewBuilder viewBuilder, GameRoom room, List<String> viewerIds) {
        this.viewBuilder = viewBuilder;
        this.room = room;
        this.viewerIds = viewerIds;
    }

    /**
     * 部屋の全員ぶんを控える記録係を作る。
     *
     * <p>★<b>閲覧者の一覧は差し込む時点で固定する。</b>操作の最中に席が動くことは
     * ロックが防ぐが、<b>操作をまたいで人が増減する</b>ことは起きる ——
     * その場合、増えた人には段が1つも無いので
     * {@link #stepsFor} が空を返し、<b>従来どおり最終状態だけが描かれる</b>(退化の経路)。
     */
    public static StepRecorder forViewers(GameViewBuilder viewBuilder, GameRoom room,
            List<String> viewerIds) {
        return new StepRecorder(viewBuilder, room, viewerIds);
    }

    /**
     * 誰にも配らない器のための、何も控えない記録係。
     *
     * <p>★<b>閲覧者が0人なので、ビルダーには1度も触れない。</b>
     * 試験の {@code PeekingBroadcaster} が使う ——
     * あの器は「配信が起きたこと」だけを数えており、ビューを組み立てない。
     */
    public static StepRecorder none() {
        return new StepRecorder(null, null, List.of());
    }

    /**
     * ★★★ログ行が1つ増えるたびに1段である(裁定362)。
     *
     * <p>★<b>控えるのはログ行が積まれた「後」である</b> ——
     * 段のビューとログ行は<b>同じ瞬間のもの</b>でなければならない。
     */
    @Override
    public void accept(String logLine) {
        // ★★Batch 85a: この段に属する出来事(このログ行の前に起きたもの)を汲み出す。
        //   ★<b>畳む段でも汲み出す</b> —— 汲み出さないと、畳んだ段の出来事が
        //   次の段(または最終状態)へ<b>ずれて</b>載る。
        List<GameEvent> events = drain();
        if (frames.size() >= LIMIT) {
            // ★★★畳んだ段の出来事は、段と一緒に落とす(裁定374)。
            //   最終状態には結果が載っているので、嘘にはならない
            folded++;
            return;
        }
        Map<String, GameView> byViewer = new HashMap<>();
        Map<String, List<EventView>> eventsByViewer = new HashMap<>();
        for (String viewerId : viewerIds) {
            byViewer.put(viewerId, viewBuilder.build(room, viewerId));
            eventsByViewer.put(viewerId, viewBuilder.buildEvents(room, viewerId, events));
        }
        frames.add(new StepFrame(logLine, byViewer, eventsByViewer));
    }

    /**
     * 最後のログ行の後に起きた出来事を締める(★Batch 85a・裁定374)。
     *
     * <p>★<b>記録係を外す直前に1度だけ呼ぶ</b>({@code GameWsController.execute} の {@code finally})。
     * 外すと部屋が溜めを空にするので、それより前でなければならない。
     * ★これは畳みの対象ではない —— 段ではなく<b>最終状態</b>に付く出来事である。
     */
    public void closeTail() {
        List<GameEvent> events = drain();
        for (String viewerId : viewerIds) {
            tailByViewer.put(viewerId, viewBuilder.buildEvents(room, viewerId, events));
        }
    }

    private List<GameEvent> drain() {
        return room == null ? List.of() : room.drainEvents();
    }

    /**
     * その閲覧者へ載せる段の列。★段が無ければ空である。
     *
     * <p>★★<b>1段でも欠けていたら、まるごと空を返す。</b>
     * 穴の空いた列を送ると、<b>クライアントは飛んだ段を「起きなかったこと」として再生する</b> ——
     * それは裁定62(演出は観測できたことだけを語る)の逆である。
     * ★空なら従来どおり最終状態だけが描かれるので、<b>嘘にはならない</b>。
     */
    public List<GameStep> stepsFor(String viewerId) {
        List<GameStep> steps = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            GameView view = frames.get(i).byViewer().get(viewerId);
            if (view == null) {
                return List.of();
            }
            steps.add(new GameStep(i + 1, frames.get(i).logLine(), view,
                    frames.get(i).eventsByViewer().get(viewerId)));
        }
        return steps;
    }

    /**
     * その閲覧者へ、<b>最終状態に付けて</b>載せる出来事(★Batch 85a)。
     *
     * <p>★★<b>差し込んだ時点で居なかった閲覧者には空を返す</b>(退化の経路)——
     * その人は {@link #stepsFor} も空なので、最終状態だけを描く。
     * 段の無い人に出来事の後半だけを渡すと、<b>途中から始まる物語</b>になる。
     */
    public List<EventView> eventsFor(String viewerId) {
        return tailByViewer.getOrDefault(viewerId, List.of());
    }

    /** 上限で畳んだ段の数。★0 なら1段も畳んでいない(裁定368 の表示はこの値で決まる) */
    public int folded() {
        return folded;
    }

    /** 控えた段の数(★番人が数えるための読み口。裁定362 の「段 = ログ行」を測る) */
    public int size() {
        return frames.size();
    }
}
