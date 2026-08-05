package com.example.qte.manual.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import com.example.qte.manual.ManualActor;
import com.example.qte.manual.ManualDeckImport;
import com.example.qte.manual.ManualDeckImporter;
import com.example.qte.manual.ManualGameService;
import com.example.qte.manual.ManualLabels;
import com.example.qte.manual.ManualLogEntry;
import com.example.qte.manual.ManualLogRenderer;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualPermissions;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;
import com.example.qte.manual.ManualRoomOptions;
import com.example.qte.manual.ManualRoomType;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualViewpoint;
import com.example.qte.manual.view.ManualGameView;
import com.example.qte.manual.view.ManualViewBuilder;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードの HTTP 入口。
 *
 * 「ページを開くまで」と「盤面に持ち込むファイル」は HTTP、
 * 「開いた後の操作」は WebSocket、という通常モードと同じ役割分担である。
 *
 * <h2>★Batch 21a で足した入口</h2>
 * <ol>
 *   <li>{@link #listRooms} — 部屋一覧(1-3)。★盤面状態は返さない</li>
 *   <li>{@link #createRoom} が部屋の属性を受け取る(1-2)</li>
 *   <li>{@link #join} が席を受け取る(2-1)</li>
 *   <li>{@link #exportLog} が閲覧者の視点でレンダリングする(5-4)</li>
 * </ol>
 * 画面(ロビー・席選択)は 21b が作る。ここで用意するのはその土台となる API だけである。
 *
 * <h2>★デッキ zip を multipart で受けない理由</h2>
 * {@code MultipartFile} で受けると、Spring Boot 既定の 1MB 上限に引っかかる。
 * ユドナリウムの保存 zip は画像を同梱することがあり(設計書 7-2)、容易に 1MB を超える。
 * 上限を上げるには <b>通常モードを含むアプリ全体の設定に触る</b>ことになる。
 * zip の中身は1つのバイト列でしかないため、{@code @RequestBody byte[]} で素のまま受ける。
 */
@Controller
@RequiredArgsConstructor
public class ManualLobbyController {

    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ManualRoomManager roomManager;

    private final ManualDeckImporter deckImporter;

    private final ManualGameService gameService;

    private final ManualViewBuilder viewBuilder;

    private final ManualBroadcaster broadcaster;

    private final ManualLogRenderer logRenderer;

    /**
     * 盤面画面。★occupantId はここでは受け取らない。誰であるかは
     * クライアントの localStorage と {@link #join} で決まる(設計書 6-3)。
     */
    @GetMapping("/manual/battle/{roomId}")
    public String battle(@PathVariable String roomId, Model model) {
        ManualRoom room = roomManager.requireRoom(roomId);
        model.addAttribute("roomId", room.getRoomId());
        model.addAttribute("defaultLabels", ManualLabels.DEFAULTS);
        return "manual-battle";
    }

    /**
     * 部屋一覧(Batch 21a 設計書 1-3)。
     *
     * <h3>★盤面状態を返さない</h3>
     * 一覧は入室していない人が見る画面である。ここに盤面を載せると、
     * 個別宛先による情報保護(2-4)を HTTP 側から迂回できてしまう。
     * 返すのは表示に要る項目だけに限る。
     *
     * <h3>★鍵つき部屋の部屋IDを載せない(F1)</h3>
     * 部屋ID(ランダム6文字)がパスワードを兼ねている(1-2)。
     * 一覧に出した瞬間に鍵の意味が消えるため、{@code requireRoomId} の部屋では null にする。
     * 入りたい人はIDを口頭やチャットで受け取り、直接入室の欄に打ち込む。
     *
     * <h3>自動更新しない(1-3)</h3>
     * 更新は画面側の手動ボタンで行う。部屋数が少ない前提であり、
     * ポーリングを足すのは必要になってからでよい。
     */
    @GetMapping("/manual/api/rooms")
    @ResponseBody
    public List<RoomSummary> listRooms() {
        List<RoomSummary> summaries = new ArrayList<>();
        for (ManualRoom room : roomManager.allRooms()) {
            ManualRoomOptions options = room.getOptions();
            summaries.add(new RoomSummary(
                    options.requireRoomId() ? null : room.getRoomId(),
                    options.name(),
                    options.type(),
                    options.spectatorAllowed(),
                    options.requireRoomId(),
                    seatName(room, ManualSeatId.A),
                    seatName(room, ManualSeatId.B),
                    room.spectatorCount()));
        }
        summaries.sort((a, b) -> a.roomName().compareTo(b.roomName()));
        return summaries;
    }

    /**
     * 部屋を作り、作成者をそのまま在室させる。
     *
     * ★属性を省略した要求は従来どおりの全公開部屋になる(1-2 の {@code openDefault})。
     * 20c までのロビー画面は属性を送らないため、この既定が後方互換を担っている。
     * 21b の新ロビーが属性を送るようになる。
     */
    @PostMapping("/manual/api/rooms")
    @ResponseBody
    public JoinResponse createRoom(@RequestBody(required = false) CreateRoomRequest request) {
        ManualRoomOptions options = request == null
                ? ManualRoomOptions.openDefault()
                : new ManualRoomOptions(request.roomName(),
                        request.type() == null ? ManualRoomType.OPEN : request.type(),
                        request.spectatorAllowed() == null || request.spectatorAllowed(),
                        Boolean.TRUE.equals(request.requireRoomId()));
        ManualRoom room = roomManager.createRoom(options);
        // ★作った人は席に着く。空席が無いことは起こりえない(作った直後だから)
        ManualSeatId seat = request != null && request.seat() != null
                ? request.seat()
                : ManualSeatId.A;
        ManualOccupant occupant = room.join(request == null ? null : request.displayName(), seat);
        return toJoinResponse(room, occupant);
    }

    /**
     * 既存の部屋に入室する。occupantId はここで発行される。
     *
     * @param seat 座る席(2-1)。★null は観戦を意味するが、
     *             <b>全公開部屋では後方互換のため空席へ自動で座らせる</b>。
     *             20c までのロビー画面は席を送らず、送られた人はプレイヤーとして扱われていた。
     *             対戦部屋では 21b の席選択画面が必ず席を明示する
     */
    @PostMapping("/manual/api/rooms/{roomId}/occupants")
    @ResponseBody
    public JoinResponse join(@PathVariable String roomId,
            @RequestBody(required = false) JoinRequest request) {
        ManualRoom room = roomManager.requireRoom(roomId);
        synchronized (room.getLock()) {
            ManualSeatId seat = request == null ? null : request.seat();
            boolean spectate = request != null && Boolean.TRUE.equals(request.spectate());
            if (seat == null && !spectate && !room.getType().isRestricted()) {
                // ★全公開部屋の後方互換。席が埋まっていれば観戦者になる
                seat = room.firstFreeSeat().orElse(null);
            }
            ManualOccupant occupant =
                    room.join(request == null ? null : request.displayName(), seat);
            return toJoinResponse(room, occupant);
        }
    }

    /**
     * デッキ zip を席に読み込む。本文は zip のバイト列そのものである。
     * 検証違反は拒否せず、警告として返す(設計書 7-4)。
     *
     * ★対戦部屋では自席のぶんだけ読み込める(21 6-3・E3)。
     * ボタンの出し分け(21b)はあくまで操作補助であり、
     * 検証はサーバで行う(設計判断27「外部から来るデータはすべて検証する」)。
     */
    @PostMapping("/manual/api/rooms/{roomId}/deck")
    @ResponseBody
    public DeckImportResponse importDeck(@PathVariable String roomId,
            @RequestParam(defaultValue = "A") ManualSeatId seat,
            @RequestParam(required = false) String occupantId,
            @RequestBody byte[] body) {
        ManualRoom room = roomManager.requireRoom(roomId);
        ManualDeckImport imported = deckImporter.importZip(body);
        ManualGameView view;
        synchronized (room.getLock()) {
            ManualOccupant viewer = room.findOccupant(occupantId).orElse(null);
            if (room.getType().isRestricted()) {
                if (viewer == null) {
                    throw new IllegalArgumentException("この部屋に入室していません");
                }
                ManualPermissions.require(ManualPermissions.denySeatAction(
                        ManualActor.of(room, viewer), seat));
            }
            gameService.loadDeck(room, seat, imported);
            view = viewBuilder.build(room, viewer);
        }
        broadcaster.broadcast(room);
        return new DeckImportResponse(
                room.getRoomId(),
                seat,
                imported.deckName(),
                imported.leader() == null ? null : imported.leader().displayName(),
                imported.main().size(),
                imported.taboo().size(),
                imported.totalCards(),
                imported.unresolvedCount(),
                imported.warnings(),
                view);
    }

    /**
     * ログをテキストファイルとして書き出す(設計書 5-5 / 21 5-4)。
     *
     * ★WebSocket ではなく HTTP GET にした。ファイルダウンロードはブラウザの標準機能
     * (リンククリック → 保存ダイアログ)に任せるのが最も単純である。
     *
     * <h3>★各自「自分に見えているログ」が落ちる(5-4)</h3>
     * ダウンロードだけ完全版、という裏口は作らない。同じ
     * {@link ManualLogRenderer} を配信と共有しているため、経路によって見え方が変わらない。
     * 全公開部屋と全見え観戦者は結果的に完全ログになる。
     *
     * <h3>★対戦部屋では occupantId が必須である</h3>
     * 誰として書き出すのかが分からないまま完全ログを返すのが、まさにその裏口である。
     * 分からないなら断る。全公開部屋は元々すべてが公開なので、省略を許す。
     */
    @GetMapping("/manual/api/rooms/{roomId}/log")
    public ResponseEntity<byte[]> exportLog(@PathVariable String roomId,
            @RequestParam(required = false) String occupantId) {
        ManualRoom room = roomManager.requireRoom(roomId);
        ManualOccupant viewer = room.findOccupant(occupantId).orElse(null);
        if (room.getType().isRestricted() && viewer == null) {
            throw new IllegalArgumentException(
                    "対戦部屋のログは、その部屋の在室者としてのみ書き出せます");
        }
        ManualViewpoint viewpoint = ManualViewpoint.of(room, viewer);

        StringBuilder text = new StringBuilder();
        text.append("QTE Battle 手動モード ログ — 部屋 ").append(room.getRoomId())
                .append(" / ").append(room.getOptions().name())
                .append(" / ").append(room.getType().getDisplayName())
                .append('\n');
        if (viewer != null) {
            text.append("視点: ").append(viewer.getDisplayName())
                    .append(viewer.isSeated() ? "(席%s)".formatted(viewer.getSeatId()) : "(観戦)")
                    .append('\n');
        }
        for (ManualLogEntry entry : room.getLog()) {
            text.append('[').append(LOG_TIME_FORMAT.format(entry.at())).append("] ")
                    .append(logRenderer.render(entry.event(), viewpoint)).append('\n');
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        String filename = "qte-manual-log-%s-%s.txt".formatted(room.getRoomId(),
                LOG_TIME_FORMAT.format(Instant.now()).replace(" ", "_").replace(":", ""));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /** 入力エラーは 400 で理由だけ返す。画面側が一覧に出す。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<ErrorResponse> handleInvalidInput(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    private String seatName(ManualRoom room, ManualSeatId seatId) {
        return room.occupantOfSeat(seatId).map(ManualOccupant::getDisplayName).orElse(null);
    }

    private JoinResponse toJoinResponse(ManualRoom room, ManualOccupant occupant) {
        return new JoinResponse(room.getRoomId(), occupant.getOccupantId(),
                occupant.getDisplayName(), occupant.getSeatId(), room.getType(),
                room.getOptions().name());
    }

    // ---- やり取りする型 ----

    /**
     * 部屋作成(1-2)。★すべて省略可能であり、省略すると従来の全公開部屋になる。
     * 対戦部屋({@code type = VERSUS})では {@code roomName} が必須である
     * ({@link ManualRoomOptions} が検証する)。
     */
    public record CreateRoomRequest(
            String displayName,
            String roomName,
            ManualRoomType type,
            Boolean spectatorAllowed,
            Boolean requireRoomId,
            ManualSeatId seat) {
    }

    /**
     * 入室(2-1)。
     *
     * @param seat     座る席。null かつ {@code spectate} が false なら、
     *                 全公開部屋では自動着席、対戦部屋では観戦になる
     * @param spectate 明示的に観戦を選んだか。★全公開部屋で「観戦したい」を表せる唯一の手段
     */
    public record JoinRequest(String displayName, ManualSeatId seat, Boolean spectate) {
    }

    public record JoinResponse(String roomId, String occupantId, String displayName,
            ManualSeatId seat, ManualRoomType roomType, String roomName) {
    }

    /**
     * 部屋一覧の1件(1-3)。★盤面状態も occupantId も含まない。
     *
     * @param roomId 鍵つき部屋({@code locked})では null。IDが鍵だからである(F1)
     */
    public record RoomSummary(
            String roomId,
            String roomName,
            ManualRoomType type,
            boolean spectatorAllowed,
            boolean locked,
            String seatAName,
            String seatBName,
            int spectatorCount) {
    }

    /**
     * @param totalCards      リーダーを含む総枚数。実サンプルは 49 になる
     * @param unresolvedCount カード定義に突合できなかった枚数。0 が正常である
     */
    public record DeckImportResponse(
            String roomId,
            ManualSeatId seat,
            String deckName,
            String leaderName,
            int mainCount,
            int tabooCount,
            int totalCards,
            int unresolvedCount,
            List<String> warnings,
            ManualGameView view) {
    }

    public record ErrorResponse(String message) {
    }
}
