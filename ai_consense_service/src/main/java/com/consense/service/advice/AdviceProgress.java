package com.consense.service.advice;

import com.consense.web.dto.AdviceDtos.CitationVO;
import java.util.List;

/** Request-local observations and cancellation; no model reasoning is reported as an observed phase. */
public interface AdviceProgress {
    AdviceProgress NONE = new AdviceProgress() { };
    default void phase(String phase) { }
    default void section(String content, List<String> citations, List<CitationVO> sources) { }
    default void checkCancelled() { }
    default void onCancel(Runnable cancel) { }
    default boolean streaming() { return false; }
}
