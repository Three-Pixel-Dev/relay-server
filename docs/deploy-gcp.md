# Deploy Relay API to a GCP VM

Same shape as Tobu Learner: **JAR on the VM host**, secrets in `/opt/relay/.env`, GitHub Actions deploys over SSH.

| Piece | Where it runs |
| --- | --- |
| API | JAR on the VM (`java -jar relay.jar` via `./run-jar.sh`) |
| Playwright (WEB scrape) | Optional Docker sidecar (`docker compose up -d`) |
| PostgreSQL | **Neon** (managed) — not on the VM |

**Your VM:** `relay-instance` · `asia-southeast1-c` · external IP `35.185.177.9`

---

## How to give SSH access (do NOT paste the private key in chat)

Cursor / chat should never receive your private key. Use **GitHub Actions secrets** instead.

### 1. Generate a deploy key (on your PC)

```bash
ssh-keygen -t ed25519 -C "relay-gcp-deploy" -f ~/.ssh/relay_gcp_ed25519
```

- **Private:** `~/.ssh/relay_gcp_ed25519` → GitHub secret only  
- **Public:** `~/.ssh/relay_gcp_ed25519.pub` → install on the VM  

### 2. Install the **public** key on the VM

**Option A — GCP Console**  
Compute Engine → `relay-instance` → **Edit** → **SSH Keys** → add:

```text
ssh-ed25519 AAAA...your-public-key... relay-gcp-deploy
```

Username is often the Google account local-part or `ubuntu` / whatever you set as `GCP_VM_USER`.

**Option B — browser SSH** (SSH button on the VM), then:

```bash
mkdir -p ~/.ssh && chmod 700 ~/.ssh
echo 'ssh-ed25519 AAAA...your-public-key...' >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

### 3. Test from your PC

```bash
ssh -i ~/.ssh/relay_gcp_ed25519 ubuntu@35.185.177.9
```

Replace `ubuntu` with the real VM username if different.

### 4. Add GitHub Actions secrets

Repo **Three-Pixel-Dev/relay-server** → **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `GCP_VM_HOST` | `35.185.177.9` |
| `GCP_VM_USER` | e.g. `ubuntu` or your GCP SSH username |
| `GCP_VM_SSH_KEY` | **Full private key** (`-----BEGIN OPENSSH PRIVATE KEY-----` …) |
| `GCP_VM_PORT` | optional, default `22` |
| `GCP_VM_DEPLOY_DIR` | optional, default `/opt/relay` |

Paste the private key only into that secret box — not into Cursor chat, Discord, or the PR.

---

## One-time VM setup

### Firewall

Open **tcp:22** (SSH) and **tcp:8080** (API), or put nginx/HTTPS in front later.

### Install Java 21 + Docker (Playwright)

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jre-headless curl

# Docker (for Playwright sidecar)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
# log out/in after usermod
```

### Deploy directory + `.env`

```bash
sudo mkdir -p /opt/relay/logs /opt/relay/storage
sudo chown -R "$USER":"$USER" /opt/relay
cd /opt/relay
# copy .env.example from the repo once, then fill Neon / JWT / Google / Stripe / etc.
nano .env
```

Important prod values:

```env
SERVER_PORT=8080
SPRING_DATASOURCE_URL=jdbc:postgresql://...
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...
JWT_SECRET=...long-random...
GOOGLE_OAUTH_CLIENT_IDS=...
STRIPE_SECRET_KEY=...
STRIPE_PUBLISHABLE_KEY=...
STRIPE_WEBHOOK_SECRET=...
STRIPE_SUCCESS_URL=https://YOUR_WEB_ORIGIN/connections?stripe=success
STRIPE_CANCEL_URL=https://YOUR_WEB_ORIGIN/connections?stripe=cancel
RELAY_PGVECTOR_AUTO_MIGRATE=false
RELAY_STORAGE_ROOT=/opt/relay/storage
RELAY_WEB_PLAYWRIGHT_WS_ENDPOINT=ws://127.0.0.1:3000/
```

Run Playwright sidecar once:

```bash
cd /opt/relay
# after first CI deploy (or scp docker-compose.yml)
docker compose up -d
```

### Optional systemd

```ini
# /etc/systemd/system/relay-api.service
[Unit]
Description=Relay API
After=network-online.target docker.service

[Service]
Type=simple
User=YOUR_VM_USER
WorkingDirectory=/opt/relay
ExecStart=/opt/relay/run-jar.sh --foreground
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

If you use systemd, change the GitHub deploy step to `sudo systemctl restart relay-api` instead of `./run-jar.sh` (edit the workflow when ready).

---

## CI / CD

| Workflow | When | What |
| --- | --- | --- |
| `.github/workflows/ci.yml` | PR / push | `mvn package` |
| `.github/workflows/deploy-gcp.yml` | push `main` or manual | build JAR → SCP → restart on VM |

After secrets + `/opt/relay/.env` exist, push to `main` or run **Actions → Deploy to GCP VM → Run workflow**.

Health check after deploy:

```bash
curl -fsS http://35.185.177.9:8080/actuator/health
```

---

## Manual deploy (without CI)

```bash
./mvnw -B -DskipTests clean package
cp target/relay-0.0.1-SNAPSHOT.jar relay.jar
scp -i ~/.ssh/relay_gcp_ed25519 relay.jar run-jar.sh stop-jar.sh docker-compose.yml ubuntu@35.185.177.9:/opt/relay/
ssh -i ~/.ssh/relay_gcp_ed25519 ubuntu@35.185.177.9 'cd /opt/relay && chmod +x *.sh && ./stop-jar.sh; ./run-jar.sh'
```
