# US-3-7 — 관리자 매물 심사(승인·반려)

> 모듈: 매물 등록 · 탐색 · 찜 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/03-listings-favorites.md)
>
> 관리자(`ROLE_USER`, `ACTIVE`, `userType=ADMIN`)가 임대인이 올린 매물을 심사하는 흐름이다. **인가가 두 겹**이다 — `SecurityConfig`의 `/api/v1/admin/**` → `hasRole("USER")` 매처가 온보딩 토큰을 걸러 내고, 서비스가 `user` 공개 API로 `userType=ADMIN`을 다시 확인해 아니면 `403 FORBIDDEN`이다. 매물 등록이 임대인 여부를 서비스에서 재검사하는 것과 **완전히 같은 모양**이며, 토큰에는 관리자 여부를 담지 않으므로 **권한 부여·회수가 즉시 반영**된다. **승인·반려 모두 상태를 가리지 않는다** — 잘못 반려한 매물을 되살리는 재승인, 공개 매물을 내리는 사후 반려, 이미 반려한 매물의 사유 정정이 모두 정상 경로다. 관리자의 오판을 되돌릴 수단이 서버에 있어야 하기 때문이며, 제약을 걸면 임대인 수정 API가 나오기 전까지 잘못 처리된 매물이 묶인다. 조회는 세입자 경로(`PUBLISHED` 고정)를 재사용하지 않고 **심사 전용 저장소 메서드**를 쓴다 — 세입자 조회의 안전장치를 풀지 않기 위해서다.

```mermaid
sequenceDiagram
    actor A as 관리자
    participant W as 관리자 웹
    participant SEC as 공통 보안 필터
    participant LIST as listing 모듈
    participant USER as user 공개 API
    participant DB as MongoDB

    Note over A,W: ⓪ 로그인 — 임대인 웹과 동일한 경로다<br/>이메일·비밀번호 local_accounts · refresh는 HttpOnly 쿠키

    A->>W: 관리자 계정으로 로그인
    W->>SEC: POST /api/v1/auth/login (permitAll)
    SEC-->>W: 200 accessToken + refresh 쿠키

    Note over A,DB: ① 심사 목록 — 모든 상태를 본다

    A->>W: 심사 화면 진입
    W->>SEC: GET /api/v1/admin/listings?status=PENDING<br/>Authorization: Bearer 정식 토큰
    Note over SEC: /api/v1/admin/** → hasRole("USER") 명시 매처<br/>없으면 anyRequest().authenticated()로 떨어져<br/>ROLE_ONBOARDING 토큰이 컨트롤러까지 닿는다
    SEC->>LIST: 인증된 요청 전달 (userId)
    LIST->>USER: getUserType(userId)
    USER-->>LIST: userType

    alt 관리자 아님 (TENANT · LANDLORD)
        LIST-->>W: 403 FORBIDDEN
        Note over LIST: 매처는 온보딩 완료까지만 본다<br/>관리자 여부는 여기서 DB로 판정한다
    else 관리자 (ADMIN)
        LIST->>DB: findForAdmin(statuses, page, size, sort)
        Note over DB: status 필터가 비면 조건을 생략한다<br/>세입자 조회 3종이 쓰는 PUBLISHED 고정 쿼리를 재사용하지 않는다
        DB-->>LIST: 매물 페이지 (모든 상태)
        LIST->>USER: getUserName(landlordId)
        Note over LIST,USER: 한 페이지 안에서 임대인별로 한 번만 묻는다<br/>한 임대인이 지점을 여럿 올리는 것이 정상이라 페이지 안 중복이 흔하다

        alt 이름이 있다
            USER-->>LIST: 이름
        else 이름 미설정 (소셜 미제공 · 탈퇴 익명화)
            USER-->>LIST: 빈 문자열
        else 계정 행이 없다
            USER--xLIST: UserNotFoundException
        end

        Note over LIST: 빈 문자열도 예외도 여기서 삼켜 landlordName 키만 생략한다<br/>그대로 흘리면 임대인 한 명 때문에 심사 목록 전체가 404가 된다<br/>심사 대상은 매물이고 이름은 표시 보조값이다
        LIST-->>W: 200 { content[], page }
    end

    Note over A,DB: ② 심사 상세 — 저장된 전 필드 + 임대인 이름

    A->>W: 매물 선택
    W->>SEC: GET /api/v1/admin/listings/{listingId}
    SEC->>LIST: 인증된 요청 전달
    LIST->>USER: getUserType(userId)
    USER-->>LIST: ADMIN
    LIST->>DB: findById(listingId)

    alt 없는 매물 · ObjectId 형식 아님
        DB-->>LIST: 없음
        LIST-->>W: 404 LISTING_NOT_FOUND
    else 존재
        DB-->>LIST: Listing
        LIST->>USER: getUserName(landlordId)
        USER-->>LIST: 이름 (미설정이면 빈 문자열 · 계정 행이 없으면 UserNotFoundException)
        LIST-->>W: 200 전 필드
        Note over LIST: landlordId · landlordName · businessRegistrationNumber ·<br/>설문 3종 · rejectionReason 포함<br/>세입자 응답이 감추는 값을 감추지 않는다<br/>landlordName만 매물 문서에 없는 값이다 — user에서 조회 시점에 조합한다<br/>부재 처리는 목록과 같다 — 삼켜서 그 키만 생략한다<br/>승인·반려 응답도 같은 구조라 같은 조합을 거친다
    end

    Note over A,DB: ③ 승인 또는 반려 — 상태를 바꾸는 두 액션

    alt 승인
        A->>W: 승인 클릭
        W->>SEC: POST /api/v1/admin/listings/{listingId}/approval
    else 반려
        A->>W: 사유 입력 후 반려 클릭
        W->>SEC: POST /api/v1/admin/listings/{listingId}/rejection<br/>{ reason }
        Note over SEC: reason은 @NotBlank @Size(max=500)<br/>누락·공백·초과는 400 INVALID_INPUT
    end

    SEC->>LIST: 인증된 요청 전달
    LIST->>USER: getUserType(userId)
    USER-->>LIST: ADMIN
    LIST->>DB: findById(listingId)
    DB-->>LIST: Listing

    alt 이미 공개 중인 매물의 재승인
        Note over LIST: approve()가 자기 자신을 돌려준다 — 아무 일도 하지 않는다<br/>updatedAt을 바꾸면 목록 기본 정렬이 흔들린다
        LIST-->>W: 200 심사 상세 (그대로)
    else 그 외 (상태 제약 없음)
        Note over LIST: approve() → PUBLISHED · rejectionReason 비움<br/>reject(reason) → REJECTED · 사유 저장<br/>updatedAt 갱신
        LIST->>DB: save(listing)
        DB-->>LIST: 저장 완료
        Note over LIST: 누가 어느 매물을 어떻게 처리했는지 log.info<br/>심사 이력 테이블은 후속
        LIST-->>W: 200 심사 상세
    end

    Note over A,DB: ④ 승인 결과 — 세입자 조회에 나타난다

    participant T as 세입자 앱
    T->>SEC: GET /api/v2/listings (permitAll)
    SEC->>LIST: 요청 전달
    LIST->>DB: search(PUBLISHED 고정)
    DB-->>LIST: 승인된 매물 포함
    LIST-->>T: 200 목록
    Note over T,DB: 승인 전에는 어느 조회에도 없던 매물이<br/>이 시점부터 보인다 — 이번 작업의 핵심 회귀 지점
```

## 왜 이렇게 갈랐나

- **인가를 두 겹으로 둔 이유.** 매처(`hasRole("USER")`)는 경로 단위로만 판단할 수 있어 `userType`을 볼 수 없다. 그렇다고 토큰에 관리자 여부를 실으면 권한을 회수해도 토큰 수명만큼 관리자로 남는다. 그래서 매처는 온보딩 완료까지만 거르고 **판정은 매 요청 DB 조회**로 한다 — 임대인 게이트가 이미 쓰는 방식이다.
- **심사 전용 조회 메서드를 새로 둔 이유.** 세입자용 조회 3종은 저장소에서 `status = PUBLISHED`를 조건 앞머리에 고정해 두었다. 그 자리를 파라미터로 열면 호출자가 상태를 넘기지 않았을 때 비공개 매물이 세입자에게 샐 수 있다. 상태를 받는 경로를 **따로** 만들어 "세입자 경로는 공개 고정, 관리자 경로만 상태를 받는다"를 타입으로 가른다.
- **임대인 이름을 스냅샷하지 않고 조회 시점에 조합하는 이유.** 매물 문서에 임대인 이름을 함께 저장하면 스키마 변경과 마이그레이션, 임대인이 프로필에서 이름을 고칠 때마다의 동기화, 이미 쌓인 문서의 백필이 줄줄이 따라온다. 그런데 심사자가 보려는 것은 **지금 그 계정의 이름**이지 등록 당시의 이름이 아니고, 등록 시점의 이름을 보존해야 할 요구는 어디에도 없다. 그래서 매물에는 `landlordId`만 남기고, 이름은 `user` 공개 API의 `getUserName`으로 **조회할 때마다 애플리케이션 레벨에서 조합**한다 — 매물은 MongoDB, 계정은 MySQL이라 애초에 저장소에서 조인할 수도 없고, `booking`이 예약자 성명을 스냅샷 없이 실시간 조인하는 것과 같은 선택이다. 대신 조합은 실패할 수 있으므로(이름이 빈 계정, 행이 사라진 계정) **부재를 에러가 아니라 키 생략으로** 다룬다. 심사 대상은 매물이고 이름은 표시 보조값이라, 이름 하나 때문에 심사가 멈추는 쪽이 훨씬 나쁘다.
- **그 키 생략이 곧 예외 포획이라는 점은 짚어 둘 값이 있다.** `getUserName`은 이름이 없으면(미설정·탈퇴 익명화 — 탈퇴는 행이 남고 이름만 빈다) 빈 문자열을 주지만, **계정 행 자체가 없으면 `UserNotFoundException`을 던진다**. 이 예외를 그대로 흘리면 심사 목록 전체가 `404 USER_NOT_FOUND`가 되는데, 그 코드는 "매물이 없다"도 "관리자가 없다"도 아니라서 관리자 화면이 해석할 수 없다. 그래서 `listing`이 **그 예외 하나만** 잡아 이름을 비우고 나머지는 그대로 흘린다. 같은 쿼리를 쓰는 `chat`(상대 이름)·`booking`(예약자 성명)은 지금 이 예외를 잡지 않는데, 거기서는 **상대가 없으면 그 대화·예약 자체가 성립하지 않아** 실패가 곧 정답이기 때문이다. 심사는 반대다 — 임대인 계정이 사라져도 그 매물은 여전히 내려야 할지 판단해야 할 대상으로 남는다.
- **그 포획은 심사 트랜잭션 밖에서 일어나야 한다.** 심사 4종은 모두 트랜잭션 안에서 도는데, 그 안에서 잡으면 잡히기도 전에 스프링이 **바깥 트랜잭션을 rollback-only로 표시**한다(참여 트랜잭션의 실패 규칙). 그러면 정상 반환해도 커밋에서 터져 `500`이 나가고, 부재를 조용히 넘기려던 의도가 정확히 뒤집힌다 — 잡았는데도 목록 전체가 죽는다. 그래서 이름 조회만 **트랜잭션을 잠시 밀어 두는**(`NOT_SUPPORTED`) 전용 협력자에 두었다. 실패는 그 조회가 새로 연 트랜잭션 안에서 끝나고, 밀어 뒀던 트랜잭션은 아무 표시 없이 되돌아온다.
- **상태 전이에 제약을 두지 않은 이유.** 심사는 사람이 하는 판단이라 틀릴 수 있는데, 제약을 걸면 **관리자가 자기 오판을 되돌릴 수단이 서버에 없어진다** — 임대인 수정 API가 나오기 전까지 잘못 반려한 매물이 손댈 수 없는 상태로 묶인다. 잘못 반려한 매물의 재승인, 문제가 발견된 공개 매물의 사후 반려, 사유 정정이 모두 정상 경로다. 대신 **이미 공개 중인 매물의 재승인만 아무 일도 하지 않는다** — 결과는 같지만 `updatedAt`이 바뀌면 세입자 목록의 기본 정렬(찜 수 → 최신 수정순)에서 그 매물만 위로 올라가는, 눈에 띄지 않는 부작용이 생긴다.
- **승인·반려를 액션 두 개로 나눈 이유.** 하나의 상태 변경 API였다면 `status` 값에 따라 `reason` 필수 여부가 갈리는 조건부 검증이 되고, 승인 요청에 사유가 실려 와도 타입으로 막을 수 없다. 액션을 나누면 "반려에는 사유가 필요하다"가 요청 타입 자체로 강제된다.
- **관리자가 세입자·임대인 API에 닿지 못하는 이유.** `ADMIN`은 세입자·임대인과 병존하지 않는 제3의 유형이라, 각 모듈의 서비스가 **세입자 또는 임대인만 통과시키는 허용 목록 게이트**를 둔다. 관리자를 콕 집어 거부하지 않으므로 모듈은 `ADMIN`이라는 개념을 알 필요가 없고, 나중에 유형이 늘어도 자동으로 거부된다.
