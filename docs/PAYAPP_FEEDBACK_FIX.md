# 페이앱 결제 확인 중 문제: 요청 필터 수정

2026-11-17 확인. 첨부된 진단의 본문 유실 현상을 이 저장소의 `BodyLimitFilter`와 실제 Tomcat/Spring MVC로 재현했다. 운영 서버 로그와 페이앱 주문 처리 코드는 확인하지 못했으므로 운영 장애의 유일한 원인으로 확정한 것은 아니다.

## 재현과 변경

`application/x-www-form-urlencoded` POST에 `pay_state=4`, `price`, `mul_no`, `var1`을 보내면 수정 전에는 필수 `@RequestBody String`을 받는 컨트롤러가 HTTP 400을 반환했다.

필터가 `getInputStream()`으로 본문을 읽으면 Tomcat은 이후 폼 파라미터를 파싱하지 않는다. Spring은 쿼리 문자열이 없는 폼 POST의 본문을 `getParameterMap()`으로 다시 구성하므로, 입력 스트림만 복원하는 기존 래퍼에서는 본문이 비게 된다. [Spring 7.0.10 구현](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-web/src/main/java/org/springframework/http/server/ServletServerHttpRequest.java#L214).

수정은 크기를 검사한 바이트에서 폼 파라미터를 복원하고, 래퍼의 `getParameterMap`, `getParameter`, `getParameterValues`, `getParameterNames`에 함께 제공한다. 쿼리 값은 폼 값보다 먼저 유지한다. 원본 입력 스트림, 한글·URL 인코딩, 반복 값·빈 값, 65,536바이트 제한도 유지한다.

실제 HTTP 회귀 테스트 11개를 추가했다. 수정 후에는 같은 폼 요청이 HTTP 200으로 처리된다. Content-Length가 없는 chunked 폼, 크기 경계와 초과, JSON 원문 보존도 검증했다. `test bootJar --offline` 결과는 전체 59개 테스트 통과 및 빌드 성공이다.

## 프론트엔드 확인

`14-DEMODAY-FRONTEND-yeonhakdang`의 `0987a7a` 기준 코드 점검 결과:

- `lib/backend.ts`: `/api/orders/{id}/complete` 및 `/api/me/library`의 서버 상태로 결제 완료를 판단한다.
- `lib/paymentReturn.ts`, `components/proto/Prototype.tsx`: 복귀 후 1.5초 간격으로 약 20초간 상태를 조회하고, 계속 확인 중이면 구매 내역으로 이동한다. 단품은 결제 확인 후 해당 결과 화면, 패키지는 결제 완료 화면으로 이동한다.
- `components/screens/10-policy/PurchaseHistory.tsx`: `PENDING`을 '결제 확인 중'으로 표시하고 4초 간격으로 최대 60초간 갱신한다. 서버가 `PAID`를 반환하면 '결제 완료'와 결과 보기 버튼을 표시한다.
- `next.config.ts`: `/api/webhooks/payapp`과 `/api/payments/payapp/return`을 포함한 API 경로를 서버 환경변수 `BACKEND_ORIGIN`으로 전달한다.

첨부된 구매 내역 화면은 서버가 계속 `PENDING`을 반환할 때의 프론트 동작과 일치한다. 이 동작을 바꾸는 프론트 수정은 하지 않았다. 실제 배포 환경의 `BACKEND_ORIGIN` 값은 저장소에 없으며 확인하지 못했다.

## 배포와 기존 결제 복구

확인 시점에 백엔드 원격 `main`은 `8b269a9`이고 포트원 구현만 있다. 프론트 문서가 참조하는 `PayAppController`, `docs/PAYAPP.md`, 페이앱 마이그레이션은 이 버전에 없다. 따라서 현재 생성한 JAR을 페이앱 운영 서버의 대체 배포물로 사용할 수는 없다. **페이앱 코드가 있는 실제 배포 소스에 필터 수정을 적용해야 한다.**

1. 실제 배포 소스에 `BodyLimitFilter.java` 수정과 회귀 테스트를 적용하고 빌드·배포한다.
2. 페이앱의 검증 가능한 원본 결제 통보를 재처리한다. `mul_no`, 연결 주문, 금액과 통보 인증을 확인하는 기존 검증 경로를 사용한다.
3. 통보 응답이 HTTP 200과 `SUCCESS`인지 확인한다. 이어 같은 주문의 완료 조회와 구매 내역이 `PAID`를 반환하는지 확인한다.
4. 구매 내역을 새로고침하여 결제 완료·결과 보기와 보관함 권한을 확인한다.

페이앱은 HTTP 200 및 `SUCCESS` 응답을 성공으로 판단하고, `checkretry=y`인 요청에 한해 실패한 통보를 최대 10회 재시도한다. 재시도가 종료된 기존 주문은 페이앱 측 재통보 또는 서버의 검증된 재처리가 필요하다. 브라우저 새로고침은 웹훅 재전송을 대신하지 않는다. [페이앱 공식 결제통보 문서](https://docs.payapp.kr/dev_center01.html).

이 작업에서는 운영 배포, 실제 결제·환불, 기존 주문 상태 변경, Git 커밋·푸시는 수행하지 않았다.
