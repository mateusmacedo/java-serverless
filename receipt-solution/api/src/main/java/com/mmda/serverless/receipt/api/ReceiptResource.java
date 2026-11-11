package com.mmda.serverless.receipt.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.UncheckedIOException;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Consulta e emissão manual de comprovantes. A emissão inicia a mesma máquina de estado que o
 * evento {@code RequestCompleted} inicia, com a entrada no mesmo formato.
 */
@Path("/receipts")
public class ReceiptResource {

    private final DynamoDbClient dynamoDb;
    private final SfnClient sfn;
    private final ObjectMapper objectMapper;
    private final ReceiptApiConfig config;

    ReceiptResource(DynamoDbClient dynamoDb, SfnClient sfn, ObjectMapper objectMapper, ReceiptApiConfig config) {
        this.dynamoDb = dynamoDb;
        this.sfn = sfn;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    @GET
    @Path("/{requestId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response find(@PathParam("requestId") String requestId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(config.tableName())
                .key(Map.of("requestId", AttributeValue.fromS(requestId)))
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Receipt.fromItem(item)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response issue(@Valid @NotNull IssueReceiptRequest request) {
        // WHY: a máquina lê a entrada com InputPath $.detail, o formato do evento do EventBridge
        StartExecutionResponse execution = sfn.startExecution(StartExecutionRequest.builder()
                .stateMachineArn(config.stateMachineArn())
                .input(toJson(Map.of("detail", request)))
                .build());
        return Response.accepted(new IssueAccepted(request.requestId(), execution.executionArn())).build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public record IssueAccepted(String requestId, String executionArn) {
    }
}
