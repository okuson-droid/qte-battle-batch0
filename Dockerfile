# ============================================================
# マルチステージビルド:
#   ステージ1(build) = Maven入りの重いイメージでjarを作る
#   ステージ2(実行)  = JREだけの軽いイメージにjarだけを載せる
# ビルド道具(Maven・ソースコード)は本番イメージに含めない。
# 詳細は batch6-deploy-guide.md 2章を参照
# ============================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 依存関係の解決を先に行う: pom.xmlが変わらない限りこの層はキャッシュされ、
# ソースだけ変えた再デプロイのビルドが大幅に速くなる
COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
# テストはローカル(Eclipse)で実行済みの前提でスキップし、ビルド時間を短縮する
RUN mvn -q package -DskipTests

# ---- 実行ステージ ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/qte-battle-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
