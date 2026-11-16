import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * PIT mutations.xml 요약 도구. 외부 라이브러리 없이 JDK만 쓰므로 빌드 없이 바로 실행한다.
 *
 * <pre>
 * cd backend
 * java scripts/mutation/PitReport.java summary   [mutations.xml]                 # 클래스별 표
 * java scripts/mutation/PitReport.java survivors [mutations.xml] [--class 이름일부] # 살아남은 변이만
 * java scripts/mutation/PitReport.java todo      [mutations.xml]                 # 생존 + 미실행 (AI 입력용)
 * java scripts/mutation/PitReport.java score     [mutations.xml]                 # 한 줄 점수 (로그용)
 * java scripts/mutation/PitReport.java comment   [mutations.xml]                 # PR 댓글용 마크다운
 * </pre>
 */
public class PitReport {

    private static final String DEFAULT_XML = "build/reports/pitest/mutations.xml";
    private static final String PACKAGE_PREFIX = "withoutc.chongchong.";
    private static final Set<String> DETECTED = Set.of("KILLED", "TIMED_OUT", "MEMORY_ERROR");
    private static final int COMMENT_SURVIVOR_LIMIT = 200;

    record Mutation(String status, String mutatedClass, String method, int line, String mutator, String description) {

        boolean detected() {
            return DETECTED.contains(status);
        }

        boolean survived() {
            return "SURVIVED".equals(status);
        }

        boolean noCoverage() {
            return "NO_COVERAGE".equals(status);
        }

        String shortClass() {
            return mutatedClass.startsWith(PACKAGE_PREFIX) ? mutatedClass.substring(PACKAGE_PREFIX.length()) : mutatedClass;
        }

        String oneLine() {
            return "%s:%d %s() [%s] %s".formatted(shortClass(), line, method, mutator, description);
        }
    }

    record Stats(int total, int detected, int survived, int noCoverage) {

        static Stats of(List<Mutation> mutations) {
            return new Stats(mutations.size(),
                    (int) mutations.stream().filter(Mutation::detected).count(),
                    (int) mutations.stream().filter(Mutation::survived).count(),
                    (int) mutations.stream().filter(Mutation::noCoverage).count());
        }

        String score() {
            return percent(detected, total);
        }

        String strength() {
            return percent(detected, total - noCoverage);
        }

        private static String percent(int part, int whole) {
            return whole == 0 ? "-" : Math.round(part * 100.0 / whole) + "%";
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        String mode = rest.isEmpty() ? "summary" : rest.removeFirst();
        String classFilter = takeOption(rest, "--class");
        List<Mutation> mutations = load(Path.of(rest.isEmpty() ? DEFAULT_XML : rest.getFirst()));

        switch (mode) {
            case "summary" -> System.out.print(summaryTable(mutations));
            case "survivors" -> survivors(mutations, classFilter);
            case "todo" -> todo(mutations);
            case "score" -> score(mutations);
            case "comment" -> System.out.print(comment(mutations));
            default -> {
                System.err.println("알 수 없는 모드: " + mode + " (summary | survivors | todo | score | comment)");
                System.exit(2);
            }
        }
    }

    private static String takeOption(List<String> args, String name) {
        int index = args.indexOf(name);
        if (index < 0 || index + 1 >= args.size()) {
            return null;
        }
        String value = args.get(index + 1);
        args.subList(index, index + 2).clear();
        return value;
    }

    static List<Mutation> load(Path xml) throws Exception {
        if (!Files.exists(xml)) {
            throw new IOException("PIT 결과가 없습니다: " + xml + " (먼저 ./gradlew pitest 실행)");
        }
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xml.toFile()).getElementsByTagName("mutation");
        List<Mutation> mutations = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element m = (Element) nodes.item(i);
            String mutator = text(m, "mutator");
            mutations.add(new Mutation(
                    m.getAttribute("status"),
                    text(m, "mutatedClass"),
                    text(m, "mutatedMethod"),
                    Integer.parseInt(text(m, "lineNumber")),
                    mutator.substring(mutator.lastIndexOf('.') + 1).replace("Mutator", ""),
                    text(m, "description")));
        }
        return mutations;
    }

    private static String text(Element parent, String tag) {
        return parent.getElementsByTagName(tag).item(0).getTextContent();
    }

    private static List<Mutation> sorted(List<Mutation> mutations, Predicate<Mutation> filter) {
        return mutations.stream().filter(filter)
                .sorted(Comparator.comparing(Mutation::mutatedClass).thenComparingInt(Mutation::line))
                .toList();
    }

    static String summaryTable(List<Mutation> mutations) {
        Map<String, List<Mutation>> byClass = mutations.stream()
                .collect(Collectors.groupingBy(Mutation::shortClass, TreeMap::new, Collectors.toList()));
        StringBuilder out = new StringBuilder()
                .append("| 클래스 | 변이 | 잡음 | 생존 | 미실행 | 변이 점수 | 테스트 강도 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        byClass.forEach((name, list) -> out.append(row(name, Stats.of(list))));
        out.append(row("**합계**", Stats.of(mutations)));
        return out.toString();
    }

    private static String row(String name, Stats s) {
        return "| %s | %d | %d | %d | %d | %s | %s |\n"
                .formatted(name, s.total(), s.detected(), s.survived(), s.noCoverage(), s.score(), s.strength());
    }

    private static void survivors(List<Mutation> mutations, String classFilter) {
        List<Mutation> survived = sorted(mutations,
                m -> m.survived() && (classFilter == null || m.mutatedClass().contains(classFilter)));
        survived.forEach(m -> System.out.println(m.oneLine()));
        System.out.println("# SURVIVED " + survived.size());
    }

    private static void todo(List<Mutation> mutations) {
        sorted(mutations, m -> m.survived() || m.noCoverage())
                .forEach(m -> System.out.println("[" + m.status() + "] " + m.oneLine()));
    }

    private static void score(List<Mutation> mutations) {
        Stats s = Stats.of(mutations);
        System.out.printf("%s(%d/%d)\t%d\t%d%n", s.score(), s.detected(), s.total(), s.survived(), s.noCoverage());
    }

    static String comment(List<Mutation> mutations) {
        Stats total = Stats.of(mutations);
        List<Mutation> survived = sorted(mutations, Mutation::survived);
        Map<String, List<Mutation>> weakClasses = survived.stream()
                .collect(Collectors.groupingBy(Mutation::shortClass, TreeMap::new, Collectors.toList()));

        StringBuilder out = new StringBuilder()
                .append("## 🧬 변이 테스트 결과\n\n")
                .append("코드를 일부러 망가뜨렸을 때 단위 테스트가 알아챈 비율입니다. ")
                .append("**살아남은 변이**는 테스트가 못 잡은 버그라 줄일수록 좋습니다.\n\n")
                .append("| 변이 점수 | 테스트 강도 | 잡음 | 살아남음 | 실행 안 됨 | 전체 |\n")
                .append("|---|---|---|---|---|---|\n")
                .append("| **%s** | %s | %d | %d | %d | %d |\n\n".formatted(total.score(), total.strength(),
                        total.detected(), total.survived(), total.noCoverage(), total.total()));

        if (survived.isEmpty()) {
            return out.append("살아남은 변이가 없습니다. ✅\n").toString();
        }

        out.append("<details><summary>살아남은 변이가 있는 클래스 (").append(weakClasses.size()).append("개)</summary>\n\n")
                .append("| 클래스 | 변이 | 생존 | 미실행 | 변이 점수 | 테스트 강도 |\n|---|---|---|---|---|---|\n");
        weakClasses.keySet().forEach(name -> {
            Stats s = Stats.of(mutations.stream().filter(m -> m.shortClass().equals(name)).toList());
            out.append("| %s | %d | %d | %d | %s | %s |\n"
                    .formatted(name, s.total(), s.survived(), s.noCoverage(), s.score(), s.strength()));
        });
        out.append("\n</details>\n\n");

        out.append("<details><summary>살아남은 변이 목록 (").append(survived.size()).append("개)</summary>\n\n```\n");
        survived.stream().limit(COMMENT_SURVIVOR_LIMIT).forEach(m -> out.append(m.oneLine()).append('\n'));
        if (survived.size() > COMMENT_SURVIVOR_LIMIT) {
            out.append("... 외 ").append(survived.size() - COMMENT_SURVIVOR_LIMIT).append("개 (아티팩트의 HTML 리포트 참고)\n");
        }
        return out.append("```\n\n</details>\n").toString();
    }
}
