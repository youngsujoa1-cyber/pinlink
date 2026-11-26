#!/usr/bin/env python3
"""firestore.rules → 콘솔에 붙여 넣을 한 줄 문자열.

Firebase 콘솔의 규칙 편집기는 저장할 때 줄바꿈을 합치고 '//' 주석 뒤를 지운다.
그래서 주석이 있는 원본(firestore.rules)과 콘솔에 올리는 본문(firestore.rules.oneline)을
나누고, 이 스크립트로 둘을 맞춘다.

사용:
  python3 tools/rules-oneline.py            # firestore.rules.oneline 갱신 + 한 줄 출력
  python3 tools/rules-oneline.py --check    # 파일이 원본과 맞는지만 확인(다르면 종료코드 1)
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "firestore.rules"
TARGET = ROOT / "firestore.rules.oneline"


def oneline(text: str) -> str:
    lines = []
    for raw in text.splitlines():
        line = raw.split("//", 1)[0].strip()
        if line:
            lines.append(line)
    packed = " ".join(lines)
    # 규칙 본문에 의미 있는 공백은 문자열/정규식 안에만 있다 — 그 밖은 한 칸으로 줄인다
    return re.sub(r"\s{2,}", " ", packed).strip()


def main() -> int:
    if not SOURCE.exists():
        print(f"✗ {SOURCE} 가 없습니다", file=sys.stderr)
        return 2
    packed = oneline(SOURCE.read_text(encoding="utf-8"))
    if "--check" in sys.argv:
        if not TARGET.exists() or TARGET.read_text(encoding="utf-8").strip() != packed:
            print("✗ firestore.rules.oneline 이 원본과 다릅니다 (python3 tools/rules-oneline.py 로 갱신)")
            return 1
        print("✓ firestore.rules.oneline 최신")
        return 0
    TARGET.write_text(packed + "\n", encoding="utf-8")
    print(f"✓ {TARGET.name} ({len(packed)} chars)")
    print(packed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
