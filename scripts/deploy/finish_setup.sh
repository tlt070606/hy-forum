#!/usr/bin/env bash
# 收尾：修 JDK 符号链接 → MySQL 调优重启 → 建库建账号 → 打印摘要
set -uo pipefail
log() { echo "[$(date +%H:%M:%S)] $*"; }

# 1) JDK：把 /opt/jdk-21 指向真正的 JDK 21（上一步脚本把符号链接放进了目录里）
pkill -f install_stack.sh 2>/dev/null || true
rm -rf /opt/jdk-21
ln -sfn /usr/lib/jvm/java-21-openjdk-amd64 /opt/jdk-21
log "java: $(/opt/jdk-21/bin/java -version 2>&1 | head -1)"

# 2) MySQL 调优（1.6 GB 单机三件套必须压住）
cat >/etc/mysql/mysql.conf.d/zz-hyforum.cnf <<'EOF'
[mysqld]
bind-address              = 127.0.0.1
innodb_buffer_pool_size   = 64M
innodb_log_file_size      = 32M
max_connections           = 50
performance_schema        = OFF
table_open_cache          = 200
tmp_table_size            = 16M
max_heap_table_size       = 16M
character-set-server      = utf8mb4
collation-server          = utf8mb4_general_ci
EOF
log "MySQL 调优配置已写入"
systemctl restart mysql
sleep 8
log "mysql = $(systemctl is-active mysql)"
mysql -uroot -N -B -e "SELECT CONCAT('  buffer_pool=', @@innodb_buffer_pool_size/1024/1024, 'MB  performance_schema=', @@performance_schema);" 2>&1 | sed 's/^/  /'

# 3) 建库 + 账号（口令随机生成，落盘留档）
if [ -f /root/.hyforum_db_pass ]; then
  P=$(cat /root/.hyforum_db_pass)
  log "复用已有口令文件"
else
  P=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 22)
  echo -n "$P" > /root/.hyforum_db_pass
  chmod 600 /root/.hyforum_db_pass
  log "已生成新口令"
fi
mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS hy_forum DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'hyforum'@'127.0.0.1' IDENTIFIED BY '${P}';
ALTER USER 'hyforum'@'127.0.0.1' IDENTIFIED BY '${P}';
GRANT ALL PRIVILEGES ON hy_forum.* TO 'hyforum'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
log "库与账号就绪："
mysql -uroot -N -B -e "SHOW DATABASES;" | grep -x hy_forum | sed 's/^/  db: /'
mysql -uroot -N -B -e "SELECT CONCAT('  user: ', user, '@', host) FROM mysql.user WHERE user='hyforum';"

# 4) 摘要
echo
log "===== 内存（含 swap）====="
free -m
log "===== 服务状态 ====="
for s in mysql redis-server nginx; do log "  $s = $(systemctl is-active $s)"; done
log "===== 收尾完成 ====="
