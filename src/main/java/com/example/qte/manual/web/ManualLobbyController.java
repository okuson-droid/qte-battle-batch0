package com.example.qte.manual.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

import com.example.qte.manual.ManualDeckImport;
import com.example.qte.manual.ManualDeckImporter;
import com.example.qte.manual.ManualGameService;
import com.example.qte.manual.ManualLabels;
import com.example.qte.manual.ManualLogEntry;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualOccupantRole;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.view.ManualGameView;
import com.example.qte.manual.view.ManualViewBuilder;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードの HTTP 入口。
 *
 * 「ページを開くまで」と「盤面に持ち込むファイル」は HTTP、
 * 「開いた後の操作」は WebSocket、という通常モードと同じ役割分担である。
 *
 * <h2>★入口の作り替え(Batch 19a)</h2>
 * 設計書 6-2 が定める「{@code /} を手動モードの新ロビーにし、既存ロビーを {@code /auto} へ移す」は
 * {@code LobbyController}(通常モードのファイル)側で行う。本クラスが 19a で追加したのは
 * 盤面画面そのものの入口({@link #battle})と、暫定入口だった {@code ManualBattleController} の
 * 置き換えである。★{@code occupantId} はもうサーバ側で発行・受け渡ししない。
 * クライアントが localStorage で保持し、無ければ {@link #join} を呼ぶ(設計書 6-3)。
 * 17b の目視確認専用画面({@code /manual/deck-check})は本物の盤面がある今は不要なため削除した。
 *
 * <h2>★デッキ zip を multipart で受けない理由</h2>
 * {@code MultipartFile} で受けると、Spring Boot 既定の 1MB 上限に引っかかる。
 * ユドナリウムの保存 zip は画像を同梱することがあり(設計書 7-2)、容易に 1MB を超える。
 * 上限を上げるには {@code application.properties} を書き換えるか
 * {@code MultipartConfigElement} を差し替えることになり、どちらも
 * <b>通常モードを含むアプリ全体の設定に触る</b>。
 * zip の中身は1つのバイト列でしかないため、{@code @RequestBody byte[]} で素のまま受ける。
 * 設定を1行も足さずに済み、通常モードへの影響も無い。
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

    /**
     * 盤面画面。★occupantId はここでは受け取らない。誰であるかは
     * クライアントの localStorage と {@link #join} で決まる(設計書 6-3)。
     * 部屋が無ければ 400 で {@link #handleInvalidInput} がロビーへの導線を返す代わりに
     * エラーメッセージだけを返す(手動モードにロビー相当の再表示画面が無いため)。
     */
    @GetMapping("/manual/battle/{roomId}")
    public String battle(@PathVariable String roomId, Model model) {
        ManualRoom room = roomManager.requireRoom(roomId);
        model.addAttribute("roomId", room.getRoomId());
        model.addAttribute("defaultLabels", ManualLabels.DEFAULTS);
        return "manual-battle";
    }

    /** 部屋を作り、作成者をそのまま在室させる。 */
    @PostMapping("/manual/api/rooms")
    @ResponseBody
    public JoinResponse createRoom(@RequestBody(required = false) JoinRequest request) {
        ManualRoom room = roomManager.createRoom();
        ManualOccupant occupant = room.join(nameOf(request), ManualOccupantRole.PLAYER);
        return new JoinResponse(room.getRoomId(), occupant.getOccupantId(), occupant.getDisplayName());
    }

    /** 既存の部屋に入室する。occupantId はここで発行される。 */
    @PostMapping("/manual/api/rooms/{roomId}/occupants")
    @ResponseBody
    public JoinResponse join(@PathVariable String roomId,
            @RequestBody(required = false) JoinRequest request) {
        ManualRoom room = roomManager.requireRoom(roomId);
        synchronized (room.getLock()) {
            ManualOccupant occupant = room.join(nameOf(request), ManualOccupantRole.PLAYER);
            return new JoinResponse(room.getRoomId(), occupant.getOccupantId(), occupant.getDisplayName());
        }
    }

    /**
     * デッキ zip を席に読み込む。本文は zip のバイト列そのものである。
     * 検証違反は拒否せず、警告として返す(設計書 7-4)。
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
            gameService.loadDeck(room, seat, imported);
            ManualOccupant viewer = room.findOccupant(occupantId).orElse(null);
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
     * ログをテキストファイルとして書き出す(設計書 5-5)。
     *
     * ★WebSocket ではなく HTTP GET にした。ファイルダウンロードはブラウザの標準機能
     * (リンククリック → 保存ダイアログ)に任せるのが最も単純であり、STOMP 経由でバイト列を
     * 送り返してクライアント側で Blob を組み立てる必要が無い。
     * ログはこのモードの成果物であり(設計書 5-5)、古い行を捨てず全件書き出す。
     */
    @GetMapping("/manual/api/rooms/{roomId}/log")
    public ResponseEntity<byte[]> exportLog(@PathVariable String roomId) {
        ManualRoom room = roomManager.requireRoom(roomId);
        StringBuilder text = new StringBuilder();
        text.append("QTE Battle 手動モード ログ — 部屋 ").append(room.getRoomId()).append('\n');
        for (ManualLogEntry entry : room.getLog()) {
            text.append('[').append(LOG_TIME_FORMAT.format(entry.at())).append("] ")
                    .append(entry.text()).append('\n');
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        String filename = "qte-manual-log-%s-%s.txt".formatted(room.getRoomId(),
                LOG_TIME_FORMAT.format(Instant.now()).replace(" ", "_").replace(":", ""));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /** 入力エラーは 400 で理由だけ返す。画面側が一覧に出す。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<ErrorResponse> handleInvalidInput(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    private String nameOf(JoinRequest request) {
        return request == null ? null : request.displayName();
    }

    // ---- やり取りする型 ----

    public record JoinRequest(String displayName) {
    }

    public record JoinResponse(String roomId, String occupantId, String displayName) {
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
