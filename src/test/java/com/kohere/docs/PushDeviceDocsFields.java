package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.enumField;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;

import com.kohere.notification.domain.PushPlatform;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.request.ParameterDescriptor;

/** FCM 기기 등록·삭제 API가 공유하는 Swagger 문구와 요청 기술자를 제공한다. */
public final class PushDeviceDocsFields {

  /** 등록 요청에서 문서화하는 입력 형식·검증 오류 코드다. */
  public static final String[] REGISTER_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};

  /** 등록 요청에서 문서화하는 인증 오류 코드다. */
  public static final String[] REGISTER_401 = {"UNAUTHENTICATED"};

  /** 등록 요청에서 문서화하는 온보딩 권한 오류 코드다. */
  public static final String[] REGISTER_403 = {"AUTH_ONBOARDING_REQUIRED"};

  /** 삭제 요청에서 문서화하는 경로 형식 오류 코드다. */
  public static final String[] DELETE_400 = {"MALFORMED_REQUEST"};

  /** 삭제 요청에서 문서화하는 인증 오류 코드다. */
  public static final String[] DELETE_401 = {"UNAUTHENTICATED"};

  /** 삭제 요청에서 문서화하는 온보딩 권한 오류 코드다. */
  public static final String[] DELETE_403 = {"AUTH_ONBOARDING_REQUIRED"};

  /** FCM 기기 등록·갱신 오퍼레이션 제목이다. */
  public static final String REGISTER_SUMMARY = "FCM 기기 등록·갱신";

  /** FCM 기기 등록·갱신 오퍼레이션 설명이다. */
  public static final String REGISTER_DESCRIPTION =
      "앱 실행·로그인 또는 FCM 토큰 갱신 시 호출합니다. 같은 installationId는 새 행을 만들지 않고 현재 사용자와 토큰으로 갱신하며, 성공 응답은 본문 없는 204입니다.";

  /** FCM 기기 삭제 오퍼레이션 제목이다. */
  public static final String DELETE_SUMMARY = "FCM 기기 삭제";

  /** FCM 기기 삭제 오퍼레이션 설명이다. */
  public static final String DELETE_DESCRIPTION =
      "로그아웃 시 현재 앱 installation을 푸시 발송 대상에서 제거합니다. 이미 없거나 현재 사용자의 기기가 아니어도 멱등하게 204를 반환합니다.";

  private PushDeviceDocsFields() {}

  /** 등록과 삭제 경로가 공유하는 앱 설치본 UUID 파라미터를 설명한다. */
  public static ParameterDescriptor[] installationPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("installationId").description("앱이 최초 실행 때 생성해 Keychain 등에 보관하는 설치본 UUID")
    };
  }

  /** 등록 요청의 현재 FCM 토큰과 플랫폼 필드를 설명한다. */
  public static FieldDescriptor[] registerRequestFields() {
    return new FieldDescriptor[] {
      fieldWithPath("fcmToken").description("Firebase가 현재 앱 설치본에 발급한 FCM 토큰(1~1024자, 그대로 전송)"),
      enumField("platform", PushPlatform.class, "토큰 발급 플랫폼. 현재 허용값은 IOS")
    };
  }
}
