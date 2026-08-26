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
 * <p>★★<b>Batch 66: {@link GameRoomManager} がようやくこのクラスを呼ぶようになった。</b>
 * 17b はここに「既存ファイルの変更が許されるバッチ(19a)で差し替える」と書き、
 * 19a は来たが差し替えは行われなかった —— <b>書いてあっても、実装は自分では動かない</b>。
 * 64(暫定は前提が消えても戻らない)・65(簡易版は本家が直っても直らない)と
 * 同じ性質の3例目である。文字集合も長さも同一だったので実害は出ていないが、
 * <b>実害が出ていないことは、同じものが2つあってよい理由にはならない</b>(設計判断28)。
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
