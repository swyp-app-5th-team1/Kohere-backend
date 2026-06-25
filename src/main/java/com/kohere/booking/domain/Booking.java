package com.kohere.booking.domain;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

/**
 * 예약(신청) 애그리거트 루트. 영속 기술(JPA)에 의존하지 않는 순수 도메인 모델이다. 영속 매핑은 infrastructure 계층의 어댑터에서
 * 처리한다(docs/convention/code-style.md §3-3).
 *
 * <p>{@code (tenantId, listingId)}의 활성 예약은 1건만 허용한다(중복 신청 방지).
 * docs/api/specs/04-booking-inquiry-chat.md.
 *
 * <p>TODO: 도메인 불변식·상태 전이(수락/거절/취소, 입주 가능일 검증 등) 메서드를 채운다.
 */
@Getter
@Builder
public class Booking {

  private final Long id;
  private final Long tenantId;
  private final String listingId;
  private final LocalDate moveInDate;
  private final ContractPeriod contractPeriod;
  private final BookingStatus status;
  private final Instant createdAt;
}
