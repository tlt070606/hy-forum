#!/usr/bin/env bash
# =============================================================================
#  Hy论坛 · ECS 环境安装（低配档位：1.6 GiB 单机）
#
#  依据：docs/ops/deployment.md + AGENTS.md 铁律 6（**不在服务器上构建**）
#    · 本脚本只**安装运行环境**并调优，不构建任何东西 —— jar 由本机构建后上传。
#    · 调优值是按 1608 MB 内存算的：三件套（MySQL+Redis+JVM）必须塞得进 1.6G，
#      所以把 MySQL 缓冲池压到 64M、Redis 限到 64M、JVM 留给应用自己配。
#    · 加 2 GB swap：低内存机防 OOM 的兜底（OOM 静默杀进程是今天吃过一次的坑）。
#
#  ⚠️ 不动别人的站点：本脚本**不碰** /etc/nginx/sites-enabled/personal。
# =============================================================================
set -euo pipefail

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ── 1. swap（2 GB）─────────────────────────────────────────────────────────
if swapon --show | grep -q '/swapfile'; then
  log "swap 已存在，跳过"
else
  log "创建 2 GB swap ..."
  fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  log "swap 完成：$(swapon --show | tail -1)"
fi

# ── 2. MySQL 8 + Redis ────────────────────────────────────────────────────
export DEBIAN_FRONTEND=noninteractive
if ! command -v mysqld >/dev/null 2>&1; then
  log "apt update（首次较慢）..."
  apt-get update -qq
  log "安装 mysql-server redis-server ..."
  apt-get install -y -qq mysql-server redis-server >/tmp/apt.log 2>&1 || { tail -20 /tmp/apt.log; exit 1; }
else
  log "MySQL 已安装，跳过"
fi
if ! command -v redis-server >/dev/null 2>&1; then apt-get install -y -qq redis-server >/dev/null 2>&1; fi

# ── 3. JDK 21（JRE 即可；带三级降级链）──────────────────────────────────────
if [ -x /opt/jdk-21/bin/java ]; then
  log "JDK 21 已就位，跳过"
else
  mkdir -p /opt/jdk-21
  ok=0
  log "尝试 apt 安装 openjdk-21-jre-headless ..."
  if apt-get install -y -qq openjdk-21-jre-headless >/dev/null 2>&1; then
    jh=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
    [ -n "$jh" ] && ln -sfn "$jh" /opt/jdk-21 && ok=1 && log "apt 安装成功：$jh"
  fi
  if [ "$ok" = "0" ]; then
    log "apt 没有 21，改用清华镜像的 Temurin JRE 21 tarball ..."
    url='https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jre/x64/linux/'
    f=$(curl -sL --max-time 25 "$url" | grep -oE 'OpenJDK21U-jre_x64_linux_hotspot_[0-9._]+\.tar\.gz' | sort -V | tail -1)
    if [ -n "$f" ]; then
      curl -sL --max-time 300 -o /tmp/jre21.tar.gz "$url$f" && log "下载 $f 完成"
    fi
    [ -s /tmp/jre21.tar.gz ] || curl -sL --max-time 300 -o /tmp/jre21.tar.gz \
      'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse'
    rm -rf /tmp/jre21x && mkdir -p /tmp/jre21x
    tar xzf /tmp/jre21.tar.gz -C /tmp/jre21x --strip-components=1
    cp -a /tmp/jre21x/. /opt/jdk-21/
    [ -x /opt/jdk-21/bin/java ] && ok=1 && log "tarball 安装成功"
  fi
  [ "$ok" = "1" ] || { log "✗ JDK 21 安装失败"; exit 1; }
fi
/opt/jdk-21/bin/java -version 2>&1 | head -1 | sed 's/^/  java: /'

# ── 4. MySQL 调优（低配关键）+ 建库 ────────────────────────────────────────
cat >/etc/mysql/mysql.conf.d/zz-hyforum.cnf <<'EOF'
# Hy论坛低配调优：1.6 GiB 单机要塞下 MySQL+Redis+JVM
[mysqld]
bind-address              = 127.0.0.1
innodb_buffer_pool_size   = 64M
innodb_log_file_size      = 32M
innodb_flush_log_at_trx_commit = 2
max_connections           = 50
performance_schema        = OFF
table_open_cache          = 200
tmp_table_size            = 16M
max_heap_table_size       = 16M
character-set-server      = utf8mb4
collation-server          = utf8mb4_general_ci
EOF
log "MySQL 调优配置已写入 zz-hyforum.cnf"

# ── 5. Redis 调优 ────────────────────────────────────────────────────────
if [ -f /etc/redis/redis.conf ]; then
  sed -i 's/^# *maxmemory .*/maxmemory 64mb/' /etc/redis/redis.conf
  grep -q '^maxmemory-policy' /etc/redis/redis.conf || echo 'maxmemory-policy allkeys-lru' >> /etc/redis/redis.conf
  grep -q '^bind 127.0.0.1' /etc/redis/redis.conf || sed -i 's/^bind .*/bind 127.0.0.1 -::1/' /etc/redis/redis.conf
  log "Redis 调优完成（maxmemory 64mb）"
fi

# ── 6. 起服务 ────────────────────────────────────────────────────────────
systemctl enable --now mysql >/dev/null 2>&1 || systemctl restart mysql
systemctl enable --now redis-server >/dev/null 2>&1 || systemctl restart redis-server
sleep 6
log "mysql  = $(systemctl is-active mysql)"
log "redis  = $(systemctl is-active redis-server)"
redis-cli ping 2>/dev/null | sed 's/^/  redis-cli: /'

# ── 7. 建库与账号（口令由本脚本生成并打印，L1 收进应用配置）─────────────────
DBPASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 22)
mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS hy_forum DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'hyforum'@'127.0.0.1' IDENTIFIED BY '${DBPASS}';
ALTER USER 'hyforum'@'127.0.0.1' IDENTIFIED BY '${DBPASS}';
GRANT ALL PRIVILEGES ON hy_forum.* TO 'hyforum'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
log "数据库 hy_forum 与账号 hyforum 已建好"
echo "HYFORUM_DB_PASSWORD=${DBPASS}"
echo "HYFORUM_DB_PASSWORD_FILE=/root/.hyforum_db_pass"
echo -n "${DBPASS}" > /root/.hyforum_db_pass && chmod 600 /root/.hyforum_db_pass

echo
log "===== 内存占用 ====="
free -m
log "===== 磁盘 ====="
df -h / | tail -1
log "环境安装完成"
