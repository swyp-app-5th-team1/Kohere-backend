package com.kohere.gamification.infrastructure;

import com.kohere.gamification.infrastructure.QuizDocument.ChoiceSpec;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * 학습 퀴즈 카탈로그 캐노니컬 베이스라인(Mongock changeUnit). 레퍼런스 카탈로그 컬렉션 {@code quizzes}를 비우고 캐노니컬 시드를 새로 적재한다 —
 * Mongock changelog로 환경당 정확히 1회(ADR-0032·ADR-0035). 표시 문자열(문항·보기·해설)은 언어-키 맵으로 임베드하며 보기 키 A~D는 언어
 * 무관 채점 키다(ADR-0029).
 *
 * <p>비우는 대상은 서버 소유 레퍼런스 카탈로그뿐이며 사용자 데이터는 없다(무상태 — 제출/포인트 미저장). {@code test} 프로파일은 {@code
 * mongock.enabled=false}라 실행되지 않는다(테스트는 자체 시드).
 */
@ChangeUnit(id = "quiz-catalog-seed", order = "0002", author = "kohere")
public class QuizCatalogSeedChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    mongo.remove(new Query(), QuizDocument.class);
    mongo.insertAll(quizCatalog());
  }

  private static List<QuizDocument> quizCatalog() {
    return List.of(
        quiz(
            4001L,
            "A",
            t(
                "전세 계약 시 임차인의 대항력·우선변제권을 확보해 주는 제도는?",
                "Which system secures a tenant's opposing power and priority reimbursement in a jeonse contract?"),
            List.of(
                choice("A", t("확정일자", "Fixed date (확정일자)")),
                choice("B", t("관리비 정산", "Maintenance fee settlement")),
                choice("C", t("중도금 대출", "Interim payment loan")),
                choice("D", t("재산세 납부", "Property tax payment"))),
            t(
                "주민센터에서 확정일자를 받으면 대항력과 우선변제권을 확보해 보증금을 보호받을 수 있습니다.",
                "Getting a fixed date at the community center secures opposing power and priority"
                    + " reimbursement to protect your deposit.")),
        quiz(
            4002L,
            "B",
            t(
                "이사한 뒤 전입신고는 원칙적으로 언제까지 해야 하나요?",
                "By when must you file a move-in report after moving?"),
            List.of(
                choice("A", t("이사 후 30일 이내", "Within 30 days after moving")),
                choice("B", t("이사 후 14일 이내", "Within 14 days after moving")),
                choice("C", t("이사 후 3개월 이내", "Within 3 months after moving")),
                choice("D", t("신고 의무 없음", "No obligation to report"))),
            t(
                "전입신고는 이사한 날부터 14일 이내에 해야 하며, 확정일자와 함께 보증금 보호의 기본입니다.",
                "A move-in report must be filed within 14 days of moving; with a fixed date it is the"
                    + " basis of deposit protection.")),
        quiz(
            4003L,
            "C",
            t(
                "월세 계약에서 임대인이 보증금에서 함부로 공제할 수 없는 것은?",
                "In a monthly-rent contract, what can the landlord NOT arbitrarily deduct from the"
                    + " deposit?"),
            List.of(
                choice("A", t("밀린 월세", "Overdue rent")),
                choice("B", t("임차인 과실로 인한 수리비", "Repair costs from tenant negligence")),
                choice("C", t("자연 노후로 인한 벽지 교체비", "Wallpaper replacement due to natural aging")),
                choice("D", t("미납 공과금", "Unpaid utility bills"))),
            t(
                "통상적 사용에 따른 자연 노후·감가는 임대인 부담이 원칙이라 보증금에서 공제할 수 없습니다.",
                "Natural wear and aging from ordinary use are the landlord's responsibility, so they"
                    + " cannot be deducted from the deposit.")),
        quiz(
            4004L,
            "A",
            t(
                "전세보증금 반환보증(보증보험)에 가입하면 좋은 이유는?",
                "Why is it good to get jeonse deposit return insurance (guarantee)?"),
            List.of(
                choice(
                    "A",
                    t(
                        "임대인이 보증금을 못 돌려줘도 기관이 대신 반환",
                        "The agency returns the deposit if the landlord cannot")),
                choice("B", t("월세를 면제받을 수 있어서", "You are exempted from monthly rent")),
                choice("C", t("관리비가 무료가 되어서", "Maintenance fees become free")),
                choice("D", t("계약 기간이 자동 연장되어서", "The contract term is auto-extended"))),
            t(
                "반환보증은 임대인이 보증금을 돌려주지 못할 때 보증기관이 대신 지급해 임차인의 보증금을 보호합니다.",
                "The return guarantee protects the tenant's deposit by having the guarantee agency pay"
                    + " it when the landlord cannot.")),
        quiz(
            4005L,
            "D",
            t(
                "계약 전 등기부등본에서 반드시 확인해야 하는 것은?",
                "What must you check on the property register before signing?"),
            List.of(
                choice("A", t("건물의 색상", "The building's color")),
                choice("B", t("주변 맛집 정보", "Nearby restaurant info")),
                choice("C", t("임대인의 나이", "The landlord's age")),
                choice("D", t("근저당권 등 선순위 권리관계", "Prior claims such as mortgages (근저당권)"))),
            t(
                "근저당권 등 선순위 권리가 많으면 경매 시 보증금을 못 받을 수 있으므로 등기부등본으로 권리관계를 꼭 확인해야 합니다.",
                "Many prior claims like mortgages can jeopardize your deposit at auction, so always"
                    + " verify the rights on the property register.")));
  }

  private static QuizDocument quiz(
      Long id,
      String correctChoice,
      Map<String, String> question,
      List<ChoiceSpec> choices,
      Map<String, String> explanation) {
    return QuizDocument.builder()
        .id(id)
        .question(question)
        .choices(choices)
        .correctChoice(correctChoice)
        .explanation(explanation)
        .active(true)
        .build();
  }

  private static ChoiceSpec choice(String key, Map<String, String> text) {
    return ChoiceSpec.builder().key(key).text(text).build();
  }

  private static Map<String, String> t(String ko, String en) {
    return Map.of("ko", ko, "en", en);
  }

  /** forward-only: 시드는 되돌리지 않는다(Mongock이 메서드 존재만 요구). */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
