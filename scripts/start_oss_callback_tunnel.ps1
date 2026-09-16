# =============================================================================
#  OSS callback tunnel supervisor
#
#  Purpose
#  -------
#  Opens a reverse SSH tunnel so Aliyun OSS can reach the local dev backend:
#
#      OSS --POST--> 8.138.237.212:80/api/oss/callback
#                       |  (nginx, exact-match location, POST only)
#                       v
#                 ECS 127.0.0.1:18080
#                       |  (this reverse tunnel)
#                       v
#              dev machine 127.0.0.1:8080
#
#  Why a reconnect loop instead of a plain `ssh -N -R`
#  ---------------------------------------------------
#  The plain tunnel died twice (2026-09-15/16) with:
#      client_loop: send disconnect: Connection reset
#  Root cause was NOT identified. What was ruled out by evidence:
#    - not OOM on the ECS        (242 MB used of 1607 MB, no OOM in dmesg)
#    - not an sshd restart       (up since 2026-09-05)
#    - not a normal disconnect   (the session's end is absent from the ECS
#                                 sshd log => the TCP flow was reset by
#                                 something on the network path)
#  Both failures produced byte-identical messages, so it is reproducible rather
#  than a one-off. Instead of chasing it, this wrapper reconnects automatically,
#  which removes a "remember to check the tunnel" chore from the human.
#
#  Usage
#  -----
#      powershell -NoProfile -File scripts\start_oss_callback_tunnel.ps1
#  Stop with Ctrl+C, or kill the process.
#
#  It relies on ~/.ssh/config having a Host alias named `myserver`.
#
#  Why this file is ASCII-only
#  ---------------------------
#  A .ps1 without a UTF-8 BOM is decoded as GBK by PowerShell 5.1, so any
#  non-ASCII literal in here would be mangled before it ever runs
#  (see docs/agents/README.md 7.1). ASCII-only makes the BOM irrelevant.
# =============================================================================

$ErrorActionPreference = 'Continue'

$remotePort = 18080        # bound on the ECS loopback (GatewayPorts is "no", so loopback only)
$localPort  = 8080         # the dev backend on this machine
$hostAlias  = 'myserver'   # ~/.ssh/config alias for the Aliyun ECS
$delaySec   = 5            # wait before reconnecting

function Now-Stamp { (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') }

Write-Host "[$(Now-Stamp)] OSS callback tunnel supervisor starting."
Write-Host "  remote  : ${hostAlias}:127.0.0.1:${remotePort}"
Write-Host "  local   : 127.0.0.1:${localPort}"
Write-Host "  public  : http://8.138.237.212/api/oss/callback"
Write-Host ""

$attempt = 0
while ($true) {
    $attempt++
    Write-Host "[$(Now-Stamp)] attempt #${attempt}: opening tunnel"

    # -N            : no remote command, forwarding only
    # -R            : remote (reverse) forward
    # ExitOnForwardFailure : fail fast if the remote port is taken, instead of
    #                        staying up with a useless tunnel (silent failure)
    # ServerAlive*  : SSH-level keepalive so an idle flow is not reaped
    ssh -N `
        -R "${remotePort}:127.0.0.1:${localPort}" `
        -o ExitOnForwardFailure=yes `
        -o ServerAliveInterval=15 `
        -o ServerAliveCountMax=4 `
        -o TCPKeepAlive=yes `
        -o ConnectTimeout=10 `
        $hostAlias

    $code = $LASTEXITCODE
    Write-Host "[$(Now-Stamp)] tunnel exited (code ${code}); reconnecting in ${delaySec}s"
    Start-Sleep -Seconds $delaySec
}
