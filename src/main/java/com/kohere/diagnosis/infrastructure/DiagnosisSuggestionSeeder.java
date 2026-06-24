package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.infrastructure.DiagnosisSuggestionDocument.ActionSpec;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 추천 조정 제안 카탈로그({@code diagnosisSuggestions}) 초기 시드. 컬렉션이 비어 있으면 {@code NO_MATCH} 제안(사유 메시지 + 액션
 * 3종)을 한국어·영어 라벨로 적재한다(미지원 언어는 영어 폴백, ADR-0029). 표시 라벨은 운영에서 보강한다.
 *
 * <p>{@code test}를 제외한 모든 프로파일(local·dev·prod)에서 동작한다 — 컬렉션이 비어 있을 때만 적재하는 멱등 동작이라 이미 큐레이션된 카탈로그가
 * 있으면 덮어쓰지 않는다. 시드 실패는 경고만 남기고 무시한다.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class DiagnosisSuggestionSeeder implements ApplicationRunner {

  private final DiagnosisSuggestionMongoRepository repository;

  @Override
  public void run(ApplicationArguments args) {
    try {
      if (repository.count() > 0) {
        return;
      }
      repository.save(noMatch());
      log.info("Seeded diagnosisSuggestions catalog (NO_MATCH)");
    } catch (RuntimeException e) {
      log.warn("diagnosisSuggestions 시드를 생략한다(Mongo 미가용 등): {}", e.getMessage());
    }
  }

  private static DiagnosisSuggestionDocument noMatch() {
    return DiagnosisSuggestionDocument.builder()
        .id("NO_MATCH")
        .message(
            Map.of(
                "en", "No listings matched your criteria. Try adjusting the options below.",
                "ko", "조건에 맞는 매물이 없습니다. 아래 항목을 조정해 보세요."))
        .actions(
            List.of(
                action("RELAX_REGION", "Widen the region.", "지역 범위를 넓혀 보세요."),
                action("RELAX_CONDITIONS", "Reduce housing conditions.", "주거 조건을 줄여 보세요."),
                action("INCREASE_BUDGET", "Increase the monthly budget.", "월 예산을 높여 보세요.")))
        .build();
  }

  private static ActionSpec action(String type, String en, String ko) {
    return ActionSpec.builder().type(type).detail(Map.of("en", en, "ko", ko)).build();
  }
}
