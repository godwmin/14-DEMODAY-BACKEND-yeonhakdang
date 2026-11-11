# 페이앱 배포 설정

기본 결제 공급자는 `payapp`입니다. 포트원 어댑터는 `PAYMENT_PROVIDER=portone`일 때 활성화됩니다. 프론트는 페이앱 hosted payment URL 응답을 사용합니다.

Olim 백엔드 서비스에 아래 환경변수를 실행·서버 전용으로 등록합니다. 키 세 개는 비밀값으로 저장합니다.

```dotenv
PAYMENT_PROVIDER=payapp
PAYAPP_USER_ID=<판매자 로그인 아이디>
PAYAPP_LINK_KEY=<연동 KEY>
PAYAPP_LINK_VALUE=<연동 VALUE>
PAYAPP_REQUEST_KEY=<결제 KEY>
BACKEND_PUBLIC_ORIGIN=https://yeonhakdang-api-h0b8jz-tenant.olim.dev
PAYAPP_METHODS=CARD
SALES_ENABLED=false
```

키가 없어도 서버는 실행되고, 결제 요청 시 설정을 검증합니다. 결제 테스트를 시작할 때 SALES_ENABLED를 true로 설정합니다. 실제 승인·취소 요청은 판매자 계정 설정과 페이앱 심사 상태를 확인한 뒤 진행합니다.

승인 통보는 백엔드 `/api/webhooks/payapp`으로 전달됩니다. 결제 후 이동은 `/api/payments/payapp/return`에서 프론트로 연결합니다. 기존 FRONTEND_ORIGIN과 KAKAO_REDIRECT_URI는 프론트 도메인을 사용합니다. Vercel의 BACKEND_ORIGIN도 새 백엔드를 가리켜야 실제 사이트에서 같은 서버의 주문과 결제 상태를 사용합니다.

V2와 V3 마이그레이션이 동기화 버전과 페이앱 거래·취소 기록용 테이블을 추가합니다. 주문별 결과 스냅샷이 저장된 주문만 결제 준비가 가능합니다. 프론트 입력이나 브라우저 복귀만으로 결제 완료를 처리하지 않으며, 판매자 정보·서명·주문·금액이 검증된 통보로 상태를 반영합니다.

1,000원 미만 결제는 페이앱 제한으로 차단합니다. 현재 990원 부적 단품은 가격 변경 결정이 필요합니다. 결제 수단은 판매자 계정에서 허용된 항목으로 설정합니다.

테스트는 로컬 모의 페이앱 HTTP 서버를 사용합니다. 실제 판매자 계정 요청과 실결제·정산 검증은 별도입니다. 비밀값을 Git이나 문서에 기록하지 않습니다.
