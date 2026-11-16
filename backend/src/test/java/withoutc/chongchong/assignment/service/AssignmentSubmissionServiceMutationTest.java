package withoutc.chongchong.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import withoutc.chongchong.assignment.controller.dto.AssignmentAttachmentResponse;
import withoutc.chongchong.assignment.controller.dto.AssignmentSubmitRequest;
import withoutc.chongchong.assignment.controller.dto.MySubmissionDetailResponse;
import withoutc.chongchong.assignment.controller.dto.SubmissionDetailResponse;
import withoutc.chongchong.assignment.controller.dto.SubmissionListResponse.SubmissionSummary;
import withoutc.chongchong.assignment.entity.Assignment;
import withoutc.chongchong.assignment.entity.AssignmentSubmission;
import withoutc.chongchong.assignment.entity.SubmissionAttachment;
import withoutc.chongchong.assignment.entity.SubmissionStatus;
import withoutc.chongchong.assignment.exception.AssignmentErrorCode;
import withoutc.chongchong.assignment.exception.AssignmentException;
import withoutc.chongchong.assignment.policy.AssignmentAccessPolicy;
import withoutc.chongchong.assignment.policy.AssignmentAttachmentPolicy;
import withoutc.chongchong.assignment.repository.AssignmentRepository;
import withoutc.chongchong.assignment.repository.AssignmentSubmissionRepository;
import withoutc.chongchong.assignment.support.AssignmentTestFixture;
import withoutc.chongchong.auth.exception.AuthErrorCode;
import withoutc.chongchong.auth.exception.AuthException;
import withoutc.chongchong.file.entity.FileMetadata;
import withoutc.chongchong.file.service.FileUrlGenerator;
import withoutc.chongchong.notification.service.NotificationService;
import withoutc.chongchong.study.entity.StudyMember;
import withoutc.chongchong.study.entity.StudyMemberRole;
import withoutc.chongchong.study.exception.StudyMemberErrorCode;
import withoutc.chongchong.study.exception.StudyMemberException;
import withoutc.chongchong.study.repository.StudyMemberRepository;
import withoutc.chongchong.study.repository.projection.StudyMemberIdentityProjection;
import withoutc.chongchong.user.entity.User;
import withoutc.chongchong.user.repository.UserRepository;
import withoutc.chongchong.user.service.UserProfileImageUrlResolver;

@ExtendWith(MockitoExtension.class)
@DisplayName("과제 제출 서비스의 권한과 첨부파일 및 조회 결과")
class AssignmentSubmissionServiceMutationTest {

    private static final Long USER_ID = 1L;
    private static final Long STUDY_ID = 10L;
    private static final Long ASSIGNMENT_ID = 100L;
    private static final Long MEMBER_ID = 20L;
    private static final Long SUBMISSION_ID = 300L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 10, 0);

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentSubmissionRepository submissionRepository;
    @Mock
    private StudyMemberRepository memberRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AssignmentAttachmentService attachmentService;
    @Mock
    private FileUrlGenerator fileUrlGenerator;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserProfileImageUrlResolver profileImageUrlResolver;

    private AssignmentSubmissionService service;
    private Assignment assignment;
    private StudyMember actor;
    private AssignmentSubmission submission;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new AssignmentSubmissionService(assignmentRepository, submissionRepository, memberRepository,
                notificationService, attachmentService, fileUrlGenerator, userRepository,
                new AssignmentAttachmentPolicy(), new AssignmentAccessPolicy(), clock, profileImageUrlResolver);
        assignment = AssignmentTestFixture.assignmentWithId(ASSIGNMENT_ID, STUDY_ID, NOW);
        actor = member(MEMBER_ID, USER_ID, StudyMemberRole.MEMBER, "제출자");
        submission = submission(SUBMISSION_ID, actor, assignment);
    }

    @Test
    @DisplayName("스터디에 가입하지 않은 사용자는 사용자 잠금이나 과제 조회 전에 접근 거부된다")
    void rejectNonMemberBeforeLockingUsers() {
        doCallRealMethod().when(memberRepository).requireExistsByStudyIdAndUserId(STUDY_ID, USER_ID);
        when(memberRepository.existsByStudyIdAndUserId(STUDY_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_ID, STUDY_ID, ASSIGNMENT_ID,
                new AssignmentSubmitRequest("제출 내용", null)))
                .isInstanceOf(StudyMemberException.class)
                .extracting(exception -> ((StudyMemberException) exception).getErrorCode())
                .isEqualTo(StudyMemberErrorCode.STUDY_ACCESS_DENIED);
        verifyNoInteractions(userRepository, assignmentRepository, submissionRepository, notificationService);
    }

    @Test
    @DisplayName("배정이 해제된 제출물은 소유자도 제출할 수 없고 제출 상태가 바뀌지 않는다")
    void rejectUnassignedSubmission() {
        givenSubmit();
        submission.unassign();

        assertThatThrownBy(() -> service.submit(USER_ID, STUDY_ID, ASSIGNMENT_ID,
                new AssignmentSubmitRequest("제출 내용", null)))
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_DENIED);
        assertThat(submission.getSubmittedAt()).isNull();
        assertThat(submission.getContent()).isNull();
        verifyNoInteractions(attachmentService, notificationService);
    }

    @Test
    @DisplayName("본문과 링크가 공백이고 첨부파일도 없으면 최초 제출을 거부한다")
    void rejectEmptySubmission() {
        givenSubmit();

        assertThatThrownBy(() -> service.submit(USER_ID, STUDY_ID, ASSIGNMENT_ID,
                new AssignmentSubmitRequest(" \n", "\t", List.of())))
                .isInstanceOf(AssignmentException.class)
                .extracting(exception -> ((AssignmentException) exception).getErrorCode())
                .isEqualTo(AssignmentErrorCode.EMPTY_SUBMISSION);
        assertThat(submission.getSubmittedAt()).isNull();
        assertThat(submission.getContent()).isNull();
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("수정 결과의 본문과 링크 및 첨부파일이 모두 비면 저장하지 않고 거부한다")
    void rejectUpdateThatEmptiesSubmission() {
        submission.submit("기존 내용", "https://old.example.com", NOW.minusHours(1));
        givenUpdate();

        assertThatThrownBy(() -> service.updateSubmission(USER_ID, STUDY_ID, ASSIGNMENT_ID, SUBMISSION_ID,
                new AssignmentSubmitRequest("", " ", List.of())))
                .isInstanceOf(AssignmentException.class)
                .extracting(exception -> ((AssignmentException) exception).getErrorCode())
                .isEqualTo(AssignmentErrorCode.EMPTY_SUBMISSION);
        verify(submissionRepository, never()).save(submission);
    }

    @Test
    @DisplayName("파일만 제출해도 제출이 완료되고 기존 첨부를 요청 순서대로 교체한다")
    void submitFilesOnlyReplacesAttachmentsInRequestedOrder() {
        FileMetadata old = file(500L, "기존.pdf", "application/pdf", 100L);
        FileMetadata first = file(502L, "사진.png", "image/png", 200L);
        FileMetadata second = file(501L, "답안.pdf", "application/pdf", 300L);
        submission.addAttachments(List.of(old));
        givenSubmit();
        when(attachmentService.prepare(USER_ID, List.of(old), List.of(502L, 501L)))
                .thenReturn(List.of(first, second));

        var response = service.submit(USER_ID, STUDY_ID, ASSIGNMENT_ID,
                new AssignmentSubmitRequest(null, null, List.of(502L, 501L)));

        assertThat(response.submissionId()).isEqualTo(SUBMISSION_ID);
        assertThat(submission.getSubmittedAt()).isEqualTo(NOW);
        assertThat(submission.getAttachments()).extracting(SubmissionAttachment::getFile)
                .containsExactly(first, second);
        assertThat(submission.getAttachments()).extracting(SubmissionAttachment::getSortOrder)
                .containsExactly(0, 1);
        assertThat(submission.getAttachments()).allSatisfy(attachment ->
                assertThat(attachment.getSubmission()).isSameAs(submission));
    }

    @Test
    @DisplayName("제출 시 첨부파일 목록을 생략하면 기존 첨부를 그대로 유지한다")
    void submitWithoutAttachmentIdsPreservesAttachments() {
        FileMetadata old = file(500L, "기존.pdf", "application/pdf", 100L);
        submission.addAttachments(List.of(old));
        SubmissionAttachment original = submission.getAttachments().getFirst();
        givenSubmit();
        when(attachmentService.prepare(USER_ID, List.of(old), null)).thenReturn(List.of(old));

        service.submit(USER_ID, STUDY_ID, ASSIGNMENT_ID, new AssignmentSubmitRequest(null, null));

        assertThat(submission.getSubmittedAt()).isEqualTo(NOW);
        assertThat(submission.getAttachments()).containsExactly(original);
    }

    @Test
    @DisplayName("제출 시 빈 첨부파일 목록을 명시하면 기존 첨부를 모두 삭제한다")
    void submitWithEmptyAttachmentIdsRemovesAttachments() {
        submission.addAttachments(List.of(file(500L, "기존.pdf", "application/pdf", 100L)));
        givenSubmit();

        service.submit(USER_ID, STUDY_ID, ASSIGNMENT_ID,
                new AssignmentSubmitRequest("본문으로 제출", null, List.of()));

        assertThat(submission.getAttachments()).isEmpty();
        assertThat(submission.getContent()).isEqualTo("본문으로 제출");
        assertThat(submission.getSubmittedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("제출물 수정은 기존 파일을 중복 없이 재정렬하고 새 파일과 함께 저장한다")
    void updateReplacesAttachmentsAndSaves() {
        FileMetadata removed = file(500L, "삭제.pdf", "application/pdf", 100L);
        FileMetadata retained = file(501L, "유지.pdf", "application/pdf", 200L);
        FileMetadata added = file(502L, "추가.png", "image/png", 300L);
        submission.submit("기존 내용", null, NOW.minusHours(1));
        submission.addAttachments(List.of(removed, retained));
        givenUpdate();
        when(attachmentService.prepare(USER_ID, List.of(removed, retained), List.of(502L, 501L)))
                .thenReturn(List.of(added, retained));

        service.updateSubmission(USER_ID, STUDY_ID, ASSIGNMENT_ID, SUBMISSION_ID,
                new AssignmentSubmitRequest("수정 내용", null, List.of(502L, 501L)));

        assertThat(submission.getAttachments()).extracting(SubmissionAttachment::getFile)
                .containsExactly(added, retained);
        assertThat(submission.getAttachments()).extracting(SubmissionAttachment::getSortOrder)
                .containsExactly(0, 1);
        assertThat(submission.getContent()).isEqualTo("수정 내용");
        assertThat(submission.getSubmittedAt()).isEqualTo(NOW.minusHours(1));
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("첨부파일만 있는 제출물도 파일 목록을 생략한 수정에서는 기존 파일로 유효성을 판단한다")
    void updateWithoutAttachmentIdsPreservesFileOnlySubmission() {
        FileMetadata old = file(500L, "답안.pdf", "application/pdf", 100L);
        submission.submit(null, null, NOW.minusHours(1));
        submission.addAttachments(List.of(old));
        SubmissionAttachment original = submission.getAttachments().getFirst();
        givenUpdate();
        when(attachmentService.prepare(USER_ID, List.of(old), null)).thenReturn(List.of(old));

        service.updateSubmission(USER_ID, STUDY_ID, ASSIGNMENT_ID, SUBMISSION_ID,
                new AssignmentSubmitRequest(null, null));

        assertThat(submission.getAttachments()).containsExactly(original);
        assertThat(submission.getSubmittedAt()).isEqualTo(NOW.minusHours(1));
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("수정 시 빈 첨부파일 목록을 명시하면 기존 첨부를 모두 삭제해서 저장한다")
    void updateWithEmptyAttachmentIdsRemovesAttachments() {
        submission.submit("기존 내용", null, NOW.minusHours(1));
        submission.addAttachments(List.of(file(500L, "기존.pdf", "application/pdf", 100L)));
        givenUpdate();

        service.updateSubmission(USER_ID, STUDY_ID, ASSIGNMENT_ID, SUBMISSION_ID,
                new AssignmentSubmitRequest(null, null, List.of()));

        assertThat(submission.getAttachments()).isEmpty();
        assertThat(submission.getContent()).isEqualTo("기존 내용");
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("기존 첨부 삭제를 반영하다 저장소 오류가 발생하면 예외를 전파하고 후속 저장을 중단한다")
    void attachmentFlushFailureStopsUpdate() {
        FileMetadata old = file(500L, "기존.pdf", "application/pdf", 100L);
        FileMetadata replacement = file(501L, "새 답안.pdf", "application/pdf", 200L);
        submission.submit("기존 내용", null, NOW.minusHours(1));
        submission.addAttachments(List.of(old));
        givenUpdate();
        when(attachmentService.prepare(USER_ID, List.of(old), List.of(501L)))
                .thenReturn(List.of(replacement));
        var failure = new DataAccessResourceFailureException("첨부파일 변경 반영 실패");
        doThrow(failure).when(submissionRepository).flush();

        assertThatThrownBy(() -> service.updateSubmission(USER_ID, STUDY_ID, ASSIGNMENT_ID, SUBMISSION_ID,
                new AssignmentSubmitRequest("수정 내용", null, List.of(501L))))
                .isSameAs(failure);
        verify(submissionRepository, never()).save(submission);
    }

    @Test
    @DisplayName("최초 제출 알림은 확인된 리더에게만 보내고 본인과 탈퇴 또는 교체된 리더는 제외한다")
    void notifyOnlyConfirmedLeadersOtherThanSubmitter() {
        actor = member(MEMBER_ID, USER_ID, StudyMemberRole.LEADER, "제출한 리더");
        submission = submission(SUBMISSION_ID, actor, assignment);
        StudyMember leader = member(30L, 3L, StudyMemberRole.LEADER, "현재 리더");
        StudyMember departed = member(40L, 4L, StudyMemberRole.LEADER, "탈퇴한 리더");
        StudyMember replaced = member(50L, 5L, StudyMemberRole.LEADER, "사용자가 바뀐 리더");
        givenSubmit();
        when(memberRepository.findIdentitiesByStudyId(STUDY_ID)).thenReturn(List.of(
                new StudyMemberIdentityProjection(MEMBER_ID, USER_ID),
                new StudyMemberIdentityProjection(30L, 3L),
                new StudyMemberIdentityProjection(50L, 6L)));
        when(memberRepository.findAllByStudyIdAndRole(STUDY_ID, StudyMemberRole.LEADER))
                .thenReturn(List.of(actor, departed, replaced, leader));

        service.submit(USER_ID, STUDY_ID, ASSIGNMENT_ID, new AssignmentSubmitRequest("제출 내용", null));

        verify(notificationService).createAssignmentSubmissionSubmittedEventNotifications(submission, List.of(leader));
    }

    @Test
    @DisplayName("배정된 제출물이 없으면 미배정 상태와 빈 첨부 목록을 반환한다")
    void getMySubmissionWhenNotAssigned() {
        givenRead();
        when(submissionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
                .thenReturn(Optional.empty());

        var response = service.getMySubmissionDetail(USER_ID, STUDY_ID, ASSIGNMENT_ID);

        assertThat(response).isEqualTo(new MySubmissionDetailResponse(
                null, SubmissionStatus.NOT_ASSIGNED, null, null, null, List.of(), false));
        verifyNoInteractions(fileUrlGenerator);
    }

    @Test
    @DisplayName("내 제출물 상세는 제출 상태와 본문 및 순서가 있는 첨부파일 정보를 반환한다")
    void getMySubmissionWithAttachments() {
        givenRead();
        submission.submit("내 답안", "https://answer.example.com", NOW.minusHours(1));
        List<AssignmentAttachmentResponse> expectedAttachments = givenAttachmentsWithUrls();
        when(submissionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
                .thenReturn(Optional.of(submission));

        var response = service.getMySubmissionDetail(USER_ID, STUDY_ID, ASSIGNMENT_ID);

        assertThat(response).isEqualTo(new MySubmissionDetailResponse(
                SUBMISSION_ID, SubmissionStatus.SUBMITTED, NOW.minusHours(1), "내 답안",
                "https://answer.example.com", expectedAttachments, true));
    }

    @Test
    @DisplayName("제출물 상세는 제출자 프로필과 제출 시각 및 다운로드 가능한 첨부파일을 반환한다")
    void getSubmissionDetailWithProfileAndAttachments() {
        givenRead();
        submission.submit("상세 답안", "https://answer.example.com", NOW.minusHours(2));
        List<AssignmentAttachmentResponse> expectedAttachments = givenAttachmentsWithUrls();
        when(submissionRepository.getByIdAndAssignmentIdOrThrow(SUBMISSION_ID, ASSIGNMENT_ID))
                .thenReturn(submission);
        when(profileImageUrlResolver.resolveForMembers(List.of(MEMBER_ID)))
                .thenReturn(Map.of(MEMBER_ID, "https://profile.example.com/submitter.png"));

        var response = service.getSubmissionDetail(USER_ID, STUDY_ID, ASSIGNMENT_ID, SUBMISSION_ID);

        assertThat(response).isEqualTo(new SubmissionDetailResponse(
                SUBMISSION_ID, "제출자", "https://profile.example.com/submitter.png", NOW.minusHours(2),
                "상세 답안", "https://answer.example.com", expectedAttachments));
    }

    @Test
    @DisplayName("제출 목록은 각 제출자의 회원 식별자로 프로필을 찾고 제출 상태와 시각을 반환한다")
    void getSubmissionListWithDistinctProfilesAndStatuses() {
        actor = member(MEMBER_ID, USER_ID, StudyMemberRole.LEADER, "제출자");
        submission = submission(SUBMISSION_ID, actor, assignment);
        submission.submit("정시 답안", null, NOW.minusDays(1));
        StudyMember other = member(21L, 2L, StudyMemberRole.MEMBER, "다른 제출자");
        Assignment closed = AssignmentTestFixture.assignmentWithId(ASSIGNMENT_ID, STUDY_ID, NOW.minusDays(10));
        AssignmentSubmission late = submission(301L, other, closed);
        late.submit("지각 답안", null, NOW.minusDays(2));
        givenRead();
        when(submissionRepository.findAllByAssignmentIdAndSubmittedAtIsNotNull(ASSIGNMENT_ID))
                .thenReturn(List.of(submission, late));
        when(profileImageUrlResolver.resolveForMembers(List.of(MEMBER_ID, 21L)))
                .thenReturn(Map.of(MEMBER_ID, "https://profile.example.com/first.png",
                        21L, "https://profile.example.com/second.png"));

        var response = service.getSubmissionList(USER_ID, STUDY_ID, ASSIGNMENT_ID);

        assertThat(response).isNotNull();
        assertThat(response.submissions()).containsExactly(
                new SubmissionSummary(SUBMISSION_ID, "제출자", "https://profile.example.com/first.png",
                        SubmissionStatus.SUBMITTED, NOW.minusDays(1)),
                new SubmissionSummary(301L, "다른 제출자", "https://profile.example.com/second.png",
                        SubmissionStatus.LATE_SUBMITTED, NOW.minusDays(2)));
    }

    private void givenSubmit() {
        when(memberRepository.getByStudyIdAndUserIdOrThrow(STUDY_ID, USER_ID)).thenReturn(actor);
        when(assignmentRepository.getByIdAndStudyIdForUpdateOrThrow(ASSIGNMENT_ID, STUDY_ID))
                .thenReturn(assignment);
        when(submissionRepository.getWithLockByAssignmentIdAndMemberIdOrThrow(ASSIGNMENT_ID, MEMBER_ID))
                .thenReturn(submission);
    }

    private void givenUpdate() {
        when(memberRepository.getByStudyIdAndUserIdOrThrow(STUDY_ID, USER_ID)).thenReturn(actor);
        when(assignmentRepository.getByIdAndStudyIdForUpdateOrThrow(ASSIGNMENT_ID, STUDY_ID))
                .thenReturn(assignment);
        when(submissionRepository.getByIdAndAssignmentIdForUpdateOrThrow(SUBMISSION_ID, ASSIGNMENT_ID))
                .thenReturn(submission);
    }

    private void givenRead() {
        when(memberRepository.getByStudyIdAndUserIdOrThrow(STUDY_ID, USER_ID)).thenReturn(actor);
        when(assignmentRepository.getByIdAndStudyIdOrThrow(ASSIGNMENT_ID, STUDY_ID)).thenReturn(assignment);
    }

    private List<AssignmentAttachmentResponse> givenAttachmentsWithUrls() {
        submission.addAttachments(List.of(
                file(501L, "답안.pdf", "application/pdf", 300L),
                file(502L, "사진.png", "image/png", 200L)));
        when(fileUrlGenerator.generateDownloadUrl("assignment/501", "답안.pdf"))
                .thenReturn("https://download.example.com/501");
        when(fileUrlGenerator.generateImageUrl("assignment/501", "application/pdf"))
                .thenReturn(null);
        when(fileUrlGenerator.generateImageUrl("assignment/502", "image/png"))
                .thenReturn("https://image.example.com/502");
        when(fileUrlGenerator.generateDownloadUrl("assignment/502", "사진.png"))
                .thenReturn("https://download.example.com/502");
        return List.of(
                new AssignmentAttachmentResponse(501L, "답안.pdf", "application/pdf", 300L, 0,
                        null, "https://download.example.com/501"),
                new AssignmentAttachmentResponse(502L, "사진.png", "image/png", 200L, 1,
                        "https://image.example.com/502", "https://download.example.com/502"));
    }

    private FileMetadata file(Long id, String name, String contentType, long size) {
        FileMetadata file = FileMetadata.create(actor.getUser(), name, "assignment/" + id, contentType, size);
        ReflectionTestUtils.setField(file, "id", id);
        file.completeUpload();
        return file;
    }

    private StudyMember member(Long memberId, Long userId, StudyMemberRole role, String name) {
        User user = User.create(name, null);
        ReflectionTestUtils.setField(user, "id", userId);
        StudyMember member = StudyMember.create(assignment.getStudy(), user, name, null, role);
        ReflectionTestUtils.setField(member, "id", memberId);
        return member;
    }

    private AssignmentSubmission submission(Long id, StudyMember member, Assignment assignment) {
        AssignmentSubmission result = AssignmentSubmission.create(member, assignment);
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }
}
