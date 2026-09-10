# 作業台(タスクと記憶)

本ディレクトリは QTE 対戦アプリの作業状態を保つための一式である。
ゲームの実装には一切関与しない。Java からも `verify` からも参照されない。

| ファイル | 役割 |
|---|---|
| `TASKS.md` | 進行中・待ち・いつか・完了のタスク一覧 |
| `CLAUDE.md` | 作業記憶のホットキャッシュ。人・用語・現在地を約60行に畳んだもの |
| `memory/glossary.md` | 現場語の解読表 |
| `memory/people/okuson.md` | 発注者の進め方と好み |
| `memory/projects/qte-battle.md` | プロジェクトの現在値と Batch 84 の決定 |
| `memory/context/company.md` | 技術スタック・道具・毎バッチの手順 |
| `dashboard.html` | `TASKS.md` をブラウザで読み書きするための単一ファイル |

## 使い方

1. ブラウザで `dashboard.html` を開く。
2. 「TASKS.md を開く」から同じディレクトリの `TASKS.md` を選ぶ。
3. チェックを付けると、選んだファイルへそのまま書き戻す
   (File System Access API に対応しないブラウザではダウンロードになる)。

`TASKS.md` は素の Markdown であり、エディタで直接編集してもよい。
書式は `- [ ] **見出し** - 文脈` とし、子項目は2字下げの `- ` で書く。

## 正はどこにあるか

本ディレクトリは作業の**控え**であって、正ではない。

| 内容 | 正 |
|---|---|
| 裁定 | `notes/qte-rulings.md` |
| プロジェクト定義・設計判断 | `qte-project-reference.md` |
| 直近バッチの要点 | `claude/qte-handoff-vNN.md` |
| そのバッチの設計 | `notes/batchNN-design-notes.md` |

食い違ったときは上表を優先し、本ディレクトリを直す。

★引き継ぎ書は「そのバッチ時点の正」であり、**最新の文書とは限らない** ——
あとから書かれた設計書のほうが新しいことがある。

## 更新のしかた

バッチの終わりに、他の変更と同じ push へ載せる。
更新の手順は Claude の `/productivity:update` が行う。
