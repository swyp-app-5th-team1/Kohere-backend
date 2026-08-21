package com.kohere.report.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.report.domain.ReportEvidenceSnapshot;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 신고 증거 JSON이 저장 뒤 바뀌지 않았는지 확인할 SHA-256 값을 만든다. */
@Component
@RequiredArgsConstructor
public class ReportEvidenceHasher {

  private final ObjectMapper objectMapper;

  /**
   * 저장할 record를 Spring 공용 ObjectMapper로 정해진 순서대로 직렬화한 바이트를 해시한다.
   *
   * <p>필드 순서가 고정된 record만 사용하고 Map을 쓰지 않아 같은 스냅샷이 항상 같은 JSON과 해시를 만들게 한다.
   */
  public String hash(ReportEvidenceSnapshot snapshot) {
    try {
      byte[] jsonBytes = objectMapper.writeValueAsBytes(snapshot);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(jsonBytes);
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException exception) {
      // 서버가 만든 고정 record를 직렬화하지 못하는 것은 사용자 입력 오류가 아니라 구현·설정 오류다.
      throw new IllegalStateException("신고 증거 JSON을 직렬화할 수 없습니다.", exception);
    } catch (NoSuchAlgorithmException exception) {
      // 모든 표준 Java 구현은 SHA-256을 제공한다. 실행 환경이 깨진 경우만 이 예외가 발생한다.
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
    }
  }
}
