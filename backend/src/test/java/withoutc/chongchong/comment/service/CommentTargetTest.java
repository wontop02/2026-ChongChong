package withoutc.chongchong.comment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import withoutc.chongchong.notification.entity.ResourceType;

class CommentTargetTest {

    @Test
    @DisplayName("공지 댓글 대상은 공지 id를 리소스로 가지고 과제 id는 없다")
    void notice() {
        CommentTarget target = CommentTarget.notice(20L);

        assertThat(target.resourceType()).isEqualTo(ResourceType.NOTICE);
        assertThat(target.resourceId()).isEqualTo(20L);
        assertThat(target.assignmentId()).isNull();
    }

    @Test
    @DisplayName("과제 댓글 대상은 과제 id를 리소스로 가지고 상위 과제 id는 따로 두지 않는다")
    void assignment() {
        CommentTarget target = CommentTarget.assignment(20L);

        assertThat(target.resourceType()).isEqualTo(ResourceType.ASSIGNMENT);
        assertThat(target.resourceId()).isEqualTo(20L);
        assertThat(target.assignmentId()).isNull();
    }

    @Test
    @DisplayName("제출물 댓글 대상은 제출물 id를 리소스로, 소속 과제 id를 따로 가진다")
    void submission() {
        CommentTarget target = CommentTarget.submission(20L, 30L);

        assertThat(target.resourceType()).isEqualTo(ResourceType.ASSIGNMENT_SUBMISSION);
        assertThat(target.resourceId()).isEqualTo(30L);
        assertThat(target.assignmentId()).isEqualTo(20L);
    }
}
