package com.kohere.chat.domain.translation;

/** REST와 STOMP가 프런트엔드에 공개하는 번역의 최종 결과다. 내부 작업 상태는 포함하지 않는다. */
public enum TranslationResultStatus {
  /** 번역문을 표시할 수 있음. */
  SUCCEEDED,
  /** 원문과 대상 언어가 같으므로 원문을 그대로 표시하면 됨. */
  NOT_REQUIRED,
  /** 자동 번역을 완료하지 못했으므로 원문을 표시해야 함. */
  FAILED
}
