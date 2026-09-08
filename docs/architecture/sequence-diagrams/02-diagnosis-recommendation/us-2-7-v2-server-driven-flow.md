# US-2-7 (v2) — 서버 주도 진단 흐름 + 지역 매물 부재 시 재질의·종료

> 모듈: 맞춤 진단 & 매물 추천 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/02-diagnosis-recommendation.md) · 결정: [ADR-0036](../../../adr/0036-diagnosis-v2-server-driven-flow.md)
>
> **범위**: 진단의 정본 경로인 **서버 주도 대화형 흐름**(issue #157). **서버는 질문과 분기만 주도한다** — 클라이언트가 **`POST /api/v2/diagnoses/start`** 로 진단을 시작하고 **`POST /api/v2/diagnoses/next`** 로 답을 이어 보내면, 서버가 **직전에 낸 문항**(`pendingField`)에서 다음 질문·확정 시점을 판단한다. ① 지역 답 직후 매칭 매물이 0건이면 서버가 예외적으로 미리 필터링해 **"다른 지역 방을 찾아보시겠어요?"** 문항(카탈로그의 일반 질문 `field=regionRetry`)을 끼워 넣고, **"예" → `RESTART`(클라가 `/start`로 처음부터 재시도) / "아니오" → `TERMINATED`(진단 종료)**. 6단계가 다 채워지면 서버가 **자동 확정**하고 `COMPLETED`와 **`diagnosisId`만** 반환한다 — 이때 **매칭 유무조차 확인하지 않는다**(그러려면 클라가 요청한 적 없는 추천 쿼리를 돌려야 한다). **추천 매물은 클라이언트가 정한 시점에 `GET /api/v2/diagnoses/{id}/recommendations`로 별도 조회**하며, **매칭 0건은 그 응답의 `resultCode: NO_MATCH`로 드러난다**(흐름 응답엔 그 코드가 없다 — 조회 전이라 모르기 때문. 조정 제안 문구·액션은 없다).

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant DIAG as diagnosis 모듈
    participant USER as user 모듈
    participant LIST as listing 모듈
    participant FS as MongoDB · diagnosisFlowSessions
    participant DB as MongoDB · diagnoses·diagnosisQuestions

    U->>C: 진단 시작
    Note over C: 시작 시점은 클라이언트가 정한다 — 먼저 /start를 건다<br/>클라는 step 번호를 모른다 — 이후 next만 호출<br/>진행 위치·다음 질문·확정 시점은 서버가 판단

    rect rgb(245,245,245)
    Note over C,DB: ── ⓪ 진단 시작(클라 주도) — 언제나 처음부터 ──
    C->>SEC: POST /api/v2/diagnoses/start (요청 본문 없음)<br/>Authorization: Bearer accessToken<br/>(게스트는 Authorization 헤더 없음)
    Note over SEC: Authorization 헤더가 있을 때만 JWT 검증 (서명·만료·클레임)<br/>/api/v2/diagnoses/** 에만 permitAll 매처를 신규 추가해 연다<br/>(전용 매처가 없으면 anyRequest().authenticated()로 떨어져 401)<br/>진단 조회 3종은 회원 전용으로 남는다

    alt access token 만료 (토큰을 보냈는데 만료)
        SEC-->>C: 401 TOKEN_EXPIRED
        C-->>U: 재로그인·토큰 재발급 유도
        Note over U,DB: ↑ 에러 응답 — 이후 단계는 수행하지 않음<br/>(게스트 강등 아님)
    else 토큰 없음 · 위조/형식 오류 → 게스트
        Note over SEC: SecurityContext 비움 → principal null<br/>(합성 userId도 게스트 JWT도 발급하지 않는다)
        SEC->>DIAG: 게스트 요청 전달 (userId = null)
    else 유효 토큰 (ROLE_USER 또는 ROLE_ONBOARDING)
        SEC->>DIAG: 인증된 요청 전달 (userId)
    end

    Note over DIAG,FS: 진행 중 세션이 있어도 무조건 덮어쓴다(원자적 upsert)<br/>(진단하다 홈으로 갔다 다시 시작해도 서버는<br/>기존 진행 정보를 보지 않는다 — 언제나 처음부터)
    alt 회원 (userId != null)
        DIAG->>FS: 새 세션 저장 upsertByUserId(DiagnosisFlowSession.start(userId))<br/>draft=빈 초안, pendingField=region, guestSessionId=null
        FS-->>DIAG: 새 세션
    else 게스트 (userId == null)
        Note over DIAG: 게스트 세션 키 발급 — 값 형식 anonymous{uuid}<br/>요청자마다 다른 값(공용·상수 키 금지 — 진단 id가 전역 순차 채번이라<br/>소유권 검사가 유일한 IDOR 방어선)
        DIAG->>FS: 새 세션 저장 (guestSessionId 키, userId=null)<br/>draft=빈 초안, pendingField=region
        FS-->>DIAG: 새 세션
        Note over DIAG,FS: userId UNIQUE 인덱스는 partial(userId 존재 시)로 좁히고<br/>guestSessionId partial UNIQUE 인덱스를 따로 둔다<br/>(한 인덱스에 섞으면 게스트 문서의 userId=null이 서로 충돌)
    end
    Note over DIAG,FS: 덮어쓰는 이전 세션은 기록하지 않고 버린다 —<br/>이탈은 돌아왔을 때에야 알 수 있어(영영 안 오면 누락,<br/>시각도 재시작 시각) 집계가 편향되기 때문

    alt 회원 (userId != null)
        DIAG->>USER: getLanguage(userId) (표시 언어)
        USER-->>DIAG: lang
    else 게스트 (userId == null)
        Note over DIAG,USER: getLanguage를 호출하지 않는다 — lang=en 고정<br/>(users 행이 없어 호출하면 첫 요청부터 404 USER_NOT_FOUND<br/>— 4xx라 로그도 남지 않는다)
    end
    DIAG->>DB: diagnosisQuestions에서 field=region 문항 조회(findByField)
    DB-->>DIAG: ① 지역 문항(카탈로그)
    alt 회원 (userId != null)
        DIAG-->>C: 200 OK, resultCode=NEXT_QUESTION<br/>question(step=1, field=region, select, options)
    else 게스트 (userId == null)
        DIAG-->>C: 200 OK, resultCode=NEXT_QUESTION<br/>data.guestSessionId=anonymous{uuid} (회원 응답에서는 생략, NON_NULL)<br/>question(step=1, field=region, select, options)
        Note over C: 클라이언트는 이 키를 보관했다가 이후 next·추천 요청에<br/>X-Guest-Session-Id 헤더로 에코한다 — 잃어버리면 처음부터 다시<br/>(서버가 매 요청 새 키를 만들면 next가 세션 미스로 죽는다)
    end
    C-->>U: ① 지역 질문·선택지 표시
    end

    loop 대화 루프 — 매 호출이 resultCode 중 정확히 하나로 응답
        U->>C: 현재 문항 답 선택
        C->>SEC: POST /api/v2/diagnoses/next<br/>body: AnswerRequest (현재 문항 답 1개 — 필수)<br/>Authorization: Bearer accessToken<br/>(게스트는 대신 X-Guest-Session-Id: anonymous{uuid} 에코)
        Note over SEC: Authorization 헤더가 있을 때만 JWT 검증 (서명·만료·클레임)<br/>인가는 permitAll — 인증 없이도 통과
        alt 토큰 없음 · 위조/형식 오류 → 게스트
            SEC->>DIAG: 게스트 요청 전달 (userId = null, guestSessionId, answer)
        else 유효 토큰
            SEC->>DIAG: 인증된 요청 전달 (userId, answer)
        end

        alt 회원 (userId != null)
            DIAG->>FS: 진행 세션 조회 findByUserId(userId)
        else 게스트 (userId == null)
            DIAG->>FS: 진행 세션 조회 (에코된 guestSessionId 키)
            Note over DIAG,FS: 신원 종류에 따라 조회 키를 고른다 — 헤더를 빠뜨렸거나<br/>남의 키를 보내면 자기 세션에 닿지 않는다
        end
        FS-->>DIAG: 세션(draft, pendingField) 또는 없음

        rect rgb(245,245,245)
        Note over DIAG: ── ① 세션 확인 + 답 디스패치(상태별) ──
        alt 진행 중 세션 없음 (앱 재시작·터미널 후 재전송·만료<br/>·게스트가 X-Guest-Session-Id를 빠뜨림/분실)
            DIAG-->>C: 400 DIAGNOSIS_SESSION_NOT_FOUND<br/>"진행 중인 진단이 없습니다. 진단을 다시 시작해 주세요."
            Note over DIAG: 서버가 임의로 흐름을 되살리지 않는다<br/>(재개·암묵적 새 시작 없음 — 시작은 클라 주도)
            C->>SEC: POST /api/v2/diagnoses/start (클라가 복구 → ⓪로)
            C-->>U: 처음부터 다시 시작(① 지역 질문)
            Note over U,DB: ↑ 에러 응답 — 이번 루프의 이후 단계는 수행하지 않음
        else 답(field) 없음
            DIAG-->>C: 400 INVALID_INPUT<br/>(next는 현재 문항 답이 반드시 있어야 한다 — 무답 호출 없음)
            Note over U,DB: ↑ 에러 응답 — 이번 루프의 이후 단계는 수행하지 않음
        else pendingField = regionRetry — ① 지역 예외질문에 답함
            Note over DIAG: 입력 검증 — field=regionRetry & code∈{YES,NO}만 허용<br/>(그 외 = 400 INVALID_INPUT)
            Note over DIAG,DB: 예/아니오 어느 쪽이든 이 시도는 끝난다 —<br/>세션을 지우기 전에 부분 답을 DISCARDED 진단으로 남긴다<br/>("이 지역을 원했는데 매물이 없었다" 수요 신호)
            DIAG->>DB: draft.discard(now) 저장(diagnoses, status=DISCARDED)<br/>(회원=userId 채움 / 게스트=guestSessionId 채움 — 정확히 하나만)
            alt code = YES (다른 지역으로 재시도)
                DIAG->>FS: 세션 삭제 (회원=userId 키 / 게스트=guestSessionId 키)
                DIAG-->>C: 200 OK, resultCode=RESTART<br/>(코드만 — 프론트가 행할 행위=재시도)
                C-->>U: 다시 시작 안내
                C->>SEC: POST /api/v2/diagnoses/start (클라가 처음부터 재시도 → ⓪로)
                Note over C: 게스트는 재시도 시 ⓪에서 새 세션 키를 발급받는다<br/>(이전 키는 더 이상 쓰지 않는다 — 그 세션 문서는 위에서 이미 삭제됐고<br/>DISCARDED 진단 문서는 이전 키로 남는다)
                Note over U,DB: ↑ 터미널 응답 — 이번 루프의 이후 단계는 수행하지 않음
            else code = NO (진단 종료)
                DIAG->>FS: 세션 삭제 (회원=userId 키 / 게스트=guestSessionId 키)
                DIAG-->>C: 200 OK, resultCode=TERMINATED<br/>(코드만 — 프론트가 행할 행위=진단종료, question 없음)
                C-->>U: 진단 종료 화면
                Note over U,DB: ↑ 터미널 응답 — 이번 루프의 이후 단계는 수행하지 않음
            end
        else pendingField = 정본 슬롯 field — 6단계 답
            Note over DIAG: 직전에 낸 문항(pendingField)과 일치 검증<br/>(UNIVERSITY_OR_DISTRICT(3)는 저장된 purpose로 university|district 계산)<br/>불일치 = 400 INVALID_INPUT (순서 강제)
            Note over DIAG: 공유 답적용(applyAnswer)으로 draft 갱신<br/>(⑥ arcStatus는 받은 값을 그대로 저장 — conditions 교차 기록 없음)
            Note over DIAG: 답 적용 (아직 저장 전 — region이면 ② 게이트 대상)
        end
        end

        rect rgb(245,245,245)
        Note over DIAG: ── ② 지역 조기 게이트 (방금 ① 지역을 답했을 때만) ──
        opt 방금 REGION 답함 (답한 field = region)
            Note over DIAG: 서버가 미리 필터링하는 유일한 지점<br/>(그 외 단계는 질문·분기만 주도)
            DIAG->>LIST: recommendByCriteria(region-only, size=1) 동기 호출<br/>(존재 확인만 — 즉시 결과 필요, 동기 공개 query, ADR-0002 D5)
            Note over LIST: listing이 자기 MongoDB만 조회<br/>(PUBLISHED + address.city(진단 Region과 등가 비교) + ACTIVE roomOffer,<br/>cross-store 조인 없음)
            LIST-->>DIAG: 매칭 존재 여부
            opt region 매칭 0건 (있으면 그대로 ③으로)
                DIAG->>FS: pendingField=regionRetry 저장(정본 슬롯 전진 없음)
                alt 회원 (userId != null)
                    DIAG->>USER: getLanguage(userId) (표시 언어)
                    USER-->>DIAG: lang
                else 게스트 (userId == null)
                    Note over DIAG,USER: getLanguage 호출 없음 — lang=en 고정
                end
                DIAG->>DB: diagnosisQuestions에서 field=regionRetry 문항 조회(findByField)
                DB-->>DIAG: 예외질문(카탈로그의 일반 문항, SINGLE·max 1, options YES/NO)
                DIAG-->>C: 200 OK, resultCode=NEXT_QUESTION<br/>question(step=1, field=regionRetry) — 별도 결과코드 아님
                C-->>U: "현재 지역에는 매물이 없어요. 다른 지역 방을 찾아보시겠어요?"<br/>예 / 아니오
                Note over U,DB: ↑ 응답 — 이번 루프의 이후 단계는 수행하지 않음<br/>(예/아니오는 다음 next 호출에서 위 ① 디스패치가 처리)
            end
        end
        end

        rect rgb(245,245,245)
        Note over DIAG: ── ③ 다음 질문 vs 자동 확정 (위에서 응답하지 않은 경우) ──
        DIAG->>FS: 세션 저장 (pendingField=낸 문항의 field)
        alt 다음 슬롯 있음 — ofField(답한 field).next()
            alt 회원 (userId != null)
                DIAG->>USER: getLanguage(userId) (표시 언어)
                USER-->>DIAG: lang
            else 게스트 (userId == null)
                Note over DIAG,USER: getLanguage 호출 없음 — lang=en 고정<br/>게스트는 대화 전 구간에서 user 모듈을 한 번도 부르지 않는다
            end
            DIAG->>DB: diagnosisQuestions에서 다음 슬롯의 field 문항 조회(findByField)
            Note over DIAG: 카탈로그는 field로 문항을 식별한다(순서는 코드)<br/>UNIVERSITY_OR_DISTRICT(step 3)는 저장된 purpose로 university|district 택일<br/>lang으로 표시 라벨 조립 (없으면 en 폴백, US-2-6 동일)
            DIAG-->>C: 200 OK, resultCode=NEXT_QUESTION<br/>question(step, field, select, options)
            C-->>U: 다음 질문/선택지 표시 → 다음 루프로
        else 다음 슬롯 없음(⑥ arcStatus 답함) — 빌더 완성 → 자동 확정
            Note over DIAG: draft.complete(now) — 저장된 답 재검증 후<br/>IN_PROGRESS → COMPLETED (확정 시점은 서버가 판단)<br/>재검증 실패 = 400 INVALID_INPUT
            DIAG->>DB: 확정 진단 저장(diagnoses 컬렉션 — 이력·최근·상세 조회가 읽는 대상)<br/>(회원=userId 채움·guestSessionId null / 게스트=그 반대)
            DB-->>DIAG: diagnosisId
            DIAG->>FS: 세션 삭제 (회원=userId 키 / 게스트=guestSessionId 키, 흐름 종료)
            Note over DIAG,LIST: 여기서 listing을 부르지 않는다 — 매칭 0건인지는<br/>추천을 조회해야 알 수 있고, 그 조회는 클라가 요청한 적 없다<br/>(no-match를 미리 주려면 요청 없는 쿼리를 돌려야 함)
            DIAG-->>C: 200 OK, resultCode=COMPLETED<br/>diagnosisId (매칭 유무와 무관·추천 인라인 없음)
            C-->>U: 진단 완료 안내
        end
        end
    end

    rect rgb(245,245,245)
    Note over C,DB: ── ④ 매물 조회는 클라이언트가 결정한다(여기서 처음 추천 쿼리가 돈다) ──
    opt COMPLETED를 받은 뒤 — 조회 시점은 클라가 정함
        U->>C: 진단 결과 화면 진입
        C->>SEC: GET /api/v2/diagnoses/{diagnosisId}/recommendations<br/>?page=0&size=20<br/>Authorization: Bearer accessToken<br/>(게스트는 대신 X-Guest-Session-Id: anonymous{uuid} 에코)
        Note over C,DIAG: 이 호출 자체가 "매물을 받겠다"는 클라의 결정<br/>조정 제안 문구·액션은 없다
        alt 토큰 없음 · 위조/형식 오류 → 게스트
            SEC->>DIAG: 게스트 요청 전달 (userId = null, guestSessionId, diagnosisId)
        else 유효 토큰
            SEC->>DIAG: 인증된 요청 전달 (userId, diagnosisId)
        end
        DIAG->>DB: 확정 진단 조회(소유권 검증 — 타인 403·미존재 404)
        DB-->>DIAG: 저장된 진단 조건
        Note over DIAG: requireOwner — 신원 종류가 같고 값이 같을 때만 통과<br/>회원=userId 일치 / 게스트=guestSessionId 일치,<br/>한쪽이 null이면 무조건 거절(게스트↔회원 교차 조회 불가)<br/>진단 id가 전역 순차 채번이라 이 검사가 유일한 IDOR 방어선
        alt 회원 (userId != null)
            DIAG->>USER: getLanguage(userId) (표시 언어 — 매물 라벨 번역용, ADR-0037)
            USER-->>DIAG: lang
        else 게스트 (userId == null)
            Note over DIAG,USER: getLanguage 호출 없음 — lang=en 고정<br/>이 호출은 공유 DiagnosisRecommendationReader가 매 추천 요청마다 타므로<br/>분기는 그 공유 코드에 들어가지만, 조회 3종은 회원 전용이라<br/>실제로 게스트 분기를 타는 것은 v2 요청뿐이다
        end
        DIAG->>LIST: recommendByCriteria(전체 진단 조건, page/size, lang) 동기 호출
        Note over DIAG,LIST: 추천 경로는 신원을 받지 않는다 — RecommendationCriteria에<br/>userId가 없고 favorite·recentListing 쓰기도 0건<br/>→ listing 모듈 코드 변경 0건
        LIST-->>DIAG: 매칭 매물 요약 + 지도 좌표
        alt 매칭 있음
            DIAG-->>C: 200 OK, content[]·markers[]·page
            C-->>U: 매물 목록 + 지도 마커 표시
        else 매칭 0건
            DIAG-->>C: 200 OK, content: []·markers: [] (제안 suggestions 없음)
            Note over C,DIAG: 빈 목록이 곧 no-match — 에러도 별도 결과코드도 아니다
            C-->>U: 결과 없음 안내
        end
    end
    end
```

## 흐름 요약

- v2 진단은 앱이 `step`을 지정하지 않는 **서버 주도 대화형 흐름**이되, **서버가 주도하는 것은 질문과 분기뿐**이다 — **진단 시작**과 **확정 매물 조회 시점**은 클라이언트가 결정한다([ADR-0036](../../../adr/0036-diagnosis-v2-server-driven-flow.md)). 클라이언트는 **`POST /api/v2/diagnoses/start`**(요청 본문 없음)로 시작해 **`POST /api/v2/diagnoses/next`** 를 반복 호출하고, `next` 본문에는 **현재 문항의 답 1개**(`AnswerRequest` — v1 `POST /answers`와 동일 형식)만 담는다. 서버는 진행 위치·다음 질문·확정 시점을 판단한다. 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 diagnosis 모듈로 전달한다. 확정된 진단을 읽는 조회 3종(이력·최근·상세)은 [us-2-3](us-2-3-diagnosis-history.md)이 다룬다.
- **`POST /start`는 언제나 처음부터 시작한다** — 진행 중 세션이 있어도 `upsertByUserId`로 **무조건 덮어써** 새 세션(`draft`=빈 초안, `pendingField=region`)을 만든 뒤 ① 지역 질문을 반환한다(시작 버튼 더블탭이 `userId` UNIQUE와 경합하지 않도록 삭제 후 삽입이 아니라 원자적 upsert다). 덮어쓰는 이전 세션은 **기록하지 않고 버린다**. 진단하다 홈으로 갔다 다시 시작해도 서버가 기존 진단 정보를 보고 이어가지 않는다.
- **세션 없이 온 `/next`는 `400 DIAGNOSIS_SESSION_NOT_FOUND`**("진행 중인 진단이 없습니다. 진단을 다시 시작해 주세요.")다 — 앱 재시작·터미널 응답 후 재전송·세션 만료 시. 서버가 임의로 흐름을 되살리지 않고 **클라이언트가 `POST /start`로 복구**한다(`DiagnosisFlowSessionNotFoundException` → 전역 핸들러). `/next`는 답(`field`)이 반드시 있어야 하며 없으면 `400 INVALID_INPUT`이다.
- 진행 상태는 **v2 전용 세션**에 담는다 — `diagnosisFlowSessions` 컬렉션의 세션 `{ userId, draft(누적 답), pendingField(직전에 낸 문항의 field) }`. `pendingField` 같은 절차 필드는 정본 `Diagnosis`에 없으므로 **별도 컬렉션**으로 분리한다. 완료 시에만 `draft.complete()`로 정본 진단을 만들어 **`diagnoses` 컬렉션에 저장**(이력·최근·상세 조회가 읽는 대상)하고 세션은 삭제한다. 세션은 `/start`에서만 생기고 터미널(확정·재시도·종료)에서 삭제되므로 진행 중 세션을 되돌리는 전이는 두지 않는다.
- **① 지역 0건으로 끝난 시도는 버리지 않는다** — 세션을 지우기 전에 부분 답을 `draft.discard(now)`로 `diagnoses`에 `status=DISCARDED`로 남긴다(**재시도·종료 양쪽**). 목적은 **수요 분석**이다 — "어느 지역을 원했는데 매물이 없었나"(`DISCARDED` + `region` + `purpose=null`). 재시도까지 남기는 건, 안 남기면 사용자가 다른 지역으로 완주했을 때 원래 원했던 지역이 증발하기 때문이다. **그 외 이탈(답하다 앱 닫음)은 남기지 않는다** — 돌아왔을 때에야 알 수 있어 영영 안 온 사용자가 누락되고 시각도 재시작 시각이라 집계가 편향된다. **사용자 노출 경로를 두지 않는다** — 이력·최근·상세가 모두 `COMPLETED`만 보므로 목록에서 자동으로 빠지고, **id로 직접 오는 추천은 명시적으로 `404`** 다(폐기 기록은 본인 것이고 id가 순차 발급이라 소유권만으론 못 막는다). 확정과 달리 완결성 검증을 하지 않는다(부분 답이 정상).
- **정본 순서는 `pendingField`가 강제한다** — `REGION → PURPOSE → UNIVERSITY_OR_DISTRICT(저장된 purpose로 university|district) → CONDITIONS → MONTHLY_RENT → ARC_STATUS`(`DiagnosisFlowStep`의 **선언 순서**가 정본). 완료 판정을 `validateComplete`(제출 재검증)만으로 하면 `conditions`(④)가 필수가 아니어서 ④를 건너뛸 수 있다. 그래서 **선형 진행·완료의 단일 정본은 `pendingField`**(직전에 낸 문항)이며 다음 문항은 `ofField(답한 field).next()`로 정한다 — 진행 슬롯 수를 따로 세지 않는 이유는 6슬롯에 없는 `regionRetry`가 끼어들면 "몇 개 답했나"와 "무엇을 물었나"가 어긋나기 때문이다. `validateComplete`는 자동 확정 시 정합 백스톱으로만 쓴다. 조건부 분기는 ③(UNIVERSITY_OR_DISTRICT) 하나뿐이고 저장된 `purpose`로 `university`/`district`를 서버가 택일한다(카탈로그 분기 메타 없음 — US-2-5·[ADR-0028](../../../adr/0028-diagnosis-questions-catalog-store.md)). 슬롯의 `step` 번호는 응답 `data.question.step`으로 나가는 표시 값이다.
- 매 `start`·`next` 호출은 **정확히 하나의 `resultCode`**로 응답한다(정상 `200 OK`, 공통 래퍼 `{ success, data, error }`의 `data`에 태그드 유니온 `DiagnosisFlowResponse { resultCode, question?, diagnosisId? }` — 채워지지 않는 필드는 `NON_NULL`로 생략):
  - **`NEXT_QUESTION`** — 다음 슬롯이 남음. 그 문항 1개를 `question`으로 반환(③ UNIVERSITY_OR_DISTRICT는 저장된 `purpose`로 결정, 표시 라벨은 사용자 언어로 번역·`en` 폴백). **① 지역 0건 예외질문(`field=regionRetry`)도 이 코드로 내려간다.**
  - **`RESTART`** — 지역 예외질문에 `code=YES`. 세션을 삭제하고 **코드만** 반환한다 — 클라이언트가 `POST /start`로 처음부터 재시도한다.
  - **`TERMINATED`** — 지역 예외질문에 `code=NO`. 세션을 삭제하고 **코드만** 반환한다(챗봇 종료, `question` 없음).
  - **`COMPLETED`** — 마지막 슬롯(⑥ arcStatus)을 답해 자동 확정. **`diagnosisId`만** 반환한다(추천 매물 인라인 없음).
  - **매칭 0건(no-match)에 해당하는 결과코드는 없다** — 0건인지는 추천을 실제로 조회해야 알 수 있고(④), 그 조회 시점은 클라가 정하기 때문이다. `GET /api/v2/diagnoses/{id}/recommendations` 응답의 `resultCode: NO_MATCH`가 그걸 알려주며 **조정 제안 문구·액션은 없다**(응답에 그 필드가 아예 없다).
- **① 세션 확인 + 답 디스패치**: 세션을 찾고(없으면 `400 DIAGNOSIS_SESSION_NOT_FOUND`) 답 유무를 확인한 뒤(없으면 `400 INVALID_INPUT`), **`field`가 `pendingField`(서버가 직전에 낸 문항)와 일치하는지** 검증한다(불일치 `400 INVALID_INPUT`). 이 한 줄이 정본 슬롯 문항과 예외질문을 **같은 규칙**으로 막는다 — 기대 답을 정본 순서로 역산하면 예외질문 시점에 `regionRetry`가 거부되고 `purpose`가 통과해 버린다. 이후 `pendingField`로 갈린다: `regionRetry`이면 `code∈{YES,NO}`만 허용(그 외 `400 INVALID_INPUT`)하고 **둘 다 세션을 삭제**해 각각 `RESTART`·`TERMINATED`를 반환한다(흐름 제어 응답이라 `draft`에 저장하지 않고 정본 슬롯도 전진하지 않는다). 정본 슬롯 field이면 공유 `applyAnswer`로 `draft`를 갱신하고 다음 슬롯으로 진행한다(⑥ `arcStatus`는 받은 값을 그대로 저장할 뿐 `conditions`를 교차 기록하지 않는다).
- **② 지역 조기 게이트**는 방금 ① 지역을 답했을 때만 돈다(답한 field가 `region`) — **서버가 미리 필터링하는 유일한 지점**이다. `region`만 채운 경량 조건(`RecommendationCriteria`, `size=1`)으로 `listing`의 공개 query(`recommendByCriteria`)를 **동기 호출**(즉시 결과 필요 → 이벤트 아님, [ADR-0002](../../../adr/0002-inter-module-communication-via-events.md) Decision 5)해 **매칭 존재 여부만** 확인한다. `listing`은 자기 MongoDB만 조회한다(PUBLISHED + `address.city` + ACTIVE `roomOffer`, cross-store 조인 없음 — [ADR-0005](../../../adr/0005-polyglot-persistence.md)). 진단과 매물은 값 집합을 일치시키지 않고 **매핑**으로 잇는다([ADR-0039](../../../adr/0039-listing-schema-v4-registration-form.md)) — 진단 `Region` 3종과 매물 `address.city`(`City`)는 등가 비교이고, ③ 이후의 `district` 답도 5구는 등가·`ETC`는 그 5구의 여집합(`$nin`)으로 매칭한다. 0건이면 예외질문을 `NEXT_QUESTION`으로 내려보내며 `pendingField=regionRetry`로 기록하고(정본 슬롯은 전진하지 않는다), 있으면 그대로 ③으로 진행한다. region-only는 이후 예산·조건·대학 필터 결과의 상위집합이라(0건이면 어떤 후속 조건으로도 0건) 지역 단계 조기 차단이 논리적으로 타당하다.
- **① 지역 예외질문은 서버가 합성하지 않고 카탈로그 문항으로 관리한다** — `diagnosisQuestions`의 `field: "regionRetry"`, `select: { type: "SINGLE", max: 1 }`, `options: [{ code: "YES" }, { code: "NO" }]` 문항이다(예외질문도 채팅 질문의 일부라 문항·번역 정본을 다른 6단계와 같은 곳에 둔다). 따라서 **별도 결과코드가 아니라 일반 `NEXT_QUESTION`** 으로 내려가고, 그 예/아니오 응답에만 **프론트가 행할 행위**를 코드로 알린다(예=`RESTART` 재시도 / 아니오=`TERMINATED` 진단종료). `regionRetry`는 진단 답 필드가 아니라 흐름 제어 응답이므로 `Diagnosis`에 저장되지 않는다. 적재는 `DiagnosisCatalogSeedChangeUnit`(order 0000) 시드와 기배포 환경용 `DiagnosisRegionRetryQuestionChangeUnit`(order 0005, 멱등)이 담당한다. 카탈로그는 **순서를 담지 않고 `field`로 문항을 식별**하므로(ADR-0036 결정 6) 조회는 `findByField(field)` 단건이고, 이 문항이 정본 6슬롯 밖(흐름 제어)이라는 사실은 코드에만 있다(`DiagnosisFlowStep`에 없음).
- **③ 자동 확정**은 마지막 슬롯(⑥ `arcStatus`, 다음 슬롯 없음)을 답했을 때 서버가 `draft.complete(now)`로 정본 진단을 확정(`IN_PROGRESS → COMPLETED`, 재검증 실패 시 `400 INVALID_INPUT`)해 `diagnoses`에 저장하고 세션을 삭제한 뒤 **`COMPLETED` + `diagnosisId`만** 반환한다 — **여기서 listing을 호출하지 않는다.** 확정 **시점**은 클라이언트가 요청하지 않고 서버가 빌더 완성 시점에 판단하지만, **매물을 받을 시점은 클라가 정한다**. 그래서 6단계 흐름 전 구간에서 listing 호출은 ② 지역 게이트 1회뿐이다(`autoConfirmDoesNotQueryRecommendations`가 `verify(times(1))`로 고정).
- **④ 추천 조회**는 클라이언트가 `GET /api/v2/diagnoses/{id}/recommendations`를 부를 때 비로소 일어난다 — 이 호출이 곧 "매물을 받겠다"는 결정이고 페이지·정렬도 클라가 고른다. 필터·매핑·페이지 계약은 [us-2-2](us-2-2-recommendations.md)가 정본이며 조정 제안은 주지 않는다. 검증·소유권·조건 매핑·listing 호출은 공유 컴포넌트(`DiagnosisRecommendationReader`)에 모여 있다. 본인 소유만 조회 가능하다(타인 `403`·미존재 `404 DIAGNOSIS_NOT_FOUND`).
- **④ 매물 조회는 클라이언트가 결정한다** — v2 확정 응답에는 추천 매물이 인라인으로 실리지 않는다(전용 뷰 없음). 클라이언트는 받은 `diagnosisId`로 원하는 시점에 **v2 `GET /api/v2/diagnoses/{diagnosisId}/recommendations?page=0&size=20`** 를 호출해 목록·마커·페이지를 받는다(필터·매핑·페이지 계약은 [us-2-2-recommendations](us-2-2-recommendations.md)). **게스트에게는 이 엔드포인트가 유일한 추천 경로다**.
- **재시작·종료 의미**: 지역 예외질문의 "예"(`RESTART`)와 "아니오"(`TERMINATED`)는 **둘 다 세션을 삭제하는 터미널**이다. 재시작은 서버가 진행 세션을 되돌리는 것이 아니라 **클라이언트가 `POST /start`를 다시 걸어** 새 세션으로 처음부터 시작한다(고아 세션 없음). 같은 0건 지역을 다시 고르면 게이트가 매 REGION 응답마다 재확인해 예외질문이 다시 뜬다(서버 무한 루프 아님 — 사용자 주도).
- **번역(US-2-6 일관)**: `NEXT_QUESTION`의 표시 문자열(`question`·`label`)만 사용자 표시 언어로 조립하고(지역 예외질문 포함), `code`는 언어 무관 UPPER_SNAKE 불변이다. 표시 언어는 `user` 공개 query(`getLanguage`)를 동기 호출해 취득하며 미지원 언어는 영어로 폴백한다(US-2-5·US-2-6과 동일 i18n 경로).
- **에러 경계**: `resultCode`는 정상 흐름 신호이며 에러가 아니다 — `RESTART`·`TERMINATED`도 `200 OK`이고, 추천 0건도 `200 OK` + 빈 목록이다. 진행 중 세션 없이 온 `/next`는 진단 도메인 전용 `400 DIAGNOSIS_SESSION_NOT_FOUND`, 입력 위반(답 누락·잘못된 단계 답·미정의 enum·`regionRetry` 규칙 위반·자동 확정 재검증 실패)은 `400 INVALID_INPUT` + `errors[]`, JSON 파싱 불가·타입 불일치는 `400 MALFORMED_REQUEST`로 공통 처리한다. **만료된 access token은 `401 TOKEN_EXPIRED`** 로 막는다(permitAll 경로에서도 게스트로 강등하지 않는다). 반면 **토큰 미전송·위조/형식 오류는 401이 아니라 게스트로 처리**되므로 이 흐름에서 `401 UNAUTHENTICATED`는 발생하지 않는다.

### 게스트(비회원) 흐름 (#181)

- **v2 챗봇 진단은 비회원에게 열리고, 여기가 게스트 진단의 정본 경로다** — **`permitAll` 매처를 신규 추가하는 대상은 `/api/v2/diagnoses/**` 하나뿐**이며 **진단 조회 3종은 회원 전용으로 유지**한다. 퀴즈·생활팁과 달리 진단에는 전용 매처가 **아예 없어** `.anyRequest().authenticated()`로 떨어지므로 기존 줄을 고치는 것이 아니라 줄을 새로 넣어야 하고, 빠뜨리면 게스트 진단이 계속 `401`이다. 그 결과 **게스트 흐름은 `POST /api/v2/diagnoses/start` → `POST /api/v2/diagnoses/next` → `GET /api/v2/diagnoses/{diagnosisId}/recommendations`(지도는 `/recommendations/map`)로 닫히며**, 비로그인 상태로 조회 3종을 호출하면 그대로 `401`이다. 게스트용 이력·최근 시맨틱은 새로 정의하지 않는다 — 게스트 세션 키는 `/start`마다 새로 발급되어 한 키에 달리는 종료 진단이 최대 1건이라 이력이라는 개념이 성립하지 않는다.
- **게스트 신원은 `userId == null`(부재)이다** — 합성 userId(`anonymous`+uuid)를 신원으로 흘리는 안은 채택하지 않았다. 기존 `diagnoses`·`diagnosisFlowSessions` 문서의 `userId`가 BSON Int64라 타입을 String으로 넓히면 **기존 회원의 진단 이력이 조용히 매치되지 않기** 때문이다. 저장소의 `userId` 타입은 `Long` 그대로이므로 **데이터 마이그레이션이 없다.**
- **대화 연속성은 클라이언트가 에코하는 게스트 세션 키로 잇는다** — `POST /api/v2/diagnoses/start`에 인증이 없으면 서버가 키를 발급해 응답에 실어 보내고, 클라이언트는 이후 `/next`·추천 조회에 **`X-Guest-Session-Id` 헤더**로 되돌려보낸다. 값 형식은 **`anonymous<uuid>`** 이며 요청자마다 다르다. 필터가 매 요청 새 키를 만드는 설계는 `/start`는 `200`을 주지만 `/next`가 세션 미스로 `400 DIAGNOSIS_SESSION_NOT_FOUND`가 되어 **단발 요청 테스트로는 잡히지 않는 파손**이 되고, 게스트에게 JWT를 발급하는 설계는 `JwtTokenService`가 subject를 `Long.parseLong` 하므로 불가하다. **클라이언트에 새 의무가 생긴다** — 키를 잃어버리면 진단을 처음부터 다시 해야 한다.
- **게스트 진단 문서는 `userId=null` + `guestSessionId`로 저장한다** — `Diagnosis`·`DiagnosisFlowSession`에 `guestSessionId`(nullable String)를 추가하며 회원/게스트 문서는 두 필드 중 **정확히 하나만** 채운다(validator 없이 코드 규약으로만 유지되는 불변식). `diagnosisFlowSessions`의 기존 `userId` UNIQUE 인덱스는 **partial(`userId` 존재 시)로 좁히고** `guestSessionId` partial UNIQUE를 **따로** 만든다 — 한 인덱스에 두 신원을 섞으면 게스트 문서들의 `userId=null`이 서로 충돌한다.
- **소유권 검사가 유일한 IDOR 방어선이다** — 진단 id가 전역 순차 채번(`SequenceGenerator`)이라 열거가 쉽다. `requireOwner`는 **신원 종류가 같고 값이 같을 때만** 통과하며 한쪽이 null이면 무조건 거절한다(게스트가 회원 진단을, 회원이 게스트 진단을 읽지 못한다). `DiagnosisService`와 `DiagnosisRecommendationReader` 두 사본을 함께 고친다.
- **게스트 경로에서는 `user` 모듈을 한 번도 호출하지 않는다** — 표시 언어는 `getLanguage`를 부르지 않고 `en`으로 고정한다. `getLanguage`는 `users` 행이 없으면 `404 USER_NOT_FOUND`를 던지고 그중 `DiagnosisFlowService.start()`가 이를 타므로, 호출을 남겨두면 **게스트의 첫 요청부터** 실패한다(4xx라 로그도 남지 않는다). 이 흐름의 호출 지점은 `DiagnosisFlowService`(문항 번역 — ⓪ 시작·② 예외질문·③ 다음 질문)와 ④ 추천이 타는 공유 `DiagnosisRecommendationReader`(매물 라벨 번역용 표시 언어를 `recommendByCriteria(criteria, language)`로 넘김 — [ADR-0037](../../../adr/0037-listing-localization-and-code-catalog.md)) **둘**이며, 후자는 매칭 유무와 무관하게 매 추천 요청이 탄다. 온보딩 미완료(`ROLE_ONBOARDING`) 토큰은 `userId != null`이라 게스트가 아니며 `users.lang`을 따른다.
- **게스트를 받기 위한 `listing` 모듈 코드 변경은 0건이다** — ② 지역 게이트와 ④ 추천 조회가 부르는 `recommendByCriteria`는 애초에 userId 인자가 없고 `RecommendationCriteria`에도 신원 컴포넌트가 없으며, 추천 요청당 `favorite`·`recentListing` 쓰기도 0건이라 게스트 트래픽이 정크를 남기지 않는다.
- **게스트→회원 결과 이관은 #181 범위 밖이다** — 게스트가 나중에 로그인해도 진단 결과를 옮기지 않는다. `guestSessionId`를 문서에 남겨 후속 이슈에서 배치 claim으로 처리할 수 있게만 열어둔다.
- **결정 필요(미확정)** 셋:
  - 게스트 진단 데이터의 **TTL 수치** — 회원 세션은 `userId` 키 upsert로 덮어써지지만 게스트 세션은 키가 매번 달라 **절대 덮어써지지 않고** 무한 누적되며 전역 순차 채번도 함께 소모된다. **현재 저장소에는 TTL 인덱스가 하나도 없어**(회원 진단도 영구 보존) 도입 여부부터가 결정 사항이며, 도입한다면 초기화 코드를 신규 작성해야 한다(검토 중인 제안값은 `diagnosisFlowSessions` 게스트 문서 24시간·`diagnoses` 게스트 문서 30일이나 **제품 결정 전**).
  - `userId_unique_idx`를 partial로 좁힐 때의 **drop 주체** — 배포 환경에 이미 비-partial 인덱스가 있어 같은 이름·다른 옵션의 재생성이 `IndexOptionsConflict`로 거절되므로 drop이 선행돼야 하는데, 그것을 부트스트랩 initializer가 할지 Mongock changeUnit으로 둘지가 미정이다.
  - 비인증 요청 **rate limiting** — 게스트가 `POST /api/v2/diagnoses/start`를 무제한 호출할 수 있고 저장소에 관련 코드가 없다.
