#!/usr/bin/env bash
# Deploys the relay from this checkout's HEAD to the instance, whatever the
# commit (runbook §7.1, as one command). Run it ON THE MAC, from the repo:
#
#   ssh-add ~/.ssh/skipti_oracle        # once per login: the key has a passphrase
#   deploy/ship-relay.sh ubuntu@<PUBLIC_IP>
#
# What it does, in order, stopping at the first failure:
#   1. refuses a dirty tree (the tarball is HEAD, never your working files);
#   2. reads the live commit from /version, and stops if HEAD is already live;
#   3. ships `git archive HEAD` and the full commit hash to the instance;
#   4. builds there while the old relay keeps serving;
#   5. stops the relay, backs up the vault to /var/lib/skipti/backup-<live>,
#      installs the binary and the /join page, starts the relay;
#   6. checks /version answers with HEAD.
#
# Rolling back is the old binary plus that backup (runbook §7.1): a schema
# migration is one-way. The host is an argument so no address lives in this
# public repository.
set -euo pipefail

HOST="${1:?usage: deploy/ship-relay.sh ubuntu@<PUBLIC_IP>}"
KEY="${SKIPTI_SSH_KEY:-$HOME/.ssh/skipti_oracle}"
SITE="${SKIPTI_SITE:-https://skipti.vardrlabs.com}"

cd "$(git rev-parse --show-toplevel)"
if [ -n "$(git status --porcelain)" ]; then
  echo "The tree has uncommitted changes. Commit or stash them: the deploy is HEAD." >&2
  exit 1
fi
COMMIT="$(git rev-parse HEAD)"
LIVE="$(curl -fsS "$SITE/version" | sed -n 's/.*"commit":"\([0-9a-f]*\)".*/\1/p')"
LIVE="${LIVE:-unknown}"
echo "live:   $LIVE"
echo "deploy: $COMMIT"
if [ "$LIVE" = "$COMMIT" ]; then
  echo "Already live. Nothing to do."
  exit 0
fi

git archive --format=tar.gz -o /tmp/skipti-src.tar.gz HEAD
echo "$COMMIT" > /tmp/skipti-commit.txt
scp -i "$KEY" /tmp/skipti-src.tar.gz /tmp/skipti-commit.txt "$HOST":/tmp/

ssh -i "$KEY" "$HOST" "BACKUP=backup-${LIVE:0:7} bash -s" <<'REMOTE'
set -euo pipefail
source "$HOME/.cargo/env"
COMMIT="$(cat /tmp/skipti-commit.txt)"
echo "building $COMMIT (the old relay keeps serving)"
rm -rf ~/skipti && mkdir -p ~/skipti && tar -xzf /tmp/skipti-src.tar.gz -C ~/skipti
cd ~/skipti && SKIPTI_GIT_COMMIT="$COMMIT" cargo build --release -p skipti-server
echo "stopping, backing up to /var/lib/skipti/$BACKUP, installing"
sudo systemctl stop skipti-relay
sudo mkdir -p "/var/lib/skipti/$BACKUP"
sudo sh -c "cp -p /var/lib/skipti/relay-vault.db* /var/lib/skipti/$BACKUP/"
sudo install -m 755 ~/skipti/target/release/skipti-server /usr/local/bin/skipti-server
# D-199: the page moved to firebase/public (Firebase Hosting serves it from 0.4.0).
if [ -f ~/skipti/firebase/public/join/index.html ]; then
  sudo mkdir -p /var/www/skipti/join
  sudo install -m 644 ~/skipti/firebase/public/join/index.html /var/www/skipti/join/index.html
fi
sudo systemctl daemon-reload
sudo systemctl start skipti-relay
sleep 2
systemctl is-active skipti-relay
REMOTE

sleep 2
NOW="$(curl -fsS "$SITE/version" | sed -n 's/.*"commit":"\([0-9a-f]*\)".*/\1/p')"
if [ "$NOW" = "$COMMIT" ]; then
  echo "Deployed: /version answers $NOW."
else
  echo "The relay answers ${NOW:-nothing}, not $COMMIT. Check: sudo systemctl status skipti-relay" >&2
  exit 1
fi
