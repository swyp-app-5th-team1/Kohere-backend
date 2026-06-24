package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.OptionSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.SelectSpec;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 진단 문항 카탈로그({@code diagnosisQuestions}) 초기 시드. 컬렉션이 비어 있으면 6단계 문항(단계 3은 university·district 2건)을
 * 한국어·영어 라벨로 적재한다(미지원 언어는 영어 폴백, ADR-0029). 표시 라벨은 운영에서 보강한다.
 *
 * <p>{@code test}를 제외한 모든 프로파일(local·dev·prod)에서 동작한다 — 컬렉션이 비어 있을 때만 적재하는 멱등 동작이라 이미 큐레이션된 카탈로그가
 * 있으면 덮어쓰지 않는다. test는 (Mongo 컨테이너가 없어) 컨텍스트 기동을 막지 않기 위해 제외한다. 시드 실패는 경고만 남기고 무시한다.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class DiagnosisQuestionSeeder implements ApplicationRunner {

  private final DiagnosisQuestionMongoRepository repository;

  @Override
  public void run(ApplicationArguments args) {
    try {
      if (repository.count() > 0) {
        return;
      }
      repository.saveAll(catalog());
      log.info("Seeded diagnosisQuestions catalog (6 steps)");
    } catch (RuntimeException e) {
      log.warn("diagnosisQuestions 시드를 생략한다(Mongo 미가용 등): {}", e.getMessage());
    }
  }

  private static List<DiagnosisQuestionDocument> catalog() {
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
                opt("SNU", "서울대학교", "Seoul National University"),
                opt("CAU", "중앙대학교", "Chung-Ang University"),
                opt("SOONGSIL", "숭실대학교", "Soongsil University"),
                opt("HUFS", "한국외국어대학교", "Hankuk University of Foreign Studies"),
                opt("KHU", "경희대학교", "Kyung Hee University"),
                opt("KOREA", "고려대학교", "Korea University"),
                opt("SKKU", "성균관대학교", "Sungkyunkwan University"),
                opt("SUNGSHIN", "성신여자대학교", "Sungshin Women's University"),
                opt("KONKUK", "건국대학교", "Konkuk University"),
                opt("SEJONG", "세종대학교", "Sejong University"),
                opt("HYU", "한양대학교", "Hanyang University"),
                opt("HONGIK", "홍익대학교", "Hongik University"),
                opt("YONSEI", "연세대학교", "Yonsei University"),
                opt("EWHA", "이화여자대학교", "Ewha Womans University"),
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
                opt("PRIVATE_TOILET", "개인 화장실", "Private toilet"),
                opt("PRIVATE_BATH", "개인 욕실", "Private bath"),
                opt("ENGLISH_AVAILABLE", "영어 가능", "English available"),
                opt("RESIDENT_REGISTRATION", "전입신고 가능", "Resident registration"),
                opt("NO_MAINTENANCE_FEE", "관리비 없음", "No maintenance fee"),
                opt("MEALS_PROVIDED", "식사 제공", "Meals provided"),
                opt("DOUBLE_ROOM", "2인실", "Double room"))),
        doc(
            5,
            "monthlyBudgetMax",
            select("NUMBER", 1),
            q("월 예산 상한은 얼마인가요?(원)", "What is your maximum monthly budget? (KRW)"),
            List.of()),
        doc(
            6,
            "arcStatus",
            select("SINGLE", 1),
            q("외국인등록증(ARC) 발급 상태는 어떤가요?", "What is your ARC issuance status?"),
            List.of(opt("ARC_ISSUED", "발급 완료", "Issued"), opt("ARC_PENDING", "발급 예정", "Pending"))));
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
}
