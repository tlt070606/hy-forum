package com.hyforum.auth;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构约束 —— 铁律 3（禁止跨模块调用）与铁律 7（禁止重组件）。
 *
 * <p>对应验收项：{@code ARCH_no_cross_module_dependency}、{@code ARCH_no_heavy_components}。
 * 口径来源：docs/技术方案.md §3.3 的 <b>v1.8 补充</b>（业务包互不依赖，
 * 只允许依赖 {@code common} 与 {@code domain}）与 AGENTS.md 铁律 3 / 7；
 * 豁免边与守卫的重写依据见 {@link #KNOWN_BUSINESS_MODULES} 与 CR-004 豁免的注释。</p>
 *
 * <h2>为什么铁律 3 需要机器校验而不是靠人记</h2>
 * <p>单模块工程里"模块"只体现为包名，编译期没有任何东西阻止
 * {@code post} 直接 {@code import com.hyforum.interaction.service.XxxService}。
 * 一旦发生，后续想把 post 抽成独立 Maven 模块（v1.8 明确说过"包名不变、代码不动"）
 * 就会立刻失败，而且这种依赖往往是"顺手引用一下"造成的，代码评审很容易漏。</p>
 *
 * <h2>2026-10-01 守卫重写（user 包盲区事故的整改）</h2>
 * <p>业务包名单曾由本测试手工维护，M4 新增 {@code user} 包时名单没更新，
 * {@code user → interaction} 的真实依赖对规则不可见（规则绿、守卫瞎）。
 * 重写后业务包从编译产物自动推导（新增包自动入守卫），
 * CR-004 的豁免边从"没被守卫看见"变成"显式登记的一条豁免"。
 * 手工清单降级为<b>下限兜底</b>：已知包消失必须显式失败。</p>
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

    /**
     * 已知业务包的<b>下限清单</b>（2026-10-01 起只作兜底，不再作白名单）。
     *
     * <p>历史教训（本清单重写的直接原因）：它曾同时充当白名单 ——"七个业务包，
     * 一个不多一个不少"（技术方案 §3.3 v1.8 的定值）。M4 新建 {@code user} 包时
     * 没有人更新它，结果 {@code user → interaction} 的真实跨模块依赖对守卫
     * <b>完全不可见</b>：规则照常绿，守卫却瞎了。更糟的是 {@code UserService} 的注释
     * 还断言"user→post 会被本测试当场判违规" —— 那个断言是错的，来自对守卫能力的
     * 错误假设。这正是本项目最忌讳的"假绿"，而且这一次的假绿是守卫自己的清单造成的。</p>
     *
     * <p>现在的业务包列表由编译产物<b>自动推导</b>（见 {@link #deriveBusinessModules}）：
     * 新增业务包自动纳入守卫，清单只回答"这些包必须仍然存在" ——
     * 某个包被改名/合并而没同步这里，测试会红并逼人显式确认。
     * 方向从此是"<b>清单过期必须显式失败</b>"，而不是"<b>清单过期悄悄漏检</b>"。</p>
     */
    private static final List<String> KNOWN_BUSINESS_MODULES = List.of(
            "com.hyforum.auth",
            "com.hyforum.post",
            "com.hyforum.media",
            "com.hyforum.interaction",
            "com.hyforum.audit",
            "com.hyforum.notify",
            "com.hyforum.admin",
            "com.hyforum.user");

    /**
     * CR-004 豁免边（2026-10-01 随守卫重写一并<b>显式化</b>）：
     * {@code user} 包允许依赖 {@code interaction} 包的 <b>service 与 vo</b> 两层。
     *
     * <p>理由（CR-004 原裁定 + 2026-10-01 复核维持）：个人主页 / 收藏列表的互动数据聚合
     * （关注关系、点赞态、Feed、收藏项）是 {@code interaction} 的写权，
     * {@code user} 只是读者；依赖方向单向、无环，拆掉它需要把 FollowService 整体搬包，
     * 在当前规模下收益不值重构成本。</p>
     *
     * <p>豁免面刻意收窄到这两个子包：{@code user} 不得碰 {@code interaction} 的
     * controller/dto，也不得依赖其它任何业务包；其余业务包（含 interaction 自己）
     * 不得依赖 {@code user}。若豁免面需要扩大，必须先改这条注释并给出新的裁定编号 ——
     * 让"放宽守卫"成为一件显式的事，而不是顺手加一个 import。</p>
     */

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

        // 业务包从编译产物自动推导；KNOWN_BUSINESS_MODULES 是下限兜底（见其注释）
        Set<String> modules = deriveBusinessModules(classes);
        assertThat(modules)
                .as("推导出的业务包必须覆盖全部已知包：缺一个说明该包被改名/删除，"
                        + "请显式更新 KNOWN_BUSINESS_MODULES（这是'清单过期必须显式失败'的防线）")
                .containsAll(KNOWN_BUSINESS_MODULES);

        List<String> violations = collectCrossModuleViolations(classes, modules);
        assertThat(violations)
                .as("铁律 3：业务包之间禁止互相依赖（只允许依赖 common 与 domain）。"
                        + "唯一的豁免边是 user → interaction 的 service/vo（CR-004，"
                        + "见类注释的豁免说明）。新增跨模块引用前请先在那里登记裁定。")
                .isEmpty();
    }

    /**
     * 从编译产物推导业务包：{@code com.hyforum} 的直接子包里，除 {@code common} 与
     * {@code domain} 外全部算业务包。新增业务包因此<b>自动</b>进入守卫范围，
     * 不再有"建了包忘了登记"的盲区（user 包事故的直接整改）。
     */
    private static Set<String> deriveBusinessModules(JavaClasses classes) {
        Set<String> modules = new java.util.TreeSet<>();
        for (JavaClass clazz : classes) {
            String pkg = clazz.getPackageName();
            if (!pkg.startsWith("com.hyforum.")) {
                continue;
            }
            String rest = pkg.substring("com.hyforum.".length());
            int dot = rest.indexOf('.');
            String top = dot < 0 ? rest : rest.substring(0, dot);
            if (!top.isBlank() && !top.equals("common") && !top.equals("domain")) {
                modules.add("com.hyforum." + top);
            }
        }
        return modules;
    }

    /** 类所在包对应的业务模块；不属于任何业务包（common/domain 等）返回 null。 */
    private static String moduleOf(String packageName, Set<String> modules) {
        return modules.stream()
                .filter(module -> packageName.equals(module) || packageName.startsWith(module + "."))
                .findFirst()
                .orElse(null);
    }

    /** 包级声明类（{@code PackageMarker}）：纯常量声明，互相引用不算跨模块依赖。 */
    private static boolean isPackageMarker(JavaClass clazz) {
        return clazz.getSimpleName().equals("PackageMarker");
    }

    /** CR-004 豁免边：{@code user → interaction} 的 service/vo（含子包）。 */
    private static boolean isBlessedByCr004(String sourceModule, JavaClass target) {
        if (!sourceModule.equals("com.hyforum.user")) {
            return false;
        }
        String pkg = target.getPackageName();
        return pkg.equals("com.hyforum.interaction.service")
                || pkg.startsWith("com.hyforum.interaction.service.")
                || pkg.equals("com.hyforum.interaction.vo")
                || pkg.startsWith("com.hyforum.interaction.vo.");
    }

    /**
     * 逐条依赖检查业务包之间的引用，返回违规清单（空 = 合规）。
     *
     * <p>刻意用 {@code getDirectDependenciesFromSelf()} 逐条枚举而不是
     * {@code LayeredArchitecture}：豁免边（CR-004）是"某条具体方向的依赖"，
     * 分层规则表达不了"这一条允许、其余全禁"，硬套会把豁免面放大到整层。
     * 逐条枚举的违规消息直接给出 源类 → 目标类，改起来不用再猜。</p>
     */
    private static List<String> collectCrossModuleViolations(JavaClasses classes, Set<String> modules) {
        List<String> violations = new java.util.ArrayList<>();
        for (JavaClass clazz : classes) {
            String sourceModule = moduleOf(clazz.getPackageName(), modules);
            if (sourceModule == null || isPackageMarker(clazz)) {
                continue;
            }
            for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String targetModule = moduleOf(target.getPackageName(), modules);
                if (targetModule == null || targetModule.equals(sourceModule) || isPackageMarker(target)) {
                    continue;
                }
                if (isBlessedByCr004(sourceModule, target)) {
                    continue;
                }
                violations.add(sourceModule + " → " + targetModule + "："
                        + clazz.getName() + " -> " + target.getName()
                        + "（" + dependency.getDescription() + "）");
            }
        }
        return violations;
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
