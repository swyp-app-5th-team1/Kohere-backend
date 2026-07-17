package com.kohere.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 진단 6단계 정본 순서 enum 단위 테스트(ADR-0036). 순서는 선언 순서가 정본이고 {@code step}은 그 위치에서 파생하므로, 이 두 사실이 어긋나지 않는지와
 * <b>v1 공개 계약</b>({@code GET /api/v1/diagnoses/questions/{step}}의 번호↔문항)을 고정한다.
 */
class DiagnosisFlowStepTest {

  @Test
  @DisplayName("v1 단계 번호 1~6은 각 문항에 고정된다(공개 계약 — 선언 순서를 바꾸면 여기서 잡힌다)")
  void v1StepNumbersArePinned() {
    // 이 번호는 앱이 GET /questions/{step}으로 보내는 값이자 스펙 §1의 ①~⑥이다. 순서를 바꾸면
    // step()이 따라 움직여 v1 계약이 조용히 바뀌므로, 그 변경을 의식적으로 하게 만든다.
    assertThat(DiagnosisFlowStep.ofStep(1).field()).isEqualTo("region");
    assertThat(DiagnosisFlowStep.ofStep(2).field()).isEqualTo("purpose");
    assertThat(DiagnosisFlowStep.ofStep(3))
        .isEqualTo(DiagnosisFlowStep.UNIVERSITY_OR_DISTRICT); // university|district
    assertThat(DiagnosisFlowStep.ofStep(4).field()).isEqualTo("conditions");
    assertThat(DiagnosisFlowStep.ofStep(5).field()).isEqualTo("monthlyRent");
    assertThat(DiagnosisFlowStep.ofStep(6).field()).isEqualTo("arcStatus");
  }

  @Test
  @DisplayName("step()과 ofStep()은 서로의 역이고 범위를 벗어나면 거부한다")
  void stepRoundTrips() {
    for (DiagnosisFlowStep slot : DiagnosisFlowStep.values()) {
      assertThat(DiagnosisFlowStep.ofStep(slot.step())).isEqualTo(slot);
    }
    assertThatThrownBy(() -> DiagnosisFlowStep.ofStep(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DiagnosisFlowStep.ofStep(7))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("next()가 선언 순서를 따라가고 마지막 슬롯 뒤는 없다(= 빌더 완성)")
  void nextFollowsDeclarationOrder() {
    assertThat(DiagnosisFlowStep.REGION.next()).isEqualTo(DiagnosisFlowStep.PURPOSE);
    assertThat(DiagnosisFlowStep.PURPOSE.next())
        .isEqualTo(DiagnosisFlowStep.UNIVERSITY_OR_DISTRICT);
    assertThat(DiagnosisFlowStep.UNIVERSITY_OR_DISTRICT.next())
        .isEqualTo(DiagnosisFlowStep.CONDITIONS);
    assertThat(DiagnosisFlowStep.CONDITIONS.next()).isEqualTo(DiagnosisFlowStep.MONTHLY_RENT);
    assertThat(DiagnosisFlowStep.MONTHLY_RENT.next()).isEqualTo(DiagnosisFlowStep.ARC_STATUS);
    assertThat(DiagnosisFlowStep.ARC_STATUS.next()).isNull(); // 마지막 → 자동 확정
  }

  @Test
  @DisplayName("ofField는 university·district를 둘 다 UNIVERSITY_OR_DISTRICT로 되돌린다(③ 분기는 한 슬롯)")
  void ofFieldMapsBothBranchFields() {
    assertThat(DiagnosisFlowStep.ofField("university"))
        .isEqualTo(DiagnosisFlowStep.UNIVERSITY_OR_DISTRICT);
    assertThat(DiagnosisFlowStep.ofField("district"))
        .isEqualTo(DiagnosisFlowStep.UNIVERSITY_OR_DISTRICT);
    assertThat(DiagnosisFlowStep.UNIVERSITY_OR_DISTRICT.field())
        .isNull(); // 저장된 purpose로 택일해야 하므로 단정 불가
  }

  @Test
  @DisplayName("정본 6슬롯에 없는 흐름 제어 field(regionRetry)는 슬롯이 없다")
  void ofFieldRejectsFlowControlField() {
    // regionRetry는 카탈로그 문항이지만 진단 답이 아니라 흐름 제어 응답이라 순서에 끼지 않는다.
    assertThatThrownBy(() -> DiagnosisFlowStep.ofField("regionRetry"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
