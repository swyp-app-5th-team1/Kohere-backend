package com.kohere.diagnosis.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * v2 진행 세션 컬렉션({@code diagnosisFlowSessions}) 인덱스 멱등 생성(기동 시). Spring Boot 3.5(Spring Data MongoDB
 * 4.x)는 자동 인덱스 생성이 기본 비활성이라 {@code @Indexed} 선언만으로는 인덱스가 만들어지지 않으므로 부트스트랩에서 명시적으로
 * 보장한다(migration-policy §8 — 멱등 생성).
 *
 * <p>{@code userId} UNIQUE 인덱스는 "사용자당 1 세션"을 받친다. {@code test} 프로파일·Mongo 미가용 시는 건너뛴다(기동을 막지 않는다).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class DiagnosisFlowSessionIndexInitializer implements ApplicationRunner {

  private final MongoOperations mongoOperations;

  @Override
  public void run(ApplicationArguments args) {
    try {
      mongoOperations
          .indexOps("diagnosisFlowSessions")
          .createIndex(
              new Index().on("userId", Sort.Direction.ASC).unique().named("userId_unique_idx"));
      log.info("Ensured diagnosisFlowSessions MongoDB index");
    } catch (RuntimeException e) {
      log.warn("진단 흐름 세션 인덱스 생성을 생략한다(Mongo 미가용 등): {}", e.getMessage());
    }
  }
}
