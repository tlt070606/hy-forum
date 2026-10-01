#!/usr/bin/env bash
# 每日备份（数据是你唯一带不走就没了的东西）+ 回调可达性自检
set -uo pipefail
log() { echo "[$(date +%H:%M:%S)] $*"; }

mkdir -p /var/backups/hy-forum
cat >/usr/local/bin/hy-forum-backup.sh <<'EOF'
#!/usr/bin/env bash
# Hy论坛每日备份：mysqldump 到 /var/backups/hy-forum，保留最近 7 天
#
# ⚠️ 2026-10-01 修复：上一版写的是 `mysql -uroot hy_forum | gzip` —— `mysql` 是
#    **交互式客户端**不是导出工具，它从 stdin 读命令、stdout 几乎什么都不出，
#    于是"备份成功"产出的 gzip 只有约 20 字节（空流）。上线记录 §5 判定过这个问题，
#    但脚本本身一直没修 —— 兜底工具坏了没人知道，直到需要它的那天。
#    正确工具是 `mysqldump`，并且导出后必须**校验产物**（大小 + 可解压），
#    把"备份是好的"从假设变成每跑一次就验证一次的事实。
set -uo pipefail
D=/var/backups/hy-forum
mkdir -p "$D"
OUT="$D/hy_forum-$(date +%F).sql.gz"

# --single-transaction：InnoDB 一致性快照，备份期间不锁写（论坛白天也可能跑）
# --quick：逐行取，避免把整库缓到内存（2G 小机禁不起）
# --routines --triggers：留全，将来加存储过程/触发器不至于静默丢
if ! mysqldump -uroot --single-transaction --quick --routines --triggers hy_forum | gzip > "$OUT"; then
  echo "[backup][ERROR] mysqldump 失败，删除损坏产物：$OUT" >&2
  rm -f "$OUT"
  exit 1
fi

# 产物校验 ①：小于 1KB 视为空备份（空库也不止这个数；上次事故就是 20 字节）
if [ "$(stat -c%s "$OUT")" -lt 1024 ]; then
  echo "[backup][ERROR] 备份文件小于 1KB，疑似空备份：$OUT" >&2
  exit 1
fi
# 产物校验 ②：gzip 必须能完整解压且末尾有数据（防"写了一半的文件"）
if ! gzip -t "$OUT" 2>/dev/null || [ -z "$(gzip -dc "$OUT" | tail -c 200)" ]; then
  echo "[backup][ERROR] 备份文件解压校验失败：$OUT" >&2
  exit 1
fi

echo "[backup] OK $(date +%F) size=$(stat -c%s "$OUT") -> $OUT"
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
