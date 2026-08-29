# 프론트엔드 iOS 채팅 FCM 푸시 연동 가이드

> 대상: iOS 앱 프론트엔드 개발자
>
> 최종 수정: 2026-08-29
>
> Swagger: `Users → PUT/DELETE /api/v1/users/me/push-devices/{installationId}`

이 문서는 앱이 Firebase에서 발급받은 FCM 토큰을 백엔드에 등록하고, 새 채팅 푸시를 받았을 때 알림 표시와 채팅방 이동을 구현하는 방법을 설명한다.

채팅 화면의 REST·STOMP 전체 연결은 [09-frontend-stomp-guide.md](09-frontend-stomp-guide.md)를 참고한다. 이 문서는 iOS 외부 푸시에 필요한 내용만 다룬다. 세부 백엔드 설계와 후속 구현 계획의 정본은 [future/06-chat-notifications.md](future/06-chat-notifications.md)다.

## 1. 먼저 확인할 현재 구현 상태

### 지금 백엔드에 구현된 기능

- iOS 앱 설치본과 FCM 토큰 등록·갱신
- 같은 설치본의 토큰 변경 시 기존 행 갱신
- 같은 기기에서 로그인 계정이 바뀔 때 현재 사용자로 소유권 변경
- 로그아웃할 설치본 삭제
- 등록·삭제 API의 인증·인가와 Swagger 문서
- 새 `TEXT`·`INQUIRY_CARD`·`BOOKING_CARD` 저장 후 알림 이벤트 발행
- Firebase Admin SDK를 이용한 실제 FCM 발송
- 아래에 정의한 `notification`과 `data` payload 전달
- FCM이 영구 무효라고 응답한 토큰의 자동 삭제

### 아직 함께 확인해야 하는 항목

- dev 백엔드의 `APP_FIREBASE_ENABLED=true` 반영
- Firebase Console의 APNs 인증키와 iOS Bundle ID 연결
- 실제 iPhone의 foreground·background·종료 상태 수신과 클릭 이동

프론트는 **알림 권한·installationId·FCM 토큰·등록/삭제 API·푸시 클릭 처리**를 연동할 수 있다. 실제 휴대폰 상단 알림은 백엔드 코드가 dev에 배포되고 Firebase/APNs 설정이 연결된 뒤 실제 iPhone에서 함께 검증한다.

## 2. 핵심 용어

| 용어 | 쉬운 설명 |
| --- | --- |
| FCM | Firebase Cloud Messaging. 백엔드가 푸시를 보내는 외부 서비스 |
| APNs | Apple Push Notification service. FCM이 iPhone에 알림을 최종 전달할 때 거치는 Apple 서비스 |
| FCM 토큰 | FCM이 한 앱 설치본에 발급한 푸시 발송 주소. 영구값이 아니며 바뀔 수 있음 |
| `installationId` | 앱이 생성해 보관하는 설치본 UUID. 아이폰 하드웨어 주소가 아님 |
| `notification` | iOS 알림 배너와 알림 센터에 표시할 제목·본문 |
| `data` | 앱이 푸시 종류를 구분하고 클릭 후 이동할 화면을 결정하는 부가 데이터 |
| foreground | 앱이 현재 화면에 보이는 상태 |
| background | 앱이 화면에는 없지만 메모리에 남아 있는 상태 |
| 종료 상태 | 사용자가 앱을 열지 않았거나 앱 process가 종료된 상태 |

FCM 토큰과 `installationId`는 역할이 다르다.

```text
installationId = 이 앱 설치본을 계속 알아보는 고정 이름
FCM 토큰       = 지금 이 설치본에 푸시를 보낼 현재 주소
```

FCM 토큰이 바뀌어도 같은 `installationId`로 다시 등록하면 백엔드가 기존 행의 토큰을 교체한다.

## 3. 프론트와 백엔드의 책임

| 구분 | 책임 |
| --- | --- |
| iOS 앱 | 알림 권한 요청, installationId 생성·보관, FCM 토큰 취득·갱신 감지, 등록·삭제 API 호출 |
| 백엔드 | JWT 사용자와 installationId·FCM 토큰 연결, 새 채팅 수신자 결정, 최종 payload 조립, FCM 발송 |
| Firebase FCM | 백엔드 요청을 받아 APNs로 전달 |
| APNs/iOS | background·종료 상태에서 시스템 알림 표시 |

백엔드는 앱의 FCM 토큰을 만들 수 없다. 앱이 Firebase SDK에서 받은 현재 토큰을 백엔드에 알려 줘야 한다.

반대로 앱이 background·종료 상태일 때 표시할 `notification.title`과 `notification.body`는 백엔드가 완성해 보낸다. 프론트가 `type`과 `listingTitle`을 조합해 시스템 알림 문구를 새로 만드는 구조가 아니다.

## 4. 전체 흐름

### 4.1 앱 실행·로그인 후 기기 등록

```text
앱 실행
  → Firebase 초기화
  → iOS 알림 권한 확인·요청
  → APNs 원격 알림 등록
  → Firebase SDK에서 FCM 토큰 취득
  → 로컬에서 installationId 조회 또는 생성
  → 사용자 로그인과 access token 준비
  → PUT 기기 등록 API 호출
  → 204 확인
```

`installationId`, FCM 토큰, access token 세 값이 모두 준비된 뒤 등록 API를 호출한다. 이 세 값의 준비 순서는 앱 실행 상황에 따라 달라질 수 있으므로 한곳에서 조건을 모아 등록을 시도하는 편이 안전하다.

### 4.2 FCM 토큰이 바뀐 경우

```text
Firebase SDK가 새 FCM 토큰 callback 전달
  → 로컬에 현재 토큰 갱신
  → 로그인 상태라면 같은 installationId로 PUT 재호출
  → 백엔드가 기존 행의 토큰 교체
```

새 토큰을 받았다고 `installationId`를 새로 만들지 않는다.

### 4.3 새 채팅 푸시와 클릭 이동

> 이 흐름은 현재 백엔드 FCM 발송 코드가 배포되고 dev Firebase가 활성화된 뒤 동작한다.

```text
새 TEXT·문의 카드·신청 카드 중 하나가 MySQL에 저장됨
  → 백엔드가 내 등록 기기로 FCM 발송
  → FCM이 APNs를 거쳐 iPhone에 전달
  → iOS가 상단 알림 표시
  → 사용자가 알림 선택
  → 앱이 data.type과 data.roomId 확인
  → REST 메시지 이력 조회
  → 채팅방 화면 표시와 STOMP 연결
```

푸시는 새 채팅 활동이 있다는 신호다. 실제 말풍선과 카드 데이터는 푸시 payload가 아니라 기존 채팅 REST 이력에서 가져온다. `TEXT`는 상대 참여자에게, 문의·신청 카드는 해당 매물 임대인에게 발송한다.

## 5. iOS·Firebase 선행 설정

다음 항목이 준비돼야 실제 iPhone에 알림이 도착한다.

- Firebase 프로젝트에 iOS 앱이 올바른 Bundle ID로 등록돼 있다.
- iOS 프로젝트에 해당 Firebase 앱의 `GoogleService-Info.plist`가 포함돼 있다.
- 앱 시작 시 Firebase SDK를 초기화한다.
- Xcode target에서 Push Notifications capability를 활성화한다.
- Firebase Console에 해당 앱용 APNs 인증키가 등록돼 있다.
- 앱이 `UNUserNotificationCenter`를 통해 사용자 알림 권한을 요청한다.
- 앱이 `UIApplication.registerForRemoteNotifications()`를 호출한다.
- Firebase Messaging delegate에서 현재 FCM 토큰과 이후 토큰 갱신을 받는다.

Firebase AppDelegate swizzling을 끈 프로젝트라면 APNs device token을 Firebase Messaging에 직접 연결해야 한다. 기본 swizzling을 사용한다면 Firebase SDK가 이 연결을 처리한다. 프로젝트 설정에 따라 둘 중 한 방식만 적용한다.

시뮬레이터만으로 완료 판단하지 않는다. APNs·FCM·background·종료 상태는 dev Firebase 설정이 연결된 실제 iPhone에서 최종 확인한다.

## 6. installationId 생성·보관

`installationId`는 앱이 UUID로 한 번 생성하고 같은 설치본에서 계속 재사용한다.

```text
예: e7714046-1634-4dc1-a97e-8c1f91a72483
```

권장 규칙:

1. 앱 시작 시 로컬 저장소에서 `installationId`를 읽는다.
2. 값이 없으면 UUID v4를 한 번 생성해 저장한다.
3. 로그인·로그아웃에는 값을 삭제하거나 다시 만들지 않는다.
4. FCM 토큰이 바뀌어도 같은 값을 사용한다.
5. 사용자 ID, 이메일, 아이폰 이름, IDFV를 `installationId` 대신 사용하지 않는다.

Keychain 등 앱 정책에 맞는 영속 저장소를 사용할 수 있다. 중요한 점은 **같은 설치본에서 안정적으로 같은 UUID를 반환하는 것**이다. 앱 삭제·재설치 때 Keychain 값을 유지할지 초기화할지는 iOS 팀이 한 정책으로 통일한다. 어느 쪽이든 앱이 서버에 현재 토큰을 다시 `PUT`하면 백엔드가 최신 연결로 정리한다.

## 7. FCM 토큰 취득과 갱신

FCM 토큰은 한 번 발급받고 영원히 사용하는 값이 아니다. Firebase SDK의 토큰 callback이 호출될 때마다 현재 값으로 취급한다.

토큰이 바뀔 수 있는 예:

- 앱 재설치 또는 기기 복원
- Firebase SDK 데이터 초기화
- APNs·Firebase 등록 정보 변경
- Firebase의 보안·운영 정책에 따른 토큰 회전

프론트 처리 원칙:

- 새 토큰을 받으면 이전 토큰과 문자열이 같은지 비교할 수 있다.
- 값이 달라졌고 사용자가 로그인 상태라면 즉시 같은 `installationId`로 등록 API를 호출한다.
- 앱 실행 때 토큰이 같더라도 `PUT`을 다시 호출해 현재 사용자 연결과 `lastSeenAt`을 갱신해도 된다.
- 토큰을 자르거나 소문자·대문자로 바꾸지 않고 Firebase가 준 문자열 그대로 보낸다.
- 전체 토큰을 콘솔 로그, 분석 이벤트, crash report에 남기지 않는다.

## 8. 기기 등록·갱신 API

앱 실행·로그인 또는 FCM 토큰 갱신 시 호출한다.

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

| 값 | 위치 | 필수 | 설명 |
| --- | --- | --- | --- |
| `installationId` | path | 예 | 앱이 생성·보관하는 UUID |
| `fcmToken` | body | 예 | Firebase SDK가 반환한 현재 토큰. 1~1024자 |
| `platform` | body | 예 | 현재는 `IOS`만 허용 |
| `userId` | 보내지 않음 | - | 백엔드가 access token에서 확인 |

성공 응답:

```http
HTTP/1.1 204 No Content
```

`204`에는 JSON body가 없다. 공통 응답 래퍼의 `success`를 찾지 않고 HTTP status로 성공을 판단한다.

### 같은 값을 여러 번 보내도 되는가?

가능하다. 이 API는 같은 `installationId`를 새 행으로 계속 추가하지 않고 현재 값으로 갱신하는 `PUT`이다.

| 요청 상황 | 백엔드 동작 |
| --- | --- |
| 처음 보는 installationId | 새 기기 행 생성 |
| 같은 installationId·같은 토큰 | 중복 행 없이 마지막 확인 시각 갱신 |
| 같은 installationId·새 토큰 | 기존 행의 FCM 토큰 교체 |
| 같은 installationId·다른 로그인 사용자 | 현재 JWT 사용자로 소유권 변경 |
| 같은 토큰이 다른 installation에 남아 있음 | 오래된 연결 제거 후 현재 installation에 연결 |

## 9. 권장 등록 호출 시점

| 시점 | 호출 여부 | 이유 |
| --- | --- | --- |
| 로그인 성공 후 | 호출 | 현재 사용자와 기기를 연결 |
| 로그인 상태로 앱 시작 후 | 호출 | 현재 토큰·소유 관계를 다시 확인 |
| Firebase 토큰 갱신 callback | 호출 | 백엔드의 오래된 토큰을 교체 |
| 앱이 foreground로 올 때마다 | 선택 | 매번 필수는 아니며 앱 시작 정책과 중복되지 않게 구성 |
| 알림 권한 거부 상태 | 토큰이 있으면 호출 가능 | 권한이 나중에 바뀔 수 있고 현재 등록 상태를 유지할 수 있음 |
| 로그아웃 직후 | 호출하지 않음 | 먼저 삭제 API를 호출해야 함 |

등록 함수는 여러 위치에서 직접 중복 구현하지 않고 다음 조건을 확인하는 한 함수로 모으는 것을 권장한다.

```text
registerPushDeviceIfReady()
  if accessToken 없음 → 대기
  if installationId 없음 → 생성 후 계속
  if fcmToken 없음 → Firebase callback 대기
  세 값이 모두 있음 → PUT 호출
```

동시에 여러 callback이 들어올 수 있다면 프론트에서 진행 중 요청을 합치거나 마지막 토큰으로 한 번 더 호출한다. 백엔드도 같은 installation 행을 잠그고 갱신하지만 불필요한 네트워크 요청까지 막아 주는 것은 아니다.

## 10. 로그아웃 기기 삭제 API

로그아웃할 때 access token을 지우기 **전에** 호출한다.

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
사용자 로그아웃 선택
  → 현재 access token으로 DELETE push-device 호출
  → 백엔드 logout API 호출
  → 로컬 access/refresh token 삭제
  → 로그인 화면 이동
```

- 현재 사용자가 소유한 해당 installation만 삭제한다.
- 이미 없거나 다른 사용자의 installation이면 아무 것도 지우지 않고 `204`를 반환한다.
- 다른 휴대폰과 다른 installation의 푸시는 계속 유지한다.
- 로컬 `installationId` 자체는 삭제하지 않는다. 다음 로그인에서 같은 값을 다시 등록한다.

네트워크 오류 때문에 DELETE가 실패하면 “서버에서 삭제됐다”고 가정하지 않는다. 짧은 재시도를 적용할 수 있지만 사용자 로그아웃을 무한히 막지는 않는다. 실패를 민감값 없이 기록하고, 다음 로그인 시 같은 installationId로 `PUT`해 현재 사용자 연결을 다시 확정한다.

## 11. 등록·삭제 오류 처리

오류 응답은 기존 API 공통 형태를 사용한다.

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

| HTTP·code | 의미 | 프론트 처리 예시 |
| --- | --- | --- |
| `400 INVALID_INPUT` | FCM 토큰이 비었거나 너무 김, platform 누락 | 요청 구성 버그로 기록하고 값 재확인 |
| `400 MALFORMED_REQUEST` | installationId가 UUID가 아니거나 JSON·enum 형식이 잘못됨 | 앱 버전·직렬화 코드 확인 |
| `401 UNAUTHENTICATED` | access token 없음·유효하지 않음 | 재로그인 또는 토큰 재발급 흐름 |
| `401 TOKEN_EXPIRED` | access token 만료 | 재발급 성공 후 같은 요청 재시도 |
| `403 AUTH_ONBOARDING_REQUIRED` | 온보딩 완료 전 토큰 | 온보딩 완료 뒤 등록 재시도 |

프론트 분기는 언어에 따라 달라질 수 있는 `error.message`가 아니라 `error.code`를 기준으로 한다.

FCM 토큰 등록 실패를 사용자에게 기술 오류 문구 그대로 보여 줄 필요는 없다. 채팅 자체는 REST·STOMP로 계속 사용할 수 있으므로 앱 내부 재시도와 진단 로그를 우선한다.

## 12. 수신할 FCM payload 계약

> 다음 payload는 백엔드 FCM 발송 코드가 배포되고 Firebase가 활성화된 환경에서 전달된다.

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

### 12.1 notification 영역

| 원인 메시지 | `notification.title` | `notification.body` |
| --- | --- | --- |
| 일반 `TEXT` | `채팅` | `"{listingTitle}"으로부터 새 메시지가 도착했어요` |
| `INQUIRY_CARD` | `채팅` | `"{listingTitle}"에 새로운 문의가 도착했어요` |
| `BOOKING_CARD` | `채팅` | `"{listingTitle}"에 새로운 신청이 도착했어요` |

백엔드는 내부 채팅 이벤트의 메시지 종류와 `listingTitle`을 이용해 title과 body를 완성한다. 세 종류 모두 클릭 후 같은 채팅방을 열기 때문에 프론트에 보내는 `data`에는 별도 `messageType`이 없다.

background·종료 상태에서는 iOS가 이 문구로 시스템 알림을 표시한다. foreground에서 앱이 자체 배너를 보여 주더라도 서버가 보낸 title/body를 사용하고 서로 다른 문구를 다시 조립하지 않는 것을 권장한다.

알림의 “2시간 전” 같은 시스템 표시 시각은 iOS가 관리한다. `notification`에 세 번째 시각 필드를 표시하는 구조가 아니다. 앱 내부 UI에서 원래 메시지 시각이 필요할 때는 `data.sentAt`을 사용한다.

### 12.2 data 영역

| 필드 | 형식 | 사용 목적 |
| --- | --- | --- |
| `type` | string | 푸시 종류 분기. 현재 `CHAT_MESSAGE` |
| `roomId` | 숫자 문자열 | 클릭 후 열 채팅방 ID |
| `messageId` | 숫자 문자열 | 중복 처리 방지와 원인 메시지 식별 |
| `listingId` | string | 채팅방의 매물 식별 |
| `listingTitle` | string | 매물 문맥 표시와 진단용 보조 값 |
| `sentAt` | ISO-8601 string | 푸시 원인 메시지가 MySQL에 저장된 UTC 시각 |

FCM `data` 값은 모두 문자열이다. `roomId`와 `messageId`도 JSON 숫자가 아니라 문자열로 오므로 앱에서 안전하게 파싱한다.

payload에 포함되지 않는 값:

- 사용자 ID·이메일·전화번호
- access token·refresh token·FCM 토큰
- 채팅 원문·자동 번역문
- 문의·신청 카드의 상세 payload와 백엔드 내부 `messageType`
- 알림 읽음 상태나 알림 목록 ID

푸시에는 메시지 내용이 없으므로 잠금 화면에 민감한 대화가 노출되지 않는다.

## 13. 앱 상태별 처리

| 앱 상태 | 권장 처리 |
| --- | --- |
| 현재 같은 채팅방을 foreground에서 보고 있음 | STOMP로 말풍선을 표시하고 시스템 배너는 생략해 중복 알림 방지 |
| foreground의 다른 화면 | iOS `willPresent` 정책에 따라 배너·소리 표시 또는 앱 내부 배너 표시 |
| background | APNs가 `notification`으로 시스템 알림 표시 |
| 종료 상태 | APNs가 시스템 알림 표시, 클릭하면 앱 실행 후 딥링크 처리 |
| 알림 권한 거부 | 시스템 알림은 표시되지 않음. 채팅 메시지는 DB에 정상 저장 |
| 로그아웃 | DELETE가 성공했다면 이전 사용자의 푸시 대상에서 제외 |

iOS는 foreground일 때 알림 표시를 앱 delegate에 맡긴다. 현재 같은 `roomId`를 보고 있는지 확인해 배너를 생략하고, 다른 화면이면 `.banner`, `.sound` 등 제품 정책에 맞는 presentation option을 반환한다.

STOMP와 FCM은 목적이 다르다.

```text
STOMP = 앱이 연결돼 있을 때 즉시 채팅 화면 갱신
FCM   = 앱이 background·종료 상태여도 새 채팅을 알림
```

알림 전용 WebSocket을 새로 연결하지 않는다.

## 14. 알림 클릭 후 채팅방 이동

`data`가 자동으로 화면을 이동시키는 것은 아니다. iOS notification response handler가 값을 검증하고 앱 navigation을 실행한다.

권장 순서:

```text
사용자가 알림 선택
  → data.type 읽기
  → CHAT_MESSAGE인지 확인
  → roomId를 숫자로 안전하게 파싱
  → 앱 초기화·로그인 상태 확인
  → 필요하면 pending deep link로 잠시 보관
  → GET /api/v1/chat-rooms/{roomId} 호출
  → 메시지 이력 REST 조회
  → 채팅방 화면 표시
  → 09 가이드 순서로 STOMP 연결·구독
```

처리 규칙:

- `type`이 없거나 모르는 값이면 강제 crash하지 않고 앱 기본 화면을 연다.
- `roomId`가 없거나 숫자로 파싱되지 않으면 채팅 목록으로 이동한다.
- 로그인이 풀렸다면 로그인 완료 뒤 pending `roomId`를 한 번만 복원할 수 있다.
- 사용자가 방을 삭제했거나 더 이상 접근할 수 없으면 채팅 목록 또는 기본 화면으로 안전하게 돌아간다.
- 푸시 payload만으로 말풍선을 만들지 않는다. REST 메시지 이력이 화면 데이터의 정본이다.
- 알림 목록과 읽음 상태를 저장하지 않으므로 클릭할 때 읽음 API를 호출하지 않는다.

간단한 분기 예시:

```text
handlePush(data):
  if data.type != "CHAT_MESSAGE":
    openDefaultScreen()
    return

  roomId = parseLong(data.roomId)
  if roomId is invalid:
    openChatList()
    return

  openChatRoomAfterAppReady(roomId)
```

`listingId`가 아니라 `roomId`로 채팅방을 연다. `listingId`는 매물 문맥을 설명하는 보조 값이며, 같은 매물에도 사용자 조합별로 채팅방이 다를 수 있다.

## 15. 중복과 누락 처리

FCM은 같은 알림을 다시 전달하거나 전달하지 못할 수 있다. 푸시 자체를 정확히 한 번 전달되는 데이터 채널로 가정하지 않는다.

- 같은 `messageId`를 연속으로 처리하면 화면 이동·앱 내부 배너를 한 번으로 합칠 수 있다.
- foreground의 같은 방에서는 STOMP와 푸시가 모두 올 수 있으므로 현재 `roomId`를 기준으로 시스템 배너를 억제한다.
- 푸시를 놓쳐도 앱을 열면 REST 이력에서 저장된 메시지를 확인한다.
- 푸시를 받았다는 이유만으로 STOMP checkpoint를 전진시키지 않는다.
- 알림 클릭 뒤 REST 이력과 실시간 이벤트를 합칠 때는 기존 규칙대로 서버 `messageId`로 중복 제거한다.

## 16. 보안·로그 규칙

- FCM 토큰 전체 값을 앱 로그, 네트워크 로그, 분석 이벤트, crash report에 남기지 않는다.
- access token과 FCM 토큰을 같은 분석 payload에 담지 않는다.
- registration API body를 통째로 로깅하지 않는다.
- `installationId`는 하드웨어 주소가 아니지만 불필요하게 외부 분석 도구에 전송하지 않는다.
- 푸시 `data`는 신뢰할 수 없는 외부 입력처럼 필수값과 타입을 검증한다.
- push payload의 `roomId`만 믿고 화면 데이터를 만들지 않고 인증된 REST API로 접근 가능 여부를 다시 확인한다.

진단 로그가 필요하면 토큰 원문 대신 성공/실패, HTTP status, `error.code`, 토큰 존재 여부, 앱 버전 정도만 기록한다.

## 17. 환경별 확인

dev와 prod는 다음 값이 서로 맞아야 한다.

| 확인 항목 | 설명 |
| --- | --- |
| Bundle ID | Firebase에 등록된 iOS 앱과 설치한 앱의 Bundle ID가 같아야 함 |
| `GoogleService-Info.plist` | dev/prod 앱이 의도한 Firebase 프로젝트 파일을 사용해야 함 |
| APNs 인증키 | Firebase Console의 iOS 앱에 등록돼 있어야 함 |
| 백엔드 Firebase project | 앱이 토큰을 발급받은 Firebase 프로젝트와 백엔드 발송 프로젝트가 같아야 함 |
| API base URL | dev 앱은 dev 백엔드의 기기 등록 API를 사용 |
| 실제 기기 | background·종료 상태 테스트는 실제 iPhone으로 수행 |

프로젝트가 다르면 등록 API는 `204`로 성공해도 나중에 백엔드가 그 토큰으로 정상 발송하지 못한다. API 성공은 “DB에 저장됐다”는 뜻이며, APNs 전달까지 성공했다는 뜻은 아니다.

## 18. QA 시나리오

### 기기 등록 단계에서 지금 확인할 항목

- 새 로그인 뒤 PUT이 한 번 이상 성공하고 `204`를 받는다.
- 같은 installationId로 PUT을 반복해도 오류가 없다.
- FCM 토큰 callback에서 새 값이 오면 같은 installationId로 다시 PUT한다.
- 로그아웃 전에 DELETE를 호출하고 `204`를 받는다.
- 같은 기기에서 다른 계정으로 로그인하면 현재 계정으로 PUT한다.
- access token이 없을 때 등록 API를 먼저 호출하지 않는다.
- 온보딩 토큰이면 `403 AUTH_ONBOARDING_REQUIRED`를 처리한다.

### 후속 FCM 발송 배포 뒤 확인할 항목

- foreground의 다른 화면에서 새 TEXT 알림이 한 번 표시된다.
- 새 문의 카드가 저장되면 임대인에게 문의 알림이 한 번 표시된다.
- 새 신청 카드가 저장되면 임대인에게 신청 알림이 한 번 표시된다.
- 연속 중복 문의와 같은 신청의 재처리에는 추가 푸시가 표시되지 않는다.
- 현재 같은 채팅방을 보고 있으면 STOMP 말풍선만 표시하고 중복 배너를 억제한다.
- background 상태에서 상단 알림이 표시된다.
- 앱 종료 상태에서 상단 알림이 표시된다.
- 알림 선택 시 올바른 `roomId` 채팅방으로 이동한다.
- 로그인 만료 상태에서 로그인 뒤 원래 채팅방 이동을 한 번 복원한다.
- 알 수 없는 `type`이나 잘못된 `roomId`에도 crash하지 않는다.
- 알림 권한을 거부해도 앱을 열면 REST 이력에 메시지가 존재한다.
- 로그아웃한 뒤 이전 계정의 새 채팅 푸시가 오지 않는다.

## 19. 프론트 구현 체크리스트

- [ ] Firebase iOS 앱과 Bundle ID가 일치한다.
- [ ] 올바른 `GoogleService-Info.plist`가 target에 포함돼 있다.
- [ ] Push Notifications capability와 APNs 인증키 연결을 확인했다.
- [ ] 사용자 알림 권한 요청과 원격 알림 등록을 구현했다.
- [ ] installationId를 UUID로 한 번 생성하고 안정적으로 보관한다.
- [ ] 로그인·로그아웃·토큰 갱신에도 같은 installationId를 사용한다.
- [ ] Firebase Messaging token callback의 현재 토큰을 보관한다.
- [ ] access token·installationId·FCM 토큰이 준비되면 PUT을 호출한다.
- [ ] PUT/DELETE의 `204`를 body 없이 성공 처리한다.
- [ ] 로그아웃 access token 삭제 전에 DELETE를 호출한다.
- [ ] API 오류는 `error.message`가 아니라 `error.code`로 분기한다.
- [ ] FCM 토큰 전체 값을 로그와 분석 도구에 남기지 않는다.
- [ ] foreground의 현재 채팅방에서는 중복 시스템 배너를 억제한다.
- [ ] `data.type=CHAT_MESSAGE`일 때만 채팅 딥링크로 처리한다.
- [ ] `data.roomId` 문자열을 검증·파싱해 채팅방을 연다.
- [ ] 앱 초기화·로그인이 끝날 때까지 pending 딥링크를 안전하게 보관한다.
- [ ] 푸시 payload로 말풍선을 만들지 않고 REST 이력을 조회한다.
- [ ] 알림 클릭 시 읽음 API를 호출하지 않는다.
- [ ] background·종료 상태를 실제 iPhone에서 확인한다.

## 20. 이번 알림 범위에서 하지 않는 것

- 서버 알림 목록 저장·조회
- 알림 클릭 읽음 처리
- 안 읽은 알림 개수와 badge API
- 알림 전용 WebSocket
- 채팅 원문·번역문을 알림 본문에 표시
- Android 기기 등록과 Android 알림 UI

이번 기능은 iOS의 새 `TEXT`·`INQUIRY_CARD`·`BOOKING_CARD` 외부 푸시와 채팅방 이동을 대상으로 한다. 세 종류 모두 같은 `data.type=CHAT_MESSAGE`와 `roomId`로 처리하며, 앱 내부 채팅 정본과 실시간 처리는 계속 REST·STOMP 계약을 따른다.
