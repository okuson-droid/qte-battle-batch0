package com.example.qte.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.example.qte.deck.DeckDefinition;
import com.example.qte.deck.DeckFileReader;
import com.example.qte.deck.DeckValidator;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Civilization;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.GameRoomOptions;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.SeatId;
import com.example.qte.room.Spectator;

import lombok.RequiredArgsConstructor;

/**
 * 通常モードの HTTP 入口。
 *
 * 「ページを開くまで」と「盤面に持ち込むファイル」は HTTP、
 * 「開いた後の操作」は WebSocket、という手動モードと同じ役割分担である。
 *
 * <h2>★★Batch 66: ロビーの形を手動モードに揃えた</h2>
 *
 * 65 までの通常モードのロビーは、<b>2つの HTML フォームを POST する</b>形だった ——
 * 部屋を作るフォームと、部屋コードを打って入るフォーム。どちらもその場で
 * 名前・リーダー・デッキファイルを受け取り、成功したら盤面へリダイレクトする。
 * 部屋一覧は無く、部屋に名前も無く、鍵も観戦も無かった。
 *
 * <p>66 で、手動モード(21a・21b)と同じ形に寄せた。
 * <ol>
 * <li>受付は<b>JSON API</b> になった({@link #listRooms} / {@link #createRoom} /
 *     {@link #join})。画面は fetch で叩く。</li>
 * <li>盤面は {@code playerId} をクエリで受け取らない。誰であるかは
 *     クライアントの localStorage と {@link #join} で決まる(手動モードの 6-3 と同じ)。</li>
 * <li>デッキは<b>盤面に入ってから</b>読み込む({@link #importDeck})。
 *     ★プリセット(おまかせ)は退役した。</li>
 * </ol>
 *
 * <h2>★手動モードとの意図的な違い</h2>
 * <ul>
 * <li><b>全公開(一人回し)部屋が無い。</b>理由は {@link GameRoomOptions} に書いた。
 *     部屋の「種類」という欄そのものが存在しない。</li>
 * <li><b>デッキは JSON テキストで受ける</b>(手動モードは zip も受ける)。
 *     通常モードが読める形は {@code taboo-elemental-deck} v2 の1つだけである(裁定291)。</li>
 * <li><b>席を立てない。</b>観戦者からプレイヤーへの昇格・その逆は作っていない ——
 *     通常モードの席は {@code GameState} の2人と1対1であり、
 *     試合が始まったあとに動かすと盤面の持ち主が消える。</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class LobbyController {

    private final GameRoomManager roomManager;
    private final CardMasterRepository cards;
    private final DeckValidator deckValidator;
    private final DeckFileReader deckFileReader;

    // ------------------------------------------------------------------
    // 画面
    // ------------------------------------------------------------------

    /**
     * ★Batch 19a: {@code /} は手動モードの新ロビーになった(設計書 6-2)。
     * このメソッドは通常モードのロビーである。
     * ★Batch 66: リーダーの一覧はもう渡さない(プルダウンが消えたため)。
     */
    @GetMapping("/auto")
    public String lobby() {
        return "lobby";
    }

    /**
     * 手動モードの新ロビー(設計書 6-2)。部屋作成・入室は {@code manual-lobby.html} の JS が
     * {@code ManualLobbyController} の JSON API を叩く形であり、このメソッド自体は
     * ビュー名を返すだけの薄い入口である(手動モードの依存をこのクラスへ持ち込まない)。
     */
    @GetMapping("/")
    public String manualLobby() {
        return "manual-lobby";
    }

    /**
     * 対戦画面。以降のやり取りはWebSocketに切り替わる。
     *
     * <p>★<b>Batch 66: {@code playerId} を受け取らない。</b>
     * 65 まではロビーの POST が発行した playerId をクエリに載せて渡していたので、
     * <b>URL を共有すると相手の席として入れてしまい</b>、再読み込みで
     * 席を失うこともあった(URL を無くすと戻れない)。
     * 手動モードと同じく、誰であるかは localStorage と入室APIで決まる。
     */
    @GetMapping("/rooms/{roomId}/play")
    public String play(@PathVariable String roomId, Model model) {
        GameRoom room = roomManager.requireRoom(roomId);
        model.addAttribute("roomId", room.getRoomId());
        return "battle";
    }

    /**
     * デッキメーカー画面(Batch 24)。カードデータは
     * {@code /manual/api/card-library} から取得する薄い静的画面であり、
     * ここではビュー名を返すだけである(手動モードの依存をこのクラスへ持ち込まない)。
     *
     * <p>★Batch 63 から<b>両モード共通のデッキを組む場所</b>である。
     */
    @GetMapping("/deck-maker")
    public String deckMaker() {
        return "manual-deck-maker";
    }

    /** カードマスタ一覧(人が読む用) */
    @GetMapping("/cards")
    public String cards(Model model) {
        var byCiv = new LinkedHashMap<Civilization, List<CardMaster>>();
        for (Civilization civ : Civilization.values()) {
            List<CardMaster> list = cards.findByCivilization(civ);
            if (!list.isEmpty()) {
                byCiv.put(civ, list);
            }
        }
        model.addAttribute("cardsByCivilization", byCiv);
        model.addAttribute("totalCards", cards.getAllCards().size());
        model.addAttribute("roomCount", roomManager.roomCount());
        return "cards";
    }

    // ------------------------------------------------------------------
    // 受付(★Batch 66・JSON API)
    // ------------------------------------------------------------------

    /**
     * 部屋一覧(★Batch 66)。手動モードの {@code /manual/api/rooms} と同じ規約である。
     *
     * <h3>★盤面状態を返さない</h3>
     * 一覧は入室していない人が見る画面である。ここに盤面を載せると、
     * {@code GameViewBuilder} の情報フィルタを HTTP 側から迂回できてしまう。
     *
     * <h3>★鍵つき部屋の部屋IDを載せない</h3>
     * 部屋ID(ランダム6文字)がパスワードを兼ねている。
     * 一覧に出した瞬間に鍵の意味が消えるため、{@code requireRoomId} の部屋では null にする。
     *
     * <h3>自動更新しない</h3>
     * 更新は画面側の手動ボタンで行う。部屋数が少ない前提であり、
     * ポーリングを足すのは必要になってからでよい。
     */
    @GetMapping("/auto/api/rooms")
    @ResponseBody
    public List<RoomSummary> listRooms() {
        List<RoomSummary> summaries = new ArrayList<>();
        for (GameRoom room : roomManager.allRooms()) {
            summaries.add(toSummary(room,
                    room.getOptions().requireRoomId() ? null : room.getRoomId()));
        }
        summaries.sort((a, b) -> a.roomName().compareTo(b.roomName()));
        return summaries;
    }

    /**
     * 部屋1件の情報(★Batch 66)。席選択画面が<b>入室する前に</b>読む。
     *
     * <p>★この API は部屋IDを<b>知っている人</b>にしか答えない。
     * IDを知っていること自体が入室の権利である以上、その人に部屋名と席の埋まりを
     * 見せても新しく漏れる情報は無い。盤面状態を返さないのは一覧と同じである。
     */
    @GetMapping("/auto/api/rooms/{roomId}")
    @ResponseBody
    public RoomSummary getRoom(@PathVariable String roomId) {
        GameRoom room = roomManager.requireRoom(roomId);
        return toSummary(room, room.getRoomId());
    }

    /**
     * 部屋を作り、作成者をそのまま席に着かせる(★Batch 66)。
     *
     * <p>★手動モードと違い、属性を省略した既定は<b>無い</b>。
     * 通常モードの部屋は必ず対戦部屋であり、部屋名も名前も必須である
     * ({@link GameRoomOptions} と {@link GameRoom#join} が断る)。
     */
    @PostMapping("/auto/api/rooms")
    @ResponseBody
    public JoinResponse createRoom(@RequestBody(required = false) CreateRoomRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("部屋の情報が届いていません");
        }
        GameRoomOptions options = new GameRoomOptions(
                request.roomName(),
                request.spectatorAllowed() == null || request.spectatorAllowed(),
                Boolean.TRUE.equals(request.requireRoomId()));
        GameRoom room = roomManager.createRoom(options);
        SeatId seat = request.seat() == null ? SeatId.A : request.seat();
        PlayerSlot slot = room.join(request.displayName(), seat);
        return JoinResponse.ofPlayer(room, slot);
    }

    /**
     * 既存の部屋に入る(★Batch 66)。playerId(観戦者なら spectatorId)はここで発行される。
     *
     * @param request {@code seat} が非 null なら着席、{@code spectate} が true なら観戦。
     *                ★どちらでもない要求は断る —— 手動モードの全公開部屋にあった
     *                「席が空いていれば自動で座らせる」後方互換は、
     *                通常モードには<b>持ち込まない</b>(観戦のつもりで席に着かされると、
     *                デッキを読み込むまで相手の試合が始まらない)
     */
    @PostMapping("/auto/api/rooms/{roomId}/occupants")
    @ResponseBody
    public JoinResponse join(@PathVariable String roomId,
            @RequestBody(required = false) JoinRequest request) {
        GameRoom room = roomManager.requireRoom(roomId);
        synchronized (room.getLock()) {
            SeatId seat = request == null ? null : request.seat();
            boolean spectate = request != null && Boolean.TRUE.equals(request.spectate());
            if (seat != null) {
                return JoinResponse.ofPlayer(room, room.join(request.displayName(), seat));
            }
            if (!spectate) {
                throw new IllegalArgumentException("席に着くか観戦かを選んでください");
            }
            Spectator spectator = room.spectate(request.displayName());
            return JoinResponse.ofSpectator(room, spectator);
        }
    }

    /**
     * デッキファイルを自席に読み込む(★Batch 66)。本文はファイルのバイト列そのものである。
     *
     * <h3>★なぜ盤面に入ってから読むのか</h3>
     * 65 まではロビーのフォームで受け取っていた。その形は
     * <b>「部屋を作る」と「デッキを決める」を1回の送信に束ねる</b>ので、
     * デッキを直したいだけでも部屋を作り直すことになる。
     * 手動モードは 24 から盤面側で読んでおり、そちらのほうが直しやすい。
     *
     * <h3>★読めたかどうかの判断はサーバがする</h3>
     * {@link DeckFileReader} が形式を、{@link DeckValidator} が中身
     * (40枚・8枚・文明・同名上限)を見る。<b>読めなかった理由をそのまま返す</b> ——
     * 62 までの通常モードは理由を握りつぶしており、何を直せばよいか分からなかった(63)。
     *
     * <h3>★試合が始まったあとは断る</h3>
     * 山札はもう配られている。ここで載せ替えても盤面には反映されないので、
     * 「効かない操作が通る」ほうが害である。
     */
    @PostMapping("/auto/api/rooms/{roomId}/deck")
    @ResponseBody
    public DeckLoadResponse importDeck(@PathVariable String roomId,
            @RequestParam String playerId,
            @RequestBody byte[] body) {
        GameRoom room = roomManager.requireRoom(roomId);
        synchronized (room.getLock()) {
            PlayerSlot slot = room.findSlot(playerId).orElseThrow(
                    () -> new IllegalArgumentException("この部屋の席に着いていません"));
            if (room.getGameState() != null) {
                throw new IllegalArgumentException("対戦が始まったあとはデッキを変更できません");
            }
            DeckDefinition deck = deckFileReader.read(new String(body, StandardCharsets.UTF_8));
            deckValidator.validate(deck);
            slot.loadDeck(deck, deck.name());
            return new DeckLoadResponse(room.getRoomId(), slot.getSeat(), slot.getDeckName(),
                    cards.findById(slot.getLeaderCardId()).name(),
                    deck.main().stream().mapToInt(DeckDefinition.Entry::count).sum(),
                    deck.taboo().size());
        }
    }

    /**
     * 入力エラーは 400 で理由だけ返す。画面側が出す。
     *
     * <p>★<b>Batch 66: ロビーへ差し戻さなくなった。</b>65 までは
     * {@code lobby.html} を理由つきで描き直していたが、受付が JSON API になったので
     * 差し戻す先が無い(手動モードと同じ形である)。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<ErrorResponse> handleInvalidInput(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    // ------------------------------------------------------------------
    // 組み立て
    // ------------------------------------------------------------------

    /**
     * 一覧と単体で同じ組み立てを共有する。
     * ★出す項目が2箇所に分かれると、片方だけに項目が足されて食い違う。
     *
     * @param roomId 載せる部屋ID。一覧の鍵つき部屋では null を渡す
     */
    private RoomSummary toSummary(GameRoom room, String roomId) {
        GameRoomOptions options = room.getOptions();
        return new RoomSummary(
                roomId,
                options.name(),
                options.spectatorAllowed(),
                options.requireRoomId(),
                seatName(room, SeatId.A),
                seatName(room, SeatId.B),
                room.spectatorCount(),
                room.getGameState() != null);
    }

    private String seatName(GameRoom room, SeatId seat) {
        return room.slotOfSeat(seat).map(PlayerSlot::getDisplayName).orElse(null);
    }

    // ---- やり取りする型 ----

    /**
     * 部屋作成(★Batch 66)。
     *
     * @param roomName 部屋名(必須)
     * @param seat     作成者が座る席
     */
    public record CreateRoomRequest(
            String displayName,
            String roomName,
            Boolean spectatorAllowed,
            Boolean requireRoomId,
            SeatId seat) {
    }

    /**
     * 入室(★Batch 66)。
     *
     * @param seat     座る席。null なら観戦の意思表示({@code spectate})が要る
     * @param spectate 観戦を選んだか
     */
    public record JoinRequest(String displayName, SeatId seat, Boolean spectate) {
    }

    /**
     * 入室の結果(★Batch 66)。
     *
     * @param playerId 席に着いた人の id、または観戦者の id。
     *                 ★配信の宛先を兼ねるので、どちらも同じ欄で返す ——
     *                 クライアントは購読先を1つしか持たない
     * @param seat     着いた席。観戦者は null
     */
    public record JoinResponse(String roomId, String playerId, String displayName,
            SeatId seat, boolean spectator, String roomName) {

        static JoinResponse ofPlayer(GameRoom room, PlayerSlot slot) {
            return new JoinResponse(room.getRoomId(), slot.getPlayerId(), slot.getDisplayName(),
                    slot.getSeat(), false, room.getOptions().name());
        }

        static JoinResponse ofSpectator(GameRoom room, Spectator spectator) {
            return new JoinResponse(room.getRoomId(), spectator.spectatorId(),
                    spectator.displayName(), null, true, room.getOptions().name());
        }
    }

    /**
     * 部屋一覧の1件(★Batch 66)。★盤面状態も playerId も含まない。
     *
     * @param roomId  鍵つき部屋({@code locked})では null。IDが鍵だからである
     * @param started 対戦が始まっているか。★始まった部屋には座れない
     */
    public record RoomSummary(
            String roomId,
            String roomName,
            boolean spectatorAllowed,
            boolean locked,
            String seatAName,
            String seatBName,
            int spectatorCount,
            boolean started) {
    }

    /** デッキ読み込みの結果(★Batch 66)。画面が「何を読んだか」を出すのに使う */
    public record DeckLoadResponse(String roomId, SeatId seat, String deckName,
            String leaderName, int mainCount, int tabooCount) {
    }

    public record ErrorResponse(String message) {
    }
}
