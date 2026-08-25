package com.kohere.listing.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 매물 반려 요청이다({@code POST /api/v1/admin/listings/{listingId}/rejection}).
 *
 * <p>승인과 반려를 하나의 상태 변경 API로 묶지 않은 이유가 이 타입에 있다 — <b>"반려에는 사유가 필요하다"를 요청 타입 자체로 강제</b>한다. {@code
 * PATCH /{id}/status}였다면 {@code status} 값에 따라 사유 필수 여부가 갈리는 조건부 검증이 되고, 승인 요청에 사유가 실려 와도 막을 수 없다.
 *
 * @param reason 반려 사유. 임대인만 읽는 값이라 번역하지 않는다
 */
public record ListingRejectionRequest(@NotBlank @Size(max = 500) String reason) {}
