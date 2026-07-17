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
 * 진단 문항 카탈로그 컬렉션({@code diagnosisQuestions}) 인덱스 멱등 생성(기동 시). Spring Boot 3.5(Spring Data MongoDB
 * 4.x)는 자동 인덱스 생성이 기본 비활성이라 부트스트랩에서 명시적으로 보장한다(migration-policy §8 — 인덱스=부트스트랩, 시드·데이터 진화만
 * Mongock).
 *
 * <p>{@code field} UNIQUE는 <b>문항 조회 키의 유일성</b>을 받친다(ADR-0036) — 카탈로그는 순서를 담지 않고 {@code field}로 문항을
 * 식별하므로, 중복이 생기면 어느 문항이 나갈지가 시드 순서에 좌우된다. 인덱스가 그걸 시드 시점에 시끄럽게 막는다. {@code test} 프로파일·Mongo 미가용 시는
 * 건너뛴다(기동을 막지 않는다).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class DiagnosisQuestionIndexInitializer implements ApplicationRunner {

  private final MongoOperations mongoOperations;

  @Override
  public void run(ApplicationArguments args) {
    try {
      mongoOperations
          .indexOps("diagnosisQuestions")
          .createIndex(
              new Index().on("field", Sort.Direction.ASC).unique().named("field_unique_idx"));
      log.info("Ensured diagnosisQuestions MongoDB index");
    } catch (RuntimeException e) {
      log.warn("진단 문항 인덱스 생성을 생략한다(Mongo 미가용 등): {}", e.getMessage());
    }
  }
}
