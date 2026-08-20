package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.errorNull;
import static com.kohere.docs.ApiDocsFields.field;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;

import java.util.List;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;

/** Swagger <b>Chats</b> 태그에서 사용하는 문의 채팅방 생성 오퍼레이션의 설명과 필드 계약이다. */
public final class ChatDocsFields {

  public static final String INQUIRY_SUMMARY = "매물 문의 채팅방 열기";

  public static final String INQUIRY_DESCRIPTION =
      """
      공개 매물의 임대인과 로그인 세입자가 사용할 1:1 채팅방을 조회하거나 생성한다.

      **요청 방식**

      - 요청 본문은 없다.
      - 세입자 ID는 액세스 토큰에서, 임대인 ID는 매물 정본에서 서버가 결정한다. 사용자 ID를 요청으로 받지 않는다.
      - 같은 `listingId`·세입자·임대인 조합은 언제나 같은 `chatRoomId`를 사용한다.

      **응답 방식**

      - 새 방을 만든 경우 `201 Created`, `created=true`다.
      - 기존 방을 반환한 경우 `200 OK`, `created=false`다.
      - 앱은 두 경우 모두 `chatRoomId`로 채팅방 화면을 연다.
      - 이 응답은 프로필 이미지나 매물 대표 이미지를 제공하지 않는다. 채팅방 목록의 상대 이미지는 앱이 기본 아이콘으로 표시한다.
      - 매물 신청 완료 뒤 표시하는 `BOOKING_CARD`의 대표 이미지는 별도 카드 payload에 포함된다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `FORBIDDEN` | 임대인 등 비세입자가 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 |
      | 403 | `CHAT_UNAVAILABLE` | 요청자와 매물 소유자 사이 어느 방향이든 차단 관계가 있음 |
      | 404 | `LISTING_NOT_FOUND` | 매물이 없거나 비공개·삭제됨 |
      | 422 | `CHAT_SELF_INQUIRY_NOT_ALLOWED` | 본인 소유 매물에 문의 |
      """;

  public static final String[] INQUIRY_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] INQUIRY_403 = {
    "FORBIDDEN", "AUTH_ONBOARDING_REQUIRED", "CHAT_UNAVAILABLE"
  };
  public static final String[] INQUIRY_404 = {"LISTING_NOT_FOUND"};
  public static final String[] INQUIRY_422 = {"CHAT_SELF_INQUIRY_NOT_ALLOWED"};

  private ChatDocsFields() {}

  /** 경로의 매물 ID는 MongoDB ObjectId 문자열이며 요청 본문은 별도로 없다. */
  public static ParameterDescriptor[] inquiryPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("listingId").description("문의할 공개 매물 ID(ObjectId 24자리 hex)")
    };
  }

  /** 200과 201이 같은 JSON 구조를 사용하고 {@code created} 값만 다르다. */
  public static List<FieldDescriptor> inquiryResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field("data.chatRoomId", JsonFieldType.NUMBER, "앱이 열어야 할 서버 채팅방 ID"),
        field("data.created", JsonFieldType.BOOLEAN, "이번 요청에서 새 방을 만들었으면 true, 기존 방이면 false"),
        errorNull());
  }
}
