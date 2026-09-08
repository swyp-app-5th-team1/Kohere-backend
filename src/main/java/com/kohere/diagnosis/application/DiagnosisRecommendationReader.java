package com.kohere.diagnosis.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageResponse;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisAccessDeniedException;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import com.kohere.diagnosis.domain.DiagnosisStatus;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendedListingMarkersView;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.user.api.UserAccountService;
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
 *
 * <p><b>게스트가 닿는 유일한 소유권 검사 지점</b>이다(#181) — v2 추천 조회만 비회원에게 열려 있고 v1은 회원 전용이라 토큰 없는 요청이 여기까지 오지
 * 못한다. 그래서 신원을 둘(회원 {@code userId} / 게스트 세션 키) 받으며, v1 호출자는 게스트 키 자리에 {@code null}을 넘긴다.
 */
@Component
@RequiredArgsConstructor
public class DiagnosisRecommendationReader {

  /** 추천 정렬 허용 키(스펙 §7). */
  private static final Set<String> SORT_KEYS = Set.of("recommended", "price", "distance");

  /** 마커 조회가 조건 매퍼에 넘기는 자리 채우기 — 이 경로는 페이지를 나누지 않는다. */
  private static final int IGNORED_PAGE = 0;

  /** 위와 같다. 조건 매퍼가 값을 요구할 뿐 저장소까지 전달되지 않는다. */
  private static final int IGNORED_SIZE = 1;

  /** 게스트 표시 언어(#181). users 행이 없어 조회할 수 없으므로 고정한다. */
  private static final String GUEST_LANGUAGE = "en";

  private final DiagnosisRepository diagnosisRepository;
  private final ListingRecommendationService listingRecommendationService;
  private final DiagnosisCriteriaMapper criteriaMapper;
  private final UserAccountService userAccountService;

  /**
   * 본인 소유 확정 진단의 추천 매물 페이지를 조회한다(0건이면 빈 {@code content} — 에러 아님).
   *
   * @param userId 회원이면 userId, 게스트면 {@code null}
   * @param guestSessionId 게스트 세션 키(회원·v1 호출자는 {@code null})
   */
  PageResponse<RecommendedListingView> read(
      Long userId, String guestSessionId, Long diagnosisId, int page, int size, String sort) {
    validatePage(page, size);
    validateSort(sort);
    Diagnosis diagnosis =
        diagnosisRepository.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
    requireNotDiscarded(diagnosis);
    requireOwner(diagnosis, userId, guestSessionId);
    return listingRecommendationService.recommendByCriteria(
        criteriaMapper.toCriteria(diagnosis, page, size, sort), resolveLanguage(userId));
  }

  /**
   * 같은 진단 조건의 지도 마커를 페이지 없이 조회한다(서버 상한까지).
   *
   * <p><b>이 경로만 확정 진단을 요구한다.</b> 페이지 조회는 폐기 기록만 막는데, 여기는 페이지 크기 상한이 없어 조건이 빈 미완주 초안이 그대로 "조건 없는 전체
   * 매물" 조회가 된다. 게이트 순서는 {@link DiagnosisAccessGuard}의 불변식을 따른다 — 상태(404)가 소유권(403)보다 먼저다.
   *
   * <p>페이지·정렬을 받지 않으므로 그 검증도 하지 않는다. {@code toCriteria}에 넘기는 페이지 인자는 이 경로에서 쓰이지 않는 자리 채우기이며, 그 사실이
   * 코드에 남도록 명명 상수로 둔다.
   *
   * @param userId 회원이면 userId, 게스트면 {@code null}
   * @param guestSessionId 게스트 세션 키(회원은 {@code null})
   */
  RecommendedListingMarkersView readMarkers(Long userId, String guestSessionId, Long diagnosisId) {
    Diagnosis diagnosis =
        diagnosisRepository.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
    DiagnosisAccessGuard.requireCompleted(diagnosis);
    DiagnosisAccessGuard.requireOwner(diagnosis, userId, guestSessionId);
    return listingRecommendationService.recommendMarkersByCriteria(
        criteriaMapper.toCriteria(diagnosis, IGNORED_PAGE, IGNORED_SIZE, null));
  }

  /**
   * 매물 라벨의 표시 언어. <b>게스트는 {@code en} 고정이며 {@code user} 모듈을 호출하지 않는다</b>(#181) — {@code users} 행이 없어
   * 호출 자체가 {@code 404 USER_NOT_FOUND}가 되기 때문이다.
   */
  private String resolveLanguage(Long userId) {
    return userId == null ? GUEST_LANGUAGE : userAccountService.getLanguage(userId);
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

  /**
   * 소유권 판정은 {@link Diagnosis#isOwnedBy(Long, String)} 하나에 위임한다 — 같은 규칙이 {@link
   * DiagnosisService#getDetail}에도 필요한데, 규칙을 두 벌 두면 한쪽만 고쳐져 다른 경로가 뚫린다(#181).
   */
  private static void requireOwner(Diagnosis diagnosis, Long userId, String guestSessionId) {
    if (!diagnosis.isOwnedBy(userId, guestSessionId)) {
      throw new DiagnosisAccessDeniedException();
    }
  }

  private static void validatePage(int page, int size) {
    if (page < 0) {
      throw new InvalidInputException("page", "validation.min", 0, page);
    }
    if (size < 1 || size > 100) {
      throw new InvalidInputException("size", "validation.range", 1, 100, size);
    }
  }

  private static void validateSort(String sort) {
    if (sort == null || sort.isBlank()) {
      return;
    }
    String[] parts = sort.split(",");
    String key = parts[0].trim();
    if (!SORT_KEYS.contains(key)) {
      throw new InvalidInputException("sort", "validation.sortKey", key);
    }
    if (parts.length > 1) {
      String direction = parts[1].trim().toLowerCase();
      if (!direction.equals("asc") && !direction.equals("desc")) {
        throw new InvalidInputException("sort", "validation.sortDirection", parts[1]);
      }
    }
  }
}
