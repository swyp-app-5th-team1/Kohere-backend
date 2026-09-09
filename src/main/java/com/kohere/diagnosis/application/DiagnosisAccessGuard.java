package com.kohere.diagnosis.application;

import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisAccessDeniedException;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisStatus;

/**
 * 진단 단건 접근 게이트. 조회 경로들이 상태·소유권 판정을 각자 사본으로 들고 있으면 한쪽만 고쳐져 다른 경로가 뚫리므로 한 벌로 모은다.
 *
 * <p><b>불변식 1 — 소유권 wrapper를 새로 만들지 않는다.</b> 판정의 정본은 {@link Diagnosis#isOwnedBy(Long, String)} 하나이며
 * 여기서는 그것을 예외로 옮기기만 한다. 진단 id가 전역 순차 채번이라 열거가 쉽고, 게스트 경로가 열린 뒤로는 이 검사가 유일한 IDOR 방어선이다(#181).
 *
 * <p><b>불변식 2 — 상태 게이트(404)를 소유권(403)보다 먼저 호출한다.</b> 뒤집으면 타인의 폐기·미확정 진단이 404가 아니라 403이 되어, 순차 id 위에
 * "그 id에 진단이 있다"는 신호가 새로 생긴다. 정순에서 403이 뜻하는 것은 "타인의 <b>확정</b> 진단"으로 좁혀진다.
 */
final class DiagnosisAccessGuard {

  private DiagnosisAccessGuard() {}

  /**
   * 확정({@code COMPLETED}) 진단만 통과시킨다. 폐기 기록도 미완주 초안도 여기서 함께 막힌다.
   *
   * <p>폐기 기록은 소유권만으로 막히지 않는다 — 본인 것이고 id가 순차 발급이라 추측 가능하다. 수요 분석용 내부 기록이라 노출 경로를 두지 않는다.
   *
   * <p>미완주 초안은 조건이 비어 있어 그대로 매칭에 쓰면 "조건 없는 전체 매물"로 붕괴한다.
   */
  static void requireCompleted(Diagnosis diagnosis) {
    if (diagnosis.getStatus() != DiagnosisStatus.COMPLETED) {
      throw new DiagnosisNotFoundException();
    }
  }

  /**
   * 본인 소유가 아니면 거절한다.
   *
   * @param userId 회원이면 userId, 게스트면 {@code null}
   * @param guestSessionId 게스트 세션 키(회원은 {@code null})
   */
  static void requireOwner(Diagnosis diagnosis, Long userId, String guestSessionId) {
    if (!diagnosis.isOwnedBy(userId, guestSessionId)) {
      throw new DiagnosisAccessDeniedException();
    }
  }
}
