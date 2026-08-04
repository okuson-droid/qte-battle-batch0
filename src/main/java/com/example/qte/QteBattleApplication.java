package com.example.qte;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ★Batch 19a で {@code @EnableScheduling} を追加した。手動モードの切断復帰(設計書 6-3)が、
 * 5分の猶予切れを検知するために {@code ManualCleanupScheduler} を必要とするためである。
 * 通常モードはスケジュール処理を持たないため、この変更による影響は無い。
 */
@SpringBootApplication
@EnableScheduling
public class QteBattleApplication {

    public static void main(String[] args) {
        SpringApplication.run(QteBattleApplication.class, args);
    }
}
