package com.hyforum.toolcheck;

/**
 * 最小工具链验证类。
 * 故意使用 Java 21 的语法特性（record + 文本块），
 * 以便在 JDK 低于 21 时**直接编译失败** —— 这是一种"反向验证"：
 * 若编译通过，就证明确实用的是 JDK 21，而不是悄悄退回了 17。
 */
public class Hello {

    /** Java 16+ 的 record：JDK 17 也能编译，因此不能单独用它做版本判定。 */
    public record Env(String javaVersion, String javaVendor) {
    }

    public static void main(String[] args) {
        // 文本块（Java 15+）
        String banner = """
                ================================
                Hy论坛 工具链自检
                ================================
                """;
        System.out.print(banner);
        System.out.println("java.version      = " + System.getProperty("java.version"));
        System.out.println("java.vendor       = " + System.getProperty("java.vendor"));
        System.out.println("java.home         = " + System.getProperty("java.home"));
        System.out.println("os.name           = " + System.getProperty("os.name"));

        // 运行时版本判定：必须是 21.x
        String v = System.getProperty("java.version");
        if (!v.startsWith("21.")) {
            System.err.println("FAIL: 运行时不是 21.x，实际为 " + v);
            System.exit(1);
        }
        System.out.println("RESULT: PASS（JDK 21 工具链可用）");
    }
}
