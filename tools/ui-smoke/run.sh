#!/usr/bin/env bash
# 화면·리소스 스모크 테스트 — 기기 없이 Robolectric 으로 실제 안드로이드 파서를 돌린다.
#
# 앱 프로젝트 자체에는 테스트 의존성을 넣지 않는다(오프라인 빌드를 가볍게 유지).
# 이 스크립트가 작업 사본을 만들어 Robolectric 을 붙이고 테스트를 돌린다.
#
#   · UiSmokeTest       : 앱 시작 → 언어 카드 → 탭 이동 → 방 만들기 → 공유 상태 복원 → 크래시 안내
#   · ResourceSmokeTest : 모든 drawable/mipmap 을 실제(NATIVE) 파서로 inflate, 런처 아이콘 렌더링
#
# v1.0.0 의 시작 크래시(벡터 pathData 호 명령 플래그)는 legacy 그래픽 모드에서 그냥 넘어갔다.
# 반드시 NATIVE 그래픽 모드로 돌려야 기기와 같은 결과가 나온다.
#
# 사용: bash tools/ui-smoke/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
PROJECT_DIR="$(pwd)"
WORK_DIR="${WORK_DIR:-/workspace/work/pinlink-uitest}"
JAR_DIR="${JAR_DIR:-/workspace/work/robolectric-jars}"
TMP_DIR="${TMP_DIR:-/workspace/work/tmp}"

JAVA_HOME=${JAVA_HOME:-/workspace/buildenv/jdk-17.0.21.1+2}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE_USER_HOME=${GRADLE_USER_HOME:-/workspace/buildenv/gradle-home}
export GRADLE_USER_HOME

# 힙 크기는 환경에 맞게 줄일 수 있다 — 기본값은 v1.0.8 까지와 같다(2048m).
# (이 작업 환경은 메모리가 수 GB 뿐이라 GRADLE_HEAP=768m TEST_HEAP=1024m 로 돌릴 때가 있다)
TEST_HEAP=${TEST_HEAP:-2048m}
GRADLE_HEAP=${GRADLE_HEAP:-2048m}

mkdir -p "$JAR_DIR" "$TMP_DIR"
# 1) Robolectric 이 쓰는 android-all jar (없으면 받아 둔다)
for artifact in \
  "android-all-instrumented/14-robolectric-10818077-i7" \
  "android-all-instrumented/15-robolectric-12650502-i7"; do
  name=$(basename "$artifact")
  # Robolectric 은 파일 이름이 android-all-instrumented-… 인 jar 를 찾는다
  file="$JAR_DIR/android-all-instrumented-$name.jar"
  if [ ! -f "$file" ]; then
    echo "· 내려받는 중: $name.jar"
    # 파일 이름은 그룹 이름이 붙은 형태(android-all-instrumented-…) — 경로의 폴더 이름과 다르다
    curl -sL -o "$file" "https://repo1.maven.org/maven2/org/robolectric/$artifact/android-all-instrumented-$name.jar"
  fi
done

# 2) 작업 사본 만들기
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
for item in gradle build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat local.properties; do
  cp -r "$PROJECT_DIR/$item" "$WORK_DIR/"
done
# app 은 build 산출물(수백 MB)을 빼고 소스만 복사한다 — 복사가 느려지고 메모리(페이지 캐시)를 잡아먹지 않게
mkdir -p "$WORK_DIR/app"
(cd "$PROJECT_DIR/app" && tar -cf - --exclude=./build .) | (cd "$WORK_DIR/app" && tar -xf -)
rm -rf "$WORK_DIR/app/build"
# 데몬 힙도 줄일 수 있게 — gradle.properties 의 -Xmx 값을 바꿔 넣는다
sed -i "s/-Xmx2048m/-Xmx${GRADLE_HEAP}/" "$WORK_DIR/gradle.properties"
mkdir -p "$WORK_DIR/app/src/test/java/com/youngsu/pinlink"
cp "$PROJECT_DIR"/tools/ui-smoke/*.kt "$WORK_DIR/app/src/test/java/com/youngsu/pinlink/"

python3 - "$WORK_DIR" "$JAR_DIR" "$TMP_DIR" "$TEST_HEAP" <<'PY'
import sys
work, jar_dir, tmp_dir, test_heap = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
path = f"{work}/app/build.gradle.kts"
s = open(path).read()
s = s.replace("""    lint {""", """    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("java.io.tmpdir", "%s")
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", "%s")
            it.maxHeapSize = "%s"
            // 이 작업 환경은 프로세스(스레드) 수 한도가 빠듯해 JVM 내부 스레드를 줄여 둔다
            it.jvmArgs("-XX:ActiveProcessorCount=1", "-XX:CICompilerCount=2", "-XX:+UseSerialGC")
        }
    }

    lint {""" % (tmp_dir, jar_dir, test_heap), 1)
s = s.replace("""    implementation(libs.guava)""", """    implementation(libs.guava)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")""", 1)
open(path, "w").write(s)
print("· 테스트 의존성 주입 완료")
PY

# 3) 실행
cd "$WORK_DIR"
./gradlew testDebugUnitTest --offline --console=plain ${GRADLE_ARGS:-}
echo
echo "✅ 화면·리소스 스모크 테스트 통과 (리포트: $WORK_DIR/app/build/reports/tests/testDebugUnitTest/index.html)"
