package com.kohere.booking.infrastructure;

import com.kohere.booking.domain.Booking;
import com.kohere.booking.domain.BookingAlreadyExistsException;
import com.kohere.booking.domain.BookingRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * 예약 영속 어댑터. 도메인 포트 {@link BookingRepository}를 구현하고 Spring Data JPA에 위임한다. 도메인↔엔티티 변환은 private
 * static 컨버터로 처리한다(docs/convention/code-style.md §3-3).
 *
 * <p>빈 차단 목록 함정 방지: {@code NOT IN ()}는 문법 오류, {@code NOT IN (null)}은 UNKNOWN이라 모든 행이 사라진다. 그래서 어댑터
 * 내부에서 빈 목록을 sentinel {@code -1L} 한 건으로 정규화한다 — {@code users.id}는 {@code BIGINT AUTO_INCREMENT}(양의
 * 정수)라 {@code -1}이 실제 식별자와 충돌할 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class BookingRepositoryImpl implements BookingRepository {

  private static final List<Long> NO_BLOCKS = List.of(-1L);

  private final BookingJpaRepository jpaRepository;

  @Override
  public Booking save(Booking booking) {
    try {
      // IDENTITY 전략이라 save가 즉시 INSERT를 실행한다 — 유니크 (tenant_id, room_offer_id) 위반이 여기서 바로 surface된다.
      return toDomain(jpaRepository.save(toEntity(booking)));
    } catch (DataIntegrityViolationException e) {
      throw new BookingAlreadyExistsException();
    }
  }

  @Override
  public boolean existsByTenantIdAndRoomOfferId(long tenantId, String roomOfferId) {
    return jpaRepository.existsByTenantIdAndRoomOfferId(tenantId, roomOfferId);
  }

  @Override
  public boolean existsByTenantIdAndListingId(long tenantId, String listingId) {
    return jpaRepository.existsByTenantIdAndListingId(tenantId, listingId);
  }

  @Override
  public List<Booking> findVisibleByTenantId(
      Long tenantId, Collection<Long> blockedIds, int page, int size) {
    return jpaRepository
        .findVisibleByTenantId(
            tenantId,
            excluded(blockedIds),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
        .stream()
        .map(BookingRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public long countVisibleByTenantId(Long tenantId, Collection<Long> blockedIds) {
    return jpaRepository.countVisibleByTenantId(tenantId, excluded(blockedIds));
  }

  @Override
  public Optional<Booking> findVisibleByIdAndTenantId(
      Long id, Long tenantId, Collection<Long> blockedIds) {
    return jpaRepository
        .findVisibleByIdAndTenantId(id, tenantId, excluded(blockedIds))
        .map(BookingRepositoryImpl::toDomain);
  }

  @Override
  public List<Booking> findVisibleByLandlordId(
      Long landlordId, Collection<Long> blockedIds, int page, int size) {
    return jpaRepository
        .findVisibleByLandlordId(
            landlordId,
            excluded(blockedIds),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
        .stream()
        .map(BookingRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public long countVisibleByLandlordId(Long landlordId, Collection<Long> blockedIds) {
    return jpaRepository.countVisibleByLandlordId(landlordId, excluded(blockedIds));
  }

  @Override
  public Optional<Booking> findVisibleByIdAndLandlordId(
      Long id, Long landlordId, Collection<Long> blockedIds) {
    return jpaRepository
        .findVisibleByIdAndLandlordId(id, landlordId, excluded(blockedIds))
        .map(BookingRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Booking> findByIdForMutation(Long id) {
    return jpaRepository.findById(id).map(BookingRepositoryImpl::toDomain);
  }

  @Override
  public void markTenantDeleted(Long id, Instant deletedAt) {
    jpaRepository.markTenantDeleted(id, deletedAt);
  }

  @Override
  public void markLandlordDeleted(Long id, Instant deletedAt) {
    jpaRepository.markLandlordDeleted(id, deletedAt);
  }

  /** 빈 차단 목록을 sentinel {@code -1L}로 정규화한다(빈 {@code NOT IN} 함정 방지). */
  private static Collection<Long> excluded(Collection<Long> blockedIds) {
    return blockedIds == null || blockedIds.isEmpty() ? NO_BLOCKS : blockedIds;
  }

  private static Booking toDomain(BookingJpaEntity e) {
    return Booking.builder()
        .id(e.getId())
        .tenantId(e.getTenantId())
        .listingId(e.getListingId())
        .roomOfferId(e.getRoomOfferId())
        .landlordId(e.getLandlordId())
        .moveInDate(e.getMoveInDate())
        .contractPeriod(e.getContractPeriod())
        .status(e.getStatus())
        .createdAt(e.getCreatedAt())
        .build();
  }

  private static BookingJpaEntity toEntity(Booking b) {
    return BookingJpaEntity.builder()
        .id(b.getId())
        .tenantId(b.getTenantId())
        .listingId(b.getListingId())
        .roomOfferId(b.getRoomOfferId())
        .landlordId(b.getLandlordId())
        .moveInDate(b.getMoveInDate())
        .contractPeriod(b.getContractPeriod())
        .status(b.getStatus())
        .createdAt(b.getCreatedAt())
        .build();
  }
}
