# PinLink — 실시간 위치 공유 (PinLink · real-time location sharing)

친구·가족·동료가 **6자리 방 코드**로 같은 방에 들어와 서로의 위치를 한 지도에서 보는 안드로이드 앱입니다.
플레이스토어 출시와 AdMob 광고 수익화를 전제로 만들었고, 첫 실행부터 5개 언어(한국어·영어·일본어·중국어·스페인어)를 지원합니다.

- 패키지 `com.youngsu.pinlink` · versionCode `6` · versionName `1.0.5` · minSdk 26 / targetSdk 35
- 지도(하이브리드): **국내는 네이버 지도(JavaScript API v3, WebView)**, **해외는 CARTO 512×512(@2x) 베이스맵**(Canvas 직접 그리기)으로 자동 전환합니다. 대한민국 범위(위도 33.0~38.9, 경도 124.5~132.0)면 네이버, 그 밖이거나 네이버 지도가 뜨지 않으면 CARTO — 지금 떠 있는 지도는 화면 우측 상단 배지(`NAVER` / `CARTO`)에 표시합니다. 어두운/밝은 지도는 두 지도 모두에서 1탭 전환되고 고른 스타일은 앱에 저장됩니다. 네이버 키(`NAVER_MAP_CLIENT_ID`)가 없으면 전 세계를 CARTO 로만 그립니다.
- 동기화: Firestore REST(`HttpURLConnection`) — 외부 SDK 없음. **v1.0.3 부터 PinLink 전용 무료 Firebase 프로젝트(`pinlink-live`, 서울 리전)에 실제로 연결**되어 있고, 키가 비어 있는 빌드에서는 데모 모드(친구 2명 시뮬레이션)로 내려갑니다.

## 기능

| 영역 | 내용 |
| --- | --- |
| 첫 실행 | 언어 선택 카드(기기 언어 따르기 / 한국어 / English / 日本語 / 中文 / Español), 앱을 다시 그리지 않고 미리보기 |
| 방 | 닉네임·아바타 색 설정 → 새 방(6자리 코드) 만들기 / 코드로 참여, 코드 복사·공유(초대 문구) |
| 공유 모드 | 계속 공유(상시 백그라운드) · 1시간 · 3시간 · 8시간 · 24시간 · 일시정지(내 위치 숨기기). 시간이 끝나면 스스로 멈춤 |
| 백그라운드 | `foregroundServiceType="location"` 포그라운드 서비스 + 계속 뜨는 알림(중지 버튼). 앱을 닫아도 공유 |
| 정책 | 백그라운드 위치를 요청하기 전 **명시적 안내(Prominent Disclosure)** 필수, 동의 전에는 시스템 권한 창을 띄우지 않음 |
| 지도 | 멤버별 핀(이름·색·첫 글자, 오래된 핀은 흐리게), 내 위치 점, 내 위치로 이동 버튼, 멤버/칩을 누르면 그 멤버로 이동. 국내는 네이버 지도, 해외는 CARTO 지도로 자동 전환 |
| 약속 장소 | 지도 정중앙 핀으로 집결 장소를 찍고 이름을 붙여 방 전체에 공유 / 지우기 · 지도를 **길게 누르면** 그 자리에 바로 약속 핀 |
| 속도·체류 (v1.0.5) | GPS 속도를 km/h 로 환산해 내 속도 칩(`42 km/h`)·멤버 카드·방 목록에 표시. 2km/h 미만은 정지로 보고 **머문 시간**(`12분째 머무름`, 1분 미만 `방금 도착`)으로 바뀝니다. 70m 넘게 벗어나거나 움직이면 체류 시간이 새로 시작되고, Firestore `staySince` 로 함께 동기화됩니다 |
| 디자인 (v1.0.5) | 딥 옵시디언/차콜 글라스 + 샴페인 골드 · 플래티넘 · 시안 포인트. 지도 위 칩은 반투명 다크 글라스 + 1dp 테두리. 아바타 색 팔레트도 샴페인·플래티넘·시안 톤으로 교체(예전 기본 민트는 한 번만 새 기본값으로 이동) |
| 광고 | 화면 최하단 배너(콘텐츠 → 하단 탭 바 → 배너), 방 생성·참여 직후 전면 광고(3분 쿨다운). Google 공식 테스트 ID |
| 정보 | 개인정보 처리방침(한국어/영어) 앱 내 WebView + `docs/privacy.html` |

## 빌드

```bash
export JAVA_HOME=/workspace/buildenv/jdk-17.0.21.1+2
export GRADLE_USER_HOME=/workspace/buildenv/gradle-home
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew --offline lintDebug assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
bash tools/logic-check/run.sh                   # 순수 로직 검사(방 코드·만료·거리·속도·체류·Firestore 직렬화)
bash tools/server-check/run.sh                  # 실서버 왕복 검사(방·약속·멤버 8필드·규칙 거부)
python3 tools/map-js-check/check.py             # 지도 페이지 JS 검사(pip install dukpy 필요)
```

`local.properties`(저장소에 올리지 않음):

```properties
sdk.dir=/workspace/buildenv/android-sdk
FIREBASE_PROJECT_ID=pinlink-live         # 기본값도 pinlink-live
FIREBASE_API_KEY=AIzaSy…                 # 비어 있으면 데모 모드(저장소에는 올리지 않음)
CARTO_KEY=cb1_...                          # CARTO 베이스맵 키(@2x 고해상도 타일). 없으면 OpenStreetMap
NAVER_MAP_CLIENT_ID=…                      # 네이버 클라우드 플랫폼 Dynamic Map 키(국내 지도). 없으면 전부 CARTO
```

## 실서버 동기화 (v1.0.3 부터 연결됨)

PinLink 전용 **무료 Firebase 프로젝트**(Spark, 결제수단 등록 없음)에 실제로 붙어 있습니다.

| 항목 | 값 |
| --- | --- |
| 프로젝트 ID | `pinlink-live` (프로젝트 번호 635104722751) |
| Firestore | `(default)` · 리전 `asia-northeast3`(서울) · Native 모드 |
| 웹 앱 ID | `1:635104722751:web:2d5d712556823017f45d8a` |
| 콘솔 | <https://console.firebase.google.com/project/pinlink-live/firestore> |
| REST 베이스 | `https://firestore.googleapis.com/v1/projects/pinlink-live/databases/(default)/documents` |

- `local.properties` 의 `FIREBASE_PROJECT_ID`·`FIREBASE_API_KEY` 가 `BuildConfig` 로 들어가고, 앱은 자동으로 REST 동기화로 동작합니다(설정 화면 "실시간 동기화: 서버 연결됨"). 키가 비면 데모 모드.
- API 키는 Google Cloud 콘솔에서 **Cloud Firestore API 전용**으로 제한했습니다(다른 API 호출은 거부). Android 앱 제한(패키지·SHA-1)은 순수 `HttpURLConnection` 호출에 헤더가 실리지 않아 걸면 호출이 깨지므로 걸지 않았습니다.
- 키를 줄여도 공개 규칙 때문에 **키 없이도 REST 호출은 통과**합니다. 실제 방어선은 아래 보안 규칙입니다.

콘솔에 게시된 보안 규칙(한 줄로 저장돼 있음 — 콘솔 편집기가 줄바꿈을 합치고 `//` 주석 뒤를 먹으므로 주석 없이 올렸습니다):

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function okCode(c) { return c is string && c.matches('^[A-Z0-9]{6}$'); }

    match /rooms/{code} {
      allow read: if true;
      allow create, update: if okCode(code) && request.resource.data.size() <= 14
        && request.resource.data.code == code
        && (!request.resource.data.keys().hasAny(['owner'])
            || (request.resource.data.owner is string && request.resource.data.owner.size() <= 40))
        && (!request.resource.data.keys().hasAny(['meetingName'])
            || (request.resource.data.meetingName is string && request.resource.data.meetingName.size() <= 60))
        && (!request.resource.data.keys().hasAny(['meetingBy'])
            || (request.resource.data.meetingBy is string && request.resource.data.meetingBy.size() <= 40));
      allow delete: if false;   // 방 문서는 앱에서 지우지 않는다(남용 방지)

      match /members/{deviceId} {
        allow read: if true;
        allow create, update: if okCode(code) && request.resource.data.size() <= 8
          && request.resource.data.lat is number && request.resource.data.lat >= -90 && request.resource.data.lat <= 90
          && request.resource.data.lng is number && request.resource.data.lng >= -180 && request.resource.data.lng <= 180
          && (!request.resource.data.keys().hasAny(['name'])
              || (request.resource.data.name is string && request.resource.data.name.size() <= 40));
        allow delete: if true;  // 나가기
      }
    }

    match /{document=**} { allow read, write: if false; }
  }
}
```

문서 모양:

```
rooms/{code}                     code, owner, createdAt, active, meetingLat/Lng/Name/By/At
rooms/{code}/members/{deviceId}  name, color, lat, lng, battery, sharing, speedKmh, staySince
```

> **멤버 문서는 정확히 8필드**입니다. 게시된 규칙이 `request.resource.data.size() <= 8` 이라 9번째 필드를 보내면 403 입니다(v1.0.5 실서버 확인). 그래서 v1.0.5 에서 `moving`(속도로 계산)과 `updatedAt`(문서의 `updateTime` 사용)을 문서에서 빼고 `speedKmh`·`staySince` 를 넣었습니다 — **규칙은 바꾸지 않아도 됩니다.**

> **주의 (PATCH 는 문서 전체를 교체한다)**: Firestore REST 의 `PATCH` 는 `updateMask` 를 붙이지 않으면 문서를 통째로 갈아엎습니다. v1.0.2 까지 `setMeeting` 이 마스크 없이 보내서 **약속 장소를 찍으면 방 문서의 `code`·`owner`·`createdAt` 이 지워지고, 약속을 지우면 방 문서가 빈 문서가 됐습니다.** v1.0.3 에서는 `FirestoreCodec.meetingMaskQuery()`(`updateMask.fieldPaths=meetingLat&…`)를 항상 붙여 약속 필드만 바꿉니다.

## 구조

```
app/src/main/java/com/youngsu/pinlink/
  core/PinLogic.kt          방 코드·공유 시간·거리·움직임·Firestore 직렬화 (안드로이드 없음 → JVM 검사)
  data/AppPrefs.kt          프로필·방·공유 모드 저장
  data/AppLanguage.kt       앱 언어(6개)
  data/PinCloud.kt          SyncEngine — CloudEngine(Firestore REST) / DemoEngine(시뮬레이션), 실패 사유(SyncFault)
  data/SyncLog.kt           최근 120줄 통신 기록(설정 → 동기화 진단)
  data/PinSession.kt        앱 상태 한 곳: 위치 요청·8초 업로드·폴링·만료 처리
  location/ShareService.kt  포그라운드 서비스 + 알림(중지 버튼)
  map/MapTileProvider.kt    OSM 타일 다운로드·메모리/디스크 캐시
  map/MapRegion.kt          국내/해외 판별 + 네이버·CARTO 전환 규칙 (안드로이드 없음 → JVM 검사)
  ui/widget/MemberMapView.kt  CARTO 지도 그리기(팬·핀치·핀 탭·약속 장소 핀·고정 핀·길게 누르기)
  ui/widget/NaverMapView.kt   네이버 지도 WebView + JS 브리지(중심·핀 탭·길게 누르기·실패 알림)
  ui/widget/HybridMapView.kt  두 지도를 한 API 로 — 국내/해외에 따라 자동 전환
  assets/naver_map.html       네이버 지도 페이지(핀·내 위치·깃발·고정 핀·다크 모드는 CSS 색반전)
  ui/StayText.kt            체류 시간 문구(방금 도착 / 12분째 머무름)
  ui/MainActivity.kt        탭(지도·방·설정) + 배너 + 권한 흐름 + 전면 광고 + 실패 사유 토스트
  ui/MapScreen · RoomScreen · SettingsScreen · Dialogs · Onboarding · PrivacyActivity
```

## 개인정보 처리방침

- 앱 안: 설정 → 개인정보 처리방침 (`app/src/main/assets/privacy.html`)
- 웹 게시용: `docs/privacy.html` (GitHub Pages 등에 그대로 올리면 한국어/영어 전환 가능)
- 공개 주소(GitHub Pages `main` → `/docs` 활성화 후)
  - 앱 소개: <https://youngsujoa1-cyber.github.io/pinlink/>
  - 개인정보 처리방침: <https://youngsujoa1-cyber.github.io/pinlink/privacy.html> — Play Console 개인정보 처리방침 URL 로 넣을 주소
  - `app-ads.txt`: <https://youngsujoa1-cyber.github.io/pinlink/app-ads.txt> (AdMob 크롤러가 보는 도메인 루트는 `youngsujoa1-cyber.github.io` 라서, 루트 저장소 `youngsujoa1-cyber.github.io` 에도 같은 파일이 있어야 인증된다)

## 검증한 것 / 못 한 것

- `./gradlew --offline lintDebug assembleDebug` 통과 (lint error 0, v1.0.5)
- `bash tools/server-check/run.sh` — v1.0.5 실서버 **12개 검사 전부 통과**: 방 생성·조회, 약속 찍기/지우기(updateMask), **방이 없을 때 약속 PATCH 는 403(버그 재현)**, 멤버 8필드(speedKmh·staySince) 200, **9필드 403**, 방 없는 멤버 쓰기 200, 멤버 삭제 200, 방 문서 삭제 403
- JVM 로직 검사 **전부 통과** (`tools/logic-check/run.sh`, v1.0.3 에서 약속 마스크 검사 4개 추가): 방 코드 생성·정규화(전각 문자 포함), 모드별 종료 시각·남은 시간·만료, 하버사인 거리, 이동 판정, Firestore 필드·JSON 직렬화(이스케이프·숫자 표기), 문서 되읽기
- APK 확인(v1.0.3): `aapt2 dump badging` 으로 패키지 `com.youngsu.pinlink` / versionCode 4 / 1.0.3 / targetSdk 35 / `INTERNET` 권한 / 권한 9종, `foregroundServiceType=0x8(location)` 서비스, `assets/privacy.html`, 5개 언어 문자열, adaptive icon 포함
- Firestore REST 요청 모양 검증: 앱이 보내는 그대로의 본문을 실제 Firestore 엔드포인트로 보내 **400(깨진 JSON)과 달리 403(규칙 거부)** 응답을 확인 — 즉 본문은 파싱되고 보안 규칙에서만 막힌다
- **실서버 왕복(v1.0.3, `pinlink-live`)**: 방 만들기 → 방 조회 → 약속 찍기 → 약속 지우기 → 멤버 위치 업로드 → 멤버 목록 → 나가기 전부 HTTP 200. 규칙 거부도 확인(소문자 코드 403 · 범위 밖 좌표 403 · 방 문서 삭제 403 · code 없는 방 문서 403). 검증 스크립트 `tool-results/pinlink-live-test.sh`
- v1.0.4 국내/해외 판별 검사 36개 추가(`tools/logic-check/run.sh`): 대한민국 경계값(위도 33.0/38.9, 경도 124.5/132.0), 파리·도쿄·뉴욕·시드니, 여유폭(±0.6°), 전환 규칙(키 없음·실패·중심 없음 → CARTO)
- 네이버 지도 인증 검증(2026-11-26, 실제 키): `curl -sI` → HTTP 200 · JS 326KB 정상 수신. 런타임 인증 엔드포인트 `https://oapi.map.naver.com/v3/auth?ncpKeyId=…&url=https://youngsujoa1-cyber.github.io/pinlink/` → `{"result":{"apiQuota":"200000","apiKeyInfo":"apiKey:map/webApiGroup"}}`, 같은 키로 등록되지 않은 주소(`evil.example.com`)·가짜 키는 HTTP 401 `Authentication Failed` — 등록한 도메인에서만 통과하는 것을 확인
- 네이버 지도 JS 실제 동작(같은 키, 등록 도메인 페이지에서 SDK 로드): 지도 생성·중심/줌 조회 정상, `nrbe.pstatic.net/styles/basic/…png` 타일 요청 발생(`performance` 리소스로 확인) → 인증 통과 후 타일까지 내려오는 것 확인
- 지도 페이지 JS를 Duktape 로 스텁 렌더링해 26개 동작 검사 (`python3 tools/map-js-check/check.py`, `pip install dukpy`): 멤버 마커 생성·재사용·삭제, 이름표/첫 글자/색/투명도/선택 링, 내 위치 점, 약속 깃발, 이동(`moveTo`), 다크 클래스 on/off, 고정 핀 on/off, 따라가기 on/off, 중심 콜백
- `tools/ui-smoke/run.sh` 에 지도 제공자 배지 검사 추가 — 네이버 지도가 준비되지 않는 환경에서 9초 뒤 CARTO 로 넘어가고 앱은 살아 있는지(13개 테스트 전부 통과)
- **못 한 것**: 이 환경에는 기기·에뮬레이터가 없어 실제 화면 렌더링, GPS 측정, 백그라운드 서비스 유지, 광고 노출, **두 기기 사이의 실제 위치 동기화**, **네이버 지도 위에서의 핀·핀치 체감**은 설치 후 확인이 필요합니다(서버·인증·타일 요청은 위처럼 검증).

## v1.0.5 — "서버에 연결하지 못했어요" 원인과 수정 + 속도·체류·프리미엄 다크 (2026-11-26)

**실기기 증상**: 5G 로 연결된 폰에서 방 코드 `SV55WC` 를 띄운 채 지도 화면에 `서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.` 토스트.

**원인 (실서버로 재현·확정)**

1. **그 방 문서가 서버에 없었습니다** — `GET rooms/SV55WC` → 404, `rooms` 컬렉션 0건. 화면의 코드·`공유 중` 은 기기에 저장된(prefs) 값이었습니다.
2. 지도 화면에서 이 문구가 뜨는 곳은 **약속 장소 저장**(`PATCH rooms/{code}?updateMask=…`) 한 곳뿐입니다. 문서가 없는 상태의 `PATCH` 는 Firestore 에서 **문서 만들기(create)** 로 처리되는데, 게시된 규칙의 create 조건 `request.resource.data.code == code` 를 약속 본문(`meeting*` 만)이 만족하지 못해 **403 PERMISSION_DENIED** (curl 로 그대로 재현).
3. 앱이 모든 비2xx·예외를 `false` 하나로 뭉개 `err_network` 만 띄웠습니다 — 권한 거부가 네트워크 문제로 보였던 이유.
4. 방이 없어도 **멤버 문서 쓰기는 200** 이라(하위 컬렉션은 부모 문서를 요구하지 않음) 지도·`공유 중` 은 정상으로 보였습니다.
5. `fetchRoom` 이 **404(없음)와 통신 실패를 구분하지 못해** 앱이 방이 사라진 걸 알아채지도, 고치지도 못했습니다.

**수정**

- `ensureRoom(seed)` — 방·약속을 쓰기 전에 `code`·`owner`·`createdAt` 이 들어간 온전한 문서를 보장. 앱 시작·폴링에서 방이 사라졌으면 **스스로 되살리고** 한 번 알립니다(`서버에 없던 방 정보를 다시 만들었어요`). 사용자의 `SV55WC` 도 다음 실행 때 서버에 다시 생깁니다.
- 실패를 이유별로 구분: `OFFLINE / DENIED(403) / MISSING(404) / BAD_REQUEST / SERVER / UNKNOWN` + Firestore 원문 메시지. 토스트에 사유 한 줄, `설정 → 동기화 진단` 에 마지막 오류·최근 통신 기록·`서버 다시 확인`·`기록 복사`.
- `SyncLog`: 최근 120줄을 기기에 남기고 복사할 수 있게(기기에서만 나는 문제를 캡처 없이 확인).
- **속도(km/h)**: GPS `location.speed` → km/h, 2km/h 미만은 정지(0). 속도를 못 받으면 두 측점 거리·시간으로 계산. 지도 상단 칩·멤버 카드·방 목록에 표시.
- **체류 시간**: 70m 안에서 서 있으면 `staySince` 유지, 움직이거나 벗어나면 새로 시작 → `12분째 머무름` / `1시간 25분째` / 1분 미만 `방금 도착`. Firestore `staySince` 로 동기화(재시작해도 이어짐).
- **프리미엄 다크**: 딥 옵시디언(`#080B12`~`#141B2D`) + 샴페인 골드(`#E5C158`/`#D4AF37`) + 플래티넘/시안. 지도 위 칩은 반투명 글라스 + 1dp 골드 헤어라인. 네이버 지도일 때 앱이 그리던 저작권 문구를 감춰 SDK 로고와 겹치지 않게 정리.
- **초대 문구에 Play 스토어 링크** 추가(5개 언어): `https://play.google.com/store/apps/details?id=com.youngsu.pinlink`
- 테스트 도구 `tools/server-check/run.sh` 추가, `tools/ui-smoke/run.sh` 의 Robolectric jar 내려받기 경로 오류 수정(파일 이름 `android-all-instrumented-…`).
- 버전 `versionCode 6` / `versionName "1.0.5"`

**검증**

- `tools/logic-check/run.sh` 199 PASS(속도 16·체류 12·문서 8필드 검사 추가) · `tools/ui-smoke/run.sh` 13개 통과 · `lintDebug` · `assembleDebug` 통과
- `tools/server-check/run.sh` 12/12 통과(위 목록)
- APK `downloads/pinlink.apk` — `aapt2 dump badging`: versionCode 6 / 1.0.5, debug 서명(다른 안드로이드 폰에 그대로 설치 가능)

**남은 주의**

- 실기기에서 GPS 속도·체류 값의 체감(터널·지하주차장 등)은 설치 후 확인이 필요합니다. 속도를 못 받는 기기에서는 위치 변화로 계산하므로 8초 업로드 주기만큼 늦게 반영됩니다.
- `tools/server-check/run.sh` 는 `rooms/CHECK5` 검사 방을 남깁니다(규칙상 앱에서 방 문서를 지울 수 없음). 콘솔에서 지우면 됩니다.

## v1.0.4 — 국내 네이버 지도 + 해외 CARTO (2026-11-26)

**한 일**

- 네이버 클라우드 플랫폼에 `PinLink` Application 등록 + Dynamic Map 키 발급(월 600만 건 무료). 등록한 Web 서비스 URL `https://youngsujoa1-cyber.github.io`, Android 패키지 `com.youngsu.pinlink`
- 키는 `local.properties` 의 `NAVER_MAP_CLIENT_ID` → `BuildConfig` 로만 들어갑니다(공개 저장소 0건). 지도 페이지(`assets/naver_map.html`)에는 `__NCP_KEY_ID__` 자리표시자만 있고, 앱이 실행할 때 채워 넣습니다
- `map/MapRegion.kt`: 대한민국 범위(33.0~38.9 / 124.5~132.0) 판별 + 전환 규칙(네이버 → CARTO 는 ±0.6° 여유를 두어 경계에서 깜빡이지 않음)
- `ui/widget/NaverMapView.kt`: `https://youngsujoa1-cyber.github.io/pinlink/` 를 baseURL 로 WebView 를 열어 네이버 지도 v3 를 그립니다(등록된 주소라서 인증 통과). JS 브리지로 중심 이동·핀 탭·길게 누르기·실패를 주고받고, 9초 안에 지도가 뜨지 않거나 인증·네트워크가 실패하면 CARTO 로 넘깁니다
- `ui/widget/HybridMapView.kt`: 두 지도를 하나의 API 로 감싸 지도 화면은 어느 쪽이 떠 있는지 몰라도 되게 했습니다. 지도 중심이 국내면 네이버, 해외로 나가면 CARTO(중심·줌 유지한 채)로 바뀌고, 우측 상단 `NAVER` / `CARTO` 배지와 저작권 표시(© NAVER Corp. / © OpenStreetMap · © CARTO)도 함께 바뀝니다
- 기존 기능을 네이버 지도 위에서도 동일하게: 밝은/어두운 지도(어두운 쪽은 지도만 CSS 색반전, 핀은 한 번 더 반전해 원래 색 유지), 내 위치 파란 점, 멤버 핀·이름표·선택 링·오래된 핀 흐리게, 약속 장소 깃발, 방 전체 팬/핀치/두 번 탭 확대, 내 위치로 이동, 정중앙 고정 핀으로 약속 찍기
- **길게 누르기**로 약속 장소 찍기 추가(두 지도 모두) — 누른 자리에 바로 이름 입력 창
- 버전 `versionCode 5` / `versionName "1.0.4"`

**검증**

- 네이버 지도 인증: `curl` HTTP 200 + 런타임 인증 엔드포인트 결과(위 "검증한 것" 참고), 등록되지 않은 도메인·가짜 키는 401
- `./gradlew --offline lintDebug assembleDebug` · `tools/logic-check/run.sh`(국내/해외 36개 추가) · `tools/ui-smoke/run.sh`(13개) 전부 통과
- APK `downloads/pinlink.apk` (11.2MB) · `aapt2 dump badging` versionCode 5 / 1.0.4

**남은 주의**

- 네이버 지도는 무료 쿼터(월 600만 건) 안에서만 무료입니다. 지도 1장을 열 때마다 타일·인증 호출이 쌓이므로 사용자가 늘면 콘솔에서 사용량을 확인하세요
- 네이버 지도 어두운 모드는 공식 다크 스타일이 아니라 CSS 색반전입니다(로고까지 반전됨). 네이버가 스타일맵(커스텀 스타일)을 제공하면 그쪽으로 바꾸는 편이 더 깔끔합니다

## v1.0.3 — 무료 Firebase 실서버 연결 (2026-11-26)

**한 일**

- 사용자 계정(youngsujoa1@gmail.com)으로 **PinLink 전용 무료 Spark 프로젝트 `pinlink-live`** 를 만들고 서울 리전(`asia-northeast3`)에 Cloud Firestore 생성 (Gemini 사용설정·개발자 프로그램·애널리틱스는 모두 해제, 결제수단 등록 없음)
- `rooms/{code}` + `rooms/{code}/members/{deviceId}` 규칙 게시 (읽기 공개 / 방 생성·수정은 6자리 코드·필드 크기 제한 / 방 삭제 불가 / 멤버 삭제 허용 / 나머지 전면 차단)
- `local.properties` 에 `FIREBASE_PROJECT_ID=pinlink-live`·`FIREBASE_API_KEY` 를 넣고, `build.gradle.kts` 의 기본 프로젝트 ID 도 `daeri-ledger-spots` → `pinlink-live` 로 교체 (APK dex 에 남은 `daeri-ledger-spots` 문자열 0건 확인)
- API 키를 Google Cloud 콘솔에서 **Cloud Firestore API 전용**으로 제한
- **버그 수정**: `setMeeting` 이 `updateMask` 없이 PATCH 해서 방 문서가 통째로 교체되던 문제 → `FirestoreCodec.meetingFieldPaths()`·`meetingMaskQuery()` 를 만들어 항상 마스크를 붙임(설정·삭제 모두). JVM 검사 4개 추가.
- 버전 `versionCode 4` / `versionName "1.0.3"`

**검증**

- 실서버 왕복·규칙 거부 전부 확인(`tool-results/pinlink-live-test.sh`) — 테스트 문서는 콘솔에서 삭제해 지금 `rooms` 컬렉션은 비어 있습니다
- `./gradlew --offline lintDebug assembleDebug` · `tools/logic-check/run.sh` · `tools/ui-smoke/run.sh` 통과
- APK `downloads/pinlink.apk` (11.2MB)

**남은 주의**

- 방 문서는 규칙상 삭제할 수 없어 오래된 방은 `rooms` 컬렉션에 계속 쌓입니다(문서 1건 ≈ 수백 바이트, Spark 저장 1GiB). 정리가 필요하면 콘솔에서 직접 지우거나, 나중에 TTL/Cloud Functions(Blaze 전환)로 자동화할 수 있습니다.
- 6자리 코드를 아는 사람은 누구나 방에 들어와 위치를 올릴 수 있는 구조입니다(앱 설계상 인증 없음). 스팸이 문제가 되면 Firebase App Check(Play Integrity) 도입이 다음 단계입니다.
- 나중에 Firebase Auth(익명 로그인 등)를 붙이면 API 키 제한에 Identity Toolkit API 를 다시 추가해야 합니다.

## v1.0.2 — 지도가 흐리고 지저분하던 문제 (2026-11-26)

**원인 (두 가지)**

1. `local.properties` 의 `CARTO_KEY` 가 비어 있어 256×256 OpenStreetMap 기본 타일로 떨어졌다 — OSM 기본 스타일은 도로·건물·POI 가 빽빽해 "지저분"하고, density 3 기기에서 256px 타일을 768px 로 3배 늘려 그려 "흐릿"했다.
2. `MapTileProvider` 가 `@2x`(512×512) 타일을 요청하지 않았고, CARTO 키 파라미터 이름도 틀렸다(`api_key` → CARTO 는 `key`). 틀린 이름으로 보내면 200 응답과 함께 **'API KEY REQUIRED' 워터마크 256×256 타일**이 온다.

**수정**

- `CARTO_KEY` 적용 + `https://{a|b|c|d}.basemaps.cartocdn.com/{style}/{z}/{x}/{y}@2x.png?key=…` 로 512×512 타일 요청(좌표로 서브도메인 분산)
- 스타일 2종: **다크(Dark Matter)** 기본 · **밝은 지도(Voyager)** — 지도 우측 상단 레이어 버튼으로 1탭 전환, `AppPrefs.mapStyle` 에 저장
- 디스크 캐시를 스타일별 `pinlink-tiles/v2_dark` / `v2_light` 로 분리하고, v1.0.1 의 저해상도 `v1` 캐시는 첫 실행 때 삭제
- 렌더링: 타일 Paint 에 `FILTER_BITMAP_FLAG`+`ANTI_ALIAS_FLAG`, 줌이 바뀌는 순간 **부모 타일(z-1) 크롭 확대 폴백**(빈 격자 깜빡임 제거), 화면 둘레 한 줄 프리페치, 다운로드 스레드 3→6, 메모리 캐시 예산 확대(512px 타일 ≈ 1MB/장)
- @2x 요청이 512px 미만(=워터마크)으로 오면 실패로 보고 OpenStreetMap 으로 폴백
- CARTO 무료 티어 조건인 지도 저작권 표시(`© OpenStreetMap contributors · © CARTO`)를 지도 위에 추가

**검증**

- `curl`/파이썬으로 zoom 3·12·15·17·18 의 dark_all·voyager `@2x` 타일이 **모두 HTTP 200 · 512×512** 로 내려오는 것 확인(키 파라미터 `key`)
- 앱과 같은 계산으로 타일을 이어 붙인 미리보기: `downloads/pinlink-map-dark-preview.png`, `pinlink-map-light-preview.png`, 비교용 `pinlink-map-v101-compare.png`
- `./gradlew --offline lintDebug assembleDebug` · `tools/logic-check/run.sh` · `tools/ui-smoke/run.sh` 통과, APK `versionCode 3 / versionName 1.0.2`
- **못 한 것**: 기기에서의 실제 체감(핀치 중 부드러움)은 설치 후 확인 필요. CARTO 키는 2026-11-30 이후 재등록이 필요할 수 있음(키 정책 FAQ).

## v1.0.1 — 설치 후 첫 실행 크래시 + 기본 아이콘 문제 (2026-11-26)

**원인 (한 가지)** — 벡터 드로어블 `pathData` 의 호(arc) 명령에 플래그를 붙여 쓴 표기

```
a2.5 2.5 0 010-5      ← 기기(ART)의 PathParser 가 "a needs to be followed by a multiple of 7 floats" 로 거부
```

- `ic_place`(지도 핀)가 `MapScreen` 을 만드는 순간 `setImageResource` 로 읽히면서 `Resources$NotFoundException` → **앱이 열리자마자 종료**
- 같은 표기를 쓰던 `ic_notification`(포그라운드 알림 아이콘), `ic_launcher_monochrome`(적응형 아이콘 레이어)도 같은 오류
- 적응형 아이콘은 레이어 하나만 실패해도 통째로 못 읽어서 런처가 **기본 안드로이드 아이콘**으로 떨어졌다

**수정**

- 세 드로어블의 경로를 호 명령을 쓰지 않는 구글 Material Icons 표준 `place` 경로로 교체 → APK 안에 붙여 쓴 플래그 표기가 0건
- 적응형 아이콘 앞면을 벡터가 아니라 그림(PNG, `mipmap-*dpi/ic_launcher_foreground`)으로 바꿔 3D 아이콘이 그대로 보이게 함 (같은 계열 앱들이 쓰는 방식)
- 시작 경로 보강: `MainActivity` 화면 만들기를 try/catch 로 감싸 실패하면 앱을 죽이는 대신 안내 화면([다시 열기])을 보여 준다. 배너 광고는 첫 화면 뒤에 붙인다
- AdMob 자동 초기화 provider(`MobileAdsInitProvider`)를 매니페스트에서 빼고 `PinLinkApp`→`AdManager` 가 try/catch 안에서 직접 초기화 (앱 코드 밖에서 시작 중 던지던 예외 차단, 앱 ID meta-data 는 그대로)
- `CrashLog`: 잡히지 않은 예외를 기기에 적어 두고 다음 실행 때 화면에 보여 준다 (복사 버튼) — 재현되지 않는 크래시를 사용자 캡처 한 장으로 확인하기 위한 장치

**검증 (Robolectric, 실제 안드로이드 파서 = `@GraphicsMode(NATIVE)`)**

- `ResourceSmokeTest`: 앱의 **모든 drawable·mipmap 을 실제 파서로 inflate** (12개 테스트 전부 통과), 런처 아이콘·알림 아이콘을 비트맵으로 그려 픽셀 확인
- `UiSmokeTest`: 앱 시작 → 첫 실행 언어 카드 → 탭 이동(지도/방/설정) → 방 만들기 다이얼로그 → 저장된 공유 상태 복원 → 크래시 기록/안내 창
- legacy 그래픽 모드에서는 이 오류가 그냥 넘어가므로, 테스트는 반드시 NATIVE 모드로 돌린다
