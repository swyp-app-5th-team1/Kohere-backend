package com.kohere.chat.presentation.stomp.dto;

/**
 * 프런트엔드에 전달하는 번역 작업의 최종 상태다.
 *
 * <p>내부 스케줄링 상태인 PENDING과 PROCESSING은 화면 계약이 아니므로 포함하지 않는다. 어느 상태에서도 원문 메시지의 저장 성공 여부는 바뀌지 않는다.
 */
public enum ChatTranslationResultStatus {
  /** 번역문이 저장됐으며 {@code translatedContent}를 표시할 수 있음. */
  SUCCEEDED,
  /** 원문과 대상 언어가 같아 별도 번역문이 필요하지 않음. */
  NOT_REQUIRED,
  /** 허용된 재시도를 마쳤지만 번역하지 못함. 원문은 계속 사용할 수 있음. */
  FAILED
}
