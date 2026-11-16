#!/usr/bin/env bash
# PIT 변이 테스트 + AI 테스트 보강 자동 루프 (로컬 + CI의 be-mutation-ai.yml)
#
# 사용법 (저장소 어디서든): backend/scripts/mutation/ai-loop.sh [최대회차=5]
# 한 회차에 남은 변이가 많으면 앞에서부터 MAX_ITEMS(기본 30)개만 넘긴다.
#
# 환경 변수 (모두 선택)
#   PIT_TARGETS   검사할 클래스 (changed-targets.sh 출력). 없으면 전체
#   MUTATION_LOG  회차 기록 파일 (기본 scripts/mutation/mutation-log.txt, CI는 /dev/null)
#   RUN_DIR       회차별 결과 폴더 (기본 build/mutation-runs/<시각>)
#   REUSE_FIRST_MEASURE  1이면 첫 회차 측정을 건너뛰고 이미 있는 mutations.xml을 쓴다 (CI에서 보강 전 측정과 중복 방지)
#   AI_AGENT      claude(기본) | codex
#   CODEX_MODEL   codex 모델 (없으면 ~/.codex/config.toml 설정)
#   CODEX_EFFORT  codex 추론 강도 (low | medium | high | xhigh | max, 없으면 설정값)
#   CLAUDE_BIN    claude 실행 파일 경로
#   CODEX_BIN     codex 실행 파일 경로
#   CODEX_SANDBOX codex 샌드박스 (기본 workspace-write, CI는 러너 자체가 일회용이라 danger-full-access)
#
# 한 회차 = pitest 측정 → 남은 변이(생존+미실행)를 AI(claude 또는 codex)에게 전달 → 테스트 추가 → 안전 검사
# 멈추는 경우: 남은 변이 0 / 직전 회차보다 줄지 않음 / 운영 코드가 바뀜 / AI가 쓴 테스트 실패 / 최대 회차
set -euo pipefail

MAX_ROUNDS="${1:-5}"
REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
BACKEND="$REPO_ROOT/backend"
DIR="$BACKEND/scripts/mutation"
LOG="${MUTATION_LOG:-$DIR/mutation-log.txt}"
REPORT="$DIR/PitReport.java"
XML="$BACKEND/build/reports/pitest/mutations.xml"
AI_AGENT="${AI_AGENT:-claude}"
CLAUDE_BIN="${CLAUDE_BIN:-$(command -v claude || echo "$HOME/.local/bin/claude")}"
CODEX_BIN="${CODEX_BIN:-codex}"
RUN_DIR="${RUN_DIR:-$BACKEND/build/mutation-runs/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$RUN_DIR"

# 운영 코드와 PIT 설정의 지문. AI 실행 전후로 비교해서 하나라도 바뀌면 즉시 중단한다.
fingerprint() {
  (cd "$REPO_ROOT" && find backend/src/main -type f -print0 | sort -z | xargs -0 shasum
   shasum backend/build.gradle) | shasum | cut -d' ' -f1
}

# AI에게 프롬프트를 주고 마지막 답변을 $2에, 전체 로그를 $3에 남긴다.
run_agent() {
  local prompt="$1" answer="$2" log="$3"
  case "$AI_AGENT" in
    claude)
      (cd "$REPO_ROOT" && "$CLAUDE_BIN" -p "$prompt" \
          --model sonnet \
          --max-turns 20 \
          --allowedTools "Read" "Glob" "Grep" \
            "Edit(/backend/src/test/**)" "Write(/backend/src/test/**)" \
            "Edit(/backend/scripts/mutation/equivalent.md)" \
            "Bash(cd backend)" "Bash(./gradlew test *)" \
          >"$answer" 2>"$log")
      ;;
    codex)
      # 도구 제한이 claude만큼 세밀하지 않으므로, 운영 코드 지문 검사와 CI의 변경 파일 검사가 안전장치다.
      local sandbox_args=(--sandbox "${CODEX_SANDBOX:-workspace-write}")
      if [[ "${CODEX_SANDBOX:-}" == "danger-full-access" ]]; then
        sandbox_args=(--dangerously-bypass-approvals-and-sandbox)
      fi
      local model_args=()
      [[ -n "${CODEX_MODEL:-}" ]] && model_args+=(-m "$CODEX_MODEL")
      [[ -n "${CODEX_EFFORT:-}" ]] && model_args+=(-c "model_reasoning_effort=\"$CODEX_EFFORT\"")
      "$CODEX_BIN" exec "${sandbox_args[@]}" "${model_args[@]}" \
          -c sandbox_workspace_write.network_access=true \
          --add-dir "$HOME/.gradle" \
          --ephemeral \
          -C "$REPO_ROOT" \
          -o "$answer" \
          "$prompt" </dev/null >"$log" 2>&1
      ;;
    *)
      echo "알 수 없는 AI_AGENT: $AI_AGENT (claude | codex)" >&2
      return 2
      ;;
  esac
}

measure() {
  local args=(pitest -q)
  [[ -n "${PIT_TARGETS:-}" ]] && args+=("-PpitTargets=$PIT_TARGETS")
  (cd "$BACKEND" && ./gradlew "${args[@]}" >"$RUN_DIR/pitest-$1.log" 2>&1)
}

last_round=$(awk -F'\t' '$1 ~ /^[0-9]+$/ {n=$1} END {print n+0}' "$LOG" 2>/dev/null || echo 0)
prev_left=""

for ((i = 1; i <= MAX_ROUNDS; i++)); do
  round=$((last_round + i))
  echo "=== 회차 $round: 측정 ==="
  if [[ "$i" -eq 1 && "${REUSE_FIRST_MEASURE:-}" == "1" && -f "$XML" ]]; then
    echo "(이미 있는 측정 결과 사용)"
  else
    measure "$round-before"
  fi
  java "$REPORT" todo "$XML" >"$RUN_DIR/todo-all-$round.txt"
  head -n "${MAX_ITEMS:-30}" "$RUN_DIR/todo-all-$round.txt" >"$RUN_DIR/todo-$round.txt"
  left=$(wc -l <"$RUN_DIR/todo-all-$round.txt" | tr -d ' ')
  echo "남은 변이: $left개"

  if [[ "$left" -eq 0 ]]; then
    echo "남은 변이 없음. 종료."
    break
  fi
  if [[ -n "$prev_left" && "$left" -ge "$prev_left" ]]; then
    echo "직전 회차($prev_left개)보다 줄지 않음. 사람이 볼 차례라 종료."
    break
  fi
  prev_left="$left"

  before=$(fingerprint)
  prompt=$(cat <<EOF
너는 Spring Boot 백엔드(backend/, 패키지 withoutc.chongchong)의 단위 테스트를 보강한다.
PIT 변이 테스트에서 아래 변이가 잡히지 않았다. [SURVIVED]는 테스트가 실행했는데도 못 잡은 것, [NO_COVERAGE]는 테스트가 아예 실행하지 않은 것이다.

$(cat "$RUN_DIR/todo-$round.txt")

규칙
1. backend/src/main 과 backend/build.gradle 은 절대 수정하지 않는다. 테스트만 추가·수정한다.
2. 테스트는 대상 클래스와 같은 패키지(backend/src/test/java 아래)에 둔다. 스프링 컨텍스트나 DB를 띄우지 않는 순수 단위 테스트(Mockito 가능)만 쓴다. @SpringBootTest, @Tag("integration"), PostgresContainerTest 상속은 쓰지 않는다 (PIT가 integration 태그 테스트는 제외하므로 변이를 잡지 못한다).
3. 기존 테스트 스타일(@DisplayName 한국어, AssertJ)을 따른다.
4. "메서드가 호출됐는지"보다 바깥에서 보이는 행동(반환값, 예외와 에러 코드, 저장·조회 여부)을 확인한다. 구현 코드를 그대로 베끼는 테스트는 쓰지 않는다.
5. 코드를 바꿔도 동작이 같아 어떤 테스트로도 잡을 수 없는 동등 변이라고 판단되면 테스트를 쓰지 말고 backend/scripts/mutation/equivalent.md 표에 한 줄 추가한다. "사람 판정" 칸은 비워 둔다.
6. 테스트를 다 쓴 뒤 backend 에서 ./gradlew test -PexcludeIntegration --tests '<추가한 테스트 클래스>' 로 실행한다. 실패하면 테스트를 운영 코드에 맞추지 말고, 왜 실패했는지와 실제 버그일 가능성을 마지막 답변에 적는다.
7. 마지막 답변은 한국어로: 추가한 테스트 목록, 각 테스트가 노린 변이, 동등 변이로 판단한 것, 실패한 테스트.
EOF
)
  echo "=== 회차 $round: $AI_AGENT 실행 ==="
  run_agent "$prompt" "$RUN_DIR/ai-$round.md" "$RUN_DIR/agent-$round.log" \
    || echo "($AI_AGENT 종료 코드 $? — 내용은 $RUN_DIR/agent-$round.log 확인)"

  if [[ "$(fingerprint)" != "$before" ]]; then
    printf '%s\t자동\t-\t-\t-\t중단: 운영 코드 또는 build.gradle이 바뀜\n' "$round" >>"$LOG"
    echo "!!! 운영 코드 또는 build.gradle이 바뀌었습니다. git diff로 확인하세요. 중단."
    exit 1
  fi

  echo "=== 회차 $round: 단위 테스트 확인 ==="
  # PIT와 같은 기준: integration 태그를 뺀 단위 테스트 전체
  if ! (cd "$BACKEND" && ./gradlew test -q -PexcludeIntegration >"$RUN_DIR/test-$round.log" 2>&1); then
    printf '%s\t자동\t-\t-\t-\t중단: 테스트 실패 (버그 후보 확인 필요)\n' "$round" >>"$LOG"
    echo "!!! 테스트가 실패했습니다. 실제 버그 후보일 수 있습니다: $RUN_DIR/test-$round.log, $RUN_DIR/ai-$round.md"
    exit 1
  fi

  measure "$round-after"
  printf '%s\t자동(%s)\t%s\t%s\n' "$round" "$AI_AGENT" "$(java "$REPORT" score "$XML")" "결과: $RUN_DIR/ai-$round.md" >>"$LOG"
  tail -1 "$LOG"
done

echo "=== 최종 ==="
java "$REPORT" summary "$XML"
