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
import com.kohere.chat.domain.translation.TranslationResultStatus;
import com.kohere.report.domain.ReportReason;
import com.kohere.report.domain.ReportStatus;
import java.util.List;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;

/** Swagger <b>Chats</b> 태그에서 사용하는 실시간 연결 안내와 채팅 REST API의 설명·필드 계약이다. */
public final class ChatDocsFields {

  public static final String STOMP_GUIDE_SUMMARY = "실시간 채팅 STOMP 연결 방법 확인";

  public static final String STOMP_GUIDE_DESCRIPTION =
      """
      프런트엔드가 **실시간 채팅에 연결할 때 필요한 주소와 경로를 확인하는 안내 API**다.

      이 GET API가 WebSocket을 대신 연결하는 것은 아니다. Swagger는 HTTP 문서 도구라 STOMP를 직접 실행할 수 없으므로,
      여기에서 계약을 확인한 뒤 앱의 STOMP 클라이언트로 실제 연결·구독·전송을 구현한다.

      **1. WebSocket 연결 주소**

      - 개발 서버: `wss://dev.kohere.app/ws/chat`
      - 로컬 서버: `ws://localhost:8080/ws/chat`
      - 현재 페이지의 host를 사용한다면 `https`에서는 `wss`, `http`에서는 `ws`를 사용한다.

      **2. STOMP CONNECT 인증**

      WebSocket 통로가 열린 뒤 STOMP `CONNECT` frame의 native header에 다음 값을 넣는다.

      ```text
      Authorization: Bearer {accessToken}
      ```

      JWT를 `?token=...`처럼 URL에 넣지 않는다. URL은 브라우저 방문 기록, 프록시·서버 로그, APM 등에 남아 토큰이 노출될 수 있다.

      **3. 먼저 구독할 개인 queue**

      개인 queue는 서버가 **현재 사용자 session에만 보내는 처리 결과와 알림을 받는 경로**다.
      메시지를 보내기 전에 먼저 구독해야 저장 성공 ACK나 오류를 놓치지 않는다.

      | queue | 받는 내용 |
      |---|---|
      | `/user/queue/chat-control` | `PONG`, `SUBSCRIPTION_READY` |
      | `/user/queue/chat-acks` | 내가 보낸 TEXT의 DB 저장 성공 결과 |
      | `/user/queue/chat-errors` | 내가 보낸 TEXT의 검증·권한 오류 |
      | `/user/queue/chat-room-events` | 채팅방 생성·갱신·재표시 알림 |
      | `/user/queue/chat-translations` | 내가 받은 TEXT의 원문과 최종 번역 결과를 함께 받는 경로 |

      **4. 개인 queue가 준비됐는지 확인**

      개인 queue들을 구독한 뒤 `/app/chat/control/ping`으로 다음 값을 보낸다.

      ```json
      { "version": 1, "requestId": "프론트가 생성한 UUID" }
      ```

      `/user/queue/chat-control`에 같은 `requestId`의 `PONG`이 오면 개인 queue를 정상적으로 받을 수 있다는 뜻이다.

      **5. 채팅방 구독**

      `/topic/chat-rooms/{roomId}`를 구독하면 서버가 만든 새 `INQUIRY_CARD`와 `BOOKING_CARD`를 실시간으로 받는다.
      여기서 topic은 방 참여자들이 같은 실시간 이벤트를 받도록 만든 **방송 경로**이며, 데이터를 저장하는 장소는 아니다. 정본은 MySQL이다.

      구독이 서버 broker에 실제 등록되면 개인 control queue로 `SUBSCRIPTION_READY`가 온다.

      - `roomId`: 준비가 끝난 채팅방 번호
      - `highWatermark`: 준비 완료 시점에 이 사용자에게 보이는 마지막 `messageId`. 메시지가 없으면 null

      `highWatermark`는 구독 등록 사이에 빠질 수 있는 메시지를 확인하는 안전 기준이다. 앱은 준비 이벤트를 받은 뒤
      `GET /api/v1/chat-rooms/{roomId}/messages?afterMessageId=...`를 자동 호출해 누락분을 보충하고 실시간 이벤트와 합친다.
      계속 반복하는 polling은 아니다.

      사용자가 삭제해 현재 목록에서 숨긴 채팅방은 직접 구독할 수 없다. 같은 매물에 다시 문의하거나 실제 새 메시지가 도착해 방이 다시 표시된 뒤
      목록·상세를 다시 조회하고 구독한다. 이때 해당 사용자가 삭제한 과거 대화는 복원되지 않고 삭제 이후 새 메시지만 보인다. 상대방의 기존 이력은 유지된다.

      **6. TEXT 전송**

      `/app/chat-rooms/{roomId}/messages`로 다음 값을 보낸다.

      ```json
      {
        "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
        "content": "안녕하세요"
      }
      ```

      - `clientMessageId`: 프런트가 전송 직전에 생성한 UUID. 같은 메시지 재시도에는 같은 UUID를 사용한다.
      - `content`: 사용자가 입력한 원문. 공백을 포함해 최대 3,000 Unicode 문자다.

      저장이 완료되면 발신자에게는 ACK가 먼저 온다. 수신자에게는 번역 작업이 끝난 뒤
      `/user/queue/chat-translations`로 **원문과 번역 결과가 한 이벤트에 함께** 온다. 원문만 먼저 보내고 나중에 말풍선을 바꾸는 방식이 아니다.

      주요 값은 다음과 같다.

      - `messageId`: MySQL이 발급한 최종 메시지 번호
      - `clientMessageId`: 프런트가 SEND할 때 만든 UUID
      - `chatRoomId`: 메시지가 속한 채팅방 번호
      - `senderId`: TEXT를 보낸 사용자 ID
      - `originalContent`: 사용자가 입력한 수정하지 않은 원문
      - `status`: `SUCCEEDED`, `NOT_REQUIRED`, `FAILED` 중 최종 번역 상태
      - `sourceLanguage`: Google이 감지한 원문 언어. 실패하면 null일 수 있음
      - `targetLanguage`: 수신자 언어에서 정한 `ko` 또는 `en`
      - `translatedContent`: 성공한 번역문. 번역 불필요·실패면 null
      - `provider`: 현재 `GOOGLE_CLOUD_TRANSLATION`
      - `sentAt`: 원문 저장 시각
      - `translatedAt`: 번역 작업이 끝난 시각

      `FAILED`여도 `originalContent`가 함께 오므로 앱은 원문 말풍선을 표시할 수 있다. 같은 언어여서 번역이 필요 없는
      `NOT_REQUIRED`도 원문을 표시한다. 백엔드는 `번역 중` 같은 화면 문구를 보내지 않는다.

      새 문의 채팅방이 생성되면 서버가 `INQUIRY_CARD`를 첫 메시지로 저장하고, 입주 신청이 저장되면 `BOOKING_CARD`를 자동 생성해 같은 room
      topic으로 전달한다. 프런트는 두 카드를 직접 SEND하지 않는다.

      **7. ACK: 내가 보낸 TEXT의 저장 결과**

      ACK는 STOMP 수신 확인이 아니라 **MySQL 저장 성공 결과**다. `/user/queue/chat-acks`로 다음과 같이 온다.

      ```json
      {
        "version": 1,
        "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
        "messageId": 70051,
        "sentAt": "2026-08-19T10:15:30.123456Z",
        "duplicate": false
      }
      ```

      - `clientMessageId`: 어떤 임시 말풍선의 결과인지 찾는 UUID
      - `messageId`: 서버에 저장된 최종 메시지 번호
      - `sentAt`: 서버에 처음 저장된 시각
      - `duplicate`: 같은 UUID·같은 본문이 이미 저장돼 기존 결과를 돌려준 경우 true

      임시 말풍선은 사용자가 전송 버튼을 누른 즉시 앱이 화면에 먼저 보여 주는 전송 중 메시지다. 앱은 임시 말풍선과 SEND에 같은
      `clientMessageId`를 넣고, ACK가 오면 같은 UUID의 말풍선에 서버 `messageId`를 연결해 전송 완료로 바꾼다.
      `INQUIRY_CARD`와 `BOOKING_CARD`는 서버가 만들기 때문에 ACK가 없다.

      **8. 오류 처리**

      개별 TEXT 오류는 `/user/queue/chat-errors`로 `clientMessageId`, `code`, `message`가 온다.
      앱의 재시도·로그인 이동 같은 처리는 변하지 않는 `code`를 기준으로 결정한다. `message`는 사용자 언어와 문구 개선에 따라 바뀔 수 있으므로
      화면 표시 용도로만 사용한다.

      **9. 재연결**

      연결이 끊기면 새 access token으로 CONNECT하고 개인 queue와 room topic을 다시 구독한다. `SUBSCRIPTION_READY` 뒤 마지막으로 연속 확인한
      `messageId`를 `afterMessageId`로 보내 REST 이력을 합친다. Simple Broker는 오프라인 동안의 이벤트를 다시 재생하지 않기 때문이다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 안내 API 호출에 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 안내 API 호출에 사용한 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 계정이 안내 API 실행 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      """;

  public static final String[] STOMP_GUIDE_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] STOMP_GUIDE_403 = {"AUTH_ONBOARDING_REQUIRED"};

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

      - 새 방을 만든 경우 `201 Created`, `created=true`이며 서버가 첫 메시지 `INQUIRY_CARD`도 함께 저장한다.
      - 기존 방을 반환한 경우 `200 OK`, `created=false`다.
      - 두 응답의 JSON 구조는 같다. 프론트는 두 경우 모두 `chatRoomId`로 채팅방 화면을 열면 된다.
      - `created`는 새 방인지 구분하는 참고값이다. 화면 이동 여부를 이 값으로 나누지 않는다.
      - 문의 응답 자체에는 카드가 없다. 응답을 받은 뒤 메시지 이력 API를 호출해 저장된 `INQUIRY_CARD`를 읽는다.
      - 신규 방의 room topic은 프론트 구독 전에 카드가 발행될 수 있으므로 `created=true`여도 메시지 이력 조회를 생략하지 않는다.
      - 기존 방이면 문의서를 새로 추가하지 않는다. 따라서 반복 문의나 신청으로 먼저 만들어진 방에 카드가 중복되지 않는다.
      - 채팅방 목록의 상대 이미지는 앱이 기본 아이콘으로 표시한다. 문의서 대표 이미지는 메시지 이력의 `inquiryCard.thumbnailUrl`에 있다.

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
      - 마지막 메시지가 `INQUIRY_CARD`이면 `preview=null`이다. 앱이 “매물 문의가 시작되었습니다” 같은 고정 문구를 `ko/en`으로 표시한다.
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
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
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
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
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

      **문의하기 뒤 INQUIRY_CARD를 받는 흐름**

      1. `POST /api/v1/listings/{listingId}/inquiries`를 호출한다.
      2. 새 방이면 서버가 방·참여자 두 명·문의서를 함께 저장하고 `created=true`를 반환한다.
      3. 반환된 `chatRoomId`로 이 메시지 이력 API를 호출한다.
      4. `type=INQUIRY_CARD`인 항목의 `inquiryCard`로 문의서 UI를 그린다.

      새 방의 문의서는 프론트가 room topic을 구독하기 전에 저장·발행될 수 있다. 따라서 문의 응답 직후의 메시지 이력 조회가 최초 문의서를 받는 기본 방법이고,
      room topic은 연결 중 새로 생기는 서버 카드 수신과 재연결 보조에 사용한다.

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
      - `INQUIRY_CARD`: 문의하기로 새 방을 만들 때 서버가 저장한 문의서다. `inquiryCard`에 대표 이미지·제목·지역 code·매물 유형·최소/최대 월세가 있다.
      - `BOOKING_CARD`: 신청 완료 이벤트로 서버가 만든 카드다. `bookingCard`에 신청 시점 정보가 있고 `originalContent`·`clientMessageId`·`senderId`는 null이다.
      - 문의서의 `inquiryCard.thumbnailUrl`과 신청서의 `bookingCard.listing.thumbnailUrl`은 각 카드 상단 대표 이미지다. 채팅방 목록의 프로필 이미지와는 관계없다.
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
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      | 404 | `CHAT_ROOM_NOT_FOUND` | 방 없음·비참여자·요청자에게 숨겨진 방 |
      """;

  public static final String[] MESSAGE_HISTORY_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] MESSAGE_HISTORY_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] MESSAGE_HISTORY_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] MESSAGE_HISTORY_404 = {"CHAT_ROOM_NOT_FOUND"};

  public static final String ROOM_DELETE_SUMMARY = "채팅방 삭제";

  public static final String ROOM_DELETE_DESCRIPTION =
      """
      로그인 사용자에게만 채팅방과 삭제 시점까지의 대화를 숨긴다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 필수. 온보딩을 완료한 사용자의 access token을 보낸다.

      **프론트 요청 방법**

      - 채팅방 목록 또는 상세 응답에서 받은 숫자 `chatRoomId`를 URL의 `roomId`에 넣는다.
      - 요청 body와 사용자 ID는 보내지 않는다. 삭제 대상 사용자는 access token에서 서버가 확인한다.

      ```http
      DELETE /api/v1/chat-rooms/556
      ```

      **성공 응답 처리**

      - 성공 status는 `204 No Content`이고 JSON 응답 본문은 없다.
      - 프론트는 `204`를 받으면 해당 채팅방을 내 목록과 현재 화면에서 제거하면 된다.
      - 이미 숨긴 방에 같은 DELETE를 다시 보내도 `204`이며 삭제 시각과 과거 이력 경계는 바뀌지 않는다.

      **삭제의 정확한 의미**

      - 요청한 사용자의 채팅방만 목록에서 숨긴다.
      - 상대방의 채팅방과 전체 과거 대화는 그대로 유지한다.
      - 일반 사용자가 삭제를 취소하거나 과거 대화를 복원하는 API는 없다.
      - DB 원문을 즉시 물리 삭제하는 API가 아니다. 3개월 보존 뒤 물리 삭제는 후속 서버 작업이다.
      - 같은 매물에 다시 문의하면 같은 `roomId`가 다시 표시될 수 있지만 삭제 전 메시지는 계속 숨긴다.
      - 숨긴 동안 상대방의 실제 새 메시지가 오면 채팅방이 다시 표시되고, 삭제한 사용자는 그 새 메시지부터 볼 수 있다.

      **보안 규칙**

      - 방이 없거나 요청자가 참여자가 아니면 모두 `404 CHAT_ROOM_NOT_FOUND`다.
      - 같은 404를 사용해 제3자가 roomId 존재 여부를 알아내지 못하게 한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `MALFORMED_REQUEST` | `roomId`가 숫자가 아님 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      | 404 | `CHAT_ROOM_NOT_FOUND` | 방 없음 또는 비참여자 |
      """;

  public static final String[] ROOM_DELETE_400 = {"MALFORMED_REQUEST"};
  public static final String[] ROOM_DELETE_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] ROOM_DELETE_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] ROOM_DELETE_404 = {"CHAT_ROOM_NOT_FOUND"};

  public static final String ROOM_BLOCK_SUMMARY = "채팅방 상대방 차단";

  public static final String ROOM_BLOCK_DESCRIPTION =
      """
      현재 채팅방의 두 참여자 중 로그인 사용자가 아닌 상대방을 차단한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 필수. 온보딩을 완료한 사용자의 access token을 보낸다.

      **프론트 요청 방법**

      - 채팅방 목록 또는 상세 응답에서 받은 숫자 `chatRoomId`를 URL의 `roomId`에 넣는다.
      - 요청 body와 상대 사용자 ID는 보내지 않는다.
      - 서버가 실제 채팅방 참여자 두 명을 확인하고 로그인 사용자가 아닌 상대방을 찾는다.

      ```http
      POST /api/v1/chat-rooms/556/block
      ```

      **성공 응답 처리**

      - 성공 status는 `204 No Content`이고 JSON 응답 본문은 없다.
      - 프론트는 `204`를 받으면 메시지 입력창을 비활성화하고 현재 화면의 `blocked`를 true로 처리한다.
      - 이미 차단한 상대에게 같은 요청을 다시 보내도 `204`다.

      **차단의 정확한 의미**

      - 채팅방 한 개가 아니라 두 사용자 사이의 전역 차단이다.
      - 차단 이전의 채팅방과 메시지는 양쪽 모두 그대로 조회할 수 있다.
      - 차단 후에는 어느 방향으로도 새 TEXT를 저장하거나 실시간 전달하지 않는다.
      - 차단만으로 채팅방을 목록에서 숨기지 않는다. 숨기려면 채팅방 삭제 API를 별도로 호출한다.
      - 차단 해제는 기존 `DELETE /api/v1/users/me/blocks/{userId}`를 사용한다.

      **보안 규칙**

      - 방이 없거나 요청자가 참여자가 아니면 모두 `404 CHAT_ROOM_NOT_FOUND`다.
      - 같은 404를 사용해 제3자가 roomId 존재 여부를 알아내지 못하게 한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `MALFORMED_REQUEST` | `roomId`가 숫자가 아님 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      | 404 | `CHAT_ROOM_NOT_FOUND` | 방 없음 또는 비참여자 |
      """;

  public static final String[] ROOM_BLOCK_400 = {"MALFORMED_REQUEST"};
  public static final String[] ROOM_BLOCK_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] ROOM_BLOCK_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] ROOM_BLOCK_404 = {"CHAT_ROOM_NOT_FOUND"};

  public static final String CHAT_REPORT_SUMMARY = "채팅방 상대방 신고 접수";

  public static final String CHAT_REPORT_DESCRIPTION =
      """
      현재 1:1 채팅방의 상대방을 **고정 신고 사유 하나로 신고**한다.

      **프론트 요청 순서**

      1. 앱이 현재 언어에 맞는 신고 화면을 직접 표시한다. 신고 사유 문구는 프론트가 `ko/en`으로 관리한다.
      2. 사용자가 사유 하나를 선택하고 신고 버튼을 누른다.
      3. 채팅방 목록·상세에서 받은 숫자 `chatRoomId`를 URL의 `roomId`에 넣는다.
      4. body에는 선택한 `reason` 코드 하나만 보낸다.

      ```http
      POST /api/v1/chat-rooms/556/reports
      Authorization: Bearer <accessToken>
      Content-Type: application/json
      ```

      ```json
      { "reason": "ILLEGAL_CONTENT" }
      ```

      **프론트가 보내지 않는 값**

      - 신고자 ID: access token에서 서버가 확인한다.
      - 신고 대상 사용자 ID: 서버가 채팅방의 다른 참여자를 찾는다.
      - 상세 사유 문자열: 지원하지 않는다.
      - 증거 메시지: 서버가 신고자에게 현재 보이는 최근 TEXT 원문을 최대 20개 수집한다.

      INQUIRY_CARD·BOOKING_CARD와 자동 번역문은 신고 증거에서 제외한다. 사용자가 이전에 채팅방을 삭제했다가 다시 들어왔다면 삭제 전에 숨긴 과거 TEXT도
      증거로 복원하지 않는다.

      **성공 응답 처리**

      - `201 Created`: 이번 요청으로 새 신고와 증거가 DB에 저장됐다.
      - `200 OK`: 같은 사용자가 같은 채팅방을 이미 신고해 기존 접수 결과를 반환했다.
      - 두 경우의 JSON 구조는 같다. `reportId`가 있으면 DB 접수가 완료된 것이다.
      - DB 저장 뒤 응답을 받지 못해 같은 요청을 재전송해도 신고가 중복 저장되지 않는다.
      - 두 번째 요청의 reason이 달라도 최초 신고 사유와 증거를 덮어쓰지 않는다.

      신고 접수는 상대방을 자동 차단하거나 채팅방을 자동 삭제하지 않는다. 필요하면 차단·삭제 API를 각각 별도로 호출한다.

      **신고 사유 코드와 프론트 표시 예시**

      | code | ko | en |
      |---|---|---|
      | `ABUSE_HARASSMENT_DISCRIMINATION` | 욕설, 비방, 차별, 혐오 | Abuse, Harassment, Discrimination |
      | `ILLEGAL_CONTENT` | 불법정보 | Illegal Content |
      | `SEXUAL_INAPPROPRIATE_CONTENT` | 음란, 청소년 유해 | Sexual or Inappropriate Content |
      | `PERSONAL_INFORMATION` | 개인정보 노출, 유포, 거래 | Personal Information |
      | `SPAM` | 도배, 스팸 | Spam |
      | `OTHER` | 기타 | Other |

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | reason 누락 |
      | 400 | `MALFORMED_REQUEST` | roomId가 숫자가 아니거나 지원하지 않는 reason 문자열 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      | 404 | `CHAT_ROOM_NOT_FOUND` | 방 없음·비참여자·현재 숨긴 방 |
      | 422 | `REPORT_REQUIRES_TEXT_MESSAGE` | 신고자에게 현재 보이는 TEXT 원문이 없음 |
      """;

  public static final String[] CHAT_REPORT_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] CHAT_REPORT_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] CHAT_REPORT_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] CHAT_REPORT_404 = {"CHAT_ROOM_NOT_FOUND"};
  public static final String[] CHAT_REPORT_422 = {"REPORT_REQUIRES_TEXT_MESSAGE"};

  private ChatDocsFields() {}

  /** 실제 WebSocket 처리 코드와 같은 경로가 안내 응답에 노출되는지 Swagger schema에 고정한다. */
  public static List<FieldDescriptor> stompGuideResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field(
            "data.webSocketEndpoint",
            JsonFieldType.STRING,
            "모든 환경에서 사용하는 WebSocket handshake 상대 경로. 현재 /ws/chat"),
        field(
            "data.developmentWebSocketUrl",
            JsonFieldType.STRING,
            "배포된 개발 서버에 연결할 전체 WSS 주소. 현재 wss://dev.kohere.app/ws/chat"),
        field(
            "data.localWebSocketUrl",
            JsonFieldType.STRING,
            "로컬 8080 서버에 연결할 전체 WS 주소. 현재 ws://localhost:8080/ws/chat"),
        field(
            "data.connectHeaderName",
            JsonFieldType.STRING,
            "STOMP CONNECT native header 이름. Authorization 사용"),
        field(
            "data.connectHeaderValueFormat",
            JsonFieldType.STRING,
            "CONNECT header 값 형식. {accessToken}을 로그인으로 받은 실제 토큰으로 교체"),
        field(
            "data.controlSendDestination",
            JsonFieldType.STRING,
            "개인 queue가 준비됐는지 PING/PONG으로 확인하는 SEND 경로"),
        field(
            "data.roomSubscribeDestination",
            JsonFieldType.STRING,
            "서버 생성 INQUIRY_CARD·BOOKING_CARD를 실시간 수신할 방 topic 형식. {roomId}를 서버 채팅방 번호로 교체"),
        field(
            "data.messageSendDestination",
            JsonFieldType.STRING,
            "사용자 TEXT를 보낼 SEND 경로 형식. {roomId}를 서버 채팅방 번호로 교체"),
        field(
            "data.controlQueue",
            JsonFieldType.STRING,
            "PONG과 SUBSCRIPTION_READY를 현재 session만 받는 개인 queue"),
        field("data.ackQueue", JsonFieldType.STRING, "내가 보낸 TEXT의 MySQL 저장 성공 결과 ACK를 받는 개인 queue"),
        field("data.errorQueue", JsonFieldType.STRING, "내가 보낸 TEXT의 검증·권한 오류를 받는 개인 queue"),
        field("data.roomEventQueue", JsonFieldType.STRING, "채팅방 생성·갱신·재표시 신호를 받는 개인 queue"),
        field(
            "data.translationQueue",
            JsonFieldType.STRING,
            "받은 TEXT의 원문과 최종 번역 상태를 한 이벤트로 함께 받는 개인 queue"),
        field(
            "data.maxTextCodePoints",
            JsonFieldType.NUMBER,
            "TEXT 원문 한 건에 허용되는 최대 Unicode 문자 수. 현재 3,000"),
        field(
            "data.heartbeatSeconds",
            JsonFieldType.NUMBER,
            "STOMP 연결 생존 여부를 확인하는 heartbeat 간격(초). 현재 10"),
        errorNull());
  }

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
            "새 채팅방·참여자 두 명·첫 INQUIRY_CARD를 이번 요청에서 함께 저장했으면 true. 기존 방을 반환해 문의서를 추가하지 않았으면 false. 값과 관계없이 chatRoomId로 화면 이동"),
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
            "마지막 메시지 종류. TEXT·INQUIRY_CARD·BOOKING_CARD 중 하나이며 lastMessage가 null이면 없음"),
        optField(
            "data.content[].lastMessage.preview",
            JsonFieldType.STRING,
            "채팅 목록 두 번째 줄에 표시할 TEXT 문자열. 서버 카드면 null이므로 앱이 type·myRole·언어에 맞는 안내 문구 표시"),
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

  /** 삭제할 채팅방 ID가 프런트 생성값이 아니라 서버 응답에서 온 숫자임을 분명히 한다. */
  public static ParameterDescriptor[] roomDeletePathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("roomId")
          .description("채팅방 목록·상세 또는 문의하기 응답에서 받은 숫자 chatRoomId. 현재 로그인 사용자에게만 숨김")
    };
  }

  /** 차단할 사용자 ID가 아니라 서버가 상대방을 찾을 수 있는 채팅방 ID만 받는다는 점을 설명한다. */
  public static ParameterDescriptor[] roomBlockPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("roomId")
          .description("채팅방 목록·상세 또는 문의하기 응답에서 받은 숫자 chatRoomId. 서버가 이 방에서 로그인 사용자가 아닌 상대방을 찾아 차단")
    };
  }

  /** 신고 대상 userId가 아니라 현재 채팅방 ID만 경로에 넣는다는 점을 고정한다. */
  public static ParameterDescriptor[] chatReportPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("roomId")
          .description("채팅방 목록·상세 또는 문의하기 응답에서 받은 숫자 chatRoomId. 서버가 이 방의 상대방과 원문 증거를 찾음")
    };
  }

  /** 프런트가 보내는 신고 값은 현지화 문구가 아닌 고정 code 한 개뿐이다. */
  public static List<FieldDescriptor> chatReportRequestFields() {
    return List.of(
        enumField(
            "reason",
            ReportReason.class,
            "사용자가 선택한 신고 사유의 언어 무관 코드. 화면 문구는 프론트가 ko/en으로 현지화하며 상세 사유 문자열은 보내지 않음"));
  }

  /** 신규 201과 중복 200이 함께 사용하는 신고 접수 확인 응답이다. */
  public static List<FieldDescriptor> chatReportResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field("data.reportId", JsonFieldType.NUMBER, "DB에 커밋된 신고의 서버 식별자"),
        field("data.chatRoomId", JsonFieldType.NUMBER, "신고가 접수된 현재 1:1 채팅방 ID"),
        enumField(
            "data.reason",
            ReportReason.class,
            "최초 접수된 신고 사유 코드. 중복 재요청에서는 이번 body가 아니라 기존 최초 사유가 반환될 수 있음"),
        enumField(
            "data.status", ReportStatus.class, "신고 상태. 현재 사용자 접수 단계는 RECEIVED이며 관리자 처리 상태는 후속 기능"),
        field("data.receivedAt", JsonFieldType.STRING, "서버가 최초 신고와 증거 저장을 완료한 접수 시각(ISO-8601 UTC)"),
        errorNull());
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

  /** TEXT·INQUIRY_CARD·BOOKING_CARD가 함께 사용하는 커서 응답 필드를 Swagger schema에 고정한다. */
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
            "TEXT를 보내기 전에 프론트가 생성한 UUID. 전송 결과를 임시 말풍선과 연결하고 재시도 중복 저장을 막는 값. 같은 메시지를 재시도할 때 같은 UUID를 사용하며 서버 카드는 null"),
        field("data.content[].chatRoomId", JsonFieldType.NUMBER, "메시지가 속한 서버 채팅방 ID"),
        optField(
            "data.content[].senderId",
            JsonFieldType.NUMBER,
            "TEXT를 보낸 사용자의 서버 사용자 ID. 서버가 만든 INQUIRY_CARD·BOOKING_CARD는 null"),
        field(
            "data.content[].mine",
            JsonFieldType.BOOLEAN,
            "현재 로그인 사용자가 보낸 TEXT이면 true. 말풍선을 오른쪽/왼쪽에 배치할 때 사용"),
        enumField(
            "data.content[].type",
            MessageType.class,
            "화면에 그릴 메시지 종류. TEXT=일반 말풍선, INQUIRY_CARD=매물 문의서, BOOKING_CARD=매물 신청 카드"),
        optField(
            "data.content[].originalContent",
            JsonFieldType.STRING,
            "사용자가 입력한 TEXT 원문. 번역본이 있어도 원문 보기 기능을 위해 함께 반환하며 서버 카드는 null"),
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
            "data.content[].inquiryCard",
            JsonFieldType.OBJECT,
            "매물 문의서 UI를 그리는 정보. type=INQUIRY_CARD일 때만 있고 다른 타입은 null"),
        optField(
            "data.content[].inquiryCard.listingId",
            JsonFieldType.STRING,
            "문의한 매물 ID. View Detail을 누르면 이 ID로 기존 매물 상세 화면을 연다"),
        optField(
            "data.content[].inquiryCard.thumbnailUrl",
            JsonFieldType.STRING,
            "문의 당시 첫 번째 대표 이미지 URL. 이미지가 없으면 null"),
        optField("data.content[].inquiryCard.title", JsonFieldType.STRING, "문의 당시 매물 제목"),
        optField(
            "data.content[].inquiryCard.city",
            JsonFieldType.STRING,
            "주소의 city code. 프론트가 현재 언어의 label로 표시"),
        optField(
            "data.content[].inquiryCard.district",
            JsonFieldType.STRING,
            "주소의 district code. 프론트가 현재 언어의 label로 표시"),
        optField(
            "data.content[].inquiryCard.listingType",
            JsonFieldType.STRING,
            "매물 유형 code. GOSHIWON·CO_LIVING·SHARE_HOUSE 중 하나이며 프론트가 ko/en label로 표시"),
        optField(
            "data.content[].inquiryCard.monthlyRentMin",
            JsonFieldType.NUMBER,
            "문의 당시 ACTIVE 방 상품의 최소 월세(KRW)"),
        optField(
            "data.content[].inquiryCard.monthlyRentMax",
            JsonFieldType.NUMBER,
            "문의 당시 ACTIVE 방 상품의 최대 월세(KRW)"),
        optField(
            "data.content[].translation",
            JsonFieldType.OBJECT,
            "현재 로그인 사용자에게 보여 줄 받은 TEXT의 최종 번역 정보. 처리가 끝나지 않았거나 내가 보낸 TEXT·서버 카드이면 null"),
        optEnumField(
            "data.content[].translation.status",
            TranslationResultStatus.class,
            "최종 번역 상태. SUCCEEDED=번역문 표시, NOT_REQUIRED=같은 언어라 원문 표시, FAILED=번역 실패로 원문 표시"),
        optField(
            "data.content[].translation.content",
            JsonFieldType.STRING,
            "status=SUCCEEDED일 때 기본 말풍선에 표시할 번역문. 그 외 상태는 null"),
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
