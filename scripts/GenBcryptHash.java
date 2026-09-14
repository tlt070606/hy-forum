import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 生成 seed.sql 用的 BCrypt 哈希（strength=10，与后端 AuthService 一致）。
 *
 * 为什么单独放一个生成器：seed.sql 里的管理员密码是**哈希**，明文不入库也不进仓库。
 * 任何环境都可以用它现场生成自己的哈希，因此不依赖"谁手里有那个密码"。
 *
 * 用法（用后端工程自己的依赖，保证版本与运行时一致）：
 *   cd server
 *   mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 *   java -cp (Get-Content target/cp.txt -Raw) ..\scripts\GenBcryptHash.java '你的明文口令'
 *
 * 不带参数时会生成一个随机口令并打印出来（只在终端显示，不会被写进任何文件）。
 */
public class GenBcryptHash {

    private static final int STRENGTH = 10;

    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(STRENGTH);

        String plain;
        boolean generated = false;
        if (args.length >= 1 && !args[0].isBlank()) {
            plain = args[0];
        } else {
            plain = randomPassword(16);
            generated = true;
        }

        String hash = encoder.encode(plain);

        // 自校验：生成的哈希必须能匹配回明文，否则就是环境有问题（例如取到了别的实现）
        boolean ok = encoder.matches(plain, hash);
        if (!ok) {
            System.err.println("FAIL: 生成的哈希无法匹配明文，不要使用");
            System.exit(1);
        }

        if (generated) {
            System.out.println("生成的随机口令（仅本次显示，请立即抄走）：");
            System.out.println("  " + plain);
            System.out.println();
        }
        System.out.println("BCrypt 哈希（strength=" + STRENGTH + "，粘进 seed.sql）：");
        System.out.println("  " + hash);
        System.out.println();
        System.out.println("自校验: " + (ok ? "PASS（哈希与明文匹配）" : "FAIL"));
    }

    /** 用 SecureRandom 生成口令，字符集避开容易混淆的 0/O/1/l/I。 */
    private static String randomPassword(int len) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%^*";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
