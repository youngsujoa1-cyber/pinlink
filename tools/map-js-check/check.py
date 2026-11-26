#!/usr/bin/env python3
"""지도 페이지(assets/naver_map.html) 안의 JavaScript 를 실제 브라우저·기기 없이 검사한다.

네이버 SDK 와 DOM 을 가짜 객체(스텁)로 바꿔 끼우고, 앱이 부르는 것과 같은 순서로
`PinLink.setMembers` / `setMy` / `setMeeting` / `moveTo` / `setDark` / `setPick` / `setFollow`
를 호출해 마커 HTML·좌표·콜백이 기대대로 만들어지는지 본다.

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
  Marker: function(o){ this.o=o; markers.push(this); this.setPosition=function(p){this.o.position=p;}; this.setIcon=function(i){this.o.icon=i;}; this.setZIndex=function(){}; this.setMap=function(){ this.removed = true; }; },
  MapTypeId:{NORMAL:'normal'}, Position:{BOTTOM_LEFT:1},
  Event:{ addListener: function(t,e,f){ listeners.push({ev:e,fn:f}); } } } };
var window = { naver: naver, document: document, navigator: navigator,
  PinLinkNative: { onReady:function(){logs.push('ready');}, onCenter:function(a,b,c){logs.push('center:'+a+','+c);},
    onFailure:function(r){logs.push('fail:'+r);}, onMemberTap:function(id){logs.push('tap:'+id);},
    onLongPress:function(a,b){logs.push('long:'+a+','+b);}, onFollowChanged:function(f){logs.push('follow:'+f);} },
  addEventListener: function(){} };
function fire(ev, arg){ for (var i=0;i<listeners.length;i++){ if (listeners[i].ev===ev) listeners[i].fn(arg); } }
"""


def payload(items):
    """앱이 JS 로 넘기는 것과 같은 'JSON 문자열' 리터럴"""
    return json.dumps(json.dumps(items, ensure_ascii=True))


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
r.afterUpdate = markers.length; r.reused = markers[0].o.icon.content.indexOf('pinwrap sel') >= 0; r.removed = markers[1].removed === true;
P.clearMeeting(); P.clearMy(); r.afterClear = markers.length;
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
        ("오래된 핀 투명도", "opacity:0.6" in r["m1"]),
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
        ("선택만 바뀌면 제자리 갱신", r["reused"] is True),
        ("빠진 멤버는 지도에서 제거", r["removed"] is True),
        ("clear 후 마커 배열 유지", r["afterClear"] == 4),
        ("idle 때 중심 다시 보고", r["centerCount"] == 2),
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
