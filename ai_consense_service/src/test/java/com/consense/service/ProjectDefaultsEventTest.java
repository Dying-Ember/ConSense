package com.consense.service;
import com.consense.repository.*;
import com.consense.domain.Project;
import com.consense.web.dto.ProjectDtos.ProjectRequest;
import com.consense.service.drafting.DraftPackagedTemplates;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.mockito.Mockito.*;

class ProjectDefaultsEventTest {
    @Test void onlyNewProjectsRequestPackagedTemplates() {
        ProjectRepository projects=mock(ProjectRepository.class);
        ProjectService s=new ProjectService(projects,mock(SourceDocumentRepository.class),mock(DraftVariableRepository.class),mock(DraftDocumentRepository.class),mock(VettingFindingRepository.class),mock(ChatMessageRepository.class),mock(EvidenceChunkRepository.class));
        ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);ReflectionTestUtils.setField(s,"projectEvents",events);
        ProjectRequest req=new ProjectRequest();req.setId("new-id");req.setNameZhHans("New");
        when(projects.findById("new-id")).thenReturn(Optional.empty());s.save(req);
        verify(events).publishEvent(any(DraftPackagedTemplates.ProjectCreated.class));
        reset(events);Project old=new Project();old.setId("new-id");when(projects.findById("new-id")).thenReturn(Optional.of(old));s.save(req);
        verifyNoInteractions(events);
    }
}
