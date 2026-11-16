# 통합 테스트가 이미 검증하는 변이

PIT는 속도 때문에 DB·스프링을 띄우는 통합 테스트(`@Tag("integration")`)를 빼고 잰다.
그래서 통합 테스트가 이미 검증하는 코드도 "실행 안 됨"으로 보일 수 있다.
AI는 이런 변이를 단위 테스트로 **중복해서 쓰지 말고** 여기에 기록한다. 사람이 판정한다.

| 위치 | 변이 | 이미 검증하는 테스트 | 사람 판정 | 판정 이유 |
|---|---|---|---|---|
| auth.service.AuthTokenService:49 `issue()` | 반환값을 null로 | `AuthTokenServiceTest` "기존 사용자에게 Access Token과 Refresh Token을 발급하고 인증 세션을 저장한다" | | |
| auth.service.AuthTokenService:83 `createTokenPair()` | 반환값을 null로 | 위와 같음 | | |
| auth.service.AuthTokenService:64 `rotate()` | 세션 토큰 교체 호출 삭제 | `AuthTokenServiceRefreshTest` "회전이 끝난 이전 Refresh Token은 다시 사용할 수 없다" | | |
| auth.service.AuthTokenService:66 `rotate()` | 반환값을 null로 | `AuthTokenServiceRefreshTest` "유효한 Refresh Token을 새 Access Token과 Refresh Token으로 회전한다" | | |
| comment.service.CommentService:119 `update()` | 수정 권한 검사 삭제 | `AssignmentCommentApiTest` "과제·제출물 댓글 CRUD와 대상별 알림·위치 조회 및 작성자·리더 권한을 적용한다" | | |
| comment.service.CommentService:120 `update()` | 댓글 수정 호출 삭제 | 위와 같음 | | |
| comment.service.CommentService:131 `delete()` | 삭제 권한 검사 삭제 | 위와 같음 | | |
| comment.service.CommentService:132 `delete()` | 댓글 삭제 호출 삭제 | 위와 같음 | | |
| assignment.service.AssignmentService:73 `create()` | `VoidMethodCall`: `StudyMemberRepository.requireExistsByStudyIdAndUserId` 호출 삭제 | `StudyMutationAccessApiTest` "멤버십이 필요한 변경 요청은 스터디 존재 확인보다 접근 거부를 먼저 반환한다" (POST `/assignments`) | | |
| assignment.service.AssignmentService:88 `create()` | `VoidMethodCall`: `Assignment.addAttachments` 호출 삭제 | `AssignmentAttachmentApiTest` "과제 첨부를 등록하고 순서를 변경하거나 제거할 수 있다" | | |
| assignment.service.AssignmentService:98 `update()` | `VoidMethodCall`: `StudyMemberRepository.requireExistsByStudyIdAndUserId` 호출 삭제 | `StudyMutationAccessApiTest` "멤버십이 필요한 변경 요청은 스터디 존재 확인보다 접근 거부를 먼저 반환한다" (PATCH `/assignments/1`) | | |
| assignment.service.AssignmentService:100 `update()` | `NegateConditionals`: 제출 대상 ID 존재 조건 반전 | `AssignmentApiTest` "제출 대상을 제외해도 기록을 보존하고 재추가 후 수정할 수 있다" | | |
| assignment.service.AssignmentService:112 `update()` | `NegateConditionals`: 제출 대상 ID 존재 조건 반전 | `AssignmentApiTest` "제출 대상을 제외해도 기록을 보존하고 재추가 후 수정할 수 있다", "리더가 JSON으로 과제 수정 요청을 보내면 모든 수정 필드와 대기 리마인더를 변경한다" | | |
| assignment.service.AssignmentService:118 `update()` | `VoidMethodCall`: `replaceAttachments` 호출 삭제 | `AssignmentAttachmentApiTest` "과제 첨부를 등록하고 순서를 변경하거나 제거할 수 있다" | | |
| assignment.service.AssignmentService:121 `update()` | `VoidMethodCall`: `Assignment.update` 호출 삭제 | `AssignmentApiTest` "리더가 JSON으로 과제 수정 요청을 보내면 모든 수정 필드와 대기 리마인더를 변경한다" | | |
| assignment.service.AssignmentService:126 `update()` | `NegateConditionals`: 제출 대상 교체 조건 반전 | `AssignmentApiTest` "제출 대상을 제외해도 기록을 보존하고 재추가 후 수정할 수 있다", "리더가 JSON으로 과제 수정 요청을 보내면 모든 수정 필드와 대기 리마인더를 변경한다" | | |
| assignment.service.AssignmentService:127 `update()` | `VoidMethodCall`: `Assignment.replaceSubmitters` 호출 삭제 | `AssignmentApiTest` "제출 대상을 제외해도 기록을 보존하고 재추가 후 수정할 수 있다" | | |
| assignment.service.AssignmentService:134 `delete()` | `VoidMethodCall`: `StudyMemberRepository.requireExistsByStudyIdAndUserId` 호출 삭제 | `StudyMutationAccessApiTest` "멤버십이 필요한 변경 요청은 스터디 존재 확인보다 접근 거부를 먼저 반환한다" (DELETE `/assignments/1`) | | |
| assignment.service.AssignmentService:140 `delete()` | `VoidMethodCall`: `CommentRepository.deleteAllForAssignment` 호출 삭제 | `AssignmentCommentConcurrencyTest` "과제·제출물 댓글 생성이 먼저 잠그면 과제 삭제는 커밋을 기다린 뒤 댓글·알림을 정리한다" | | |
| assignment.service.AssignmentService:141 `delete()` | `VoidMethodCall`: `NotificationService.deleteNotificationsForResource` 호출 삭제 | `AssignmentNotificationDeletionTransactionTest` "과제 삭제 시 과제·제출물 알림과 발송 기록을 함께 삭제한다" | | |
| assignment.service.AssignmentService:167 `getDetail()` | `NullReturnVals`: 상세 응답을 null로 | `AssignmentApiTest` "스터디원이 과제 상세를 조회하면 클라이언트 계약 필드를 반환한다" | | |
| assignment.service.AssignmentService:171 `getList()` | `NullReturnVals`: 기본 정렬 목록 응답을 null로 | `AssignmentApiTest` "리더가 과제 목록을 조회하면 제출 인원과 리마인드 정보를 반환한다" | | |
| assignment.service.AssignmentService:195 `findSortedAssignments()` | `NegateConditionals`: 커서 null 조건 반전 | `AssignmentApiTest` "마감순은 마감 전·후와 같은 마감 시각 경계를 넘어 빠짐없이 이어 조회한다" | | |
| assignment.service.AssignmentService:197 `findSortedAssignments()` | `NegateConditionals`: 마감순 분기 반전 | `AssignmentApiTest` "마감순은 마감 전·후와 같은 마감 시각 경계를 넘어 빠짐없이 이어 조회한다", "미제출순은 본인의 대상 과제만 우선하며 제출·대상 제외·미배정 과제도 이어 조회한다" | | |
| assignment.service.AssignmentService:199 `findSortedAssignments()` | `NegateConditionals`: anchor null 조건 반전 | `AssignmentApiTest` "마감순은 마감 전·후와 같은 마감 시각 경계를 넘어 빠짐없이 이어 조회한다" | | |
| assignment.service.AssignmentService:200 `findSortedAssignments()` | `NegateConditionals`: cursorCloseAt null 조건 반전 | `AssignmentApiTest` "마감순은 마감 전·후와 같은 마감 시각 경계를 넘어 빠짐없이 이어 조회한다" | | |
| assignment.service.AssignmentService:200 `findSortedAssignments()` | `NegateConditionals`: 마감 시각이 현재보다 이전인지 확인하는 조건 반전 | `AssignmentApiTest` "마감순은 마감 전·후와 같은 마감 시각 경계를 넘어 빠짐없이 이어 조회한다" | | |
| assignment.service.AssignmentService:201 `findSortedAssignments()` | `EmptyObjectReturnVals`: 마감순 결과를 빈 목록으로 | `AssignmentApiTest` "마감순은 마감 전·후와 같은 마감 시각 경계를 넘어 빠짐없이 이어 조회한다" | | |
| assignment.service.AssignmentService:204 `findSortedAssignments()` | `NegateConditionals`: 미제출순 anchor null 조건 반전 | `AssignmentApiTest` "미제출순은 본인의 대상 과제만 우선하며 제출·대상 제외·미배정 과제도 이어 조회한다" | | |
| assignment.service.AssignmentService:206 `lambda$findSortedAssignments$0()` | `NegateConditionals`: 제출 대상 여부 조건 반전 | `AssignmentApiTest` "미제출순은 본인의 대상 과제만 우선하며 제출·대상 제외·미배정 과제도 이어 조회한다" | | |
| assignment.service.AssignmentService:206 `lambda$findSortedAssignments$0()` | `NegateConditionals`: 제출 시각 null 조건 반전 | `AssignmentApiTest` "미제출순은 본인의 대상 과제만 우선하며 제출·대상 제외·미배정 과제도 이어 조회한다" | | |
| assignment.service.AssignmentService:206 `lambda$findSortedAssignments$0()` | `BooleanTrueReturnVals`: 필터 결과를 항상 true로 | `AssignmentApiTest` "미제출순은 본인의 대상 과제만 우선하며 제출·대상 제외·미배정 과제도 이어 조회한다" | | |
| assignment.service.AssignmentService:208 `findSortedAssignments()` | `EmptyObjectReturnVals`: 미제출순 결과를 빈 목록으로 | `AssignmentApiTest` "미제출순은 본인의 대상 과제만 우선하며 제출·대상 제외·미배정 과제도 이어 조회한다" | | |
| assignment.service.AssignmentService:212 `replaceAttachments()` | `NegateConditionals`: 첨부파일 ID null 조건 반전 | `AssignmentAttachmentApiTest` "과제 첨부를 등록하고 순서를 변경하거나 제거할 수 있다" | | |
| assignment.service.AssignmentService:217 `replaceAttachments()` | `VoidMethodCall`: `Assignment.clearAttachments` 호출 삭제 | `AssignmentAttachmentApiTest` "과제 첨부를 등록하고 순서를 변경하거나 제거할 수 있다" | | |
| assignment.service.AssignmentService:218 `replaceAttachments()` | `VoidMethodCall`: `AssignmentRepository.flush` 호출 삭제 | `AssignmentAttachmentApiTest` "과제 첨부를 등록하고 순서를 변경하거나 제거할 수 있다" | | |
