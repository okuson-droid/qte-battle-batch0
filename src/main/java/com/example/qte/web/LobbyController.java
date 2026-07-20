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
import com.example.qte.deck.DeckValidator;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.PlayerSlot;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * ロビー(部屋の作成・入室)とデッキビルダー画面。
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
    private final ObjectMapper objectMapper;

    @GetMapping("/")
    public String lobby(Model model) {
        model.addAttribute("leaders", selectableLeaders());
        return "lobby";
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

    /** デッキビルダー画面 */
    @GetMapping("/deck-builder")
    public String deckBuilder() {
        return "deck-builder";
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

    private DeckDefinition parseDeck(String deckJson) {
        if (deckJson == null || deckJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(deckJson, DeckDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("デッキファイルの形式が正しくありません");
        }
    }

    private String redirectToBattle(String roomId, String playerId) {
        return "redirect:/rooms/" + roomId + "/play?playerId=" + playerId;
    }

    /** 選択可能なリーダー。メインデッキを用意済みの文明(水・火・闇・光)に限る */
    private List<CardMaster> selectableLeaders() {
        return java.util.stream.Stream.of(Civilization.WATER, Civilization.FIRE, Civilization.DARK, Civilization.LIGHT)
                .flatMap(civ -> cards.findByCivilization(civ).stream())
                .filter(c -> c.type() == CardType.LEADER)
                .toList();
    }
}
