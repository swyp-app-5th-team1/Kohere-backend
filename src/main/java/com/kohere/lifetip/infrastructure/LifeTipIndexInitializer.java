package com.kohere.lifetip.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * 생활 팁 컬렉션 인덱스 멱등 생성(기동 시). Spring Boot 3.5(Spring Data MongoDB 4.x)는 자동 인덱스 생성이 기본 비활성이라 도큐먼트의
 * {@code @CompoundIndex} 선언만으로는 인덱스가 만들어지지 않으므로 부트스트랩에서 명시적으로 보장한다(migration-policy §8 — 멱등 생성).
 *
 * <p>{@code lifeTipTopics(order)}는 주제 목록 정렬 조회, {@code lifeTips(topicCode, order)}는 주제별 팁 정렬 조회를
 * 받친다. {@code test} 프로파일·Mongo 미가용 시는 건너뛴다(기동을 막지 않는다).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class LifeTipIndexInitializer implements ApplicationRunner {

  private final MongoOperations mongoOperations;

  @Override
  public void run(ApplicationArguments args) {
    try {
      mongoOperations
          .indexOps("lifeTipTopics")
          .createIndex(new Index().on("order", Sort.Direction.ASC).named("order_idx"));
      mongoOperations
          .indexOps("lifeTips")
          .createIndex(
              new CompoundIndexDefinition(new Document("topicCode", 1).append("order", 1))
                  .named("topicCode_order_idx"));
      log.info("Ensured lifetip MongoDB indexes");
    } catch (RuntimeException e) {
      log.warn("생활 팁 인덱스 생성을 생략한다(Mongo 미가용 등): {}", e.getMessage());
    }
  }
}
