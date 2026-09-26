#!/usr/bin/env bash
# =============================================================================
#  Hy论坛 · ECS 部署（本机构建 → 上传 → 装配）
#
#  前置：/root/hy-forum-server.jar、/root/h5.tar.gz、/root/hy_forum.sql、/root/hyforum.env
#  依据：铁律 6（**不在服务器上构建**）—— 本脚本只装配，不构建。
#
#  ⚠️ 不碰 /etc/nginx/sites-enabled/personal：
#     我们新增**自己的 server 块**，用 `server_name 8.138.237.212` 精确匹配 IP。
#     personal 是 80 的 default_server 且**没有 server_name**，所以：
#       · 用 IP 访问 → 精确匹配 → 落到我们这里（http://8.138.237.212/）
#       · 用域名访问 → 不匹配 → 落到默认服务器 personal（它的一切照旧）
# =============================================================================
set -euo pipefail
log() { echo "[$(date +%H:%M:%S)] $*"; }

APP=/opt/hy-forum
WEBROOT=/var/www/hy-forum
mkdir -p "$APP" "$WEBROOT" /etc/hy-forum

# ── 1. 应用与前端产物 ──────────────────────────────────────────────────────
install -m 0644 /root/hy-forum-server.jar "$APP/app.jar"
log "jar 就位：$(du -h "$APP/app.jar" | cut -f1)"

rm -rf "${WEBROOT:?}/"* 
tar xzf /root/h5.tar.gz -C "$WEBROOT"
log "H5 产物就位：$(find "$WEBROOT" -type f | wc -l) 个文件"
[ -f "$WEBROOT/index.html" ] || { log "✗ 缺 index.html"; exit 1; }

# ── 2. 环境变量（凭据走独立文件 + 600，不写进 systemd 单元）─────────────────
install -m 0600 /root/hyforum.env /etc/hy-forum/env
DBPASS=$(cat /root/.hyforum_db_pass)
{
  echo "SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/hy_forum?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
  echo "SPRING_DATASOURCE_USERNAME=hyforum"
  echo "SPRING_DATASOURCE_PASSWORD=${DBPASS}"
  echo "SPRING_DATA_REDIS_HOST=127.0.0.1"
  echo "SPRING_DATA_REDIS_PORT=6379"
  echo "SERVER_PORT=8080"
} >> /etc/hy-forum/env
chmod 600 /etc/hy-forum/env
log "环境文件已写：/etc/hy-forum/env（$(grep -c . /etc/hy-forum/env) 行，600）"

# ── 3. 导入数据库 ─────────────────────────────────────────────────────────
log "导入数据库 ..."
mysql -uroot hy_forum < /root/hy_forum.sql
mysql -uroot -N -B -e "SELECT CONCAT('  用户=', (SELECT COUNT(*) FROM hy_forum.user), ' 帖=', (SELECT COUNT(*) FROM hy_forum.post), ' 评论=', (SELECT COUNT(*) FROM hy_forum.comment));"
log "数据库导入完成"

# ── 4. systemd 服务 ───────────────────────────────────────────────────────
cat >/etc/systemd/system/hy-forum.service <<'EOF'
[Unit]
Description=Hy论坛 后端
After=network.target mysql.service redis-server.service
Wants=mysql.service redis-server.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/hy-forum
EnvironmentFile=/etc/hy-forum/env
# 低配档位：把堆压到 256M，1.6 GiB 单机要给 MySQL/Redis 留余量
ExecStart=/opt/jdk-21/bin/java -Xmx256m -Xss512k -XX:+UseSerialGC \
  -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
  -jar /opt/hy-forum/app.jar
Restart=always
RestartSec=5
StandardOutput=append:/var/log/hy-forum/app.log
StandardError=append:/var/log/hy-forum/app.err.log

[Install]
WantedBy=multi-user.target
EOF
mkdir -p /var/log/hy-forum
systemctl daemon-reload
systemctl enable hy-forum >/dev/null 2>&1 || true
log "systemd 单元已装"

# ── 5. nginx（自己的 server 块，不动 personal）────────────────────────────
if [ ! -f /etc/nginx/sites-available/hy-forum ]; then
  cat >/etc/nginx/sites-available/hy-forum <<'EOF'
# Hy论坛 —— 用 IP 精确匹配（http://8.138.237.212/）
# personal 是 default_server 且无 server_name：域名访问照旧走它，互不影响。
server {
    listen 80;
    listen [::]:80;
    server_name 8.138.237.212;

    root /var/www/hy-forum;
    index index.html;

    access_log /var/log/nginx/hy-forum.access.log;
    error_log  /var/log/nginx/hy-forum.error.log;

    client_max_body_size 20m;

    # 接口反代到后端（H5 走同源相对 /api，后端没有 CORS 头，必须同源）
    location /api/ {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    # H5 是 hash 路由（#/pages/...），静态直出即可；再给一层 SPA 回退兜底
    location / {
        try_files $uri $uri/ /index.html;
    }
}
EOF
  ln -sfn /etc/nginx/sites-available/hy-forum /etc/nginx/sites-enabled/hy-forum
  log "nginx server 块已装（未触碰 personal）"
else
  log "nginx 配置已存在，跳过"
fi
if nginx -t 2>/tmp/ngx.err; then
  systemctl reload nginx
  log "nginx 配置校验通过并已 reload"
else
  log "✗ nginx 配置有问题："; cat /tmp/ngx.err; exit 1
fi

# ── 6. 起服务 ─────────────────────────────────────────────────────────────
systemctl restart hy-forum
sleep 25
log "hy-forum = $(systemctl is-active hy-forum)"
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8080/v3/api-docs || true)
  [ "$code" = "200" ] && break
  sleep 5
done
log "后端 /v3/api-docs -> HTTP ${code:-无}"

echo
log "===== 摘要 ====="
free -m | head -2
log "  hy-forum = $(systemctl is-active hy-forum)"
log "  本机 80（经 nginx，Host=IP）-> HTTP $(curl -s -o /dev/null -w '%{http_code}' --max-time 8 -H 'Host: 8.138.237.212' http://127.0.0.1/ || echo 无)"
log "===== 部署完成 ====="
