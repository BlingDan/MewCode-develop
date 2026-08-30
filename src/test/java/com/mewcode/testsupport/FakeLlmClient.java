package com.mewcode.testsupport;

import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 测试用的 Provider 替身，记录结构化请求并按顺序返回预置流。 */
public final class FakeLlmClient implements LlmClient {

    private final ArrayDeque<FakeCancellableLlmStream> responses = new ArrayDeque<>();
    private final List<PromptRequest> requests = new ArrayList<>();

    /** 追加一次结构化请求对应的 Provider 流。 */
    public synchronized void enqueue(StreamEvent... events) {
        responses.addLast(new FakeCancellableLlmStream(List.of(events)));
    }

    @Override
    public synchronized CancellableLlmStream openStream(PromptRequest request) {
        requests.add(request);
        if (responses.isEmpty()) {
            throw new AssertionError("No fake Provider response remains.");
        }
        return responses.removeFirst().open();
    }

    public synchronized List<PromptRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized int requestCount() {
        return requests.size();
    }
}
