package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.enumField;
import static com.kohere.docs.ApiDocsFields.errorNull;
import static com.kohere.docs.ApiDocsFields.field;
import static com.kohere.docs.ApiDocsFields.optEnumField;
import static com.kohere.docs.ApiDocsFields.optField;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;

import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.TranslationProvider;
import java.util.List;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;

/** Swagger <b>Chats</b> 태그에서 사용하는 문의 채팅방 생성 오퍼레이션의 설명과 필드 계약이다. */
public final class ChatDocsFields {

  public static final String INQUIRY_SUMMARY = "매물 문의 채팅방 열기";

  public static final String INQUIRY_DESCRIPTION =
      """
      매물 화면에서 **문의하기**를 눌렀을 때 1:1 채팅방을 열기 위한 API다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 필수. 온보딩을 완료한 세입자의 access token을 보낸다.

      **프론트 요청 방법**

      - 요청 본문은 없다.
      - 매물 목록 또는 매물 상세 응답에서 받은 `listingId`만 URL에 넣는다.
      - 프론트가 세입자 ID나 임대인 ID를 보낼 필요는 없다. 서버가 access token과 매물 정보로 두 사용자를 찾는다.
      - 같은 매물에서 같은 세입자와 임대인이 다시 문의하면 새 채팅방을 만들지 않고 기존 채팅방을 반환한다.

      **프론트 처리 방법**

      - 새 방을 만든 경우 `201 Created`, `created=true`다.
      - 기존 방을 반환한 경우 `200 OK`, `created=false`다.
      - 두 응답의 JSON 구조는 같다. 프론트는 두 경우 모두 `chatRoomId`로 채팅방 화면을 열면 된다.
      - `created`는 새 방인지 구분하는 참고값이다. 화면 이동 여부를 이 값으로 나누지 않는다.
      - 이 응답은 프로필 이미지나 매물 대표 이미지를 제공하지 않는다. 채팅방 목록의 상대 이미지는 앱이 기본 아이콘으로 표시한다.
      - 매물 신청 완료 뒤 표시하는 `BOOKING_CARD`의 대표 이미지는 메시지 이력 응답의 `bookingCard` 안에 별도로 포함된다.

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

  public static final String ROOM_LIST_SUMMARY = "내 채팅방 목록 조회";

  public static final String ROOM_LIST_DESCRIPTION =
      """
      채팅 탭을 열었을 때 보여 줄 **내 1:1 채팅방 목록**을 최근 활동 순으로 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 필수. 온보딩을 완료한 사용자의 access token을 보낸다.

      **프론트 요청 방법**

      - 사용자 ID는 access token에서 확인하므로 별도로 보내지 않는다.
      - 첫 요청은 `page=0`으로 보낸다. `page`를 생략해도 기본값은 0이다.
      - `size`는 한 번에 받을 채팅방 개수다. 기본값은 20이고 1~100까지 보낼 수 있다.
      - 응답의 `page.hasNext=true`이면 다음 요청에서 `page`를 1 증가시킨다.
      - 사용자가 삭제해 숨긴 채팅방은 목록에서 제외한다.

      ```http
      GET /api/v1/chat-rooms?page=0&size=20
      GET /api/v1/chat-rooms?page=1&size=20  // 다음 페이지
      ```

      **프론트 표시 방법**

      - 앱의 채팅 목록 한 줄에 필요한 채팅방 ID, 내 역할, 매물 제목·주소, 상대 ID·이름, 마지막 메시지와 차단 여부를 반환한다.
      - 상대 프로필 이미지와 일반 채팅방용 매물 이미지는 반환하지 않는다. 앱은 상대방 자리에 기본 프로필 아이콘을 표시한다.
      - 빈 채팅방이나 사용자가 삭제한 과거 메시지만 남은 채팅방은 `lastMessage=null`이다.
      - 마지막 메시지가 `TEXT`이면 `preview`에 표시할 문자열을 반환한다. 현재 단계에서는 저장된 원문이며 자동 번역 연결 뒤에는 수신자용 번역본을 우선한다.
      - 마지막 메시지가 `BOOKING_CARD`이면 `preview=null`이다. 앱이 `myRole`과 `ko/en` UI 문구로 “신청이 접수되었습니다” 또는 “새로운 입주 신청이 도착했습니다”를 표시한다.
      - `blocked=true`이면 두 사용자 사이 어느 방향으로든 차단 관계가 있어 새 메시지를 보낼 수 없다. 차단 방향은 노출하지 않는다.
      - 채팅방이 하나도 없으면 `content=[]`, `totalElements=0`, `totalPages=0`, `hasNext=false`다. 빈 화면으로 처리하면 된다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `page`가 음수이거나 `size`가 1~100 밖 |
      | 400 | `MALFORMED_REQUEST` | `page`·`size`가 정수가 아님 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 |
      """;

  public static final String[] ROOM_LIST_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] ROOM_LIST_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] ROOM_LIST_403 = {"AUTH_ONBOARDING_REQUIRED"};

  public static final String ROOM_DETAIL_SUMMARY = "채팅방 기본 정보 조회";

  public static final String ROOM_DETAIL_DESCRIPTION =
      """
      채팅방 화면을 열 때 필요한 매물·상대·역할·차단 정보를 한 건 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 필수. 온보딩을 완료한 사용자의 access token을 보낸다.

      **언제 사용하는가**

      - 채팅방 목록에서 한 항목을 눌렀을 때
      - 푸시 알림이나 딥링크의 `roomId`로 채팅방 화면을 바로 열 때
      - 앱 재실행·새로고침 뒤 채팅방 헤더를 다시 만들 때

      **프론트 요청 방법**

      - 채팅방 목록 또는 문의하기 응답에서 받은 `chatRoomId`를 URL의 `roomId`에 그대로 넣는다.
      - `roomId`는 프론트가 생성하는 UUID가 아니라 서버가 반환한 숫자다.

      **프론트 표시 방법**

      - 실제 대화 메시지는 포함하지 않는다. 메시지는 `/api/v1/chat-rooms/{roomId}/messages`에서 별도로 조회한다.
      - 매물 제목·주소는 채팅방 생성 시 저장한 사본이라 원본 매물이 비공개·삭제돼도 대화 맥락을 유지한다.
      - 상대 프로필 이미지와 일반 채팅방용 매물 이미지는 반환하지 않는다. 앱은 기본 프로필 아이콘을 사용한다.
      - `myRole`은 앱이 BOOKING_CARD를 임차인용 또는 임대인용으로 표시할 때 사용한다.
      - `blocked=true`이면 어느 방향으로든 차단 관계가 있어 새 메시지를 보낼 수 없다. 누가 차단했는지는 노출하지 않는다.

      **보안 규칙**

      - 사용자 ID는 액세스 토큰에서 결정한다.
      - 방이 없거나, 요청자가 참여자가 아니거나, 요청자에게 숨겨진 방이면 모두 `404 CHAT_ROOM_NOT_FOUND`다.
      - 같은 404를 사용해 제3자가 roomId를 바꿔 보며 채팅방 존재 여부를 확인하지 못하게 한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `MALFORMED_REQUEST` | `roomId`가 숫자가 아님 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 |
      | 404 | `CHAT_ROOM_NOT_FOUND` | 방 없음·비참여자·요청자에게 숨겨진 방 |
      """;

  public static final String[] ROOM_DETAIL_400 = {"MALFORMED_REQUEST"};
  public static final String[] ROOM_DETAIL_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] ROOM_DETAIL_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] ROOM_DETAIL_404 = {"CHAT_ROOM_NOT_FOUND"};

  public static final String MESSAGE_HISTORY_SUMMARY = "채팅방 메시지 이력 조회";

  public static final String MESSAGE_HISTORY_DESCRIPTION =
      """
      채팅방 화면에 표시할 **저장된 대화 메시지**를 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 필수. 온보딩을 완료한 사용자의 access token을 보낸다.

      **프론트 요청 전 준비**

      - 채팅방 목록·상세 또는 문의하기 응답에서 받은 `chatRoomId`를 URL의 `roomId`에 넣는다.
      - 처음 들어갈 때는 `cursor`와 `afterMessageId`를 모두 생략한다.

      **언제 사용하는가**

      - 채팅방에 처음 들어갈 때: `cursor` 없이 호출해 최근 메시지를 가져온다.
      - 위로 스크롤할 때: 이전 응답의 `nextCursor`를 이번 요청의 `cursor`에 넣어 과거 메시지를 더 가져온다.
      - WebSocket 재연결 뒤: 마지막으로 연속 확인한 `messageId`를 `afterMessageId`로 보내 끊긴 동안 저장된 메시지를 보충한다.
      - 재연결 시 앱이 자동으로 한 번 이상 호출할 수 있지만, 일정 간격으로 계속 호출하는 polling API는 아니다.

      **신청 완료 뒤 BOOKING_CARD를 받는 흐름**

      1. 앱이 기존 `POST /api/v1/listings/{listingId}/bookings`로 신청을 저장한다.
      2. 앱이 `POST /api/v1/listings/{listingId}/inquiries`로 같은 매물의 `chatRoomId`를 받는다.
      3. 앱이 이 메시지 이력 API를 호출한다.
      4. 서버가 저장한 `BOOKING_CARD`가 일반 TEXT와 같은 `content[]`에 시간순으로 함께 반환된다.

      BOOKING_CARD를 "채팅방에 전달"한다는 말은 프론트가 카드 전송 API를 호출한다는 뜻이 아니다. 백엔드가 예약 이벤트를 처리해
      `chat_messages`에 저장하고, 이 API는 이미 저장된 카드를 읽어 프론트에 반환한다. 프론트는 `type=BOOKING_CARD`를 보고 카드 UI만 그린다.

      이벤트 처리는 예약 HTTP 응답 뒤 비동기로 진행된다. 신청 직후 첫 조회에 카드가 아직 없으면 짧게 기다린 뒤 이 API를 한 번 다시 조회할 수 있다.
      지속 polling은 필요하지 않으며, STOMP 단계가 연결되면 새 카드는 실시간 메시지 이벤트로도 받게 된다.

      **과거 메시지를 조회하는 가장 쉬운 흐름**

      1. 처음에는 `cursor` 없이 요청한다.

          ```http
          GET /api/v1/chat-rooms/10/messages?size=3
          ```

      2. 서버가 최근 메시지와 다음 조회 위치를 반환한다. 아래 예시는 전체 응답 중 `data` 내부만 줄여서 표시한 것이다.

          ```json
          {
            "content": [
              { "messageId": 105 },
              { "messageId": 104 },
              { "messageId": 103 }
            ],
            "nextCursor": "103",
            "hasNext": true
          }
          ```

      3. 사용자가 위로 스크롤하면 `nextCursor` 값을 다음 요청의 `cursor`에 그대로 넣는다.

          ```http
          GET /api/v1/chat-rooms/10/messages?cursor=103&size=3
          ```

          이 요청은 **“10번 채팅방에서 103번보다 오래된 메시지를 최대 3개 주세요”**라는 뜻이다.

      4. `hasNext=false`가 되면 더 오래된 메시지가 없으므로 추가 요청을 멈춘다.

      ```text
      이전 응답의 nextCursor → 다음 요청의 cursor
      ```

      프론트는 cursor를 직접 계산하지 않는다. 서버가 반환한 `nextCursor`를 문자열 그대로 사용하면 된다.

      **파라미터를 쉽게 정리하면**

      | 값 | 누가 보내는가 | 의미 |
      |---|---|---|
      | `cursor` | 프론트 → 서버 | 이 메시지 번호보다 오래된 대화를 달라는 기준점 |
      | `size` | 프론트 → 서버 | 한 번에 받을 최대 메시지 개수. 기본 30, 허용 1~100 |
      | `nextCursor` | 서버 → 프론트 | 다음 과거 조회의 `cursor`에 그대로 넣을 값 |
      | `hasNext` | 서버 → 프론트 | 과거 메시지가 더 남아 있으면 true |

      **afterMessageId는 언제 사용하는가**

      `afterMessageId`는 위로 스크롤할 때 사용하지 않는다. WebSocket 연결이 끊겼다가 다시 연결됐을 때 놓친 새 메시지를 보충하는 용도다.

      ```http
      GET /api/v1/chat-rooms/10/messages?afterMessageId=150&size=100
      ```

      위 요청은 **“150번 이후에 저장된 새 메시지를 주세요”**라는 뜻이다. `cursor`와 조회 방향이 반대이므로 두 값을 한 요청에 함께 보내면 400 오류가 발생한다.

      - `cursor` 조회 결과는 최신 메시지부터 내림차순이다.
      - `afterMessageId` 조회 결과는 앱이 순서대로 합칠 수 있도록 오래된 메시지부터 오름차순이다.

      **빈 결과 처리**

      - 표시할 메시지가 없으면 `content=[]`, `nextCursor=null`, `hasNext=false`다.
      - 오류가 아니라 정상 `200 OK`이므로 빈 대화 화면으로 처리한다.

      **메시지 종류**

      - `TEXT`: 사용자가 보낸 변경되지 않은 원문을 `originalContent`로 반환한다. `clientMessageId`는 전송 시 프런트가 생성한 UUID다.
      - `BOOKING_CARD`: 신청 완료 이벤트로 서버가 만든 카드다. `bookingCard`에 신청 시점 정보가 있고 `originalContent`·`clientMessageId`·`senderId`는 null이다.
      - 카드의 `listing.thumbnailUrl`은 신청 카드 상단 대표 이미지다. 채팅방 목록의 프로필 이미지와는 관계없다.
      - 카드 문구·항목명은 앱이 `myRole`과 지원 언어 `ko/en`에 맞춰 표시하고, 카드 payload 자체는 Google 자동 번역 대상이 아니다.
      - 자동 번역이 아직 저장되지 않았거나 실패한 경우에도 원문은 반환하며 `translation=null`이다.

      **삭제와 보안 규칙**

      - 개별 메시지를 삭제하는 기능은 아니다.
      - 사용자가 채팅방을 삭제했을 때 기록한 개인 경계 이하의 과거 메시지는 그 사용자에게만 반환하지 않는다.
      - 상대방의 이력은 변경하지 않는다.
      - 방이 없거나, 요청자가 참여자가 아니거나, 요청자에게 숨겨진 방이면 모두 `404 CHAT_ROOM_NOT_FOUND`다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 두 커서를 함께 사용, 0 이하 커서, size 범위 위반 |
      | 400 | `MALFORMED_REQUEST` | `roomId`·`cursor`·`afterMessageId`·`size` 중 숫자여야 하는 값이 숫자가 아님 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 |
      | 404 | `CHAT_ROOM_NOT_FOUND` | 방 없음·비참여자·요청자에게 숨겨진 방 |
      """;

  public static final String[] MESSAGE_HISTORY_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] MESSAGE_HISTORY_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] MESSAGE_HISTORY_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] MESSAGE_HISTORY_404 = {"CHAT_ROOM_NOT_FOUND"};

  private ChatDocsFields() {}

  /** 프론트가 매물 목록·상세에서 받은 ID를 그대로 사용할 수 있도록 출처까지 설명한다. */
  public static ParameterDescriptor[] inquiryPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("listingId").description("매물 목록 또는 매물 상세 응답에서 받은 listingId. 문의할 공개 매물의 ID")
    };
  }

  /** 200과 201이 같은 JSON 구조를 사용하고 {@code created} 값만 다르다. */
  public static List<FieldDescriptor> inquiryResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field(
            "data.chatRoomId",
            JsonFieldType.NUMBER,
            "열어야 할 채팅방 ID. 채팅방 상세·메시지 조회와 WebSocket 구독에 같은 값을 사용"),
        field(
            "data.created",
            JsonFieldType.BOOLEAN,
            "이번 요청으로 새 채팅방을 만들었으면 true, 이미 있던 채팅방을 반환했으면 false. 값과 관계없이 chatRoomId로 화면 이동"),
        errorNull());
  }

  /** 채팅방 목록의 offset 페이지 query 계약이다. */
  public static ParameterDescriptor[] roomListQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page")
          .optional()
          .description("가져올 페이지 번호. 첫 페이지는 0이며 기본값도 0. 다음 페이지는 1씩 증가"),
      parameterWithName("size").optional().description("한 페이지에 받을 채팅방 개수. 기본값 20, 허용 범위 1~100")
    };
  }

  /** 앱 목록 UI가 사용하는 필드와 nullable 조건을 Swagger schema에 고정한다. */
  public static List<FieldDescriptor> roomListResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field(
            "data.content[].chatRoomId",
            JsonFieldType.NUMBER,
            "이 항목을 눌렀을 때 열 채팅방 ID. 상세·메시지 조회와 WebSocket 구독에 사용"),
        enumField(
            "data.content[].myRole",
            ChatParticipantRole.class,
            "현재 로그인 사용자의 채팅 역할. TENANT=임차인, LANDLORD=임대인. 신청 카드 문구와 UI를 역할별로 표시할 때 사용"),
        field(
            "data.content[].listing.listingId",
            JsonFieldType.STRING,
            "이 채팅이 어떤 매물에 관한 것인지 나타내는 매물 ID"),
        field("data.content[].listing.title", JsonFieldType.STRING, "채팅 목록에 표시할 매물 제목"),
        field("data.content[].listing.address", JsonFieldType.STRING, "채팅 목록 또는 채팅방 헤더에 표시할 매물 주소"),
        field(
            "data.content[].counterpart.userId",
            JsonFieldType.NUMBER,
            "1:1 채팅 상대의 서버 사용자 ID. 표시 이름과 같은 상대를 식별하는 값"),
        field("data.content[].counterpart.displayName", JsonFieldType.STRING, "채팅 목록에 표시할 상대방 이름"),
        optField("data.content[].lastMessage", JsonFieldType.OBJECT, "사용자에게 보이는 마지막 메시지. 없으면 null"),
        optField(
            "data.content[].lastMessage.messageId",
            JsonFieldType.NUMBER,
            "마지막 메시지의 서버 번호. lastMessage가 null이면 이 필드도 없음"),
        optEnumField(
            "data.content[].lastMessage.type",
            MessageType.class,
            "마지막 메시지 종류. TEXT 또는 BOOKING_CARD이며 lastMessage가 null이면 없음"),
        optField(
            "data.content[].lastMessage.preview",
            JsonFieldType.STRING,
            "채팅 목록 두 번째 줄에 표시할 TEXT 문자열. BOOKING_CARD이면 null이므로 앱이 myRole에 맞는 신청 안내 문구 표시"),
        optField(
            "data.content[].lastMessage.sentAt",
            JsonFieldType.STRING,
            "마지막 메시지 저장 시각(ISO-8601 UTC)"),
        field(
            "data.content[].blocked",
            JsonFieldType.BOOLEAN,
            "어느 방향이든 차단 관계가 있어 새 메시지를 보낼 수 없으면 true"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 받은 페이지 번호. 첫 페이지는 0"),
        field("data.page.size", JsonFieldType.NUMBER, "요청한 한 페이지 최대 채팅방 개수"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "현재 사용자에게 보이는 전체 채팅방 개수"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 개수. 채팅방이 없으면 0"),
        field(
            "data.page.hasNext",
            JsonFieldType.BOOLEAN,
            "다음 페이지가 있으면 true. true일 때 page를 1 증가시켜 추가 조회"),
        errorNull());
  }

  /** 경로의 roomId는 listingId나 프런트 UUID가 아니라 서버가 발급한 숫자 채팅방 ID다. */
  public static ParameterDescriptor[] roomDetailPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("roomId")
          .description("채팅방 목록 또는 문의하기 응답에서 받은 chatRoomId. 프론트가 새로 생성하지 않고 그대로 사용")
    };
  }

  /** 채팅방 헤더와 역할별 카드 UI에 필요한 단건 응답 필드다. */
  public static List<FieldDescriptor> roomDetailResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field("data.chatRoomId", JsonFieldType.NUMBER, "현재 연 채팅방 ID. 메시지 조회와 WebSocket 구독에 사용"),
        enumField(
            "data.myRole",
            ChatParticipantRole.class,
            "현재 로그인 사용자의 채팅 역할. TENANT=임차인, LANDLORD=임대인. 신청 카드 문구와 UI 선택에 사용"),
        field("data.listing.listingId", JsonFieldType.STRING, "이 채팅이 어떤 매물에 관한 것인지 나타내는 매물 ID"),
        field("data.listing.title", JsonFieldType.STRING, "채팅방 상단에 표시할 매물 제목"),
        field("data.listing.address", JsonFieldType.STRING, "채팅방 상단에 표시할 매물 주소"),
        field("data.counterpart.userId", JsonFieldType.NUMBER, "현재 대화 상대의 서버 사용자 ID"),
        field("data.counterpart.displayName", JsonFieldType.STRING, "채팅방 상단에 표시할 상대방 이름"),
        field("data.blocked", JsonFieldType.BOOLEAN, "어느 방향이든 차단 관계가 있어 새 메시지를 보낼 수 없으면 true"),
        errorNull());
  }

  /** 경로의 roomId는 프런트 UUID가 아니라 서버가 발급한 숫자 채팅방 ID다. */
  public static ParameterDescriptor[] messageHistoryPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("roomId")
          .description("채팅방 목록·상세 또는 문의하기 응답에서 받은 chatRoomId. 이 채팅방의 메시지를 조회")
    };
  }

  /** 최근·과거·재연결 조회를 구분하는 커서 query 계약이다. */
  public static ParameterDescriptor[] messageHistoryQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("cursor")
          .optional()
          .description(
              "과거 메시지 조회 위치. 이전 응답의 nextCursor를 그대로 입력한다. 예: cursor=103은 103번보다 오래된 메시지 조회. 첫 요청에서는 생략하고 afterMessageId와 동시에 보내지 않는다."),
      parameterWithName("afterMessageId")
          .optional()
          .description(
              "WebSocket 재연결 뒤 누락 메시지 보충 전용. 앱이 마지막으로 연속 저장한 messageId를 입력하면 그보다 새 메시지를 반환. 위로 스크롤에는 사용하지 않으며 cursor와 동시 사용 금지"),
      parameterWithName("size").optional().description("한 번에 받을 최대 메시지 개수. 기본값 30, 허용 범위 1~100")
    };
  }

  /** TEXT와 BOOKING_CARD가 함께 사용하는 커서 응답 필드를 Swagger schema에 고정한다. */
  public static List<FieldDescriptor> messageHistoryResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field("data.content", JsonFieldType.ARRAY, "화면에 표시할 메시지 목록. 메시지가 없으면 빈 배열 []"),
        field(
            "data.content[].messageId",
            JsonFieldType.NUMBER,
            "서버가 저장한 메시지 번호. 메시지 정렬·중복 제거와 afterMessageId 기준으로 사용"),
        optField(
            "data.content[].clientMessageId",
            JsonFieldType.STRING,
            "TEXT를 보내기 전에 프론트가 생성한 UUID. 전송 결과를 임시 말풍선과 연결하고 재시도 중복 저장을 막는 값. 같은 메시지를 재시도할 때 같은 UUID를 사용하며 BOOKING_CARD는 null"),
        field("data.content[].chatRoomId", JsonFieldType.NUMBER, "메시지가 속한 서버 채팅방 ID"),
        optField(
            "data.content[].senderId",
            JsonFieldType.NUMBER,
            "TEXT를 보낸 사용자의 서버 사용자 ID. 서버가 만든 BOOKING_CARD는 null"),
        field(
            "data.content[].mine",
            JsonFieldType.BOOLEAN,
            "현재 로그인 사용자가 보낸 TEXT이면 true. 말풍선을 오른쪽/왼쪽에 배치할 때 사용"),
        enumField(
            "data.content[].type",
            MessageType.class,
            "화면에 그릴 메시지 종류. TEXT=일반 말풍선, BOOKING_CARD=매물 신청 카드"),
        optField(
            "data.content[].originalContent",
            JsonFieldType.STRING,
            "사용자가 입력한 TEXT 원문. 번역본이 있어도 원문 보기 기능을 위해 함께 반환하며 BOOKING_CARD는 null"),
        optField(
            "data.content[].bookingCard",
            JsonFieldType.OBJECT,
            "매물 신청 카드 UI를 그리는 데 필요한 정보. type=BOOKING_CARD일 때만 있고 TEXT는 null"),
        optField(
            "data.content[].bookingCard.bookingId",
            JsonFieldType.NUMBER,
            "이 카드가 나타내는 매물 신청 ID. type=BOOKING_CARD일 때만 사용"),
        optField(
            "data.content[].bookingCard.listing",
            JsonFieldType.OBJECT,
            "신청 카드 상단의 이미지·제목·주소·월세를 그리는 매물 정보"),
        optField(
            "data.content[].bookingCard.listing.listingId", JsonFieldType.STRING, "신청 대상 매물 ID"),
        optField(
            "data.content[].bookingCard.listing.thumbnailUrl",
            JsonFieldType.STRING,
            "신청 카드 대표 이미지 URL. 이미지가 없으면 null"),
        optField(
            "data.content[].bookingCard.listing.title", JsonFieldType.STRING, "신청 카드에 표시할 매물 제목"),
        optField(
            "data.content[].bookingCard.listing.address", JsonFieldType.STRING, "신청 카드에 표시할 매물 주소"),
        optField(
            "data.content[].bookingCard.listing.monthlyRent",
            JsonFieldType.NUMBER,
            "신청 시점 월세(KRW)"),
        optField(
            "data.content[].bookingCard.applicant", JsonFieldType.OBJECT, "임대인용 신청 카드에 표시할 신청자 정보"),
        optField(
            "data.content[].bookingCard.applicant.userId", JsonFieldType.NUMBER, "신청자 users.id"),
        optField("data.content[].bookingCard.applicant.name", JsonFieldType.STRING, "신청자 이름"),
        optField(
            "data.content[].bookingCard.applicant.gender", JsonFieldType.STRING, "신청자 성별 code"),
        optField(
            "data.content[].bookingCard.applicant.country", JsonFieldType.STRING, "신청자 국가 code"),
        optField(
            "data.content[].bookingCard.applicant.countryName",
            JsonFieldType.STRING,
            "신청자 국가 표시 이름"),
        optField("data.content[].bookingCard.applicant.email", JsonFieldType.STRING, "신청자 이메일"),
        optField("data.content[].bookingCard.roomOfferId", JsonFieldType.STRING, "신청한 객실 상품 ID"),
        optField("data.content[].bookingCard.roomOfferName", JsonFieldType.STRING, "신청한 객실 표시 이름"),
        optField(
            "data.content[].bookingCard.moveInDate", JsonFieldType.STRING, "입주 희망일(YYYY-MM-DD)"),
        optField("data.content[].bookingCard.contractPeriod", JsonFieldType.NUMBER, "계약 기간(개월)"),
        optField("data.content[].bookingCard.deposit", JsonFieldType.NUMBER, "보증금(KRW)"),
        optField("data.content[].bookingCard.totalAmount", JsonFieldType.NUMBER, "총 초기 비용(KRW)"),
        optField(
            "data.content[].translation",
            JsonFieldType.OBJECT,
            "현재 로그인 사용자에게 보여 줄 TEXT 자동 번역 정보. 번역이 없거나 실패·불필요하면 null이며 앱은 originalContent 표시"),
        optField(
            "data.content[].translation.content",
            JsonFieldType.STRING,
            "기본 말풍선에 먼저 표시할 자동 번역문. 원문 보기 선택 시 originalContent로 전환"),
        optField(
            "data.content[].translation.sourceLanguage",
            JsonFieldType.STRING,
            "번역 API가 감지한 원문 언어 code"),
        optField(
            "data.content[].translation.targetLanguage",
            JsonFieldType.STRING,
            "로그인 사용자의 대상 언어 code(ko 또는 en)"),
        optEnumField("data.content[].translation.provider", TranslationProvider.class, "번역 제공자"),
        field(
            "data.content[].sentAt",
            JsonFieldType.STRING,
            "메시지 전송 시각(ISO-8601 UTC). 메시지 순서와 화면의 현지 시각 표시에 사용"),
        optField(
            "data.nextCursor",
            JsonFieldType.STRING,
            "다음 과거 메시지를 조회할 때 요청의 cursor에 그대로 넣는 값. hasNext=false면 null"),
        field(
            "data.hasNext",
            JsonFieldType.BOOLEAN,
            "더 오래된 메시지가 남아 있으면 true. false면 위로 스크롤 추가 요청을 멈춘다"),
        errorNull());
  }
}
