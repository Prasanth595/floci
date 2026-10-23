package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@ApplicationScoped
class RedshiftDataIdempotencyStore {
    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    private final AccountAwareStorageBackend<Receipt> receipts;
    private final Clock clock;

    @Inject
    RedshiftDataIdempotencyStore(StorageFactory factory) {
        this(factory.create("redshift-data", "idempotency.json", new TypeReference<Map<String, Receipt>>() { }),
                Clock.systemUTC());
    }

    RedshiftDataIdempotencyStore(AccountAwareStorageBackend<Receipt> receipts, Clock clock) {
        this.receipts = receipts;
        this.clock = clock;
    }

    static String clientToken(JsonNode request) {
        JsonNode token = request.get("ClientToken");
        if (token == null) {
            return null;
        }
        if (!token.isTextual() || token.textValue().isEmpty() || token.textValue().length() > 64) {
            throw new AwsException("ValidationException", "ClientToken must be a string between 1 and 64 characters", 400);
        }
        return token.textValue();
    }

    synchronized Receipt find(Scope scope) {
        Receipt receipt = receipts.getForAccount(scope.accountId(), scope.key()).orElse(null);
        if (receipt != null && !clock.instant().isBefore(receipt.expiresAt)) {
            receipts.deleteForAccount(scope.accountId(), scope.key());
            return null;
        }
        return receipt == null ? null : receipt.snapshot();
    }

    synchronized void put(Scope scope, ObjectNode response) {
        Receipt receipt = new Receipt();
        receipt.acceptedAt = clock.instant();
        receipt.expiresAt = receipt.acceptedAt.plus(TOKEN_TTL);
        receipt.response = response.deepCopy();
        receipts.putAllAtomicallyForAccount(scope.accountId(), Map.of(scope.key(), receipt));
    }

    synchronized void removeIfStatement(Scope scope, String statementId) {
        Receipt receipt = receipts.getForAccount(scope.accountId(), scope.key()).orElse(null);
        if (receipt != null && statementId.equals(receipt.response.path("Id").asText())) {
            receipts.deleteForAccount(scope.accountId(), scope.key());
            receipts.flush();
        }
    }

    synchronized void clear() {
        receipts.clear();
        receipts.flush();
    }

    synchronized void sweep() {
        Instant now = clock.instant();
        for (AccountAwareStorageBackend.AccountEntry<Receipt> entry : receipts.scanAllAccountEntries(key -> true)) {
            if (!now.isBefore(entry.value().expiresAt)) {
                receipts.deleteForAccount(entry.accountId(), entry.key());
            }
        }
    }

    record Scope(String accountId, String region, String operation, String token) {
        String key() {
            return encode(region) + "." + encode(operation) + "." + encode(token);
        }

        private static String encode(String value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    @RegisterForReflection
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static final class Receipt {
        Instant acceptedAt;
        Instant expiresAt;
        ObjectNode response;

        Receipt snapshot() {
            Receipt copy = new Receipt();
            copy.acceptedAt = acceptedAt;
            copy.expiresAt = expiresAt;
            copy.response = response.deepCopy();
            return copy;
        }
    }
}
