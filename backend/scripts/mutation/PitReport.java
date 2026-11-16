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
    private static final int COMMENT_LIST_LIMIT = 100;

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

        /** withoutc.chongchong 바로 아래 패키지 (comment, auth, ...) */
        String domain() {
            String rest = shortClass();
            int dot = rest.indexOf('.');
            return dot < 0 ? rest : rest.substring(0, dot);
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
        List<Mutation> noCoverage = sorted(mutations, Mutation::noCoverage);

        StringBuilder out = new StringBuilder()
                .append("## 🧬 변이 테스트 결과\n\n")
                .append("코드를 일부러 망가뜨렸을 때 단위 테스트가 알아챈 비율입니다. ")
                .append("**살아남음**은 테스트가 실행했지만 못 잡은 것, **실행 안 됨**은 그 코드를 실행하는 단위 테스트가 없는 것입니다.\n\n")
                .append("| 변이 점수 | 테스트 강도 | 잡음 | 살아남음 | 실행 안 됨 | 전체 |\n")
                .append("|---|---|---|---|---|---|\n")
                .append("| **%s** | %s | %d | %d | %d | %d |\n\n".formatted(total.score(), total.strength(),
                        total.detected(), total.survived(), total.noCoverage(), total.total()));

        out.append("### 도메인별\n\n").append(groupTable(mutations, Mutation::domain, "도메인")).append('\n');
        out.append("<details><summary>클래스별</summary>\n\n")
                .append(groupTable(mutations, Mutation::shortClass, "클래스"))
                .append("\n</details>\n\n");

        appendList(out, "살아남은 변이", survived);
        appendList(out, "실행 안 된 변이", noCoverage);
        if (survived.isEmpty()) {
            out.append("살아남은 변이가 없습니다. ✅\n");
        }
        return out.toString();
    }

    private static String groupTable(List<Mutation> mutations, java.util.function.Function<Mutation, String> key,
                                     String title) {
        Map<String, List<Mutation>> groups = mutations.stream()
                .collect(Collectors.groupingBy(key, TreeMap::new, Collectors.toList()));
        StringBuilder out = new StringBuilder()
                .append("| ").append(title).append(" | 변이 점수 | 테스트 강도 | 잡음 | 살아남음 | 실행 안 됨 | 전체 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        groups.forEach((name, list) -> {
            Stats s = Stats.of(list);
            out.append("| %s | %s | %s | %d | %d | %d | %d |\n".formatted(name, s.score(), s.strength(),
                    s.detected(), s.survived(), s.noCoverage(), s.total()));
        });
        return out.toString();
    }

    private static void appendList(StringBuilder out, String title, List<Mutation> list) {
        if (list.isEmpty()) {
            return;
        }
        out.append("<details><summary>").append(title).append(" (").append(list.size()).append("개)</summary>\n\n```\n");
        list.stream().limit(COMMENT_LIST_LIMIT).forEach(m -> out.append(m.oneLine()).append('\n'));
        if (list.size() > COMMENT_LIST_LIMIT) {
            out.append("... 외 ").append(list.size() - COMMENT_LIST_LIMIT).append("개 (아티팩트의 HTML 리포트 참고)\n");
        }
        out.append("```\n\n</details>\n\n");
    }
}
