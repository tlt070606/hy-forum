#!/usr/bin/env bash
# 每日备份（数据是你唯一带不走就没了的东西）+ 回调可达性自检
set -uo pipefail
log() { echo "[$(date +%H:%M:%S)] $*"; }

mkdir -p /var/backups/hy-forum
cat >/usr/local/bin/hy-forum-backup.sh <<'EOF'
#!/usr/bin/env bash
# Hy论坛每日备份：mysqldump 到 /var/backups/hy-forum，保留最近 7 天
set -uo pipefail
D=/var/backups/hy-forum
mkdir -p "$D"
mysql -uroot hy_forum | gzip > "$D/hy_forum-$(date +%F).sql.gz"
# 保留最近 7 个
ls -1t "$D"/hy_forum-*.sql.gz 2>/dev/null | tail -n +8 | xargs -r rm -f
EOF
chmod +x /usr/local/bin/hy-forum-backup.sh

# 装 cron（每天 03:30）
cat >/etc/cron.d/hy-forum-backup <<'EOF'
30 3 * * * root /usr/local/bin/hy-forum-backup.sh >/dev/null 2>&1
EOF
chmod 644 /etc/cron.d/hy-forum-backup
log "备份 cron 已装（每天 03:30，保留 7 天）"

# 立刻跑一次，证明它真的能产出文件（不是"配了但没验证"）
/usr/local/bin/hy-forum-backup.sh
ls -lh /var/backups/hy-forum/ | tail -2 | sed 's/^/  /'
log "手动跑一次：$(ls /var/backups/hy-forum/ | wc -l) 个备份文件"
echo "  （这一份也给了你'把数据带回本地'的路：scp myserver:/var/backups/hy-forum/*.sql.gz .）"

echo
log "===== OSS 回调从公网可达性自检 ====="
# 用空 body POST：应返回业务错误（400/403），说明请求**到达了后端**（而不是 404/502）
code=$(curl -s -o /tmp/cb.txt -w '%{http_code}' --max-time 15 -X POST http://8.138.237.212/api/oss/callback -H 'Content-Type: application/json' -d '{}' || echo 000)
log "  POST /api/oss/callback -> HTTP $code"
log "  响应：$(head -c 200 /tmp/cb.txt)"
log "  （4xx = 已到达后端并按鉴权拒绝 ✓；404 = nginx 没转发 ✗；502 = 后端没起 ✗）"

echo
log "===== 最终状态 ====="
for s in hy-forum mysql redis-server nginx; do log "  $s = $(systemctl is-active $s)"; done
free -m | head -2
log "===== 完成 ====="
