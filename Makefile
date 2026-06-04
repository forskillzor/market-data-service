.PHONY: build run

build:
	./gradlew shadowJar --no-daemon

run:
	@echo "Running on :8081..."
	DB_HOST=localhost DB_PORT=5432 DB_NAME=trade_collector DB_USER=trade_user DB_PASSWORD=dev_password java -jar build/libs/market-data-server.jar
