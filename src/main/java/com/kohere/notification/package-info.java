/**
 * 사용자 앱 설치본과 외부 푸시 전달을 소유하는 Notification Bounded Context다.
 *
 * <p>iOS 설치본·FCM 토큰의 저장 기반과 로그인 사용자의 등록·삭제 API를 제공한다. chat 공개 이벤트를 구독해 FCM을 발송하고 user 탈퇴 이벤트를 구독해
 * 토큰을 정리한다. 저장 모델과 Firebase SDK 타입은 다른 모듈에 공개하지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Notification",
    allowedDependencies = {"common", "chat"})
package com.kohere.notification;
