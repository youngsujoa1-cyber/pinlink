#!/usr/bin/env bash
# PinLink 실서버 왕복 검사 — Firestore REST 에 앱과 **같은 모양의 요청**을 그대로 보낸다.
#
# 검사하는 것
#   1) 방 만들기(4필드) · 조회 · 약속 장소 찍기/지우기(updateMask)
#   2) 멤버 문서 8필드(speedKmh · staySince 포함) — 보안 규칙의 size() <= 8 안에서 통과하는지
#   3) 방 문서가 **없는** 상태에서 약속 PATCH → 403 (v1.0.4 실기기 버그의 원인, 회귀 방지)
#   4) 9필드(옛 updatedAt 까지) 멤버 문서 → 403 (규칙이 실제로 8개를 강제하는지)
#   5) 나가기(멤버 삭제)
#
# 키는 local.properties(저장소 제외)에서 읽는다 — 이 파일에는 비밀값이 없다.
# 사용: bash tools/server-check/run.sh      (프로젝트 루트에서)
set -uo pipefail
cd "$(dirname "$0")/../.."

PROPS=${PROPS:-local.properties}
PROJECT_ID=${FIREBASE_PROJECT_ID:-$(grep -E '^FIREBASE_PROJECT_ID=' "$PROPS" | tail -1 | cut -d= -f2- | tr -d '[:space:]')}
API_KEY=${FIREBASE_API_KEY:-$(grep -E '^FIREBASE_API_KEY=' "$PROPS" | tail -1 | cut -d= -f2- | tr -d '[:space:]')}
if [ -z "${PROJECT_ID:-}" ] || [ -z "${API_KEY:-}" ]; then
  echo "✗ FIREBASE_PROJECT_ID / FIREBASE_API_KEY 가 없습니다 ($PROPS)" >&2
  exit 2
fi

BASE="https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"
CODE=${CODE:-CHECK5}          # 규칙: ^[A-Z0-9]{6}$
DEVICE=${DEVICE:-servercheck-0001}
TMP=$(mktemp -d)
PASS=0
FAIL=0
trap 'rm -rf "$TMP"' EXIT

say() { printf '%s\n' "$*"; }
ok() { PASS=$((PASS + 1)); say "PASS  $1"; }
no() { FAIL=$((FAIL + 1)); say "FAIL  $1"; }

# 요청 하나 — 마지막 줄에 HTTP 코드만 돌려준다
req() { # method path bodyfile -> code
  local method=$1 path=$2 body=${3:-}
  local out="$TMP/body.json"
  if [ -n "$body" ]; then
    curl -s -o "$out" -w '%{http_code}' -X "$method" \
      -H 'Content-Type: application/json; charset=utf-8' --data @"$body" "$path"
  else
    curl -s -o "$out" -w '%{http_code}' -X "$method" "$path"
  fi
}

expect_code() { # 이름 기대코드 실제코드
  if [ "$2" = "$3" ]; then ok "$1 → $3"; else no "$1 → $3 (기대 $2)"; sed 's/^/        /' "$TMP/body.json" | head -4; fi
}

now_iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# ─────────────────────────────────────────────── 1) 방 만들기 · 조회
cat > "$TMP/room.json" <<JSON
{"fields":{"code":{"stringValue":"$CODE"},"owner":{"stringValue":"서버검사"},"createdAt":{"timestampValue":"$now_iso"},"active":{"booleanValue":true}}}
JSON
expect_code "방 만들기 (4필드, 새로 만든 방 아님)" 200 "$(req PATCH "$BASE/rooms/$CODE?key=$API_KEY" "$TMP/room.json")"
expect_code "방 조회" 200 "$(req GET "$BASE/rooms/$CODE?key=$API_KEY")"

# ─────────────────────────────────────────────── 2) 약속 장소 찍기 · 지우기
cat > "$TMP/meeting.json" <<JSON
{"fields":{"meetingLat":{"doubleValue":37.3980},"meetingLng":{"doubleValue":127.1090},"meetingName":{"stringValue":"정자역 2번 출구"},"meetingBy":{"stringValue":"서버검사"},"meetingAt":{"timestampValue":"$now_iso"}}}
JSON
MASK='updateMask.fieldPaths=meetingLat&updateMask.fieldPaths=meetingLng&updateMask.fieldPaths=meetingName&updateMask.fieldPaths=meetingBy&updateMask.fieldPaths=meetingAt'
expect_code "약속 장소 찍기 (updateMask)" 200 "$(req PATCH "$BASE/rooms/$CODE?key=$API_KEY&$MASK" "$TMP/meeting.json")"
expect_code "약속 장소 지우기 (updateMask · 빈 본문)" 200 "$(req PATCH "$BASE/rooms/$CODE?key=$API_KEY&$MASK" <(printf '{"fields":{}}'))"

# ─────────────────────────────────────────────── 3) 방이 없을 때 약속 PATCH → 403 (v1.0.4 버그)
expect_code "방 없는 상태의 약속 PATCH 는 403 (v1.0.4 실기기 버그)" 403 \
  "$(req PATCH "$BASE/rooms/QQQQQQ?key=$API_KEY&$MASK" "$TMP/meeting.json")"

# ─────────────────────────────────────────────── 4) 멤버 8필드 (speedKmh · staySince)
cat > "$TMP/member8.json" <<JSON
{"fields":{"name":{"stringValue":"서버검사"},"color":{"stringValue":"#E5C158"},"lat":{"doubleValue":37.3980},"lng":{"doubleValue":127.1090},"battery":{"integerValue":"88"},"sharing":{"booleanValue":true},"speedKmh":{"doubleValue":42.0},"staySince":{"timestampValue":"$now_iso"}}}
JSON
expect_code "멤버 8필드 쓰기 (speedKmh · staySince 포함)" 200 \
  "$(req PATCH "$BASE/rooms/$CODE/members/$DEVICE?key=$API_KEY" "$TMP/member8.json")"
expect_code "멤버 목록" 200 "$(req GET "$BASE/rooms/$CODE/members?key=$API_KEY&pageSize=50")"

# 9필드(옛 updatedAt 까지) → 규칙 size() <= 8 에 걸려 403 이어야 한다
cat > "$TMP/member9.json" <<JSON
{"fields":{"name":{"stringValue":"서버검사"},"color":{"stringValue":"#E5C158"},"lat":{"doubleValue":37.3980},"lng":{"doubleValue":127.1090},"battery":{"integerValue":"88"},"moving":{"booleanValue":true},"sharing":{"booleanValue":true},"speedKmh":{"doubleValue":42.0},"staySince":{"timestampValue":"$now_iso"}}}
JSON
expect_code "멤버 9필드는 규칙이 거부 (403)" 403 \
  "$(req PATCH "$BASE/rooms/$CODE/members/$DEVICE?key=$API_KEY" "$TMP/member9.json")"

# 방 문서가 없어도 멤버 문서는 만들어진다 — v1.0.4 화면이 "공유 중"으로 멀쩡해 보인 이유
expect_code "방 없는 상태의 멤버 쓰기는 통과 (200)" 200 \
  "$(req PATCH "$BASE/rooms/QQQQQQ/members/$DEVICE?key=$API_KEY" "$TMP/member8.json")"
expect_code "그 멤버 정리" 200 "$(req DELETE "$BASE/rooms/QQQQQQ/members/$DEVICE?key=$API_KEY")"

# ─────────────────────────────────────────────── 5) 나가기
expect_code "멤버 삭제 (방 나가기)" 200 "$(req DELETE "$BASE/rooms/$CODE/members/$DEVICE?key=$API_KEY")"
expect_code "방 문서 삭제는 규칙이 거부 (403)" 403 "$(req DELETE "$BASE/rooms/$CODE?key=$API_KEY")"

say ""
say "검사 방: rooms/$CODE (규칙상 방 문서는 앱에서 지울 수 없습니다 — 콘솔에서 지우면 됩니다)"
say "결과: $PASS 통과 · $FAIL 실패 (프로젝트 $PROJECT_ID)"
[ "$FAIL" -eq 0 ] || exit 1
