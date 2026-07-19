package com.example.qte.web;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.PlayerSlot;

import lombok.RequiredArgsConstructor;

/**
 * ロビー(部屋の作成・入室)。ここは従来型のMVC + PRGパターンで作る。
 * 「ページを開くまで」はMVC、「開いた後の対戦」はWebSocket、という役割分担。
 *
 * playerIdはサーバが発行するUUIDで、URLのクエリパラメータで持ち回る。
 * 認証は作らない方針(仕様1-2)のため、このUUIDを知っていること自体を本人確認とする割り切り。
 */
@Controller
@RequiredArgsConstructor
public class LobbyController {

    private final GameRoomManager roomManager;
    private final CardMasterRepository cards;

    @GetMapping("/")
    public String lobby(Model model) {
        model.addAttribute("leaders", selectableLeaders());
        return "lobby";
    }

    /** 部屋を作成し、作成者をプレイヤー1として登録する */
    @PostMapping("/rooms")
    public String createRoom(@RequestParam String playerName, @RequestParam String leaderCardId,
            RedirectAttributes redirectAttributes) {
        GameRoom room = roomManager.createRoom();
        String playerId = registerPlayer(room, playerName, leaderCardId);
        return redirectToBattle(room.getRoomId(), playerId);
    }

    /** 部屋コードを指定して入室する(プレイヤー2) */
    @PostMapping("/rooms/join")
    public String joinRoom(@RequestParam String roomId, @RequestParam String playerName,
            @RequestParam String leaderCardId, RedirectAttributes redirectAttributes) {
        GameRoom room = roomManager.findRoom(roomId.trim())
                .orElseThrow(() -> new IllegalArgumentException("部屋が見つかりません: " + roomId));
        String playerId;
        synchronized (room.getLock()) {
            playerId = registerPlayer(room, playerName, leaderCardId);
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

    /** カードマスタ一覧(Batch 0の動作確認画面をこちらへ移設) */
    @GetMapping("/cards")
    public String cards(Model model) {
        var byCiv = new java.util.LinkedHashMap<Civilization, List<CardMaster>>();
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

    private String registerPlayer(GameRoom room, String playerName, String leaderCardId) {
        String playerId = UUID.randomUUID().toString();
        String name = playerName == null || playerName.isBlank() ? "名無しのデュエリスト" : playerName.trim();
        room.addSlot(new PlayerSlot(playerId, name, leaderCardId));
        return playerId;
    }

    private String redirectToBattle(String roomId, String playerId) {
        return "redirect:/rooms/" + roomId + "/play?playerId=" + playerId;
    }

    /** 選択可能なリーダー。メインデッキを用意済みの文明(水・火)に限る */
    private List<CardMaster> selectableLeaders() {
        return java.util.stream.Stream.of(Civilization.WATER, Civilization.FIRE)
                .flatMap(civ -> cards.findByCivilization(civ).stream())
                .filter(c -> c.type() == CardType.LEADER)
                .toList();
    }
}
