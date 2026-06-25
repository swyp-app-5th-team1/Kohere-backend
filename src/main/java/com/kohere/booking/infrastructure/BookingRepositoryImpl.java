package com.kohere.booking.infrastructure;

import com.kohere.booking.domain.Booking;
import com.kohere.booking.domain.BookingRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 예약 영속 어댑터(스켈레톤 placeholder). 도메인 포트 {@link BookingRepository}를 구현한다. 현재는 미구현이며 JPA 어댑터로
 * 교체한다(docs/convention/code-style.md §3-3).
 */
@Repository
public class BookingRepositoryImpl implements BookingRepository {

  @Override
  public Optional<Booking> findByTenantIdAndListingId(Long tenantId, String listingId) {
    throw new UnsupportedOperationException("TODO: JPA 구현으로 교체");
  }
}
