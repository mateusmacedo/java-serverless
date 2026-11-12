package com.mmda.serverless.quote.functions;

import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.QuoteKeys;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Ciclo de vida das ocorrências da quote (M5): revisa as do índice {@code ISSUE_OPEN} e resolve as que sumiram. Só
 * a {@code STUCK_VERSION} entra nele, e some quando a versão sai do {@code ACTIVE_VERSION} (§3 e §9 do desenho). Uma
 * ocorrência que segue aberta continua no índice; o próximo ciclo do {@code reconciler} a revisa de novo.
 */
@ApplicationScoped
public class IssueReviewer {

    private static final Logger LOG = Logger.getLogger(IssueReviewer.class);

    private final QuoteTable table;
    private final QuoteFunctionsConfig config;

    IssueReviewer(QuoteTable table, QuoteFunctionsConfig config) {
        this.table = table;
        this.config = config;
    }

    /** Contadores: {@code open} (seguem abertas) e {@code resolved}. A falha de uma fica no log e não para as outras. */
    Map<String, Integer> review(Instant now) {
        Map<String, Integer> report = new LinkedHashMap<>(Map.of("open", 0, "resolved", 0));
        for (Map<String, AttributeValue> indexed : table.indexed(QuoteKeys.ISSUE_OPEN, config.reconcilerBatchSize())) {
            String issueId = Attr.string(indexed, "issueId");
            try {
                report.merge(reviewIssue(issueId, now) ? "resolved" : "open", 1, Integer::sum);
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "reconciler could not review issueId=%s", issueId);
            }
        }
        return report;
    }

    private boolean reviewIssue(String issueId, Instant now) {
        // WHY: o índice é eventualmente consistente; a leitura da tabela confirma que a ocorrência segue aberta
        Optional<Map<String, AttributeValue>> found = table.get(QuoteKeys.issuePk(issueId), QuoteKeys.META);
        if (found.isEmpty() || !QuoteIssues.Status.OPEN.name().equals(Attr.string(found.get(), "status"))) {
            return false;
        }
        Map<String, AttributeValue> issue = found.get();
        if (!QuoteIssues.Type.STUCK_VERSION.name().equals(Attr.string(issue, "issueType"))) {
            return false;
        }
        String quoteId = Attr.string(issue, "quoteId");
        int version = (int) Attr.number(issue, "version", 0);
        if (quoteId == null || version < 1 || stillActive(quoteId, version)) {
            return false;
        }
        boolean resolved = table.resolveIssue(issue, "A versão " + version
                + " terminou depois de passar do prazo; a ocorrência foi resolvida.", now);
        if (resolved) {
            LOG.infof("STUCK_VERSION resolved: quoteId=%s version=%d issueId=%s", quoteId, version, issueId);
        }
        return resolved;
    }

    private boolean stillActive(String quoteId, int version) {
        Optional<Map<String, AttributeValue>> versionItem =
                table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version));
        return versionItem.isPresent()
                && !Phase.TERMINAL.name().equals(Attr.string(versionItem.get(), "phase"))
                && QuoteKeys.ACTIVE_VERSION.equals(Attr.string(versionItem.get(), QuoteKeys.GSI2_PK));
    }
}
