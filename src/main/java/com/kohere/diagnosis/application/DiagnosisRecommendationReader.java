package com.kohere.diagnosis.application;

import com.kohere.common.response.PageResponse;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendedListingMarkersView;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.user.api.UserAccountService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 확정 진단의 추천 매물을 페이지와 마커 두 모양으로 조회하는 컴포넌트. 페이지·정렬 검증 → 진단 조회(미존재 404) → 상태 검증(확정 아니면 404) → 소유권
 * 검증(타인 403) → 조건 매핑 → listing 공개 query 동기 호출까지가 두 경로에서 동일하다(ADR-0002 D5).
 *
 * <p>두 경로의 차이는 결과 모양뿐이다 — 페이지 조회는 오프셋 페이지를, 마커 조회는 페이지 없이 서버 상한까지의 좌표를 돌려준다.
 *
 * <p><b>게스트가 닿는 유일한 소유권 검사 지점</b>이다(#181) — 추천 조회가 비회원에게 열려 있어 신원을 둘(회원 {@code userId} / 게스트 세션 키)
 * 받는다. 회원 호출자는 게스트 키 자리에 {@code null}을 넘긴다.
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
   * @param guestSessionId 게스트 세션 키(회원은 {@code null})
   */
  PageResponse<RecommendedListingView> read(
      Long userId, String guestSessionId, Long diagnosisId, int page, int size, String sort) {
    DiagnosisPageRequests.validatePage(page, size);
    DiagnosisPageRequests.validateSort(sort, SORT_KEYS);
    Diagnosis diagnosis =
        diagnosisRepository.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
    DiagnosisAccessGuard.requireCompleted(diagnosis);
    DiagnosisAccessGuard.requireOwner(diagnosis, userId, guestSessionId);
    return listingRecommendationService.recommendByCriteria(
        criteriaMapper.toCriteria(diagnosis, page, size, sort), resolveLanguage(userId));
  }

  /**
   * 같은 진단 조건의 지도 마커를 페이지 없이 조회한다(서버 상한까지).
   *
   * <p>페이지 조회와 같은 게이트를 쓴다 — 확정 진단만 통과한다. 여기는 페이지 크기 상한이 없어 조건이 빈 미완주 초안이 통과하면 그대로 "조건 없는 전체 매물" 조회가
   * 되므로 특히 그렇다. 게이트 순서는 {@link DiagnosisAccessGuard}의 불변식을 따른다 — 상태(404)가 소유권(403)보다 먼저다.
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
}
