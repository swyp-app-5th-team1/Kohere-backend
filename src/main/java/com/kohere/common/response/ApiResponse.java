package com.kohere.common.response;

import java.util.List;
import java.util.Map;

/**
 * 모든 API 응답을 감싸는 공통 래퍼. 성공·에러 모두 동일한 봉투(success/data/error)를 사용한다.
 *
 * <p>규약: docs/api/api-design-guide.md §3, docs/api/error-response-guide.md §1. 컨트롤러는 엔티티가 아닌 DTO를
 * 담아 반환한다.
 */
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static ApiResponse<Void> error(String code, String message) {
    return new ApiResponse<>(false, null, new ErrorResponse(code, message, List.of(), null));
  }

  public static ApiResponse<Void> error(
      String code, String message, List<FieldErrorDetail> errors) {
    return new ApiResponse<>(false, null, new ErrorResponse(code, message, errors, null));
  }

  /**
   * 코드별 부가 데이터를 실은 실패 응답. {@code details}가 {@code null}이면 키 자체가 나가지 않으므로 위 두 팩터리와 결과가 같다.
   *
   * <p>어떤 키를 싣는지는 그 코드의 API 스펙이 정한다 — 스펙에 없는 데이터를 임의로 넣지 않는다(ADR-0004 Amended).
   */
  public static ApiResponse<Void> error(String code, String message, Map<String, Object> details) {
    return new ApiResponse<>(false, null, new ErrorResponse(code, message, List.of(), details));
  }
}
