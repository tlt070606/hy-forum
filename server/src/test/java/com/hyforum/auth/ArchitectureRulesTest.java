package com.hyforum.auth;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构约束 —— 铁律 3（禁止跨模块调用）与铁律 7（禁止重组件）。
 *
 * <p>对应验收项：{@code ARCH_no_cross_module_dependency}、{@code ARCH_no_heavy_components}。
 * 口径来源：docs/技术方案.md §3.3 的 <b>v1.8 补充</b>（七个业务包互不依赖，
 * 只允许依赖 {@code common} 与 {@code domain}）与 AGENTS.md 铁律 3 / 7。</p>
 *
 * <h2>为什么铁律 3 需要机器校验而不是靠人记</h2>
 * <p>单模块工程里"模块"只体现为包名，编译期没有任何东西阻止
 * {@code post} 直接 {@code import com.hyforum.interaction.service.XxxService}。
 * 一旦发生，后续想把 post 抽成独立 Maven 模块（v1.8 明确说过"包名不变、代码不动"）
 * 就会立刻失败，而且这种依赖往往是"顺手引用一下"造成的，代码评审很容易漏。</p>
 *
 * <h2>ARCH_no_heavy_components 为什么要真的去解析依赖树</h2>
 * <p>铁律 7 禁的是 <b>依赖</b>（Elasticsearch / RabbitMQ / Kafka / 注册中心客户端）。
 * 光扫 classpath 上的类是不够的：某个 starter 完全可能只把客户端 jar 拉进来而
 * 应用启动时不加载它（例如 {@code spring-boot-starter-data-elasticsearch} 在
 * 自动配置被排除时）。因此本用例解析 <b>Maven 解析后的依赖树</b> ——
 * 那才是"这个工程依赖了什么"的权威答案。</p>
 */
@DisplayName("架构约束 · 铁律 3 / 铁律 7")
class ArchitectureRulesTest {

    /** 七个业务包（技术方案 §3.3 v1.8 的定值，一个不多一个不少）。 */
    private static final List<String> BUSINESS_MODULES = List.of(
            "com.hyforum.auth",
            "com.hyforum.post",
            "com.hyforum.media",
            "com.hyforum.interaction",
            "com.hyforum.audit",
            "com.hyforum.notify",
            "com.hyforum.admin");

    /**
     * 每个业务包在本次架构规则里的"图层名"。
     *
     * <p>包级 {@code PackageMarker} 单独成层并声明为"可被所有业务包依赖"的叶子：
     * 它是为了让空包真实存在于编译产物里（否则 ArchUnit 无从校验）而存在的纯声明类，
     * 不含任何逻辑。若不显式豁免它，"引用包名常量"这种无害行为会被判成跨模块依赖 ——
     * 那样的规则会因为噪音太大而被人为忽略，反而失去作用。</p>
     */
    private static String layerName(int index) {
        return "MODULE_" + index;
    }

    private static String markerLayerName(int index) {
        return "MARKER_" + index;
    }

    /** 铁律 7 的禁用依赖：groupId / artifactId 特征（小写子串匹配）。 */
    private static final List<String> FORBIDDEN_DEPENDENCY_KEYWORDS = List.of(
            "elasticsearch",
            "opensearch",
            "rabbitmq",
            "amqp",
            "kafka",
            "rocketmq",
            "pulsar",
            "activemq",
            "nacos",
            "eureka",
            "consul",
            "zookeeper",
            "dubbo");

    @Test
    void ARCH_no_cross_module_dependency() {
        JavaClasses classes = importMainClasses();

        // 前置自检：七个业务包必须都真实存在。
        // 若某个包没有任何类，规则会"通过"但什么都没检查 —— 那是假绿，必须显式失败。
        for (String module : BUSINESS_MODULES) {
            assertThat(hasClassesInPackage(classes, module))
                    .as("业务包 %s 在编译产物里不存在，ArchUnit 规则会变成空转（假绿）。"
                            + "请确认该包内至少有包级声明类。", module)
                    .isTrue();
        }

        Architectures.LayeredArchitecture architecture = Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true);

        for (int i = 0; i < BUSINESS_MODULES.size(); i++) {
            String module = BUSINESS_MODULES.get(i);
            // 本模块自身：只允许访问 自己 / common / domain / 各模块的包级声明
            architecture = architecture
                    .layer(layerName(i)).definedBy(module + "..")
                    .whereLayer(layerName(i)).mayOnlyBeAccessedByLayers(accessibleByLayers(i));
            // 包级声明类：叶子层，任何人都可以引用它的常量
            architecture = architecture
                    .layer(markerLayerName(i)).definedBy(module + ".PackageMarker")
                    .whereLayer(markerLayerName(i)).mayOnlyBeAccessedByLayers(accessibleByLayers(i));
        }

        // LayeredArchitecture 本身就是一条 ArchRule，直接 check
        architecture.as("七个业务包之间禁止相互依赖（只允许依赖 common 与 domain）").check(classes);
    }

    /**
     * 判断某个包下是否真的有类。
     *
     * <p>用包名精确匹配（{@code pkg.equals(module) || pkg.startsWith(module + ".")}），
     * 而不是 {@code getPackageName().startsWith(module)} —— 后者会把
     * {@code com.hyforum.authx} 误判成 {@code com.hyforum.auth}，规则就悄悄漏掉一个模块。</p>
     */
    private static boolean hasClassesInPackage(JavaClasses classes, String module) {
        return classes.stream().anyMatch(javaClass -> {
            String pkg = javaClass.getPackageName();
            return pkg.equals(module) || pkg.startsWith(module + ".");
        });
    }

    /**
     * 某个模块的图层"允许被谁访问"。
     *
     * <p>注意 ArchUnit 的 {@code mayOnlyBeAccessedByLayers} 表达的是
     * "谁可以访问我"，因此这里回答的问题是：
     * 「模块 i 的代码除了被自己访问，还能被谁访问？」—— 答案是<b>只有它自己</b>。</p>
     */
    private static String[] accessibleByLayers(int index) {
        return new String[]{layerName(index)};
    }

    /**
     * <b>头像 URL 必须经过 {@code AvatarUrlResolver}</b>（CR-Q，2026-09-20 L1 裁决）。
     *
     * <h2>这条规则为什么必须存在（它是"第四次"的唯一防线）</h2>
     * <p>"头像"这件事有三个消费者，而它们被<b>逐个发现、每次漏一个</b>：</p>
     * <ol>
     *   <li>签名下发（{@code target=avatar}）—— §14 做了；</li>
     *   <li>写入校验（{@code PUT /api/user/profile} 的归属校验）—— §14 做了；</li>
     *   <li><b>读取时签名</b> —— §14 <b>漏了</b> → 桶私有 → 头像裸 URL 必然 403
     *       → <b>帖子图能看、头像不能</b>（CR-Q 报的现象）。</li>
     * </ol>
     * <p>而头像出现在<b>帖子作者、评论作者、通知发送者、关注/粉丝列表、侧栏最新发帖的人</b>……
     * 只要有一个装配点漏调解析器，就是第四次。L1 明确不接受"在几个 VO 里各补一行"——
     * 所以要有一条<b>机器守着</b>的规则。</p>
     *
     * <h2>规则内容</h2>
     * <p>{@code domain} 与 {@code common.oss} 之外的类，<b>不得调用
     * {@code User.getAvatarUrl()}</b>。取值只有两条合法路径：</p>
     * <ul>
     *   <li>调 {@code AvatarUrlResolver.resolve(...)}（推荐，也是唯一"拿到签名 URL"的方式）；或</li>
     *   <li>把已由它解析好的 URL 往下传。</li>
     * </ul>
     * <p>新增装配点若直接读 {@code user.getAvatarUrl()}，<b>代码能编译、接口也能跑</b>，
     * 但这条规则会红 —— 「编译能过但架构门会红」正是我们要的形态。</p>
     *
     * <p>豁免的两个包都有明确理由：{@code domain} 是实体自身（getter 的定义处），
     * {@code common.oss} 是解析器的家（它<b>就是要</b>读这个字段）。</p>
     */
    @Test
    void ARCH_avatar_url_must_go_through_resolver() {
        JavaClasses classes = importMainClasses();

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.hyforum.domain..")
                .and().resideOutsideOfPackage("com.hyforum.common.oss..")
                .should().callMethod(com.hyforum.domain.user.entity.User.class, "getAvatarUrl")
                .because("头像必须经 AvatarUrlResolver.resolve() 装配才能带上读时签名；"
                        + "直接读裸字段会让前端拿到一个必然 403 的 URL（CR-Q：帖子图能看、头像不能）。"
                        + "若新增了头像装配点，请调用 AvatarUrlResolver 而不是 getAvatarUrl()");

        rule.check(classes);
    }

    @Test
    void ARCH_no_heavy_components() {
        List<DependencyCoordinates> dependencies = MavenDependencyTreeReader.read();

        assertThat(dependencies)
                .as("没能解析到依赖树，本规则会变成空转（假绿）—— 必须显式失败而不是跳过")
                .isNotEmpty();

        List<String> violations = dependencies.stream()
                .filter(dep -> FORBIDDEN_DEPENDENCY_KEYWORDS.stream().anyMatch(dep::matchesKeyword))
                .map(DependencyCoordinates::asString)
                .toList();

        assertThat(violations)
                .as("铁律 7：禁止引入 Elasticsearch / MQ / 注册中心客户端等重组件（部署目标为 2 GiB 单机）。"
                        + "命中的依赖：%s", violations)
                .isEmpty();

        // 反向自检：依赖树必须真的被读到了内容（避免解析出空列表却"通过"）
        assertThat(dependencies.size())
                .as("依赖树条数异常少（%d），解析可能有问题", dependencies.size())
                .isGreaterThan(20);
    }

    /**
     * 导入主源码编译产物。
     *
     * <p>{@code excludePaths} 排除测试产物：本规则约束的是交付给生产的代码，
     * 测试基类（例如共享 support 包）之间的依赖不在铁律 3 的范围内。</p>
     */
    private static JavaClasses importMainClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("com.hyforum");
    }

    /**
     * 一条依赖坐标（groupId:artifactId:version）。
     *
     * <p>同时校验 groupId 与 artifactId，是因为重组件可能以任一形式出现：
     * 例如 AMQP 客户端的 groupId 是 {@code com.rabbitmq}、artifactId 是 {@code amqp-client}；
     * 而 Spring Boot 的 starter 则把组件名放在 artifactId（{@code spring-boot-starter-data-elasticsearch}）。</p>
     */
    private record DependencyCoordinates(String groupId, String artifactId, String version,
                                         String scope) {

        boolean matchesKeyword(String keyword) {
            String lowerGroup = groupId == null ? "" : groupId.toLowerCase(java.util.Locale.ROOT);
            String lowerArtifact = artifactId == null ? "" : artifactId.toLowerCase(java.util.Locale.ROOT);
            return lowerGroup.contains(keyword) || lowerArtifact.contains(keyword);
        }

        String asString() {
            return groupId + ":" + artifactId + ":" + version + " (" + scope + ")";
        }
    }

    /**
     * 读取 Maven 解析后的依赖树。
     *
     * <p>取数方式（按可靠性排序，取第一个可用的）：</p>
     * <ol>
     *   <li>{@code maven-dependency-plugin} 在 {@code process-test-classes} 阶段产出的
     *       {@code target/dependency-tree.txt}（插件配置在 pom 里，见 {@code report-dependency-tree}）；</li>
     *   <li>就地调用 {@code mvn dependency:tree} 重新解析（较慢但总能拿到）。</li>
     * </ol>
     *
     * <p>刻意<b>不</b>扫本地仓库目录：本地仓库里有别人（其他项目）下载的组件，
     * 那不能证明"本工程依赖了它"，会给出假阳性。</p>
     */
    private static final class MavenDependencyTreeReader {

        private static final String TREE_FILE = "target/dependency-tree.txt";

        private MavenDependencyTreeReader() {
        }

        static List<DependencyCoordinates> read() {
            java.io.File file = new java.io.File(TREE_FILE);
            if (file.isFile()) {
                List<DependencyCoordinates> parsed = parse(readFile(file));
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
            return readByRunningMaven();
        }

        private static String readFile(java.io.File file) {
            try {
                return java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读取 " + TREE_FILE + " 失败", ex);
            }
        }

        /**
         * 直接用 Maven 生成依赖树。
         *
         * <p>命令行与 pom 里插件用的是同一组参数（{@code outputType=text}），
         * 因此两种取数方式的解析代码完全一致。</p>
         */
        private static List<DependencyCoordinates> readByRunningMaven() {
            String mvn = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "mvn.cmd" : "mvn";
            java.io.File tempOutput = new java.io.File("target/dependency-tree-archunit.txt");
            tempOutput.getParentFile().mkdirs();
            try {
                ProcessBuilder builder = new ProcessBuilder(mvn, "-B", "-q",
                        "dependency:tree",
                        "-DoutputType=text",
                        "-DoutputFile=" + tempOutput.getAbsolutePath());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new IllegalStateException("mvn dependency:tree 退出码=" + exit + "，输出：" + output);
                }
                return parse(readFile(tempOutput));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("调用 mvn dependency:tree 失败", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("调用 mvn dependency:tree 被中断", ex);
            }
        }

        /**
         * 解析 text 形式的依赖树。
         *
         * <p>行样例：</p>
         * <pre>
         * [INFO] +- org.springframework.boot:spring-boot-starter-web:jar:3.3.5:compile
         * [INFO] |  \- org.springframework:spring-webmvc:jar:6.1.14:compile
         * [INFO] \- cn.dev33:sa-token-core:jar:1.38.0:compile
         * </pre>
         * <p>因此按 {@code groupId:artifactId:packaging:version:scope} 五段解析；
         * 版本里可能带 {@code (optional)} 等后缀，需要切掉。</p>
         */
        static List<DependencyCoordinates> parse(String rawText) {
            List<DependencyCoordinates> result = new java.util.ArrayList<>();
            for (String line : rawText.split("\\R")) {
                // 去掉树形前缀与 [INFO] 前缀
                String trimmed = line.replaceFirst("^\\[INFO\\]\\s*", "").trim();
                int colon = trimmed.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                // 只处理形如 "groupId:artifactId:packaging:version:scope" 的行
                String[] parts = trimmed.split(":");
                if (parts.length < 5) {
                    continue;
                }
                String groupId = parts[0];
                String artifactId = parts[1];
                // parts[2] 是 packaging（jar/pom），parts[3] 是版本，parts[4] 是 scope
                String version = parts[3];
                String scope = parts[4].split("\\s")[0];
                if (groupId.isBlank() || artifactId.isBlank()) {
                    continue;
                }
                result.add(new DependencyCoordinates(groupId, artifactId, version, scope));
            }
            return result;
        }
    }
}
