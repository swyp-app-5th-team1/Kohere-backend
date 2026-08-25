package com.kohere.common.exception;

import com.kohere.common.logging.AccessLogContext;
import com.kohere.common.response.ApiResponse;
import com.kohere.common.response.FieldErrorDetail;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 핸들러. 모든 예외를 공통 래퍼 에러 응답으로 일관되게 변환한다.
 *
 * <p>도메인/비즈니스 예외는 {@link BusinessException}으로, 입력 검증 실패는 {@code INVALID_INPUT}으로, Spring MVC 표준 예외는
 * 대응 공통 코드로 매핑한다. docs/api/error-response-guide.md §5.
 *
 * <p>응답 {@code message}는 {@link ErrorCode#getCode()}를 키로 리소스 번들({@code
 * messages[_<lang>].properties})에서 요청 locale({@code Accept-Language})로 번역한다 — 키/언어가 없으면 기본 번들(영어),
 * 그래도 없으면 코드의 기본 메시지로 폴백한다(ADR-0030). 동적 메시지({@code BusinessException}의 커스텀 메시지)는 키 부재 시 그대로 노출된다.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

  /**
   * 409 {@code RESOURCE_CONFLICT}로 번역할 UNIQUE 제약의 <b>화이트리스트</b>(소문자). 여기 있는 세 개는 모두 <b>재시도가 실제로 복구
   * 경로인</b> 경합이며, 스펙 에러 표에 409로 문서화돼 있다 — {@code uq_users_phone_number}(V23: 같은 번호의 웹 가입 ↔ 앱 임대인
   * 온보딩), {@code uq_local_accounts_email}·{@code uq_local_accounts_user_id}(V22: 웹 자격증명 중복 등록).
   *
   * <p><b>블랙리스트가 아니라 화이트리스트인 이유</b> — 모르는 제약을 409로 낮추는 실수는 조용하지만(클라이언트가 무한 재시도하고 알림은 안 뜬다), 모르는 제약을
   * 500으로 두는 실수는 시끄럽다(ERROR 로그와 알림이 뜬다). 제약이 늘어날 때 안전한 쪽으로 실패하려면 목록에 없는 것을 500에 남겨야 한다.
   *
   * <p>여기 <b>없는</b> UNIQUE도 각자 이유가 있다 — {@code uq_bookings_tenant_room_offer}는 {@code
   * BookingRepositoryImpl}이 도메인 코드({@code BOOKING_ALREADY_EXISTS})로 이미 바꾸고, {@code
   * uq_user_blocks_*}는 {@code UserBlockServiceImpl}이 멱등으로 삼켜 애초에 여기까지 오지 않는다. {@code
   * uq_users_nickname}·{@code uq_social_accounts_provider_user}는 서버가 값을 만드는 자리라 위반이 곧 생성 로직의 버그이므로
   * 500이 맞다.
   */
  private static final Set<String> RETRYABLE_UNIQUE_CONSTRAINTS =
      Set.of("uq_users_phone_number", "uq_local_accounts_email", "uq_local_accounts_user_id");

  private final MessageSource messageSource;

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
    ErrorCode ec = e.getErrorCode();
    // 5xx(UPSTREAM_ERROR·INTERNAL_ERROR 등)는 서버/의존성 장애다 — cause 스택트레이스까지 남겨야
    // 삼켜지지 않는다(예: SMTP MailException). 4xx는 클라이언트 오류라 노이즈이므로 로깅하지 않는다.
    if (ec.getHttpStatus().is5xxServerError()) {
      log.error("Business exception [{}]: {}", ec.getCode(), e.getMessage(), e);
    }
    AccessLogContext.errorCode(ec.getCode());
    // details 를 재정의하지 않은 예외는 null 이라 키 자체가 빠진다 — 종전 응답과 외형이 같다(ADR-0004 Amended).
    return ResponseEntity.status(ec.getHttpStatus())
        .body(ApiResponse.error(ec.getCode(), resolveMessage(ec, e.getMessage()), e.getDetails()));
  }

  /**
   * 필드를 특정한 {@code InvalidInputException}은 Bean Validation 위반과 같은 모양으로 돌려준다 — {@code
   * error.message}가 일반 문구로 덮이는 구조라, 어느 필드가 왜 거절됐는지 전달할 수 있는 자리는 {@code errors[]}뿐이다(#151). 필드를 특정하지
   * 않았으면 빈 배열이라 {@link #handleBusiness}와 결과가 같다.
   */
  @ExceptionHandler(InvalidInputException.class)
  public ResponseEntity<ApiResponse<Void>> handleInvalidInput(InvalidInputException e) {
    ErrorCode ec = e.getErrorCode();
    AccessLogContext.errorCode(ec.getCode());
    List<FieldErrorDetail> details =
        e.getField() == null
            ? List.of()
            : List.of(new FieldErrorDetail(e.getField(), resolveReason(e)));
    return ResponseEntity.status(ec.getHttpStatus())
        .body(ApiResponse.error(ec.getCode(), resolveMessage(ec, ec.getDefaultMessage()), details));
  }

  /** 사유 번들 키를 요청 locale로 해소한다. 키가 없으면 넘어온 문자열을 그대로 쓴다 — 아직 키로 바꾸지 않은 호출부가 종전처럼 동작하게 하는 폴백이다. */
  private String resolveReason(InvalidInputException e) {
    return messageSource.getMessage(
        e.getReasonCode(), e.getReasonArgs(), e.getReasonCode(), LocaleContextHolder.getLocale());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
    List<FieldErrorDetail> details =
        e.getBindingResult().getFieldErrors().stream().map(this::toFieldErrorDetail).toList();
    ErrorCode ec = ErrorCode.INVALID_INPUT;
    AccessLogContext.errorCode(ec.getCode());
    return ResponseEntity.badRequest()
        .body(ApiResponse.error(ec.getCode(), resolveMessage(ec, ec.getDefaultMessage()), details));
  }

  /**
   * 요청을 해석조차 못 한 경우다.
   *
   * <p>{@code MissingServletRequestPartException}이 함께 있는 이유 — multipart 요청에 필수 part가 빠지면 Spring이 이
   * 예외를 던지는데, 이 클래스의 {@code @ExceptionHandler(Exception.class)}가 {@code
   * DefaultHandlerExceptionResolver}보다 먼저 잡아 <b>500 INTERNAL_ERROR</b>로 나가 버린다. 클라이언트가 part 이름을 틀린
   * 것이지 서버 장애가 아니다.
   */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestPartException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception e) {
    return errorResponse(ErrorCode.MALFORMED_REQUEST);
  }

  /**
   * 요청 총량이 서블릿 상한을 넘었다.
   *
   * <p>multipart 해석은 핸들러를 찾기 전에 일어나므로 어느 엔드포인트인지 알 수 없다 — 도메인 코드 대신 공통 코드를 쓴다. 사진 한 장의 상한은 도메인이 따로
   * 보고 {@code LISTING_IMAGE_TOO_LARGE}로 안내한다(ADR-0041).
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handlePayloadTooLarge(MaxUploadSizeExceededException e) {
    return errorResponse(ErrorCode.PAYLOAD_TOO_LARGE);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException e) {
    return errorResponse(ErrorCode.METHOD_NOT_ALLOWED);
  }

  // 미매핑 경로(NoHandlerFoundException)·정적 리소스 미존재(NoResourceFoundException, Boot 3) 모두 404로.
  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
    return errorResponse(ErrorCode.RESOURCE_NOT_FOUND);
  }

  /**
   * <b>문서화된 UNIQUE 제약</b>의 중복 위반만 <b>409 {@code RESOURCE_CONFLICT}</b>로 번역한다. 종전에는 아래 {@link
   * #handleUnexpected}로 흘러 <b>500 {@code INTERNAL_ERROR}</b>가 됐는데, 그 status는 어느 스펙의 에러 카탈로그에도 없고
   * 클라이언트에게 <b>재시도하면 성공한다는 신호를 하나도 주지 않는다</b>.
   *
   * <p><b>왜 전역이어야 하는가 — 서비스 안의 try/catch로는 절반밖에 못 잡는다.</b> 위반이 드러나는 시점이 쓰기 종류마다 다르다.
   *
   * <ul>
   *   <li><b>메서드 안</b>: IDENTITY 전략의 INSERT는 식별자를 받아야 해서 {@code save} 호출에서 <b>즉시</b> 실행된다(웹 자격증명의
   *       {@code uq_local_accounts_email}).
   *   <li><b>커밋 시점</b>: 이미 있는 행의 UPDATE는 플러시까지 미뤄져 <b>{@code @Transactional} 프록시가 반환한 뒤</b> 터진다 —
   *       임대인 온보딩이 {@code users.phone_number}에 번호를 쓰는 것(V23 {@code uq_users_phone_number})이 정확히 이
   *       경우다. 서비스 메서드는 이미 정상 반환했으므로 그 안의 어떤 {@code catch}도 이 예외를 볼 수 없다.
   * </ul>
   *
   * <p>둘을 모두 덮는 지점은 트랜잭션 <b>바깥</b>인 여기뿐이다. 그리고 이 자리에서 낼 수 있는 코드는 도메인 무관한 공통 코드다 — {@code AUTH_*}로
   * 번역하면 커뮤니티·매물의 제약 위반까지 인증 에러라고 우기게 된다. 뜻을 좁혀 알려야 하는 충돌은 <b>미리 판정할 수 있는 충돌</b>이고, 그건 도메인이 자기 자리에서
   * 이미 하고 있다({@code BookingRepositoryImpl}은 {@code BOOKING_ALREADY_EXISTS}로 바꾸고, {@code
   * UserBlockServiceImpl}은 멱등이라 삼킨다) — 그렇게 지역에서 잡힌 예외는 애초에 여기까지 오지 않는다.
   *
   * <p><b>무조건 번역하지 않는 이유가 이 메서드의 핵심이다.</b> {@link DataIntegrityViolationException}은 UNIQUE 중복만이 아니라
   * <b>NOT NULL 위반·길이 초과·잘못된 FK</b>까지 같은 타입으로 온다. 그것들은 재시도해도 절대 성공하지 않는 <b>서버 버그</b>인데, 통째로 409로
   * 낮추면 클라이언트에게 "충돌이니 다시 보내라"고 거짓말을 하고 로그 레벨까지 WARN으로 내려가 <b>알림 임계값 아래로 사라진다</b>. 그래서 아래 {@link
   * #isDocumentedUniqueConflict} 판정을 통과하지 못한 위반은 종전 그대로 {@link #handleUnexpected}에 넘겨 <b>ERROR 로그 +
   * 500</b>으로 둔다.
   *
   * <p>이 코드가 실제로 필요한 경합은 <b>같은 번호의 웹 가입(US-1-11)과 앱 임대인 온보딩(US-1-15)이 거의 동시에 도착하는 경우</b>다. 두 흐름 모두
   * "그 번호의 계정이 있나"를 잠금 조회하지만 <b>아직 없는 행은 잠글 수 없어</b> 양쪽이 "없음"으로 판정할 수 있고, 그때 계정이 갈라지는 것을 막는 유일한 수단이
   * {@code uq_users_phone_number}다(ADR-0047 §5). 늦은 쪽은 여기서 409를 받고, <b>재시도하면 상대가 만든 계정을 발견해 정상
   * 연동·병합된다</b>. 문서상 이 코드가 나올 수 있는 자리도 그 두 엔드포인트뿐이다(스펙 §1-3·§5-2).
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
      DataIntegrityViolationException e) {
    if (!isDocumentedUniqueConflict(e)) {
      // 알 수 없는 제약 위반 = 서버 버그로 취급한다. status도 로그 레벨도 낮추지 않는다.
      return handleUnexpected(e);
    }
    log.warn("Data integrity violation translated to {}", ErrorCode.RESOURCE_CONFLICT.getCode(), e);
    return errorResponse(ErrorCode.RESOURCE_CONFLICT);
  }

  /**
   * 409로 번역해도 되는 <b>문서화된 UNIQUE 제약</b>의 중복 위반인지 — 원인 사슬에 Hibernate {@link
   * ConstraintViolationException}이 있고 그 <b>제약 이름</b>이 {@link #RETRYABLE_UNIQUE_CONSTRAINTS}에 있을 때만
   * 참이다.
   *
   * <p><b>SQLState(23000/23505)로 판정하지 않는다 — MySQL에서는 너무 넓다.</b> MySQL은 UNIQUE 중복(오류 1062)뿐 아니라 NOT
   * NULL 위반(1048)에도 SQLState {@code 23000}을 붙이므로, SQLState만 보면 정확히 <b>가려내려던 서버 버그를 다시 409로
   * 끌어들인다</b>.
   *
   * <p><b>제약 이름이 신뢰할 수 있는 신호인 근거</b>(Hibernate 6.6 · MySQL 8+ 기준으로 실제 변환 코드를 읽어 확인했다):
   *
   * <ul>
   *   <li>{@code MySQLDialect.buildSQLExceptionConversionDelegate()}는 <b>오류 코드 1062(Duplicate
   *       entry)에서만</b> {@code ConstraintKind.UNIQUE}인 {@code ConstraintViolationException}을 만들고, 그
   *       제약 이름을 {@code " for key '…'"} 템플릿으로 예외 메시지에서 뽑는다.
   *   <li>NOT NULL 위반은 그 분기에 걸리지 않고 {@code SQLExceptionTypeDelegate}로 내려가는데, 그 경로도 같은 추출기를 쓰므로
   *       <b>{@code " for key '"} 조각이 없는 메시지</b>("Column 'x' cannot be null")에서는 제약 이름이 {@code
   *       null}이다. 길이 초과({@code DataException})도 마찬가지다. 즉 <b>아래 이름들과 매칭되는 경로는 중복
   *       INSERT/UPDATE뿐</b>이다.
   *   <li>MySQL 8.0.19+는 이름을 {@code 테이블.인덱스}로 붙여 주므로 마지막 마디만 비교한다.
   * </ul>
   *
   * <p>제약 이름이 비어 있으면 예외 메시지 사슬을 한 번 더 훑는다 — Spring의 {@code HibernateJpaDialect}가 {@code constraint
   * [이름]}을 메시지에 실어 주므로 추출기가 무엇을 놓쳐도 이름 자체는 남아 있다. 어느 쪽에서도 이름을 찾지 못하면 <b>번역하지 않는다</b>(모르면 500이 안전한
   * 기본값이다).
   */
  private static boolean isDocumentedUniqueConflict(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && (matchesDocumentedConstraint(violation.getConstraintName())
              || containsDocumentedConstraint(exception))) {
        return true;
      }
      if (cause == cause.getCause()) {
        return false;
      }
    }
    return false;
  }

  /** MySQL 8은 {@code 테이블.인덱스}로 알려 주므로 마지막 마디를 대소문자 무시로 비교한다. */
  private static boolean matchesDocumentedConstraint(String constraintName) {
    if (constraintName == null) {
      return false;
    }
    String name = constraintName.substring(constraintName.lastIndexOf('.') + 1);
    return RETRYABLE_UNIQUE_CONSTRAINTS.contains(name.toLowerCase(Locale.ROOT));
  }

  /** 추출기가 이름을 못 뽑았을 때의 예비 판정 — 메시지 사슬에 제약 이름이 문자열로 남아 있다. */
  private static boolean containsDocumentedConstraint(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null) {
        String lowered = message.toLowerCase(Locale.ROOT);
        if (RETRYABLE_UNIQUE_CONSTRAINTS.stream().anyMatch(lowered::contains)) {
          return true;
        }
      }
      if (cause == cause.getCause()) {
        return false;
      }
    }
    return false;
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    log.error("Unhandled exception", e);
    return errorResponse(ErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ApiResponse<Void>> errorResponse(ErrorCode ec) {
    AccessLogContext.errorCode(ec.getCode());
    return ResponseEntity.status(ec.getHttpStatus())
        .body(ApiResponse.error(ec.getCode(), resolveMessage(ec, ec.getDefaultMessage())));
  }

  /** ErrorCode 코드 키로 요청 locale의 메시지를 해소한다(키/언어 부재 시 {@code fallback}). */
  private String resolveMessage(ErrorCode ec, String fallback) {
    return messageSource.getMessage(ec.getCode(), null, fallback, LocaleContextHolder.getLocale());
  }

  /**
   * {@code errors[].reason}도 {@code message}와 같이 요청 locale로 번역한다(ADR-0030). {@link FieldError}는
   * {@code MessageSourceResolvable}이라 {@code NotBlank.<객체>.<필드>} → {@code NotBlank.<필드>} → {@code
   * NotBlank} 순으로 번들 키를 찾고, 없으면 제약의 기본 메시지로 폴백한다.
   *
   * <p>번들 키를 두지 않으면 Bean Validation의 영문 기본 문구(<i>must not be blank</i>)나 바인딩 실패의 내부 문구(<i>Failed to
   * convert property value of type…</i>)가 그대로 응답에 나간다 — 후자는 사용자에게 보여줄 수 없는 구현 세부다(#151).
   */
  private FieldErrorDetail toFieldErrorDetail(FieldError fe) {
    return new FieldErrorDetail(
        fe.getField(), messageSource.getMessage(fe, LocaleContextHolder.getLocale()));
  }
}
