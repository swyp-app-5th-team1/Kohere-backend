package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.OptionSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.SelectSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisSuggestionDocument.ActionSpec;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * 진단 카탈로그 캐노니컬 베이스라인(Flyway V1 "from-scratch"에 대응하는 Mongock init changeUnit, order 0000). 레퍼런스 카탈로그
 * 컬렉션 {@code diagnosisQuestions}(6단계, ③은 university/district 2건)·{@code
 * diagnosisSuggestions}(NO_MATCH)를 **비우고 캐노니컬 시드를 새로 적재**한다 — Mongock changelog로 환경당 정확히 1회. 멱등
 * {@code ApplicationRunner} 시더를 대체해 카탈로그 생애주기를 Mongock 단일 메커니즘으로 통합한다(ADR-0032 Decision 4). 이후
 * 구조·데이터 진화는 order 0001+ changeUnit(예: {@link DiagnosisGroupAndRentRangeChangeUnit}).
 *
 * <p>비우는 대상은 **서버 소유 레퍼런스 카탈로그뿐**이며, 사용자 데이터({@code diagnoses})는 절대 건드리지 않는다(그 백필은 0001).
 * 콘텐츠(라벨·번역) 정본은 ADR-0028·ADR-0029. test 프로파일은 {@code mongock.enabled=false}라 실행되지 않는다(테스트는 자체 시드).
 */
@ChangeUnit(id = "diagnosis-catalog-seed", order = "0000", author = "kohere")
public class DiagnosisCatalogSeedChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    // version 0 = 캐노니컬 베이스라인(Flyway V1 from-scratch 대응): 레퍼런스 카탈로그 컬렉션을 비우고 시드를 새로
    // 적재한다. 사용자 데이터(diagnoses)는 절대 건드리지 않는다 — 기존 진단 백필은 order 0001 changeUnit 책임.
    mongo.remove(new Query(), DiagnosisQuestionDocument.class);
    mongo.insertAll(questionCatalog());
    mongo.remove(new Query(), DiagnosisSuggestionDocument.class);
    mongo.save(noMatchSuggestion());
  }

  private static List<DiagnosisQuestionDocument> questionCatalog() {
    return List.of(
        doc(
            1,
            "region",
            select("SINGLE", 1),
            q("어느 지역에서 거주할 예정인가요?", "Which region will you live in?"),
            List.of(
                opt("SEOUL", "서울", "Seoul"),
                opt("BUSAN", "부산", "Busan"),
                opt("GYEONGGI", "경기", "Gyeonggi"))),
        doc(
            2,
            "purpose",
            select("SINGLE", 1),
            q("입국 목적이 무엇인가요?", "What is your purpose of stay?"),
            List.of(opt("STUDY", "유학(학업)", "Study"), opt("NON_STUDY", "그 외", "Other"))),
        doc(
            3,
            "university",
            select("SINGLE", 1),
            q("어느 대학교에 다니나요?", "Which university do you attend?"),
            List.of(
                opt("HUFS_KHU_KOREA", "한국외대·경희대·고려대", "HUFS · Kyung Hee · Korea Univ."),
                opt("SKKU_SUNGSHIN", "성균관대·성신여대", "Sungkyunkwan · Sungshin Women's"),
                opt("SNU_CAU_SOONGSIL", "서울대·중앙대·숭실대", "Seoul National · Chung-Ang · Soongsil"),
                opt("HONGIK_YONSEI_EWHA", "홍익대·연세대·이화여대", "Hongik · Yonsei · Ewha Womans"),
                opt("KONKUK_SEJONG_HYU", "건국대·세종대·한양대", "Konkuk · Sejong · Hanyang"),
                opt("ETC", "기타", "Other"))),
        doc(
            3,
            "district",
            select("SINGLE", 1),
            q("어느 지역(구)에서 거주할 예정인가요?", "Which district will you live in?"),
            List.of(
                opt("GURO_GU", "구로구", "Guro-gu"),
                opt("YEONGDEUNGPO_GU", "영등포구", "Yeongdeungpo-gu"),
                opt("GEUMCHEON_GU", "금천구", "Geumcheon-gu"),
                opt("GWANAK_GU", "관악구", "Gwanak-gu"),
                opt("DONGDAEMUN_GU", "동대문구", "Dongdaemun-gu"),
                opt("ETC", "기타", "Other"))),
        doc(
            4,
            "conditions",
            select("MULTI", 3),
            q("원하는 주거 조건을 선택하세요(최대 3개).", "Select your housing conditions (up to 3)."),
            List.of(
                opt("IMMEDIATE_MOVE_IN", "즉시 입주", "Immediate move-in"),
                opt("FEMALE_ONLY", "여성 전용", "Female only"),
                opt("PRIVATE_BATH", "개인 욕실", "Private bath"),
                opt("ENGLISH_AVAILABLE", "영어 가능", "English available"),
                opt("RESIDENT_REGISTRATION", "전입신고 가능", "Resident registration"),
                opt("NO_MAINTENANCE_FEE", "관리비 없음", "No maintenance fee"),
                opt("MEALS_PROVIDED", "식사 제공", "Meals provided"),
                opt("DOUBLE_ROOM", "2인실", "Double room"))),
        doc(
            5,
            "monthlyRent",
            select("NUMBER_RANGE", 1),
            q("월세 범위(최소~최대)는 얼마인가요?(원)", "What is your monthly rent range (min–max)? (KRW)"),
            List.of()),
        doc(
            6,
            "arcStatus",
            select("SINGLE", 1),
            q("외국인등록증(ARC) 발급 상태는 어떤가요?", "What is your ARC issuance status?"),
            List.of(opt("ARC_ISSUED", "발급 완료", "Issued"), opt("ARC_PENDING", "발급 예정", "Pending"))));
  }

  private static DiagnosisSuggestionDocument noMatchSuggestion() {
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

  private static DiagnosisQuestionDocument doc(
      int step,
      String field,
      SelectSpec select,
      Map<String, String> question,
      List<OptionSpec> options) {
    return DiagnosisQuestionDocument.builder()
        .step(step)
        .field(field)
        .question(question)
        .select(select)
        .options(options)
        .active(true)
        .build();
  }

  private static SelectSpec select(String type, int max) {
    return SelectSpec.builder().type(type).max(max).build();
  }

  private static Map<String, String> q(String ko, String en) {
    return Map.of("ko", ko, "en", en);
  }

  private static OptionSpec opt(String code, String ko, String en) {
    return OptionSpec.builder().code(code).label(Map.of("ko", ko, "en", en)).build();
  }

  private static ActionSpec action(String type, String en, String ko) {
    return ActionSpec.builder().type(type).detail(Map.of("en", en, "ko", ko)).build();
  }

  /** forward-only: 시드는 되돌리지 않는다(Mongock이 메서드 존재만 요구). */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
