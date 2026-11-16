package withoutc.chongchong.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import withoutc.chongchong.assignment.controller.dto.AssignmentCreateRequest;
import withoutc.chongchong.assignment.controller.dto.AssignmentUpdateRequest;
import withoutc.chongchong.assignment.entity.Assignment;
import withoutc.chongchong.assignment.entity.AssignmentSubmission;
import withoutc.chongchong.assignment.entity.SubmissionTarget;
import withoutc.chongchong.assignment.entity.SubmissionVisibility;
import withoutc.chongchong.assignment.exception.AssignmentErrorCode;
import withoutc.chongchong.assignment.exception.AssignmentException;
import withoutc.chongchong.assignment.policy.AssignmentAccessPolicy;
import withoutc.chongchong.assignment.repository.AssignmentRepository;
import withoutc.chongchong.assignment.repository.AssignmentSubmissionRepository;
import withoutc.chongchong.assignment.support.AssignmentTestFixture;
import withoutc.chongchong.comment.repository.CommentRepository;
import withoutc.chongchong.file.service.FileUrlGenerator;
import withoutc.chongchong.notification.service.NotificationService;
import withoutc.chongchong.study.entity.Study;
import withoutc.chongchong.study.entity.StudyMember;
import withoutc.chongchong.study.entity.StudyMemberRole;
import withoutc.chongchong.study.repository.StudyMemberRepository;
import withoutc.chongchong.study.repository.StudyRepository;
import withoutc.chongchong.study.repository.projection.StudyMemberIdentityProjection;
import withoutc.chongchong.user.entity.User;
import withoutc.chongchong.user.repository.UserRepository;
import withoutc.chongchong.user.service.UserProfileImageUrlResolver;

@DisplayName("과제 서비스의 제출 대상 후보 검증")
@ExtendWith(MockitoExtension.class)
class AssignmentServiceMembershipTest {

    private static final Long USER_ID = 1L;
    private static final Long STUDY_ID = 10L;
    private static final Long ASSIGNMENT_ID = 100L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 10, 0);

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentSubmissionRepository assignmentSubmissionRepository;
    @Mock
    private StudyMemberRepository studyMemberRepository;
    @Mock
    private StudyRepository studyRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private CommentRepository commentRepository;

    private AssignmentService assignmentService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        assignmentService = new AssignmentService(
                assignmentRepository, assignmentSubmissionRepository, studyMemberRepository, studyRepository,
                notificationService, mock(AssignmentAttachmentService.class), mock(FileUrlGenerator.class),
                mock(UserRepository.class), commentRepository, clock, new AssignmentAccessPolicy(),
                mock(UserProfileImageUrlResolver.class));
    }

    @ParameterizedTest
    @CsvSource({"22, 3", "21, 4", "22, 4"})
    @DisplayName("과제 생성 시 후보의 멤버 ID와 사용자 ID가 모두 일치하는 대상에게만 제출 정보와 알림을 만든다")
    void createExcludesMembersThatDoNotMatchCandidates(long candidateMemberId, long candidateUserId) {
        Study study = Study.create("스터디", "설명", false);
        StudyMember leader = member(study, 11L, USER_ID, StudyMemberRole.LEADER);
        StudyMember retained = member(study, 20L, 2L, StudyMemberRole.MEMBER);
        StudyMember newcomer = member(study, 21L, 3L, StudyMemberRole.MEMBER);
        when(studyMemberRepository.getByStudyIdAndUserIdOrThrow(STUDY_ID, USER_ID)).thenReturn(leader);
        when(studyRepository.getByIdForShareOrThrow(STUDY_ID)).thenReturn(study);
        when(studyMemberRepository.findIdentitiesByStudyId(STUDY_ID)).thenReturn(List.of(
                new StudyMemberIdentityProjection(retained.getId(), retained.getUser().getId()),
                new StudyMemberIdentityProjection(candidateMemberId, candidateUserId)));
        when(studyMemberRepository.findAllByStudyId(STUDY_ID)).thenReturn(List.of(leader, retained, newcomer));

        assignmentService.create(USER_ID, STUDY_ID, createRequest());

        ArgumentCaptor<Assignment> saved = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(saved.capture());
        assertThat(saved.getValue().getSubmitterIds()).containsExactly(retained.getId());
        assertThat(saved.getValue().getSubmissions()).extracting(AssignmentSubmission::getMember)
                .containsExactly(retained);
        verify(notificationService).createAssignmentCreatedEventNotifications(
                saved.getValue(), List.of(retained), USER_ID);
    }

    @ParameterizedTest
    @CsvSource({"22, 3", "21, 4", "22, 4"})
    @DisplayName("과제 수정 시 후보와 일치하지 않는 제출 대상을 지정하면 거부하고 기존 과제와 대상을 유지한다")
    void rejectSubmitterThatDoesNotMatchCandidates(long candidateMemberId, long candidateUserId) {
        Assignment assignment = AssignmentTestFixture.assignmentWithId(ASSIGNMENT_ID, STUDY_ID, NOW);
        Study study = assignment.getStudy();
        StudyMember leader = member(study, 11L, USER_ID, StudyMemberRole.LEADER);
        StudyMember retained = member(study, 20L, 2L, StudyMemberRole.MEMBER);
        StudyMember newcomer = member(study, 21L, 3L, StudyMemberRole.MEMBER);
        assignment.replaceSubmitters(List.of(retained));
        String originalTitle = assignment.getTitle();
        AssignmentUpdateRequest request = new AssignmentUpdateRequest("수정 제목", null, null, null,
                null, null, null, null, List.of(newcomer.getId()));
        when(studyMemberRepository.getByStudyIdAndUserIdOrThrow(STUDY_ID, USER_ID)).thenReturn(leader);
        when(assignmentRepository.getByIdAndStudyIdForUpdateOrThrow(ASSIGNMENT_ID, STUDY_ID))
                .thenReturn(assignment);
        when(studyMemberRepository.findIdentitiesByStudyId(STUDY_ID)).thenReturn(List.of(
                new StudyMemberIdentityProjection(candidateMemberId, candidateUserId)));
        when(studyMemberRepository.findAllByStudyId(STUDY_ID)).thenReturn(List.of(leader, retained, newcomer));

        assertThatThrownBy(() -> assignmentService.update(USER_ID, STUDY_ID, ASSIGNMENT_ID, request))
                .isInstanceOf(AssignmentException.class)
                .extracting(exception -> ((AssignmentException) exception).getErrorCode())
                .isEqualTo(AssignmentErrorCode.INVALID_SUBMITTER_IDS);
        assertThat(assignment.getTitle()).isEqualTo(originalTitle);
        assertThat(assignment.getSubmitterIds()).containsExactly(retained.getId());
        assertThat(assignment.getSubmissions()).singleElement()
                .satisfies(submission -> assertThat(submission.isAssigned()).isTrue());
        verify(assignmentRepository, never()).save(any(Assignment.class));
        verifyNoInteractions(notificationService);
    }

    private AssignmentCreateRequest createRequest() {
        return new AssignmentCreateRequest("과제 제목", "과제 내용", "링크 제출", SubmissionTarget.MEMBERS_ONLY,
                SubmissionVisibility.LEADER_ONLY, NOW.plusDays(7), List.of());
    }

    private StudyMember member(Study study, Long memberId, Long userId, StudyMemberRole role) {
        User user = User.create("사용자", null);
        ReflectionTestUtils.setField(user, "id", userId);
        StudyMember member = StudyMember.create(study, user, "스터디원", null, role);
        ReflectionTestUtils.setField(member, "id", memberId);
        return member;
    }

}
