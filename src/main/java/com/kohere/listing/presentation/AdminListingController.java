package com.kohere.listing.presentation;

import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.listing.application.AdminListingService;
import com.kohere.listing.application.dto.AdminListingDetailResponse;
import com.kohere.listing.presentation.dto.AdminListingSearchRequest;
import com.kohere.listing.presentation.dto.ListingRejectionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 매물 심사 REST 컨트롤러다(US-3-7). 입력 검증·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다
 * (docs/convention/code-style.md §3-3). 도메인 DTO만 반환하고 공통 래퍼는 {@link
 * com.kohere.common.response.ApiResponseWrapper}가 자동 적용한다(ADR-0013).
 *
 * <p>경로가 {@code /api/v1}인 것은 <b>신규 네임스페이스</b>이기 때문이다. 매물 조회 정본은 {@code /api/v2}지만, v2는 하위 호환이 깨지는
 * 변경에만 붙이는 규칙이고 여기에는 대체할 v1 계약이 없다(api-design-guide §2-1).
 *
 * <p>인가는 {@code SecurityConfig}의 {@code hasRole("USER")} 매처와 서비스의 관리자 재검사가 함께 만든다.
 *
 * <p>스펙: docs/api/specs/03-listings-favorites.md 「관리자 매물 심사」.
 */
@RestController
@RequestMapping("/api/v1/admin/listings")
@RequiredArgsConstructor
public class AdminListingController {

  private final AdminListingService adminListingService;

  /** 모든 상태의 매물을 조회한다. {@code status}를 생략하면 전체다. */
  @GetMapping
  public PageResponse<AdminListingDetailResponse> list(
      @AuthenticationPrincipal AuthPrincipal principal,
      @Valid @ModelAttribute AdminListingSearchRequest request) {
    return adminListingService.list(
        principal.userId(), request.status(), request.page(), request.size(), request.sort());
  }

  /** 심사 상세. 저장된 전 필드를 반환한다. */
  @GetMapping("/{listingId}")
  public AdminListingDetailResponse detail(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String listingId) {
    return adminListingService.detail(principal.userId(), listingId);
  }

  /**
   * 매물을 승인해 공개한다.
   *
   * <p>{@code 201}이 아니라 {@code 200}이다 — 자원을 만드는 것이 아니라 상태를 바꾸는 액션이라, 임대인 온보딩이 {@code 200}인 것과 같은
   * 이유다.
   */
  @PostMapping("/{listingId}/approval")
  public AdminListingDetailResponse approve(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String listingId) {
    return adminListingService.approve(principal.userId(), listingId);
  }

  /** 매물을 사유와 함께 반려한다. */
  @PostMapping("/{listingId}/rejection")
  public AdminListingDetailResponse reject(
      @AuthenticationPrincipal AuthPrincipal principal,
      @PathVariable String listingId,
      @Valid @RequestBody ListingRejectionRequest request) {
    return adminListingService.reject(principal.userId(), listingId, request.reason());
  }
}
