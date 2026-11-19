#!/usr/bin/env bash
# Run on the GCP VM as relay914 (or with sudo).
# Domain: https://dev-api-relay.saungera.com → localhost:8080
set -euo pipefail

DOMAIN="dev-api-relay.saungera.com"
EMAIL="${CERTBOT_EMAIL:-admin@saungera.com}"
APP_PORT="${APP_PORT:-8080}"

echo "==> Installing nginx + certbot"
sudo apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y nginx certbot python3-certbot-nginx

echo "==> Writing temporary HTTP-only site (for ACME)"
sudo tee /etc/nginx/sites-available/"$DOMAIN" >/dev/null <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        proxy_pass http://127.0.0.1:${APP_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
        client_max_body_size 55m;
    }
}
EOF

sudo ln -sfn /etc/nginx/sites-available/"$DOMAIN" /etc/nginx/sites-enabled/"$DOMAIN"
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx

echo "==> Checking DNS resolves to this VM"
RESOLVED="$(getent ahostsv4 "$DOMAIN" | awk '{print $1; exit}' || true)"
MY_IP="$(curl -fsS -4 ifconfig.me || curl -fsS -4 icanhazip.com || true)"
echo "Domain A: ${RESOLVED:-NONE}"
echo "VM public: ${MY_IP:-UNKNOWN}"
if [[ -z "${RESOLVED:-}" ]]; then
  echo "ERROR: $DOMAIN has no A record. In Z.com delete the CNAME and add an A record:"
  echo "  Host: dev-api-relay   Type: A   Value: ${MY_IP:-35.185.177.9}"
  exit 1
fi
if [[ -n "${MY_IP:-}" && "$RESOLVED" != "$MY_IP" ]]; then
  echo "WARNING: DNS ($RESOLVED) != this VM ($MY_IP). Certbot may fail."
fi

echo "==> Issuing Let's Encrypt certificate"
sudo certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m "$EMAIL" --redirect

sudo nginx -t
sudo systemctl reload nginx

echo "==> Done"
echo "Test: curl -fsS https://${DOMAIN}/actuator/health"
