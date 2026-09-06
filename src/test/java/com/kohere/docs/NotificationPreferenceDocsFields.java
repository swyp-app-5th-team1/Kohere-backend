package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.errorNull;
import static com.kohere.docs.ApiDocsFields.field;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;

import java.util.List;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;

/** 사용자 단위 채팅 푸시 설정 API가 공유하는 Swagger 문구와 요청·응답 기술자를 제공한다. */
public final class NotificationPreferenceDocsFields {

  /** 설정 조회 요청에서 문서화하는 인증 오류 코드다. */
  public static final String[] GET_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  /** 설정 조회 요청에서 문서화하는 온보딩 권한 오류 코드다. */
  public static final String[] GET_403 = {"AUTH_ONBOARDING_REQUIRED"};

  /** 설정 변경 요청에서 문서화하는 입력 오류 코드다. */
  public static final String[] PATCH_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};

  /** 설정 변경 요청에서 문서화하는 인증 오류 코드다. */
  public static final String[] PATCH_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  /** 설정 변경 요청에서 문서화하는 온보딩 권한 오류 코드다. */
  public static final String[] PATCH_403 = {"AUTH_ONBOARDING_REQUIRED"};

  /** 현재 채팅 푸시 설정 조회 오퍼레이션 제목이다. */
  public static final String GET_SUMMARY = "채팅 푸시 수신 설정 조회";

  /** 현재 채팅 푸시 설정 조회 오퍼레이션 설명이다. */
  public static final String GET_DESCRIPTION =
      """
      현재 계정에 적용되는 **채팅 푸시 알림 수신 설정**을 조회한다.

      **프론트 호출 시점과 화면 처리**

      1. 사용자가 로그인한 뒤 알림 설정 화면을 열 때 호출한다.
      2. 응답의 `data.chatPushEnabled`를 채팅 알림 스위치에 그대로 표시한다.
      3. 다른 기기에서 설정을 바꿀 수도 있으므로 화면을 다시 열 때 서버 값을 다시 조회한다.

      **응답값 의미**

      | `data.chatPushEnabled` | 백엔드 동작 |
      |---|---|
      | `true` | 이후 생성되는 새 채팅 메시지의 FCM 푸시를 등록된 기기로 발송한다. |
      | `false` | 채팅 메시지는 정상 저장하지만 FCM 푸시 발송만 생략한다. |

      - 설정을 한 번도 변경하지 않은 신규·기존 사용자도 기본값 `true`를 받는다.
      - 기본값을 조회했더라도 서버는 불필요한 설정 행을 만들지 않는다.
      - 이 값은 채팅방별 설정이 아니라 **현재 계정 전체**의 설정이며, 같은 계정으로 로그인한 모든 기기에 공통 적용된다.

      **iOS 알림 권한과의 차이**

      이 API는 Kohere 백엔드의 채팅 푸시 발송 여부만 반환한다. `true`여도 사용자가 iOS 설정에서 앱 알림 권한을 껐다면 휴대폰 알림은 표시되지 않는다. iOS 권한 상태는 앱이 별도로 확인한다.

      **에러 코드**

      | status | `error.code` | 프론트 처리 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 로그인 화면으로 이동하거나 인증을 다시 시작한다. |
      | 401 | `TOKEN_EXPIRED` | 토큰을 재발급한 뒤 같은 요청을 다시 호출한다. |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩을 완료한 뒤 다시 호출한다. |
      """;

  /** 현재 채팅 푸시 설정 변경 오퍼레이션 제목이다. */
  public static final String PATCH_SUMMARY = "채팅 푸시 수신 설정 변경";

  /** 현재 채팅 푸시 설정 변경 오퍼레이션 설명이다. */
  public static final String PATCH_DESCRIPTION =
      """
      알림 설정 화면에서 사용자가 선택한 **계정 전체의 채팅 푸시 수신 여부**를 저장한다.

      **프론트 호출 흐름**

      1. 사용자가 채팅 알림 스위치를 변경할 때 `chatPushEnabled`에 새 값을 넣어 호출한다.
      2. `200 OK`를 받으면 응답의 `data.chatPushEnabled`를 최종 스위치 상태로 반영한다.
      3. 요청이 실패하면 화면을 기존 값으로 되돌리거나 GET API로 서버 값을 다시 조회한다.

      **요청값에 따른 동작**

      | 요청값 | 백엔드 동작 |
      |---|---|
      | `true` | 이후 생성되는 새 채팅부터 FCM 푸시 발송을 재개한다. |
      | `false` | 이후 채팅 메시지는 정상 저장하되 FCM 푸시 발송만 생략한다. |

      - `false`로 변경해도 FCM 토큰과 앱 설치 정보는 삭제하지 않는다. 앱은 토큰 등록·갱신 흐름을 그대로 유지한다.
      - 다시 `true`로 변경하면 별도의 기기 재등록 없이 이후 푸시부터 받을 수 있다.
      - 수신을 거부한 기간에 생략된 푸시는 나중에 다시 발송하지 않는다. 채팅방 메시지 이력은 별도로 정상 조회할 수 있다.
      - 설정은 로그아웃하거나 앱을 종료해도 서버에 유지되며, 같은 계정의 모든 기기에 공통 적용된다.
      - 이 값은 iOS 시스템 알림 권한과 별개다. 백엔드 설정과 iOS 권한이 모두 허용 상태여야 시스템 알림을 볼 수 있다.

      **입력 규칙**

      - `chatPushEnabled`는 필수 boolean이다. 필드를 빼거나 `null`을 보내면 `400 INVALID_INPUT`이다.
      - JSON 문법을 해석할 수 없으면 `400 MALFORMED_REQUEST`다.

      **에러 코드**

      | status | `error.code` | 프론트 처리 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 필수 boolean 값을 넣어 다시 요청한다. |
      | 400 | `MALFORMED_REQUEST` | JSON 형식과 `Content-Type: application/json`을 확인한다. |
      | 401 | `UNAUTHENTICATED` | 로그인 화면으로 이동하거나 인증을 다시 시작한다. |
      | 401 | `TOKEN_EXPIRED` | 토큰을 재발급한 뒤 같은 변경 요청을 다시 호출한다. |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩을 완료한 뒤 다시 호출한다. |
      """;

  private NotificationPreferenceDocsFields() {}

  /** 설정 변경 요청의 필수 boolean 필드를 설명한다. */
  public static FieldDescriptor[] updateRequestFields() {
    return new FieldDescriptor[] {
      fieldWithPath("chatPushEnabled")
          .description(
              "현재 계정의 모든 채팅 푸시 수신 여부. true면 이후 푸시를 발송하고 false면 발송만 생략한다(필수 boolean, null 불가)")
    };
  }

  /** 조회와 변경이 공유하는 성공 응답 봉투와 설정 필드를 설명한다. */
  public static List<FieldDescriptor> preferenceResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data", JsonFieldType.OBJECT, "서버가 현재 계정에 적용하는 알림 수신 설정"),
        field(
            "data.chatPushEnabled",
            JsonFieldType.BOOLEAN,
            "현재 계정의 모든 채팅 푸시 수신 여부. 설정 이력이 없으면 true이며 같은 계정의 모든 기기에 공통 적용"),
        errorNull());
  }
}
