#!/usr/bin/env bash
# HOOK VPN - تحويل خادم Xray إلى VLESS + WebSocket + TLS (شهادة ذاتية)
# بعد التحويل يقبل الخادم أي SNI، ويستطيع فاحص SNI في التطبيق اختبار القوائم الكبيرة.
# التشغيل:  curl -fsSL <رابط هذا الملف> | sudo bash
# للتراجع إلى REALITY:  sudo cp /usr/local/etc/xray/config.json.reality.bak /usr/local/etc/xray/config.json && sudo systemctl restart xray
set -e

DIR=/usr/local/etc/xray
CONF=$DIR/config.json

if [ "$(id -u)" -ne 0 ]; then
  echo "شغّل السكربت بـ sudo"
  exit 1
fi
command -v xray >/dev/null 2>&1 || { echo "Xray غير مثبّت"; exit 1; }
command -v openssl >/dev/null 2>&1 || apt-get install -y openssl >/dev/null
command -v python3 >/dev/null 2>&1 || apt-get install -y python3 >/dev/null

# نحتفظ بنفس UUID القديم إن وُجد
UUID=$(python3 - <<'PY'
import json
try:
    c = json.load(open("/usr/local/etc/xray/config.json"))
    print(c["inbounds"][0]["settings"]["clients"][0]["id"])
except Exception:
    pass
PY
)
[ -n "$UUID" ] || UUID=$(xray uuid)

# نسخة احتياطية من الإعداد الحالي (مرة واحدة فقط)
[ -f "$CONF.reality.bak" ] || cp "$CONF" "$CONF.reality.bak"

# شهادة ذاتية صالحة 10 سنوات
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout "$DIR/hook.key" -out "$DIR/hook.crt" -days 3650 -subj "/CN=hook" >/dev/null 2>&1

# صلاحيات الملفات لمستخدم خدمة Xray
SVC_USER=$(systemctl show xray -p User --value 2>/dev/null || true)
[ -n "$SVC_USER" ] || SVC_USER=root
chown "$SVC_USER" "$DIR/hook.key" "$DIR/hook.crt"
chmod 600 "$DIR/hook.key"
chmod 644 "$DIR/hook.crt"

WSPATH="/h$(openssl rand -hex 4)"

cat > "$CONF" <<JSON
{
  "inbounds": [{
    "port": 443,
    "protocol": "vless",
    "settings": {
      "clients": [{ "id": "$UUID" }],
      "decryption": "none"
    },
    "streamSettings": {
      "network": "ws",
      "security": "tls",
      "tlsSettings": {
        "alpn": ["http/1.1"],
        "certificates": [{
          "certificateFile": "$DIR/hook.crt",
          "keyFile": "$DIR/hook.key"
        }]
      },
      "wsSettings": { "path": "$WSPATH" }
    }
  }],
  "outbounds": [{ "protocol": "freedom" }]
}
JSON

xray run -test -config "$CONF"
systemctl restart xray
sleep 2
STATE=$(systemctl is-active xray || true)
echo "حالة Xray: $STATE"
if [ "$STATE" != "active" ]; then
  echo "فشل التشغيل. للتراجع: cp $CONF.reality.bak $CONF && systemctl restart xray"
  exit 1
fi

IP=$(curl -4 -s https://ifconfig.me || true)
[ -n "$IP" ] || IP="IP_السيرفر"
ENC_PATH=$(printf '%s' "$WSPATH" | sed 's|/|%2F|g')

echo
echo "================ انسخ هذا الرابط إلى التطبيق (+ إضافة ← من الحافظة) ================"
echo "vless://$UUID@$IP:443?encryption=none&security=tls&type=ws&path=$ENC_PATH&sni=facebook.com&fp=chrome&alpn=http%2F1.1#hook-ws"
echo "===================================================================================="
