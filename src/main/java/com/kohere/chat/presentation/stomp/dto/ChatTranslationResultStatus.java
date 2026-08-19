package com.kohere.chat.presentation.stomp.dto;

/** 프런트에 전달하는 최종 번역 상태. 내부 작업 상태인 PENDING과 PROCESSING은 포함하지 않는다. */
public enum ChatTranslationResultStatus {
  SUCCEEDED,
  NOT_REQUIRED,
  FAILED
}
