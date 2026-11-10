package com.mmda.serverless.sample.functions;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Lê o limite de tamanho (SSM), a chave de assinatura (Secrets Manager) e o payload (S3);
 * rejeita payload vazio ou acima do limite e assina o conteúdo com HMAC.
 */
@Component("validate")
class ValidateFunction implements Function<RequestContext, RequestContext> {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final S3Client s3;
    private final SsmClient ssm;
    private final SecretsManagerClient secretsManager;
    private final FunctionsProperties properties;

    ValidateFunction(S3Client s3, SsmClient ssm, SecretsManagerClient secretsManager,
                     FunctionsProperties properties) {
        this.s3 = s3;
        this.ssm = ssm;
        this.secretsManager = secretsManager;
        this.properties = properties;
    }

    @Override
    public RequestContext apply(RequestContext request) {
        long maxPayloadBytes = Long.parseLong(ssm
                .getParameter(builder -> builder.name(properties.maxPayloadParameter()))
                .parameter()
                .value());
        String signingKey = secretsManager
                .getSecretValue(builder -> builder.secretId(properties.signingSecretId()))
                .secretString();
        byte[] content = s3
                .getObjectAsBytes(builder -> builder.bucket(properties.bucketName()).key(request.s3Key()))
                .asByteArray();

        if (content.length == 0) {
            throw new InvalidRequestException("Payload of request %s is empty".formatted(request.requestId()));
        }
        if (content.length > maxPayloadBytes) {
            throw new InvalidRequestException("Payload of %d bytes exceeds the limit of %d bytes"
                    .formatted(content.length, maxPayloadBytes));
        }
        return request.validated(sign(signingKey, content), content.length);
    }

    private static String sign(String key, byte[] content) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(content));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not sign the payload", exception);
        }
    }
}
