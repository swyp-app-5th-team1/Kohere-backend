package com.kohere.diagnosis.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageResponse;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisAccessDeniedException;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import com.kohere.diagnosis.domain.DiagnosisStatus;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendedListingView;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 확정 진단의 추천 매물 조회를 v1({@link DiagnosisService#getRecommendations})과 v2({@link
 * DiagnosisFlowService#getRecommendations})가 공유하는 컴포넌트. 페이지·정렬 검증 → 진단 조회(미존재 404) → 소유권 검증(타인 403)
 * → 조건 매핑 → listing 공개 query 동기 호출까지가 두 버전에서 동일하기 때문이다(ADR-0002 D5).
 *
 * <p>매핑 결과 DTO만 버전별로 다르다 — v1은 0건일 때 조정 제안({@code suggestions})을 덧붙이고, v2는 붙이지 않는다. 그 차이는 호출자가 정하고
 * 여기서는 listing이 준 페이지를 그대로 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class DiagnosisRecommendationReader {

  /** 추천 정렬 허용 키(스펙 §7). */
  private static final Set<String> SORT_KEYS = Set.of("recommended", "price", "distance");

  private final DiagnosisRepository diagnosisRepository;
  private final ListingRecommendationService listingRecommendationService;
  private final DiagnosisCriteriaMapper criteriaMapper;

  /** 본인 소유 확정 진단의 추천 매물 페이지를 조회한다(0건이면 빈 {@code content} — 에러 아님). */
  PageResponse<RecommendedListingView> read(
      long userId, Long diagnosisId, int page, int size, String sort) {
    validatePage(page, size);
    validateSort(sort);
    Diagnosis diagnosis =
        diagnosisRepository.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
    requireNotDiscarded(diagnosis);
    requireOwner(diagnosis, userId);
    return listingRecommendationService.recommendByCriteria(
        criteriaMapper.toCriteria(diagnosis, page, size, sort));
  }

  /**
   * 폐기 기록({@code DISCARDED})은 조회 대상이 아니다 — 없는 것처럼 취급한다.
   *
   * <p>이건 <b>소유권만으로 막히지 않는다</b>: 폐기 기록은 본인 것이고 진단 id가 순차 발급이라 추측 가능하다. 이력·최근은 {@code COMPLETED}만
   * 보지만 이 경로는 id로 직접 오므로 상태를 따로 걸러야 한다. 미완주 부분 답으로 추천을 돌리는 것도 무의미하다(ADR-0036 결정 12).
   */
  private static void requireNotDiscarded(Diagnosis diagnosis) {
    if (diagnosis.getStatus() == DiagnosisStatus.DISCARDED) {
      throw new DiagnosisNotFoundException();
    }
  }

  private static void requireOwner(Diagnosis diagnosis, long userId) {
    if (diagnosis.getUserId() == null || diagnosis.getUserId() != userId) {
      throw new DiagnosisAccessDeniedException();
    }
  }

  private static void validatePage(int page, int size) {
    if (page < 0) {
      throw new InvalidInputException("page는 0 이상이어야 합니다: " + page);
    }
    if (size < 1 || size > 100) {
      throw new InvalidInputException("size는 1~100 사이여야 합니다: " + size);
    }
  }

  private static void validateSort(String sort) {
    if (sort == null || sort.isBlank()) {
      return;
    }
    String[] parts = sort.split(",");
    String key = parts[0].trim();
    if (!SORT_KEYS.contains(key)) {
      throw new InvalidInputException("정렬 키가 올바르지 않습니다: " + key);
    }
    if (parts.length > 1) {
      String direction = parts[1].trim().toLowerCase();
      if (!direction.equals("asc") && !direction.equals("desc")) {
        throw new InvalidInputException("정렬 방향이 올바르지 않습니다: " + parts[1]);
      }
    }
  }
}
