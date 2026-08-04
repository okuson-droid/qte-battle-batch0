package com.example.qte.room;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 部屋IDの生成と正規化。通常モードと手動モードで共用する(設計書 2-1)。
 *
 * 部屋IDは「口頭で伝えられること」を要件とするため、紛らわしい文字(I・O・0・1)を
 * 除いた32文字から6文字を引く。32^6 ≒ 10億通りであり、同時に存在する部屋数が
 * 高々数十であることを考えれば衝突はまず起きない。
 * それでも {@code putIfAbsent} で登録の原子性を確保するのは、
 * 「起きないはずのこと」の扱いをコードに書き残しておくためである。
 *
 * ★このクラスは新規である。{@link GameRoomManager} は同じロジックを
 * private メソッドとして持ったままであり、まだこのクラスを呼んでいない。
 * Batch 17b は既存ファイルを1行も変更しない制約下にあるためで、
 * 既存ファイルの変更が許されるバッチ(19a)で差し替える。
 * 現状は2つの実装が並立するが、文字集合・長さともに同一であることを
 * 目視で保証しており、両者のIDが衝突しても管理台帳が別のため実害はない。
 */
public final class RoomIds {

    /** 紛らわしい文字(I・O・0・1)を除外した32文字 */
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private RoomIds() {
    }

    /** 新しい部屋IDを1つ引く。呼び出し側は台帳への登録が原子的であることを保証すること。 */
    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 利用者が入力した部屋IDを台帳の鍵の形に揃える。
     * 前後の空白を落とし、大文字に寄せる(IDに小文字は使っていない)。
     */
    public static String normalize(String roomId) {
        if (roomId == null) {
            return null;
        }
        return roomId.trim().toUpperCase(Locale.ROOT);
    }
}
