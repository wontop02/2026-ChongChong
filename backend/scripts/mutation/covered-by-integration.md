# 통합 테스트가 이미 검증하는 변이

PIT는 속도 때문에 DB·스프링을 띄우는 통합 테스트(`@Tag("integration")`)를 빼고 잰다.
그래서 통합 테스트가 이미 검증하는 코드도 "실행 안 됨"으로 보일 수 있다.
AI는 이런 변이를 단위 테스트로 **중복해서 쓰지 말고** 여기에 기록한다.

기록은 주장일 뿐이므로, 통합 테스트까지 포함한 PIT로 정말 잡히는지 확인해서 "확인" 칸을 채운다. (PR CI가 아니라 로컬에서 필요할 때)

```bash
cd backend
./gradlew pitest -PpitWithIntegration \
  -PpitTargets='<대상 클래스>,<대상 클래스>$*' \
  -PpitTests='<표에 적힌 통합 테스트>,<그 클래스의 단위 테스트>'
```

| 위치 | 변이 | 이미 검증하는 테스트 | 확인 | 확인 방법 |
|---|---|---|---|---|
| auth.service.AuthTokenService:49 `issue()` | 반환값을 null로 | `AuthTokenServiceTest` "기존 사용자에게 Access Token과 Refresh Token을 발급하고 인증 세션을 저장한다" | ✅ 잡힘 | 통합 포함 PIT (11-17): `AuthTokenServiceTest`가 잡음 |
| auth.service.AuthTokenService:83 `createTokenPair()` | 반환값을 null로 | 위와 같음 | ✅ 잡힘 | 통합 포함 PIT (11-17): `AuthTokenServiceTest`가 잡음 |
| auth.service.AuthTokenService:64 `rotate()` | 세션 토큰 교체 호출 삭제 | `AuthTokenServiceRefreshTest` "회전이 끝난 이전 Refresh Token은 다시 사용할 수 없다" | ✅ 잡힘 | 통합 포함 PIT (11-17): `AuthTokenServiceRefreshTest`가 잡음 |
| auth.service.AuthTokenService:66 `rotate()` | 반환값을 null로 | `AuthTokenServiceRefreshTest` "유효한 Refresh Token을 새 Access Token과 Refresh Token으로 회전한다" | ✅ 잡힘 | 통합 포함 PIT (11-17): `AuthTokenServiceRefreshTest`가 잡음 |
| comment.service.CommentService:119 `update()` | 수정 권한 검사 삭제 | `AssignmentCommentApiTest` "과제·제출물 댓글 CRUD와 대상별 알림·위치 조회 및 작성자·리더 권한을 적용한다" | ✅ 잡힘 | 통합 포함 PIT (11-17): `AssignmentCommentApiTest`가 잡음 |
| comment.service.CommentService:120 `update()` | 댓글 수정 호출 삭제 | 위와 같음 | ✅ 잡힘 | 통합 포함 PIT (11-17): `AssignmentCommentApiTest`가 잡음 |
| comment.service.CommentService:131 `delete()` | 삭제 권한 검사 삭제 | 위와 같음 | ✅ 잡힘 | 통합 포함 PIT (11-17): `AssignmentCommentApiTest`가 잡음 |
| comment.service.CommentService:132 `delete()` | 댓글 삭제 호출 삭제 | 위와 같음 | ✅ 잡힘 | 통합 포함 PIT (11-17): `AssignmentCommentApiTest`가 잡음 |
