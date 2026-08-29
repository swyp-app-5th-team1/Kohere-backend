package com.kohere.notification.application;

import java.util.List;

/** 완성된 푸시 메시지를 외부 provider로 보내는 notification 모듈의 발송 포트다. */
public interface PushMessageSender {

  /**
   * 메시지의 모든 FCM 토큰에 발송을 시도하고 입력 토큰 순서와 같은 결과를 반환한다.
   *
   * @param message 표시 문구·data·대상 토큰을 가진 발송 요청
   * @return 각 입력 토큰에 대응하는 발송 결과
   */
  List<PushSendResult> send(PushMessage message);
}
