# Favorite App Shortcuts Design

## Goal

Galaxy Mirror Android 앱에서 자주 쓰는 앱을 추가/삭제하고, Mac 뷰어의 남는 패널 영역에서 해당 앱 바로가기를 눌러 Galaxy 단말에서 앱을 실행한다.

## Scope

- Android 앱이 설치된 런처 앱 목록을 보여주고 사용자가 즐겨찾기를 선택한다.
- 즐겨찾기는 Android 로컬 저장소에 `packageName`, `label`만 저장한다.
- Mac 뷰어는 즐겨찾기 목록을 읽고 실행 버튼만 제공한다.
- Mac 뷰어에서는 즐겨찾기 추가/삭제를 제공하지 않는다.

## Architecture

- `FavoriteAppsRepository`가 런처 앱 조회, 즐겨찾기 저장, JSON 응답 생성, 앱 실행을 담당한다.
- `MainActivity` Ktor 서버는 `GET /apps/favorites`와 `POST /apps/launch`를 제공한다.
- Compose 메인 화면은 즐겨찾기 목록, 앱 추가 버튼, 삭제 버튼을 제공한다.
- 정적 뷰어는 `GET /apps/favorites`로 바로가기를 렌더링하고, 클릭 시 `POST /apps/launch`를 호출한다.

## Android Constraints

- Android 11+ package visibility를 위해 manifest에 launcher intent query를 선언한다.
- 앱 실행은 `packageManager.getLaunchIntentForPackage(packageName)` 결과가 있을 때만 수행한다.
- launch 실패, 삭제된 앱, 빈 즐겨찾기는 사용자에게 조용히 실패하지 않고 상태 메시지를 남긴다.

## Testing

- 즐겨찾기 JSON 직렬화, 중복 제거, 삭제, launch request 파싱을 단위 테스트한다.
- Compose 화면에서 자주 쓰는 앱 섹션과 추가/삭제 버튼 노출을 컴파일/계측 테스트로 확인한다.
- 뷰어 JS는 즐겨찾기 버튼 렌더링과 `POST /apps/launch` 호출을 Node 테스트로 확인한다.
