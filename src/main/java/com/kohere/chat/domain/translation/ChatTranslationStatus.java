package com.kohere.chat.domain.translation;

/** 번역 작업의 DB 처리 상태다. PENDING과 PROCESSING은 서버 내부 상태이며 화면 문구가 아니다. */
public enum ChatTranslationStatus {
  /** 원문과 같은 트랜잭션에 저장됐고 Worker가 아직 가져가지 않은 상태. */
  PENDING,
  /** 한 Worker가 lease를 확보하고 Google 호출을 진행 중인 상태. */
  PROCESSING,
  /** 번역문을 정상적으로 저장한 최종 상태. */
  SUCCEEDED,
  /** 감지된 원문 언어와 대상 언어가 같아 번역문이 필요 없는 최종 상태. */
  NOT_REQUIRED,
  /** 재시도 한도 또는 영구 오류 때문에 번역을 완료하지 못한 최종 상태. */
  FAILED
}
