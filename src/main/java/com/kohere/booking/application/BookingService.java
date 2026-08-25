package com.kohere.booking.application;

import com.kohere.booking.BookingCreatedEvent;
import com.kohere.booking.application.dto.BookingDetailResponse;
import com.kohere.booking.application.dto.BookingReportResponse;
import com.kohere.booking.application.dto.BookingResponse;
import com.kohere.booking.application.dto.BookingSummaryResponse;
import com.kohere.booking.application.dto.LandlordBookingDetailResponse;
import com.kohere.booking.application.dto.LandlordBookingSummaryResponse;
import com.kohere.booking.application.dto.ReportReasonResponse;
import com.kohere.booking.domain.Booking;
import com.kohere.booking.domain.BookingAlreadyExistsException;
import com.kohere.booking.domain.BookingNotFoundException;
import com.kohere.booking.domain.BookingReport;
import com.kohere.booking.domain.BookingReportRepository;
import com.kohere.booking.domain.BookingRepository;
import com.kohere.booking.domain.BookingStatus;
import com.kohere.booking.domain.CounterpartBlockedException;
import com.kohere.booking.domain.InvalidMoveInDateException;
import com.kohere.booking.domain.ListingUnavailableException;
import com.kohere.booking.domain.ReportReasonRepository;
import com.kohere.booking.domain.TenantOnlyException;
import com.kohere.booking.presentation.dto.BookingReportRequest;
import com.kohere.booking.presentation.dto.BookingRequest;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.request.RequestDates;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.api.BookingListingQueryService;
import com.kohere.listing.api.RoomOfferBookingView;
import com.kohere.user.api.ApplicantProfileView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매물 예약(신청) 유스케이스. 예약 저장(생성)·내 예약 조회(목록·단건 상세)와 예약 내역 관리(삭제·차단·신고)를 조율한다. 예약은 {@code ACTIVE} 세입자
 * 전용이며(URL 게이트 {@code ROLE_USER} + 서비스 레벨 {@code TENANT} 검사), 동일 세입자·동일 방 상품 예약은 1건만 허용한다(재신청 시 409
 * BOOKING_ALREADY_EXISTS).
 *
 * <p>조회는 표시 가능한 예약만 반환한다 — 요청자 쪽 삭제({@code *_deleted_at})가 없고, 요청자가 차단한 상대의 예약이 아닌 것만. 차단 상대 집합은
 * {@code user :: api}(findBlockedUserIds)로 취득해 리포지토리 술어로 넘긴다(애플리케이션 레벨 조인 · ADR-0002). 삭제·차단·신고는
 * 삭제·차단 상태와 무관해야 하므로 비필터 조회로 참여자를 검증한다. 신규 신청은 차단 관계(양방향)면 거부한다(블랙홀 예약 방지).
 *
 * <p>예약 목록·상세의 매물 요약과 가격은 기존 계약대로 조회 시점에 {@code listing :: api}·{@code user :: api} 공개 쿼리로
 * 조합한다(cross-store 조인 금지 · ADR-0005). 다만 채팅의 BOOKING_CARD는 신청 당시 화면을 재현해야 하므로 예약 저장 시점 값을 {@link
 * BookingCreatedEvent}에 고정해 발행한다. booking은 chat을 직접 호출하지 않으며, chat listener가 이벤트를 비동기로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

  private static final String USER_TYPE_TENANT = "TENANT";
  private static final String USER_TYPE_LANDLORD = "LANDLORD";
  private static final int MAX_PAGE_SIZE = 100;

  private final BookingRepository bookingRepository;
  private final BookingReportRepository bookingReportRepository;
  private final ReportReasonRepository reportReasonRepository;
  private final BookingListingQueryService listingQueryService;
  private final UserAccountService userAccountService;
  private final AppUserGuard appUserGuard;
  private final UserBlockService userBlockService;
  private final BookingCreatedEventFactory bookingCreatedEventFactory;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * 예약을 저장하고, 동일한 커밋에 채팅 신청 카드 작업도 빠지지 않도록 모듈 이벤트를 기록한다.
   *
   * <p>HTTP 응답에 채팅 카드 자체를 넣지는 않는다. 프런트는 예약 성공 뒤 기존 문의 API로 같은 채팅방을 열고 메시지 이력 API에서 서버가 만든
   * BOOKING_CARD를 받는다. 이벤트 listener는 비동기이므로 아주 짧은 시간 동안 카드를 아직 조회하지 못할 수 있으며, 실시간 STOMP 연결 단계에서는 저장
   * 완료 이벤트로 화면을 자동 갱신한다.
   */
  @Transactional
  public BookingResponse createBooking(long tenantId, String listingId, BookingRequest request) {
    assertTenant(tenantId);
    RoomOfferBookingView offer =
        listingQueryService
            .findPublishedRoomOffer(listingId, request.roomOfferId())
            .orElseThrow(ListingUnavailableException::new);
    LocalDate moveInDate = RequestDates.parse("moveInDate", request.moveInDate());
    validateMoveInDate(moveInDate);
    if (userBlockService.isBlockedBetween(tenantId, offer.landlordId())) {
      throw new CounterpartBlockedException();
    }
    if (bookingRepository.existsByTenantIdAndRoomOfferId(tenantId, request.roomOfferId())) {
      throw new BookingAlreadyExistsException();
    }

    // 예약과 이벤트에 같은 시각을 사용해야 카드가 어떤 신청 커밋에서 시작됐는지 일관되게 추적할 수 있다.
    Instant createdAt = Instant.now();
    Booking saved =
        bookingRepository.save(
            Booking.builder()
                .tenantId(tenantId)
                .listingId(listingId)
                .roomOfferId(request.roomOfferId())
                .landlordId(offer.landlordId())
                .moveInDate(moveInDate)
                .contractPeriod(request.contractPeriod())
                .status(BookingStatus.REQUESTED)
                .createdAt(createdAt)
                .build());

    // 임대인 카드에 필요한 신청자 정보도 예약이 성공한 바로 이 시점에 고정한다. listener에서 다시 조회하지 않는다.
    ApplicantProfileView applicant = userAccountService.getApplicantProfile(tenantId);
    BookingCreatedEvent event = bookingCreatedEventFactory.create(saved, offer, applicant);

    // Spring Modulith JPA가 이 publish를 가로채 예약과 같은 MySQL 트랜잭션에 EVENT_PUBLICATION 행을 기록한다.
    // 따라서 아래 트랜잭션이 롤백되면 예약과 이벤트가 함께 사라지고, 커밋되면 chat listener가 비동기로 처리한다.
    eventPublisher.publishEvent(event);

    return new BookingResponse(
        saved.getId(),
        saved.getStatus(),
        saved.getListingId(),
        saved.getRoomOfferId(),
        saved.getMoveInDate(),
        saved.getContractPeriod(),
        saved.getCreatedAt());
  }

  /**
   * 예약 목록 조회(userType 분기). {@code LANDLORD}면 내 소유 매물에 신청된 예약을({@code landlord_id} 스코프), 그 외(세입자)는 내
   * 예약을 {@code createdAt} 내림차순 오프셋 페이지네이션으로 반환한다. 삭제·차단 대상은 제외한다. 두 역할 모두 유효 요청이라 역할 {@code 403}은
   * 없다.
   *
   * <p>범위 밖 {@code page}·{@code size}는 보정하지 않고 {@code 400 INVALID_INPUT}으로 거절한다 — 조용히 깎으면 100개를 받은
   * 클라이언트가 「원래 100개」인지 「잘린 것」인지 구분할 수 없다. 스펙 04 §2와 {@code listing}의 목록 API가 쓰는 규칙과 같다.
   */
  @Transactional(readOnly = true)
  public PageResponse<?> getBookings(long userId, int page, int size) {
    appUserGuard.requireAppUser(userId);
    validatePage(page, size);
    List<Long> blockedIds = userBlockService.findBlockedUserIds(userId);

    if (USER_TYPE_LANDLORD.equals(userAccountService.getUserType(userId))) {
      List<LandlordBookingSummaryResponse> content =
          bookingRepository.findVisibleByLandlordId(userId, blockedIds, page, size).stream()
              .map(this::toLandlordSummary)
              .toList();
      return page(
          content, page, size, bookingRepository.countVisibleByLandlordId(userId, blockedIds));
    }
    List<BookingSummaryResponse> content =
        bookingRepository.findVisibleByTenantId(userId, blockedIds, page, size).stream()
            .map(this::toSummary)
            .toList();
    return page(content, page, size, bookingRepository.countVisibleByTenantId(userId, blockedIds));
  }

  /** 페이지 번호와 크기가 API 약속 범위 안에 있는지 확인한다. */
  private static void validatePage(int page, int size) {
    if (page < 0) {
      throw new InvalidInputException("page", "validation.min", 0, page);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidInputException("size", "validation.range", 1, MAX_PAGE_SIZE, size);
    }
  }

  /**
   * 예약 단건 상세 조회(userType 분기). 삭제·차단 대상은 제외한다. 조회 권한 밖(세입자: 타인 예약 / 임대인: 내 소유 매물 신청 아님)이거나 없으면
   * {@code BookingNotFoundException}(404 통일)이다.
   */
  @Transactional(readOnly = true)
  public Object getBooking(long userId, long bookingId) {
    appUserGuard.requireAppUser(userId);
    List<Long> blockedIds = userBlockService.findBlockedUserIds(userId);
    if (USER_TYPE_LANDLORD.equals(userAccountService.getUserType(userId))) {
      Booking booking =
          bookingRepository
              .findVisibleByIdAndLandlordId(bookingId, userId, blockedIds)
              .orElseThrow(BookingNotFoundException::new);
      return toLandlordDetail(
          booking, offerOf(booking), userAccountService.getApplicantProfile(booking.getTenantId()));
    }
    Booking booking =
        bookingRepository
            .findVisibleByIdAndTenantId(bookingId, userId, blockedIds)
            .orElseThrow(BookingNotFoundException::new);
    return toDetail(booking, offerOf(booking), userAccountService.getUserName(userId));
  }

  /**
   * 예약 내역 삭제 — 요청자 쪽 소프트삭제 컬럼만 세팅한다(상대에겐 그대로 보인다). 멱등(이미 삭제해도 204). 참여자가 아니거나 없으면 {@code
   * BookingNotFoundException}(404 통일, 존재 비노출). 변이라 비필터 조회로 참여자를 판정한다.
   */
  @Transactional
  public void deleteBooking(long userId, long bookingId) {
    appUserGuard.requireAppUser(userId);
    Booking booking =
        bookingRepository.findByIdForMutation(bookingId).orElseThrow(BookingNotFoundException::new);
    Instant now = Instant.now();
    if (isTenant(booking, userId)) {
      bookingRepository.markTenantDeleted(bookingId, now);
    } else if (isLandlord(booking, userId)) {
      bookingRepository.markLandlordDeleted(bookingId, now);
    } else {
      throw new BookingNotFoundException();
    }
  }

  /**
   * 예약 상대 차단 — 예약에서 상대(counterpart)를 서버가 도출해 {@code user :: api} 공개 명령으로 차단을 등록한다. 멱등(이미 차단이면 204).
   * 참여자가 아니거나 없으면 404(존재 비노출).
   */
  @Transactional
  public void blockBooking(long userId, long bookingId) {
    appUserGuard.requireAppUser(userId);
    Booking booking =
        bookingRepository.findByIdForMutation(bookingId).orElseThrow(BookingNotFoundException::new);
    userBlockService.block(userId, counterpartOf(booking, userId));
  }

  /**
   * 예약 신고 접수 — 참여자만 신고할 수 있다. 동일 예약을 여러 번 신고할 수 있다(다건 허용 · 도배 방지는 후속 레이트리밋). 신고 대상 판정은 삭제·차단 상태와
   * 무관하다(비필터 조회 · 증거 보존). reason은 선택이며 응답에 {@code reporterId}·{@code detail} 원문은 노출하지 않는다.
   */
  @Transactional
  public BookingReportResponse reportBooking(
      long userId, long bookingId, BookingReportRequest request) {
    appUserGuard.requireAppUser(userId);
    Booking booking =
        bookingRepository.findByIdForMutation(bookingId).orElseThrow(BookingNotFoundException::new);
    if (!isParticipant(booking, userId)) {
      throw new BookingNotFoundException();
    }
    String reason = request.reason();
    validateReason(reason);
    BookingReport saved =
        bookingReportRepository.save(
            BookingReport.create(userId, bookingId, reason, request.detail(), Instant.now()));
    return new BookingReportResponse(
        saved.getId(), saved.getBookingId(), saved.getReason(), saved.getCreatedAt());
  }

  /**
   * 예약 신고 사유 목록. code는 언어 무관 불변이고, label은 사용자 표시 언어({@code user :: api getLanguage})로 content 번들에서
   * 번역한다 — 에러 메시지의 {@code Accept-Language} 경로와 구분한다(ADR-0030 · #169). 키 부재 시 code로 폴백한다.
   */
  @Transactional(readOnly = true)
  public ReportReasonResponse getReportReasons(long userId) {
    appUserGuard.requireAppUser(userId);
    String lang = userAccountService.getLanguage(userId);
    List<ReportReasonResponse.Reason> reasons =
        reportReasonRepository.findActiveOrdered(lang).stream()
            .map(r -> new ReportReasonResponse.Reason(r.code(), r.label()))
            .toList();
    return new ReportReasonResponse(reasons);
  }

  /**
   * 이미 존재하는 예약의 카드 값을 읽는다. <b>매물·방 상태를 보지 않는다.</b>
   *
   * <p>표시 경로가 여기 하나로 모여 있어야 한다 — 예전에는 목록이 저장소 포트를 직접 불러, 상세만 고치면 목록 카드가 계속 빈 채로 남는 모양이었다.
   */
  private RoomOfferBookingView offerOf(Booking booking) {
    return listingQueryService
        .findRoomOfferForExistingBooking(booking.getListingId(), booking.getRoomOfferId())
        .orElse(null);
  }

  private static <T> PageResponse<T> page(List<T> content, int page, int size, long total) {
    int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
    boolean hasNext = page + 1 < totalPages;
    return PageResponse.of(content, new PageInfo(page, size, total, totalPages, hasNext));
  }

  private void assertTenant(long userId) {
    if (!USER_TYPE_TENANT.equals(userAccountService.getUserType(userId))) {
      throw new TenantOnlyException();
    }
  }

  private void validateMoveInDate(LocalDate moveInDate) {
    if (moveInDate.isBefore(LocalDate.now())) {
      throw new InvalidMoveInDateException();
    }
  }

  /** 예약 상대(counterpart)를 도출한다. 참여자가 아니면 404(존재 비노출) — 자기 차단은 구조상 발생하지 않는다. */
  private static long counterpartOf(Booking booking, long userId) {
    if (isTenant(booking, userId)) {
      return booking.getLandlordId();
    }
    if (isLandlord(booking, userId)) {
      return booking.getTenantId();
    }
    throw new BookingNotFoundException();
  }

  private static boolean isParticipant(Booking booking, long userId) {
    return isTenant(booking, userId) || isLandlord(booking, userId);
  }

  private static boolean isTenant(Booking booking, long userId) {
    return booking.getTenantId() != null && booking.getTenantId() == userId;
  }

  private static boolean isLandlord(Booking booking, long userId) {
    return booking.getLandlordId() != null && booking.getLandlordId() == userId;
  }

  /** 선택 사유 검증 — {@code null}은 사유 없음, 카탈로그에 없는(또는 비활성) 코드는 400 {@code INVALID_INPUT}. */
  private void validateReason(String reason) {
    if (reason != null && !reportReasonRepository.existsActiveByCode(reason)) {
      throw new InvalidInputException("reason", "validation.notAllowed", reason);
    }
  }

  private BookingSummaryResponse toSummary(Booking booking) {
    RoomOfferBookingView offer = offerOf(booking);
    return new BookingSummaryResponse(
        booking.getId(),
        booking.getListingId(),
        offer == null ? null : offer.title(),
        offer == null ? null : offer.thumbnailUrl(),
        booking.getRoomOfferId(),
        booking.getMoveInDate(),
        booking.getContractPeriod(),
        booking.getStatus(),
        booking.getCreatedAt());
  }

  private BookingDetailResponse toDetail(
      Booking booking, RoomOfferBookingView offer, String tenantName) {
    int deposit = offer == null ? 0 : offer.deposit();
    int totalAmount =
        offer == null ? 0 : offer.deposit() + offer.monthlyRent() * booking.getContractPeriod();
    return new BookingDetailResponse(
        booking.getId(),
        booking.getStatus(),
        booking.getListingId(),
        booking.getRoomOfferId(),
        offer == null ? null : offer.title(),
        offer == null ? null : offer.thumbnailUrl(),
        offer == null ? null : offer.address(),
        offer == null ? null : offer.roomOfferName(),
        booking.getCreatedAt(),
        booking.getMoveInDate(),
        booking.getContractPeriod(),
        tenantName,
        deposit,
        totalAmount);
  }

  private LandlordBookingSummaryResponse toLandlordSummary(Booking booking) {
    RoomOfferBookingView offer = offerOf(booking);
    return new LandlordBookingSummaryResponse(
        booking.getId(),
        booking.getListingId(),
        offer == null ? null : offer.title(),
        offer == null ? null : offer.thumbnailUrl(),
        booking.getRoomOfferId(),
        offer == null ? null : offer.roomOfferName(),
        userAccountService.getUserName(booking.getTenantId()),
        booking.getMoveInDate(),
        booking.getContractPeriod(),
        booking.getStatus(),
        booking.getCreatedAt());
  }

  private LandlordBookingDetailResponse toLandlordDetail(
      Booking booking, RoomOfferBookingView offer, ApplicantProfileView applicant) {
    int deposit = offer == null ? 0 : offer.deposit();
    int totalAmount =
        offer == null ? 0 : offer.deposit() + offer.monthlyRent() * booking.getContractPeriod();
    return new LandlordBookingDetailResponse(
        booking.getId(),
        booking.getStatus(),
        booking.getListingId(),
        booking.getRoomOfferId(),
        offer == null ? null : offer.title(),
        offer == null ? null : offer.thumbnailUrl(),
        offer == null ? null : offer.address(),
        offer == null ? null : offer.roomOfferName(),
        booking.getCreatedAt(),
        booking.getMoveInDate(),
        booking.getContractPeriod(),
        applicant.userId(),
        applicant.name(),
        applicant.gender(),
        applicant.country(),
        applicant.countryName(),
        applicant.email(),
        deposit,
        totalAmount);
  }
}
