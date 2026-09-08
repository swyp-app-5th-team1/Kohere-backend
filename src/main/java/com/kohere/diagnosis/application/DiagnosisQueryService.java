package com.kohere.diagnosis.application;

import com.kohere.common.response.PageResponse;
import com.kohere.diagnosis.application.dto.DiagnosisResponse;
import com.kohere.diagnosis.application.dto.LatestDiagnosisResponse;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisCondition;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 확정된 진단을 읽는 조회 전용 서비스(이력·최근·상세). <b>쓰기 경로를 갖지 않는다</b> — 진단을 만들고 채우고 확정하는 것은 서버 주도 흐름({@link
 * DiagnosisFlowService})의 몫이고, 여기는 그 흐름이 {@code diagnoses}에 남긴 결과를 읽기만 한다.
 *
 * <p>그래서 협력자가 저장소 하나다. 문항 카탈로그·답 적용기·번역기·추천 리더는 조회에 쓰이지 않는다.
 *
 * <p><b>회원 전용이다</b> — {@code SecurityConfig}에 이 경로용 {@code permitAll} 매처를 두지 않아 비회원 요청이 여기까지 오지
 * 못한다. 게스트 진단({@code userId} 부재)은 사용자 id 기준 질의에 애초에 걸리지 않으므로 목록에서 자동으로 빠지고, 상세는 소유권 검사가 막는다.
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md §4~§6.
 */
@Service
@RequiredArgsConstructor
public class DiagnosisQueryService {

  /** 이력 정렬 허용 키(스펙 §4). */
  private static final Set<String> HISTORY_SORT_KEYS = Set.of("submittedAt");

  private final DiagnosisRepository diagnosisRepository;

  /** 내 완료 진단 이력(최신순, 오프셋 페이지). */
  public PageResponse<DiagnosisResponse> getHistory(long userId, int page, int size, String sort) {
    DiagnosisPageRequests.validatePage(page, size);
    DiagnosisPageRequests.validateSort(sort, HISTORY_SORT_KEYS);
    List<DiagnosisResponse> content =
        diagnosisRepository
            .findCompletedByUserId(userId, page, size, DiagnosisPageRequests.isAscending(sort))
            .stream()
            .map(DiagnosisQueryService::toResponse)
            .toList();
    long total = diagnosisRepository.countCompletedByUserId(userId);
    return PageResponse.of(content, DiagnosisPageRequests.pageInfo(page, size, total));
  }

  /** 최근 완료 진단 단건. 확정 진단이 없으면 {@code completed=false}이고 요약 필드는 모두 null이다(404가 아니다). */
  public LatestDiagnosisResponse getLatest(long userId) {
    return diagnosisRepository
        .findLatestCompletedByUserId(userId)
        .map(DiagnosisQueryService::toLatestResponse)
        .orElseGet(
            () ->
                new LatestDiagnosisResponse(
                    false, null, null, null, null, null, null, null, null, null, null));
  }

  /**
   * 진단 단건 상세(본인 소유 확정 진단만).
   *
   * <p>게이트 순서가 계약이다 — 상태(404)를 소유권(403)보다 <b>먼저</b> 본다({@link DiagnosisAccessGuard} 불변식 2).
   */
  public DiagnosisResponse getDetail(long userId, Long diagnosisId) {
    Diagnosis diagnosis =
        diagnosisRepository.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
    DiagnosisAccessGuard.requireCompleted(diagnosis);
    DiagnosisAccessGuard.requireOwner(diagnosis, userId, null);
    return toResponse(diagnosis);
  }

  private static DiagnosisResponse toResponse(Diagnosis d) {
    return new DiagnosisResponse(
        d.getId(),
        d.getRegion(),
        d.getPurpose(),
        d.getUniversity(),
        d.getDistrict(),
        conditionsList(d),
        // 확정 진단은 월세가 반드시 채워져 있어 정상 경로에서는 삼항의 오른쪽에 닿지 않는다.
        // 응답이 primitive int라 손상된 문서에서 언박싱 NPE(500)가 나는 것을 막는 방어다.
        d.getMonthlyRentMin() == null ? 0 : d.getMonthlyRentMin(),
        d.getMonthlyRentMax() == null ? 0 : d.getMonthlyRentMax(),
        d.getArcStatus(),
        d.getStatus(),
        d.getSubmittedAt());
  }

  private static LatestDiagnosisResponse toLatestResponse(Diagnosis d) {
    return new LatestDiagnosisResponse(
        true,
        d.getId(),
        d.getRegion(),
        d.getPurpose(),
        d.getUniversity(),
        d.getDistrict(),
        conditionsList(d),
        d.getMonthlyRentMin(),
        d.getMonthlyRentMax(),
        d.getArcStatus(),
        d.getSubmittedAt());
  }

  private static List<DiagnosisCondition> conditionsList(Diagnosis d) {
    return d.getConditions() == null ? List.of() : List.copyOf(d.getConditions());
  }
}
