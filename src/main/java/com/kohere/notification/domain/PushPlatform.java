package com.kohere.notification.domain;

/** 푸시 토큰을 발급한 앱 플랫폼이다. 첫 구현에서는 APNs로 전달되는 iOS만 허용한다. */
public enum PushPlatform {
  /** Firebase Cloud Messaging이 APNs를 거쳐 전달할 iOS 앱 설치본이다. */
  IOS
}
