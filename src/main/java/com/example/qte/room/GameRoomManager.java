package com.example.qte.room;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 全対戦部屋の台帳。アプリ内で唯一のsingletonビーン。
 *
 * ここが「ゲーム状態をメモリに持つ」の物理的な置き場所である:
 * singletonビーンのフィールド(ConcurrentHashMap)に可変データをぶら下げる。
 * EM-Springで学んだ「singletonビーンはステートレスに」の原則の、意図的な例外。
 * なぜ例外が許されるか・どう安全にするかは batch0-design-notes.md 2章を参照。
 */
@Component
public class GameRoomManager {

    private static final String ROOM_ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 紛らわしい文字(I,O,0,1)を除外
    private static final int ROOM_ID_LENGTH = 6;

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public GameRoom createRoom() {
        // 万一の衝突時は生成し直す。putIfAbsentで「確認と登録」を原子的に行う
        while (true) {
            String roomId = generateRoomId();
            GameRoom room = new GameRoom(roomId);
            if (rooms.putIfAbsent(roomId, room) == null) {
                return room;
            }
        }
    }

    public Optional<GameRoom> findRoom(String roomId) {
        return Optional.ofNullable(rooms.get(roomId.toUpperCase()));
    }

    public void removeRoom(String roomId) {
        rooms.remove(roomId);
    }

    public int roomCount() {
        return rooms.size();
    }

    private String generateRoomId() {
        StringBuilder sb = new StringBuilder(ROOM_ID_LENGTH);
        for (int i = 0; i < ROOM_ID_LENGTH; i++) {
            sb.append(ROOM_ID_CHARS.charAt(random.nextInt(ROOM_ID_CHARS.length())));
        }
        return sb.toString();
    }
}
