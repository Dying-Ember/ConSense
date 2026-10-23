package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class CanonicalBindingFrameComparisonTest {
    @Test void everyStoredEntityFieldStillRejectsChangesAtBothFreshReadGuards()throws Exception {
        Fixture fixture=new Fixture("{}");fixture.service.assertFrameSourcesCurrent(fixture.frame);
        int checked=0;
        for(Field field:CanonicalSourceBinding.class.getDeclaredFields()){
            if(Modifier.isStatic(field.getModifiers())||field.isSynthetic())continue;
            field.setAccessible(true);CanonicalSourceBinding changed=copy(fixture.original);Object value=field.get(changed);
            field.set(changed,value instanceof String?value+"-changed":value instanceof Long?((Long)value)+1:((Instant)value).plusNanos(1));
            fixture.current.set(changed);
            assertChanged(()->fixture.service.assertFrameSourcesCurrent(fixture.frame),field.getName());
            assertChanged(()->fixture.service.revalidateFrame(fixture.frame),field.getName());checked++;
            field.set(changed,null);
            assertChanged(()->fixture.service.assertFrameSourcesCurrent(fixture.frame),field.getName()+" null");
            assertChanged(()->fixture.service.revalidateFrame(fixture.frame),field.getName()+" null");
        }
        assertEquals(20,checked,"Revisit the complete binding comparison when the stored entity changes");
        fixture.current.set(copy(fixture.original));fixture.service.assertFrameSourcesCurrent(fixture.frame);
        verify(fixture.repository,times(checked*4+2)).findByProjectIdAndDocumentIdOrderByIdDesc("frame-project",22L,org.springframework.data.domain.PageRequest.of(0,1));
        verifyNoInteractions(fixture.backend);
    }

    @Test void freshGuardDoesNotSerializeTheRetainedLargeSnapshotAgain()throws Exception {
        java.lang.management.ThreadMXBean bean=ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocation=(com.sun.management.ThreadMXBean)bean;
        assumeTrue(allocation.isThreadAllocatedMemorySupported());
        if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        char[] text=new char[256*1024];Arrays.fill(text,'x');
        Fixture fixture=new Fixture("{\"text\":\""+new String(text)+"\"}");
        fixture.service.assertFrameSourcesCurrent(fixture.frame);
        long before=allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
        fixture.service.assertFrameSourcesCurrent(fixture.frame);
        long bytes=allocation.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        assertTrue(bytes<512*1024,"A scalar binding comparison allocated "+bytes+" bytes for a retained snapshot");
        verify(fixture.reader,times(2)).assertCurrent(any(CanonicalSourceBinding.class),eq("vetting"));
        verify(fixture.repository,times(2)).findByProjectIdAndDocumentIdOrderByIdDesc("frame-project",22L,org.springframework.data.domain.PageRequest.of(0,1));
        verifyNoInteractions(fixture.backend);
    }

    private static void assertChanged(org.junit.jupiter.api.function.Executable action,String field){
        CanonicalEvidenceException error=assertThrows(CanonicalEvidenceException.class,action,field);
        assertEquals("CANONICAL_FRAME_BINDING_CHANGED",error.getMessage(),field);assertEquals(HttpStatus.CONFLICT,error.getStatus(),field);
    }
    private static CanonicalSourceBinding copy(CanonicalSourceBinding value){CanonicalSourceBinding out=new CanonicalSourceBinding();BeanUtils.copyProperties(value,out);return out;}

    private static final class Fixture {
        final CanonicalSourceBinding original=new CanonicalSourceBinding();
        final AtomicReference<CanonicalSourceBinding> current=new AtomicReference<>();
        final CanonicalSourceBindingRepository repository=mock(CanonicalSourceBindingRepository.class);
        final CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);
        final CanonicalCurrentSourceReader reader=mock(CanonicalCurrentSourceReader.class);
        final CanonicalEvidenceService service=new CanonicalEvidenceService(repository,backend,reader);
        final CanonicalEvidenceService.VerifiedBindingFrame frame;
        Fixture(String raw)throws Exception {
            for(Field field:CanonicalSourceBinding.class.getDeclaredFields()){
                if(Modifier.isStatic(field.getModifiers())||field.isSynthetic())continue;
                field.setAccessible(true);field.set(original,field.getType()==String.class?field.getName():field.getType()==Long.class?11L:Instant.parse("2026-10-22T00:00:00Z"));
            }
            original.setProjectId("frame-project");original.setDocumentId(22L);original.setDeclaredModule("vetting");original.setSnapshotJson(raw);
            current.set(copy(original));when(repository.findByProjectIdAndDocumentIdOrderByIdDesc("frame-project",22L,org.springframework.data.domain.PageRequest.of(0,1))).thenAnswer(call->Collections.singletonList(current.get()));
            Class<?> type=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");
            Constructor<?> view=type.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
            Constructor<?> constructor=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);constructor.setAccessible(true);
            frame=(CanonicalEvidenceService.VerifiedBindingFrame)constructor.newInstance(service,service,"frame-project","vetting",Collections.singletonList(view.newInstance(original,JsonUtils.parse("{}"),false)));
        }
    }
}
