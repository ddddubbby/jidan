# 统一验收入口，命令与开发规格 14.2 一致。
#   make verify           依次跑后端、Web、APP
#   make verify-backend   只跑后端（需要 Docker 在运行，Testcontainers 会起一个 MySQL）
# web/ 与 android/ 还没建出来时（P0-3、P0-4 之前）对应一步明确打印“跳过”，不当作通过。

.PHONY: verify verify-backend verify-web verify-android

verify: verify-backend verify-web verify-android

verify-backend:
	cd backend && ./mvnw verify

verify-web:
	@if [ -d web ]; then \
		cd web && npm ci && npm run typecheck && npm run build; \
	else \
		echo "[跳过] web/ 尚未建立（P0-3），Web 未验证"; \
	fi

verify-android:
	@if [ -d android ]; then \
		cd android && ./gradlew :core:test :device:testDebugUnitTest :app:testSimDebugUnitTest :app:assembleSimDebug :app:assembleRealDebug; \
	else \
		echo "[跳过] android/ 尚未建立（P0-4），APP 未验证"; \
	fi
