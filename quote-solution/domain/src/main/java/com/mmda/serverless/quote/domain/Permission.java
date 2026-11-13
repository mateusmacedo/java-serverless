package com.mmda.serverless.quote.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Permissões do contrato e os grupos do Cognito que as concedem (§11 do
 * desenho). A organização vem do
 * grupo {@code org_<organizationId>}, nunca do body.
 */
public enum Permission {
    QUOTES_READ("quotes:read", "perm_quotes_read"),
    QUOTES_WRITE("quotes:write", "perm_quotes_write"),
    OFFERS_SELECT("offers:select", "perm_offers_select"),
    OPERATIONS_READ("operations:read", "perm_operations_read"),
    OPERATIONS_RECONCILE("operations:reconcile", "perm_operations_reconcile");

    public static final String ORGANIZATION_GROUP_PREFIX = "org_";

    private final String code;
    private final String group;

    Permission(String code, String group) {
        this.code = code;
        this.group = group;
    }

    public String code() {
        return code;
    }

    public String group() {
        return group;
    }

    public static List<Permission> fromGroups(Set<String> groups) {
        return Arrays.stream(values()).filter(permission -> groups.contains(permission.group)).toList();
    }

    /**
     * Organização do usuário. WHY: mais de um grupo de organização é configuração
     * ambígua; sem decidir por
     * ordem, o acesso é negado.
     */
    public static Optional<String> organizationFromGroups(Set<String> groups) {
        List<String> organizations = groups.stream()
                .filter(group -> group.startsWith(ORGANIZATION_GROUP_PREFIX)
                        && group.length() > ORGANIZATION_GROUP_PREFIX.length())
                .map(group -> group.substring(ORGANIZATION_GROUP_PREFIX.length()))
                .toList();
        return organizations.size() == 1 ? Optional.of(organizations.getFirst()) : Optional.empty();
    }
}
