#!/system/bin/sh
MODDIR=${0%/*}
umask 077
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    [ -f "$MODDIR/disable" ] && exit 0
    sleep 3
done
echo $$ > "$MODDIR/supervisor.pid"
while [ ! -f "$MODDIR/disable" ] && [ -f "$MODDIR/backend.apk" ]; do
    if [ -f "$MODDIR/backend.log" ] && [ "$(wc -c < "$MODDIR/backend.log")" -gt 1048576 ]; then
        mv "$MODDIR/backend.log" "$MODDIR/backend.previous.log"
    fi
    CLASSPATH="$MODDIR/backend.apk" /system/bin/app_process /system/bin \
        dev.local.supercardhost.RootDisplayDaemon >> "$MODDIR/backend.log" 2>&1 &
    daemon_pid=$!
    echo "$daemon_pid" > "$MODDIR/daemon.pid"
    wait "$daemon_pid"
    sleep 3
done
