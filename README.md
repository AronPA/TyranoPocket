# TyranoPocket — 개발 체크포인트 / APK 미생성

**배포 가능한 앱이 아닙니다. Android APK 빌드, Android 16 실행, 실제 TyranoScript 엔진 테스트는 아직 통과하지 않았습니다.**

이 프로젝트의 목표는 Windows Electron 패키지의 웹 게임을 Android에서 로컬로 실행하는 설치형 플레이어입니다. Windows EXE 실행이나 DRM 우회 기능은 없습니다.

## 현재 증거

- 프로덕션 파일 처리 JVM 코드 컴파일 성공, 핵심 테스트 24개 통과.
- 실제 앱에 포함되는 JS 호환·미디어 스크립트의 Node 테스트 7개 통과.
- 96 MiB ASAR 파일 추출을 최대 Java 힙 48 MiB에서 통과.
- Gradle 시작용 공식 배포 다운로드가 HTTP 403으로 차단됨. Gradle task는 시작하지 못함.
- Android SDK, platform-tools, emulator, API 36 디바이스가 없어 Android 테스트 실행 불가.
- Android Kotlin/Compose 소스는 **컴파일·Lint 미검증**.
- ASAR A–E fixture는 문서 기반 독립 생성물. 공식 `@electron/asar` 교차 검증은 **미실행**.
- fixture의 대사 화면은 **합성 테스트 드라이버**입니다. 실제 TyranoScript 엔진이 아닙니다.
- 프로젝트 서명 키는 생성·복구 검증했지만, 서명한 APK는 없습니다.
- 기존 테스트 31개를 다시 실행하여 통과했고, APK 배포 검사 보조 테스트 9개도 통과했습니다.
- 공식 Gradle 8.13 Wrapper를 공식 GitHub commit에서 확보했으며 공개 SHA-256과 일치합니다. Gradle 배포본 전체와 Android SDK 확보를 의미하지는 않습니다.
- GitHub Actions 자동 빌드와 로컬 서명 경로를 준비했습니다. 현재 계정 연결에서 접근 가능한 저장소가 없어 CI 실행은 아직 못했습니다. `docs/ci-build.md`에 현재 상태를 기록했습니다.

## 구조

`app/`: Kotlin, Compose, SAF, WebViewAssetLoader, 미디어 lifecycle, 한국어 UI.

`core/`: Android와 공유하는 Java 8 JVM 라이브러리. GameImporter, ZipImporter, AsarReader, GameDetector, GamePackageBuilder, VirtualFileSystem과 보안·스트리밍 테스트. Android API 없이 실제 컴파일·실행하기 위해 분리했습니다. 전체 프로젝트가 Kotlin만으로 작성된 것은 아닙니다.

`fixtures/`: A 기본, B unpacked, C 백업 복구, D 중첩 ZIP, E 대소문자 충돌 및 공격 ZIP. 공개 저장소에는 포함하지 않으며 테스트 실행 시 `tools/make_fixtures.py`로 생성합니다. 실제 사용자 게임 파일은 사용하지 않습니다.

`tools/`: fixture 생성, 핵심 테스트, 공식 ASAR 비교, Android 검증, 서명 키 생성 및 Gradle 부트스트랩. 사용자에게 실행을 맡기는 설치 안내가 아닙니다.

`evidence/`: 실제 실행한 테스트 및 차단 기록. 공개 소스에는 포함하지 않습니다. CI가 실행되면 별도의 빌드 아티팩트로 로그를 생성합니다. 미실행 항목에 대한 성공 로그는 없습니다.

## 개발 재개 게이트

Android SDK/API 36, build tools, Gradle 의존성 다운로드가 가능한 빌드 환경이 필요합니다. GitHub의 연결된 계정은 확인했으나 접근 가능한 저장소는 아직 없습니다. 공식 대체 배포 경로도 한 번씩 확인했고 차단된 다운로드를 반복하지 않습니다.

1. 사용자가 지정한 GitHub 저장소에 접근하여 준비한 Actions 워크플로 업로드·실행. 공개 저장소에는 허용 목록에 있는 앱 소스와 빌드 파일만 업로드합니다. 게임 파일·생성된 fixture·서명 키·비밀번호·개인 로그는 커밋하지 않습니다. 공식 Wrapper JAR 확보와 검증은 수행했습니다.
2. `tools/official-fixtures.mjs`의 공식 ASAR 입출력과 프로덕션 리더 간 교차 검증 추가·실행.
3. Kotlin/Compose 빌드·Lint를 실행하여 실제 오류 수정. 현재 소스에 잠재 오류가 없다고 보증하지 않음.
4. 테스트 APK/앱 설치 후 `tools/test-android.py`의 두 단계 검증 실행.
5. 실제 시스템 파일 선택기를 통한 ZIP 및 폴더 가져오기, 전화/오디오 포커스, 외부 URL 차단, 두 게임 저장소 격리·삭제, IndexedDB, 영상 전체화면을 추가 검증.
6. 라이선스를 확인한 실제 TyranoScript 엔진 fixture 확보 및 게임·플러그인 실행 테스트. 합성 fixture 성공으로 대체 금지.
7. release APK 서명·인증서·패키지·SDK·설치 검증, Logcat 및 앱 진단 로그 검사.
8. 최신 사용자 지시에 따라 실제 빌드·서명·정적 APK 검증을 통과한 APK를 먼저 전달하고, Android 16 실행 검증의 수행 여부는 별도로 밝힙니다. 실행 미검증 APK를 전체 개발 완료로 표시하지 않습니다.

패키지 ID는 `gradle.properties`의 `tyranopocket.applicationId` 한 곳에서 설정합니다. 현재 값은 `com.local.tyranopocket.p7e7af1`입니다. SDK 36/36, minSdk 26으로 구성했습니다. 소스에 네이티브 ABI 종속 라이브러리는 없습니다. 실제 universal APK 여부는 빌드 후 확인해야 합니다.

서명 자료는 소스 ZIP에 포함하지 않습니다. 별도 키 백업과 복구 자료는 비공개로 보존해야 합니다.
