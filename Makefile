.PHONY: build run package deploy

VERSION ?= $(shell date +%Y%m%d-%H%M%S)
VPS_HOST ?=
VPS_USER ?=
VPS_SSH_KEY ?=

build:
	./gradlew shadowJar --no-daemon

run:
	DB_HOST=localhost DB_PORT=5432 DB_NAME=trade_collector DB_USER=trade_user DB_PASSWORD=dev_password HTTP_HOST=0.0.0.0 HTTP_PORT=8085 java -jar build/libs/market-data-server.jar

package: build
	mkdir -p deploy-package
	cp build/libs/market-data-server.jar deploy-package/
	printf '#!/bin/bash\nDB_HOST=$${DB_HOST:-localhost}\nDB_PORT=$${DB_PORT:-5432}\nDB_NAME=$${DB_NAME:-trade_collector}\nDB_USER=$${DB_USER:-trade_user}\nDB_PASSWORD=$${DB_PASSWORD:-dev_password}\nHTTP_HOST=$${HTTP_HOST:-0.0.0.0}\nHTTP_PORT=$${HTTP_PORT:-8085}\ncd /opt/market-data-server/current\nexec java -jar market-data-server.jar\n' > deploy-package/run.sh
	chmod +x deploy-package/run.sh
	printf '[Unit]\nDescription=Market Data Server\nAfter=network.target\n\n[Service]\nType=simple\nUser=deploy\nGroup=deploy\nWorkingDirectory=/opt/market-data-server/current\nEnvironmentFile=/etc/default/market-data-server\nExecStart=/opt/market-data-server/current/run.sh\nRestart=on-failure\nRestartSec=10\n\n[Install]\nWantedBy=multi-user.target\n' > deploy-package/market-data-server.service
	tar -czf market-data-server-$(VERSION).tar.gz -C deploy-package .
	rm -rf deploy-package
	@ls -lh market-data-server-*.tar.gz

deploy: package
	@test -n "$(VPS_HOST)" || { echo "❌ set VPS_HOST="; exit 1; }
	@test -n "$(VPS_USER)" || { echo "❌ set VPS_USER="; exit 1; }
	@test -n "$(VPS_SSH_KEY)" || { echo "❌ set VPS_SSH_KEY="; exit 1; }
	ssh -i $(VPS_SSH_KEY) $(VPS_USER)@$(VPS_HOST) "sudo mkdir -p /opt/market-data-server/current && sudo chown -R $(VPS_USER):$(VPS_USER) /opt/market-data-server"
	scp -i $(VPS_SSH_KEY) market-data-server-$(VERSION).tar.gz $(VPS_USER)@$(VPS_HOST):/tmp/
	ssh -i $(VPS_SSH_KEY) $(VPS_USER)@$(VPS_HOST) "\
		set -e; \
		sudo systemctl stop market-data-server 2>/dev/null || true; \
		tar -xzf /tmp/market-data-server-$(VERSION).tar.gz -C /opt/market-data-server/current/; \
		sudo cp /opt/market-data-server/current/market-data-server.service /etc/systemd/system/; \
		sudo systemctl daemon-reload; \
		sudo systemctl enable market-data-server 2>/dev/null || true; \
		sudo systemctl restart market-data-server; \
		sleep 3; \
		curl -sf http://localhost:8085/health && echo ' ✅' \
	"
	@rm -f market-data-server-*.tar.gz
