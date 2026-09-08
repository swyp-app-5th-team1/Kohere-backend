package com.kohere.diagnosis.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageInfo;
import java.util.Set;

/**
 * 진단 조회의 페이지·정렬 파라미터 검증. 같은 본문이 조회 경로마다 사본으로 있었고 상한도 리터럴이라, 한쪽만 고쳐지면 같은 파라미터가 경로에 따라 다르게 거절된다.
 *
 * <p>메시지 키와 인자 순서는 바꾸지 않는다 — {@code errors[]} 페이로드가 곧 공개 계약이고 문서 스니펫이 그 위에 서 있다.
 */
final class DiagnosisPageRequests {

  /** 한 페이지 최대 크기. 저장소도 같은 값으로 한 번 더 깎으므로 여기를 올려도 실제 반환 수는 늘지 않는다. */
  private static final int MAX_PAGE_SIZE = 100;

  private DiagnosisPageRequests() {}

  static void validatePage(int page, int size) {
    if (page < 0) {
      throw new InvalidInputException("page", "validation.min", 0, page);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidInputException("size", "validation.range", 1, MAX_PAGE_SIZE, size);
    }
  }

  static void validateSort(String sort, Set<String> allowedKeys) {
    if (sort == null || sort.isBlank()) {
      return;
    }
    String[] parts = sort.split(",");
    String key = parts[0].trim();
    if (!allowedKeys.contains(key)) {
      throw new InvalidInputException("sort", "validation.sortKey", key);
    }
    if (parts.length > 1) {
      String direction = parts[1].trim().toLowerCase();
      if (!direction.equals("asc") && !direction.equals("desc")) {
        throw new InvalidInputException("sort", "validation.sortDirection", parts[1]);
      }
    }
  }

  /** sort 문자열의 방향(asc)을 해석한다(미지정·desc면 false). 검증은 {@link #validateSort}가 선행한다. */
  static boolean isAscending(String sort) {
    if (sort == null || sort.isBlank()) {
      return false;
    }
    String[] parts = sort.split(",");
    return parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc");
  }

  static PageInfo pageInfo(int page, int size, long total) {
    int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
    boolean hasNext = (page + 1) < totalPages;
    return new PageInfo(page, size, total, totalPages, hasNext);
  }
}
