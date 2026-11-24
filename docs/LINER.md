# 라이너 AI 사주 해설

무료 학과 결과의 **AI 사주 해설 보기** 버튼에서 추가 해설을 생성한다. 기존 사주 계산·학과 순위·유료 구매 결과는 변경하지 않는다. 게스트도 이용할 수 있다.

## 배포

1. https://platform.liner.com/ko 로그인 → 관리의 **API 키** → 연학당용 새 키 생성. 기존에 발급한 키가 다시 보이지 않으면 새 키를 발급한다. API 크레딧도 확인한다.
2. Olim 백엔드 환경변수 `LINER_API_KEY`에 등록한다. **실행 / 서버 전용 / 비밀값** 설정을 사용한다. 프론트 환경변수나 소스·Git·공개 채팅에는 넣지 않는다.
3. 이 변경이 포함된 백엔드를 배포한다. Flyway V6가 해설 캐시와 사용량 테이블을 생성한다. 프론트도 최신 main으로 배포한다.
4. 학과 분석 완료 → **AI 사주 해설 보기** → 해설 3문단 표시를 확인한다. 같은 입력을 다른 창에서 분석하면 저장된 해설을 추가 생성 없이 보여준다.

키 이외의 환경변수는 필수가 아니다.

| 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `LINER_API_KEY` | 빈 값 | 비어 있으면 외부 호출 없이 기존 해설 제공 |
| `LINER_MODEL` | `liner-gpt-router` | Liner Chat Completions 모델 |
| `LINER_DAILY_LIMIT` | `200` | 전체 서비스의 하루 신규 생성 상한(UTC), 실패한 시도도 포함 |

사용자 계정 또는 게스트 세션당 매 UTC 시간 구간 5건까지 새 해설을 생성한다. 캐시 조회는 차감하지 않는다. 동일 입력의 동시 요청은 하나만 생성한다. 실패 후 2분은 재호출하지 않는다. 글로벌 한도는 신규 게스트 세션으로 계정별 한도를 우회하더라도 적용된다.

## API와 저장

`POST /api/ai/saju-explanation?generate=false`는 캐시 조회만 한다. `generate=true`일 때만 새 해설을 요청한다. 두 요청 모두 CSRF 토큰이 필요하다. 입력은 계산 엔진 버전, 사주 기둥 3~4개, 일간, 오행 개수, TOP 3 학과 식별자·명칭·점수로 제한된다. 이름·생년월일·출생시간 원문·성별·계정 ID·결제 정보는 Liner에 보내지 않는다.

응답: `{ "status": "READY", "paragraphs": ["...", "...", "..."] }`. 나머지 상태는 `NOT_GENERATED`, `DISABLED`, `PENDING`, `UNAVAILABLE`, `LIMITED`이며 문단은 빈 배열이다. 타임아웃·잘못된 키·잔액 부족·비정상 응답 시 기존 계산 해설을 유지한다. 오류 응답 원문이나 키를 사용자에게 노출하지 않는다.

서버는 입력의 정규화 해시 + 프롬프트 버전 + 모델로 해설을 캐시한다. 같은 계산 결과를 갖는 이용자는 같은 해설을 재사용한다. 캐시에는 입력 원문이나 계정 연결을 저장하지 않는다. 생성 제한에는 계정/세션 식별자의 해시만 사용하며 오래된 제한 행은 정리한다. 해설을 다시 쓰려면 프롬프트 버전을 올리거나 모델을 변경한다. 생성 내용은 사주 계산의 정확성을 검증하지 않으며, 적성이나 합격 가능성을 보장하지 않는다.

Liner 공식 계약: https://liner.com/developers/docs/liner-model-api-chat-completions
호출: `POST https://platform.liner.com/api/v1/chat/completions`, `Authorization: Bearer ...`, 비스트리밍 JSON 응답. 테스트는 로컬 HTTP 모의 서버를 사용한다. 실제 키·크레딧이 없는 상태에서는 운영 Liner 호출과 생성 문구의 품질은 확인할 수 없다.

## 운영 오류 확인

`UNAVAILABLE` 응답의 `failureCode`와 Olim 로그의 `Liner explanation unavailable` 행을 확인한다. 같은 실패 결과의 조회에도 고정된 오류 코드를 반환한다. API 키, 요청 프롬프트, 원문 응답은 로그에 남기지 않는다.

- `AI_AUTH_FAILED`: Liner가 HTTP 401 반환. API 키가 맞는지와 재배포 여부 확인.
- `AI_CREDITS_REQUIRED`: HTTP 402. Liner API 잔액·결제 상태 확인.
- `AI_ACCESS_DENIED`: HTTP 403. 해당 키·계정의 API 접근 권한 확인.
- `AI_REQUEST_REJECTED`: HTTP 400/404/422. 모델 및 요청 파라미터 확인. 로그의 `providerCode`는 제공자의 오류 코드이며 메시지 원문은 기록하지 않는다.
- `AI_PROVIDER_RATE_LIMITED`: HTTP 429. 제공자 측 요청 제한.
- `AI_TIMEOUT` / `AI_CONNECTION_FAILED`: 타임아웃 또는 서버 네트워크 연결 실패.
- `AI_INVALID_RESPONSE` / `AI_INCOMPLETE_RESPONSE`: JSON·문단 검증 실패 또는 응답 미완료.

프론트의 작은 안내에는 제공자나 서버 저장 방식 대신 사용자에게 필요한 설명만 표시한다. 실제 원인 수정 여부는 운영 API에서 `READY`와 해설 문단 3개가 반환되는지 확인해야 한다.

2026-11-24 실제 키로 비교한 결과, 사용자 메시지에 분석 JSON만 넣으면 `400 invalid_request`가 반환됐고 같은 데이터 앞에 자연어 해설 요청을 붙이면 정상 생성됐다. 사용자 메시지는 **해설 요청 문장 + 분석 JSON**으로 구성해야 한다. 실제 Java 클라이언트에서도 정상 생성 및 문단 검증을 확인했다. 요청에는 출력 상한과 추론 옵션을 유지하고, 거절·인증·잔액·타임아웃 오류를 자동 재시도하지 않는다.

`providerReason`은 실제 키, 따옴표 안의 값, 긴 토큰과 비ASCII 문자를 가린 최대 240자의 진단 설명으로, 운영 로그에만 표시한다. 원문 응답을 프론트에 전달하지 않는다.
