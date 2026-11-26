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

# ─────────────────────────────────────────────── 6) 방 채팅 (v1.0.6)
# 메시지는 문서 ID 가 '뒤집은 시각'(MAX-ms)으로 시작해 목록 순서 = 최신 순서가 된다.
# 규칙은 5필드·1~500자·정수 createdAtMs 만 허용하고 고치기·지우기는 막는다.
CHAT_CODE=${CHAT_CODE:-CHAT01}    # 6자리 코드
CHAT_NOW=$(date +%s%3N)
CHAT_ID="b$(printf '%019d' $((9223372036854775807 - CHAT_NOW)))-check1"
cat > "$TMP/chat.json" <<JSON
{"fields":{"senderId":{"stringValue":"$DEVICE"},"senderName":{"stringValue":"서버검사"},"senderColor":{"stringValue":"#E5C158"},"text":{"stringValue":"채팅 검사 메시지"},"createdAtMs":{"integerValue":"$CHAT_NOW"}}}
JSON
expect_code "채팅 메시지 5필드 쓰기" 200 "$(req PATCH "$BASE/rooms/$CHAT_CODE/messages/$CHAT_ID?key=$API_KEY" "$TMP/chat.json")"
expect_code "채팅 메시지 목록 (pageSize=1 · 최신 확인용)" 200 "$(req GET "$BASE/rooms/$CHAT_CODE/messages?key=$API_KEY&pageSize=1")"

# 빈 글 → 403
cat > "$TMP/chat-empty.json" <<JSON
{"fields":{"senderId":{"stringValue":"$DEVICE"},"senderName":{"stringValue":"서버검사"},"senderColor":{"stringValue":"#E5C158"},"text":{"stringValue":""},"createdAtMs":{"integerValue":"$CHAT_NOW"}}}
JSON
expect_code "빈 글 메시지는 거부 (403)" 403 \
  "$(req PATCH "$BASE/rooms/$CHAT_CODE/messages/b-empty?key=$API_KEY" "$TMP/chat-empty.json")"

# 501자 → 403 (규칙 text.size() <= 500)
TEXT501=$(head -c 501 /dev/zero | tr '\0' 'a')
cat > "$TMP/chat-long.json" <<JSON
{"fields":{"senderId":{"stringValue":"$DEVICE"},"senderName":{"stringValue":"서버검사"},"senderColor":{"stringValue":"#E5C158"},"text":{"stringValue":"$TEXT501"},"createdAtMs":{"integerValue":"$CHAT_NOW"}}}
JSON
expect_code "501자 메시지는 거부 (403)" 403 \
  "$(req PATCH "$BASE/rooms/$CHAT_CODE/messages/b-long?key=$API_KEY" "$TMP/chat-long.json")"

# 6필드 → 403 (규칙 size() <= 5)
cat > "$TMP/chat-six.json" <<JSON
{"fields":{"senderId":{"stringValue":"$DEVICE"},"senderName":{"stringValue":"서버검사"},"senderColor":{"stringValue":"#E5C158"},"text":{"stringValue":"6필드"},"createdAtMs":{"integerValue":"$CHAT_NOW"},"extra":{"stringValue":"x"}}}
JSON
expect_code "메시지 6필드는 거부 (403)" 403 \
  "$(req PATCH "$BASE/rooms/$CHAT_CODE/messages/b-six?key=$API_KEY" "$TMP/chat-six.json")"

# 보낸 메시지 고치기·지우기 → 403 (남의 대화를 못 지우게)
sleep 1
expect_code "보낸 메시지 고치기는 거부 (403)" 403 \
  "$(req PATCH "$BASE/rooms/$CHAT_CODE/messages/$CHAT_ID?key=$API_KEY" "$TMP/chat.json")"
expect_code "메시지 지우기는 거부 (403)" 403 \
  "$(req DELETE "$BASE/rooms/$CHAT_CODE/messages/$CHAT_ID?key=$API_KEY")"
expect_code "소문자 방 코드 메시지도 거부 (403)" 403 \
  "$(req PATCH "$BASE/rooms/chat01/messages/b-lower?key=$API_KEY" "$TMP/chat.json")"

say ""
say "── 방장 내보내기 (v1.0.7) ─────────────────────────────────────"
# 규칙: rooms.kickedIds 에 들어간 기기는 자기 members/{deviceId} 를 **다시 쓸 수 없다**.
#       그래서 방장이 내보낸 뒤에도 그 폰의 위치 업로드(8초 주기)가 방에 재등장하지 않는다.
#       옛 방(kickedIds 필드 없음)에서도 오류 없이 통과해야 한다(레거시 방 보호).
KICKED=${KICKED:-servercheck-kicked01}
OTHER=${OTHER:-servercheck-other01}
HOST=${HOST:-servercheck-host01}

# 1) 아직 kickedIds 가 없는 방(레거시 모양)에 멤버 쓰기 → 200
#    (exists()·'kickedIds' in … 가드가 오류를 내지 않는지 확인하는 회귀 검사)
#    매번 같은 조건으로 돌도록 방 문서를 4필드(옛 모양)로 다시 만든 뒤 검사한다.
LEGACY_CODE=${LEGACY_CODE:-LEGACY}
cat > "$TMP/legacy-room.json" <<JSON
{"fields":{"code":{"stringValue":"$LEGACY_CODE"},"owner":{"stringValue":"서버검사"},"createdAt":{"timestampValue":"$now_iso"},"active":{"booleanValue":true}}}
JSON
expect_code "레거시 모양 방 만들기 (hostDeviceId·kickedIds 없음)" 200 \
  "$(req PATCH "$BASE/rooms/$LEGACY_CODE?key=$API_KEY" "$TMP/legacy-room.json")"
expect_code "레거시 방(필드 없음)에 멤버 쓰기" 200 \
  "$(req PATCH "$BASE/rooms/$LEGACY_CODE/members/$OTHER?key=$API_KEY" "$TMP/member8.json")"
req DELETE "$BASE/rooms/$LEGACY_CODE/members/$OTHER?key=$API_KEY" >/dev/null

# 2) 방장 정보·내보낸 목록 올리기
cat > "$TMP/kick.json" <<JSON
{"fields":{"kickedIds":{"arrayValue":{"values":[{"stringValue":"$KICKED"}]}}}}
JSON
expect_code "kickedIds 배열 쓰기 (updateMask)" 200 \
  "$(req PATCH "$BASE/rooms/$CODE?key=$API_KEY&updateMask.fieldPaths=kickedIds" "$TMP/kick.json")"
cat > "$TMP/host.json" <<JSON
{"fields":{"hostDeviceId":{"stringValue":"$HOST"}}}
JSON
expect_code "hostDeviceId 쓰기 (updateMask · 옛 방 방장 등록)" 200 \
  "$(req PATCH "$BASE/rooms/$CODE?key=$API_KEY&updateMask.fieldPaths=hostDeviceId" "$TMP/host.json")"

# 3) 내보낸 기기 vs 남은 기기
cat > "$TMP/member-kicked.json" <<JSON
{"fields":{"name":{"stringValue":"내보내진 기기"},"color":{"stringValue":"#FF6B7A"},"lat":{"doubleValue":37.3980},"lng":{"doubleValue":127.1090},"battery":{"integerValue":"55"},"sharing":{"booleanValue":true},"speedKmh":{"doubleValue":0.0},"staySince":{"timestampValue":"$now_iso"}}}
JSON
expect_code "내보낸 기기의 멤버 쓰기는 거부 (403 · 재입장 차단)" 403 \
  "$(req PATCH "$BASE/rooms/$CODE/members/$KICKED?key=$API_KEY" "$TMP/member-kicked.json")"
expect_code "내보내지 않은 기기는 그대로 통과 (200)" 200 \
  "$(req PATCH "$BASE/rooms/$CODE/members/$OTHER?key=$API_KEY" "$TMP/member8.json")"
expect_code "내보낸 기기의 멤버 문서 삭제(정리)는 허용 (200)" 200 \
  "$(req DELETE "$BASE/rooms/$CODE/members/$KICKED?key=$API_KEY")"

# 4) 내보낸 기기는 채팅도 쓰지 못한다
cat > "$TMP/chat-kicked.json" <<JSON
{"fields":{"senderId":{"stringValue":"$KICKED"},"senderName":{"stringValue":"내보내진 기기"},"senderColor":{"stringValue":"#FF6B7A"},"text":{"stringValue":"아직 들어 있어요"},"createdAtMs":{"integerValue":"$CHAT_NOW"}}}
JSON
expect_code "내보낸 기기의 메시지도 거부 (403 · 같은 방에서)" 403 \
  "$(req PATCH "$BASE/rooms/$CODE/messages/b-kicked?key=$API_KEY" "$TMP/chat-kicked.json")"

# 5) 모양 제한 — 21개·문자열은 거부
mkdir -p "$TMP/many"
python3 - "$TMP/many/kick21.json" <<'PY'
import json, sys
values = [{"stringValue": "kicked-%02d" % i} for i in range(21)]
json.dump({"fields": {"kickedIds": {"arrayValue": {"values": values}}}}, open(sys.argv[1], "w"))
PY
expect_code "kickedIds 21개는 거부 (403 · 규칙 20개 한도)" 403 \
  "$(req PATCH "$BASE/rooms/$CODE?key=$API_KEY&updateMask.fieldPaths=kickedIds" "$TMP/many/kick21.json")"
cat > "$TMP/kick-string.json" <<JSON
{"fields":{"kickedIds":{"stringValue":"$KICKED"}}}
JSON
expect_code "kickedIds 가 배열이 아니면 거부 (403)" 403 \
  "$(req PATCH "$BASE/rooms/$CODE?key=$API_KEY&updateMask.fieldPaths=kickedIds" "$TMP/kick-string.json")"

# 6) 방이 아예 없을 때의 멤버 쓰기는 그대로 통과 (exists() 가드)
expect_code "방 없는 곳의 멤버 쓰기는 그대로 통과 (200)" 200 \
  "$(req PATCH "$BASE/rooms/QQQQQQ/members/$DEVICE?key=$API_KEY" "$TMP/member8.json")"

# 7) 내보내기 뒤에도 방 문서의 다른 기능(약속 장소 updateMask)은 그대로 → 200
expect_code "kickedIds 가 있는 방에서도 약속 장소 PATCH 는 통과" 200 \
  "$(req PATCH "$BASE/rooms/$CODE?key=$API_KEY&$MASK" "$TMP/meeting.json")"

# 8) 검사 흔적 정리 — 내보낸 목록을 비우고 방장 값만 남긴다(필드는 지울 수 없어 빈 값으로)
cat > "$TMP/kick-empty.json" <<JSON
{"fields":{"kickedIds":{"arrayValue":{"values":[]}}}}
JSON
req PATCH "$BASE/rooms/$CODE?key=$API_KEY&updateMask.fieldPaths=kickedIds" "$TMP/kick-empty.json" >/dev/null
expect_code "멤버 정리 (남은 검사 멤버 삭제)" 200 \
  "$(req DELETE "$BASE/rooms/$CODE/members/$OTHER?key=$API_KEY")"
req DELETE "$BASE/rooms/QQQQQQ/members/$DEVICE?key=$API_KEY" >/dev/null

say ""
say "검사 방: rooms/$CODE (규칙상 방 문서는 앱에서 지울 수 없습니다 — 콘솔에서 지우면 됩니다)"
say "채팅 검사 메시지는 rooms/$CHAT_CODE/messages 에 남습니다(규칙상 삭제 불가 — 콘솔에서 정리)"
say "결과: $PASS 통과 · $FAIL 실패 (프로젝트 $PROJECT_ID)"
[ "$FAIL" -eq 0 ] || exit 1
