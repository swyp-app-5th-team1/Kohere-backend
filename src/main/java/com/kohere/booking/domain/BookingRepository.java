package com.kohere.booking.domain;

import java.util.Optional;

/**
 * 예약 영속 포트. 구현은 infrastructure 계층에 두어 의존성을 역전한다(docs/convention/code-style.md §3-3). 도메인은 영속 기술을
 * 모른다.
 *
 * <p>TODO: 활성 예약 판정(REQUESTED/ACCEPTED)·저장 메서드를 추가한다.
 */
public interface BookingRepository {

  Optional<Booking> findByTenantIdAndListingId(Long tenantId, String listingId);
}
