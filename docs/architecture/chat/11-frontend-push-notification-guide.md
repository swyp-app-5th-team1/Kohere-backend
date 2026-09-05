# 프론트엔드 iOS 채팅 FCM 푸시 연동 가이드

> 대상: iOS 앱 프론트엔드 개발자
>
> 최종 수정: 2026-09-06
>
> Swagger: `Users → PUT/DELETE /api/v1/users/me/push-devices/{installationId}`, `GET/PATCH /api/v1/users/me/notification-preferences`

이 문서는 iOS 앱이 FCM 토큰을 백엔드에 등록하고, 채팅 푸시를 표시한 뒤 사용자가 알림을 누르면 해당 채팅방으로 이동하는 방법을 설명한다.

채팅 화면의 REST·STOMP 연결과 메시지 렌더링은 [09-frontend-stomp-guide.md](09-frontend-stomp-guide.md)를 따른다. 여기서는 **iOS 외부 푸시 연동에 필요한 프론트엔드 작업만** 다룬다.

## 1. 프론트에서 구현할 것

프론트엔드는 다음 일곱 가지를 구현하면 된다.

1. Firebase iOS SDK 초기화와 알림 권한 요청
2. 앱 설치본을 구분하는 `installationId` 생성·보관
3. Firebase가 발급한 FCM 토큰 취득과 갱신 감지
4. 로그인 후 기기 등록 API 호출
5. 로그아웃 전 기기 삭제 API 호출
6. 알림을 눌렀을 때 `roomId` 채팅방으로 이동
7. 사용자의 `chatPushEnabled` 조회·변경을 설정 스위치와 연결

전체 흐름은 다음과 같다.

```text
앱 실행
  → Firebase 초기화
  → 알림 권한 확인·요청
  → FCM 토큰 취득
  → installationId 조회 또는 생성
  → 로그인 완료
  → 백엔드에 installationId와 FCM 토큰 등록
  → 알림 설정 화면을 열 때 chatPushEnabled 조회

새 채팅 활동 발생
  → iPhone에 알림 도착
  → 사용자가 알림 선택
  → type과 roomId 확인
  → REST로 채팅방과 메시지 조회
  → 채팅방 화면 표시
  → STOMP 연결·구독
```

## 2. 꼭 알아야 할 값

| 값 | 의미 | 누가 만드나 |
| --- | --- | --- |
| `installationId` | 이 앱 설치본을 계속 구분하는 UUID | iOS 앱 |
| FCM 토큰 | 현재 설치본에 푸시를 보낼 주소 | Firebase SDK |
| access token | 로그인 사용자를 확인하는 인증값 | 로그인 API |
| `roomId` | 알림을 눌렀을 때 열 채팅방 ID | 백엔드 푸시 data |
| `chatPushEnabled` | 현재 계정의 채팅 푸시 허용 여부 | 백엔드 알림 설정 API |

`installationId`와 FCM 토큰은 역할이 다르다.

```text
installationId = 같은 앱 설치본인지 알아보는 고정 식별자
FCM 토큰       = 현재 그 설치본에 푸시를 보낼 주소
```

FCM 토큰은 바뀔 수 있다. 같은 `installationId`로 새 토큰을 다시 등록하면 백엔드는 기존 기기 정보의 토큰을 갱신한다.

## 3. iOS·Firebase 사전 설정

실제 iPhone에서 푸시를 받으려면 다음 설정이 필요하다.

- Firebase 프로젝트에 iOS 앱이 올바른 Bundle ID로 등록돼 있어야 한다.
- iOS target에 해당 Firebase 앱의 `GoogleService-Info.plist`가 포함돼 있어야 한다.
- 앱 시작 시 Firebase SDK를 초기화해야 한다.
- Xcode target에서 Push Notifications capability를 활성화해야 한다.
- Firebase Console에 APNs 인증키가 등록돼 있어야 한다.
- 앱이 사용자에게 알림 권한을 요청해야 한다.
- 앱이 APNs 원격 알림 등록을 요청해야 한다.
- Firebase Messaging delegate에서 FCM 토큰을 받아야 한다.

Firebase AppDelegate swizzling을 끈 프로젝트는 APNs device token을 `Messaging.messaging().apnsToken`에 직접 연결해야 한다. 기본 swizzling을 사용한다면 Firebase SDK가 이 연결을 처리한다.

시뮬레이터만으로 완료 여부를 판단하지 않는다. background·앱 종료 상태와 실제 알림 선택은 dev Firebase 설정이 연결된 **실제 iPhone**에서 최종 확인한다.

## 4. installationId 생성·보관

`installationId`는 앱이 UUID로 한 번 생성하고 같은 설치본에서 계속 사용한다.

```text
예: e7714046-1634-4dc1-a97e-8c1f91a72483
```

구현 규칙:

1. 앱 실행 시 Keychain 등 로컬 저장소에서 `installationId`를 읽는다.
2. 값이 없으면 UUID v4를 한 번 생성해 저장한다.
3. 로그인·로그아웃할 때 삭제하거나 새로 만들지 않는다.
4. FCM 토큰이 바뀌어도 같은 값을 사용한다.
5. 사용자 ID, 이메일, 기기 이름, IDFV를 대신 사용하지 않는다.

앱 삭제·재설치 시 Keychain 값을 유지할지는 iOS 팀 정책으로 통일한다. 어느 정책이든 앱이 현재 `installationId`와 FCM 토큰을 다시 등록하면 백엔드가 최신 연결로 정리한다.

## 5. FCM 토큰 취득·갱신

Firebase Messaging token callback에서 받은 값을 현재 FCM 토큰으로 사용한다. 이 callback은 앱 시작 때 호출될 수 있고 새 토큰이 발급될 때도 다시 호출될 수 있다.

토큰이 바뀔 수 있는 대표적인 경우:

- 앱 재설치 또는 기기 복원
- Firebase SDK 데이터 초기화
- APNs·Firebase 등록 정보 변경
- Firebase의 보안·운영 정책에 따른 토큰 갱신

새 토큰을 받았을 때 처리:

```text
새 FCM 토큰 수신
  → 현재 토큰으로 로컬 상태 갱신
  → 로그인 상태인지 확인
  → 같은 installationId로 기기 등록 API 재호출
```

주의사항:

- 새 토큰을 받았다고 `installationId`를 새로 만들지 않는다.
- 토큰을 자르거나 변경하지 않고 Firebase가 준 문자열 그대로 보낸다.
- 전체 토큰을 콘솔 로그, 분석 이벤트, crash report에 남기지 않는다.

## 6. 기기 등록·갱신 API

access token, `installationId`, FCM 토큰이 모두 준비되면 호출한다.

```http
PUT /api/v1/users/me/push-devices/{installationId}
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "fcmToken": "opaque-fcm-registration-token",
  "platform": "IOS"
}
```

| 값 | 위치 | 설명 |
| --- | --- | --- |
| `installationId` | path | 앱이 생성·보관하는 UUID |
| `fcmToken` | body | Firebase가 반환한 현재 토큰, 1~1024자 |
| `platform` | body | 현재는 `IOS`만 허용 |
| 사용자 ID | 보내지 않음 | 백엔드가 access token에서 확인 |

성공 응답:

```http
HTTP/1.1 204 No Content
```

`204`에는 JSON body가 없다. 응답 body를 파싱하지 말고 HTTP status로 성공을 판단한다.

같은 값을 여러 번 보내도 된다.

| 요청 상황 | 결과 |
| --- | --- |
| 처음 보는 installationId | 새 기기로 등록 |
| 같은 installationId·같은 토큰 | 중복 생성 없이 등록 상태 갱신 |
| 같은 installationId·새 토큰 | 기존 FCM 토큰 교체 |
| 같은 기기에서 다른 사용자 로그인 | 현재 로그인 사용자로 연결 변경 |

### 권장 호출 시점

| 시점 | 처리 |
| --- | --- |
| 로그인 성공 후 | 등록 API 호출 |
| 로그인 상태로 앱 시작 | 등록 API 호출 |
| FCM 토큰 callback에서 현재 토큰 수신 | 같은 installationId로 등록 API 호출 |
| access token이 아직 없음 | 로그인 완료까지 대기 |
| FCM 토큰이 아직 없음 | Firebase callback까지 대기 |

여러 callback에서 각각 API를 호출하지 말고 다음 조건을 확인하는 하나의 함수로 모으는 것을 권장한다.

```text
registerPushDeviceIfReady()
  accessToken 없음      → 대기
  installationId 없음  → 생성 후 계속
  fcmToken 없음         → 대기
  세 값이 모두 있음     → PUT 호출
```

동시에 여러 callback이 들어오면 진행 중 요청을 합치거나 가장 마지막 FCM 토큰으로 다시 호출한다.

## 7. 로그아웃 기기 삭제 API

로그아웃할 때 access token을 삭제하기 **전에** 호출한다.

```http
DELETE /api/v1/users/me/push-devices/{installationId}
Authorization: Bearer <accessToken>
```

성공 응답:

```http
HTTP/1.1 204 No Content
```

권장 순서:

```text
로그아웃 선택
  → 현재 access token으로 기기 DELETE 호출
  → 백엔드 logout API 호출
  → 로컬 access/refresh token 삭제
  → 로그인 화면 이동
```

- 이미 삭제됐거나 현재 사용자의 기기가 아니어도 `204`를 반환한다.
- 다른 휴대폰에 등록된 푸시는 유지된다.
- 로컬 `installationId`는 삭제하지 않는다.
- DELETE가 네트워크 오류로 실패하면 서버에서 삭제됐다고 가정하지 않는다.
- 짧게 재시도할 수 있지만 로그아웃을 무한히 막지는 않는다.

DELETE가 실패하면 이전 사용자와 기기의 연결이 서버에 잠시 남을 수 있다. 실패 사실을 민감값 없이 기록하고, 다음 로그인 시 현재 사용자 정보로 같은 `installationId`를 다시 `PUT`한다.

## 8. API 오류 처리

오류 응답은 기존 공통 형식을 사용한다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_INPUT",
    "message": "Invalid input.",
    "errors": [
      {
        "field": "fcmToken",
        "reason": "This field is required."
      }
    ]
  }
}
```

| HTTP·code | 의미 | 처리 |
| --- | --- | --- |
| `400 INVALID_INPUT` | 토큰 누락·길이 초과 또는 platform 누락 | 요청값 확인 |
| `400 MALFORMED_REQUEST` | UUID·JSON·enum 형식 오류 | 직렬화 코드 확인 |
| `401 UNAUTHENTICATED` | 유효한 access token이 없음 | 로그인 처리 |
| `401 TOKEN_EXPIRED` | access token 만료 | 재발급 후 같은 요청 재시도 |
| `403 AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 | 온보딩 완료 후 재시도 |

분기는 번역될 수 있는 `error.message`가 아니라 `error.code`로 처리한다. 기기 등록 실패가 채팅 REST·STOMP 사용 자체를 막지는 않으므로, 기술 오류 문구를 사용자에게 그대로 노출하기보다 앱 내부 재시도와 진단 로그를 우선한다.

## 9. 채팅 푸시 수신 설정

이 설정은 채팅방별 음소거가 아니라, 현재 계정의 **모든 채팅 푸시**에 공통으로 적용된다.

설정 화면을 열 때 현재 값을 조회한다.

```http
GET /api/v1/users/me/notification-preferences
Authorization: Bearer <accessToken>
```

```json
{
  "success": true,
  "data": {
    "chatPushEnabled": true
  },
  "error": null
}
```

이전에 설정을 한 번도 변경하지 않은 신규·기존 사용자도 `data.chatPushEnabled=true`를 받는다. 앱은 이 값으로 설정 스위치를 표시한다.

사용자가 스위치를 변경하면 새 값을 전송한다.

```http
PATCH /api/v1/users/me/notification-preferences
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "chatPushEnabled": false
}
```

백엔드는 저장된 값을 `200 OK`로 다시 반환한다. 앱은 응답값으로 스위치의 최종 상태를 확정한다.

```json
{
  "success": true,
  "data": {
    "chatPushEnabled": false
  },
  "error": null
}
```

프론트엔드 처리 규칙:

- `false`면 백엔드가 새 채팅 FCM을 보내지 않는다.
- 앱을 종료하거나 다시 로그인해도 설정은 계정에 유지된다.
- 여러 기기에서 같은 계정을 사용하면 모든 기기에 공통으로 적용된다.
- 알림을 꺼도 FCM 토큰 `PUT`과 토큰 갱신은 계속 수행한다.
- 다시 `true`로 바꾸면 이후 새 채팅부터 푸시가 재개된다. 꺼져 있던 동안의 푸시는 재발송되지 않는다.
- `chatPushEnabled`를 누락하거나 `null`로 보내면 `400`으로 처리한다.
- 동시 변경이 있으면 서버가 마지막으로 처리한 값이 최종 상태다.
- PATCH가 실패하면 스위치를 성공한 것처럼 확정하지 말고, 이전 서버 값으로 돌리거나 GET으로 다시 확인한다.

이 설정은 iOS 시스템의 앱 알림 권한과 다르다. `chatPushEnabled=true`여도 iOS 설정에서 알림을 차단했다면 시스템 알림은 표시되지 않는다.

## 10. 백엔드가 보내는 푸시 값

백엔드는 다음 형태의 `notification`과 `data`를 FCM에 보낸다.

```json
{
  "notification": {
    "title": "채팅",
    "body": "\"고시원3\"으로부터 새 메시지가 도착했어요"
  },
  "data": {
    "type": "CHAT_MESSAGE",
    "roomId": "10",
    "messageId": "125",
    "listingId": "68f1c7...",
    "listingTitle": "고시원3",
    "sentAt": "2026-08-29T06:30:00Z"
  }
}
```

이 JSON은 **백엔드가 FCM에 보내는 구조**다. iOS callback의 `userInfo`에 `notification`과 `data`가 그대로 중첩돼 들어오는 것은 아니다. 실제 iOS 읽기 방법은 다음 절을 따른다.

### 표시 문구

| 채팅 활동 | title | body |
| --- | --- | --- |
| 일반 메시지 | `채팅` | `"{listingTitle}"으로부터 새 메시지가 도착했어요` |
| 문의 카드 | `채팅` | `"{listingTitle}"에 새로운 문의가 도착했어요` |
| 신청 카드 | `채팅` | `"{listingTitle}"에 새로운 신청이 도착했어요` |

background·종료 상태에서 표시할 title과 body는 백엔드가 완성한다. 앱이 `type`과 `listingTitle`을 조합해 다른 시스템 알림 문구를 만들 필요가 없다.

`2시간 전` 같은 알림 표시 시각은 iOS가 관리한다. 앱 내부에서 원인 메시지의 저장 시각이 필요할 때만 `sentAt`을 사용한다.

### data 필드

| 필드 | 형식 | 용도 |
| --- | --- | --- |
| `type` | string | 푸시 종류. 현재 `CHAT_MESSAGE` |
| `roomId` | 숫자 문자열 | 선택 후 열 채팅방 ID |
| `messageId` | 숫자 문자열 | 원인 메시지 식별과 중복 처리 보조값 |
| `listingId` | string | 채팅방의 매물 식별자 |
| `listingTitle` | string | 매물 문맥 표시용 보조값 |
| `sentAt` | ISO-8601 string | 원인 메시지가 저장된 UTC 시각 |

FCM data 값은 모두 문자열이다. `roomId`와 `messageId`도 숫자 타입이 아니므로 앱에서 안전하게 파싱한다.

푸시에는 채팅 원문, 번역문, 문의·신청 상세정보, 사용자 개인정보, 인증 토큰이 포함되지 않는다. 실제 말풍선과 카드는 REST 메시지 이력으로 조회한다.

## 11. iOS에서 실제 payload 읽기

FCM은 Apple 앱용 메시지를 APNs 형식으로 변환한다.

- title과 body는 `UNNotificationContent`에서 읽는다.
- 백엔드가 보낸 custom data는 `userInfo`의 최상위 키에서 읽는다.
- `userInfo["data"]["type"]`처럼 `data` 아래에서 찾지 않는다.
- `userInfo`에는 `aps`와 Firebase 내부 메타데이터도 함께 있을 수 있으므로 필요한 키만 안전하게 읽는다.

Swift 처리 예시:

```swift
let content = response.notification.request.content
let userInfo = content.userInfo

let title = content.title
let body = content.body
let type = userInfo["type"] as? String
let roomIdText = userInfo["roomId"] as? String
let messageIdText = userInfo["messageId"] as? String
let listingId = userInfo["listingId"] as? String
let listingTitle = userInfo["listingTitle"] as? String
let sentAtText = userInfo["sentAt"] as? String
```

개념적인 APNs 수신 형태는 다음과 같다. Firebase 메타데이터는 생략했다.

```json
{
  "aps": {
    "alert": {
      "title": "채팅",
      "body": "\"고시원3\"으로부터 새 메시지가 도착했어요"
    }
  },
  "type": "CHAT_MESSAGE",
  "roomId": "10",
  "messageId": "125",
  "listingId": "68f1c7...",
  "listingTitle": "고시원3",
  "sentAt": "2026-08-29T06:30:00Z"
}
```

## 12. 앱 상태별 처리

| 앱 상태 | 처리 |
| --- | --- |
| foreground에서 같은 채팅방을 보고 있음 | STOMP로 새 메시지를 표시하고 푸시 배너는 생략 |
| foreground의 다른 화면 | `willPresent`에서 배너 표시 또는 앱 내부 배너 표시 |
| background | iOS 시스템 알림 표시 |
| 앱 종료 상태 | iOS 시스템 알림 표시, 선택 시 앱 실행 후 이동 처리 |
| 알림 권한 거부 | 시스템 알림은 표시되지 않지만 메시지는 서버에 저장됨 |
| `chatPushEnabled=false` | 앱 상태와 관계없이 백엔드가 새 채팅 FCM을 보내지 않음 |
| 로그아웃 | 기기 DELETE 성공 후 이전 사용자 푸시 대상에서 제외 |

현재 같은 `roomId`를 보고 있는지는 앱 navigation 상태에서 확인한다. 같은 방이면 STOMP 메시지와 푸시 배너가 동시에 보이지 않도록 하나만 표시한다.

### 현재 알림 소리 정책

현재 백엔드 payload에는 APNs `sound` 값이 없다. 따라서 배너는 표시할 수 있지만 시스템 알림 소리는 보장하지 않는다.

- foreground presentation option은 현재 `.banner`, `.list` 중심으로 구성한다.
- 기본 알림 소리가 필요하면 백엔드의 APNs `sound` 설정과 앱의 소리 권한을 함께 추가해야 한다.
- 앱 badge 값도 현재 푸시 범위에 포함되지 않는다.

## 13. 알림 선택 후 채팅방 이동

payload가 자동으로 화면을 이동시키지는 않는다. `UNUserNotificationCenterDelegate`의 notification response handler에서 직접 이동을 실행한다.

```text
사용자가 알림 선택
  → userInfo["type"] 확인
  → CHAT_MESSAGE인지 확인
  → userInfo["roomId"]를 숫자로 파싱
  → 앱 초기화와 로그인 상태 확인
  → 필요하면 pending deep link로 임시 보관
  → GET /api/v1/chat-rooms/{roomId}
  → GET /api/v1/chat-rooms/{roomId}/messages?size=30
  → 채팅방 화면 표시
  → 09 가이드에 따라 STOMP 연결·구독
```

처리 규칙:

- `type`이 없거나 모르는 값이면 crash하지 않고 기본 화면으로 이동한다.
- `roomId`가 없거나 숫자로 변환되지 않으면 채팅 목록으로 이동한다.
- 로그인이 풀렸다면 로그인 후 pending `roomId` 이동을 한 번만 실행한다.
- `listingId`가 아니라 `roomId`로 채팅방을 연다.
- payload만으로 말풍선이나 카드를 만들지 않는다.
- 채팅방 REST 조회가 실패하면 채팅 목록 또는 기본 화면으로 안전하게 이동한다.
- 알림 목록과 읽음 상태를 저장하지 않으므로 읽음 API를 호출하지 않는다.

간단한 분기 예시:

```text
handlePush(userInfo):
  type = userInfo["type"]
  if type != "CHAT_MESSAGE":
    openDefaultScreen()
    return

  roomId = parseLong(userInfo["roomId"])
  if roomId is invalid:
    openChatList()
    return

  openChatRoomAfterAppReady(roomId)
```

### 삭제했던 채팅방에서 온 알림

사용자가 채팅방을 삭제한 뒤 상대방의 실제 새 메시지가 도착하면 백엔드는 같은 `roomId`의 채팅방을 다시 표시한다.

- 알림을 누르면 같은 채팅방으로 들어간다.
- 삭제 이전 메시지는 다시 보이지 않는다.
- 삭제 이후에 생긴 새 메시지부터 조회된다.

다만 오래전에 발송된 지연 푸시를 나중에 선택했거나 접근 권한이 사라진 경우에는 채팅방 REST 조회가 실패할 수 있다. 이때는 채팅 목록 또는 기본 화면으로 이동한다.

## 14. 중복·누락 처리

FCM 푸시는 중복되거나 전달되지 않을 수 있다. 푸시를 채팅 데이터의 정본으로 사용하지 않는다.

- 같은 `messageId`를 연속으로 처리하면 배너와 화면 이동을 한 번으로 합칠 수 있다.
- foreground의 같은 방에서는 현재 `roomId`를 기준으로 푸시 배너를 억제한다.
- 푸시를 놓쳐도 앱을 열면 REST 이력에서 저장된 메시지를 확인한다.
- 푸시를 받았다는 이유만으로 STOMP checkpoint를 변경하지 않는다.
- REST 이력과 STOMP 이벤트는 기존 규칙대로 서버 `messageId`로 중복 제거한다.

```text
STOMP = 앱이 연결돼 있을 때 채팅 화면을 실시간 갱신
FCM   = background·종료 상태에서도 새 채팅을 알림
REST  = 저장된 채팅방과 메시지 데이터의 정본
```

알림 전용 WebSocket은 연결하지 않는다.

## 15. 보안·로그 규칙

- FCM 토큰 전체 값을 앱 로그, 분석 이벤트, crash report에 남기지 않는다.
- access token과 FCM 토큰을 같은 분석 payload에 담지 않는다.
- 기기 등록 API body를 통째로 로깅하지 않는다.
- 푸시 `userInfo`는 신뢰할 수 없는 입력처럼 필수값과 타입을 검증한다.
- `roomId`만 믿고 화면 데이터를 만들지 않고 인증된 REST API로 접근 여부를 확인한다.

진단 로그가 필요하면 HTTP status, `error.code`, 앱 버전, 토큰 존재 여부 정도만 기록한다.

## 16. 실제 기기 QA

### 기기 등록

- [ ] 로그인 후 PUT이 `204`로 성공한다.
- [ ] 같은 installationId로 PUT을 반복해도 오류가 없다.
- [ ] 새 FCM 토큰을 받으면 같은 installationId로 다시 PUT한다.
- [ ] 로그아웃 전에 DELETE를 호출하고 `204`를 받는다.
- [ ] 같은 기기에서 다른 계정으로 로그인하면 현재 계정으로 PUT한다.
- [ ] access token이나 FCM 토큰이 준비되기 전에 등록 API를 호출하지 않는다.

### 푸시 수신·이동

- [ ] foreground의 다른 화면에서 새 일반 메시지 알림이 한 번 표시된다.
- [ ] 새 문의 카드와 신청 카드 알림 문구가 각각 올바르게 표시된다.
- [ ] 같은 채팅방을 보고 있으면 STOMP 메시지만 표시하고 중복 배너를 생략한다.
- [ ] background 상태에서 시스템 알림이 표시된다.
- [ ] 앱 종료 상태에서 시스템 알림이 표시된다.
- [ ] 알림을 누르면 올바른 `roomId` 채팅방으로 이동한다.
- [ ] iOS 코드가 custom data를 `userInfo` 최상위 키에서 읽는다.
- [ ] 로그인 만료 상태에서는 로그인 후 원래 채팅방 이동을 한 번 실행한다.
- [ ] 알 수 없는 `type`이나 잘못된 `roomId`에도 crash하지 않는다.
- [ ] 삭제했던 방에 새 메시지가 오면 같은 방이 다시 표시되고 과거 이력은 보이지 않는다.
- [ ] 로그아웃한 뒤 이전 계정의 새 채팅 푸시가 오지 않는다.

### 채팅 푸시 수신 설정

- [ ] 설정 행이 없는 계정의 GET 결과가 `true`다.
- [ ] 스위치를 끄면 PATCH 응답과 재조회 결과가 `false`다.
- [ ] `false`인 동안 새 채팅 푸시가 오지 않는다.
- [ ] 앱 종료·재실행 후에도 GET 결과가 `false`다.
- [ ] 다른 기기에서 같은 계정으로 조회해도 `false`다.
- [ ] 스위치를 다시 켜면 이후 새 채팅부터 푸시가 온다.
- [ ] 알림을 끄고 켜는 동안에도 FCM 토큰 등록·갱신은 계속한다.

실제 알림 도착 테스트 전에는 다음 환경 연결도 백엔드·인프라 담당자와 확인한다.

- 설치 앱의 Bundle ID와 Firebase iOS 앱이 일치하는지
- 올바른 `GoogleService-Info.plist`를 사용하는지
- Firebase Console에 APNs 인증키가 등록됐는지
- 앱과 백엔드가 같은 Firebase 프로젝트를 사용하는지
- dev 백엔드에서 Firebase 발송이 활성화됐는지

기기 등록 API의 `204`는 FCM 토큰이 DB에 저장됐다는 뜻이다. 실제 APNs 전달 성공까지 보장하는 응답은 아니다.

## 17. 이번 범위에 없는 기능

- 서버 알림 목록 저장·조회
- 알림 클릭 읽음 처리
- 안 읽은 알림 개수와 badge API
- 알림 전용 WebSocket
- 알림에서 채팅 원문·번역문 표시
- Android 기기 등록과 Android 알림 UI
- 채팅방별 알림 음소거

이번 기능은 iOS의 새 일반 메시지·문의 카드·신청 카드 푸시, 사용자 단위 채팅 푸시 on/off, 채팅방 이동을 다룬다. 실제 채팅 데이터와 실시간 처리는 계속 REST·STOMP 계약을 따른다.
