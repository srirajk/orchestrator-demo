package com.openwolf.iam.service;

import com.openwolf.iam.exception.AuthzDeniedException;
import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.CheckResult;
import dev.cerbos.sdk.builders.AttributeValue;
import dev.cerbos.sdk.builders.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Wraps the Cerbos PDP for IAM-internal authorization checks.
 * <p>
 * Design decisions:
 * <ul>
 *   <li>If {@code iam.cerbos.authz-enabled=false}: logs WARN and returns (dev mode).</li>
 *   <li>On gRPC transport errors: logs ERROR and <strong>fails-open</strong>
 *       (allows). Cerbos sidecar restarts should not break IAM availability.</li>
 *   <li>On Cerbos DENY: throws {@link AuthzDeniedException} (maps to HTTP 403).</li>
 * </ul>
 * </p>
 */
@Service
public class CerbosAuthzService {

    private static final Logger log = LoggerFactory.getLogger(CerbosAuthzService.class);

    @Value("${iam.cerbos.authz-enabled:true}")
    private boolean authzEnabled;

    /**
     * Optional — absent when {@code iam.cerbos.authz-enabled=false} (CerbosConfig is skipped).
     */
    @Autowired(required = false)
    private CerbosBlockingClient cerbosClient;

    /**
     * Checks whether the actor is allowed to perform {@code action} on the given resource.
     *
     * @param actorId       principal ID of the caller
     * @param actorRoles    roles assigned to the caller
     * @param actorAttrs    actor attribute map (e.g. classification, segments)
     * @param resourceKind  Cerbos resource kind (e.g. "user", "policy", "relationship")
     * @param resourceId    resource identifier
     * @param resourceAttrs resource attribute map
     * @param action        action to check (e.g. "read", "create", "delete")
     * @throws AuthzDeniedException if Cerbos denies the action
     */
    public void checkAllowed(String actorId, List<String> actorRoles,
                             Map<String, Object> actorAttrs,
                             String resourceKind, String resourceId,
                             Map<String, Object> resourceAttrs,
                             String action) {
        if (!authzEnabled) {
            log.warn("Cerbos authz DISABLED — skipping check: actor={} action={} resource={}/{}",
                    actorId, action, resourceKind, resourceId);
            return;
        }

        if (cerbosClient == null) {
            log.error("Cerbos authz enabled but CerbosBlockingClient bean is absent — allowing by fail-open");
            return;
        }

        try {
            // Build Cerbos principal
            dev.cerbos.sdk.builders.Principal cerbosPrincipal =
                    dev.cerbos.sdk.builders.Principal.newInstance(actorId,
                            actorRoles.toArray(String[]::new));

            if (actorAttrs != null) {
                for (Map.Entry<String, Object> entry : actorAttrs.entrySet()) {
                    cerbosPrincipal = cerbosPrincipal.withAttribute(
                            entry.getKey(),
                            AttributeValue.stringValue(String.valueOf(entry.getValue()))
                    );
                }
            }

            // Build Cerbos resource (SDK 0.12.x: Resource replaces ResourceSet)
            Resource resource = Resource.newInstance(resourceKind, resourceId);
            if (resourceAttrs != null) {
                for (Map.Entry<String, Object> entry : resourceAttrs.entrySet()) {
                    resource = resource.withAttribute(
                            entry.getKey(),
                            AttributeValue.stringValue(String.valueOf(entry.getValue()))
                    );
                }
            }

            CheckResult result = cerbosClient.check(cerbosPrincipal, resource, action);

            if (!result.isAllowed(action)) {
                log.warn("Cerbos DENY: actor={} action={} resource={}/{}", actorId, action, resourceKind, resourceId);
                throw new AuthzDeniedException(
                        String.format("Access denied: '%s' cannot perform '%s' on %s/%s",
                                actorId, action, resourceKind, resourceId));
            }

            log.debug("Cerbos ALLOW: actor={} action={} resource={}/{}", actorId, action, resourceKind, resourceId);

        } catch (AuthzDeniedException ex) {
            throw ex; // rethrow — not a transport error
        } catch (RuntimeException ex) {
            // Cerbos sidecar may be down or restarting — fail-open for availability
            // (gRPC StatusRuntimeException is a RuntimeException; catching broadly avoids
            //  compile-time dependency on grpc-api which is runtime-scoped in the SDK pom)
            log.error("Cerbos check failed (failing-open): actor={} action={} resource={}/{} error={}",
                    actorId, action, resourceKind, resourceId, ex.getMessage(), ex);
        }
    }
}
