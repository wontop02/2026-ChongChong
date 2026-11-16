#!/usr/bin/env bash
# 기준 커밋 이후 바뀐 클래스를 PIT targetClasses 형식(쉼표 구분)으로 출력한다. 없으면 빈 줄.
#
#   사용: scripts/mutation/changed-targets.sh <기준>     예) origin/dev, HEAD^1
#   로컬: ./gradlew pitest -PpitTargets="$(scripts/mutation/changed-targets.sh origin/dev)"
#
# - src/main에서 바뀐 클래스
# - src/test에서 바뀐 FooTest.java·FooUnitTest.java 의 짝인 Foo (테스트만 보강한 PR도 점수가 나오게)
# - 컨트롤러·설정·리포지토리·부트스트랩은 build.gradle의 excludedClasses와 같이 뺀다.
set -euo pipefail

base="${1:?기준 커밋을 주세요 (예: origin/dev)}"
root="$(git rev-parse --show-toplevel)"
from="$(git -C "$root" merge-base "$base" HEAD)"
main="backend/src/main/java"
test="backend/src/test/java"

{
    git -C "$root" diff --name-only --diff-filter=AMR "$from" -- "$main" | grep '\.java$' || true
    git -C "$root" diff --name-only --diff-filter=AMR "$from" -- "$test" | grep 'Test\.java$' \
        | sed -E -e "s#^$test/(.*)UnitTest\.java\$#$main/\1.java#" -e "s#^$test/(.*)Test\.java\$#$main/\1.java#" || true
} | sort -u | while read -r file; do
    [ -f "$root/$file" ] || continue
    cls="${file#"$main"/}"
    cls="${cls%.java}"
    cls="${cls//\//.}"
    case "$cls" in
        *.controller.* | *.config.* | *.repository.* | withoutc.chongchong.ChongChongApplication) continue ;;
    esac
    printf '%s\n%s$*\n' "$cls" "$cls"   # 내부 클래스(Foo$Bar)까지 포함
done | paste -sd, -
