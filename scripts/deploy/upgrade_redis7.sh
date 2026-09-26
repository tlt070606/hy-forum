#!/usr/bin/env bash
# =============================================================================
#  Redis 6.0.16 -> 7.x（对齐项目目标版本）
#
#  为什么必须升：项目的浏览量回写用 `GETDEL`（Redis 6.2+ 才有），而 Ubuntu 20.04
#  自带 redis-server 是 6.0.16 -> 每 5 分钟的落库**每次都抛 ERR unknown command GETDEL**，
#  于是 view_count 永远不落库、列表页浏览量恒为 0。这是部署时选错版本造成的真实缺陷。
#
#  数据影响：本机 Redis 里只有浏览量增量（可丢）与登录态（升级后会掉线，用户重登即可）。
# =============================================================================
set -uo pipefail
log() { echo "[$(date +%H:%M:%S)] $*"; }

log "升级前：$(redis-server --version | head -1)"

# 记住现有配置里我们加过的两项，升级后要确认仍在
MAXMEM_BEFORE=$(grep -E '^maxmemory ' /etc/redis/redis.conf 2>/dev/null || echo '(未设置)')
log "升级前 maxmemory：$MAXMEM_BEFORE"

export DEBIAN_FRONTEND=noninteractive
if apt-get install -y -qq lsb-release curl gpg >/dev/null 2>&1; then :; fi

ok=0
# 路线 1：官方 APT 仓库
if [ "$ok" = "0" ]; then
  log "尝试官方仓库 packages.redis.io ..."
  if curl -fsSL --max-time 25 https://packages.redis.io/gpg 2>/dev/null | gpg --dearmor -o /usr/share/keyrings/redis-archive-keyring.gpg 2>/dev/null; then
    echo "deb [signed-by=/usr/share/keyrings/redis-archive-keyring.gpg] https://packages.redis.io/deb $(lsb_release -cs) main" > /etc/apt/sources.list.d/redis.list
    if apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq --only-upgrade redis-server redis-tools >/dev/null 2>&1; then
      v=$(redis-server --version | grep -oE 'v=[0-9.]+' | head -1)
      log "官方仓库安装结果：$v"
      case "$v" in v=7*|v=8*) ok=1;; esac
    fi
  fi
fi

# 路线 2：源码编译（官方仓库不通时的兜底，编译在服务器上跑但只影响 Redis 自身）
if [ "$ok" = "0" ]; then
  log "官方仓库没成功，改用源码编译 Redis 7 ..."
  apt-get install -y -qq build-essential tcl >/dev/null 2>&1
  cd /tmp && rm -rf redis-7* 
  curl -sL --max-time 300 -o /tmp/redis7.tar.gz https://download.redis.io/releases/redis-7.2.5.tar.gz || \
  curl -sL --max-time 300 -o /tmp/redis7.tar.gz https://mirrors.tuna.tsinghua.edu.cn/redis/redis-7.2.5.tar.gz
  if [ -s /tmp/redis7.tar.gz ]; then
    tar xzf /tmp/redis7.tar.gz -C /tmp
    cd /tmp/redis-7.2.5
    make -j2 >/tmp/redis_build.log 2>&1 && make install PREFIX=/usr/local >>/tmp/redis_build.log 2>&1
    if [ -x /usr/local/bin/redis-server ]; then
      # 用编译版替换发行版二进制（保留 systemd 单元与配置路径）
      systemctl stop redis-server 2>/dev/null || true
      mv /usr/bin/redis-server /usr/bin/redis-server.distrib.bak 2>/dev/null || true
      ln -sfn /usr/local/bin/redis-server /usr/bin/redis-server
      ln -sfn /usr/local/bin/redis-cli    /usr/bin/redis-cli 2>/dev/null || true
      ok=1
      log "源码编译版已就位：$(redis-server --version | head -1)"
    fi
  fi
fi

[ "$ok" = "1" ] || { log "✗ Redis 7 安装失败，回写缺陷仍在"; exit 1; }

# 重新确保调优项（升级可能覆盖配置）
grep -q '^maxmemory ' /etc/redis/redis.conf 2>/dev/null && sed -i 's/^maxmemory .*/maxmemory 64mb/' /etc/redis/redis.conf || echo 'maxmemory 64mb' >> /etc/redis/redis.conf
grep -q '^maxmemory-policy' /etc/redis/redis.conf || echo 'maxmemory-policy allkeys-lru' >> /etc/redis/redis.conf

systemctl restart redis-server
sleep 4
log "redis = $(systemctl is-active redis-server)"
log "版本：$(redis-cli INFO server 2>/dev/null | grep redis_version)"
log -n "GETDEL 自检："
redis-cli SET __probe 1 >/dev/null
redis-cli GETDEL __probe
redis-cli DEL __probe >/dev/null
log "maxmemory：$(redis-cli CONFIG GET maxmemory 2>/dev/null | tail -1)"

# 重启后端（升级后连接需要重建）
systemctl restart hy-forum
sleep 20
log "hy-forum = $(systemctl is-active hy-forum)"
log "完成。回写任务将在启动后 5 分钟首次执行。"
