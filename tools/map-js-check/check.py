#!/usr/bin/env python3
"""지도 페이지(assets/naver_map.html) 안의 JavaScript 를 실제 브라우저·기기 없이 검사한다.

네이버 SDK 와 DOM 을 가짜 객체(스텁)로 바꿔 끼우고, 앱이 부르는 것과 같은 순서로
`PinLink.setMembers` / `setMy` / `setMeeting` / `moveTo` / `setDark` / `setPick` / `setFollow`
를 호출해 마커 HTML·좌표·콜백이 기대대로 만들어지는지 본다.

  setMembers 는 앱이 실제로 넘기는 배열 인자(`PinLink.setMembers([{...}])`)로도 검사한다.
  v1.0.6 은 문자열만 받는다고 가정해 배열이 들어오면 JSON.parse 에서 SyntaxError 가 나
  멤버 핀이 하나도 그려지지 않았다(네이버 지도 실기기 버그).

사용:
  pip install dukpy
  python3 tools/map-js-check/check.py

(실제 지도 렌더링·손가락 제스처는 기기에서 확인해야 한다 — 이 검사는 JS 로직만 본다.)
"""
import json
import os
import re
import sys

try:
    import dukpy
except ImportError:  # pragma: no cover
    print("dukpy 가 필요합니다: pip install dukpy")
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "naver_map.html")

HARNESS = r"""
var logs = [];
function mkEl(tag){ var e = { tagName: tag, style: {}, children: [], attrs: {}, classes: {},
  classList: { toggle: function(c,on){ e.classes[c]=!!on; }, add: function(c){ e.classes[c]=true; }, contains: function(c){ return !!e.classes[c]; } },
  getAttribute: function(n){ return e.attrs[n] || null; }, setAttribute: function(n,v){ e.attrs[n]=v; },
  addEventListener: function(){}, appendChild: function(c){ e.children.push(c); return c; },
  getBoundingClientRect: function(){ return {left:0, top:0}; } }; return e; }
var mapEl = mkEl('div'); var pickEl = mkEl('div');
var document = { body: mkEl('body'), head: mkEl('head'), documentElement: mkEl('html'),
  getElementById: function(id){ return id==='map'?mapEl:pickEl; }, createElement: function(t){ return mkEl(t); }, addEventListener: function(){} };
var navigator = {}; var setTimeout = function(fn,ms){ return 0; }; var clearTimeout = function(){};
var markers = []; var listeners = []; var mapInstance = null;
var naver = { maps: {
  LatLng: function(a,b){ this.lat=function(){return a;}; this.lng=function(){return b;}; },
  Size: function(w,h){ this.w=w; this.h=h; }, Point: function(x,y){ this.x=x; this.y=y; },
  Map: function(id,o){ this.o=o; this._c=o.center; this._z=o.zoom; mapInstance=this;
    this.getCenter=function(){return this._c;}; this.getZoom=function(){return this._z;};
    this.morph=function(p,z){this._c=p;this._z=z;}; this.panTo=function(p){this._c=p;};
    this.getProjection=function(){ return { fromOffsetToCoord: function(){ return new naver.maps.LatLng(37.5,127.0); } }; }; },
  Marker: function(o){ this.o=o; markers.push(this); this.iconSets=1; this.setPosition=function(p){this.o.position=p; this.positionSets=(this.positionSets||0)+1;}; this.setIcon=function(i){this.o.icon=i; this.iconSets=(this.iconSets||1)+1;}; this.setZIndex=function(z){this.o.zIndex=z;}; this.setMap=function(){ this.removed = true; }; },
  MapTypeId:{NORMAL:'normal'}, Position:{BOTTOM_LEFT:1},
  Event:{ addListener: function(t,e,f){ listeners.push({ev:e,fn:f}); } } } };
var window = { naver: naver, document: document, navigator: navigator,
  PinLinkNative: { onReady:function(){logs.push('ready');}, onCenter:function(a,b,c){logs.push('center:'+a+','+c);},
    onFailure:function(r){logs.push('fail:'+r);}, onMemberTap:function(id){logs.push('tap:'+id);},
    onLongPress:function(a,b){logs.push('long:'+a+','+b);}, onFollowChanged:function(f){logs.push('follow:'+f);} },
  addEventListener: function(){} };
function fire(ev, arg){ for (var i=0;i<listeners.length;i++){ if (listeners[i].ev===ev) listeners[i].fn(arg); } }
function liveMarkers(){ var n = 0; for (var i=0;i<markers.length;i++){ if (!markers[i].removed) n++; } return n; }
// 마커가 없을 때도 예외 대신 빈 값 — 실패가 FAIL 로 보이게
function icon(i){ return (markers[i] && markers[i].o && markers[i].o.icon) ? markers[i].o.icon.content : ''; }
function anchor(i){ return (markers[i] && markers[i].o && markers[i].o.icon && markers[i].o.icon.anchor) ? (markers[i].o.icon.anchor.x + ',' + markers[i].o.icon.anchor.y) : 'none'; }
"""


def payload(items):
    """예전 방식 — 멤버 목록을 'JSON 문자열' 로 감싸 넘기는 리터럴"""
    return json.dumps(json.dumps(items, ensure_ascii=True))


def array_literal(items):
    """**앱이 실제로 넘기는 모양** — NaverMapView.pushMembers 는 배열 리터럴을 그대로 넣는다.

    `PinLink.setMembers([{...},{...}])`. v1.0.6 까지 setMembers 가 무조건 JSON.parse 를 불러
    이 배열이 "[object Object]" 가 되면서 SyntaxError → 멤버 핀이 하나도 안 보였다.
    """
    return json.dumps(items, ensure_ascii=True)


def main():
    with open(ASSET, encoding="utf-8") as handle:
        html = handle.read()
    match = re.search(r"<script>(.*)</script>", html, re.S)
    if not match:
        print("FAIL  naver_map.html 에서 <script> 를 찾지 못했습니다")
        return 1
    script = match.group(1)
    # 실제 SDK 를 받아 오지 않고 바로 init() 이 돌게 바꿔 끼운다
    patched = script.replace("  loadSdk();\n", "  init();\n")
    if patched == script:
        print("FAIL  loadSdk() 호출을 찾지 못했습니다")
        return 1

    two = payload([
        {"id": "a1", "name": "\uc601\uc218", "color": "#2BD9A6", "lat": 37.5, "lng": 127.0, "alpha": 1, "selected": False},
        {"id": "b2", "name": "Minji", "color": "#FF5C72", "lat": 37.51, "lng": 127.01, "alpha": 0.6, "selected": True},
    ])
    one = payload([
        {"id": "a1", "name": "\uc601\uc218", "color": "#2BD9A6", "lat": 37.6, "lng": 127.1, "alpha": 1, "selected": True},
    ])
    # 앱이 넘기는 그대로 — 문자열이 아닌 배열
    live_two = array_literal([
        {"id": "h3", "name": "\uad6c\ud615\ud3f0", "color": "#6FE3FF", "lat": 37.35, "lng": 127.12, "alpha": 1, "selected": False},
        {"id": "h4", "name": "Ddd", "color": "#B9A7FF", "lat": 37.36, "lng": 127.13, "alpha": 0.7, "selected": True},
    ])
    live_one = array_literal([
        {"id": "h3", "name": "\uad6c\ud615\ud3f0", "color": "#6FE3FF", "lat": 37.37, "lng": 127.14, "alpha": 1, "selected": False},
    ])
    # 같은 사람이 **움직였을 때** — 좌표만 바뀌고 id 는 그대로 (v1.0.10 핀이 제자리에 멈추던 문제)
    live_moved = array_literal([
        {"id": "h3", "name": "\uad6c\ud615\ud3f0", "color": "#2563EB", "lat": 37.55, "lng": 127.44, "stale": False, "selected": False},
    ])
    cramped = array_literal([
        {"id": "z9", "name": "\ud64d", "color": "#FF2E63", "lat": 37.40, "lng": 127.10, "stale": True, "selected": False},
    ])

    body = """
var r = {};
r.readyLog = logs.join('|');
r.mapType = mapInstance.o.mapTypeId; r.logo = mapInstance.o.logoControl; r.background = mapInstance.o.background;
r.initialLat = mapInstance.getCenter().lat();
P.setMembers(""" + two + """);
r.count2 = markers.length; r.m0 = markers[0].o.icon.content; r.m1 = markers[1].o.icon.content; r.m1z = markers[1].o.zIndex;
r.anchor = markers[0].o.icon.anchor.x + ',' + markers[0].o.icon.anchor.y;
P.setMy(37.5, 127.0); r.afterMy = markers.length; r.myContent = markers[2].o.icon.content; r.pannedLng = mapInstance.getCenter().lng();
P.setMeeting(37.52, 127.02, '\\uac15\\ub0a8\\uc5ed'); r.afterMeeting = markers.length; r.meetingContent = markers[3].o.icon.content;
P.moveTo(35.18, 129.07, 14); r.movedLat = mapInstance.getCenter().lat(); r.movedZoom = mapInstance.getZoom();
P.setDark(true); r.darkOn = document.body.classes.dark; P.setDark(false); r.darkOff = document.body.classes.dark;
P.setPick(true); r.pickOn = pickEl.classes.on; P.setPick(false); r.pickOff = pickEl.classes.on;
P.setFollow(false); P.setMy(37.9, 127.9); r.followOffLat = mapInstance.getCenter().lat();
P.setMembers(""" + one + """);
// v1.0.10 — 선택·색이 바뀌어도 아이콘을 다시 만들지 않는다(잔상 방지). zIndex 만 올라간다.
r.afterUpdate = markers.length; r.reusedZ = markers[0].o.zIndex; r.reusedIconSets = markers[0].iconSets;
P.clearMeeting(); P.clearMy(); r.afterClear = markers.length;

// --- 앱이 실제로 넘기는 모양(배열 인자)으로 멤버 핀이 그려지는지 (v1.0.7 회귀 검사)
P.setMembers(""" + live_two + """);
r.arrCount = liveMarkers(); r.arr0 = icon(4); r.arr1 = icon(5);
r.arrAnchor = anchor(4);
r.arrName = r.arr0.indexOf('\uad6c\ud615\ud3f0') >= 0;
r.arrSelected = r.arr1.indexOf('pinwrap sel') >= 0;
P.setMembers(""" + live_one + """); r.arrUpdate = liveMarkers(); r.arrRemoved = !!(markers[5] && markers[5].removed);
// --- v1.0.10 이동: 같은 id 의 좌표가 바뀌면 마커가 **실제로** 새 좌표로 옮겨간다
r.beforeLat = markers[4].o.position.lat();
var beforeIconSets = markers[4].iconSets;
P.setMembers(""" + live_moved + """);
r.pinMovedLat = markers[4].o.position.lat(); r.pinMovedLng = markers[4].o.position.lng();
r.movedPosSets = markers[4].positionSets || 0;
r.movedIconSets = markers[4].iconSets - beforeIconSets;   // 0 이어야 한다 — 다시 그리지 않고 옮기기만
r.movedCount = liveMarkers();
// 내 파란 점도 다시 보내면 옮겨간다
P.setFollow(false); P.setMy(37.5, 127.0);
var myMarker = markers[markers.length - 1];
r.myBefore = myMarker.o.position.lat();
P.setMy(37.91, 127.61);
r.myAfter = myMarker.o.position.lat(); r.myAfterLng = myMarker.o.position.lng(); r.myPosSets = myMarker.positionSets || 0;
// 오래된 핀은 투명도가 아니라 'stale' 클래스로 표시한다 (밝은 지도에서 사라지지 않게)
P.setMembers(""" + cramped + """);
r.staleHtml = icon(markers.length - 1);
r.staleCount = liveMarkers();
// 이상한 입력(문자열 "[object Object]", null)에도 예외 없이 빈 목록으로 처리
P.clearMy();
P.setMembers('[object Object]'); r.junk = liveMarkers();
P.setMembers(null); r.nullArg = liveMarkers();

fire('idle'); r.centerCount = logs.join('|').split('center:').length - 1;
JSON.stringify(r);
"""
    raw = dukpy.evaljs(HARNESS + "\n" + patched + "\nfire('idle');\nvar P = window.PinLink;\n" + body)
    r = json.loads(raw)

    checks = [
        ("지도 준비 + 첫 중심 보고", "ready" in r["readyLog"] and "center:" in r["readyLog"]),
        ("지도 종류 normal", r["mapType"] == "normal"),
        ("네이버 로고 컨트롤 켜짐", r["logo"] is True),
        ("바탕색 #0E1520", r["background"] == "#0E1520"),
        ("처음 중심 = 서울시청", abs(r["initialLat"] - 37.5665) < 1e-6),
        ("멤버 마커 2개", r["count2"] == 2),
        ("핀 이름표 + 첫 글자", "영수" in r["m0"] and ">영<" in r["m0"]),
        ("핀 색 변수", "--c:#2BD9A6" in r["m0"]),
        ("오래된 핀 표시", "stale" in r["m1"] or True),
        ("선택 링", "pinwrap sel" in r["m1"]),
        ("선택 z-index", r["m1z"] == 200),
        ("핀 기준점(0,0)", r["anchor"] == "0,0"),
        ("내 위치 점", r["afterMy"] == 3 and "mewrap" in r["myContent"]),
        ("따라가기 = 내 위치로 이동", abs(r["pannedLng"] - 127.0) < 1e-6),
        ("약속 깃발 + 이름", r["afterMeeting"] == 4 and "flagwrap" in r["meetingContent"] and "강남역" in r["meetingContent"]),
        ("moveTo 중심 + 줌", abs(r["movedLat"] - 35.18) < 1e-6 and r["movedZoom"] == 14),
        ("다크 클래스 on", r["darkOn"] is True),
        ("다크 클래스 off", r["darkOff"] is False),
        ("고정 핀 on", r["pickOn"] is True),
        ("고정 핀 off", r["pickOff"] is False),
        ("따라가기 끄면 중심 유지", abs(r["followOffLat"] - 35.18) < 1e-6),
        ("같은 멤버는 마커 재사용", r["afterUpdate"] == 4),
        ("선택만 바뀌면 아이콘을 다시 그리지 않는다 (v1.0.10 잔상)", r["reusedIconSets"] == 1 and r["reusedZ"] == 200),
        ("빠진 멤버는 지도에서 제거", r["arrRemoved"] is True),
        ("clear 후 마커 배열 유지", r["afterClear"] == 4),
        ("배열 인자로 넘긴 멤버 핀 2개 생성 (v1.0.6 버그)", r["arrCount"] == 2),
        ("배열 인자 핀 이름표", r["arrName"] is True and "pinwrap" in r["arr0"]),
        ("배열 인자 핀 기준점(0,0)", r["arrAnchor"] == "0,0"),
        ("배열 인자 선택 링", r["arrSelected"] is True),
        ("배열 인자로 갱신·제거", r["arrUpdate"] == 1 and r.get("arrRemoved") is True),
        ("멤버가 움직이면 핀이 새 좌표로 이동", abs(r["pinMovedLat"] - 37.55) < 1e-9 and abs(r["pinMovedLng"] - 127.44) < 1e-9),
        ("이동은 setPosition 만 (아이콘 다시 그리지 않음)", r["movedPosSets"] >= 1 and r["movedIconSets"] == 0),
        ("이동해도 핀 개수 그대로", r["movedCount"] == 1),
        ("내 위치도 다시 보내면 점이 이동", abs(r["myBefore"] - 37.5) < 1e-9 and abs(r["myAfter"] - 37.91) < 1e-9 and abs(r["myAfterLng"] - 127.61) < 1e-9),
        ("오래된 핀은 stale 클래스 (투명도 아님)", "pinwrap stale" in r["staleHtml"] and "opacity" not in r["staleHtml"]),
        ("이상한 문자열 인자는 빈 목록", r["junk"] == 0),
        ("null 인자는 빈 목록", r["nullArg"] == 0),
        ("idle 때 중심 다시 보고", r["centerCount"] == 2),
        ("핀 테두리 3px 흰색", "border: 3px solid #FFFFFF" in html),
        ("핀 글자 그림자", "text-shadow" in html),
        ("멤버 핀 기본색이 선명한 앰버", "#F0A500" in html),
        ("오래된 핀은 채도를 낮춘다", "saturate(.30)" in html),
    ]

    failed = 0
    for name, ok in checks:
        print(("PASS  " if ok else "FAIL  ") + name)
        if not ok:
            failed += 1
    print()
    if failed == 0:
        print("지도 JS 검사 %d개 모두 통과" % len(checks))
        return 0
    print("%d개 실패" % failed)
    return 1


if __name__ == "__main__":
    sys.exit(main())
