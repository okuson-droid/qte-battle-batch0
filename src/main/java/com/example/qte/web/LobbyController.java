package com.example.qte.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.qte.deck.DeckDefinition;
import com.example.qte.deck.DeckFileReader;
import com.example.qte.deck.DeckValidator;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.PlayerSlot;

import lombok.RequiredArgsConstructor;

/**
 * ロビー(部屋の作成・入室)とカード一覧の入口。
 * 「ページを開くまで」はMVC、「開いた後の対戦」はWebSocket、という役割分担。
 *
 * デッキはファイルとして持ち込まれる(アカウント・DBを持たない方針)。
 * ファイルの中身はクライアントのJSが読み取って隠しフィールドに載せ、
 * 通常のフォーム送信で届く。サーバは必ずDeckValidatorで検証してから受け付ける。
 */
@Controller
@RequiredArgsConstructor
public class LobbyController {

    private final GameRoomManager roomManager;
    private final CardMasterRepository cards;
    private final DeckValidator deckValidator;
    private final DeckFileReader deckFileReader;

    /**
     * ★Batch 19a: {@code /} は手動モードの新ロビーになった(設計書 6-2)。
     * このメソッドは通常モードのロビーそのものであり、内容(lobby.html)は無変更のまま
     * {@code /auto} へ移設しただけである。未完成の対戦システムはリンクからしか到達できない。
     */
    @GetMapping("/auto")
    public String lobby(Model model) {
        model.addAttribute("leaders", selectableLeaders());
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

    /** 部屋を作成し、作成者をプレイヤー1として登録する */
    @PostMapping("/rooms")
    public String createRoom(@RequestParam String playerName,
            @RequestParam(required = false) String leaderCardId,
            @RequestParam(required = false) String deckJson) {
        GameRoom room = roomManager.createRoom();
        String playerId = registerPlayer(room, playerName, leaderCardId, deckJson);
        return redirectToBattle(room.getRoomId(), playerId);
    }

    /** 部屋コードを指定して入室する(プレイヤー2) */
    @PostMapping("/rooms/join")
    public String joinRoom(@RequestParam String roomId, @RequestParam String playerName,
            @RequestParam(required = false) String leaderCardId,
            @RequestParam(required = false) String deckJson) {
        GameRoom room = roomManager.findRoom(roomId.trim())
                .orElseThrow(() -> new IllegalArgumentException("部屋が見つかりません: " + roomId));
        String playerId;
        synchronized (room.getLock()) {
            playerId = registerPlayer(room, playerName, leaderCardId, deckJson);
        }
        return redirectToBattle(room.getRoomId(), playerId);
    }

    /** 対戦画面。以降のやり取りはWebSocketに切り替わる */
    @GetMapping("/rooms/{roomId}/play")
    public String play(@PathVariable String roomId, @RequestParam String playerId, Model model) {
        GameRoom room = roomManager.findRoom(roomId)
                .orElseThrow(() -> new IllegalArgumentException("部屋が見つかりません: " + roomId));
        PlayerSlot slot = room.findSlot(playerId)
                .orElseThrow(() -> new IllegalArgumentException("この部屋に入室していません"));
        model.addAttribute("roomId", room.getRoomId());
        model.addAttribute("playerId", slot.getPlayerId());
        model.addAttribute("playerName", slot.getDisplayName());
        return "battle";
    }

    /*
     * ★★Batch 63: 通常モード用のデッキビルダー(/deck-builder)は退役した。
     *
     * デッキファイルの形式を taboo-elemental-deck に一本化した結果、この画面が書き出す
     * 形式は無くなった。カードの正も 46b 以降は両モードとも manual-cards.json であり、
     * 機能はデッキメーカー(/deck-maker)の下位互換になっていた
     * (マナカーブ・検証一覧・autosave・[元に戻す] が無い)。
     * 画面を2つ残すことは、同じ規則を2箇所へ書き続けることと同義である。
     * 一緒に退役したもの: deck-builder.html / deck-builder.js /
     * CardApiController(/api/cards・/api/implemented-civilizations。この画面専用だった)。
     */

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

    /** 入力エラー(部屋が見つからない・デッキ不正など)はロビーに戻して理由を表示する */
    @ExceptionHandler(IllegalArgumentException.class)
    public String handleInvalidInput(IllegalArgumentException e, Model model) {
        model.addAttribute("errorMessage", e.getMessage());
        model.addAttribute("leaders", selectableLeaders());
        return "lobby";
    }

    private String registerPlayer(GameRoom room, String playerName, String leaderCardId, String deckJson) {
        String playerId = UUID.randomUUID().toString();
        String name = playerName == null || playerName.isBlank() ? "名無しのデュエリスト" : playerName.trim();

        DeckDefinition deck = parseDeck(deckJson);
        String effectiveLeaderId;
        String deckName;
        if (deck != null) {
            // デッキファイルのリーダーが優先される(プルダウンの選択は無視)
            deckValidator.validate(deck);
            effectiveLeaderId = deck.leaderCardId();
            deckName = deck.name() == null || deck.name().isBlank() ? "読み込んだデッキ" : deck.name();
        } else {
            if (leaderCardId == null || leaderCardId.isBlank()) {
                throw new IllegalArgumentException("リーダーを選択するか、デッキファイルを読み込んでください");
            }
            effectiveLeaderId = leaderCardId;
            deckName = "おまかせ";
        }
        room.addSlot(new PlayerSlot(playerId, name, effectiveLeaderId, deck, deckName));
        return playerId;
    }

    /**
     * デッキファイルを読む。未選択(空)は「おまかせ」を意味するので null を返す。
     *
     * <p>★★Batch 63: 読み取りは {@link DeckFileReader} に移した。62 までは
     * {@code ObjectMapper.readValue} で {@link DeckDefinition} に直接流し込んでいたが、
     * それは<b>ファイルの形と内部表現が同じであることを前提にした書き方</b>であり、
     * デッキメーカーが書いた形式を読めない原因そのものだった。
     * <b>読めなかった理由をそのまま画面へ返す</b> —— 62 までは理由を握りつぶして
     * 「デッキファイルの形式が正しくありません」の一言にしていたので、
     * 何を直せばよいのかが分からなかった。
     */
    private DeckDefinition parseDeck(String deckJson) {
        if (deckJson == null || deckJson.isBlank()) {
            return null;
        }
        return deckFileReader.read(deckJson);
    }

    private String redirectToBattle(String roomId, String playerId) {
        return "redirect:/rooms/" + roomId + "/play?playerId=" + playerId;
    }

    /** 選択可能なリーダー。メインデッキを用意済みの文明(水・火・闇・光)に限る */
    /**
     * 選択画面に出すリーダー。効果を実装済みの文明のみを出す
     * (未実装文明のリーダーを選べると、デッキが組めても何も起きない対戦になるため)。
     * Batch 13c で全6文明がそろった。判定の重複を避けるため、実装済みの集合は
     * {@link DeckValidator#implementedCivilizations()} を唯一の正とする。
     */
    private List<CardMaster> selectableLeaders() {
        return DeckValidator.implementedCivilizations().stream()
                .flatMap(civ -> cards.findByCivilization(civ).stream())
                .filter(c -> c.type() == CardType.LEADER)
                .toList();
    }
}
