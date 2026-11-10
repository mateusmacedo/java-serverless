package com.mmda.serverless.sample.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@ExtendWith(MockitoExtension.class)
// WHY: a sobrecarga do SDK com Consumer repassa para a sobrecarga com request, que é a simulada;
// no modo estrito o Mockito trata essa chamada como argumento divergente
@MockitoSettings(strictness = Strictness.LENIENT)
class ReprocessFunctionTest {

    private static final String QUEUE_URL = "http://floci:4566/000000000000/dead-letter-queue";

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private SqsClient sqs;

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private SfnClient sfn;

    private final ObjectMapper objectMapper = TestFixtures.objectMapper();

    private ReprocessFunction function;

    @BeforeEach
    void setUp() {
        function = new ReprocessFunction(sqs, sfn, objectMapper, TestFixtures.PROPERTIES);
        doReturn(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build())
                .when(sqs).getQueueUrl(any(GetQueueUrlRequest.class));
        lenient().doReturn(DeleteMessageResponse.builder().build())
                .when(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void restartsAMessageBelowTheAttemptLimit() throws Exception {
        givenMessages(deadLetter("receipt-1", 1));
        doReturn(StartExecutionResponse.builder().executionArn("arn:execution").build())
                .when(sfn).startExecution(any(StartExecutionRequest.class));

        ReprocessResult result = function.apply(new ReprocessCommand(10));

        assertThat(result).isEqualTo(new ReprocessResult(1, 0));
        ArgumentCaptor<StartExecutionRequest> captor = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfn).startExecution(captor.capture());
        StartExecutionRequest start = captor.getValue();
        assertThat(start.stateMachineArn()).isEqualTo(TestFixtures.PROPERTIES.stateMachineArn());
        assertThat(start.name()).isEqualTo("req-1-2");
        assertThat(objectMapper.readValue(start.input(), RequestContext.class))
                .isEqualTo(new RequestContext("req-1", "requests/req-1.json", 2, null, null));
        verifyDeleted("receipt-1");
    }

    @Test
    void discardsAMessageThatReachedTheAttemptLimit() {
        givenMessages(deadLetter("receipt-1", 3));

        ReprocessResult result = function.apply(new ReprocessCommand(10));

        assertThat(result).isEqualTo(new ReprocessResult(0, 1));
        verify(sfn, never()).startExecution(any(StartExecutionRequest.class));
        verifyDeleted("receipt-1");
    }

    @Test
    void handlesEveryMessageOfTheBatch() {
        givenMessages(deadLetter("receipt-1", 1), deadLetter("receipt-2", 3));
        doReturn(StartExecutionResponse.builder().executionArn("arn:execution").build())
                .when(sfn).startExecution(any(StartExecutionRequest.class));

        ReprocessResult result = function.apply(new ReprocessCommand(null));

        assertThat(result).isEqualTo(new ReprocessResult(1, 1));
        verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void readsTheConfiguredQueueWithTheRequestedBatchSize() {
        givenMessages();

        ReprocessResult result = function.apply(new ReprocessCommand(4));

        assertThat(result).isEqualTo(new ReprocessResult(0, 0));
        ArgumentCaptor<GetQueueUrlRequest> queue = ArgumentCaptor.forClass(GetQueueUrlRequest.class);
        verify(sqs).getQueueUrl(queue.capture());
        assertThat(queue.getValue().queueName()).isEqualTo("dead-letter-queue");
        ArgumentCaptor<ReceiveMessageRequest> receive = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs).receiveMessage(receive.capture());
        assertThat(receive.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(receive.getValue().maxNumberOfMessages()).isEqualTo(4);
    }

    private void givenMessages(Message... messages) {
        doReturn(ReceiveMessageResponse.builder().messages(List.of(messages)).build())
                .when(sqs).receiveMessage(any(ReceiveMessageRequest.class));
    }

    private void verifyDeleted(String receiptHandle) {
        ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs).deleteMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(captor.getValue().receiptHandle()).isEqualTo(receiptHandle);
    }

    /** Corpo no formato enviado pelo estado SendToDeadLetterQueue: entrada do estado mais o erro. */
    private static Message deadLetter(String receiptHandle, int attempt) {
        String body = """
                {"requestId":"req-1","s3Key":"requests/req-1.json","attempt":%d,
                 "error":{"Error":"com.mmda.serverless.sample.functions.InvalidRequestException","Cause":"{}"}}
                """.formatted(attempt);
        return Message.builder().receiptHandle(receiptHandle).body(body).build();
    }
}
