package com.kohere.booking.presentation;

import com.kohere.booking.application.BookingService;
import com.kohere.booking.application.dto.BookingResponse;
import com.kohere.booking.presentation.dto.BookingRequest;
import com.kohere.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 신청(예약) REST 컨트롤러. 입력 검증·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다 (docs/convention/code-style.md
 * §3-3). 응답은 공통 래퍼로 감싼다.
 *
 * <p>스펙: docs/api/specs/04-booking-inquiry-chat.md (신청/예약 부분). 신청은 매물에 종속되는 액션이므로 {@code
 * /listings/{listingId}} 하위에 중첩한다.
 */
@RestController
@RequestMapping("/api/v1/listings/{listingId}")
@RequiredArgsConstructor
public class BookingController {

  private final BookingService bookingService;

  @PostMapping("/bookings")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<BookingResponse> createBooking(
      @PathVariable String listingId, @Valid @RequestBody BookingRequest request) {
    return ApiResponse.success(bookingService.createBooking(listingId, request));
  }
}
